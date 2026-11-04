package com.oadig.syncsrv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.oadig.syncsrv.app.SpaController;
import com.oadig.syncsrv.shared.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

class SpaRoutingTest {
  private AnnotationConfigWebApplicationContext context;
  private MockMvc mvc;

  @Configuration
  @EnableWebMvc
  @Import({SpaController.class, GlobalExceptionHandler.class})
  static class WebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
      registry.addResourceHandler("/**").addResourceLocations("classpath:/static/");
    }
  }

  @BeforeEach
  void setup() {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(WebConfig.class);
    context.refresh();
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @AfterEach
  void close() {
    context.close();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/agents",
        "/login",
        "/dashboard",
        "/storages",
        "/jobs",
        "/runs",
        "/snapshots",
        "/audit",
        "/settings"
      })
  void directPageRequestsForwardToTheFrontend(String path) throws Exception {
    mvc.perform(get(path).accept("text/html"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/index.html"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/assets/missing-routing-fixture.js",
        "/api/missing-routing-fixture",
        "/agent/releases/missing/agent.exe"
      })
  void missingResourcesRemain404InsteadOfBecomingServerErrorsOrPages(String path) throws Exception {
    mvc.perform(get(path))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
  }
}
