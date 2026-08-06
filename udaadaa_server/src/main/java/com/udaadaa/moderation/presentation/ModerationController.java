package com.udaadaa.moderation.presentation;

import com.udaadaa.common.security.CurrentUserProvider;
import com.udaadaa.member.MemberId;
import com.udaadaa.moderation.application.ModerationApplicationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/moderation")
class ModerationController {

    private final CurrentUserProvider currentUserProvider;
    private final ModerationApplicationService moderationApplicationService;

    ModerationController(
            CurrentUserProvider currentUserProvider,
            ModerationApplicationService moderationApplicationService
    ) {
        this.currentUserProvider = currentUserProvider;
        this.moderationApplicationService = moderationApplicationService;
    }

    @PostMapping("/blocks")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void block(@Valid @RequestBody CreateBlockRequest request) {
        moderationApplicationService.block(currentMemberId(), MemberId.from(request.blockedMemberId()));
    }

    @DeleteMapping("/blocks/{blockedMemberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unblock(@PathVariable UUID blockedMemberId) {
        moderationApplicationService.unblock(currentMemberId(), MemberId.from(blockedMemberId));
    }

    @GetMapping("/blocks")
    BlockedMembersResponse getBlockedMembers() {
        List<UUID> blockedMemberIds = moderationApplicationService.getBlockedMembers(currentMemberId()).stream()
                .map(MemberId::value)
                .toList();
        return new BlockedMembersResponse(blockedMemberIds);
    }

    @GetMapping("/interaction-status")
    InteractionStatusResponse getInteractionStatus(
            @RequestParam(required = false) List<UUID> targetIds
    ) {
        Set<MemberId> targets = targetIds == null
                ? Set.of()
                : targetIds.stream().map(MemberId::from).collect(Collectors.toSet());
        return InteractionStatusResponse.from(
                moderationApplicationService.canInteractWith(currentMemberId(), targets)
        );
    }

    private MemberId currentMemberId() {
        return MemberId.from(currentUserProvider.currentUser().id());
    }
}
