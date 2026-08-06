package com.udaadaa.chat.domain;

import com.udaadaa.member.MemberId;

public record ReadPosition(MemberId memberId, long lastReadSequence) {
}
