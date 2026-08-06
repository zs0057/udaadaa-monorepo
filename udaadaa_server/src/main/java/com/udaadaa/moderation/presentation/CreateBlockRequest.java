package com.udaadaa.moderation.presentation;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

record CreateBlockRequest(
        @NotNull UUID blockedMemberId
) {
}
