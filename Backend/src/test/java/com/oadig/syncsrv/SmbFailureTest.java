package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Models.Endpoint;
import com.oadig.syncsrv.shared.web.GlobalExceptionHandler;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SmbFailureTest {
  private Endpoint endpoint(int port) {
    Endpoint endpoint = new Endpoint();
    endpoint.id = "nas";
    endpoint.protocol = "SMB";
    endpoint.host = "127.0.0.1";
    endpoint.port = port;
    endpoint.root = "/downloads/Backup";
    endpoint.username = "fixture-user";
    endpoint.password = "fixture-secret";
    return endpoint;
  }

  @Test
  void peerClosingDuringNegotiationHasUsefulMessageAndAuditWithoutCredentials() throws Exception {
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<?> peer = executor.submit(() -> {
          try (Socket accepted = listener.accept()) {
            accepted.setSoTimeout(5000);
            accepted.getInputStream().read(new byte[4096]);
          } catch (IOException ex) {
            throw new UncheckedIOException(ex);
          }
        });
        Endpoint endpoint = endpoint(listener.getLocalPort());
        MemoryStore memory = new MemoryStore();
        memory.put("endpoint", endpoint.id, endpoint);
        when(memory.store.decrypt(anyString())).thenReturn(endpoint.password);
        SmbFailure failure = assertThrows(SmbFailure.class,
            () -> new ConfigurationService(memory.store).probe(endpoint.id, "admin"));
        peer.get(5, TimeUnit.SECONDS);
        assertEquals("SMB_CONNECTION_CLOSED", failure.code());
        assertEquals("NEGOTIATE", failure.details().get("stage"));
        assertTrue(failure.getMessage().contains("445"));
        assertFalse(failure.getMessage().contains(endpoint.password));
        assertFalse(failure.getMessage().contains("java.util.concurrent"));
        verify(memory.store).commit(eq("admin"), eq("endpoint.probe_failed"), eq(endpoint.id),
            eq(failure.details()), any(com.oadig.syncsrv.platform.persistence.Store.Change[].class));
      } finally {
        executor.shutdownNow();
      }
    }
  }

  @Test
  void wrappedAuthenticationFailureDoesNotExposeRawServerMessage() {
    var raw = new SMBApiException(NtStatus.STATUS_LOGON_FAILURE.getValue(),
        SMB2MessageCommandCode.SMB2_SESSION_SETUP, "sensitive-server-detail", null);
    SmbFailure failure = SmbFailure.from(endpoint(445), SmbFailure.Stage.AUTHENTICATE,
        new ExecutionException(raw));
    assertEquals("SMB_AUTH_FAILED", failure.code());
    assertTrue(failure.getMessage().contains("账号"));
    assertFalse(failure.getMessage().contains("sensitive-server-detail"));
    var response = new GlobalExceptionHandler().smb(failure);
    assertEquals(502, response.getStatusCode().value());
    assertEquals("SMB_AUTH_FAILED", response.getBody().errorCode());
  }

  @Test
  void directoryFailureRemainsDistinctFromNetworkOrAuthenticationFailure() {
    var raw = new SMBApiException(NtStatus.STATUS_BAD_NETWORK_NAME.getValue(),
        SMB2MessageCommandCode.SMB2_TREE_CONNECT, "missing", null);
    SmbFailure failure = SmbFailure.from(endpoint(445), SmbFailure.Stage.SHARE, raw);
    assertEquals("SMB_SHARE_NOT_FOUND", failure.code());
    assertTrue(failure.getMessage().contains("共享名/子目录"));
    assertSame(failure, SmbFailure.from(endpoint(445), SmbFailure.Stage.DIRECTORY, failure));
  }
}
