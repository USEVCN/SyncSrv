package com.oadig.syncsrv.modules.audit;

import static com.oadig.syncsrv.modules.config.ConfigurationController.actor;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.*;
import com.oadig.syncsrv.shared.Models.*;
import com.oadig.syncsrv.shared.web.ApiResponse;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
  private final Store store;

  public AuditController(Store store) {
    this.store = store;
  }

  @GetMapping
  public ApiResponse<?> page(
      @RequestParam(defaultValue = "-1") long after, @RequestParam(defaultValue = "100") int limit)
      throws Exception {
    long start =
        after < 0
            ? Math.max(0, store.head().sequence() - Math.min(1000, Math.max(1, limit)))
            : after;
    return ApiResponse.ok(Map.of("head", store.head(), "events", store.events(start, limit)));
  }

  @PostMapping("/verify")
  public ApiResponse<?> verify(HttpServletRequest r) throws Exception {
    AuditHead head = store.verify();
    store.commit(actor(r), "audit.verified", "audit", head);
    return ApiResponse.ok(head);
  }

  @GetMapping("/public-key")
  public void key(HttpServletResponse response) throws Exception {
    response.setContentType("text/plain;charset=UTF-8");
    response.setHeader("Content-Disposition", "attachment; filename=audit-public.txt");
    response.getWriter().write(store.publicKey());
  }

  @GetMapping("/export")
  public void export(HttpServletRequest request, HttpServletResponse response) throws Exception {
    store.commit(actor(request), "audit.exported", "audit", Map.of());
    AuditHead head = store.verify();
    EvidenceHeader header = store.header(head);
    response.setContentType("application/x-ndjson;charset=UTF-8");
    response.setHeader(
        "Content-Disposition", "attachment; filename=syncsrv-audit-" + head.sequence() + ".jsonl");
    var out = response.getOutputStream();
    out.write((Json.write(header) + "\n").getBytes(StandardCharsets.UTF_8));
    long sequence = 0;
    while (sequence < head.sequence()) {
      List<AuditEvent> events =
          store.events(sequence, (int) Math.min(500, head.sequence() - sequence));
      if (events.isEmpty()) throw new IllegalStateException("审计导出期间记录缺失");
      for (AuditEvent event : events) {
        out.write((Json.write(event) + "\n").getBytes(StandardCharsets.UTF_8));
        sequence = event.sequence();
      }
    }
  }
}
