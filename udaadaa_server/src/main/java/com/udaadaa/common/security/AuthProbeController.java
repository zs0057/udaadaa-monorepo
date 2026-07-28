package com.udaadaa.common.security;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthProbeController {

    private final CurrentUserProvider currentUserProvider;

    AuthProbeController(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/me")
    AuthenticatedUserResponse me() {
        return new AuthenticatedUserResponse(currentUserProvider.currentUser().id());
    }

    record AuthenticatedUserResponse(UUID id) {
    }
}
