package com.udaadaa.member.application;

public class NicknameAlreadyExistsException extends RuntimeException {

    public NicknameAlreadyExistsException() {
        super("Nickname already exists");
    }
}
