package com.oadig.syncsrv.platform.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.shared.Models.Endpoint;
import java.io.*;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class FtpStorageLifecycleTest {
  private Endpoint endpoint() {
    Endpoint endpoint = new Endpoint();
    endpoint.host = "127.0.0.1";
    return endpoint;
  }

  private void connected(FTPClient ftp) throws Exception {
    when(ftp.getReplyCode()).thenReturn(220);
    when(ftp.login(anyString(), anyString())).thenReturn(true);
    when(ftp.getSystemType()).thenReturn("UNIX");
    when(ftp.setFileType(anyInt())).thenReturn(true);
    when(ftp.isConnected()).thenReturn(true);
  }

  @Test void failedLoginDisconnectsImmediately() throws Exception {
    try (var clients = mockConstruction(FTPClient.class, (ftp, context) -> {
      connected(ftp);
      when(ftp.login(anyString(), anyString())).thenReturn(false);
    })) {
      IOException error = assertThrows(IOException.class, () -> new FtpStorage(endpoint()));
      assertEquals("FTP 登录失败", error.getMessage());
      verify(clients.constructed().get(0)).disconnect();
    }
  }

  @Test void initialCommandFailureKeepsCauseAndDisconnects() throws Exception {
    IOException original = new IOException("SYST connection reset");
    IOException cleanup = new IOException("disconnect failure");
    try (var clients = mockConstruction(FTPClient.class, (ftp, context) -> {
      connected(ftp);
      when(ftp.getSystemType()).thenThrow(original);
      doThrow(cleanup).when(ftp).disconnect();
    })) {
      assertSame(original, assertThrows(IOException.class, () -> new FtpStorage(endpoint())));
      assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
      verify(clients.constructed().get(0)).disconnect();
    }
  }

  @Test void failedRetrieveDisconnectsDedicatedReadSession() throws Exception {
    IOException original = new IOException("RETR connection reset");
    try (var clients = mockConstruction(FTPClient.class, (ftp, context) -> {
      connected(ftp);
      when(ftp.retrieveFileStream(anyString())).thenThrow(original);
    }); FtpStorage storage = new FtpStorage(endpoint())) {
      assertSame(original, assertThrows(IOException.class, () -> storage.read("file.txt", 0)));
      verify(clients.constructed().get(1)).disconnect();
      verify(clients.constructed().get(0), never()).disconnect();
    }
  }

  @Test void rejectedRetrieveDisconnectsDedicatedReadSession() throws Exception {
    try (var clients = mockConstruction(FTPClient.class, (ftp, context) -> connected(ftp));
         FtpStorage storage = new FtpStorage(endpoint())) {
      assertThrows(IOException.class, () -> storage.read("file.txt", 0));
      verify(clients.constructed().get(1)).disconnect();
    }
  }

  @Test void completedReadCanBeClosedRepeatedly() throws Exception {
    try (var clients = mockConstruction(FTPClient.class, (ftp, context) -> {
      connected(ftp);
      when(ftp.retrieveFileStream(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1}));
      when(ftp.completePendingCommand()).thenReturn(true);
    }); FtpStorage storage = new FtpStorage(endpoint())) {
      InputStream stream = storage.read("file.txt", 0);
      assertArrayEquals(new byte[]{1}, stream.readAllBytes());
      stream.close(); stream.close();
      FTPClient reader = clients.constructed().get(1);
      verify(reader).completePendingCommand(); verify(reader).disconnect();
    }
  }

  @Test void partialReadDoesNotAwaitTransferCompletion() throws Exception {
    try (var clients = mockConstruction(FTPClient.class, (ftp, context) -> {
      connected(ftp);
      when(ftp.retrieveFileStream(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
    }); FtpStorage storage = new FtpStorage(endpoint())) {
      try (InputStream stream = storage.read("file.txt", 0)) { assertEquals(1, stream.read()); }
      FTPClient reader = clients.constructed().get(1);
      verify(reader, never()).completePendingCommand(); verify(reader).disconnect();
    }
  }
}
