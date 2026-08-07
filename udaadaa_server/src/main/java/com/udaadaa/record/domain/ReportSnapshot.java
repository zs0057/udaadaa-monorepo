package com.udaadaa.record.domain;

import java.time.LocalDate;

public record ReportSnapshot(
        LocalDate date,
        Long breakfast,
        Long lunch,
        Long dinner,
        Long snack,
        Long exercise,
        Double weight
) {
    public static ReportSnapshot empty(LocalDate date) {
        return new ReportSnapshot(date, null, null, null, null, null, null);
    }
}
