package com.udaadaa.moderation.application;

public class BlockedMemberNotFoundException extends RuntimeException {

    public BlockedMemberNotFoundException() {
        super("Member to block was not found");
    }
}
