package com.udaadaa.moderation.presentation;

import java.util.List;
import java.util.UUID;

record BlockedMembersResponse(
        List<UUID> blockedMemberIds
) {
}
