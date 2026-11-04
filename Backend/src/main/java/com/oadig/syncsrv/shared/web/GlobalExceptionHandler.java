package com.oadig.syncsrv.shared.web;

import com.oadig.syncsrv.modules.jobs.TaskManager;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler({
    org.springframework.web.HttpRequestMethodNotSupportedException.class,
    org.springframework.web.HttpMediaTypeNotSupportedException.class,
    org.springframework.web.HttpMediaTypeNotAcceptableException.class,
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  public ResponseEntity<ApiResponse<Void>> invalidRequest(Exception e) {
    org.springframework.web.ErrorResponse error = (org.springframework.web.ErrorResponse) e;
    return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
        .body(ApiResponse.error("INVALID_REQUEST", "请求方法、数据类型或参数不正确"));
  }

  @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
  public void disconnected(org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
    new org.springframework.web.util.DisconnectedClientHelper(getClass().getName())
        .checkAndLogClientDisconnectedException(e);
  }

  @ExceptionHandler(com.oadig.syncsrv.platform.storage.SmbFailure.class)
  public ResponseEntity<ApiResponse<Void>> smb(
      com.oadig.syncsrv.platform.storage.SmbFailure failure) {
    org.slf4j.LoggerFactory.getLogger(getClass()).warn("SMB operation failed: {}", failure.code(), SafeMessages.logged(failure));
    return ResponseEntity.status(502).body(ApiResponse.error(failure.code(), failure.getMessage()));
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  public ResponseEntity<ApiResponse<Void>> notFound(Exception e) {
    return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "请求的资源不存在"));
  }

  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> status(
      org.springframework.web.server.ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(
            ApiResponse.error(
                "REQUEST_FAILED", e.getReason() == null ? "请求的资源不可用" : e.getReason()));
  }

  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<ApiResponse<Void>> input(Exception e) {
    if(RequestSizeFilter.exceeded(e))return ResponseEntity.status(413).body(ApiResponse.error("PAYLOAD_TOO_LARGE","控制请求不能超过 1 MiB"));
    return ResponseEntity.badRequest()
        .body(
            new ApiResponse<>(
                false,
                null,
                "INVALID_INPUT",
                e instanceof HttpMessageNotReadableException
                    ? "请求数据格式不正确"
                    : TaskManager.safeMessage(e)));
  }

  @ExceptionHandler(SecurityException.class)
  public ResponseEntity<ApiResponse<Void>> denied(SecurityException e) {
    return ResponseEntity.status(403)
        .body(ApiResponse.error("FORBIDDEN", TaskManager.safeMessage(e)));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
    if(RequestSizeFilter.exceeded(e))return ResponseEntity.status(413).body(ApiResponse.error("PAYLOAD_TOO_LARGE","控制请求不能超过 1 MiB"));
    org.slf4j.LoggerFactory.getLogger(getClass()).error("API request failed", SafeMessages.logged(e));
    return ResponseEntity.internalServerError()
        .body(
            new ApiResponse<>(
                false,
                null,
                "OPERATION_FAILED",
                e instanceof java.io.IOException || e instanceof IllegalStateException
                    ? TaskManager.safeMessage(e)
                    : "操作失败，请检查连接或服务日志"));
  }
}
