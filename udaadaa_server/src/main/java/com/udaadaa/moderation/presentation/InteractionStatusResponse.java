package com.udaadaa.moderation.presentation;

import com.udaadaa.member.MemberId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

record InteractionStatusResponse(
        Map<UUID, Boolean> statuses
) {

    static InteractionStatusResponse from(Map<MemberId, Boolean> statuses) {
        Map<UUID, Boolean> result = new LinkedHashMap<>();
        statuses.forEach((memberId, canInteract) -> result.put(memberId.value(), canInteract));
        return new InteractionStatusResponse(result);
    }
}
