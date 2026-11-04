package com.oadig.syncsrv.modules.archive;

import static com.oadig.syncsrv.modules.config.ConfigurationController.actor;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs/{jobId}/archive")
public final class ArchiveController {
  public record Execute(String planId, boolean confirmed) {}
  private final ArchiveService archive;
  private final TaskManager tasks;
  public ArchiveController(Store store, ConfigurationService config, TaskManager tasks) {
    archive = new ArchiveService(store, config); this.tasks = tasks;
  }
  @PostMapping("/preview")
  public ApiResponse<?> preview(@PathVariable String jobId, @RequestBody ArchiveModels.Request body,
      HttpServletRequest r) throws Exception {
    return ApiResponse.ok(archive.preview(jobId, body, actor(r)));
  }
  @GetMapping("/plans")
  public ApiResponse<?> plans(@PathVariable String jobId) throws Exception {
    return ApiResponse.ok(archive.plans(jobId));
  }
  @GetMapping("/plans/{planId}")
  public ApiResponse<?> plan(@PathVariable String jobId, @PathVariable String planId) throws Exception {
    return ApiResponse.ok(archive.view(jobId, planId));
  }
  @PostMapping("/execute")
  public ApiResponse<?> execute(@PathVariable String jobId, @RequestBody Execute body,
      HttpServletRequest r) throws Exception {
    return ApiResponse.ok(tasks.archive(jobId, body.planId(), body.confirmed(), actor(r)));
  }
}
