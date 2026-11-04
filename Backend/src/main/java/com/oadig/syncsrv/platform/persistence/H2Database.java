package com.oadig.syncsrv.platform.persistence;

import java.sql.*;

/** Embedded H2 schema and durable-write settings; Store owns the data directory lock. */
public final class H2Database {
  private H2Database() {}
  public static void initialize(Connection db) throws SQLException {
    try (Statement s = db.createStatement()) {
      s.execute("SET WRITE_DELAY 0");
      s.execute("CREATE ALIAS IF NOT EXISTS SYNCSRV_FIELD FOR \"com.oadig.syncsrv.platform.persistence.HistoryFields.value\"");
      s.execute("CREATE ALIAS IF NOT EXISTS SYNCSRV_INSTANT FOR \"com.oadig.syncsrv.platform.persistence.HistoryFields.instant\"");
      s.execute("CREATE TABLE IF NOT EXISTS schema_version(id INT PRIMARY KEY, version INT NOT NULL)");
      // Never repair or overwrite an existing schema version silently.
      try (ResultSet v = s.executeQuery("SELECT version FROM schema_version WHERE id=1")) {
        if (v.next()) {
          if (v.getInt(1) != 1) throw new IllegalStateException("数据库结构版本不匹配");
          return;
        }
      }
      s.execute("CREATE TABLE IF NOT EXISTS objects(kind VARCHAR(32),id VARCHAR(64),data CLOB NOT NULL,PRIMARY KEY(kind,id))");
      s.execute("CREATE TABLE IF NOT EXISTS object_signatures(kind VARCHAR(32),id VARCHAR(64),signature VARCHAR(128) NOT NULL,PRIMARY KEY(kind,id))");
      s.execute("CREATE TABLE IF NOT EXISTS audit(seq BIGINT PRIMARY KEY,time VARCHAR(40) NOT NULL,actor VARCHAR(128) NOT NULL,action VARCHAR(96) NOT NULL,subject VARCHAR(128) NOT NULL,detail CLOB NOT NULL,previous VARCHAR(64) NOT NULL,hash VARCHAR(64) NOT NULL,signature VARCHAR(128) NOT NULL)");
      s.execute("CREATE INDEX IF NOT EXISTS idx_audit_action_seq ON audit(action,seq)");
      s.execute("CREATE INDEX IF NOT EXISTS idx_audit_subject_seq ON audit(subject,seq)");
      s.execute("INSERT INTO schema_version VALUES(1,1)");
    }
  }
  static void configure(Connection db) throws SQLException {
    try (Statement s = db.createStatement()) { s.execute("SET WRITE_DELAY 0"); }
  }
}
