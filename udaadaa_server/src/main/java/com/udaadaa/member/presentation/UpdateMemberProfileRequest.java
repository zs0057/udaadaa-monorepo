package com.udaadaa.member.presentation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

record UpdateMemberProfileRequest(
        @Size(max = 30)
        @Pattern(regexp = ".*\\S.*")
        String nickname,
        @DecimalMin("50.0") @DecimalMax("250.0") BigDecimal height,
        @DecimalMin("20.0") @DecimalMax("500.0") BigDecimal weight
) {

    @JsonIgnore
    @AssertTrue(message = "At least one profile field is required")
    public boolean isUpdateRequested() {
        return nickname != null || height != null || weight != null;
    }
}
