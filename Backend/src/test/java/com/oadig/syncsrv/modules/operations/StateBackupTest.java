package com.oadig.syncsrv.modules.operations;
import static org.junit.jupiter.api.Assertions.*;
import static com.oadig.syncsrv.platform.persistence.Store.Change.*;
import com.oadig.syncsrv.platform.persistence.*;
import java.nio.file.*;import java.sql.*;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
class StateBackupTest {
 @TempDir Path root;
 Path backup()throws Exception{
  Path data=root.resolve("source"),file=root.resolve("state.enc");
  try(Store store=new Store(data);Connection db=DriverManager.getConnection(MetadataTools.url(data),"sa","")){
   store.commit("named-operator","fixture.saved","中文",Map.of(),put("sample","one",Map.of("path","目录/😀.txt")));
   db.setAutoCommit(false);StateBackup.exportState(db,data,file,"strong-recovery-passphrase",store.header(store.verify()));
  }return file;
 }
 @Test void encryptedRecoveryPreservesSigningIdentityAndDocuments()throws Exception{
  Path file=backup(),data=root.resolve("restored");StateBackup.importState(data,file,"strong-recovery-passphrase");
  try(Store store=new Store(data)){
   assertEquals(1,store.verify().sequence());assertEquals("目录/😀.txt",store.get("sample","one",Map.class).get("path"));
   assertArrayEquals(Files.readAllBytes(root.resolve("source/audit-public.key")),Files.readAllBytes(data.resolve("audit-public.key")));
  }
 }
 @Test void wrongPasswordAndCorruptionCannotCreateDatabase()throws Exception{
  Path file=backup(),data=root.resolve("rejected");assertThrows(Exception.class,()->StateBackup.importState(data,file,"wrong-recovery-passphrase"));
  byte[] raw=Files.readAllBytes(file);raw[raw.length-1]^=1;Path bad=root.resolve("bad.enc");Files.write(bad,raw);
  assertThrows(Exception.class,()->StateBackup.importState(data,bad,"strong-recovery-passphrase"));
  assertFalse(Files.exists(data.resolve("metadata.mv.db")));assertFalse(Files.exists(data.resolve("credentials.key")));
 }
 @Test void nonemptyTargetIsNeverOverwritten()throws Exception{
  Path file=backup(),data=root.resolve("target");try(Store store=new Store(data)){store.commit("test","keep","existing",Map.of(),put("sample","keep",Map.of("safe",true)));}
  assertThrows(Exception.class,()->StateBackup.importState(data,file,"strong-recovery-passphrase"));
  try(Store store=new Store(data)){assertEquals(Boolean.TRUE,store.get("sample","keep",Map.class).get("safe"));}
 }
}
