package com.oadig.syncsrv.platform.persistence;
import com.oadig.syncsrv.shared.Json;
import java.sql.*;
import java.security.*;
import java.util.*;

/** Independent consistent read snapshot; signed content remains authoritative. */
public final class HistoryReader {
  private final String url,user,password;
  private final PublicKey key;
  private volatile Map<String,Long> cachedCounts=Map.of();private volatile long countsUntil;
  public HistoryReader(String url,String user,String password,PublicKey key) {
    this.url=url;this.user=user;this.password=password;this.key=key;
  }
  public <T> List<T> recent(String kind,int limit,Class<T> type) throws Exception { return page(kind,limit,0,type); }
  public <T> List<T> page(String kind,int limit,int offset,Class<T> type) throws Exception {
    if(offset<0||offset>10000000) throw new IllegalArgumentException("Invalid offset");
    if(limit<1||limit>1000) throw new IllegalArgumentException("Page limit 1..1000");
    try(Connection db=DriverManager.getConnection(url,user,password)) {
      db.setReadOnly(true);
      try(PreparedStatement s=db.prepareStatement("SELECT o.id,o.data,s.signature FROM objects o LEFT JOIN object_signatures s ON s.kind=o.kind AND s.id=o.id WHERE o.kind=? ORDER BY COALESCE(SYNCSRV_INSTANT(o.data,'startedAt'),SYNCSRV_INSTANT(o.data,'createdAt'),SYNCSRV_INSTANT(o.data,'completedAt')) DESC,o.id LIMIT ? OFFSET ?")) {
        s.setString(1,kind);s.setInt(2,limit);s.setInt(3,offset);s.setQueryTimeout(15);
        List<T> result=new ArrayList<>();
        try(ResultSet rows=s.executeQuery()) { while(rows.next()) {
          String id=rows.getString(1),data=rows.getString(2),sig=rows.getString(3);
          if(sig==null || !Store.validSignature(key,Store.objectHash(kind,id,data),sig)) throw new SecurityException("历史元数据签名异常");
          result.add(Json.read(data,type));
        }}
        return result;
      }
    }
  }
  public com.oadig.syncsrv.shared.Models.SnapshotSummary nearest(String jobId,String before)throws Exception {
    java.time.Instant.parse(before);
    try(Connection db=DriverManager.getConnection(url,user,password)) {
      db.setReadOnly(true);
      try(PreparedStatement query=db.prepareStatement("SELECT o.id,o.data,s.signature FROM objects o JOIN object_signatures s ON s.kind=o.kind AND s.id=o.id WHERE o.kind='snapshot-summary' AND SYNCSRV_FIELD(o.data,'jobId')=? AND SYNCSRV_INSTANT(o.data,'completedAt')<=? ORDER BY SYNCSRV_INSTANT(o.data,'completedAt') DESC,o.id LIMIT 1")) {
        query.setString(1,jobId);query.setObject(2,java.time.Instant.parse(before).atOffset(java.time.ZoneOffset.UTC));query.setQueryTimeout(15);
        try(ResultSet row=query.executeQuery()) {
          if(!row.next())return null;
          String id=row.getString(1),data=row.getString(2),sig=row.getString(3);
          if(!Store.validSignature(key,Store.objectHash("snapshot-summary",id,data),sig))throw new SecurityException("恢复节点签名异常");
          return Json.read(data,com.oadig.syncsrv.shared.Models.SnapshotSummary.class);
        }
      }
    }
  }

  public Map<String,Long> counts()throws Exception {
    if(System.nanoTime()<countsUntil)return cachedCounts;
    synchronized(this){
      if(System.nanoTime()<countsUntil)return cachedCounts;
      Map<String,Long> counts=new HashMap<>();
      try(Connection db=DriverManager.getConnection(url,user,password);PreparedStatement query=db.prepareStatement("SELECT kind,COUNT(*) FROM objects WHERE kind IN('run','snapshot-summary') GROUP BY kind")){
        db.setReadOnly(true);query.setQueryTimeout(15);try(ResultSet rows=query.executeQuery()){while(rows.next())counts.put(rows.getString(1),rows.getLong(2));}
      }
      cachedCounts=Map.copyOf(counts);countsUntil=System.nanoTime()+30_000_000_000L;return cachedCounts;
    }
  }

}
