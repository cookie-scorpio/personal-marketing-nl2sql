package com.boc.nl2sql.common.exception;

/** 可安全映射到统一 API 错误结构的业务异常，code 是跨客户端稳定识别的错误码。 */
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
