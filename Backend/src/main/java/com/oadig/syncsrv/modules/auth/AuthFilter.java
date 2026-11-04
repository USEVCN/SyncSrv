package com.oadig.syncsrv.modules.auth;

import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AuthFilter extends OncePerRequestFilter {
  private final AuthService auth;

  public AuthFilter(AuthService auth) {
    this.auth = auth;
  }

  public static String token(HttpServletRequest request) {
    if (request.getCookies() != null)
      for (Cookie c : request.getCookies())
        if (c.getName().equals("SYNC_SESSION")) return c.getValue();
    return null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Referrer-Policy", "same-origin");
    response.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'"
            + " data:; font-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action"
            + " 'self'");
    response.setHeader("X-Request-ID", UUID.randomUUID().toString());
    String path = request.getRequestURI();
    // Entry pages must never outlive the hashed scripts removed by a release switch.
    // Keep fingerprinted assets cacheable; also refresh version/distribution metadata.
    if (!path.startsWith("/assets/")) response.setHeader("Cache-Control", "no-store");
    if (!path.startsWith("/api/")) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("Cache-Control", "no-store");
    if (!List.of("GET", "HEAD").contains(request.getMethod())) {
      boolean allowed = "1".equals(request.getHeader("X-SyncSrv-Request"));
      String origin = request.getHeader("Origin");
      if (origin != null) {
        try {
          URI u = URI.create(origin);
          allowed =
              allowed
                  && Objects.equals(u.getRawAuthority(), request.getHeader("Host"))
                  && List.of("http", "https").contains(u.getScheme());
        } catch (Exception e) {
          allowed = false;
        }
      }
      if (!allowed) {
        error(response, 403, "FORBIDDEN", "请求来源校验失败");
        return;
      }
    }
    String actor = auth.actor(token(request));
    if (actor == null && !path.equals("/api/auth/login") && !path.equals("/api/health")) {
      error(response, 401, "UNAUTHORIZED", "请先登录");
      return;
    }
    if (actor != null) {
      try {
        String role = auth.role(actor);
        boolean read = List.of("GET", "HEAD").contains(request.getMethod());
        boolean ownAuth = path.startsWith("/api/auth/") || path.equals("/api/audit/verify");
        boolean administration = path.contains("/retention") || path.startsWith("/api/accounts") || path.startsWith("/api/operations")
            || path.startsWith("/api/settings") || path.startsWith("/api/endpoints")
            || path.startsWith("/api/agents/invitations") || path.contains("/authorization");
        if (role == null || !ownAuth && (!read && "AUDITOR".equals(role)
            || administration && !"ADMIN".equals(role) && (!read || path.startsWith("/api/accounts")))) {
          error(response,403,"FORBIDDEN","当前账号无此操作权限"); return;
        }
        request.setAttribute("role", role);
      } catch (Exception e) { throw new ServletException(e); }
    }
    request.setAttribute("actor", actor);
    chain.doFilter(request, response);
  }

  private void error(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(Json.write(new ApiResponse<>(false, null, code, message)));
  }
}
