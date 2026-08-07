package com.udaadaa.record.presentation;

import com.udaadaa.record.domain.ReportSnapshot;
import java.time.LocalDate;

public record ReportResponse(
        LocalDate date,
        Long breakfast,
        Long lunch,
        Long dinner,
        Long snack,
        Long exercise,
        Double weight
) {
    public static ReportResponse from(ReportSnapshot snapshot) {
        return new ReportResponse(
                snapshot.date(),
                snapshot.breakfast(),
                snapshot.lunch(),
                snapshot.dinner(),
                snapshot.snack(),
                snapshot.exercise(),
                snapshot.weight()
        );
    }
}
