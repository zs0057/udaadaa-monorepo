package com.udaadaa.chat.presentation.websocket;

import java.security.Principal;
import java.util.UUID;

record StompPrincipal(UUID memberId) implements Principal {

    @Override
    public String getName() {
        return memberId.toString();
    }
}
