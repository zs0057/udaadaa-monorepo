package com.udaadaa.notification.domain;

import com.udaadaa.member.MemberId;

public record PushRecipient(MemberId memberId, String fcmToken) {
}
