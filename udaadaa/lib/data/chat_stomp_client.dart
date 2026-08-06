import 'dart:convert';

import 'package:stomp_dart_client/stomp_dart_client.dart';
import 'package:udaadaa/utils/constant.dart';

/// Spring Chat STOMP 브로커(`/ws/chat`) 연결 래퍼.
///
/// 서버는 클라이언트가 STOMP로 SEND하는 경로를 두지 않는다(ChatWebSocketConfig 참고) —
/// 메시지 전송은 항상 REST(`ChatApiClient.sendMessage`)로 하고, 이 클라이언트는
/// `/topic/rooms/{roomId}`를 SUBSCRIBE해서 서버가 저장 커밋 후 브로드캐스트하는
/// 새 메시지만 받는다.
///
/// 재연결은 stomp_dart_client가 기본 제공(reconnectDelay, 기본 5초)한다.
/// 재연결마다 onConnect가 다시 호출되므로, 그 안에서 구독을 다시 걸고
/// 끊겨있던 동안 놓친 메시지를 REST로 따라잡는 건 호출부(ChatCubit) 책임이다.
class ChatStompClient {
  StompClient? _client;
  final Map<String, void Function()> _unsubscribeByRoom = {};
  late void Function(String roomId, Map<String, dynamic> payload) _onMessage;

  bool get isActive => _client?.connected ?? false;

  void connect({
    required void Function() onConnected,
    required void Function(String roomId, Map<String, dynamic> payload)
        onMessage,
    void Function(dynamic error)? onError,
  }) {
    final accessToken = supabase.auth.currentSession?.accessToken;
    if (accessToken == null) {
      logger.e("ChatStompClient: no active Supabase session, skip connect");
      return;
    }

    _onMessage = onMessage;
    final wsUrl = _toWsUrl(springApiUrl);

    _client = StompClient(
      config: StompConfig(
        url: '$wsUrl/ws/chat',
        stompConnectHeaders: {'Authorization': 'Bearer $accessToken'},
        onConnect: (frame) {
          logger.d("🔌 STOMP connected");
          onConnected();
        },
        onWebSocketError: (dynamic error) {
          logger.e("⛔ STOMP WebSocket error: $error");
          onError?.call(error);
        },
        onStompError: (frame) {
          logger.e("⛔ STOMP error frame: ${frame.body}");
        },
        onDisconnect: (frame) {
          logger.w("🔌 STOMP disconnected");
        },
        reconnectDelay: const Duration(seconds: 5),
      ),
    );
    _client!.activate();
  }

  /// 방 하나를 구독한다. 이미 구독 중이면 아무 것도 하지 않는다(중복 방지).
  void subscribeToRoom(String roomId) {
    if (_client == null || !_client!.connected) return;
    if (_unsubscribeByRoom.containsKey(roomId)) return;

    final unsubscribeFn = _client!.subscribe(
      destination: '/topic/rooms/$roomId',
      headers: const {},
      callback: (frame) {
        if (frame.body == null) return;
        try {
          final payload = Map<String, dynamic>.from(
              jsonDecode(frame.body!) as Map);
          _onMessage(roomId, payload);
        } catch (e) {
          logger.e("⛔ STOMP payload 파싱 실패(room=$roomId): $e");
        }
      },
    );
    _unsubscribeByRoom[roomId] = () => unsubscribeFn(unsubscribeHeaders: {});
  }

  void subscribeToRooms(Iterable<String> roomIds) {
    for (final roomId in roomIds) {
      subscribeToRoom(roomId);
    }
  }

  void unsubscribeFromRoom(String roomId) {
    _unsubscribeByRoom.remove(roomId)?.call();
  }

  void disconnect() {
    for (final unsubscribe in _unsubscribeByRoom.values) {
      unsubscribe();
    }
    _unsubscribeByRoom.clear();
    _client?.deactivate();
    _client = null;
  }

  String _toWsUrl(String httpUrl) {
    if (httpUrl.startsWith('https://')) {
      return httpUrl.replaceFirst('https://', 'wss://');
    }
    if (httpUrl.startsWith('http://')) {
      return httpUrl.replaceFirst('http://', 'ws://');
    }
    return httpUrl;
  }
}

final chatStompClient = ChatStompClient();
