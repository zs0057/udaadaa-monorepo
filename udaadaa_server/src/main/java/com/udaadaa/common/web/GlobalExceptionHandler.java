package com.udaadaa.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> validationException(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("INVALID_REQUEST", "요청값이 올바르지 않습니다.", request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResourceFoundException(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "요청한 경로를 찾을 수 없습니다.", request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpectedException(Exception exception, HttpServletRequest request) {
        ApiError error = ApiError.of("INTERNAL_ERROR", "요청을 처리하지 못했습니다.", request);
        log.error("Unhandled request failure traceId={}", error.traceId(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error);
    }
}
