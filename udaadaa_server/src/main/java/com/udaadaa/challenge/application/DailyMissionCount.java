package com.udaadaa.challenge.application;

import java.time.LocalDate;

/**
 * 특정 날짜의 feed(운동 제외)·체중 기록 건수. 캘린더 UI가 과거 날짜를 선택했을 때 매번
 * 새로 조회하지 않고 이 목록에서 찾아 쓸 수 있도록 startDay~오늘 전체를 한 번에 내려준다
 * (기존 ChallengeCubit.getSelectedDayMission이 탭할 때마다 Supabase를 다시 조회하던 것을 대체).
 */
public record DailyMissionCount(LocalDate date, int feedCount, int weightCount) {
}
