package com.oadig.syncsrv.modules.settings.tls;

import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/system/https")
public class HttpsController {
  private final HttpsService service;
  public HttpsController(HttpsService service) { this.service = service; }

  @GetMapping
  public ApiResponse<?> get(HttpServletRequest request) {
    admin(request); return ApiResponse.ok(service.status());
  }

  @PostMapping("/validate")
  public ApiResponse<?> validate(HttpServletRequest request) throws Exception {
    admin(request); return ApiResponse.ok(service.check(input(request)));
  }

  @PutMapping
  public ApiResponse<?> save(HttpServletRequest request) throws Exception {
    admin(request);
    return ApiResponse.ok(service.save(input(request), (String) request.getAttribute("actor")));
  }

  private static HttpsSettingsInput input(HttpServletRequest request) throws Exception {
    byte[] raw = request.getInputStream().readNBytes(196609);
    if (raw.length > 196608) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "证书请求过大");
    try { return Json.MAPPER.readValue(raw, HttpsSettingsInput.class); }
    catch (Exception e) { throw new IllegalArgumentException("HTTPS 配置格式不正确"); }
  }

  public static void admin(HttpServletRequest request) {
    if (!"ADMIN".equals(request.getAttribute("role"))) throw new SecurityException("仅管理员可管理系统设置");
  }
}
