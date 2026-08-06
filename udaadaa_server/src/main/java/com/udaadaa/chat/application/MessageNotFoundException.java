package com.udaadaa.chat.application;

public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException() {
        // 메시지가 없는 경우, 다른 방 소속인 경우, 내가 보낸 메시지가 아닌 경우를 모두
        // 같은 오류로 처리한다(RoomNotFoundException과 같은 정보 비노출 원칙).
        super("Message was not found in this room, or you do not own it");
    }
}
