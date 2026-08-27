package com.boc.nl2sql.common.api;

/** 统一 API 响应，字段命名由 Jackson 转换为 snake_case。 */
public record ApiResponse<T>(int code, String message, T data, String requestId) {

    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>(0, "处理成功", data, requestId);
    }

    public static <T> ApiResponse<T> error(int code, String message, String requestId) {
        return new ApiResponse<>(code, message, null, requestId);
    }
}
