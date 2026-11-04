package com.oadig.syncsrv.modules.jobs;

import static com.oadig.syncsrv.modules.config.ConfigurationController.actor;

import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TaskController {
  public record RestoreRequest(String snapshotId, String directory, String endpointId) {}

  private final TaskManager tasks;
  private final RestoreService restore;
  private final SyncEngine sync;

  public TaskController(TaskManager tasks, RestoreService restore, SyncEngine sync) {
    this.tasks = tasks;
    this.restore = restore;
    this.sync = sync;
  }

  @PostMapping("/jobs/{id}/run")
  public ApiResponse<?> run(@PathVariable String id, HttpServletRequest r) throws Exception {
    return ApiResponse.ok(tasks.submit(id, actor(r)));
  }

  @PostMapping("/runs/{id}/pause")
  public ApiResponse<?> pause(@PathVariable String id, HttpServletRequest r) throws Exception {
    tasks.pause(id, actor(r));
    return ApiResponse.ok(Map.of());
  }

  @GetMapping("/runs/live")
  public ApiResponse<?> live() { return ApiResponse.ok(tasks.liveRuns()); }

  @GetMapping("/runs/{id}/files")
  public ApiResponse<?> files(@PathVariable String id, @RequestParam(defaultValue = "1") int page) throws Exception {
    return ApiResponse.ok(tasks.files(id,page));
  }

  @GetMapping("/runs/{id}/report")
  public ApiResponse<?> report(@PathVariable String id) throws Exception {
    return ApiResponse.ok(tasks.report(id));
  }

  @PostMapping("/jobs/{id}/cleanup")
  public ApiResponse<?> cleanup(@PathVariable String id, HttpServletRequest r) throws Exception {
    return ApiResponse.ok(tasks.cleanup(id, actor(r)));
  }

  @GetMapping("/snapshots/{id}")
  public ApiResponse<?> snapshot(@PathVariable String id) throws Exception {
    return ApiResponse.ok(sync.snapshot(id));
  }

  @GetMapping("/snapshots/{id}/plan")
  public ApiResponse<?> plan(@PathVariable String id) throws Exception {
    return ApiResponse.ok(restore.plan(id));
  }

  @PostMapping("/restores")
  public ApiResponse<?> restore(@RequestBody RestoreRequest body, HttpServletRequest r)
      throws Exception {
    return ApiResponse.ok(
        tasks.restore(body.snapshotId(), body.endpointId(), body.directory(), actor(r)));
  }
}
