package com.oadig.syncsrv;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import com.oadig.syncsrv.modules.auth.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
class AuthRolesHttpTest {
 private int request(String role,String method,String path)throws Exception{
  AuthService auth=mock(AuthService.class);when(auth.actor("token")).thenReturn("named-user");when(auth.role("named-user")).thenReturn(role);
  var request=new MockHttpServletRequest(method,path);request.setCookies(new Cookie("SYNC_SESSION","token"));request.addHeader("X-SyncSrv-Request","1");
  var response=new MockHttpServletResponse();new AuthFilter(auth).doFilter(request,response,new MockFilterChain());return response.getStatus();
 }
 @Test void auditorCanReadExportAndVerifyButCannotModifyConfiguration()throws Exception{
  assertEquals(200,request("AUDITOR","GET","/api/state"));assertEquals(200,request("AUDITOR","POST","/api/audit/verify"));
  assertEquals(403,request("AUDITOR","PUT","/api/settings"));assertEquals(403,request("AUDITOR","GET","/api/accounts"));
 }
 @Test void operatorCannotManageCredentialsOrAccounts()throws Exception{
  assertEquals(403,request("OPERATOR","PUT","/api/endpoints"));assertEquals(403,request("OPERATOR","PUT","/api/accounts"));
  assertEquals(200,request("OPERATOR","POST","/api/jobs/job/run"));assertEquals(200,request("ADMIN","PUT","/api/endpoints"));
 }
}
