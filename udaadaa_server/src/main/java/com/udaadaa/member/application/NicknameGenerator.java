package com.udaadaa.member.application;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
class NicknameGenerator {

    private static final List<String> ADJECTIVES = List.of(
            "배부른", "행복한", "용감한", "활기찬", "지혜로운",
            "즐거운", "슬기로운", "긍정적인", "평화로운", "창의적인"
    );
    private static final List<String> NOUNS = List.of(
            "토끼", "사자", "호랑이", "고양이", "강아지",
            "여우", "나무늘보", "팬더", "코알라", "펭귄",
            "햄스터", "다람쥐", "앵무새", "알파카", "쿼카"
    );

    String generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "%s %s %d".formatted(
                ADJECTIVES.get(random.nextInt(ADJECTIVES.size())),
                NOUNS.get(random.nextInt(NOUNS.size())),
                random.nextInt(10_000)
        );
    }
}
