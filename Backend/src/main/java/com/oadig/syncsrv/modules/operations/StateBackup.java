package com.oadig.syncsrv.modules.operations;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;

/** Offline, encrypted metadata and key recovery. File volumes B/C are recovered separately. */
public final class StateBackup {
  private static final List<String> TABLES=List.of("objects","object_signatures","audit");
  private static final List<String> KEYS=List.of("audit-private.key","audit-public.key","credentials.key");
  private static final byte[] MAGIC="SYNCSRV-STATE-1\n".getBytes(StandardCharsets.US_ASCII);
  private StateBackup() {}
  public static void command(String[] args) throws Exception {
    if(args.length!=3||!List.of("export","import","inspect").contains(args[1]))throw new IllegalArgumentException("state-backup export|import|inspect FILE; passphrase from stdin");
    var env=new StandardEnvironment();ConfigDataEnvironmentPostProcessor.applyTo(env);
    Path data=Path.of(env.getRequiredProperty("syncsrv.data")).toAbsolutePath();
    String url=com.oadig.syncsrv.platform.persistence.MetadataTools.url(data);
    String phrase=System.console()!=null?new String(System.console().readPassword("Recovery passphrase: ")):new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine();
    if(phrase==null||phrase.length()<16)throw new IllegalArgumentException("Recovery passphrase needs at least 16 characters");
    Path file=Path.of(args[2]).toAbsolutePath();
    String actor="os:"+Objects.toString(System.getenv("SUDO_USER"),System.getProperty("user.name"));
    if(args[1].equals("inspect")){
      Path temporary=Files.createTempFile("syncsrv-evidence-",".zip");
      try{decrypt(file,temporary,phrase);try(ZipFile zip=new ZipFile(temporary.toFile())){
        var proof=zip.getEntry("evidence.json");if(proof==null||proof.getSize()>16384)throw new SecurityException("Invalid backup evidence");
        System.out.println(new String(zip.getInputStream(proof).readAllBytes(),StandardCharsets.UTF_8));
      }}finally{Files.deleteIfExists(temporary);}return;
    }
    if(args[1].equals("export")){
      if(Files.exists(file))throw new IllegalArgumentException("Backup destination exists");
      try(Store verified=new Store(data);Connection db=DriverManager.getConnection(url,"sa","")){
        verified.commit(actor,"state.backup_export_started","state",Map.of("filename",file.getFileName().toString()));
        db.setReadOnly(true);db.setAutoCommit(false);
        exportState(db,data,file,phrase,verified.header(verified.verify()));
        verified.commit(actor,"state.backup_export_completed","state",Map.of("sha256",fileHash(file),"bytes",Files.size(file)));
      }
    } else {
      importState(data,file,phrase);
      try(Store restored=new Store(data)){
        restored.commit(actor,"state.backup_imported","state",Map.of("sha256",fileHash(file)));
        RecoveryState.prepare(restored,actor);
      }
    }
    System.out.println("STATE_"+args[1].toUpperCase(Locale.ROOT)+"_COMPLETE");
  }
  private static Cipher cipher(int mode,String phrase,byte[] salt,byte[] nonce)throws Exception{
    var spec=new PBEKeySpec(phrase.toCharArray(),salt,600000,256);
    byte[] key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();spec.clearPassword();
    Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));Arrays.fill(key,(byte)0);c.updateAAD(MAGIC);return c;
  }
  static void exportState(Connection db,Path data,Path file,String phrase,EvidenceHeader evidence)throws Exception{
    byte[] salt=new byte[16],nonce=new byte[12];new SecureRandom().nextBytes(salt);new SecureRandom().nextBytes(nonce);
    Path temp=file.resolveSibling(file.getFileName()+".incomplete");
    try {
      try(var raw=Files.newOutputStream(temp,StandardOpenOption.CREATE_NEW)){
        if(Files.getFileStore(temp).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        raw.write(MAGIC);raw.write(salt);raw.write(nonce);
        try(var zip=new ZipOutputStream(new CipherOutputStream(raw,cipher(Cipher.ENCRYPT_MODE,phrase,salt,nonce)))){
          entry(zip,"evidence.json",Json.bytes(evidence));
          for(String key:KEYS)entry(zip,key,Files.readAllBytes(data.resolve(key)));
          for(String table:TABLES){
            zip.putNextEntry(new ZipEntry(table+".jsonl"));
            try(Statement s=db.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
              s.setFetchSize(500);
              try(ResultSet rows=s.executeQuery("SELECT * FROM "+table+(table.equals("audit")?" ORDER BY seq":""))){
                var meta=rows.getMetaData();
                while(rows.next()){
                  Map<String,String> values=new LinkedHashMap<>();for(int i=1;i<=meta.getColumnCount();i++)values.put(meta.getColumnName(i).toLowerCase(Locale.ROOT),rows.getString(i));
                  zip.write((Json.write(values)+"\n").getBytes(StandardCharsets.UTF_8));
                }
              }
            }
            zip.closeEntry();
          }
        }
      }
      try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
      Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);
      forceDirectory(file.getParent());
    }finally{Files.deleteIfExists(temp);}
  }
  private static void forceDirectory(Path path){try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.READ)){channel.force(true);}catch(Exception ignored){/* Directory fsync is unavailable on Windows. */}}
  private static void entry(ZipOutputStream zip,String name,byte[] data)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
  static void importState(Path data,Path file,String phrase)throws Exception{
    Files.createDirectories(data);
    for(String key:KEYS)if(Files.exists(data.resolve(key)))throw new IllegalStateException("Import requires an empty recovery data directory");
    Path temp=Files.createTempFile(data,".decrypt-",".zip");
    if(Files.getFileStore(temp).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    try {
      // Fully authenticate encrypted input before making any database changes.
      decrypt(file,temp,phrase);
      try(var instanceChannel=java.nio.channels.FileChannel.open(data.resolve("instance.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
          var lock=instanceChannel.tryLock();ZipFile zip=new ZipFile(temp.toFile())){
        if(lock==null)throw new IllegalStateException("Center still running");
        try(Connection db=DriverManager.getConnection(com.oadig.syncsrv.platform.persistence.MetadataTools.url(data),"sa","")){
        com.oadig.syncsrv.platform.persistence.H2Database.initialize(db);
        for(String table:TABLES)try(Statement s=db.createStatement();ResultSet rows=s.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();if(rows.getLong(1)!=0)throw new IllegalStateException("Recovery database must be empty");}
        Set<String> expected=new HashSet<>(KEYS);expected.add("evidence.json");for(String table:TABLES)expected.add(table+".jsonl");
        var names=zip.stream().map(ZipEntry::getName).toList();if(names.size()!=expected.size()||!new HashSet<>(names).equals(expected))throw new SecurityException("Invalid recovery archive members");
        PublicKey key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(zip.getInputStream(zip.getEntry("audit-public.key")).readAllBytes()));
        EvidenceHeader evidence=Json.read(new String(zip.getInputStream(zip.getEntry("evidence.json")).readAllBytes(),StandardCharsets.UTF_8),EvidenceHeader.class);
        if(!Store.validSignature(key,Ids.hash(Json.bytes(evidence.head())),evidence.signature()))throw new SecurityException("Backup evidence signature rejected");
        db.setAutoCommit(false);
        boolean attemptedCommit=false;
        try {
          for(String table:TABLES)importTable(db,zip,table);
          validate(db,key,evidence.head());
          // Stage key files before commit; rollback database on any filesystem failure.
          for(String name:KEYS){
            Files.copy(zip.getInputStream(zip.getEntry(name)),data.resolve(name));
            if(Files.getFileStore(data).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(data.resolve(name),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
          }
          for(String name:KEYS)try(var channel=java.nio.channels.FileChannel.open(data.resolve(name),StandardOpenOption.WRITE)){channel.force(true);}
          forceDirectory(data);attemptedCommit=true;db.commit();
        }catch(Exception e){db.rollback();if(!attemptedCommit)for(String name:KEYS)Files.deleteIfExists(data.resolve(name));throw e;}
      }
      }
    }finally{Files.deleteIfExists(temp);}
  }
  private static void decrypt(Path file,Path target,String phrase)throws Exception{
    if(Files.getFileStore(target).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(target,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    try(var in=Files.newInputStream(file);var out=Files.newOutputStream(target)){
      if(!Arrays.equals(MAGIC,in.readNBytes(MAGIC.length)))throw new SecurityException("Invalid backup format");
      byte[] salt=in.readNBytes(16),nonce=in.readNBytes(12);
      try(var encrypted=new CipherInputStream(in,cipher(Cipher.DECRYPT_MODE,phrase,salt,nonce))){encrypted.transferTo(out);}
    }
  }
  private static String fileHash(Path path)throws Exception{
    var digest=Ids.digest();try(var in=new DigestInputStream(Files.newInputStream(path),digest)){in.transferTo(OutputStream.nullOutputStream());}return HexFormat.of().formatHex(digest.digest());
  }
  private static void importTable(Connection db,ZipFile zip,String table)throws Exception{
    List<String> columns=switch(table){case "objects"->List.of("kind","id","data");case "object_signatures"->List.of("kind","id","signature");default->List.of("seq","time","actor","action","subject","detail","previous","hash","signature");};
    try(var reader=new BufferedReader(new InputStreamReader(zip.getInputStream(zip.getEntry(table+".jsonl")),StandardCharsets.UTF_8));
        var insert=db.prepareStatement("INSERT INTO "+table+"("+String.join(",",columns)+") VALUES("+String.join(",",Collections.nCopies(columns.size(),"?"))+")")){
      String line;int pending=0;while((line=reader.readLine())!=null){var row=Json.MAPPER.readTree(line);for(int i=0;i<columns.size();i++)insert.setString(i+1,row.path(columns.get(i)).asText());insert.addBatch();if(++pending%500==0)insert.executeBatch();}if(pending%500!=0)insert.executeBatch();
    }
  }
  private static void validate(Connection db,PublicKey key,AuditHead head)throws Exception{
    try(Statement s=db.createStatement();ResultSet rows=s.executeQuery("SELECT o.kind,o.id,o.data,s.signature FROM objects o LEFT JOIN object_signatures s ON s.kind=o.kind AND s.id=o.id")){
      while(rows.next())if(rows.getString(4)==null||!Store.validSignature(key,Ids.textHash(rows.getString(1)+"\n"+rows.getString(2)+"\n"+rows.getString(3)),rows.getString(4)))throw new SecurityException("Recovered metadata signature invalid");
    }
    long seq=0;String previous="";
    try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM audit ORDER BY seq")){
      while(r.next()){
        AuditEvent e=new AuditEvent(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9));
        if(e.sequence()!=seq+1||!e.previous().equals(previous)||!Store.eventHash(e).equals(e.hash())||!Store.validSignature(key,e.hash(),e.signature()))throw new SecurityException("Recovered audit chain invalid");seq=e.sequence();previous=e.hash();
      }
    }
    if(seq!=head.sequence()||!previous.equals(head.head()))throw new SecurityException("Recovered head mismatch");
  }
}
