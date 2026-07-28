package com.udaadaa.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class SecurityErrorWriter {

    private SecurityErrorWriter() {
    }

    public static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        ApiError error = ApiError.of(code, message, request);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"%s\",\"message\":\"%s\",\"traceId\":\"%s\"}"
                .formatted(error.code(), error.message(), error.traceId()));
    }
}
