import 'package:dio/dio.dart';
import 'package:udaadaa/utils/constant.dart';

/// Spring Chat API(`/api/v1/chat`) 전용 클라이언트.
///
/// Phase 3 읽기 경로 전환 범위: 방 목록·메시지·이미지 갤러리·읽음 위치 조회만 다룬다.
/// 쓰기 경로(메시지 전송, 참가/나가기, 반응, 삭제/숨김, 이미지 업로드 승인)는
/// 별도 단계(Flutter 전환 B/C)에서 이 클라이언트에 메서드를 추가한다.
class ChatApiClient {
  ChatApiClient()
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
      throw StateError('No active Supabase session to call Chat API');
    }
    return Options(headers: {'Authorization': 'Bearer $accessToken'});
  }

  /// 내가 참가 중인 방 목록을 마지막 메시지·내 읽음 위치·참가자 닉네임과 함께 반환한다.
  Future<List<Map<String, dynamic>>> getRooms() async {
    final response = await _dio.get(
      '/api/v1/chat/rooms',
      options: await _authOptions(),
    );
    return List<Map<String, dynamic>>.from(response.data as List);
  }

  /// afterSequence보다 큰 순번의 메시지를 오름차순으로 최대 limit개 반환한다.
  /// (초기 로드·이전 메시지 더보기 모두 이 커서로 windowing해서 쓴다 — chat_cubit 참고)
  Future<List<Map<String, dynamic>>> getMessages(
    String roomId, {
    int after = 0,
    int limit = 30,
  }) async {
    final response = await _dio.get(
      '/api/v1/chat/rooms/$roomId/messages',
      queryParameters: {'after': after, 'limit': limit},
      options: await _authOptions(),
    );
    return List<Map<String, dynamic>>.from(response.data as List);
  }

  /// 채팅방 이미지 갤러리용 — imageMessage만 최신순으로 최대 limit개 반환한다.
  Future<List<Map<String, dynamic>>> getRecentImages(
    String roomId, {
    int limit = 32,
  }) async {
    final response = await _dio.get(
      '/api/v1/chat/rooms/$roomId/images',
      queryParameters: {'limit': limit},
      options: await _authOptions(),
    );
    return List<Map<String, dynamic>>.from(response.data as List);
  }

  /// 메시지를 전송한다. clientMessageId로 재전송 멱등 처리된다(같은 id로 다시 보내도
  /// 새 메시지가 만들어지지 않고 기존 메시지가 그대로 반환됨 — CHT-03).
  Future<Map<String, dynamic>> sendMessage(
    String roomId, {
    required String clientMessageId,
    required String type,
    String? content,
    String? imagePath,
  }) async {
    final response = await _dio.post(
      '/api/v1/chat/rooms/$roomId/messages',
      data: {
        'clientMessageId': clientMessageId,
        'type': type,
        if (content != null) 'content': content,
        if (imagePath != null) 'imagePath': imagePath,
      },
      options: await _authOptions(),
    );
    return response.data as Map<String, dynamic>;
  }

  /// 방 참가자 전원의 읽음 위치(lastReadSequence)를 반환한다("안읽음 N명" 계산용).
  Future<List<Map<String, dynamic>>> getReadPositions(String roomId) async {
    final response = await _dio.get(
      '/api/v1/chat/rooms/$roomId/read-positions',
      options: await _authOptions(),
    );
    return List<Map<String, dynamic>>.from(response.data as List);
  }
}

final chatApiClient = ChatApiClient();
