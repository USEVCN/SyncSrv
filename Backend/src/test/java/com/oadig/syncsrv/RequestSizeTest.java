package com.oadig.syncsrv;
import static org.junit.jupiter.api.Assertions.*;
import com.oadig.syncsrv.shared.web.RequestSizeFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
class RequestSizeTest {
 @Test void oversizedDeclaredAndChunkedJsonAreRejectedBeforeParsing()throws Exception{
  for(boolean chunked:new boolean[]{false,true}){
   var request=new MockHttpServletRequest("POST","/api/auth/login"){@Override public long getContentLengthLong(){return chunked?-1:super.getContentLengthLong();}};
   request.setContent(new byte[1024*1024+1]);var response=new MockHttpServletResponse();
   new RequestSizeFilter().doFilter(request,response,(r,s)->{r.getInputStream().readAllBytes();});
   assertEquals(413,response.getStatus());assertTrue(response.getContentAsString().contains("PAYLOAD_TOO_LARGE"));
  }
 }
 @Test void normalMetadataRemainsReadable()throws Exception{
  var request=new MockHttpServletRequest("POST","/api/settings");request.setContent("{}".getBytes());var response=new MockHttpServletResponse();
  new RequestSizeFilter().doFilter(request,response,(r,s)->assertArrayEquals("{}".getBytes(),r.getInputStream().readAllBytes()));assertEquals(200,response.getStatus());
 }
}
