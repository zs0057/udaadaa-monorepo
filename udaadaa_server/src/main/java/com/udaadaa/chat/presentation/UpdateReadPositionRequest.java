package com.udaadaa.chat.presentation;

import jakarta.validation.constraints.PositiveOrZero;

record UpdateReadPositionRequest(@PositiveOrZero long lastReadSequence) {
}
