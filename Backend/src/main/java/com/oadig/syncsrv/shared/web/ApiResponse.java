package com.oadig.syncsrv.shared.web;

/** Same response contract as LabelEditor; domain errors remain explicit. */
public record ApiResponse<T>(boolean success, T data, String errorCode, String message) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, data, null, null);
  }

  public static ApiResponse<Void> error(String code, String message) {
    return new ApiResponse<>(false, null, code, message);
  }
}
