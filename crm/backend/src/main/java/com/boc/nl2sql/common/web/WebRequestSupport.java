package com.boc.nl2sql.common.web;

import jakarta.servlet.http.HttpServletRequest;

/** 提供请求链路元数据的统一读取方式，避免控制器依赖过滤器的内部属性名称。 */
public final class WebRequestSupport {
    private WebRequestSupport() {
    }

    public static String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
