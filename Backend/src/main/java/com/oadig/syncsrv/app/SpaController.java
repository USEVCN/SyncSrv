package com.oadig.syncsrv.app;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
  @GetMapping({
    "/login",
    "/dashboard",
    "/storages",
    "/jobs",
    "/agents",
    "/runs",
    "/snapshots",
    "/audit",
    "/settings"
  })
  public String index() {
    return "forward:/index.html";
  }
}
