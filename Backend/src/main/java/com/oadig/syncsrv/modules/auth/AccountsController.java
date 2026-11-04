package com.oadig.syncsrv.modules.auth;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/accounts")
public final class AccountsController {
  private final AuthService auth;
  public AccountsController(AuthService auth) { this.auth=auth; }
  public record Edit(String username,String password,String role,boolean disabled) {}
  @GetMapping public ApiResponse<?> list() throws Exception { return ApiResponse.ok(auth.accounts()); }
  @PutMapping public ApiResponse<?> save(@RequestBody Edit e,HttpServletRequest r) throws Exception {
    auth.saveAccount(e.username(),e.password(),e.role(),e.disabled(),(String)r.getAttribute("actor"));
    return ApiResponse.ok(java.util.Map.of());
  }
}
