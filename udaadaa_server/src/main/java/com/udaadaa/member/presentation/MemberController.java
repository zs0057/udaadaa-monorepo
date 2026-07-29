package com.udaadaa.member.presentation;

import com.udaadaa.common.security.CurrentUserProvider;
import com.udaadaa.member.MemberId;
import com.udaadaa.member.application.MemberApplicationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members/me")
class MemberController {

    private final CurrentUserProvider currentUserProvider;
    private final MemberApplicationService memberApplicationService;

    MemberController(
            CurrentUserProvider currentUserProvider,
            MemberApplicationService memberApplicationService
    ) {
        this.currentUserProvider = currentUserProvider;
        this.memberApplicationService = memberApplicationService;
    }

    @PostMapping("/initialize")
    MemberProfileResponse initialize() {
        return MemberProfileResponse.from(memberApplicationService.initialize(currentMemberId()));
    }

    @GetMapping
    MemberProfileResponse get() {
        return MemberProfileResponse.from(memberApplicationService.get(currentMemberId()));
    }

    @PatchMapping
    MemberProfileResponse update(@Valid @RequestBody UpdateMemberProfileRequest request) {
        return MemberProfileResponse.from(memberApplicationService.update(
                currentMemberId(),
                request.nickname(),
                request.height(),
                request.weight()
        ));
    }

    private MemberId currentMemberId() {
        return MemberId.from(currentUserProvider.currentUser().id());
    }
}
