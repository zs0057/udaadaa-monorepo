package com.udaadaa.common.security;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    public AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new AccessDeniedException("Authenticated JWT is required");
        }
        return new AuthenticatedUser(UUID.fromString(jwtAuthentication.getToken().getSubject()));
    }
}
