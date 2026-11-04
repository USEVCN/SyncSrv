package com.oadig.syncsrv;

import static org.mockito.Mockito.*;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Json;
import java.util.*;

/**
 * Deterministic domain tests; database transaction/signature tests use a separate real H2
 * fixture.
 */
final class MemoryStore {
  final Store store = mock(Store.class);
  final Map<String, Map<String, String>> objects = new java.util.concurrent.ConcurrentHashMap<>();
  final List<String> actions = new java.util.concurrent.CopyOnWriteArrayList<>();

  MemoryStore() throws Exception {
    when(store.readSnapshot(any())).thenAnswer(i -> ((java.util.concurrent.Callable<?>)i.getArgument(0)).call());
    when(store.get(anyString(), anyString(), any()))
        .thenAnswer(
            i -> {
              String value = objects.getOrDefault(i.getArgument(0), Map.of()).get(i.getArgument(1));
              return value == null ? null : Json.read(value, (Class<?>) i.getArgument(2));
            });
    when(store.list(anyString(), any()))
        .thenAnswer(
            i -> {
              List<Object> result = new ArrayList<>();
              for (String value : objects.getOrDefault(i.getArgument(0), Map.of()).values())
                result.add(Json.read(value, (Class<?>) i.getArgument(1)));
              return result;
            });
    when(store.recent(anyString(), anyInt(), any())).thenAnswer(i -> store.list(i.getArgument(0),i.getArgument(2)).stream().limit((int)i.getArgument(1)).toList());
    when(store.runHeads()).thenAnswer(i->store.list("run",com.oadig.syncsrv.shared.Models.Run.class));
    when(store.restoreRuns()).thenAnswer(i->store.list("run",com.oadig.syncsrv.shared.Models.Run.class).stream().filter(v->"RESTORE".equals(v.kind)).toList());
    when(store.getMany(anyString(), anyCollection(), any()))
        .thenAnswer(
            i -> {
              Map<String, Object> result = new java.util.concurrent.ConcurrentHashMap<>();
              Collection<String> ids = i.getArgument(1);
              for (String id : ids) {
                String value = objects.getOrDefault(i.getArgument(0), Map.of()).get(id);
                if (value != null) result.put(id, Json.read(value, (Class<?>) i.getArgument(2)));
              }
              return result;
            });
    doAnswer(
            i -> {
              actions.add(i.getArgument(1));
              for (Object arg : i.getArguments())
                if (arg instanceof Store.Change c) {
                  Map<String, String> kind =
                      objects.computeIfAbsent(c.kind(), k -> new java.util.concurrent.ConcurrentSkipListMap<>());
                  if (c.delete()) kind.remove(c.id());
                  else kind.put(c.id(), Json.write(c.value()));
                }
              return null;
            })
        .when(store)
        .commit(anyString(), anyString(), anyString(), any(), any(Store.Change[].class));
  }

  void put(String kind, String id, Object value) {
    objects.computeIfAbsent(kind, k -> new java.util.concurrent.ConcurrentSkipListMap<>()).put(id, Json.write(value));
  }
}
