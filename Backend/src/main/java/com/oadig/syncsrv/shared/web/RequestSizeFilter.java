package com.oadig.syncsrv.shared.web;

import com.oadig.syncsrv.shared.Json;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds JSON before deserialization, including chunked requests without Content-Length. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestSizeFilter extends OncePerRequestFilter {
 static final long MAXIMUM=1024*1024;
 private static final class TooLarge extends IOException {}
 @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getRequestURI().startsWith("/api/")&&!r.getRequestURI().startsWith("/agent/v1/");}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  if(request.getContentLengthLong()>MAXIMUM){reject(response);return;}
  var bounded=new HttpServletRequestWrapper(request){
   private ServletInputStream limited;
   @Override public ServletInputStream getInputStream()throws IOException{
    if(limited!=null)return limited;var input=super.getInputStream();
    limited=new ServletInputStream(){long count;
     private void count(int n)throws TooLarge{if(n>0){count+=n;if(count>MAXIMUM)throw new TooLarge();}}
     public int read()throws IOException{int v=input.read();count(v<0?0:1);return v;}
     public int read(byte[] bytes,int offset,int length)throws IOException{int n=input.read(bytes,offset,(int)Math.min(length,MAXIMUM-count+1));count(n);return n;}
     public boolean isFinished(){return input.isFinished();}public boolean isReady(){return input.isReady();}public void setReadListener(ReadListener listener){input.setReadListener(listener);}
     public void close()throws IOException{input.close();}
    };return limited;
   }
   @Override public BufferedReader getReader()throws IOException{return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
  };
  try{chain.doFilter(bounded,response);}catch(Exception e){
   Throwable reason=e;while(reason!=null&&!(reason instanceof TooLarge))reason=reason.getCause();
   if(reason!=null&&!response.isCommitted()){reject(response);return;}
   if(e instanceof ServletException x)throw x;if(e instanceof IOException x)throw x;throw new ServletException(e);
  }
 }
 public static boolean exceeded(Throwable e){while(e!=null){if(e instanceof TooLarge)return true;e=e.getCause();}return false;}
 private static void reject(HttpServletResponse response)throws IOException{
  response.resetBuffer();response.setStatus(413);response.setContentType("application/json;charset=UTF-8");
  response.getWriter().write(Json.write(new ApiResponse<>(false,null,"PAYLOAD_TOO_LARGE","控制请求不能超过 1 MiB；文件数据请使用直传协议")));
 }
}
