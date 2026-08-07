package com.udaadaa.challenge.application;

public class AlreadyChallengingException extends RuntimeException {

    public AlreadyChallengingException() {
        super("Already participating in a challenge");
    }
}
