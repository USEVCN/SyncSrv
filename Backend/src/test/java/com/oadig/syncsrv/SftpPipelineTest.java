package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.modules.transfer.*;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpPipelineTest {
  @TempDir Path root;
  static final int BLOCK = 64 * 1024;

  @Test void realServerSeesBoundedReadAheadAndPartialCloseKeepsSessionUsable() throws Exception {
    try (Fixture fixture = new Fixture(root)) {
      byte[] bytes = content(1024 * 1024); Files.write(fixture.files.resolve("file"), bytes);
      try (Storage storage = StorageFactory.open(fixture.endpoint)) {
        Probe probe = fixture.probe.get(); probe.blockRead = true;
        long offset = 173, length = 800123;
        try (InputStream input = storage.read("file", offset, length)) {
          ExecutorService pool = Executors.newSingleThreadExecutor();
          try {
            Future<byte[]> read = pool.submit(input::readAllBytes);
            assertTrue(probe.entered.await(5, TimeUnit.SECONDS));
            awaitQueued(probe, 7);
            assertEquals(7, probe.queued(), "one active + seven queued SSHJ reads, bounded at eight");
            probe.release.countDown();
            assertArrayEquals(Arrays.copyOfRange(bytes, (int)offset, (int)(offset + length)), read.get(10, TimeUnit.SECONDS));
            assertEquals(-1, input.read());
            assertTrue(probe.readEnd.get() <= offset + length, "read-ahead must stay within requested range");
          } finally { probe.release.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS); }
        }
        int before = probe.reads.get();
        try (InputStream empty = storage.read("file", 17, 0)) {
          assertEquals(0, empty.read(new byte[0])); assertEquals(-1, empty.read());
        }
        assertEquals(before, probe.reads.get(), "zero-length ranges must not issue reads");
        try (InputStream partial = storage.read("file", 0)) { assertEquals(bytes[0] & 255, partial.read()); }
        assertEquals(bytes.length, storage.stat("file").size());
      }
    }
  }

  @Test void realServerSeesBoundedWritePipelineAndOffsetHashMatches() throws Exception {
    try (Fixture fixture = new Fixture(root); Storage storage = StorageFactory.open(fixture.endpoint)) {
      byte[] bytes = content(1024 * 1024); Probe probe = fixture.probe.get(); probe.blockWrite = true;
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try {
        Future<?> writing = pool.submit(() -> { storage.write("file", 0, new ByteArrayInputStream(bytes), bytes.length); return null; });
        assertTrue(probe.entered.await(5, TimeUnit.SECONDS)); awaitQueued(probe, 7);
        assertEquals(7, probe.queued(), "writes must be pipelined but bounded to eight pending ACKs");
        probe.release.countDown(); writing.get(10, TimeUnit.SECONDS);
        assertEquals(16, probe.writes.get()); assertEquals(1, probe.closes.get());
        assertEquals(Ids.hash(bytes), Ids.hash(Files.readAllBytes(fixture.files.resolve("file"))));
        byte[] patch = content(100003); Arrays.fill(patch, (byte)73);
        storage.write("file", 71234, new ByteArrayInputStream(patch), patch.length);
        System.arraycopy(patch, 0, bytes, 71234, patch.length);
        try (InputStream input = storage.read("file", 0)) {
          assertEquals(Ids.hash(bytes), Ids.hash(input.readAllBytes()));
        }
      } finally { probe.release.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS); }
    }
  }

  @Test void multipleWriteErrorsDrainAllQueuedAcknowledgementsBeforeClosing() throws Exception {
    try (Fixture fixture = new Fixture(root); Storage storage = StorageFactory.open(fixture.endpoint)) {
      Probe probe = fixture.probe.get(); probe.failWrites = 3;
      byte[] bytes = content(3 * BLOCK);
      IOException error = assertThrows(IOException.class,
          () -> storage.write("broken", 0, new ByteArrayInputStream(bytes), bytes.length));
      assertEquals(3, probe.writes.get(), "every queued write must reach a terminal ACK");
      assertEquals(1, probe.closes.get()); assertEquals(0, probe.queued());
      assertTrue(error.getSuppressed().length >= 2, "keep all failed acknowledgements");
      probe.failWrites = 0;
      storage.write("good", 0, new ByteArrayInputStream(bytes), bytes.length);
      assertEquals(Ids.hash(bytes), Ids.hash(Files.readAllBytes(fixture.files.resolve("good"))));
    }
  }

  @Test void shortSourceDrainsWritesAndCanResumeWithoutHiddenWriters() throws Exception {
    try (Fixture fixture = new Fixture(root); Storage storage = StorageFactory.open(fixture.endpoint)) {
      byte[] bytes = content(4 * BLOCK); Probe probe = fixture.probe.get();
      assertThrows(EOFException.class, () -> storage.write("file", 0,
          new ByteArrayInputStream(Arrays.copyOf(bytes, 3 * BLOCK)), bytes.length));
      assertEquals(3, probe.writes.get()); assertEquals(1, probe.closes.get()); assertEquals(0, probe.queued());
      storage.write("file", 3L * BLOCK, new ByteArrayInputStream(bytes, 3 * BLOCK, BLOCK), BLOCK);
      assertEquals(Ids.hash(bytes), Ids.hash(Files.readAllBytes(fixture.files.resolve("file"))));
    }
  }

  @Test void verifiedTransferStillUsesSharedLimiterAndExactAccounting() throws Exception {
    try (Fixture fixture = new Fixture(root); Storage target = StorageFactory.open(fixture.endpoint)) {
      Path local = Files.createDirectory(root.resolve("local")); byte[] bytes = content(1024 * 1024);
      Files.write(local.resolve("source"), bytes);
      Run run = new Run(); run.id = Ids.next(); run.actor = "test";
      TaskContext task = new TaskContext(run, new BandwidthLimiter(0), 4); task.metrics.start();
      long started = System.nanoTime();
      try (Storage source = new LocalStorage(local)) {
        new TransferService(new MemoryStore().store).copy(source, "source", target, "target", bytes.length,
            Ids.hash(bytes), "pipeline", task);
      }
      task.metrics.finish();
      assertEquals(bytes.length, task.run.bytes); assertEquals(2L * bytes.length, task.run.verifiedBytes);
      assertEquals(bytes.length, task.metrics.snapshot().stages().stream().mapToLong(RunMetrics.Stage::writtenBytes).sum());
      assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(600), "three MiB processing remains capped at four MiB/s");
      assertEquals(Ids.hash(bytes), Ids.hash(Files.readAllBytes(fixture.files.resolve("target"))));
    }
  }

  private static byte[] content(int size) { byte[] bytes = new byte[size]; new Random(42).nextBytes(bytes); return bytes; }
  private static void awaitQueued(Probe probe, int count) throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (probe.queued() < count && System.nanoTime() < until) Thread.sleep(5);
  }

  static final class Probe extends SftpSubsystem {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger(), closes = new AtomicInteger();
    final AtomicLong readEnd = new AtomicLong();
    volatile boolean blockRead, blockWrite;
    volatile int failWrites;
    Probe(ChannelSession channel, SftpSubsystemConfigurator config) { super(channel, config); }
    int queued() { return requests.size(); }
    private void block() throws IOException {
      entered.countDown();
      try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("fixture release timeout"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("fixture interrupted"); }
    }
    @Override protected int doRead(int id, String handle, long offset, int length, byte[] data, int from,
        AtomicReference<Boolean> eof) throws IOException {
      if (reads.incrementAndGet() == 1 && blockRead) block();
      readEnd.accumulateAndGet(offset + length, Math::max);
      return super.doRead(id, handle, offset, length, data, from, eof);
    }
    @Override protected void doWrite(int id, String handle, long offset, int length, byte[] data, int from,
        int count) throws IOException {
      int number = writes.incrementAndGet();
      if (number == 1 && blockWrite) block();
      if (number <= failWrites) throw new IOException("injected write failure " + number);
      super.doWrite(id, handle, offset, length, data, from, count);
    }
    @Override protected void doClose(int id, String handle) throws IOException { super.doClose(id, handle); closes.incrementAndGet(); }
  }

  static final class Fixture implements AutoCloseable {
    final SshServer server = SshServer.setUpDefaultServer();
    final AtomicReference<Probe> probe = new AtomicReference<>();
    final Endpoint endpoint = new Endpoint();
    final Path files;
    Fixture(Path root) throws Exception {
      server.setHost("127.0.0.1"); server.setPort(0);
      var keys = new SimpleGeneratorHostKeyProvider(root.resolve("host.key")); keys.setAlgorithm("RSA");
      server.setKeyPairProvider(keys); files = Files.createDirectory(root.resolve("files"));
      server.setFileSystemFactory(new VirtualFileSystemFactory(files));
      server.setPasswordAuthenticator((user, pass, session) -> user.equals("test") && pass.equals("fixture-only"));
      server.setSubsystemFactories(List.of(new SftpSubsystemFactory() {
        public Command createSubsystem(ChannelSession channel) {
          Probe created = new Probe(channel, this); probe.set(created); return created;
        }
      }));
      server.start(); endpoint.protocol = "SFTP"; endpoint.host = "127.0.0.1"; endpoint.port = server.getPort();
      endpoint.root = "/"; endpoint.username = "test"; endpoint.password = "fixture-only";
      endpoint.hostKey = KeyUtils.getFingerPrint(keys.loadKeys(null).iterator().next().getPublic());
    }
    public void close() throws Exception { server.stop(true); }
  }
}
