package com.udaadaa.chat.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record AddReactionRequest(@NotBlank @Size(max = 32) String content) {
}
