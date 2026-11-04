package com.oadig.syncsrv.shared.web;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class RequestFailureStatusTest {
 @RestController static class Fixture {
  @PostMapping("/fixture") public String post(@RequestParam("required")String value){return value;}
 }
 @Test void wrongMethodIs405WithAllowHeaderInsteadOf500() throws Exception {
  MockMvcBuilders.standaloneSetup(new Fixture()).setControllerAdvice(new GlobalExceptionHandler()).build()
   .perform(get("/fixture")).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow","POST"))
   .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
 }
 @Test void missingParameterIs400InsteadOf500() throws Exception {
  MockMvcBuilders.standaloneSetup(new Fixture()).setControllerAdvice(new GlobalExceptionHandler()).build()
   .perform(post("/fixture")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
 }
}
