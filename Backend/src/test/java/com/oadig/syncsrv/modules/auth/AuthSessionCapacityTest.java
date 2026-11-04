package com.oadig.syncsrv.modules.auth;

import static org.junit.jupiter.api.Assertions.*;
import com.oadig.syncsrv.platform.persistence.Store;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthSessionCapacityTest {
  @TempDir Path directory;
  @Test void oneAccountsLoginCapacityDoesNotLogOutAnotherAccount() throws Exception {
    try (Store store = new Store(directory)) {
      AuthService auth = new AuthService(store, "fixture-only-password", 5, 2);
      auth.saveAccount("operator", "fixture-user-password", "OPERATOR", false, "admin");
      String operator = auth.login("operator", "fixture-user-password", "fixture-source");
      String first = auth.login("admin", "fixture-only-password", "fixture-source");
      String second = auth.login("admin", "fixture-only-password", "fixture-source");
      String third = auth.login("admin", "fixture-only-password", "fixture-source");
      assertNull(auth.actor(first));
      assertEquals("admin", auth.actor(second));
      assertEquals("admin", auth.actor(third));
      assertEquals("operator", auth.actor(operator));
    }
  }
  @Test void changingPasswordRevokesOnlyThatUsersSessions() throws Exception {
    try (Store store = new Store(directory)) {
      AuthService auth = new AuthService(store, "fixture-only-password");
      auth.saveAccount("operator", "fixture-user-password", "OPERATOR", false, "admin");
      String operator = auth.login("operator", "fixture-user-password", "fixture-source");
      String administrator = auth.login("admin", "fixture-only-password", "fixture-source");
      auth.change("fixture-only-password", "fixture-replacement-password", "admin");
      assertNull(auth.actor(administrator));
      assertEquals("operator", auth.actor(operator));
    }
  }
}
