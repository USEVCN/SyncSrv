package com.oadig.syncsrv.modules.jobs;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.AgentSource;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.*;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Read-only, one directory per request; never scans or hashes remote file content. */
@RestController
public final class JobDirectoryController {
  public record Entry(String path, String name, boolean directory, long size, long modified,
                      boolean symbolic) {}
  public record Listing(String root, String mode, String observedAt, List<Entry> entries,
                        int total, boolean hasMore) {}
  private final Store store;
  private final ConfigurationService config;
  private static final int PAGE_SIZE = 200;

  public JobDirectoryController(Store store, ConfigurationService config) {
    this.store = store;
    this.config = config;
  }

  @GetMapping("/api/jobs/{id}/tree")
  public ApiResponse<Listing> tree(@PathVariable String id, @RequestParam String role,
      @RequestParam(defaultValue = "") String path, @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "modified") String sort,
      HttpServletRequest request) throws Exception {
    String actor = (String) request.getAttribute("actor");
    Job job = config.job(id);
    String directory = safePath(path);
    if (!Set.of("A", "B", "C").contains(role) || offset < 0 || !Set.of("modified", "name").contains(sort))
      throw new IllegalArgumentException("目录角色或分页参数不正确");
    try {
      Listing listing = role.equals("A") && job.sourceMode.equals("AGENT")
          ? source(job, directory, offset, sort) : remote(job, role, directory, offset, sort);
      store.commit(actor, "directory.browsed", id,
          Map.of("role", role, "path", directory, "offset", offset,
                 "entries", listing.entries().size(), "mode", listing.mode(), "sort", sort));
      return ApiResponse.ok(listing);
    } catch (Exception e) {
      store.commit(actor, "directory.browse_failed", id,
          Map.of("role", role, "path", directory, "errorType", e.getClass().getSimpleName()));
      throw e;
    }
  }

  private Listing source(Job job, String directory, int offset, String sort) throws Exception {
    AgentSource.State state;
    Map<String, Node> nodes;
    synchronized (store) {
      state = AgentSource.state(store, job.id);
      if (state == null) throw new IOException("源端尚未上报目录清单，请等待首次基线上传");
      nodes = AgentSource.load(store, job.id);
    }
    if (!directory.isEmpty() && (!nodes.containsKey(directory) || !nodes.get(directory).directory()))
      throw new IOException("源端上报清单中不存在此目录");
    List<Entry> entries = nodes.values().stream()
        .filter(n -> StoragePaths.parent(n.path()).equals(directory))
        .map(n -> new Entry(n.path(), StoragePaths.name(n.path()), n.directory(), n.size(), n.modified(), false))
        .toList();
    return page(job.agentRoot, "SOURCE_MANIFEST", state.publishedAt(), entries, offset, sort);
  }

  private Listing remote(Job job, String role, String directory, int offset, String sort) throws Exception {
    String endpointId = switch (role) {
      case "A" -> job.sourceId;
      case "B" -> job.targetId;
      default -> job.archiveId;
    };
    Endpoint endpoint = config.endpoint(endpointId);
    // Archive connections can be shared. Only expose this task's version namespace.
    String prefix = role.equals("C") ? safePath(job.id) + "/versions" : "";
    String physical = prefix.isEmpty() ? directory : prefix + (directory.isEmpty() ? "" : "/" + directory);
    String root = prefix.isEmpty() ? endpoint.root : StoragePaths.join(endpoint.root, prefix);
    List<Entry> entries = new ArrayList<>();
    try (Storage storage = StorageFactory.open(endpoint)) {
      String at = "";
      for (String part : physical.isEmpty() ? new String[0] : physical.split("/")) {
        at = at.isEmpty() ? part : at + "/" + part;
        Storage.Item item = storage.stat(at);
        if (item == null && role.equals("C") && directory.isEmpty())
          return page(root, "LIVE", Ids.now(), List.of(), offset, sort);
        if (item == null || item.symbolic() || !item.directory())
          throw new IOException("目录不存在或不支持浏览符号链接");
      }
      for (Storage.Item item : storage.list(physical)) {
        if (item.name().equalsIgnoreCase(".syncsrv")) continue;
        if (item.name().isEmpty() || !safePath(item.name()).equals(item.name()))
          throw new IOException("存储返回了不安全的目录名称");
        if (item.name().contains("/")) throw new IOException("存储返回了不安全的目录名称");
        String child = directory.isEmpty() ? item.name() : directory + "/" + item.name();
        entries.add(new Entry(child, item.name(), item.directory(), item.size(), item.modified(), item.symbolic()));
      }
    }
    return page(root, "LIVE", Ids.now(), entries, offset, sort);
  }

  private static Listing page(String root, String mode, String observed, List<Entry> entries, int offset, String sort) {
    Comparator<Entry> names = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(Entry::name);
    Comparator<Entry> order = sort.equals("name") ? names
        : Comparator.comparingLong(Entry::modified).reversed().thenComparing(names);
    List<Entry> sorted = entries.stream().sorted(order).toList();
    int from = Math.min(offset, sorted.size());
    int to = (int) Math.min((long) from + PAGE_SIZE, sorted.size());
    return new Listing(root, mode, observed, sorted.subList(from, to), sorted.size(), to < sorted.size());
  }

  private static String safePath(String value) {
    String path = StoragePaths.relative(value);
    if (path.length() > 4096 || Arrays.stream(path.split("/")).anyMatch(p -> p.equalsIgnoreCase(".syncsrv")))
      throw new IllegalArgumentException("不能浏览内部工作目录");
    return path;
  }
}
