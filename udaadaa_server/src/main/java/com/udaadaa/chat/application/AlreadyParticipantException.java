package com.udaadaa.chat.application;

public class AlreadyParticipantException extends RuntimeException {

    public AlreadyParticipantException() {
        super("Already a participant of this room");
    }
}
