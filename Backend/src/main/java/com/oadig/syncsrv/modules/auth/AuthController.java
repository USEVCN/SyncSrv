package com.oadig.syncsrv.modules.auth;

import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  public record Login(
      @NotBlank @Size(max = 64) String username, @NotNull @Size(max = 128) String password) {}

  public record Password(
      @NotNull @Size(max = 128) String current, @NotNull @Size(max = 128) String next) {}

  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @PostMapping("/login")
  public ApiResponse<?> login(
      @Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    String token = auth.login(body.username(), body.password(), request.getRemoteAddr());
    cookie(response, request, token, 28800);
    return ApiResponse.ok(Map.of("username", body.username(), "role", auth.role(body.username())));
  }

  @GetMapping("/me")
  public ApiResponse<?> me(HttpServletRequest request) throws Exception {
    return ApiResponse.ok(Map.of("username", request.getAttribute("actor"), "role", auth.role((String)request.getAttribute("actor"))));
  }

  @PostMapping("/logout")
  public ApiResponse<?> logout(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    auth.logout(AuthFilter.token(request));
    cookie(response, request, "", 0);
    return ApiResponse.ok(Map.of());
  }

  @PostMapping("/password")
  public ApiResponse<?> password(
      @Valid @RequestBody Password body, HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    auth.change(body.current(), body.next(), (String) request.getAttribute("actor"));
    cookie(response, request, "", 0);
    return ApiResponse.ok(Map.of());
  }

  private void cookie(
      HttpServletResponse response, HttpServletRequest request, String value, int maxAge) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("SYNC_SESSION", value)
            .httpOnly(true)
            .secure(request.isSecure() || "true".equals(System.getenv("SYNCSRV_SECURE_COOKIE")))
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build()
            .toString());
  }
}
