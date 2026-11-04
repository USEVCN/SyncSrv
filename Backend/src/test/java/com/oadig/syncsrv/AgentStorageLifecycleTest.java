package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.*;
import com.oadig.syncsrv.modules.sync.CurrentIndex;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.*;
import java.io.*;
import org.junit.jupiter.api.Test;

class AgentStorageLifecycleTest {
  final MemoryStore memory = new MemoryStore();
  final ConfigurationService config = mock(ConfigurationService.class);
  final Endpoint endpoint = new Endpoint();
  final Job job = new Job();
  final Node node = new Node("file.txt", false, 3, 1, "a".repeat(64), "");

  AgentStorageLifecycleTest() throws Exception {
    job.id = "agent-job"; job.targetId = "B";
    memory.put("agent-source-state", job.id, new AgentSource.State(1, "", "", true));
    memory.put(AgentSource.kind(job.id), Ids.textHash(node.path()), node);
    when(config.endpoint(job.targetId)).thenReturn(endpoint);
  }

  @Test void directInstallForksDoNotOpenUnusedTargetSessions() throws Exception {
    Storage destination = mock(Storage.class);
    when(destination.stat(anyString())).thenReturn(new Storage.Item("file", false, 3, 1, false));
    try (var factory = mockStatic(StorageFactory.class);
         AgentStorage source = new AgentStorage(memory.store, config, job)) {
      for (int i = 0; i < 16; i++) {
        try (AgentStorage worker = source.fork()) {
          assertSame(source.list(""), worker.list(""));
          assertEquals(3, worker.stat(node.path()).size());
          assertEquals(node.hash(), worker.hash(node.path()));
          assertTrue(worker.capabilities().offsetRead());
          worker.stage(node.path(), destination, ".syncsrv/tmp/file");
        }
      }
      assertThrows(UnsupportedOperationException.class, () -> source.list("").clear());
      factory.verifyNoInteractions();
    }
  }

  @Test void readsReuseOneSessionPerWorkerAndCloseOnlyTheirOwnSession() throws Exception {
    Storage first = mock(Storage.class), second = mock(Storage.class);
    String staged = DirectUploadService.path(job.id, node);
    for (Storage storage : new Storage[]{first, second}) {
      when(storage.stat(staged)).thenReturn(new Storage.Item("file", false, 3, 1, false));
      when(storage.read(staged, 0)).thenAnswer(i -> new ByteArrayInputStream(new byte[]{1, 2, 3}));
    }
    try (var factory = mockStatic(StorageFactory.class);
         AgentStorage source = new AgentStorage(memory.store, config, job)) {
      factory.when(() -> StorageFactory.open(endpoint)).thenReturn(first, second);
      AgentStorage one = source.fork(), two = source.fork();
      try (InputStream in = one.read(node.path(), 0)) { assertEquals(3, in.readAllBytes().length); }
      try (InputStream in = one.read(node.path(), 0)) { assertEquals(3, in.readAllBytes().length); }
      try (InputStream in = two.read(node.path(), 0)) { assertEquals(3, in.readAllBytes().length); }
      one.close(); one.close();
      verify(first).close(); verify(second, never()).close();
      two.close(); verify(second).close();
      factory.verify(() -> StorageFactory.open(endpoint), times(2));
    }
  }

  @Test void committedFallbackSharesDirectSession() throws Exception {
    memory.put(CurrentIndex.kind(job.id), Ids.textHash(node.path()), node);
    memory.put("current-index", job.id, new CurrentIndex.Format(1, job.id));
    Storage target = mock(Storage.class);
    when(target.read(node.path(), 1)).thenReturn(new ByteArrayInputStream(new byte[]{2, 3}));
    try (var factory = mockStatic(StorageFactory.class)) {
      factory.when(() -> StorageFactory.open(endpoint)).thenReturn(target);
      try (AgentStorage source = new AgentStorage(memory.store, config, job);
           InputStream in = source.read(node.path(), 1)) {
        assertArrayEquals(new byte[]{2, 3}, in.readAllBytes());
      }
      verify(target).close();
      factory.verify(() -> StorageFactory.open(endpoint));
    }
  }

  @Test void closedSessionCannotReopenOrFork() throws Exception {
    try (var factory = mockStatic(StorageFactory.class)) {
      AgentStorage source = new AgentStorage(memory.store, config, job);
      source.close(); source.close();
      assertThrows(IOException.class, () -> source.read(node.path(), 0));
      assertThrows(IOException.class, source::fork);
      factory.verifyNoInteractions();
    }
  }
}
