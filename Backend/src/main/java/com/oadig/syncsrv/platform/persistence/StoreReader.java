package com.oadig.syncsrv.platform.persistence;

import com.oadig.syncsrv.shared.Json;
import java.sql.*;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.Callable;

/** Planning reads one independent consistent snapshot and never acquires the writer monitor. */
final class StoreReader {
 private static final String QUERY="SELECT o.id,o.data,s.signature,CHAR_LENGTH(o.data) AS data_size FROM objects o LEFT JOIN object_signatures s ON s.kind=o.kind AND s.id=o.id WHERE o.kind=?";
 private final Store owner;private final PublicKey key;private final String url,user,password;
 private final ThreadLocal<Connection> view=new ThreadLocal<>();
 private final ThreadLocal<Long> bytes=ThreadLocal.withInitial(()->0L);
 StoreReader(Store owner,PublicKey key,String url,String user,String password){this.owner=owner;this.key=key;this.url=url;this.user=user;this.password=password;}
 interface Query<T>{T run(Connection db)throws Exception;}
 private <T>T query(Query<T> query)throws Exception{
  Connection db=view.get();if(db!=null)return query.run(db);
  synchronized(owner){return query.run(owner.readingConnection());}
 }
 boolean inSnapshot(){return view.get()!=null;}
 <T>T snapshot(Callable<T> work)throws Exception{
  if(inSnapshot())return work.call();
  try(Connection db=DriverManager.getConnection(url,user,password)){
   db.setReadOnly(true);db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);db.setAutoCommit(false);view.set(db);bytes.set(0L);
   try{return work.call();}finally{view.remove();bytes.remove();db.rollback();}
  }
 }
 <T>T get(String kind,String id,Class<T> type)throws Exception{return query(db->{
  try(PreparedStatement s=db.prepareStatement(QUERY+" AND o.id=?")){s.setString(1,kind);s.setString(2,id);s.setQueryTimeout(30);try(ResultSet rows=s.executeQuery()){return rows.next()?read(kind,rows,type):null;}}
 });}
 <T>List<T> list(String kind,Class<T> type)throws Exception{return query(db->{
  List<T> result=new ArrayList<>();try(PreparedStatement s=db.prepareStatement(QUERY+" ORDER BY o.id")){
   s.setString(1,kind);s.setQueryTimeout(30);try(ResultSet rows=s.executeQuery()){while(rows.next())result.add(read(kind,rows,type));}
  }return result;
 });}
 <T>Map<String,T> many(String kind,Collection<String> ids,Class<T> type)throws Exception{return query(db->{
  Map<String,T> result=new HashMap<>();List<String> keys=new ArrayList<>(new LinkedHashSet<>(ids));
  for(int offset=0;offset<keys.size();offset+=500){var batch=keys.subList(offset,Math.min(keys.size(),offset+500));
   try(PreparedStatement s=db.prepareStatement(QUERY+" AND o.id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")")){
    s.setString(1,kind);for(int i=0;i<batch.size();i++)s.setString(i+2,batch.get(i));s.setQueryTimeout(30);
    try(ResultSet rows=s.executeQuery()){while(rows.next())result.put(rows.getString(1),read(kind,rows,type));}
   }
  }return result;
 });}

 List<com.oadig.syncsrv.shared.Models.Run> runHeads(boolean restoreOnly)throws Exception{return query(db->{
  String sql=restoreOnly?QUERY+" AND SYNCSRV_FIELD(o.data,'kind')='RESTORE'":
   "SELECT id,data,signature,data_size FROM (SELECT o.id,o.data,s.signature,CHAR_LENGTH(o.data) AS data_size,ROW_NUMBER() OVER(PARTITION BY SYNCSRV_FIELD(o.data,'jobId'),SYNCSRV_FIELD(o.data,'kind') ORDER BY SYNCSRV_INSTANT(o.data,'startedAt') DESC,o.id) AS row_position FROM objects o LEFT JOIN object_signatures s ON s.kind=o.kind AND s.id=o.id WHERE o.kind=?) h WHERE row_position=1 OR SYNCSRV_FIELD(data,'status') IN('RUNNING','QUEUED','PAUSING')";
  List<com.oadig.syncsrv.shared.Models.Run> result=new ArrayList<>();
  try(PreparedStatement s=db.prepareStatement(sql)){s.setString(1,"run");s.setQueryTimeout(30);try(ResultSet rows=s.executeQuery()){while(rows.next())result.add(read("run",rows,com.oadig.syncsrv.shared.Models.Run.class));}}return result;
 });}
 private <T>T read(String kind,ResultSet row,Class<T> type)throws Exception{
  long maximum=Math.max(8L<<20,Math.min(64L<<20,Runtime.getRuntime().maxMemory()/16));
  if(row.getLong(4)>maximum/2)throw new java.io.IOException("单份元数据超过安全内存预算");
  String id=row.getString(1),data=row.getString(2),signature=row.getString(3);
  if(inSnapshot()){long size=Math.addExact(bytes.get(),2L*data.length());if(size>maximum)throw new java.io.IOException("规划数据超过安全内存预算，请缩小保留范围或增加堆内存后重试");bytes.set(size);}
  if(signature==null||!Store.validSignature(key,Store.objectHash(kind,id,data),signature))throw new SecurityException("元数据签名校验失败: "+kind+"/"+id);
  return Json.read(data,type);
 }
}
