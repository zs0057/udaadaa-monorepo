package com.udaadaa.record.application;

import com.udaadaa.chat.ChatReader;
import com.udaadaa.member.MemberId;
import com.udaadaa.record.domain.CalorieEstimate;
import com.udaadaa.record.domain.CalorieEstimator;
import com.udaadaa.record.domain.FeedOwnership;
import com.udaadaa.record.domain.FeedType;
import com.udaadaa.record.domain.MissionCommitResult;
import com.udaadaa.record.domain.RecordRepository;
import com.udaadaa.record.domain.ReportSnapshot;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordApplicationService {

    // Phase 4 CHA-06과 동일하게, "하루" 경계는 KST로 명시적으로 계산한다.
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RecordRepository recordRepository;
    private final ChatReader chatReader;
    private final CalorieEstimator calorieEstimator;

    RecordApplicationService(
            RecordRepository recordRepository,
            ChatReader chatReader,
            CalorieEstimator calorieEstimator
    ) {
        this.recordRepository = recordRepository;
        this.chatReader = chatReader;
        this.calorieEstimator = calorieEstimator;
    }

    /**
     * 기존 mission_complete DB 함수 하나가 하던 일(feed/weight 기록 + 채팅 메시지 1~2개)에
     * report 원자적 갱신(REC-05)까지 한 트랜잭션으로 묶는다. clientRequestId로 재시도를
     * 감지해 중복 기록을 막는다(REC-04).
     */
    @Transactional
    public MissionCommitResult commitMission(MemberId memberId, MissionCommitCommand command) {
        var existing = recordRepository.findExistingCommit(command.clientRequestId());
        if (existing.isPresent()) {
            return existing.get();
        }

        LocalDate today = LocalDate.now(KST);
        FeedType type = command.type();

        UUID feedId = null;
        UUID weightId = null;
        if (type == FeedType.weight) {
            weightId = recordRepository.insertWeight(
                    memberId,
                    command.weight() == null ? 0 : command.weight(),
                    today,
                    command.feedImagePath()
            );
        } else {
            feedId = recordRepository.insertFeed(
                    memberId, type, command.review(), command.feedImagePath(), command.calorie()
            );
        }

        recordRepository.applyReportDelta(
                memberId,
                today,
                type,
                type == FeedType.exercise ? command.exerciseTime() : command.calorie(),
                command.weight()
        );

        // 리뷰 textMessage는 weight가 아니고 내용이 있을 때만 — 기존 mission_complete와 동일 조건.
        String reviewText = type != FeedType.weight ? command.review() : null;
        chatReader.recordMissionMessages(
                memberId, command.roomId(), command.messageContent(), command.messageImagePath(), reviewText
        );

        recordRepository.saveCommit(command.clientRequestId(), memberId, command.roomId(), feedId, weightId);

        return new MissionCommitResult(feedId, weightId);
    }

    @Transactional(readOnly = true)
    public ReportSnapshot getReport(MemberId memberId, LocalDate date) {
        return recordRepository.findReport(memberId, date).orElseGet(() -> ReportSnapshot.empty(date));
    }

    /**
     * REC-07: 기존 feed_cubit.dart deleteMyFeed()와 동일한 의미 — 내 피드를 지우면서 그날
     * report의 해당 칼로리를 함께 되돌린다. report 조정이 실패하면(행이 없거나 음수가 되면)
     * 삭제 자체를 하지 않는다(기존 동작 그대로, 다만 이제 한 트랜잭션 안에서 원자적으로).
     */
    @Transactional
    public void deleteMyFeed(MemberId memberId, UUID feedId) {
        FeedOwnership ownership = recordRepository.findFeedOwnedBy(memberId, feedId)
                .orElseThrow(FeedNotFoundException::new);

        boolean adjusted = recordRepository.decrementReportIfSufficient(
                memberId, ownership.kstDay(), ownership.type(), ownership.calorie()
        );
        if (!adjusted) {
            throw new ReportAdjustmentFailedException();
        }

        recordRepository.deleteFeed(feedId);
    }

    public CalorieEstimate estimateCalorie(String base64Image, String description) {
        try {
            return calorieEstimator.estimate(base64Image, description);
        } catch (Exception e) {
            throw new CalorieEstimationFailedException(e);
        }
    }
}
