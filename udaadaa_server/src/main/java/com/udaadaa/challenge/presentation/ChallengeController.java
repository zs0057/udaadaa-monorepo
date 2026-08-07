package com.udaadaa.challenge.presentation;

import com.udaadaa.challenge.application.ChallengeApplicationService;
import com.udaadaa.common.security.CurrentUserProvider;
import com.udaadaa.member.MemberId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/challenges")
class ChallengeController {

    private final CurrentUserProvider currentUserProvider;
    private final ChallengeApplicationService challengeApplicationService;

    ChallengeController(
            CurrentUserProvider currentUserProvider,
            ChallengeApplicationService challengeApplicationService
    ) {
        this.currentUserProvider = currentUserProvider;
        this.challengeApplicationService = challengeApplicationService;
    }

    @GetMapping("/me")
    ChallengeStatusResponse getMyStatus() {
        return ChallengeStatusResponse.from(challengeApplicationService.getMyStatus(currentMemberId()));
    }

    @GetMapping("/me/history")
    List<ChallengeHistoryItemResponse> getMyHistory() {
        return challengeApplicationService.getHistory(currentMemberId()).stream()
                .map(ChallengeHistoryItemResponse::from)
                .toList();
    }

    /**
     * 일반(방 없는, 14일 고정) 챌린지 참여. 방 기반 참여는 별도 엔드포인트를 두지 않는다 —
     * CHA-02 결정대로 POST /api/v1/chat/rooms/{roomId}/participants가 챌린지 방이면
     * 같은 트랜잭션 안에서 함께 처리한다.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void enterGeneral() {
        challengeApplicationService.enterGeneral(currentMemberId());
    }

    private MemberId currentMemberId() {
        return MemberId.from(currentUserProvider.currentUser().id());
    }
}
