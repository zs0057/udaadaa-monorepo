package com.udaadaa.chat.application;

public class InvalidMessageTypeException extends RuntimeException {

    public InvalidMessageTypeException() {
        super("This message type cannot be created through this API");
    }
}
