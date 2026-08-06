package com.udaadaa.chat.application;

import com.udaadaa.chat.RoomId;
import com.udaadaa.chat.domain.ChatRepository;
import com.udaadaa.chat.domain.MessageSummary;
import com.udaadaa.chat.domain.RoomSummary;
import com.udaadaa.member.MemberId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatApplicationService {

    static final int MAX_MESSAGE_PAGE_SIZE = 50;

    private final ChatRepository chatRepository;

    ChatApplicationService(ChatRepository chatRepository) {
        this.chatRepository = chatRepository;
    }

    @Transactional(readOnly = true)
    public List<RoomSummary> getRooms(MemberId memberId) {
        return chatRepository.findRoomSummariesForMember(memberId);
    }

    @Transactional(readOnly = true)
    public List<MessageSummary> getMessages(MemberId memberId, RoomId roomId, long afterSequence, int limit) {
        if (!chatRepository.isParticipant(roomId, memberId)) {
            throw new RoomNotFoundException();
        }
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_MESSAGE_PAGE_SIZE);
        return chatRepository.findMessagesAfter(roomId, afterSequence, boundedLimit);
    }
}
