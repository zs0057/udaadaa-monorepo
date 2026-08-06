import 'package:dio/dio.dart';
import 'package:udaadaa/utils/constant.dart';

/// Spring Moderation API(`/api/v1/moderation`) 전용 클라이언트.
///
/// Phase 2 범위: 차단 생성·조회만 다룬다. 차단 해제 API는 서버에 있지만
/// 이번 단계에서는 Flutter UI에 노출하지 않는다(phase-02-moderation.md 참고).
class ModerationApiClient {
  ModerationApiClient()
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
      throw StateError('No active Supabase session to call Moderation API');
    }
    return Options(headers: {'Authorization': 'Bearer $accessToken'});
  }

  /// 내가 차단한 회원 ID 목록을 조회한다.
  Future<List<String>> getBlockedMemberIds() async {
    final response = await _dio.get(
      '/api/v1/moderation/blocks',
      options: await _authOptions(),
    );
    final data = response.data as Map<String, dynamic>;
    final ids = data['blockedMemberIds'] as List<dynamic>;
    return ids.map((e) => e as String).toList();
  }

  /// 회원을 차단한다. 이미 차단된 상태라면 그대로 성공한다(멱등).
  Future<void> blockMember(String blockedMemberId) async {
    await _dio.post(
      '/api/v1/moderation/blocks',
      data: {'blockedMemberId': blockedMemberId},
      options: await _authOptions(),
    );
  }
}

final moderationApiClient = ModerationApiClient();
