package com.oadig.syncsrv.platform.storage;

import com.oadig.syncsrv.shared.Json;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class LocalStorage implements Storage {
  private final Path root;
  private final NativeLocalIo io;
  public LocalStorage(Path root) throws Exception { this.root = root.toRealPath(); io = new NativeLocalIo(this.root); }
  public Path root() { return root; }
  private Map<String,Object> request(String op, String path) {
    StoragePaths.relative(path); return new HashMap<>(Map.of("op",op,"path",path));
  }
  private int open(String op,String path,long offset) throws Exception {
    var q=request(op,path); q.put("offset",offset); return io.call(q).json().path("handle").asInt();
  }
  private void closeHandle(int handle) throws Exception { io.call(Map.of("op","close","handle",handle)); }
  @Override public Item stat(String path) throws Exception {
    try { return Json.MAPPER.treeToValue(io.call(request("stat",path)).json().get("item"),Item.class); }
    catch (NoSuchFileException e) { return null; }
  }
  @Override public List<Item> list(String path) throws Exception {
    int h=open("list-open",path,0);
    try {
      List<Item> result=new ArrayList<>();
      for(;;) {
        var r=io.call(Map.of("op","list-next","handle",h)).json();
        for(var n:r.path("items"))result.add(Json.MAPPER.treeToValue(n,Item.class));
        if(r.path("end").asBoolean())return result;
      }
    } finally { closeHandle(h); }
  }
  @Override public InputStream read(String path,long offset) throws Exception {
    int handle=open("read-open",path,offset);
    return new InputStream() {
      private boolean closed;
      @Override public int read() throws IOException { byte[] b=new byte[1]; return read(b,0,1)<0?-1:Byte.toUnsignedInt(b[0]); }
      @Override public int read(byte[] b,int off,int length) throws IOException {
        Objects.checkFromIndexSize(off,length,b.length);
        if(closed)throw new IOException("文件流已关闭"); if(length==0)return 0;
        try {
          byte[] bytes=io.call(Map.of("op","read","handle",handle,"count",Math.min(length,NativeLocalIo.CHUNK))).body();
          if(bytes.length==0)return -1; System.arraycopy(bytes,0,b,off,bytes.length); return bytes.length;
        } catch(Exception e) { throw new IOException("读取本地文件失败",e); }
      }
      @Override public void close() throws IOException {
        if(closed)return; closed=true; try { closeHandle(handle); } catch(Exception e) { throw new IOException("关闭本地文件失败",e); }
      }
    };
  }
  @Override public void write(String path,long offset,InputStream data,long length) throws Exception {
    mkdirs(StoragePaths.parent(path)); int handle=open("write-open",path,offset); Exception failure=null;
    try {
      long remaining=length;
      while(remaining!=0) {
        int count=remaining<0?NativeLocalIo.CHUNK:(int)Math.min(NativeLocalIo.CHUNK,remaining);
        byte[] body=data.readNBytes(count);
        if(body.length==0) { if(remaining>0)throw new EOFException("文件数据提前结束"); break; }
        io.call(Map.of("op","write","handle",handle,"count",body.length),body); if(remaining>0)remaining-=body.length;
      }
    } catch(Exception e) { failure=e; throw e; }
    finally { try { closeHandle(handle); } catch(Exception e) { if(failure!=null)failure.addSuppressed(e); else throw e; } }
  }
  @Override public void mkdirs(String path) throws Exception { io.call(request("mkdirs",path)); }
  @Override public void move(String from,String to) throws Exception {
    StoragePaths.relative(to); var q=request("move",from); q.put("to",to); io.call(q);
  }
  @Override public void delete(String path,boolean directory) throws Exception {
    var q=request("delete",path); q.put("directory",directory); io.call(q);
  }
  @Override public boolean modified(String path,long millis) throws Exception {
    var q=request("modified",path); q.put("modified",millis); io.call(q); return true;
  }
  @Override public Capabilities capabilities() { return new Capabilities(true,true,false,"支持偏移续传；本地操作绑定原生根目录句柄"); }
  @Override public void close() { io.close(); }
}
