package com.oadig.syncsrv.platform.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hierynomus.smbj.share.File;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SmbStreamsTest {
  @Test
  void pipelinesAtMostFourIndependentBlocksAndFlushesAfterAcknowledgements() throws Exception {
    File file = mock(File.class);
    byte[] source = new byte[SmbStreams.BUFFER * 6 + 71];
    new Random(92).nextBytes(source);
    byte[] received = new byte[source.length];
    AtomicInteger outstanding = new AtomicInteger(), peak = new AtomicInteger();
    Set<byte[]> buffers = Collections.newSetFromMap(new IdentityHashMap<>());
    when(file.writeAsync(any(byte[].class), anyLong(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              byte[] block = call.getArgument(0);
              long offset = call.getArgument(1);
              int start = call.getArgument(2), count = call.getArgument(3);
              buffers.add(block);
              peak.accumulateAndGet(outstanding.incrementAndGet(), Math::max);
              Future<Long> response = mock(Future.class);
              when(response.get(30, TimeUnit.SECONDS))
                  .thenAnswer(
                      wait -> {
                        System.arraycopy(block, start, received, (int) offset - 29, count);
                        outstanding.decrementAndGet();
                        return (long) count;
                      });
              when(response.isDone()).thenReturn(true);
              return response;
            });
    doAnswer(
            call -> {
              assertEquals(0, outstanding.get());
              return null;
            })
        .when(file)
        .flush();
    SmbStreams.write(file, 29, new ByteArrayInputStream(source), source.length);
    assertArrayEquals(source, received);
    assertEquals(4, peak.get());
    assertEquals(7, buffers.size());
    verify(file).flush();
  }

  @Test
  void partialServerWritesResumeExactOffsetWithoutLosingData() throws Exception {
    File file = mock(File.class);
    byte[] source = new byte[SmbStreams.BUFFER + 319], received = new byte[source.length];
    new Random(41).nextBytes(source);
    when(file.writeAsync(any(byte[].class), anyLong(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              byte[] block = call.getArgument(0);
              long offset = call.getArgument(1);
              int start = call.getArgument(2), count = Math.min(64 * 1024, call.getArgument(3));
              System.arraycopy(block, start, received, (int) offset - 13, count);
              return CompletableFuture.completedFuture((long) count);
            });
    SmbStreams.write(file, 13, new ByteArrayInputStream(source), source.length);
    assertArrayEquals(source, received);
    verify(file).flush();
  }

  @Test
  void failedResponseCancelsOtherWritesAndNeverFlushes() throws Exception {
    File file = mock(File.class);
    List<CompletableFuture<Long>> writes = new ArrayList<>();
    when(file.writeAsync(any(byte[].class), anyLong(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              CompletableFuture<Long> future = new CompletableFuture<>();
              if (writes.isEmpty()) future.completeExceptionally(new IOException("disconnected"));
              writes.add(future);
              return future;
            });
    byte[] data = new byte[SmbStreams.BUFFER * 5];
    assertThrows(
        IOException.class,
        () -> SmbStreams.write(file, 0, new ByteArrayInputStream(data), data.length));
    assertEquals(4, writes.size());
    assertTrue(writes.stream().skip(1).allMatch(Future::isCancelled));
    verify(file, never()).flush();
  }

  @Test
  void shortInputZeroProgressTimeoutAndInterruptionCannotCommit() throws Exception {
    File file = mock(File.class);
    assertThrows(
        EOFException.class,
        () -> SmbStreams.write(file, 0, new ByteArrayInputStream(new byte[2]), 3));
    when(file.writeAsync(any(byte[].class), anyLong(), anyInt(), anyInt()))
        .thenReturn(CompletableFuture.completedFuture(0L));
    assertThrows(
        IOException.class,
        () -> SmbStreams.write(file, 0, new ByteArrayInputStream(new byte[3]), 3));
    Future<Long> timeout = mock(Future.class);
    when(timeout.get(30, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
    when(file.writeAsync(any(byte[].class), anyLong(), anyInt(), anyInt())).thenReturn(timeout);
    assertThrows(
        IOException.class,
        () -> SmbStreams.write(file, 0, new ByteArrayInputStream(new byte[3]), 3));
    verify(timeout).cancel(true);
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          InterruptedIOException.class,
          () -> SmbStreams.write(file, 0, new ByteArrayInputStream(new byte[3]), 3));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    verify(file, never()).flush();
  }
}
