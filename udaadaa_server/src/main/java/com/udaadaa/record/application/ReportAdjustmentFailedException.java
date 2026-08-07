package com.udaadaa.record.application;

/**
 * 기존 deleteMyFeed()의 "No report data" / "Negative report data" 방어를 대체한다.
 * report 행이 없거나, 차감 결과가 음수가 될 상황이면 삭제 자체를 막는다.
 */
public class ReportAdjustmentFailedException extends RuntimeException {
}
