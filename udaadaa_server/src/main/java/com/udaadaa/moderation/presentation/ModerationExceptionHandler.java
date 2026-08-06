package com.udaadaa.moderation.presentation;

import com.udaadaa.common.web.ApiError;
import com.udaadaa.moderation.application.BlockedMemberNotFoundException;
import com.udaadaa.moderation.application.SelfBlockNotAllowedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ModerationController.class)
class ModerationExceptionHandler {

    @ExceptionHandler(SelfBlockNotAllowedException.class)
    ResponseEntity<ApiError> selfBlockNotAllowed(HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("INVALID_REQUEST", "자기 자신은 차단할 수 없습니다.", request));
    }

    @ExceptionHandler(BlockedMemberNotFoundException.class)
    ResponseEntity<ApiError> blockedMemberNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("MEMBER_NOT_FOUND", "차단하려는 회원을 찾을 수 없습니다.", request));
    }
}
