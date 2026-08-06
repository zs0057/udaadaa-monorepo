package com.udaadaa.chat.presentation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

record SendMessageRequest(
        @NotNull UUID clientMessageId,
        @NotBlank String type,
        @Size(max = 2000) String content,
        String imagePath
) {

    @JsonIgnore
    @AssertTrue(message = "textMessage requires content, imageMessage requires imagePath")
    public boolean isContentConsistentWithType() {
        if ("textMessage".equals(type)) {
            return content != null && !content.isBlank();
        }
        if ("imageMessage".equals(type)) {
            return imagePath != null && !imagePath.isBlank();
        }
        // 알 수 없는 type은 여기서 걸러내지 않고 서비스 레이어의 InvalidMessageTypeException으로 넘긴다.
        return true;
    }
}
