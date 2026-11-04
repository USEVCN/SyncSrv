package com.oadig.syncsrv.platform.storage;

import java.io.*;
import java.net.SocketTimeoutException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.Flow.*;
import java.util.function.Consumer;

/** A bounded streaming HTTP exchange with idle deadlines and explicit cancellation. */
final class WebDavExchange implements AutoCloseable {
  private final HttpClient client;
  private final long idleNanos;
  private final Consumer<WebDavExchange> removed;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong progress = new AtomicLong(System.nanoTime());
  private volatile CompletableFuture<HttpResponse<InputStream>> response;
  private volatile Body body;
  private volatile InputStream upload;
  private volatile Subscription uploadSubscription;

  WebDavExchange(HttpClient client, Duration idleTimeout, Consumer<WebDavExchange> removed) {
    this.client = client;
    idleNanos = idleTimeout.toNanos();
    if (idleNanos <= 0) throw new IllegalArgumentException("WebDAV timeout must be positive");
    this.removed = removed;
  }

  HttpRequest.BodyPublisher upload(InputStream source, long length) {
    upload = source;
    HttpRequest.BodyPublisher delegate = length == 0 ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> source), length);
    return new HttpRequest.BodyPublisher() {
      public long contentLength() { return delegate.contentLength(); }
      public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
        delegate.subscribe(new Subscriber<>() {
          public void onSubscribe(Subscription subscription) {
            uploadSubscription = subscription;
            subscriber.onSubscribe(subscription);
            if (closed.get()) subscription.cancel();
          }
          public void onNext(ByteBuffer bytes) {
            // Demand comes from the HTTP writer: keep a live large upload alive,
            // but a peer that stops consuming cannot renew this deadline forever.
            progress.set(System.nanoTime());
            subscriber.onNext(bytes);
          }
          public void onError(Throwable error) { subscriber.onError(error); }
          public void onComplete() {
            progress.set(System.nanoTime());
            upload = null;
            subscriber.onComplete();
          }
        });
      }
    };
  }

  HttpResponse<InputStream> send(HttpRequest request) throws IOException {
    if (closed.get()) throw new IOException("WebDAV 连接已关闭");
    progress.set(System.nanoTime());
    response = client.sendAsync(request, info -> {
      Body subscriber = new Body();
      body = subscriber;
      if (closed.get()) subscriber.cancel();
      return subscriber;
    });
    if (closed.get()) response.cancel(true);
    try {
      for (;;) {
        long remaining = idleNanos - (System.nanoTime() - progress.get());
        if (remaining <= 0) throw new SocketTimeoutException("WebDAV 请求超时：服务器未响应或上传无进展");
        try { return response.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS); }
        catch (TimeoutException ignored) { /* Inspect fresh upload progress before timing out. */ }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      close();
      throw interrupted(e);
    } catch (ExecutionException e) {
      close();
      if (e.getCause() instanceof IOException io) throw io;
      throw new IOException("WebDAV 请求失败", e.getCause());
    } catch (CancellationException e) {
      close();
      throw new IOException("WebDAV 请求已取消", e);
    } catch (IOException e) {
      close();
      throw e;
    }
  }

  private static InterruptedIOException interrupted(Throwable cause) {
    InterruptedIOException error = new InterruptedIOException("WebDAV 传输已暂停");
    if (cause != null) error.initCause(cause);
    return error;
  }

  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    if (response != null) response.cancel(true);
    if (uploadSubscription != null) uploadSubscription.cancel();
    if (body != null) body.cancel();
    InputStream input = upload;
    upload = null;
    if (input != null) try { input.close(); } catch (IOException ignored) {}
    removed.accept(this);
  }

  private record Chunk(List<ByteBuffer> data, Throwable error, boolean end) {}

  private final class Body implements HttpResponse.BodySubscriber<InputStream> {
    // One chunk is consumed and one prefetched. Terminal completion may follow
    // the last chunk immediately; no complete file is retained in memory.
    private final BlockingQueue<Chunk> chunks = new ArrayBlockingQueue<>(3);
    private volatile Subscription subscription;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final InputStream stream = new InputStream() {
      private Iterator<ByteBuffer> buffers = Collections.emptyIterator();
      private ByteBuffer current;
      private boolean eof;
      @Override public int read() throws IOException {
        byte[] single = new byte[1];
        return read(single, 0, 1) == -1 ? -1 : single[0] & 0xff;
      }
      @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (Thread.currentThread().isInterrupted()) { close(); throw interrupted(null); }
        if (eof) return -1;
        for (;;) {
          if (closed.get()) throw new IOException("WebDAV 响应流已关闭");
          if (current != null && current.hasRemaining()) {
            int count = Math.min(length, current.remaining());
            current.get(bytes, offset, count);
            return count;
          }
          if (buffers.hasNext()) { current = buffers.next(); continue; }
          Chunk chunk;
          try { chunk = chunks.poll(idleNanos, TimeUnit.NANOSECONDS); }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw interrupted(e);
          }
          if (chunk == null) {
            close();
            throw new SocketTimeoutException("WebDAV 正文读取超时：服务器持续未返回数据");
          }
          if (chunk.error() != null) {
            close();
            if (chunk.error() instanceof IOException io) throw io;
            throw new IOException("WebDAV 正文读取失败", chunk.error());
          }
          if (chunk.end()) { eof = true; close(); return -1; }
          buffers = chunk.data().iterator();
          if (subscription != null && !cancelled.get()) subscription.request(1);
        }
      }
      @Override public void close() { WebDavExchange.this.close(); }
    };

    public CompletionStage<InputStream> getBody() { return CompletableFuture.completedFuture(stream); }
    public void onSubscribe(Subscription value) {
      subscription = value;
      if (closed.get() || cancelled.get()) value.cancel(); else value.request(1);
    }
    public void onNext(List<ByteBuffer> bytes) {
      if (!cancelled.get() && !chunks.offer(new Chunk(bytes, null, false))) {
        onError(new IOException("WebDAV 响应超出流控缓冲"));
      }
    }
    public void onError(Throwable error) {
      if (!cancelled.get()) chunks.offer(new Chunk(List.of(), error, true));
    }
    public void onComplete() {
      if (!cancelled.get()) chunks.offer(new Chunk(List.of(), null, true));
    }
    void cancel() {
      if (!cancelled.compareAndSet(false, true)) return;
      if (subscription != null) subscription.cancel();
      chunks.clear();
      chunks.offer(new Chunk(List.of(), new IOException("WebDAV 请求已关闭"), true));
    }
  }
}
