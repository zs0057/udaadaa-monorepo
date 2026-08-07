package com.udaadaa.record.presentation;

import com.udaadaa.common.security.CurrentUserProvider;
import com.udaadaa.member.MemberId;
import com.udaadaa.record.application.RecordApplicationService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/records")
class RecordController {

    private final CurrentUserProvider currentUserProvider;
    private final RecordApplicationService recordApplicationService;

    RecordController(CurrentUserProvider currentUserProvider, RecordApplicationService recordApplicationService) {
        this.currentUserProvider = currentUserProvider;
        this.recordApplicationService = recordApplicationService;
    }

    /**
     * 기존 supabase.rpc('mission_complete') 호출을 대체한다(REC-04). 이미지 업로드 자체는
     * 여전히 Flutter가 먼저 각 버킷에 직접 하고, 그 경로만 이 API로 넘긴다.
     */
    @PostMapping("/missions")
    MissionCommitResponse commitMission(@RequestBody MissionCommitRequest request) {
        return MissionCommitResponse.from(
                recordApplicationService.commitMission(currentMemberId(), request.toCommand())
        );
    }

    @GetMapping("/reports")
    ReportResponse getReport(@RequestParam LocalDate date) {
        return ReportResponse.from(recordApplicationService.getReport(currentMemberId(), date));
    }

    /**
     * 기존 feed_cubit.dart deleteMyFeed()를 대체한다(REC-07).
     */
    @DeleteMapping("/feed/{feedId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteMyFeed(@PathVariable UUID feedId) {
        recordApplicationService.deleteMyFeed(currentMemberId(), feedId);
    }

    /**
     * 기존에 Flutter가 dotenv API_URL로 직접 부르던 외부 칼로리 추정 서비스를 대리 호출한다
     * (REC-01). 새 서비스가 아니라 같은 서비스를 서버 경유로 부르는 것뿐이다.
     */
    @PostMapping("/calorie-estimates")
    CalorieEstimateResponse estimateCalorie(@RequestBody CalorieEstimateRequest request) {
        return CalorieEstimateResponse.from(
                recordApplicationService.estimateCalorie(request.selectedImage(), request.description())
        );
    }

    private MemberId currentMemberId() {
        return MemberId.from(currentUserProvider.currentUser().id());
    }
}
