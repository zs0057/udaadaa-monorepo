package com.udaadaa.challenge.presentation;

import com.udaadaa.challenge.application.AlreadyChallengingException;
import com.udaadaa.common.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ChallengeController.class)
class ChallengeExceptionHandler {

    @ExceptionHandler(AlreadyChallengingException.class)
    ResponseEntity<ApiError> alreadyChallenging(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("ALREADY_CHALLENGING", "이미 진행 중인 챌린지가 있습니다.", request));
    }
}
