package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageCopyTest {
  @TempDir Path root;

  private TaskContext task() {
    Run r = new Run();
    r.id = Ids.next();
    r.actor = "test";
    return new TaskContext(r, new BandwidthLimiter(0), 0);
  }

  private Storage nativeSource(Storage source, boolean unsupported, boolean interruptTail) {
    return new TransferTest.Delegate(source) {
      public boolean canCopyTo(Storage target) {
        return true;
      }

      public boolean copyRangeTo(String from, Storage target, String to, long offset, int length)
          throws Exception {
        if (unsupported) return false;
        if (interruptTail && offset > 0) throw new IOException("storage went offline");
        try (var in = delegate.read(from, offset)) {
          target.write(to, offset, in, length);
        }
        return true;
      }

      public InputStream read(String p, long offset) throws Exception {
        if (!unsupported)
          throw new AssertionError("file bytes must not be relayed by TransferService");
        return super.read(p, offset);
      }
    };
  }

  @Test
  void directCopyRetainsHashVerificationAuditAndLogicalProgress() throws Exception {
    var memory = new MemoryStore();
    byte[] data = new byte[TransferService.CHUNK_SIZE + 173];
    new Random(19).nextBytes(data);
    Path a = Files.createDirectory(root.resolve("A")), b = Files.createDirectory(root.resolve("B"));
    Files.write(a.resolve("x"), data);
    try (Storage source = new LocalStorage(a);
        Storage target = new LocalStorage(b)) {
      var t = task();
      new TransferService(memory.store)
          .copy(
              nativeSource(source, false, false),
              "x",
              target,
              "part",
              data.length,
              Ids.hash(data),
              "direct",
              t);
      assertArrayEquals(data, Files.readAllBytes(b.resolve("part")));
      assertEquals(0, t.run.bytes);
      assertEquals(data.length, t.metrics.snapshot().serverCopyBytes());
      assertEquals(data.length * 2L, t.run.verifiedBytes);
      assertTrue(memory.actions.contains("transfer.mode"));
      assertTrue(memory.actions.contains("transfer.verified"));
      assertTrue(memory.store.list("transfer", Transfer.class).isEmpty());
    }
  }

  @Test
  void interruptedDirectCopyCanResumeUsingVerifiedCheckpoint() throws Exception {
    var memory = new MemoryStore();
    byte[] data = new byte[TransferService.CHUNK_SIZE + 99];
    new Random(17).nextBytes(data);
    Path a = Files.createDirectory(root.resolve("A")), b = Files.createDirectory(root.resolve("B"));
    Files.write(a.resolve("x"), data);
    try (Storage source = new LocalStorage(a);
        Storage target = new LocalStorage(b)) {
      var service = new TransferService(memory.store);
      assertThrows(
          IOException.class,
          () ->
              service.copy(
                  nativeSource(source, false, true),
                  "x",
                  target,
                  "part",
                  data.length,
                  Ids.hash(data),
                  "direct",
                  task()));
      assertEquals(
          TransferService.CHUNK_SIZE,
          memory.store.list("transfer", Transfer.class).get(0).confirmed());
      var t = task();
      service.copy(
          nativeSource(source, false, false),
          "x",
          target,
          "part",
          data.length,
          Ids.hash(data),
          "direct",
          t);
      assertEquals(99, t.metrics.snapshot().serverCopyBytes());
      assertEquals(1, t.metrics.snapshot().resumeCount());
      assertArrayEquals(data, Files.readAllBytes(b.resolve("part")));
    }
  }

  @Test
  void unsupportedCopyFallsBackWithAuditAndVerification() throws Exception {
    var memory = new MemoryStore();
    Path a = Files.createDirectory(root.resolve("A")), b = Files.createDirectory(root.resolve("B"));
    Files.writeString(a.resolve("x"), "content");
    try (Storage source = new LocalStorage(a);
        Storage target = new LocalStorage(b)) {
      var t = task();
      new TransferService(memory.store)
          .copy(
              nativeSource(source, true, false),
              "x",
              target,
              "part",
              7,
              Ids.textHash("content"),
              "fallback",
              t);
      assertEquals("content", Files.readString(b.resolve("part")));
      assertEquals(7, t.run.bytes);
      assertEquals(0, t.metrics.snapshot().serverCopyBytes());
      assertTrue(memory.actions.contains("transfer.mode"));
    }
  }

  @Test
  void corruptedDirectCopyFailsWithoutLosingItsCheckpoint() throws Exception {
    var memory = new MemoryStore();
    Path a = Files.createDirectory(root.resolve("A")), b = Files.createDirectory(root.resolve("B"));
    Files.writeString(a.resolve("x"), "bad");
    try (Storage source = new LocalStorage(a);
        Storage target = new LocalStorage(b)) {
      assertThrows(
          IOException.class,
          () ->
              new TransferService(memory.store)
                  .copy(
                      nativeSource(source, false, false),
                      "x",
                      target,
                      "part",
                      3,
                      Ids.textHash("old"),
                      "corrupt",
                      task()));
      assertEquals(1, memory.store.list("transfer", Transfer.class).size());
      assertFalse(memory.actions.contains("transfer.verified"));
    }
  }
}
