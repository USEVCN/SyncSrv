package com.oadig.syncsrv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.oadig.syncsrv.app.SpaController;
import com.oadig.syncsrv.modules.auth.AuthFilter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;

class WebCachePolicyTest {
  private AnnotationConfigWebApplicationContext context;
  private MockMvc mvc;

  @Configuration
  @EnableWebMvc
  @Import(SpaController.class)
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
    // These public resources must not depend on an authenticated session.
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new AuthFilter(null)).build();
  }

  @AfterEach
  void close() {
    context.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/index.html", "/frontend-identity.json", "/login", "/jobs", "/agents"})
  void releaseEntryAndMetadataCannotBeStoredEvenOnHeadOrRevalidation(String path) throws Exception {
    for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.HEAD}) {
      mvc.perform(request(method, path))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"));
      mvc.perform(request(method, path).header("If-Modified-Since", "Fri, 23 Oct 2099 00:00:00 GMT"))
          .andExpect(header().string("Cache-Control", "no-store"));
    }
  }

  @Test
  void fingerprintedAssetsRetainTheirCachePolicy() throws Exception {
    mvc.perform(request(HttpMethod.GET, "/assets/missing-cache-fixture.js"))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist("Cache-Control"));
  }
}
