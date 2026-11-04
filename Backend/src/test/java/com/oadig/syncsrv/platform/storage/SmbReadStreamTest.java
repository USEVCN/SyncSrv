package com.oadig.syncsrv.platform.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.messages.SMB2ReadResponse;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SmbReadStreamTest {
  private SMB2ReadResponse reply(byte[] data, long status) {
    var response = mock(SMB2ReadResponse.class, RETURNS_DEEP_STUBS);
    when(response.getHeader().getStatusCode()).thenReturn(status);
    when(response.getData()).thenReturn(data);
    return response;
  }

  private Future<SMB2ReadResponse> pending(Callable<SMB2ReadResponse> response) throws IOException {
    Future<SMB2ReadResponse> future = mock(Future.class);
    try {
      when(future.get(30, TimeUnit.SECONDS)).thenAnswer(i -> response.call());
    } catch (Exception e) {
      throw new IOException(e);
    }
    return future;
  }

  @Test
  void boundedWindowDeliversShortAndOutOfOrderResponsesWithoutGaps() throws Exception {
    byte[] original = new byte[10003];
    new Random(37).nextBytes(original);
    AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    List<Long> offsets = new ArrayList<>();
    Closeable handle = mock(Closeable.class);
    try (var stream =
        new SmbReadStream(
            (offset, length) -> {
              assertTrue(offset >= 17 && offset + length <= 9017);
              offsets.add(offset);
              peak.accumulateAndGet(active.incrementAndGet(), Math::max);
              int count = Math.min(length, 271);
              Future<SMB2ReadResponse> future =
                  pending(
                      () -> {
                        active.decrementAndGet();
                        return reply(
                            Arrays.copyOfRange(original, (int) offset, (int) offset + count), 0);
                      });
              return future;
            },
            handle,
            17,
            9000,
            1024,
            4)) {
      assertArrayEquals(Arrays.copyOfRange(original, 17, 9017), stream.readAllBytes());
      assertEquals(0, stream.read(new byte[0]));
      assertEquals(-1, stream.read());
      assertEquals(4, peak.get());
      assertEquals(0, active.get());
    }
    verify(handle).close();
    assertTrue(offsets.size() > 9);
  }

  @Test
  void eofAndLateRepliesDoNotLoseProtocolRegistrations() throws Exception {
    List<Future<SMB2ReadResponse>> futures = new ArrayList<>();
    Closeable handle = mock(Closeable.class);
    var stream =
        new SmbReadStream(
            (at, count) -> {
              Future<SMB2ReadResponse> f =
                  pending(() -> reply(new byte[0], NtStatus.STATUS_END_OF_FILE.getValue()));
              futures.add(f);
              return f;
            },
            handle,
            0,
            -1,
            1024,
            4);
    assertEquals(-1, stream.read());
    stream.close();
    stream.close();
    for (var f : futures) verify(f, never()).cancel(anyBoolean());
    verify(handle).close();
    assertThrows(IOException.class, stream::read);
  }

  @Test
  void readErrorsAndTimeoutNeverBecomeEmptySuccess() throws Exception {
    for (boolean timeout : List.of(false, true)) {
      Future<SMB2ReadResponse> f = mock(Future.class);
      if (timeout) when(f.get(30, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
      else {
        var denied = reply(null, NtStatus.STATUS_ACCESS_DENIED.getValue());
        when(f.get(30, TimeUnit.SECONDS)).thenReturn(denied);
      }
      try (var stream = new SmbReadStream((at, count) -> f, () -> {}, 0, 1, 1024, 4)) {
        assertThrows(IOException.class, stream::read);
      }
    }
  }

  @Test
  void interruptionPreservesFlagAndAvoidsIssuingMoreReads() throws Exception {
    SmbReadStream.Request requests = mock(SmbReadStream.Request.class);
    try (var stream = new SmbReadStream(requests, () -> {}, 0, -1, 1024, 4)) {
      Thread.currentThread().interrupt();
      try {
        assertThrows(InterruptedIOException.class, stream::read);
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      verifyNoInteractions(requests);
    }
  }
}
