package com.udaadaa.chat.application;

public class RoomNotFoundException extends RuntimeException {

    public RoomNotFoundException() {
        // 존재하지 않는 방과 "존재하지만 내가 참가자가 아닌 방"을 같은 오류로 처리해
        // 방 존재 여부 자체가 비참가자에게 노출되지 않도록 한다.
        super("Room was not found or you are not a participant");
    }
}
