package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Models.Endpoint;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "syncsrv.smb.python", matches = ".+")
class SmbStorageIntegrationTest {
  @TempDir Path root;

  @Test
  void signedPipelinedWritesAndOffsetReadsMatchLocalBytes() throws Exception {
    int port;
    try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    Path fixture = Path.of(getClass().getResource("/smb-fixture.py").toURI());
    Process server =
        new ProcessBuilder(
                System.getProperty("syncsrv.smb.python"),
                fixture.toString(),
                "--root",
                root.toString(),
                "--port",
                Integer.toString(port))
            .redirectErrorStream(true)
            .redirectOutput(root.resolve("smb.log").toFile())
            .start();
    try {
      long deadline = System.nanoTime() + 10_000_000_000L;
      while (true) {
        try (var socket = new Socket("127.0.0.1", port)) {
          break;
        } catch (IOException e) {
          if (System.nanoTime() > deadline) throw e;
          Thread.sleep(50);
        }
      }
      Endpoint e = new Endpoint();
      e.protocol = "SMB";
      e.host = "127.0.0.1";
      e.port = port;
      e.root = "test";
      e.username = "test";
      e.password = "fixture-only";
      byte[] first = new byte[6 * 1024 * 1024 + 73], tail = new byte[1024 * 1024 + 91];
      new Random(24).nextBytes(first);
      new Random(18).nextBytes(tail);
      try (Storage storage = new SmbStorage(e)) {
        storage.write("nested/data.bin", 0, new ByteArrayInputStream(first), first.length);
        assertArrayEquals(first, Files.readAllBytes(root.resolve("nested/data.bin")));
        storage.write("nested/data.bin", first.length, new ByteArrayInputStream(tail), tail.length);
        try (var input = storage.read("nested/data.bin", first.length)) {
          assertArrayEquals(tail, input.readAllBytes());
        }
        try (var input = storage.read("nested/data.bin", 17)) {
          assertArrayEquals(
              Arrays.copyOfRange(first, 17, first.length), input.readNBytes(first.length - 17));
        }
        assertThrows(
            IllegalArgumentException.class,
            () -> storage.write("nested/data.bin", 0, new ByteArrayInputStream(first), -1));
        assertEquals(first.length + tail.length, storage.stat("nested/data.bin").size());
        storage.write("empty", 0, new ByteArrayInputStream(new byte[0]), 0);
        try (var input = storage.read("empty", 0)) {
          assertEquals(-1, input.read());
          assertEquals(0, input.read(new byte[0]));
        }
      }
    } finally {
      server.destroy();
      if (!server.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) server.destroyForcibly();
    }
  }
}
