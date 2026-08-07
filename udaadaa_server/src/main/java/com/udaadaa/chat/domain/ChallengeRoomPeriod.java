package com.udaadaa.chat.domain;

import java.time.LocalDate;

/**
 * 방이 챌린지 방인지 판단하는 데 쓰는 최소 정보(Phase 4 CHA-01: start_day·end_day가 둘 다
 * 있으면 챌린지 방, 새 컬럼을 추가하지 않고 기존 기준을 그대로 쓴다).
 */
public record ChallengeRoomPeriod(LocalDate startDay, LocalDate endDay) {

    public boolean isChallengeRoom() {
        return startDay != null && endDay != null;
    }
}
