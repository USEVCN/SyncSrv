package com.oadig.syncsrv.modules.config;

import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.shared.Models.*;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ConfigurationController {
  private final ConfigurationService config;
  private final TaskManager tasks;

  public ConfigurationController(ConfigurationService config, TaskManager tasks) {
    this.config = config;
    this.tasks = tasks;
  }

  @PostMapping("/endpoints")
  public ApiResponse<?> endpoint(@RequestBody Endpoint endpoint, HttpServletRequest r)
      throws Exception {
    idle();
    return ApiResponse.ok(config.saveEndpoint(endpoint, actor(r)));
  }

  @DeleteMapping("/endpoints/{id}")
  public ApiResponse<?> delete(@PathVariable String id, HttpServletRequest r) throws Exception {
    idle();
    config.deleteEndpoint(id, actor(r));
    return ApiResponse.ok(Map.of());
  }

  @PostMapping("/endpoints/{id}/probe")
  public ApiResponse<?> probe(@PathVariable String id, HttpServletRequest r) throws Exception {
    return ApiResponse.ok(config.probe(id, actor(r)));
  }

  @PostMapping("/jobs")
  public ApiResponse<?> job(@RequestBody Job job, HttpServletRequest r) throws Exception {
    // saveJob forbids changing an existing task's 源目录、备份库与归档库 and source binding.
    // Strategy updates are safe: the current execution retains its configuration snapshot.
    return ApiResponse.ok(config.saveJob(job, actor(r)));
  }

  @PutMapping("/settings")
  public ApiResponse<?> settings(@RequestBody TaskManager.Settings body, HttpServletRequest r)
      throws Exception {
    tasks.settings(body, actor(r));
    return ApiResponse.ok(tasks.settings());
  }

  private void idle() {
    if (tasks.busy()) throw new IllegalArgumentException("请等待执行结束或暂停后修改连接和任务");
  }

  public static String actor(HttpServletRequest r) {
    return (String) r.getAttribute("actor");
  }
}
