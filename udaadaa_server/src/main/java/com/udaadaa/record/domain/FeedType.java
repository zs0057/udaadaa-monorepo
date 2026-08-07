package com.udaadaa.record.domain;

/**
 * Flutter의 FeedType enum(breakfast/lunch/dinner/snack/exercise/weight)과 1:1로 대응한다.
 * DB의 "FeedType" native enum 값도 동일한 소문자 문자열이라 {@link #dbValue()}가 그대로 쓰인다.
 */
public enum FeedType {
    breakfast,
    lunch,
    dinner,
    snack,
    exercise,
    weight;

    public String dbValue() {
        return name();
    }

    public boolean isMeal() {
        return this == breakfast || this == lunch || this == dinner || this == snack;
    }
}
