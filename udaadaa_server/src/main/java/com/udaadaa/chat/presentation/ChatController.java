package com.udaadaa.chat.presentation;

import com.udaadaa.chat.RoomId;
import com.udaadaa.chat.application.ChatApplicationService;
import com.udaadaa.chat.domain.MessageSummary;
import com.udaadaa.chat.domain.RoomSummary;
import com.udaadaa.common.security.CurrentUserProvider;
import com.udaadaa.member.MemberId;
import com.udaadaa.member.MemberSummary;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
class ChatController {

    private static final int DEFAULT_MESSAGE_PAGE_SIZE = 30;
    private static final int DEFAULT_IMAGE_PAGE_SIZE = 32;

    private final CurrentUserProvider currentUserProvider;
    private final ChatApplicationService chatApplicationService;

    ChatController(
            CurrentUserProvider currentUserProvider,
            ChatApplicationService chatApplicationService
    ) {
        this.currentUserProvider = currentUserProvider;
        this.chatApplicationService = chatApplicationService;
    }

    @GetMapping("/rooms")
    List<RoomSummaryResponse> getRooms() {
        List<RoomSummary> summaries = chatApplicationService.getRooms(currentMemberId());
        Set<MemberId> participantIds = summaries.stream()
                .flatMap(room -> room.participantIds().stream())
                .collect(Collectors.toSet());
        Map<MemberId, MemberSummary> members = chatApplicationService.resolveMembers(participantIds);
        return summaries.stream()
                .map(room -> RoomSummaryResponse.from(room, members))
                .toList();
    }

    @GetMapping("/rooms/{roomId}/messages")
    List<MessageSummaryResponse> getMessages(
            @PathVariable UUID roomId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "" + DEFAULT_MESSAGE_PAGE_SIZE) int limit
    ) {
        return chatApplicationService
                .getMessages(currentMemberId(), RoomId.from(roomId), after, limit).stream()
                .map(MessageSummaryResponse::from)
                .toList();
    }

    @GetMapping("/rooms/{roomId}/images")
    List<MessageSummaryResponse> getRecentImages(
            @PathVariable UUID roomId,
            @RequestParam(defaultValue = "" + DEFAULT_IMAGE_PAGE_SIZE) int limit
    ) {
        return chatApplicationService
                .getRecentImages(currentMemberId(), RoomId.from(roomId), limit).stream()
                .map(MessageSummaryResponse::from)
                .toList();
    }

    @PostMapping("/rooms/{roomId}/messages")
    MessageSummaryResponse sendMessage(
            @PathVariable UUID roomId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        MessageSummary saved = chatApplicationService.sendMessage(
                currentMemberId(),
                RoomId.from(roomId),
                request.clientMessageId(),
                request.type(),
                request.content(),
                request.imagePath()
        );
        return MessageSummaryResponse.from(saved);
    }

    @PostMapping("/rooms/{roomId}/participants")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void joinRoom(@PathVariable UUID roomId) {
        chatApplicationService.joinRoom(currentMemberId(), RoomId.from(roomId));
    }

    @DeleteMapping("/rooms/{roomId}/participants/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leaveRoom(@PathVariable UUID roomId) {
        chatApplicationService.leaveRoom(currentMemberId(), RoomId.from(roomId));
    }

    @PatchMapping("/rooms/{roomId}/read-position")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateReadPosition(@PathVariable UUID roomId, @Valid @RequestBody UpdateReadPositionRequest request) {
        chatApplicationService.updateReadPosition(currentMemberId(), RoomId.from(roomId), request.lastReadSequence());
    }

    @GetMapping("/rooms/{roomId}/read-positions")
    List<ReadPositionResponse> getReadPositions(@PathVariable UUID roomId) {
        return chatApplicationService.getReadPositions(currentMemberId(), RoomId.from(roomId)).stream()
                .map(ReadPositionResponse::from)
                .toList();
    }

    @PostMapping("/rooms/{roomId}/messages/{messageId}/reactions")
    ReactionResponse addReaction(
            @PathVariable UUID roomId,
            @PathVariable UUID messageId,
            @Valid @RequestBody AddReactionRequest request
    ) {
        UUID reactionId = chatApplicationService.addReaction(
                currentMemberId(), RoomId.from(roomId), messageId, request.content()
        );
        return new ReactionResponse(reactionId);
    }

    @DeleteMapping("/rooms/{roomId}/reactions/{reactionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeReaction(@PathVariable UUID roomId, @PathVariable UUID reactionId) {
        chatApplicationService.removeReaction(currentMemberId(), RoomId.from(roomId), reactionId);
    }

    @DeleteMapping("/rooms/{roomId}/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteMessage(@PathVariable UUID roomId, @PathVariable UUID messageId) {
        chatApplicationService.deleteMessage(currentMemberId(), RoomId.from(roomId), messageId);
    }

    @PostMapping("/rooms/{roomId}/messages/{messageId}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void hideMessage(@PathVariable UUID roomId, @PathVariable UUID messageId) {
        chatApplicationService.hideMessage(currentMemberId(), RoomId.from(roomId), messageId);
    }

    @PostMapping("/rooms/{roomId}/image-uploads")
    ImageUploadApprovalResponse approveImageUpload(@PathVariable UUID roomId) {
        String path = chatApplicationService.approveImageUpload(currentMemberId(), RoomId.from(roomId));
        return new ImageUploadApprovalResponse(path);
    }

    private MemberId currentMemberId() {
        return MemberId.from(currentUserProvider.currentUser().id());
    }
}
