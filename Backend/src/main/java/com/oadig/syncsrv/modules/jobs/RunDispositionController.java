package com.oadig.syncsrv.modules.jobs;

import static com.oadig.syncsrv.modules.config.ConfigurationController.actor;

import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runs/{id}/disposition")
public class RunDispositionController {
  private final RunDispositions dispositions;

  public RunDispositionController(RunDispositions dispositions) {
    this.dispositions = dispositions;
  }

  @GetMapping
  public ApiResponse<?> read(@PathVariable String id, @RequestParam(defaultValue = "0") int before)
      throws Exception {
    return ApiResponse.ok(dispositions.read(id, before));
  }

  @PutMapping
  public ApiResponse<?> save(
      @PathVariable String id, @RequestBody RunDispositions.Input input, HttpServletRequest request)
      throws Exception {
    return ApiResponse.ok(dispositions.save(id, input, actor(request)));
  }
}
