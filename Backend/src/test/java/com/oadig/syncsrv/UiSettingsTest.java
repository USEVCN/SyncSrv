package com.oadig.syncsrv;

import com.oadig.syncsrv.modules.settings.*;
import com.oadig.syncsrv.shared.Json;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UiSettingsTest {
  @Test void persistsNameAndTypographyWithAnAuditRecordAndRejectsInvalidScales() throws Exception {
    var memory = new MemoryStore();
    var settings = new UiSettings(memory.store);
    var defaults = settings.get();
    assertEquals("SyncSrv", defaults.systemName());
    var typography = new LinkedHashMap<>(defaults.typography()); typography.put("body", 15);
    var next = settings.save(new UiSettings.Value(" 本地 SyncSrv ", "备份中心", typography), "admin");
    assertEquals("本地 SyncSrv", next.systemName());
    assertEquals(next, new UiSettings(memory.store).get());
    assertTrue(memory.actions.contains("ui-settings.saved"));
    typography.put("body", 49);
    assertThrows(IllegalArgumentException.class, () -> settings.save(new UiSettings.Value("SyncSrv", "", typography), "admin"));
    assertEquals(next, settings.get());
    typography.remove("body");
    assertThrows(IllegalArgumentException.class, () -> settings.save(new UiSettings.Value("SyncSrv", "", typography), "admin"));
    assertThrows(IllegalArgumentException.class, () -> settings.save(new UiSettings.Value(" ", "", defaults.typography()), "admin"));
  }

  @Test void requiresAdminForBothReadsAndWritesAndRejectsFractionalFontSizes() throws Exception {
    var controller = new SettingsController(new UiSettings(new MemoryStore().store));
    var request = new MockHttpServletRequest(); request.setAttribute("role", "AUDITOR");
    assertThrows(SecurityException.class, () -> controller.get(request));
    assertThrows(SecurityException.class, () -> controller.save(Json.MAPPER.valueToTree(UiSettings.defaults()), request));
    request.setAttribute("role", "ADMIN"); request.setAttribute("actor", "admin");
    var body = Json.MAPPER.valueToTree(UiSettings.defaults());
    ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("typography")).put("body",14.5);
    assertThrows(IllegalArgumentException.class, () -> controller.save(body, request));
  }
}
