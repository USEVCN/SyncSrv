package main

import (
 "context"
 "encoding/json"
 "io"
 "net/http"
 "net/http/httptest"
 "strings"
 "testing"
 "time"
)

func TestTransferProgressDoesNotBlockReadsAndStops(t *testing.T) {
 received:=make(chan map[string]any,1)
 server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  var update map[string]any
  if err:=json.NewDecoder(r.Body).Decode(&update);err!=nil {t.Error(err);return}
  select {case received<-update:default:}
  <-r.Context().Done()
 }));defer server.Close()
 c:=&Client{identity:Identity{Server:server.URL,PrivateKey:make([]byte,64)},http:server.Client()}
 ctx,stop:=c.watchTransfer(context.Background(),"session")
 p:=sourceProgress(ctx);p.file("file","UPLOADING_B",10)
 reader:=&pacedReader{ctx:ctx,reader:strings.NewReader("abc"),onRead:func(n int){p.read(n,false)}}
 if _,err:=io.Copy(io.Discard,reader);err!=nil {t.Fatal(err)}
 p.read(7,true)
 select {case update:=<-received:
  if update["offset"]!=float64(13) || update["transferred"]!=float64(3) || update["verified"]!=float64(7) {t.Fatalf("unexpected counters: %v",update)}
 case <-time.After(3*time.Second):t.Fatal("no live report")}
 start:=time.Now();p.read(99,false)
 if time.Since(start)>100*time.Millisecond {t.Fatal("telemetry blocked a data read")}
 stop()
}
