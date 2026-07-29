package com.udaadaa.member.application;

public class MemberNotFoundException extends RuntimeException {

    public MemberNotFoundException() {
        super("Member profile was not found");
    }
}
