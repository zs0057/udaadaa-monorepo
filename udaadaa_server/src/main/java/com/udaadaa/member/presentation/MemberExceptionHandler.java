package com.udaadaa.member.presentation;

import com.udaadaa.common.web.ApiError;
import com.udaadaa.member.application.MemberNotFoundException;
import com.udaadaa.member.application.NicknameAlreadyExistsException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MemberController.class)
class MemberExceptionHandler {

    @ExceptionHandler(MemberNotFoundException.class)
    ResponseEntity<ApiError> memberNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("MEMBER_NOT_FOUND", "회원 프로필을 찾을 수 없습니다.", request));
    }

    @ExceptionHandler(NicknameAlreadyExistsException.class)
    ResponseEntity<ApiError> nicknameAlreadyExists(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("NICKNAME_ALREADY_EXISTS", "이미 사용 중인 닉네임입니다.", request));
    }
}
