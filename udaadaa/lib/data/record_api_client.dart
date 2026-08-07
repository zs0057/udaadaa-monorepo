import 'package:dio/dio.dart';
import 'package:udaadaa/utils/constant.dart';

/// Spring Record API(`/api/v1/records`) 전용 클라이언트(Phase 5).
///
/// 미션 커밋(`POST /missions`)이 기존 `mission_complete` DB 함수를 대체한다 — feed/weight
/// 기록, report 갱신, 채팅 메시지 생성을 서버가 한 트랜잭션으로 처리한다. 이미지 업로드
/// 자체는 여전히 Flutter가 각 Storage 버킷에 직접 하고, 그 경로만 이 API로 넘긴다.
class RecordApiClient {
  RecordApiClient()
      : _dio = Dio(
          BaseOptions(
            baseUrl: springApiUrl,
            connectTimeout: const Duration(milliseconds: 8000),
            receiveTimeout: const Duration(milliseconds: 15000),
          ),
        );

  final Dio _dio;

  Future<Options> _authOptions() async {
    final accessToken = supabase.auth.currentSession?.accessToken;
    if (accessToken == null) {
      throw StateError('No active Supabase session to call Record API');
    }
    return Options(headers: {'Authorization': 'Bearer $accessToken'});
  }

  /// clientRequestId는 재시도해도 feed/weight/report/메시지가 중복 생성되지 않도록 하는
  /// 멱등 키다 — 매 커밋 시도마다 새로 생성해서 넘긴다(같은 시도를 재시도할 때만 재사용).
  Future<Map<String, dynamic>> commitMission({
    required String clientRequestId,
    required String roomId,
    required String type,
    String? review,
    required String messageContent,
    required String feedImagePath,
    required String messageImagePath,
    int? calorie,
    double? weight,
    int? exerciseTime,
  }) async {
    final response = await _dio.post(
      '/api/v1/records/missions',
      data: {
        'clientRequestId': clientRequestId,
        'roomId': roomId,
        'type': type,
        'review': review,
        'messageContent': messageContent,
        'feedImagePath': feedImagePath,
        'messageImagePath': messageImagePath,
        'calorie': calorie,
        'weight': weight,
        'exerciseTime': exerciseTime,
      },
      options: await _authOptions(),
    );
    return response.data as Map<String, dynamic>;
  }

  Future<void> deleteMyFeed(String feedId) async {
    await _dio.delete(
      '/api/v1/records/feed/$feedId',
      options: await _authOptions(),
    );
  }

  /// 기존에 Flutter가 dotenv API_URL로 직접 부르던 칼로리 추정 서비스를 이제 Spring이
  /// 대리 호출한다(REC-01). 요청 바디는 외부 서비스와 동일한 selectedImage/description.
  Future<Map<String, dynamic>> estimateCalorie({
    required String selectedImage,
    required String description,
  }) async {
    final response = await _dio.post(
      '/api/v1/records/calorie-estimates',
      data: {
        'selectedImage': selectedImage,
        'description': description,
      },
      options: await _authOptions(),
    );
    return response.data as Map<String, dynamic>;
  }
}

final recordApiClient = RecordApiClient();
