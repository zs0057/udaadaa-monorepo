package com.udaadaa.common.web;

import jakarta.servlet.http.HttpServletRequest;

public record ApiError(String code, String message, String traceId) {

    public static ApiError of(String code, String message, HttpServletRequest request) {
        Object traceId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return new ApiError(code, message, traceId == null ? "unknown" : traceId.toString());
    }
}
