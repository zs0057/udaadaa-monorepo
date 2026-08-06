package com.udaadaa.moderation.application;

public class SelfBlockNotAllowedException extends RuntimeException {

    public SelfBlockNotAllowedException() {
        super("Cannot block yourself");
    }
}
