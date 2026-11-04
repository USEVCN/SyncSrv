package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.restore.RestoreDestination;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Models.Endpoint;
import java.io.*;
import org.junit.jupiter.api.Test;

class RestoreDestinationReadTest {
  @Test void boundedReadsPreserveRestorePrefixAndSmbReadAheadLimit() throws Exception {
    Endpoint endpoint = new Endpoint(); endpoint.id = "D"; endpoint.protocol = "SMB";
    endpoint.host = "unused.test"; endpoint.root = "share";
    ConfigurationService config = mock(ConfigurationService.class);
    when(config.endpoint("D")).thenReturn(endpoint);
    byte[] bytes = {1, 2, 3};
    try (var storages = mockConstruction(SmbStorage.class, (storage, context) -> {
      when(storage.stat("")).thenReturn(new Storage.Item("", true, 0, 0, false));
      when(storage.read("point/sub/file", 7, 3)).thenReturn(new ByteArrayInputStream(bytes));
    }); RestoreDestination destination = new RestoreDestination(config, "D", "point/sub")) {
      try (InputStream input = destination.read("file", 7, 3)) {
        assertArrayEquals(bytes, input.readAllBytes());
      }
      assertEquals(1, storages.constructed().size());
      verify(storages.constructed().get(0)).read("point/sub/file", 7, 3);
      verify(storages.constructed().get(0), never()).read(anyString(), anyLong());
    }
  }
}
