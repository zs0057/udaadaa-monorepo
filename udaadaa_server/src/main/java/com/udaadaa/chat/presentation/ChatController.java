package com.udaadaa.chat.presentation;

import com.udaadaa.chat.RoomId;
import com.udaadaa.chat.application.ChatApplicationService;
import com.udaadaa.chat.domain.MessageSummary;
import com.udaadaa.common.security.CurrentUserProvider;
import com.udaadaa.member.MemberId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
class ChatController {

    private static final int DEFAULT_MESSAGE_PAGE_SIZE = 30;

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
        return chatApplicationService.getRooms(currentMemberId()).stream()
                .map(RoomSummaryResponse::from)
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

    private MemberId currentMemberId() {
        return MemberId.from(currentUserProvider.currentUser().id());
    }
}
