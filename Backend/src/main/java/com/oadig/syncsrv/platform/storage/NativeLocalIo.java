package com.oadig.syncsrv.platform.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.oadig.syncsrv.shared.Json;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Private stdio bridge. All filesystem operations stay inside a native root handle. */
final class NativeLocalIo implements AutoCloseable {
  static final int CHUNK = 1024 * 1024;
  private static Path executable;
  private final Process process;
  private final InputStream input;
  private final OutputStream output;
  private volatile boolean closed, busy;
  private static final java.util.concurrent.ScheduledThreadPoolExecutor SHUTDOWN = shutdownTimer();
  private static java.util.concurrent.ScheduledThreadPoolExecutor shutdownTimer() {
    var timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1, action -> {
      var thread = new Thread(action, "syncsrv-local-io-shutdown"); thread.setDaemon(true); return thread;
    });
    timer.setRemoveOnCancelPolicy(true); return timer;
  }
  private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);
  record Reply(JsonNode json, byte[] body) {}
  NativeLocalIo(Path root) throws Exception {
    process = new ProcessBuilder(binary().toString(), root.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    input = new BufferedInputStream(process.getInputStream(), 64 * 1024);
    output = new BufferedOutputStream(process.getOutputStream(), 64 * 1024);
    try { call(Map.of("op", "ping")); } catch (Exception e) { close(); throw e; }
  }
  private static synchronized Path binary() throws Exception {
    if (executable != null) return executable;
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
    String system = os.contains("win") ? "windows" : os.contains("mac") ? "darwin" : os.contains("linux") ? "linux" : "unsupported";
    arch = Set.of("aarch64", "arm64").contains(arch) ? "arm64" : Set.of("amd64", "x86_64").contains(arch) ? "amd64" : "unsupported";
    String name = system + "-" + arch + (system.equals("windows") ? ".exe" : "");
    byte[] bytes = resource("/native-io/" + name);
    JsonNode hashes = Json.MAPPER.readTree(resource("/native-io/checksums.json"));
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    if (!hash.equals(hashes.path(name).asText())) throw new IOException("本地存储组件完整性校验失败");
    Path dir;
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
      dir = Files.createTempDirectory("syncsrv-local-io-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    else dir = Files.createTempDirectory("syncsrv-local-io-");
    Path file = dir.resolve(name);
    Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    else if (!file.toFile().setExecutable(true, true)) throw new IOException("本地存储组件不可执行");
    dir.toFile().deleteOnExit(); file.toFile().deleteOnExit(); executable = file; return file;
  }
  private static byte[] resource(String name) throws IOException {
    try (InputStream stream = NativeLocalIo.class.getResourceAsStream(name)) {
      if (stream == null) throw new IOException("缺少安全本地存储组件，请先运行 scripts/build-native-io.py");
      return stream.readAllBytes();
    }
  }
  synchronized Reply call(Map<String, ?> request) throws Exception { return call(request, new byte[0]); }
  synchronized Reply call(Map<String, ?> request, byte[] body) throws Exception {
    busy = true;
    try {
      if (closed) throw new IOException("本地存储连接已关闭");
      return exchange(request, body, true);
    } finally { busy = false; }
  }
  private Reply exchange(Map<String, ?> request, byte[] body, boolean invalidate) throws Exception {
    JsonNode json; byte[] data;
    try {
      output.write(Json.MAPPER.writeValueAsBytes(request)); output.write('\n'); output.write(body); output.flush();
      ByteArrayOutputStream header = new ByteArrayOutputStream();
      for (int i = 0; ; i++) {
        int c = input.read(); if (c == -1) throw new EOFException("本地存储连接中断"); if (c == '\n') break;
        if (i >= 4 * 1024 * 1024) throw new IOException("本地存储响应超过上限"); header.write(c);
      }
      json = Json.MAPPER.readTree(header.toByteArray()); int length = json.path("length").asInt(-1);
      if (length < 0 || length > CHUNK) throw new IOException("本地存储响应长度无效");
      data = input.readNBytes(length); if (data.length != length) throw new EOFException("本地存储响应不完整");
    } catch (Exception e) { if (invalidate) close(); throw e; }
    if (!json.path("ok").asBoolean()) {
      String path = Objects.toString(request.get("path"), "");
      switch (json.path("error").asText()) {
        case "missing" -> throw new NoSuchFileException(path);
        case "exists" -> throw new FileAlreadyExistsException(path);
        case "denied" -> throw new AccessDeniedException(path);
        default -> throw new IOException("本地存储操作失败: " + request.get("op"));
      }
    }
    return new Reply(json, data);
  }
  @Override public void close() {
    boolean owner = closing.compareAndSet(false, true), interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    java.util.concurrent.ScheduledFuture<?> timeout = null;
    Exception shutdownFailure = null;
    try {
      if (owner) {
        closed = true;
        // Stop admitting requests, then let a short in-flight local operation
        // finish so cancellation can release native handles explicitly as well.
        long quiet = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        while (busy && System.nanoTime() < quiet) {
          java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
          if (Thread.interrupted()) interrupted = true;
        }
        if (!busy) {
          timeout = SHUTDOWN.schedule(() -> process.destroyForcibly(), 2, TimeUnit.SECONDS);
          try { exchange(Map.of("op", "shutdown"), new byte[0], false); }
          catch (Exception e) { shutdownFailure = e; process.destroyForcibly(); }
        } else process.destroyForcibly();
      }
      for (;;) {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new IllegalStateException("本地存储组件未能及时退出");
        try {
          if (owner ? process.waitFor(left, TimeUnit.NANOSECONDS) : stopped.await(left, TimeUnit.NANOSECONDS)) break;
        } catch (InterruptedException e) { interrupted = true; }
      }
    } finally {
      if (timeout != null) timeout.cancel(false);
      if (owner) {
        try { output.close(); } catch (Exception ignored) {}
        try { input.close(); } catch (Exception ignored) {}
        stopped.countDown();
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
    if (shutdownFailure != null) throw new IllegalStateException("本地存储退出确认失败", shutdownFailure);
  }
}
