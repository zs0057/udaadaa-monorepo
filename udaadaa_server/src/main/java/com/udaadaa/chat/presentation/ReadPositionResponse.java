package com.udaadaa.chat.presentation;

import com.udaadaa.chat.domain.ReadPosition;
import java.util.UUID;

record ReadPositionResponse(UUID memberId, long lastReadSequence) {

    static ReadPositionResponse from(ReadPosition readPosition) {
        return new ReadPositionResponse(readPosition.memberId().value(), readPosition.lastReadSequence());
    }
}
