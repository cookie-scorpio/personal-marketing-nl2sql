package com.boc.nl2sql.common.web;

import jakarta.servlet.http.HttpServletRequest;

public final class WebRequestSupport {
    private WebRequestSupport() {
    }

    public static String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
