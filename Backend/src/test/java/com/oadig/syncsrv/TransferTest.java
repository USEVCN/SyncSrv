package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TransferTest {
  @TempDir Path root;
  private Store memory;
  private final Map<String, Object> records = new HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    memory = mock(Store.class);
    when(memory.get(anyString(), anyString(), eq(Transfer.class)))
        .thenAnswer(i -> records.get(i.getArgument(0) + ":" + i.getArgument(1)));
    doAnswer(
            i -> {
              for (Object value : i.getArguments())
                if (value instanceof Store.Change c) {
                  if (c.delete()) records.remove(c.kind() + ":" + c.id());
                  else records.put(c.kind() + ":" + c.id(), c.value());
                }
              return null;
            })
        .when(memory)
        .commit(anyString(), anyString(), anyString(), any(), any(Store.Change[].class));
  }

  private TaskContext task() {
    Run r = new Run();
    r.id = Ids.next();
    r.actor = "test";
    return new TaskContext(r, new BandwidthLimiter(0), 0);
  }

  @Test
  void interruptedTransferResumesVerifiedChunk() throws Exception {
    byte[] data = new byte[TransferService.CHUNK_SIZE + 4096];
    new Random(81).nextBytes(data);
    Path a = Files.createDirectory(root.resolve("A")), b = Files.createDirectory(root.resolve("B"));
    Files.write(a.resolve("big"), data);
    try (Storage source = new LocalStorage(a);
        Storage target = new LocalStorage(b)) {
      TransferService service = new TransferService(memory);
      String hash = Ids.hash(data);
      Storage failing =
          new Delegate(target) {
            boolean fail = true;

            public void write(String p, long offset, InputStream in, long length) throws Exception {
              if (offset > 0 && fail) {
                fail = false;
                throw new IOException("simulated disconnect");
              }
              super.write(p, offset, in, length);
            }
          };
      assertThrows(
          IOException.class,
          () ->
              service.copy(
                  source, "big", failing, "big.part", data.length, hash, "resume", task()));
      assertEquals(TransferService.CHUNK_SIZE, Files.size(b.resolve("big.part")));
      assertFalse(records.isEmpty());
      TaskContext resumed = task();
      resumed.stage("ARCHIVING", "big");
      int[] resumeReads = {0};
      boolean[] sawProgress = {false};
      Storage measured = new Delegate(target) {
        public InputStream read(String p, long offset) throws Exception {
          return new FilterInputStream(super.read(p, offset)) {
            public int read(byte[] buffer, int start, int length) throws IOException {
              if (resumed.run.phase.equals("VERIFYING_RESUME") && length > 0) {
                assertEquals(256 * 1024, length);
                assertEquals(TransferService.CHUNK_SIZE, resumed.run.phaseTotal);
                sawProgress[0] |= resumed.run.phaseBytes > 0;
                resumeReads[0]++;
              }
              return super.read(buffer, start, length);
            }
          };
        }
      };
      service.copy(source, "big", measured, "big.part", data.length, hash, "resume", resumed);
      assertEquals(32, resumeReads[0], "8 MiB must not require 1024 small SMB reads");
      assertTrue(sawProgress[0], "verification must become visible before copying starts");
      assertEquals("ARCHIVING", resumed.run.phase);
      assertEquals("big", resumed.run.currentPath);
      assertEquals(0, resumed.run.phaseTotal);
      assertEquals(TransferService.CHUNK_SIZE + 4096L + data.length, resumed.run.verifiedBytes);
      assertArrayEquals(data, Files.readAllBytes(b.resolve("big.part")));
      assertEquals(4096, resumed.run.bytes);
      assertTrue(records.isEmpty());
    }
  }

  @Test
  void corruptedCheckpointRestartsInsteadOfTrustingLength() throws Exception {
    byte[] data = new byte[TransferService.CHUNK_SIZE + 9];
    new Random(11).nextBytes(data);
    Path a = Files.createDirectory(root.resolve("A")), b = Files.createDirectory(root.resolve("B"));
    Files.write(a.resolve("x"), data);
    try (Storage source = new LocalStorage(a);
        Storage target = new LocalStorage(b)) {
      TransferService service = new TransferService(memory);
      Storage failing =
          new Delegate(target) {
            public void write(String p, long offset, InputStream in, long length) throws Exception {
              if (offset > 0) throw new IOException("offline");
              super.write(p, offset, in, length);
            }
          };
      assertThrows(
          IOException.class,
          () ->
              service.copy(
                  source, "x", failing, "part", data.length, Ids.hash(data), "corrupt", task()));
      try (RandomAccessFile f = new RandomAccessFile(b.resolve("part").toFile(), "rw")) {
        f.write(99);
      }
      TaskContext retry = task();
      service.copy(source, "x", target, "part", data.length, Ids.hash(data), "corrupt", retry);
      assertEquals(data.length, retry.run.bytes);
      assertArrayEquals(data, Files.readAllBytes(b.resolve("part")));
    }
  }

  @Test
  void sameSizeChangedSourceCannotCommit() throws Exception {
    Path a = Files.createDirectory(root.resolve("A")), b = Files.createDirectory(root.resolve("B"));
    Files.writeString(a.resolve("x"), "later");
    try (Storage source = new LocalStorage(a);
        Storage target = new LocalStorage(b)) {
      assertThrows(
          IOException.class,
          () ->
              new TransferService(memory)
                  .copy(source, "x", target, "part", 5, Ids.textHash("first"), "changed", task()));
    }
  }

  @Test
  void pauseDuringResumeVerificationKeepsCheckpointAndWritesNothing() throws Exception {
    byte[] data = new byte[TransferService.CHUNK_SIZE + 15];
    new Random(73).nextBytes(data);
    Path a = Files.createDirectory(root.resolve("A")), b = Files.createDirectory(root.resolve("B"));
    Files.write(a.resolve("x"), data);
    byte[] prefix = Arrays.copyOf(data, TransferService.CHUNK_SIZE);
    Files.write(b.resolve("part"), prefix);
    String hash = Ids.hash(data);
    String id = TransferService.checkpointId("pause", "x", "part", hash);
    String chain = Ids.textHash("\n0\n" + prefix.length + "\n" + Ids.hash(prefix));
    records.put("transfer:" + id, new Transfer(id, hash, data.length, prefix.length, chain));
    TaskContext paused = task();
    paused.stage("ARCHIVING", "x");
    try (Storage source = new LocalStorage(a); Storage target = new LocalStorage(b)) {
      Storage cancelling = new Delegate(target) {
        public InputStream read(String p, long offset) throws Exception {
          return new FilterInputStream(super.read(p, offset)) {
            public int read(byte[] buffer, int start, int length) throws IOException {
              int n = super.read(buffer, start, length);
              if (n > 0) paused.cancelled.set(true);
              return n;
            }
          };
        }
      };
      assertThrows(InterruptedIOException.class, () -> new TransferService(memory)
          .copy(source, "x", cancelling, "part", data.length, hash, "pause", paused));
      assertTrue(records.containsKey("transfer:" + id));
      assertEquals(0, paused.run.bytes);
      assertArrayEquals(prefix, Files.readAllBytes(b.resolve("part")));
      assertEquals("ARCHIVING", paused.run.phase);
    }
  }

  @Test
  void shortReadsStillProduceCompleteVerifiedChunks() throws Exception {
    byte[] data = new byte[TransferService.CHUNK_SIZE + 1];
    new Random(39).nextBytes(data);
    Path a = Files.createDirectory(root.resolve("A")), b = Files.createDirectory(root.resolve("B"));
    Files.write(a.resolve("x"), data);
    try (Storage source = new LocalStorage(a); Storage target = new LocalStorage(b)) {
      TaskContext task = task();
      new TransferService(memory).copy(shortReads(source), "x", shortReads(target), "part",
          data.length, Ids.hash(data), "short", task);
      assertArrayEquals(data, Files.readAllBytes(b.resolve("part")));
      assertEquals(data.length, task.run.bytes);
      assertEquals(2L * data.length, task.run.verifiedBytes);
    }
  }

  private Storage shortReads(Storage storage) {
    return new Delegate(storage) {
      public InputStream read(String p, long offset) throws Exception {
        return new FilterInputStream(super.read(p, offset)) {
          public int read(byte[] buffer, int start, int length) throws IOException {
            return super.read(buffer, start, Math.min(length, 97 * 1024));
          }
        };
      }
    };
  }

  @Test
  void limiterHonorsSmallRateAndCancellation() throws Exception {
    TaskContext task = task();
    BandwidthLimiter limiter = new BandwidthLimiter(0.5);
    long start = System.nanoTime();
    limiter.acquire(256 * 1024, task);
    limiter.acquire(256 * 1024, task);
    assertTrue((System.nanoTime() - start) / 1e9 >= 0.40);
    task.cancelled.set(true);
    assertThrows(InterruptedIOException.class, () -> limiter.acquire(10, task));
  }

  @Test
  void unsafePathsAndSymlinksAreRejected() throws Exception {
    Path a = Files.createDirectory(root.resolve("A"));
    Files.createSymbolicLink(a.resolve("outside"), root);
    try (Storage s = new LocalStorage(a)) {
      assertThrows(Exception.class, () -> s.read("../secret", 0));
      assertThrows(Exception.class, () -> s.read("outside/secret", 0));
      assertThrows(Exception.class, () -> s.mkdirs("x/../../bad"));
    }
  }

  static class Delegate implements Storage {
    final Storage delegate;

    Delegate(Storage d) {
      delegate = d;
    }

    public Item stat(String p) throws Exception {
      return delegate.stat(p);
    }

    public List<Item> list(String p) throws Exception {
      return delegate.list(p);
    }

    public InputStream read(String p, long offset) throws Exception {
      return delegate.read(p, offset);
    }

    public void write(String p, long offset, InputStream in, long length) throws Exception {
      delegate.write(p, offset, in, length);
    }

    public void mkdirs(String p) throws Exception {
      delegate.mkdirs(p);
    }

    public void move(String a, String b) throws Exception {
      delegate.move(a, b);
    }

    public void delete(String p, boolean d) throws Exception {
      delegate.delete(p, d);
    }

    public Capabilities capabilities() {
      return delegate.capabilities();
    }

    public void close() {}
  }
}
