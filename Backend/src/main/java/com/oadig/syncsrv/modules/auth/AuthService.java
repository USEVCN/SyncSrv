package com.oadig.syncsrv.modules.auth;

import static com.oadig.syncsrv.platform.persistence.Store.Change.*;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Ids;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class AuthService {
  public record Account(String username, String passwordHash, String role, boolean disabled) {
    public Account(String username, String passwordHash) { this(username, passwordHash, "ADMIN", false); }
    public String effectiveRole() { return role == null ? "ADMIN" : role; }
  }
  public record AccountView(String username, String role, boolean disabled) {}

  private record Session(String actor, Instant expires) {}

  private record Attempts(int failures, Instant reset) {}

  private final Store store;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final Map<String, Session> sessions = new LinkedHashMap<>(32, 0.75f, true);
  private final Map<String, Attempts> attempts = new LinkedHashMap<>();
  private final Semaphore passwordSlots = new Semaphore(4);
  private final String dummyHash = passwords.encode(Ids.next());
  private final int maximumSessions;
  private final int maximumUserSessions;

  public AuthService(Store store, String initialPassword) throws Exception {
    this(store, initialPassword, 256, 16);
  }

  AuthService(Store store, String initialPassword, int maximumSessions, int maximumUserSessions) throws Exception {
    this.store = store;
    this.maximumSessions = maximumSessions;
    this.maximumUserSessions = maximumUserSessions;
    if (maximumSessions < 1 || maximumUserSessions < 1) throw new IllegalArgumentException("会话容量无效");
    if (store.get("account", "admin", Account.class) == null) {
      validatePassword(initialPassword);
      Account admin = new Account("admin", passwords.encode(initialPassword));
      store.commit(
          "system",
          "auth.initialized",
          "admin",
          Map.of("username", "admin"),
          put("account", "admin", admin));
    }
  }

  public String login(String username, String password, String remote) throws Exception {
    String name = username == null ? "" : username;
    String source = "source:" + remote;
    String accountKey = "account:" + name;
    Instant now = Instant.now();
    synchronized (this) {
      attempts.entrySet().removeIf(e -> !e.getValue().reset().isAfter(now));
      if (failures(source) >= 8 || failures(accountKey) >= 32)
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请稍后再试");
    }
    if (!passwordSlots.tryAcquire())
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录请求繁忙，请稍后重试");
    try {
      Account account = store.get("account", name, Account.class);
      String hash = account == null ? dummyHash : account.passwordHash();
      boolean valid = password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72
          && passwords.matches(password, hash) && account != null && !account.disabled();
      synchronized (this) {
        // A password/role change while hashing must not create a session from stale credentials.
        Account current = store.get("account", name, Account.class);
        valid = valid && current != null && !current.disabled() && current.passwordHash().equals(hash);
        if (!valid) {
          recordFailure(source, now); recordFailure(accountKey, now);
          store.commit("anonymous", "auth.login_failed", name.isBlank() ? "unknown" : name, Map.of("remote", remote));
          throw new IllegalArgumentException("用户名或密码不正确");
        }
        attempts.remove(source); attempts.remove(accountKey);
        sessions.entrySet().removeIf(e -> !e.getValue().expires().isAfter(now));
        while (sessions.values().stream().filter(e -> e.actor().equals(name)).count() >= maximumUserSessions)
          sessions.remove(sessions.entrySet().stream().filter(e -> e.getValue().actor().equals(name)).findFirst().orElseThrow().getKey());
        while (sessions.size() >= maximumSessions) sessions.remove(sessions.keySet().iterator().next());
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        store.commit(name, "auth.login", name, Map.of("remote", remote));
        sessions.put(Ids.textHash(token), new Session(name, now.plusSeconds(28800)));
        return token;
      }
    } finally { passwordSlots.release(); }
  }

  private int failures(String key) { Attempts a = attempts.get(key); return a == null ? 0 : a.failures(); }
  private void recordFailure(String key, Instant now) {
    Attempts old = attempts.getOrDefault(key, new Attempts(0, now.plusSeconds(300)));
    if (!attempts.containsKey(key) && attempts.size() >= 8192) attempts.remove(attempts.keySet().iterator().next());
    attempts.put(key, new Attempts(old.failures() + 1, old.reset()));
  }

  public synchronized String actor(String token) {
    if (token == null) return null;
    Session s = sessions.get(Ids.textHash(token));
    if (s == null || s.expires().isBefore(Instant.now())) {
      sessions.remove(Ids.textHash(token));
      return null;
    }
    return s.actor();
  }

  public synchronized void logout(String token) throws Exception {
    String actor = actor(token);
    if (actor != null) {
      store.commit(actor, "auth.logout", actor, Map.of());
      sessions.remove(Ids.textHash(token));
    }
  }

  public synchronized void change(String current, String next, String actor) throws Exception {
    Account old = store.get("account", actor, Account.class);
    if (current == null || !passwords.matches(current, old.passwordHash()))
      throw new IllegalArgumentException("当前密码不正确");
    validatePassword(next);
    Account fresh = new Account(actor, passwords.encode(next), old.effectiveRole(), false);
    store.commit(actor, "auth.password_changed", actor, Map.of(), put("account", actor, fresh));
    sessions.entrySet().removeIf(e -> e.getValue().actor().equals(actor));
  }

  public synchronized String role(String actor) throws Exception {
    Account a = actor == null ? null : store.get("account", actor, Account.class);
    return a == null || a.disabled() ? null : a.effectiveRole();
  }

  public List<AccountView> accounts() throws Exception {
    return store.list("account", Account.class).stream()
        .map(a -> new AccountView(a.username(), a.effectiveRole(), a.disabled())).toList();
  }

  public synchronized void saveAccount(String name, String password, String role, boolean disabled, String actor) throws Exception {
    if (!"ADMIN".equals(role(actor))) throw new IllegalArgumentException("需要管理员权限");
    if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
        || !List.of("ADMIN", "OPERATOR", "AUDITOR").contains(role)) throw new IllegalArgumentException("账号或角色无效");
    Account old = store.get("account", name, Account.class);
    if (old == null || password != null && !password.isBlank()) validatePassword(password);
    if (old != null && "ADMIN".equals(old.effectiveRole()) && (disabled || !"ADMIN".equals(role))
        && accounts().stream().filter(a -> !a.disabled() && "ADMIN".equals(a.role())).count() <= 1)
      throw new IllegalArgumentException("必须保留至少一个启用的管理员");
    String hash = password == null || password.isBlank() ? old.passwordHash() : passwords.encode(password);
    store.commit(actor, "auth.account_saved", name, Map.of("role",role,"disabled",disabled),
        put("account", name, new Account(name,hash,role,disabled)));
    sessions.entrySet().removeIf(e -> e.getValue().actor().equals(name));
  }

  private void validatePassword(String value) {
    if (value == null || value.length() < 10 || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException("首次启动请设置 SYNCSRV_INITIAL_PASSWORD，密码至少 10 个字符且不超过 72 字节");
  }
}
