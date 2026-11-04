package com.oadig.syncsrv.modules.settings;

import com.oadig.syncsrv.modules.settings.tls.HttpsController;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system/settings")
public class SettingsController {
  private final UiSettings settings;
  public SettingsController(UiSettings settings) { this.settings = settings; }
  @GetMapping
  public ApiResponse<?> get(HttpServletRequest request) throws Exception {
    HttpsController.admin(request); return ApiResponse.ok(settings.get());
  }
  @PutMapping
  public ApiResponse<?> save(@RequestBody com.fasterxml.jackson.databind.JsonNode body, HttpServletRequest request) throws Exception {
    HttpsController.admin(request);
    if (body == null || !body.path("systemName").isTextual() || !body.path("description").isTextual()
        || !body.path("typography").isObject()) throw new IllegalArgumentException("系统设置格式不正确");
    for (var size : body.path("typography"))
      if (!size.isIntegralNumber() || !size.canConvertToInt()) throw new IllegalArgumentException("字号必须是整数");
    UiSettings.Value value = com.oadig.syncsrv.shared.Json.MAPPER.treeToValue(body, UiSettings.Value.class);
    return ApiResponse.ok(settings.save(value, (String) request.getAttribute("actor")));
  }
}
