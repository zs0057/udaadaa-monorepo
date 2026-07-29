package com.udaadaa.member;

import java.util.Objects;
import java.util.UUID;

public record MemberId(UUID value) {

    public MemberId {
        Objects.requireNonNull(value, "Member ID is required");
    }

    public static MemberId from(UUID value) {
        return new MemberId(value);
    }
}
