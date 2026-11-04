package com.oadig.syncsrv.modules.operations;

import com.oadig.syncsrv.modules.settings.tls.HttpsController;
import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/system/updates")
public class CenterUpdateController {
  private final CenterUpdateService service;
  public CenterUpdateController(CenterUpdateService service) { this.service = service; }
  public record SourceInput(String serverUrl, String expectedRevision) { }
  @GetMapping("/source")
  public ApiResponse<?> source(HttpServletRequest request) throws Exception {
    HttpsController.admin(request); return ApiResponse.ok(service.sourceConfiguration());
  }
  @PutMapping("/source")
  public ApiResponse<?> saveSource(HttpServletRequest request) throws Exception {
    HttpsController.admin(request);
    byte[] body=request.getInputStream().readNBytes(4097);
    if(body.length>4096)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"更新源请求过大");
    SourceInput input;
    try { input=Json.MAPPER.readValue(body,SourceInput.class);if(input==null)throw new IllegalArgumentException(); }
    catch(Exception malformed){throw new IllegalArgumentException("更新源配置格式不正确");}
    return ApiResponse.ok(service.saveServer(input.serverUrl(),input.expectedRevision(),(String)request.getAttribute("actor")));
  }
  @GetMapping("/check")
  public ApiResponse<CenterUpdateService.Result> check(HttpServletRequest request) {
    HttpsController.admin(request); return ApiResponse.ok(service.check());
  }
}
