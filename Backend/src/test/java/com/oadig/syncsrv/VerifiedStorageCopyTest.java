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

class VerifiedStorageCopyTest {
  @TempDir Path root;

  private TaskContext task() {
    Run r = new Run(); r.id = Ids.next(); r.actor = "test";
    return new TaskContext(r, new BandwidthLimiter(0), 0);
  }

  private final class Fixture implements AutoCloseable {
    final byte[] data = new byte[TransferService.CHUNK_SIZE + 173];
    final Path a, b;
    final Storage local, target, source;
    final MemoryStore memory = new MemoryStore();
    boolean failTail, corrupt, unsupported, opened;
    TaskContext pauseOnRead;
    int sessions, closes, ordinaryReads, truncations;
    long copied;

    Fixture() throws Exception {
      new Random(93).nextBytes(data);
      a = Files.createDirectory(root.resolve("A"));
      b = Files.createDirectory(root.resolve("B"));
      Files.write(a.resolve("x"), data);
      local = new LocalStorage(a); target = new LocalStorage(b);
      source = new TransferTest.Delegate(local) {
        public InputStream read(String path, long offset) throws Exception {
          assertFalse(opened, "fallback must release protected handles before reading");
          ordinaryReads++;
          return super.read(path, offset);
        }
        public CopySession openCopySession(String from, Storage destination, String to) throws Exception {
          sessions++; opened = true;
          var input = new RandomAccessFile(a.resolve(from).toFile(), "r");
          var output = new RandomAccessFile(b.resolve(to).toFile(), "rw");
          return new CopySession() {
            public long sourceSize() throws Exception { return input.length(); }
            public long targetSize() throws Exception { return output.length(); }
            public void truncate() throws Exception { output.setLength(0); truncations++; }
            public boolean copy(long offset, int count) throws Exception {
              if (unsupported) return false;
              if (failTail && offset > 0) throw new IOException("connection lost");
              byte[] block = new byte[count]; input.seek(offset); input.readFully(block);
              if (corrupt) block[0] ^= 1;
              output.seek(offset); output.write(block); copied += count; return true;
            }
            public InputStream read(long offset, int count) throws Exception {
              if (pauseOnRead != null) pauseOnRead.cancelled.set(true);
              byte[] block = new byte[count]; output.seek(offset); output.readFully(block);
              return new ByteArrayInputStream(block);
            }
            public void close() throws Exception {
              try (input; output) { opened = false; closes++; }
            }
          };
        }
      };
    }

    void copy(TaskContext t) throws Exception {
      new TransferService(memory.store).copy(source, "x", target, "part", data.length,
          Ids.hash(data), "protected", t);
    }
    Transfer checkpoint() throws Exception {
      return memory.store.list("transfer", Transfer.class).get(0);
    }
    void interrupt() throws Exception {
      failTail = true;
      assertThrows(IOException.class, () -> copy(task()));
      assertEquals(TransferService.CHUNK_SIZE, checkpoint().confirmed());
      failTail = false; copied = 0;
    }
    public void close() throws Exception { local.close(); target.close(); }
  }

  @Test
  void checkpointReadBackIsTheOnlyCompleteTargetRead() throws Exception {
    try (var f = new Fixture()) {
      var t = task(); f.copy(t);
      assertArrayEquals(f.data, Files.readAllBytes(f.b.resolve("part")));
      assertEquals(f.data.length, t.run.verifiedBytes);
      assertEquals(f.data.length, t.metrics.snapshot().serverCopyBytes());
      assertEquals(0, f.ordinaryReads);
      assertEquals(1, f.sessions); assertEquals(1, f.closes);
      assertTrue(f.memory.actions.contains("transfer.verified"));
      assertTrue(f.memory.store.list("transfer", Transfer.class).isEmpty());
    }
  }

  @Test
  void resumeHashesProtectedPrefixOnceAndCopiesOnlyTail() throws Exception {
    try (var f = new Fixture()) {
      f.interrupt();
      var t = task(); f.copy(t);
      assertEquals(173, f.copied);
      assertEquals(1, t.metrics.snapshot().resumeCount());
      assertEquals(f.data.length, t.run.verifiedBytes);
      assertEquals(1, f.truncations);
      assertEquals(2, f.closes);
      assertArrayEquals(f.data, Files.readAllBytes(f.b.resolve("part")));
      assertTrue(f.memory.store.list("transfer", Transfer.class).isEmpty());
    }
  }

  @Test
  void alteredPrefixRestartsAndResetsTheCompleteDigest() throws Exception {
    try (var f = new Fixture()) {
      f.interrupt();
      try (var changed = new RandomAccessFile(f.b.resolve("part").toFile(), "rw")) {
        changed.writeByte(f.data[0] ^ 1);
      }
      var t = task(); f.copy(t);
      assertEquals(f.data.length, f.copied);
      assertEquals(2, f.truncations);
      assertTrue(f.memory.actions.contains("transfer.restart"));
      assertArrayEquals(f.data, Files.readAllBytes(f.b.resolve("part")));
    }
  }

  @Test
  void corruptCopyNeverCommitsVerificationOrDiscardsCheckpoint() throws Exception {
    try (var f = new Fixture()) {
      f.corrupt = true;
      assertThrows(IOException.class, () -> f.copy(task()));
      assertEquals(f.data.length, f.checkpoint().confirmed());
      assertEquals(1, f.closes);
      assertFalse(f.memory.actions.contains("transfer.verified"));
      f.corrupt = false; f.copied = 0;
      f.copy(task());
      assertEquals(f.data.length, f.copied);
      assertTrue(f.memory.actions.contains("transfer.restart"));
      assertArrayEquals(f.data, Files.readAllBytes(f.b.resolve("part")));
    }
  }

  @Test
  void pauseDuringResumeRetainsCheckpointWithoutNewWrites() throws Exception {
    try (var f = new Fixture()) {
      f.interrupt();
      Transfer before = f.checkpoint();
      var t = task(); f.pauseOnRead = t;
      assertThrows(InterruptedIOException.class, () -> f.copy(t));
      assertEquals(before, f.checkpoint());
      assertEquals(0, f.copied);
      assertEquals(1, f.truncations);
      assertEquals(2, f.closes);
    }
  }

  @Test
  void unsupportedCopyReleasesHandlesBeforeVerifiedRelay() throws Exception {
    try (var f = new Fixture()) {
      f.unsupported = true;
      var t = task(); f.copy(t);
      assertEquals(1, f.closes); assertEquals(1, f.ordinaryReads);
      assertEquals(f.data.length, t.run.bytes);
      assertEquals(0, t.metrics.snapshot().serverCopyBytes());
      assertArrayEquals(f.data, Files.readAllBytes(f.b.resolve("part")));
      assertTrue(f.memory.actions.contains("transfer.verified"));
    }
  }
}
