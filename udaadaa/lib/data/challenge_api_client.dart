import 'package:dio/dio.dart';
import 'package:udaadaa/utils/constant.dart';

/// Spring Challenge API(`/api/v1/challenges`) 전용 클라이언트.
///
/// 방 기반 참여는 별도 엔드포인트가 없다 — 챌린지 방 참가(`POST
/// /api/v1/chat/rooms/{roomId}/participants`)가 서버에서 방 참가와 함께
/// 원자적으로 처리한다(Phase 4 CHA-02). 여기서는 조회와 일반(14일) 참여만 다룬다.
class ChallengeApiClient {
  ChallengeApiClient()
      : _dio = Dio(
          BaseOptions(
            baseUrl: springApiUrl,
            connectTimeout: const Duration(milliseconds: 8000),
            receiveTimeout: const Duration(milliseconds: 10000),
          ),
        );

  final Dio _dio;

  Future<Options> _authOptions() async {
    final accessToken = supabase.auth.currentSession?.accessToken;
    if (accessToken == null) {
      throw StateError('No active Supabase session to call Challenge API');
    }
    return Options(headers: {'Authorization': 'Bearer $accessToken'});
  }

  /// 현재 진행 중인 챌린지 상태를 조회한다(참여 여부, 기간, 진행 일수, 연속 성공일수,
  /// 오늘 완료 여부, 최종 성공 여부, 날짜별 미션 건수).
  Future<Map<String, dynamic>> getMyStatus() async {
    final response = await _dio.get(
      '/api/v1/challenges/me',
      options: await _authOptions(),
    );
    return response.data as Map<String, dynamic>;
  }

  /// 종료된 챌린지 이력을 조회한다.
  Future<List<Map<String, dynamic>>> getHistory() async {
    final response = await _dio.get(
      '/api/v1/challenges/me/history',
      options: await _authOptions(),
    );
    return List<Map<String, dynamic>>.from(response.data as List);
  }

  /// 일반(14일 고정) 챌린지에 참여한다. 이미 진행 중인 챌린지가 있으면 409를 던진다.
  Future<void> enterGeneral() async {
    await _dio.post(
      '/api/v1/challenges',
      options: await _authOptions(),
    );
  }
}

final challengeApiClient = ChallengeApiClient();
