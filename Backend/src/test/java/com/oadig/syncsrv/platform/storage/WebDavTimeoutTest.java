package com.oadig.syncsrv.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.oadig.syncsrv.shared.Models.Endpoint;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class WebDavTimeoutTest {
  HttpServer server;
  ExecutorService handlers;
  Endpoint endpoint;
  final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    handlers = Executors.newCachedThreadPool();
    server.setExecutor(handlers);
    endpoint = new Endpoint();
    endpoint.host = "http://127.0.0.1:" + server.getAddress().getPort();
    endpoint.root = "/";
    endpoint.username = "";
    endpoint.password = "";
  }
  void serve(HttpHandler handler) { server.createContext("/", handler); server.start(); }
  void awaitRelease(HttpExchange exchange) {
    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    finally { exchange.close(); }
  }
  WebDavStorage storage(long timeoutMillis) throws Exception {
    return new WebDavStorage(endpoint, Duration.ofMillis(timeoutMillis));
  }
  @AfterEach void stop() {
    release.countDown();
    server.stop(0);
    handlers.shutdownNow();
  }

  @Test void responseHeadersWithoutBodyCannotHoldReadForever() throws Exception {
    serve(exchange -> { exchange.sendResponseHeaders(200, 8); exchange.getResponseBody().write(1); exchange.getResponseBody().flush(); awaitRelease(exchange); });
    try (Storage storage = storage(350); InputStream stream = storage.read("file", 0)) {
      assertEquals(1, stream.read());
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(SocketTimeoutException.class, stream::read));
    }
  }

  @Test void incompleteDirectoryXmlCannotHoldEnumerationForever() throws Exception {
    serve(exchange -> { exchange.sendResponseHeaders(207, 100); exchange.getResponseBody().write('<'); exchange.getResponseBody().flush(); awaitRelease(exchange); });
    try (Storage storage = storage(350)) {
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(SocketTimeoutException.class, () -> storage.list("")));
    }
  }

  @Test void noResponseHeadersHasAnIdleDeadline() throws Exception {
    serve(this::awaitRelease);
    try (Storage storage = storage(350)) {
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(SocketTimeoutException.class, () -> storage.read("file", 0)));
    }
  }

  @Test void interruptReleasesBlockedBodyReadAndPreservesInterruptFlag() throws Exception {
    serve(exchange -> { exchange.sendResponseHeaders(200, 8); exchange.getResponseBody().flush(); awaitRelease(exchange); });
    try (Storage storage = storage(10000); InputStream stream = storage.read("file", 0)) {
      CountDownLatch reading = new CountDownLatch(1);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      AtomicBoolean interrupted = new AtomicBoolean();
      Thread thread = new Thread(() -> {
        reading.countDown();
        try { stream.read(); } catch (Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
      });
      thread.start(); assertTrue(reading.await(1, TimeUnit.SECONDS));
      thread.interrupt(); thread.join(1500);
      assertFalse(thread.isAlive(), "pause left the WebDAV worker blocked");
      assertInstanceOf(InterruptedIOException.class, failure.get());
      assertTrue(interrupted.get());
    }
  }

  @Test void closingStorageReleasesItsOutstandingBodyReads() throws Exception {
    serve(exchange -> { exchange.sendResponseHeaders(200, 8); exchange.getResponseBody().flush(); awaitRelease(exchange); });
    WebDavStorage storage = storage(10000);
    try (InputStream stream = storage.read("file", 0)) {
      CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(() -> {
        try { stream.read(); return null; } catch (Throwable e) { return e; }
      });
      storage.close();
      assertInstanceOf(IOException.class, result.get(1500, TimeUnit.MILLISECONDS));
      assertThrows(IOException.class, () -> storage.read("another", 0));
    } finally { storage.close(); }
  }

  @Test void progressingResponseMayTakeLongerThanTheIdleTimeout() throws Exception {
    serve(exchange -> {
      exchange.sendResponseHeaders(200, 8);
      try {
        for (int i = 0; i < 8; i++) {
          exchange.getResponseBody().write(i); exchange.getResponseBody().flush(); Thread.sleep(100);
        }
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      finally { exchange.close(); }
    });
    try (Storage storage = storage(350); InputStream stream = storage.read("file", 0)) {
      assertArrayEquals(new byte[] {0,1,2,3,4,5,6,7}, stream.readAllBytes());
    }
  }

  @Test void serverNotReadingUploadTimesOutAndClosesTheSource() throws Exception {
    serve(this::awaitRelease);
    AtomicBoolean closed = new AtomicBoolean();
    InputStream source = new InputStream() {
      @Override public int read() { return 0; }
      @Override public int read(byte[] bytes, int offset, int length) { return length; }
      @Override public void close() { closed.set(true); }
    };
    try (Storage storage = storage(350)) {
      assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(SocketTimeoutException.class, () -> storage.write("large", 0, source, 1L << 30)));
      assertTrue(closed.get(), "cancelled upload retained its source stream");
    }
  }

  @Test void progressingUploadIsNotLimitedByTotalRequestDuration() throws Exception {
    AtomicLong received = new AtomicLong();
    serve(exchange -> {
      try (InputStream input = exchange.getRequestBody()) { received.set(input.transferTo(OutputStream.nullOutputStream())); }
      exchange.sendResponseHeaders(201, -1); exchange.close();
    });
    InputStream source = new InputStream() {
      int chunks;
      @Override public int read() { throw new UnsupportedOperationException(); }
      @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        if (chunks++ == 8) return -1;
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
        int count = Math.min(length, 1024); java.util.Arrays.fill(bytes, offset, offset + count, (byte) 1); return count;
      }
    };
    try (Storage storage = storage(350)) { storage.write("large", 0, source, 8192); }
    assertEquals(8192, received.get());
  }

  @Test void interruptReleasesUploadAwaitingResponseHeaders() throws Exception {
    CountDownLatch requested = new CountDownLatch(1);
    serve(exchange -> { requested.countDown(); awaitRelease(exchange); });
    try (Storage storage = storage(10000)) {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread writer = new Thread(() -> {
        try { storage.write("small", 0, new ByteArrayInputStream(new byte[] {1}), 1); }
        catch (Throwable e) { failure.set(e); }
      });
      writer.start(); assertTrue(requested.await(2, TimeUnit.SECONDS)); writer.interrupt(); writer.join(1500);
      assertFalse(writer.isAlive(), "pause left the PUT worker blocked");
      assertInstanceOf(InterruptedIOException.class, failure.get());
    }
  }
  @Test void stalledUploadSourceIsClosedOnTimeout() throws Exception {
    serve(this::awaitRelease);
    CountDownLatch closed = new CountDownLatch(1);
    InputStream source = new InputStream() {
      @Override public int read() throws IOException {
        try { closed.await(); return -1; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
      }
      @Override public void close() { closed.countDown(); }
    };
    try (Storage storage = storage(350)) {
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(SocketTimeoutException.class,
          () -> storage.write("blocked-source", 0, source, 1024)));
      assertEquals(0, closed.getCount());
    }
  }

  @Test void closingStorageCancelsRequestStillWaitingForHeaders() throws Exception {
    CountDownLatch requested = new CountDownLatch(1);
    serve(exchange -> { requested.countDown(); awaitRelease(exchange); });
    WebDavStorage storage = storage(10000);
    try {
      CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(() -> {
        try { storage.read("file", 0); return null; } catch (Throwable e) { return e; }
      });
      assertTrue(requested.await(2, TimeUnit.SECONDS));
      storage.close();
      assertInstanceOf(IOException.class, result.get(1500, TimeUnit.MILLISECONDS));
    } finally { storage.close(); }
  }

}
