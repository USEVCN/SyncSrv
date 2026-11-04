package com.oadig.syncsrv.modules.archive;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.platform.storage.StoragePaths;
import com.oadig.syncsrv.shared.Models.*;
import java.io.IOException;
import java.util.*;

/** Registered protocol/device mappings form a graph, including connections with no file versions. */
final class ArchiveLocations {
  private record Link(String from, String to) {}
  private final ConfigurationService config;
  private final Map<String, Optional<Endpoint>> endpoints = new HashMap<>();
  private final Map<String, List<Version>> byLocation = new HashMap<>();
  private final Set<Link> links = new LinkedHashSet<>();

  ArchiveLocations(ConfigurationService config, Collection<Version> versions) throws Exception {
    this.config = config;
    for (Endpoint endpoint : config.endpoints()) remember(endpoint);
    for (Version version : versions) endpoint(version.endpointId());
    for (Version version : versions)
      for (String key : keys(version.endpointId(), version.location()))
        byLocation.computeIfAbsent(key, ignored -> new ArrayList<>()).add(version);
  }

  List<Version> aliases(String endpoint, String location) throws Exception {
    Map<String, Version> result = new TreeMap<>();
    Set<String> keys = new HashSet<>(keys(endpoint, location));
    keys.add("UNKNOWN|" + fold(location));
    for (String key : keys)
      for (Version version : byLocation.getOrDefault(key, List.of())) result.put(version.id(), version);
    return List.copyOf(result.values());
  }

  boolean overlaps(Endpoint a, Endpoint b) throws Exception {
    remember(a); remember(b);
    for (String left : expand(roots(a))) for (String right : expand(roots(b)))
      if (left.equals(right) || left.startsWith(right + "/") || right.startsWith(left + "/")) return true;
    return false;
  }

  private Optional<Endpoint> endpoint(String id) throws Exception {
    if (!endpoints.containsKey(id)) {
      Endpoint found;
      try { found = config.endpoint(id); } catch (IllegalArgumentException missing) { found = null; }
      if (found == null) endpoints.put(id, Optional.empty()); else remember(found);
    }
    return endpoints.getOrDefault(id, Optional.empty());
  }

  private void remember(Endpoint endpoint) throws Exception {
    endpoints.put(endpoint.id, Optional.of(endpoint));
    List<String> roots = new ArrayList<>(roots(endpoint));
    if (roots.size() == 2) {
      links.add(new Link(roots.get(0), roots.get(1))); links.add(new Link(roots.get(1), roots.get(0)));
    }
  }

  private Set<String> keys(String id, String relative) throws Exception {
    StoragePaths.relative(relative);
    Optional<Endpoint> endpoint = endpoint(id);
    Set<String> keys = new HashSet<>();
    keys.add("ID|" + ArchivePaths.identity(id, relative));
    if (endpoint.isEmpty()) { keys.add("UNKNOWN|" + fold(relative)); return keys; }
    for (String root : roots(endpoint.get())) keys.add(root + (relative.isEmpty() ? "" : "/" + fold(relative)));
    return expand(keys);
  }

  private Set<String> roots(Endpoint endpoint) throws Exception {
    Set<String> result = new LinkedHashSet<>();
    result.add("PROTOCOL|" + fold(ConfigurationService.location(endpoint).replaceAll("/+$", "")));
    if (endpoint.agentId != null && !endpoint.agentId.isBlank()
        && endpoint.agentRoot != null && !endpoint.agentRoot.isBlank())
      result.add("DEVICE|" + endpoint.agentId + "|" + fold(endpoint.agentRoot.replace('\\', '/')
          .replaceAll("/+$", "")));
    return result;
  }

  private Set<String> expand(Set<String> seeds) throws IOException {
    Set<String> result = new HashSet<>(seeds);
    Deque<String> todo = new ArrayDeque<>(seeds);
    while (!todo.isEmpty()) {
      String key = todo.removeFirst();
      for (Link link : links) if (key.equals(link.from()) || key.startsWith(link.from() + "/")) {
        String alias = link.to() + key.substring(link.from().length());
        if (result.add(alias)) {
          if (result.size() > Math.max(64, links.size() * 4 + 8))
            throw new IOException("设备目录映射形成不一致循环，停止整理");
          todo.addLast(alias);
        }
      }
    }
    return result;
  }
  private static String fold(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
  }
}
