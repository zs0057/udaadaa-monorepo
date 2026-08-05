import 'package:dio/dio.dart';
import 'package:udaadaa/utils/constant.dart';

/// Spring Member API(`/api/v1/members/me`) 전용 클라이언트.
///
/// Phase 1 범위 필드(nickname, height, weight)만 다룬다.
/// fcm_token, push_option은 Member API가 아직 소유하지 않으므로
/// 이 클라이언트를 거치지 않고 계속 Supabase `profiles`를 직접 사용한다.
class MemberApiClient {
  MemberApiClient()
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
      throw StateError('No active Supabase session to call Member API');
    }
    return Options(headers: {'Authorization': 'Bearer $accessToken'});
  }

  /// 프로필이 없으면 생성하고, 있으면 기존 프로필을 반환한다. (멱등)
  Future<Map<String, dynamic>> initialize() async {
    final response = await _dio.post(
      '/api/v1/members/me/initialize',
      options: await _authOptions(),
    );
    return response.data as Map<String, dynamic>;
  }

  /// 내 프로필 조회. 프로필이 없으면 DioException(404)이 발생한다.
  Future<Map<String, dynamic>> getMe() async {
    final response = await _dio.get(
      '/api/v1/members/me',
      options: await _authOptions(),
    );
    return response.data as Map<String, dynamic>;
  }

  /// 프로필이 있으면 반환, 없으면(404) null을 반환한다.
  Future<Map<String, dynamic>?> getMeOrNull() async {
    try {
      return await getMe();
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) {
        return null;
      }
      rethrow;
    }
  }

  /// nickname, height, weight 중 변경할 값만 전달한다 (부분 수정).
  Future<Map<String, dynamic>> updateMe({
    String? nickname,
    double? height,
    double? weight,
  }) async {
    final body = <String, dynamic>{};
    if (nickname != null) body['nickname'] = nickname;
    if (height != null) body['height'] = height;
    if (weight != null) body['weight'] = weight;

    final response = await _dio.patch(
      '/api/v1/members/me',
      data: body,
      options: await _authOptions(),
    );
    return response.data as Map<String, dynamic>;
  }
}

final memberApiClient = MemberApiClient();
