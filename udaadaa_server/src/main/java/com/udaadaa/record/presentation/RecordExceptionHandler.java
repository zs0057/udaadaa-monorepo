package com.udaadaa.record.presentation;

import com.udaadaa.common.web.ApiError;
import com.udaadaa.record.application.CalorieEstimationFailedException;
import com.udaadaa.record.application.FeedNotFoundException;
import com.udaadaa.record.application.ReportAdjustmentFailedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = RecordController.class)
class RecordExceptionHandler {

    @ExceptionHandler(FeedNotFoundException.class)
    ResponseEntity<ApiError> feedNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("FEED_NOT_FOUND", "해당 기록을 찾을 수 없습니다.", request));
    }

    @ExceptionHandler(ReportAdjustmentFailedException.class)
    ResponseEntity<ApiError> reportAdjustmentFailed(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("REPORT_ADJUSTMENT_FAILED", "리포트 데이터와 맞지 않아 삭제할 수 없습니다.", request));
    }

    @ExceptionHandler(CalorieEstimationFailedException.class)
    ResponseEntity<ApiError> calorieEstimationFailed(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("CALORIE_ESTIMATION_FAILED", "칼로리 추정에 실패했습니다.", request));
    }
}
