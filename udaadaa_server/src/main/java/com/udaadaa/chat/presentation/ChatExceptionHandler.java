package com.udaadaa.chat.presentation;

import com.udaadaa.chat.application.InvalidMessageTypeException;
import com.udaadaa.chat.application.RoomNotFoundException;
import com.udaadaa.common.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ChatController.class)
class ChatExceptionHandler {

    @ExceptionHandler(RoomNotFoundException.class)
    ResponseEntity<ApiError> roomNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("ROOM_NOT_FOUND", "채팅방을 찾을 수 없습니다.", request));
    }

    @ExceptionHandler(InvalidMessageTypeException.class)
    ResponseEntity<ApiError> invalidMessageType(HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("INVALID_REQUEST", "이 API로 만들 수 없는 메시지 타입입니다.", request));
    }
}
