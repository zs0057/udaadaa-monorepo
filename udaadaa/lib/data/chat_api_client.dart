import 'package:dio/dio.dart';
import 'package:udaadaa/utils/constant.dart';

/// Spring Chat API(`/api/v1/chat`) 전용 클라이언트.
///
/// 조회(방 목록·메시지·이미지 갤러리·읽음 위치)와 쓰기(메시지 전송, 참가/나가기,
/// 반응, 삭제/숨김, 이미지 업로드 경로 발급)를 모두 다룬다.
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

  /// 방에 참가한다(참가자 테이블에 나를 추가). 이미 참가 중이면 서버가 에러를 던진다.
  Future<void> joinRoom(String roomId) async {
    await _dio.post(
      '/api/v1/chat/rooms/$roomId/participants',
      options: await _authOptions(),
    );
  }

  /// 방 참가를 취소한다(참가자 테이블에서 나를 제거).
  Future<void> leaveRoom(String roomId) async {
    await _dio.delete(
      '/api/v1/chat/rooms/$roomId/participants/me',
      options: await _authOptions(),
    );
  }

  /// 메시지를 소프트 삭제한다(is_deleted=true). 실시간 반영은 기존 Supabase Realtime
  /// UPDATE 리스너가 그대로 받는다 — 어느 서비스가 UPDATE했는지와 무관하게
  /// Postgres 행 변경이므로 STOMP 쪽에 별도 처리를 추가할 필요가 없다.
  Future<void> deleteMessage(String roomId, String messageId) async {
    await _dio.delete(
      '/api/v1/chat/rooms/$roomId/messages/$messageId',
      options: await _authOptions(),
    );
  }

  /// 메시지를 나에게만 숨긴다(신고/차단 성격의 "안 보이게" 기능).
  Future<void> hideMessage(String roomId, String messageId) async {
    await _dio.post(
      '/api/v1/chat/rooms/$roomId/messages/$messageId/hide',
      options: await _authOptions(),
    );
  }

  /// 메시지에 이모지 반응을 추가한다.
  Future<String> addReaction(
    String roomId,
    String messageId,
    String content,
  ) async {
    final response = await _dio.post(
      '/api/v1/chat/rooms/$roomId/messages/$messageId/reactions',
      data: {'content': content},
      options: await _authOptions(),
    );
    return (response.data as Map<String, dynamic>)['id'] as String;
  }

  /// 반응을 제거한다.
  Future<void> removeReaction(String roomId, String reactionId) async {
    await _dio.delete(
      '/api/v1/chat/rooms/$roomId/reactions/$reactionId',
      options: await _authOptions(),
    );
  }

  /// 이미지 업로드용 경로를 서버에서 발급받는다(CHT-06: "경로만 발급" —
  /// 실제 업로드는 여전히 Flutter가 Supabase Storage로 직접 한다).
  Future<String> approveImageUpload(String roomId) async {
    final response = await _dio.post(
      '/api/v1/chat/rooms/$roomId/image-uploads',
      options: await _authOptions(),
    );
    return (response.data as Map<String, dynamic>)['path'] as String;
  }
}

final chatApiClient = ChatApiClient();
