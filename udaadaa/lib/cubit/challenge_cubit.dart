import 'dart:async';

import 'package:bloc/bloc.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:udaadaa/cubit/auth_cubit.dart';
import 'package:udaadaa/data/challenge_api_client.dart';
import 'package:udaadaa/models/challenge.dart';
import 'package:udaadaa/service/shared_preferences.dart';
import 'package:udaadaa/utils/constant.dart';
import 'package:udaadaa/service/notifications/notification_service.dart';

part 'challenge_state.dart';

class ChallengeCubit extends Cubit<ChallengeState> {
  final AuthCubit authCubit;
  late final StreamSubscription authSubscription;
  Challenge? _challenge;
  DateTime _selectedDate = DateTime.now();
  DateTime _focusDate = DateTime.now();
  int _consecutiveDays = 0;
  int _completeDays = 0;

  double? _startWeight;
  double? _endWeight;

  // 날짜(연-월-일, 시간 제거)별 미션 건수 캐시 — refresh()가 받아온
  // dailyMissionCounts를 그대로 담아둔다. 캘린더에서 과거 날짜를 선택할 때마다
  // 새로 네트워크를 부르지 않고 여기서 찾아 쓴다.
  final Map<DateTime, Map<String, int>> _dailyMissionCounts = {};

  final Map<String, int> _selectedMissionComplete = {"feed": 0, "weight": 0};
  bool _todayChallengeComplete = false;
  bool _selectedDayChallenge = false;

  ChallengeCubit(this.authCubit) : super(ChallengeInitial()) {
    final authState = authCubit.state;
    if (authState is Authenticated) {
      refresh();
    }

    authSubscription = authCubit.stream.listen((authState) {
      if (authState is Authenticated) {
        refresh();
      } else {
        emit(ChallengeInitial());
      }
    });
    selectDay(DateTime.now());
  }

  @override
  Future<void> close() {
    authSubscription.cancel();
    return super.close();
  }

  /// 서버(GET /api/v1/challenges/me)에서 현재 챌린지 상태를 다시 불러와 로컬 상태를
  /// 갱신한다. 기존 isEntered()+getCurrentChallenges()+getConsecutiveChallengeDays()+
  /// getTodayMission()+getCurrentChallengeCompletedDays() 다섯 번의 Supabase 직접
  /// 호출을 서버 호출 한 번으로 대체한다(Phase 4 Flutter 전환).
  ///
  /// 챌린지 방 참가는 이제 방 참가 API가 서버에서 원자적으로 함께 처리하므로
  /// (Phase 4 CHA-02), ChatCubit.joinRoom()은 참여를 직접 만들지 않고 이 메서드로
  /// 로컬 상태만 새로고침한다.
  Future<bool> refresh() async {
    try {
      final status = await challengeApiClient.getMyStatus();
      final participating = status['participating'] as bool;
      final wasSuccess = _challenge?.isSuccess ?? false;

      if (!participating) {
        _challenge = null;
        _consecutiveDays = 0;
        _completeDays = 0;
        _todayChallengeComplete = false;
        _dailyMissionCounts.clear();
        authCubit.setIsChallenger(false);
        _applySelectedDayMission();
        emit(ChallengeSuccess());
        return false;
      }

      authCubit.setWasChallenger(true);
      authCubit.setIsChallenger(true);

      final startDay = DateTime.parse(status['startDay'] as String);
      final endDay = DateTime.parse(status['endDay'] as String);
      final success = status['success'] as bool;
      _challenge = Challenge(
        startDay: startDay,
        endDay: endDay,
        userId: supabase.auth.currentUser!.id,
        isSuccess: success,
      );
      _consecutiveDays = status['consecutiveDays'] as int;
      _completeDays = status['completedDays'] as int;
      _todayChallengeComplete = status['todayCompleted'] as bool;

      _dailyMissionCounts.clear();
      for (final row in (status['dailyMissionCounts'] as List)) {
        final date = DateTime.parse(row['date'] as String);
        _dailyMissionCounts[_dateKey(date)] = {
          'feed': row['feedCount'] as int,
          'weight': row['weightCount'] as int,
        };
      }
      _applySelectedDayMission();

      if (!wasSuccess && success) {
        // 조회 시점에 서버가 성공 조건을 막 반영한 경우 — 기존 updateMission()의
        // ChallengeEnd emit과 동일하게 축하 화면으로 이어지는 트리거를 한 번만 쏜다.
        emit(ChallengeEnd(endDay));
      } else {
        emit(ChallengeSuccess());
      }
      return true;
    } catch (e) {
      logger.e(e);
      return _challenge != null;
    }
  }

  /// 일반(14일 고정) 챌린지 참여. 온보딩 화면(tenth_view.dart)에서만 호출된다.
  /// 예전엔 클라이언트가 7일(+6일)로 하드코딩해 만들었는데, 연속 성공 기준(13일)과
  /// 앞뒤가 안 맞는 버그였다 — 서버가 14일로 만들도록 고쳤다(CHA-05).
  Future<void> enterChallenge() async {
    try {
      await challengeApiClient.enterGeneral();
      await refresh();
    } on DioException catch (e) {
      if (e.response?.statusCode == 409) {
        emit(ChallengeError("이미 참여 중 입니다."));
        return;
      }
      logger.e("enterChallenge error: $e");
      emit(ChallengeError("챌린지 참여에 실패했습니다."));
    } catch (e) {
      logger.e(e);
      emit(ChallengeError("챌린지 참여에 실패했습니다."));
    }
  }

  void selectFocusDate(DateTime date) {
    _focusDate = date;
    emit(ChallengeSuccess());
  }

  void selectDay(DateTime date) {
    _selectedDate = date;
    _applySelectedDayMission();
    emit(ChallengeSuccess());
  }

  /// _selectedDate에 해당하는 미션 건수를 _dailyMissionCounts 캐시에서 찾아 반영한다.
  /// 캐시 범위(챌린지 startDay~오늘) 밖의 날짜는 0건으로 취급한다(기존 동작과 동일 —
  /// 원래도 그 범위 밖에는 feed/weight가 있을 수 없다).
  void _applySelectedDayMission() {
    _selectedDayChallenge = true;
    final counts = _dailyMissionCounts[_dateKey(_selectedDate)];
    _selectedMissionComplete['feed'] = counts?['feed'] ?? 0;
    _selectedMissionComplete['weight'] = counts?['weight'] ?? 0;
  }

  DateTime _dateKey(DateTime date) =>
      DateTime(date.year, date.month, date.day);

  Future<void> scheduleNotifications(List<TimeOfDay> alarmTimes) async {
    PreferencesService().setAlarmTimes(alarmTimes);
    PreferencesService().setBool('isMissionPushOn', true);

    final nickname = (authCubit.getCurProfile?.nickname.isNotEmpty ?? false)
        ? "${authCubit.getCurProfile!.nickname}님, "
        : "";

    logger.d("🔄 알림 초기화 중...");
    NotificationService.cancelNotification().then((_) {
      final now = DateTime.now();
      logger.d("📆 현재 시각: $now");
      logger.d("⏰ 설정된 알람 시간 개수: ${alarmTimes.length}");

      for (int i = 0; i < alarmTimes.length; i++) {
        final time = alarmTimes[i];

        final firstDate = DateTime(
          now.year,
          now.month,
          now.day,
          time.hour,
          time.minute,
        );

        logger.d(
            "🛠️ 알림 예약 준비 - ID: $i, 시간: ${time.hour}:${time.minute}, 시작일: $firstDate");

        NotificationService.scheduleNotification(
          i,
          "오늘의 미션 인증 시간이에요 ⏰",
          "지금 바로 인증하여 다이어트 성공을 향해 한 발짝 더 나아가요 🚀",
          time.hour,
          time.minute,
          firstDate,
        );
      }

      logger.d("✅ 알림 예약 처리 완료");
    });
  }

  Future<void> cancelNotifications() async {
    Future.wait([
      PreferencesService().setBool('isMissionPushOn', false),
      NotificationService.cancelNotification(),
    ]);
  }

  /// feed_cubit/chat_cubit이 미션 인증(mission_complete RPC) 이후 호출한다.
  /// mission_complete RPC는 여전히 feed/weight/messages만 갱신하고 challenge는
  /// 건드리지 않으므로(Phase 5 전까지 남는 갭 — phase-04-challenge.md §8 참고),
  /// 인증 직후 진행 상태를 반영하려면 서버에 다시 물어봐야 한다.
  Future<void> updateMission() async {
    await refresh();
  }

  // result_list_view에서밖에 안씀
  Future<void> fetchChallenge() async {
    try {
      emit(ChallengeLoading());
      final history = await challengeApiClient.getHistory();
      final challengeList = history
          .map((row) => Challenge(
                startDay: DateTime.parse(row['startDay'] as String),
                endDay: DateTime.parse(row['endDay'] as String),
                userId: supabase.auth.currentUser!.id,
                isSuccess: row['success'] as bool,
              ))
          .toList();
      emit(ChallengeList(challengeList));
    } catch (error) {
      logger.e(error);
    }
  }

  Future<void> fetchStartAndEndWeights(
      DateTime startDate, DateTime endDate) async {
    try {
      if (authCubit.state is! Authenticated) {
        return;
      }

      final startReport = await supabase
          .from('report')
          .select('weight')
          .eq('date', startDate.toIso8601String())
          .maybeSingle();

      final endReport = await supabase
          .from('report')
          .select('weight')
          .eq('date', endDate.toIso8601String())
          .maybeSingle();

      final startWeight = startReport?['weight'];
      final endWeight = endReport?['weight'];

      if (startWeight == null || endWeight == null) {
        return;
      }
      _startWeight = startWeight.toDouble();
      _endWeight = endWeight.toDouble();
    } catch (e) {
      logger.e('Error fetching start/end weights: $e');
    }
  }

  Challenge? get challenge => _challenge;
  DateTime get getSelectedDate => _selectedDate;
  DateTime get getFocusDate => _focusDate;
  int get getConsecutiveDays => _consecutiveDays;
  Map<String, int> get getSelectedMission => _selectedMissionComplete;
  bool get getSelectedDayChallenge => _selectedDayChallenge;
  bool get getTodayChallengeComplete => _todayChallengeComplete;
  int get getCompleteDays => _completeDays;
  double? get getStartWeight => _startWeight;
  double? get getEndWeight => _endWeight;
}
