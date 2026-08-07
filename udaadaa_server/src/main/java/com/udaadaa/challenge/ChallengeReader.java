package com.udaadaa.challenge;

import com.udaadaa.member.MemberId;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Chat 등 다른 모듈이 챌린지 방 참가와 같은 트랜잭션 안에서 호출하는 공개 기능(CHA-02).
 *
 * <p>{@code enterForRoom}은 스스로 새 트랜잭션을 열지 않고(기본 {@code @Transactional} 전파는
 * REQUIRED) 호출부(Chat의 방 참가 트랜잭션)에 참여한다 — 방 참가와 챌린지 참여가 한쪽만
 * 성공하는 상태를 막기 위함이다.
 */
public interface ChallengeReader {

    /**
     * 챌린지 방 참가에 맞춰 참여를 만든다. 이미 같은 방으로 참여한 적이 있으면 아무 것도
     * 하지 않는다(멱등, CHA-03). "이미 다른 챌린지에 참여 중"이어도 막지 않는다 — 여러 방에
     * 동시에 참여하는 걸 막는 규칙은 기존에도 없었다.
     */
    void enterForRoom(MemberId memberId, UUID roomId, LocalDate startDay, LocalDate endDay);
}
