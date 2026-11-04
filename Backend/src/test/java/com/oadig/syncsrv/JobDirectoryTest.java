package com.oadig.syncsrv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.AgentSource;
import com.oadig.syncsrv.modules.jobs.JobDirectoryController;
import com.oadig.syncsrv.shared.Models.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class JobDirectoryTest {
  @TempDir Path root;
  final MemoryStore memory = new MemoryStore();
  final ConfigurationService config = mock(ConfigurationService.class);
  final Job job = new Job();
  final MockHttpServletRequest request = new MockHttpServletRequest();
  final JobDirectoryController controller = new JobDirectoryController(memory.store, config);
  JobDirectoryTest() throws Exception {
    job.id = "job"; job.sourceId = "a"; job.targetId = "b"; job.archiveId = "c";
    when(config.job("job")).thenReturn(job);
    request.setAttribute("actor", "admin");
  }
  void endpoint(String id) throws Exception {
    var e = new Endpoint(); e.id = id; e.root = root.toString();
    when(config.endpoint(id)).thenReturn(e);
  }
  JobDirectoryController.Listing list(String role, String path) throws Exception {
    return controller.tree("job", role, path, 0, "modified", request).data();
  }
  @Test void lazilyListsRealDirectoriesAndFilesWithoutReadingContents() throws Exception {
    endpoint("b");
    Files.createDirectories(root.resolve("folder/nested"));
    Files.writeString(root.resolve("folder/file.txt"), "unchanged");
    Files.createDirectory(root.resolve(".syncsrv"));
    var first = list("B", "");
    assertEquals(1, first.entries().size());
    assertEquals("folder", first.entries().get(0).name());
    var children = list("B", "folder");
    assertEquals(2, children.total());
    assertTrue(children.entries().stream().anyMatch(e -> e.directory() && e.name().equals("nested")));
    assertTrue(children.entries().stream().anyMatch(e -> e.path().equals("folder/file.txt")));
    assertEquals("unchanged", Files.readString(root.resolve("folder/file.txt")));
    assertTrue(memory.actions.contains("directory.browsed"));
  }
  @Test void sourceUsesPublishedManifestWithoutConnectingToB() throws Exception {
    job.sourceMode = "AGENT"; job.agentRoot = "E:\\Source";
    String published = "2026-09-18T00:00:00Z";
    memory.put("agent-source-state", "job", new AgentSource.State(1, published, published, true));
    memory.put(AgentSource.kind("job"), "dir", new Node("docs", true, 0, 0, "", ""));
    memory.put(AgentSource.kind("job"), "file", new Node("docs/readme.txt", false, 3, 1000, "a".repeat(64), ""));
    assertEquals("docs", list("A", "").entries().get(0).name());
    var children = list("A", "docs");
    assertEquals("SOURCE_MANIFEST", children.mode());
    assertEquals(published, children.observedAt());
    assertEquals("readme.txt", children.entries().get(0).name());
    verify(config, never()).endpoint(anyString());
    assertThrows(Exception.class, () -> list("A", "missing"));
  }
  @Test void archiveIsRestrictedToThisJobAndPreservesVersionNames() throws Exception {
    endpoint("c");
    Files.createDirectories(root.resolve("other/versions"));
    Files.writeString(root.resolve("other/versions/secret"), "other");
    assertEquals(0, list("C", "").total());
    assertFalse(Files.exists(root.resolve("job")));
    Files.createDirectories(root.resolve("job/versions/docs"));
    Files.writeString(root.resolve("job/versions/docs/report.__v1.__deleted"), "old");
    assertEquals(1, list("C", "").total());
    assertEquals("report.__v1.__deleted", list("C", "docs").entries().get(0).name());
    assertThrows(Exception.class, () -> list("C", "../../other/versions"));
  }
  @Test void rejectsTraversalAndSymlinkDirectoriesAndAuditsFailures() throws Exception {
    endpoint("b");
    assertThrows(Exception.class, () -> list("B", "../"));
    assertThrows(Exception.class, () -> list("B", ".syncsrv/staging"));
    assertThrows(Exception.class, () -> list("Z", ""));
    Files.createDirectory(root.resolve("actual"));
    Files.createSymbolicLink(root.resolve("link"), root.resolve("actual"));
    assertTrue(list("B", "").entries().stream().anyMatch(e -> e.name().equals("link") && e.symbolic()));
    assertThrows(Exception.class, () -> list("B", "link"));
    assertTrue(memory.actions.contains("directory.browse_failed"));
  }
  @Test void paginatesWideDirectoriesWithoutTruncatingTheRemainder() throws Exception {
    endpoint("b");
    for (int i = 0; i < 203; i++) Files.createFile(root.resolve(String.format("file-%03d", i)));
    var first = controller.tree("job", "B", "", 0, "name", request).data();
    assertEquals(200, first.entries().size()); assertTrue(first.hasMore());
    var next = controller.tree("job", "B", "", 200, "name", request).data();
    assertEquals(3, next.entries().size()); assertFalse(next.hasMore());
    assertEquals("file-200", next.entries().get(0).name());
  }
  @Test void sortsByNewestModificationBeforePaginationOrByNameOnRequest() throws Exception {
    endpoint("b");
    for (int i = 0; i < 203; i++) {
      Path file = Files.createFile(root.resolve(String.format("file-%03d", i)));
      Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1000L * (i + 1)));
    }
    var recent = list("B", "");
    assertEquals("file-202", recent.entries().get(0).name());
    assertEquals("file-003", recent.entries().get(199).name());
    var remainder = controller.tree("job", "B", "", 200, "modified", request).data();
    assertEquals("file-002", remainder.entries().get(0).name());
    var names = controller.tree("job", "B", "", 0, "name", request).data();
    assertEquals("file-000", names.entries().get(0).name());
  }

}
