package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Models.Endpoint;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.ftpserver.*;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.*;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ProtocolTest {
  @TempDir Path root;

  @Test
  void ftpServerOffsetReadWriteAndArchive() throws Exception {
    FtpServerFactory factory = new FtpServerFactory();
    ListenerFactory listener = new ListenerFactory();
    listener.setPort(0);
    listener.setServerAddress("127.0.0.1");
    factory.addListener("default", listener.createListener());
    BaseUser user = new BaseUser();
    user.setName("test");
    user.setPassword("fixture-only");
    user.setHomeDirectory(root.toString());
    user.setAuthorities(List.of(new WritePermission()));
    factory.getUserManager().save(user);
    FtpServer server = factory.createServer();
    server.start();
    try {
      Endpoint e = new Endpoint();
      e.protocol = "FTP";
      e.host = "127.0.0.1";
      e.port = factory.getListener("default").getPort();
      e.root = "/";
      e.username = "test";
      e.password = "fixture-only";
      try (Storage s = StorageFactory.open(e)) {
        contract(s);
      }
    } finally {
      server.stop();
    }
  }

  @Test
  void sftpStrictHostKeyAndOffsetContract() throws Exception {
    SshServer server = SshServer.setUpDefaultServer();
    server.setHost("127.0.0.1");
    server.setPort(0);
    SimpleGeneratorHostKeyProvider keys =
        new SimpleGeneratorHostKeyProvider(root.resolve("host.key"));
    keys.setAlgorithm("RSA");
    server.setKeyPairProvider(keys);
    Path files = Files.createDirectory(root.resolve("files"));
    server.setFileSystemFactory(new VirtualFileSystemFactory(files));
    server.setPasswordAuthenticator((u, p, s) -> u.equals("test") && p.equals("fixture-only"));
    server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
    server.start();
    try {
      Endpoint e = new Endpoint();
      e.protocol = "SFTP";
      e.host = "127.0.0.1";
      e.port = server.getPort();
      e.root = "/";
      e.username = "test";
      e.password = "fixture-only";
      e.hostKey = KeyUtils.getFingerPrint(keys.loadKeys(null).iterator().next().getPublic());
      try (Storage s = StorageFactory.open(e)) {
        contract(s);
      }
      e.hostKey = "SHA256:" + "A".repeat(43);
      assertThrows(Exception.class, () -> StorageFactory.open(e));
    } finally {
      server.stop(true);
    }
  }

  private void contract(Storage s) throws Exception {
    assertTrue(s.stat("").directory());
    s.write("read-contract", 0, new ByteArrayInputStream("abc".getBytes()), 3);
    try (InputStream in = s.read("read-contract", 0)) {
      assertEquals(0, in.read(new byte[0], 0, 0));
      assertArrayEquals("abc".getBytes(), in.readNBytes(3));
      assertEquals(0, in.read(new byte[0], 0, 0));
    }
    s.delete("read-contract", false);
    assertNull(s.stat("missing"));
    assertNull(s.stat("missing/child"));
    s.write(".hidden", 0, new ByteArrayInputStream(new byte[] {1}), 1);
    assertTrue(s.list("").stream().anyMatch(i -> i.name().equals(".hidden")));
    s.delete(".hidden", false);
    s.mkdirs("folder/nested");
    s.write("folder/file.txt", 0, new ByteArrayInputStream("first".getBytes()), 5);
    s.write("folder/file.txt", 5, new ByteArrayInputStream("-last".getBytes()), 5);
    assertEquals(10, s.stat("folder/file.txt").size());
    try (InputStream partial = s.read("folder/file.txt", 0)) {
      assertEquals('f', partial.read());
    }
    try (InputStream in = s.read("folder/file.txt", 5)) {
      assertEquals("-last", new String(in.readAllBytes()));
    }
    s.move("folder/file.txt", "folder/file.v1");
    assertNull(s.stat("folder/file.txt"));
    assertEquals(2, s.list("folder").size());
    assertThrows(Exception.class, () -> s.delete("folder", true));
    s.delete("folder/file.v1", false);
    s.delete("folder/nested", true);
    s.delete("folder", true);
  }
}
