import 'dart:async';
import 'dart:io';

import 'package:bloc/bloc.dart';
import 'package:dio/dio.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/widgets.dart';
import 'package:image/image.dart' as img;
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:uuid/uuid.dart';
import 'package:udaadaa/cubit/auth_cubit.dart';
import 'package:udaadaa/cubit/challenge_cubit.dart';
import 'package:udaadaa/cubit/form_cubit.dart';
import 'package:udaadaa/models/calorie.dart';
import 'package:udaadaa/models/chat_reaction.dart';
import 'package:udaadaa/models/feed.dart';
import 'package:udaadaa/models/message.dart';
import 'package:udaadaa/models/profile.dart';
import 'package:udaadaa/models/room.dart';
import 'package:udaadaa/data/chat_api_client.dart';
import 'package:udaadaa/data/chat_stomp_client.dart';
import 'package:udaadaa/data/moderation_api_client.dart';
import 'package:udaadaa/utils/analytics/analytics.dart';
import 'package:udaadaa/utils/constant.dart';

part 'chat_state.dart';

class ChatCubit extends Cubit<ChatState> {
  FormCubit formCubit;
  ChallengeCubit challengeCubit;
  List<Room> chatList = [];
  Map<String, List<Message>> messages = {};
  Map<String, List<Message>> imageMessages = {};

  Map<String, DateTime?> readReceipts = {};
  XFile? _selectedImage;
  List<XFile> _selectedImages = [];

  String? currentRoomId;
  List<String> blockedUsers = [];
  List<String> blockedMessages = [];
  Map<String, int> unreadMessages = {};
  int unreadMessageCount = 0;
  List<MapEntry<Profile, double>> ranking = [];
  double weightAverage = 0.0;
  Map<String, bool> _pushOptions = {};
  bool _initialized = false;
  bool wasPushHandled = false;
  bool isAndroidImageSelected = false;

  Map<String, List<String>> unreadMessageIdsByRoom = {};

  /// 방별 내 읽음 위치(lastReadSequence). GET /rooms 응답에서 채우고,
  /// sendReadReceipt·enterRoom1이 PATCH read-position 호출 후 갱신한다.
  Map<String, int> myLastReadSequenceByRoom = {};

  /// 방별 참가자 전원의 읽음 위치(memberId → lastReadSequence).
  /// GET /rooms/{roomId}/read-positions로 채우고, STOMP readPosition 이벤트로
  /// 실시간 갱신한다. 메시지 하나하나의 "안읽음 N명" 배지(message.readReceipts)는
  /// 이 맵과 message.sequence를 비교해 _recomputeReadReceiptsForRoom이 계산한다
  /// (예전처럼 메시지별 read_receipts row가 더 이상 없기 때문).
  Map<String, Map<String, int>> readPositionsByRoom = {};

  final AuthCubit authCubit;
  late final StreamSubscription authSubscription;

  RealtimeChannel? _messageChannel;
  RealtimeChannel? _reactionChannel;
  RealtimeChannel? _readReceiptChannel;
  int cnt = 0;

  bool _isLoadingMessages = false;
  final Map<String, bool> _loadingMoreMessages = {};

  final String baseUrl = '$supabaseUrl/storage/v1/object/public/ImageMessages/';

  StreamSubscription<RemoteMessage>? _messageOpenedSubscription;

  ChatCubit(this.authCubit, this.formCubit, this.challengeCubit)
      : super(ChatInitial()) {
    debugPrint("🔄 ChatCubit 생성자 호출됨");
    if (authCubit.state is Authenticated) {
      _initialize();
    }

    authSubscription = authCubit.stream.listen((authState) {
      if (authState is Authenticated) {
        _initialize();
      }
    });
  }

  // ChatCubit(this.formCubit, this.challengeCubit) : super(ChatInitial()) {
  //   Future.wait([
  //     fetchBlockedUsers(),
  //     fetchBlockedMessages(),
  //   ]).then(
  //     (value) {
  //       Future.wait([
  //         fetchPushOptions(),
  //         loadChatList().then((_) async {
  //           fetchLatestMessages();
  //           await fetchLatestReceipt();
  //           FirebaseMessaging.onMessage.listen((RemoteMessage message) {
  //             if (message.data['roomId'] != null) {
  //               final roomId = message.data['roomId'];
  //               final roomInfo =
  //                   chatList.firstWhere((room) => room.id == roomId);
  //               emit(ChatPushNotification(roomId, "새로운 메시지가 도착했습니다", roomInfo));
  //             }
  //           });
  //         }).catchError((e) {
  //           logger.e("loadChatList error: $e");
  //         }),
  //         loadInitialMessages(),
  //       ]).then((_) {
  //         calculateUnreadMessages();
  //         _initialized = true;
  //       }).catchError((e) {
  //         logger.e("loadInitialMessages error: $e");
  //       });
  //       setMessagesListener();
  //       setReactionListener();
  //       setReadReceiptListener();
  //     },
  //   ).catchError((e) {
  //     logger.e("fetchBlockedUsers error: $e");
  //   });
  // }

  /// post-initial-chat-data Edge Function 호출을 Spring Chat API(GET /rooms,
  /// GET /rooms/{roomId}/messages, GET /rooms/{roomId}/images)로 교체한 초기 로드.
  ///
  /// Realtime(STOMP 아님, 기존 Supabase Realtime `chat_events` 채널)은 이번 단계에서
  /// 그대로 유지한다 — Spring이 같은 Postgres 테이블에 쓰기 때문에 계속 동작한다.
  /// STOMP 전환은 Flutter 전환 B(쓰기 경로) 단계에서 별도로 진행한다.
  Future<void> _initialize() async {
    try {
      await Future.wait([
        fetchBlockedUsers(),
        fetchBlockedMessages(),
        fetchPushOptions(),
      ]);

      await _loadRoomsAndMessages();

      debugPrint("🔒 Blocked User IDs: $blockedUsers");
      debugPrint("🧱 Blocked Message IDs: $blockedMessages");
      debugPrint("📬 Push Options: $_pushOptions");
      debugPrint("💬 Chat List Count: ${chatList.length}");
      debugPrint("💬 Image Messages Count: ${imageMessages.length}");
      debugPrint("📥 Total unread messages across all rooms: $unreadMessageCount");

      // room_view.dart의 방 목록 화면은 BlocBuilder(buildWhen: ChatMessageLoaded/
      // UnreadMessagesUpdated/ChatMessagesRefreshedFromPush)라서, 이 초기 로드가
      // 아무 상태도 emit 안 하면 화면은 첫 build 시점(_loadRoomsAndMessages가 끝나기
      // 전, chatList가 비어있던 순간)에 멈춘 채로 남는다 — 그 방에 아무 실시간
      // 이벤트도 안 와서 다른 emit이 우연히 안 걸리면 목록이 영영 안 뜨는 버그였다
      // (조용한 새 방에서 재현 확인됨). 초기 로드 완료를 명시적으로 알린다.
      emit(ChatMessageLoaded());
      emit(UnreadMessagesUpdated(unreadMessageCount, unreadMessages));
    } catch (e) {
      logger.e("Error initializing chat data from Spring: $e");
    }

    try {
      await Future.wait([
        // fetchBlockedUsers(),
        // fetchBlockedMessages(),
      ]);

      // await fetchPushOptions();
      // await loadChatList();
      // await fetchLatestMessages();
      // await fetchLatestReceipt();

      await Future.wait([
        // loadInitialMessages1(),
        // fetchUnreadMessageIdsAfterLatestReceipt(),
      ]);

      // Cancel any existing subscription before creating a new one
      if (_messageOpenedSubscription != null) {
        _messageOpenedSubscription!.cancel();
      }

      _messageOpenedSubscription = FirebaseMessaging.onMessageOpenedApp
          .listen((RemoteMessage message) async {
        try {
          wasPushHandled = true;

          final roomId = message.data['roomId'];
          if (roomId != null) {
            // Notify UI that push notification processing has started
            emit(ChatPushStarted());

            // Allow UI to update before heavy processing
            await Future.delayed(const Duration(milliseconds: 600));

            // Refresh messages in batch to optimize network usage
            await refreshAllMessagesForPush();

            // Find room info for the notification
            Room? roomInfo;
            try {
              roomInfo = chatList.firstWhere((room) => room.id == roomId);

              // Emit event with room information
              emit(ChatPushOpenedFromBackground(
                roomId,
                "알림을 클릭하여 들어왔습니다.",
                roomInfo,
              ));
            } catch (e) {
              logger.e("Room not found for push notification: $e");
            }
          }
        } catch (e, stack) {
          logger.e("Error processing push notification",
              error: e, stackTrace: stack);
        }
      });

      setChatEventsListener();
      _connectStomp();
      _initialized = true;
      debugPrint("✅ 초기화 완료!");

      // loadImageMessages();
    } catch (e) {
      logger.e("초기화 실패: $e");
    }
  }

  /// GET /rooms + 방마다 GET /rooms/{roomId}/messages, GET /rooms/{roomId}/images를
  /// 병렬로 불러와 chatList/messages/imageMessages/unread*를 채운다.
  /// _initialize()와 joinRoom() 둘 다 이 메서드로 room 목록을 (다시) 채운다.
  Future<void> _loadRoomsAndMessages() async {
    final myUserId = supabase.auth.currentUser!.id;
    final roomsData = await chatApiClient.getRooms();

    chatList = roomsData.map((roomMap) {
      final membersData =
          List<Map<String, dynamic>>.from(roomMap['members'] as List? ?? []);
      final members = membersData
          .map((m) => Profile(
                id: m['id'] as String,
                nickname: m['nickname'] as String,
              ))
          .toList();

      final lastMessageMap = roomMap['lastMessage'] as Map<String, dynamic>?;
      final lastMessage = lastMessageMap != null
          ? Message.fromSpringMap(
              map: lastMessageMap,
              myUserId: myUserId,
              reactions: [],
              readReceipts: {},
            )
          : null;

      Room room = Room.fromSpringMap(
        roomMap,
        members: members,
        lastMessage: lastMessage,
      );

      room.members.sort(
        ((a, b) => a.id == myUserId
            ? -1
            : b.id == myUserId
                ? 1
                : blockedUsers.contains(a.id)
                    ? 1
                    : blockedUsers.contains(b.id)
                        ? -1
                        : 0),
      );
      return room;
    }).toList();

    chatList.sort((a, b) {
      if (a.lastMessage == null) return 1;
      if (b.lastMessage == null) return -1;
      return b.lastMessage!.createdAt!.compareTo(a.lastMessage!.createdAt!);
    });

    myLastReadSequenceByRoom = {
      for (final r in roomsData)
        r['id'] as String: (r['myLastReadSequence'] as num).toInt(),
    };

    unreadMessageIdsByRoom.clear();
    unreadMessages.clear();
    imageMessages.clear();
    unreadMessageCount = 0;

    // 방마다 최근 메시지 30개 + 최근 이미지 32장을 병렬로 불러온다.
    // 최근 메시지 windowing은 lastMessage.sequence 기준(= 정확히 최신 N개).
    await Future.wait(chatList.map((room) async {
      final memberMap = room.memberMap;
      final lastSeq = room.lastMessage?.sequence ?? 0;
      final myLastRead = myLastReadSequenceByRoom[room.id] ?? 0;
      final windowStart = lastSeq - 30 < 0 ? 0 : lastSeq - 30;

      try {
        final msgData = await chatApiClient.getMessages(
          room.id,
          after: windowStart,
          limit: 30,
        );
        await _fillMissingSenderProfiles(
            room, msgData.map((m) => m['senderId'] as String?));
        final loadedMessages = msgData
            .map((m) => Message.fromSpringMap(
                  map: m,
                  myUserId: myUserId,
                  profile: memberMap[m['senderId']],
                  reactions: [],
                  readReceipts: {},
                ))
            .toList()
          ..sort((a, b) => b.createdAt!.compareTo(a.createdAt!));

        messages[room.id] = loadedMessages;

        // 기존 enterRoom1이 기대하는 형태(상대가 보낸, 아직 안 읽은 메시지 id 목록)를
        // sequence 비교로 재현한다.
        final unreadIds = loadedMessages
            .where(
                (m) => m.userId != myUserId && (m.sequence ?? 0) > myLastRead)
            .map((m) => m.id!)
            .toList();
        unreadMessageIdsByRoom[room.id] = unreadIds;
        unreadMessages[room.id] = unreadIds.length;
        unreadMessageCount += unreadIds.length;

        for (final message in loadedMessages) {
          if (message.imagePath != null) {
            await makeImageUrlMessage(message, emitLoaded: false);
          }
        }
      } catch (e) {
        logger.e("⛔ [${room.roomName}] 메시지 로드 실패: $e");
        messages[room.id] = [];
      }

      try {
        final positionsData = await chatApiClient.getReadPositions(room.id);
        readPositionsByRoom[room.id] = {
          for (final p in positionsData)
            p['memberId'] as String: (p['lastReadSequence'] as num).toInt(),
        };
        _recomputeReadReceiptsForRoom(room.id);
      } catch (e) {
        logger.e("⛔ [${room.roomName}] 읽음 위치 로드 실패: $e");
        readPositionsByRoom[room.id] = {};
      }

      try {
        final imgData = await chatApiClient.getRecentImages(room.id, limit: 32);
        await _fillMissingSenderProfiles(
            room, imgData.map((m) => m['senderId'] as String?));
        imageMessages[room.id] = imgData
            .map((m) => Message.fromSpringMap(
                  map: m,
                  myUserId: myUserId,
                  profile: memberMap[m['senderId']],
                  reactions: [],
                  readReceipts: {},
                ))
            .where((msg) => !(msg.isDeleted ?? false))
            .map((message) => message.copyWith(
                imageUrl: message.imagePath != null
                    ? '$baseUrl${message.imagePath}'
                    : null))
            .toList();
      } catch (e) {
        logger.e("⛔ [${room.roomName}] 이미지 갤러리 로드 실패: $e");
        imageMessages[room.id] = [];
      }
    }));
  }

  /// room.memberMap(GET /rooms가 내려주는 "현재" 참가자 목록 기준)에 없는 발신자를
  /// profiles 테이블에서 직접 채워 넣는다.
  ///
  /// 메시지를 보낸 뒤 방을 나간 사람이나, 방금 참가해서 아직 로컬 캐시(memberMap)에
  /// 반영 안 된 사람의 메시지는 memberMap에서 프로필을 못 찾는다 — 그러면 chat_view가
  /// 닉네임 대신 발신자 id를 그대로 표시한다(asDashChatUser(user, message.profile
  /// ?.nickname ?? user), "이름이 ID값으로 보이는" 간헐적 버그의 원인). 예전 Supabase
  /// Realtime 경로(setChatEventsListener)는 매 메시지마다 profiles를 직접 조회해서
  /// 이 문제가 없었다 — 같은 방식으로 빠진 발신자만 보강한다(있는 건 다시 안 부른다).
  /// 조회 결과는 room.memberMap에도 채워 넣어서 이후 같은 발신자는 캐시로 바로 찾는다.
  Future<void> _fillMissingSenderProfiles(
      Room room, Iterable<String?> senderIds) async {
    final missing = senderIds.whereType<String>().toSet()
      ..removeAll(room.memberMap.keys);
    if (missing.isEmpty) return;

    try {
      final rows =
          await supabase.from('profiles').select().inFilter('id', missing.toList());
      for (final row in rows) {
        final profile = Profile.fromMap(map: row);
        room.memberMap[profile.id] = profile;
        if (!room.members.any((m) => m.id == profile.id)) {
          room.members.add(profile);
        }
      }
    } catch (e) {
      logger.e("⛔ [${room.roomName}] 발신자 프로필 보강 실패: $e");
    }
  }

  /// readPositionsByRoom[roomId](참가자별 lastReadSequence)과 각 메시지의 sequence를
  /// 비교해 messages[roomId]의 readReceipts(그 메시지를 읽은 사람 id 집합)를 다시 계산한다.
  ///
  /// 예전엔 메시지 하나마다 read_receipts row가 있어서 "누가 이 메시지를 읽었는지"를 직접
  /// 알 수 있었지만, 새 API는 방별 lastReadSequence 하나만 갱신한다(CHT 로드맵 3-1).
  /// 그래서 "lastReadSequence가 이 메시지의 sequence 이상인 사람 = 이 메시지를 읽은 사람"으로
  /// 역산한다. chat_bubble.dart의 "안읽음 N명" 배지(memberCount - readReceipts.length)는
  /// 그대로 두고 이 함수가 채우는 값만 바꿨다.
  void _recomputeReadReceiptsForRoom(String roomId) {
    final positions = readPositionsByRoom[roomId];
    final roomMessages = messages[roomId];
    if (positions == null || roomMessages == null) return;

    messages[roomId] = roomMessages.map((message) {
      final seq = message.sequence;
      if (seq == null) return message;
      final readers = positions.entries
          .where((entry) => entry.value >= seq)
          .map((entry) => entry.key)
          .toSet();
      return message.copyWith(readReceipts: readers);
    }).toList();
  }

  // Future<void> _initialize() async {
  //   try {
  //     await Future.wait([
  //       fetchBlockedUsers(),
  //       fetchBlockedMessages(),
  //     ]);

  //     await fetchPushOptions();
  //     await loadChatList();
  //     await fetchLatestMessages();
  //     await fetchLatestReceipt();

  //     await Future.wait([
  //       loadInitialMessages1(),
  //       fetchUnreadMessageIdsAfterLatestReceipt(),
  //     ]);

  //     FirebaseMessaging.onMessageOpenedApp
  //         .listen((RemoteMessage message) async {
  //       wasPushHandled = true;

  //       if (message.data['roomId'] != null) {
  //         emit(ChatPushStarted());
  //       }

  //       await Future.delayed(Duration(milliseconds: 500));

  //       await refreshAllMessagesForPush();

  //       if (message.data['roomId'] != null) {
  //         final roomId = message.data['roomId'];
  //         final roomInfo = chatList.firstWhere((room) => room.id == roomId);

  //         if (message.data['roomId'] != null) {
  //           emit(ChatPushOpenedFromBackground(
  //             roomId,
  //             "알림을 클릭하여 들어왔습니다.",
  //             roomInfo,
  //           ));
  //         }
  //       }
  //     });

  //     setChatEventsListener();
  //     _initialized = true;
  //     debugPrint("✅ 초기화 완료!");

  //     loadImageMessages();
  //   } catch (e) {
  //     logger.e("초기화 실패: $e");
  //   }
  // }

  /// Push 수신 시 전체 상태를 새로고침한다. 예전엔 Supabase를 5단계로 나눠 직접
  /// 호출했는데(loadChatList/fetchLatestMessages/fetchLatestReceipt/loadInitialMessages1/
  /// fetchUnreadMessageIdsAfterLatestReceipt), 그중 read_receipts 기반 읽음 계산은
  /// Flutter 전환 D 이후로는 새 쓰기가 없는 죽은 테이블을 읽는 셈이라 더 이상 정확하지
  /// 않다. _loadRoomsAndMessages()(Spring REST + sequence 기반)로 통일한다.
  Future<void> refreshAllMessagesForPush() async {
    try {
      await _loadRoomsAndMessages();

      // ✅ 현재 방에 다시 입장 처리
      if (currentRoomId != null) {
        debugPrint("💡 currentRoomId=$currentRoomId → 자동 enterRoom 호출");
        await enterRoom1(currentRoomId!);
      }

      emit(ChatInitial());
      emit(ChatMessageLoaded());
    } catch (e) {
      logger.e("refreshAllMessagesForPush error: $e");
    }
  }

  Future<void> processImageMessages(List<Message> messages) async {
    const int batchSize = 30;

    for (var i = 0; i < messages.length; i += batchSize) {
      final batch = messages.skip(i).take(batchSize).toList();

      await Future.wait(
        batch.map((message) async {
          if (message.imagePath != null) {
            await makeImageUrlMessage(message);
          }
        }),
      );

      await Future.delayed(const Duration(milliseconds: 50));
    }
  }

  Future<void> makeImageUrlMessage(Message message, {emitLoaded = true}) async {
    if (message.imagePath != null) {
      // final baseUrl =
      //     'https://ccpcclfqofyvksajnrpg.supabase.co/storage/v1/object/public/ImageMessages/';
      final fullUrl = '$baseUrl${message.imagePath}';

      messages[message.roomId] = List.from(messages[message.roomId]!.map((m) {
        if (m.id == message.id) {
          m = message.copyWith(imageUrl: fullUrl);
        }
        return m;
      }));

      if (emitLoaded) {
        emit(ChatMessageLoaded());
      }
    } else {
      logger.w("⚠️ [makeImageUrlMessage] imagePath가 null입니다. 재시도하겠습니다.");
    }
    cnt++;
  }

  // private 방식
  // Future<void> makeImageUrlMessage(Message message, {emitLoaded = true}) async {
  //   if (message.imagePath != null) {
  //     try {
  //       final url = await _getSignedUrlWithRetry(message.imagePath!);

  //       if (url == null) {
  //         logger.e("⛔ Signed URL을 생성하지 못했습니다.");
  //         return;
  //       }
  //       messages[message.roomId] = List.from(messages[message.roomId]!.map((m) {
  //         if (m.id == message.id) {
  //           m = message.copyWith(imageUrl: url);
  //         }
  //         return m;
  //       }));

  //       if (emitLoaded) {
  //         emit(ChatMessageLoaded());
  //       }
  //     } catch (e) {
  //       logger.e("⛔ makeImageUrl error: $e");
  //     }
  //   } else {
  //     logger.w("⚠️ [makeImageUrlMessage] imagePath가 null입니다. 재시도하겠습니다.");
  //   }
  //   cnt++;
  // }

  // Future<String?> _getSignedUrlWithRetry(String path, {int retry = 4}) async {
  //   for (int i = 0; i < retry; i++) {
  //     try {
  //       final url = await supabase.storage
  //           .from('ImageMessages')
  //           .createSignedUrl(path, 3600 * 3)
  //           .timeout(const Duration(milliseconds: 700));

  //       return url;
  //     } catch (e) {
  //       logger.w("🔁 createSignedUrl 실패 (시도 ${i + 1}/$retry): $e");
  //       await Future.delayed(Duration(milliseconds: 300));
  //     }
  //   }
  //   return null;
  // }

  Future<void> fetchUnreadMessageIdsAfterLatestReceipt(
      {bool emitLoaded = true}) async {
    unreadMessageCount = 0;
    final userId = supabase.auth.currentUser!.id;

    try {
      for (final room in chatList) {
        final latestReceipt = readReceipts[room.id];
        debugPrint("📬 [${room.roomName}] latestReceipt (KST): $latestReceipt");

        var query = supabase
            .from('messages')
            .select('id, created_at')
            .eq('room_id', room.id)
            .neq('user_id', userId);

        if (latestReceipt != null) {
          // KST에서 UTC로 변환 (KST는 UTC+9)
          // 9시간을 빼서 UTC로 변환
          final adjusted = latestReceipt.subtract(const Duration(hours: 9));
          debugPrint("🕐 [${room.roomName}] KST: $latestReceipt");
          debugPrint("🕐 [${room.roomName}] UTC: $adjusted");
          debugPrint(
              "🕐 [${room.roomName}] ISO8601: ${adjusted.toIso8601String()}");

          query = query.gt('created_at', adjusted.toIso8601String());
        }

        final response = await query;

        final ids = response.map<String>((row) => row['id'] as String).toList();
        unreadMessageIdsByRoom[room.id] = ids;

        unreadMessages[room.id] = ids.length;
        unreadMessageCount += ids.length;

        // 각 unread 메시지 ID도 출력
        for (var id in ids) {
          logger.d("📥 [${room.roomName}] unread message ID: $id");
        }

        logger.d("📥 [${room.roomName}] → ${ids.length}개의 unread 메시지 ID");
      }
    } catch (e) {
      logger.e("fetchUnreadMessageIdsAfterLatestReceipt error: $e");
    }
    if (emitLoaded) {
      emit(ChatMessageLoaded());
    }
  }

  Future<void> fetchPushOptions() async {
    try {
      final response = await supabase
          .from('room_participants')
          .select('push_option, room_id')
          .eq('user_id', supabase.auth.currentUser!.id);
      // logger.d(response);
      _pushOptions = response.fold<Map<String, bool>>(
        {},
        (previousValue, element) => {
          ...previousValue,
          element['room_id'] as String: element['push_option'] as bool
        },
      );
      // logger.d("fetchPushOptions: $_pushOptions");
    } catch (e) {
      logger.e('Error fetching push options: $e');
    }
  }

  Future<void> loadChatList({bool emitLoaded = true}) async {
    try {
      final ret = await supabase.from('rooms').select('*, profiles(*)');
      logger.d(ret);
      chatList = ret.map(
        (e) {
          Room room = Room.fromMap(
            e,
            members: (e['profiles'] as List<dynamic>)
                .map((profileRet) => Profile.fromMap(map: profileRet))
                .toList(),
          );
          room.members.sort(
            ((a, b) => a.id == supabase.auth.currentUser!.id
                ? -1
                : b.id == supabase.auth.currentUser!.id
                    ? 1
                    : blockedUsers.contains(a.id)
                        ? 1
                        : blockedUsers.contains(b.id)
                            ? -1
                            : 0),
          );
          return room;
        },
      ).toList();

      logger.d("loadChatList: ${chatList[0].members}");
      logger.d("loadChatList: ${chatList[0].memberMap}");
      if (emitLoaded) {
        emit(ChatListLoaded());
      }
    } catch (e) {
      logger.e("loadChatList error: $e");
    }
  }

  Future<void> fetchLatestMessages({bool emitLoaded = true}) async {
    try {
      final updatedChatList = await Future.wait(
        chatList.map((room) async {
          final response = await supabase
              .from('messages')
              .select('*')
              .eq('room_id', room.id)
              .order('created_at', ascending: false)
              .limit(1);
          if (response.isEmpty) return room;

          final message = Message.fromMap(
            map: response[0],
            myUserId: supabase.auth.currentUser!.id,
            profile: room.memberMap[response[0]['user_id']]!,
            reactions: [],
            readReceipts: {},
          );

          return room.copyWith(lastMessage: message); // 새로운 Room 객체 반환
        }),
      );

      chatList = updatedChatList;
      chatList.sort((a, b) {
        if (a.lastMessage == null) return 1;
        if (b.lastMessage == null) return -1;
        return b.lastMessage!.createdAt!.compareTo(a.lastMessage!.createdAt!);
      });
      if (emitLoaded) {
        emit(ChatListLoaded());
      }
    } catch (e) {
      logger.e('Error fetching latest messages: $e');
    }
  }

  Future<void> fetchLatestReceipt() async {
    try {
      final futures = chatList.map((room) async {
        final response = await supabase
            .from('read_receipts')
            .select('created_at')
            .eq('room_id', room.id)
            .eq('user_id', supabase.auth.currentUser!.id)
            .order('created_at', ascending: false)
            .limit(1);

        if (response.isNotEmpty) {
          final createdAt = DateTime.parse(response[0]['created_at']);

          if (readReceipts[room.id] == null ||
              readReceipts[room.id]!.isBefore(createdAt)) {
            readReceipts[room.id] = createdAt.add(const Duration(hours: 9));
            logger.d(
                "✅ readReceipts 업데이트: ${room.id} → ${readReceipts[room.id]}");
          }
        }
      });
      await Future.wait(futures);
      logger.d("🔹 fetchLatestReceipt 실행 완료");
    } catch (e) {
      logger.e('Error fetching latest read receipts: $e');
    }
    debugPrint(
        "📜 Latest Read Receipts: \n${readReceipts.entries.map((entry) => 'Room ID: ${entry.key}, Last Read: ${entry.value}').join('\n')}");
  }

  Future<void> fetchBlockedUsers() async {
    try {
      blockedUsers = await moderationApiClient.getBlockedMemberIds();
      logger.d("fetchBlockedUsers: $blockedUsers");
    } catch (e) {
      logger.e('Error fetching blocked users: $e');
    }
  }

  Future<void> fetchBlockedMessages() async {
    try {
      final response = await supabase
          .from('blocked_messages')
          .select('message_id')
          .eq('user_id', supabase.auth.currentUser!.id);
      blockedMessages = response.map((e) => e['message_id'] as String).toList();
      logger.d("fetchBlockedMessages: $blockedMessages");
    } catch (e) {
      logger.e('Error fetching blocked messages: $e');
    }
  }

  Future<void> fetchRoomRanking(Room roomInfo, {emitLoaded = true}) async {
    try {
      final results = await Future.wait(roomInfo.members.map((member) async {
        logger.d(member);
        final response = await supabase
            .from('weight')
            .select('weight')
            .eq('user_id', member.id)
            .lte(
                'created_at',
                roomInfo.endDay!
                    .subtract(const Duration(hours: 9))
                    .add(const Duration(days: 1))
                    .toIso8601String())
            .gte(
                'created_at',
                roomInfo.startDay!
                    .subtract(const Duration(hours: 9))
                    .toIso8601String())
            .order('created_at', ascending: true);

        if (response.isNotEmpty) {
          final weight = (response[response.length - 1]['weight'] as num) -
              (response[0]['weight'] as num);

          return MapEntry(member, weight.toDouble());
          //ranking.add(MapEntry(member, weight.toDouble()));
        } else {
          return MapEntry(member, 0.0);
          //ranking.add(MapEntry(member, 0.0));
        }
      }));
      ranking = [];
      ranking.addAll(results);
      logger.d("fetchRoomRanking: $ranking");
      ranking.sort((a, b) => a.value.compareTo(b.value));

      final sum = ranking.fold<double>(
          0, (previousValue, element) => previousValue + element.value);
      weightAverage = sum / ranking.length;

      if (emitLoaded) {
        emit(ChatListLoaded());
      }
    } catch (e) {
      logger.e('Error fetching room ranking: $e');
    }
  }

  Future<void> loadMoreMessages() async {
    if (currentRoomId == null || _loadingMoreMessages[currentRoomId] == true) {
      return;
    }

    final existingMessages = messages[currentRoomId!];
    if (existingMessages == null || existingMessages.isEmpty) return;

    try {
      _loadingMoreMessages[currentRoomId!] = true;

      final oldestMessage = existingMessages.last;
      final oldestSeq = oldestMessage.sequence;
      if (oldestSeq == null || oldestSeq <= 1) {
        logger.d("🚫 더 이전 메시지가 없습니다(oldestSeq=$oldestSeq).");
        return;
      }

      const pageSize = 20;
      final windowStart =
          oldestSeq - 1 - pageSize < 0 ? 0 : oldestSeq - 1 - pageSize;

      final room = chatList.firstWhere((r) => r.id == currentRoomId);
      final memberMap = room.memberMap;
      final myUserId = supabase.auth.currentUser!.id;

      final data = await chatApiClient.getMessages(
        currentRoomId!,
        after: windowStart,
        limit: pageSize,
      );
      await _fillMissingSenderProfiles(
          room, data.map((m) => m['senderId'] as String?));

      final newMessages = data
          .where((m) => (m['sequence'] as num).toInt() < oldestSeq)
          .map((m) => Message.fromSpringMap(
                map: m,
                myUserId: myUserId,
                profile: memberMap[m['senderId']],
                reactions: [],
                readReceipts: {},
              ))
          .toList()
        ..sort((a, b) => b.createdAt!.compareTo(a.createdAt!));

      messages[currentRoomId!]!.addAll(newMessages);

      for (var message in newMessages) {
        if (message.imagePath != null) {
          await makeImageUrlMessage(message);
        }
      }

      // ✅ 로드 후 전체 메시지 로그
      final updatedMessages = messages[currentRoomId]!;
      debugPrint("🆕 업데이트된 메시지 (${updatedMessages.length}개):");
      for (int i = 0; i < updatedMessages.length; i++) {
        debugPrint("  [$i] ${updatedMessages[i].content}");
      }

      emit(ChatMessageLoaded());
    } catch (e) {
      logger.e("loadMoreMessages error: $e");
    } finally {
      _loadingMoreMessages[currentRoomId!] = false;
    }
  }

  Future<void> loadInitialMessages2(
      {bool emitLoaded = true, Map<String, dynamic>? jsonData}) async {
    if (chatList.isEmpty) {
      logger.w("⚠️ chatList가 비어있습니다");
      return;
    }

    if (jsonData == null) {
      logger.w("⚠️ jsonData가 null입니다");
      return;
    }

    try {
      // jsonData의 각 키(roomId)에 대해 처리
      for (final roomId in jsonData.keys) {
        logger.d("🔄 처리 중인 roomId: $roomId");

        try {
          final messagesData = jsonData[roomId] as List<dynamic>;

          // 기존 메시지 초기화하고 새로 저장하기!
          messages[roomId] = messagesData
              .map((row) => Message.fromMap(
                    map: row,
                    myUserId: supabase.auth.currentUser!.id,
                    profile: Profile.fromMap(map: row['profiles']),
                    reactions: (row['chat_reactions'] as List<dynamic>)
                        .map(
                            (reactionRet) => Reaction.fromMap(map: reactionRet))
                        .toList(),
                    readReceipts: (row['read_receipts'] as List<dynamic>)
                        .map((receiptRet) => receiptRet['user_id'] as String)
                        .toSet(),
                  ))
              .toList();

          // 이미지 메시지 처리
          for (var message in messages[roomId]!) {
            if (message.imagePath != null) {
              if (emitLoaded) {
                await makeImageUrlMessage(message);
              } else {
                await makeImageUrlMessage(message, emitLoaded: false);
              }
            }
          }

          logger.d(
              "✅ roomId: $roomId에 대한 메시지 ${messages[roomId]?.length ?? 0}개 로드 완료");
        } catch (innerError) {
          logger.e("❌ roomId: $roomId 처리 중 오류 발생: $innerError");
        }
      }

      if (emitLoaded) {
        emit(ChatMessageLoaded());
      }
    } catch (e) {
      logger.e("loadInitialMessages2 error: $e");
    } finally {
      _isLoadingMessages = false;
    }
  }

  Future<void> loadInitialMessages1({bool emitLoaded = true}) async {
    if (chatList.isEmpty) {
      logger.w("⚠️ chatList가 비어있습니다");
      return;
    }
    // if (_isLoadingMessages) {
    //   logger.w("⚠️ 이미 로딩 중입니다!");
    //   return;
    // }

    try {
      for (final room in chatList) {
        final roomId = room.id;
        final ret = await supabase
            .from('messages')
            .select(
                "*, profiles!messages_user_id_fkey(*), chat_reactions(*), read_receipts(user_id)")
            .eq('room_id', roomId)
            .not('user_id', 'in', blockedUsers)
            .not('id', 'in', blockedMessages)
            .order('created_at', ascending: false)
            .limit(20);

        // ✅ 기존 메시지 초기화하고 새로 저장하기!
        messages[roomId] = ret
            .map((row) => Message.fromMap(
                  map: row,
                  myUserId: supabase.auth.currentUser!.id,
                  profile: Profile.fromMap(map: row['profiles']),
                  reactions: (row['chat_reactions'] as List<dynamic>)
                      .map((reactionRet) => Reaction.fromMap(map: reactionRet))
                      .toList(),
                  readReceipts: (row['read_receipts'] as List<dynamic>)
                      .map((receiptRet) => receiptRet['user_id'] as String)
                      .toSet(),
                ))
            .toList();

        for (var message in messages[roomId]!) {
          if (message.imagePath != null) {
            if (emitLoaded) {
              await makeImageUrlMessage(message);
            } else {
              await makeImageUrlMessage(message, emitLoaded: false);
            }
          }
        }
      }
      if (emitLoaded) {
        emit(ChatMessageLoaded());
      }
    } catch (e) {
      logger.e("loadInitialMessages1 error : $e");
    } finally {
      _isLoadingMessages = false;
    }
  }

  Future<void> loadInitialMessages() async {
    try {
      final ret = await supabase
          .from('messages')
          .select(
              "*, profiles!messages_user_id_fkey(*), chat_reactions(*), read_receipts(user_id)")
          .not('user_id', 'in', blockedUsers)
          .not('id', 'in', blockedMessages)
          .order('created_at');
      // logger.d(ret);
      for (var row in ret) {
        final roomId = row['room_id'];
        if (!messages.containsKey(roomId)) {
          messages[roomId] = [];
        }
        messages[roomId]!.add(
          Message.fromMap(
            map: row,
            myUserId: supabase.auth.currentUser!.id,
            profile: Profile.fromMap(map: row['profiles']),
            reactions: (row['chat_reactions'] as List<dynamic>)
                .map((reactionRet) => Reaction.fromMap(map: reactionRet))
                .toList(),
            readReceipts: (row['read_receipts'] as List<dynamic>)
                .map((receiptRet) => receiptRet['user_id'] as String)
                .toSet(),
          ),
        );
      }
      /*
      messages = ret
          .map(
            (e) => Message.fromMap(
              map: e,
              myUserId: supabase.auth.currentUser!.id,
              profile: Profile.fromMap(map: e['profiles']),
              reactions: (e['chat_reactions'] as List<dynamic>)
                  .map((reactionRet) => Reaction.fromMap(map: reactionRet))
                  .toList(),
              readReceipts: (e['read_receipts'] as List<dynamic>)
                  .map((receiptRet) => receiptRet['user_id'] as String)
                  .toSet(),
            ),
          )
          .toList();*/

      // for (var room in messages.keys) {
      //   final imageMessages =
      //       messages[room]!.where((msg) => msg.imagePath != null).toList();
      //   await processImageMessages(imageMessages);
      // }

      for (var room in messages.keys) {
        for (var message in messages[room]!) {
          if (message.imagePath != null) makeImageUrlMessage(message);
        }
        // if (message.type == 'imageMessage') makeImageUrlMessage(message);
      }
      emit(ChatMessageLoaded());
    } catch (e) {
      logger.e("getInitialMessages error : $e");
    }
  }

  void calculateUnreadMessages() {
    unreadMessageCount = 0;

    for (var room in chatList) {
      final roomMessages = messages[room.id];
      if (roomMessages == null || roomMessages.isEmpty) {
        continue; // ✅ 메시지가 없으면 skip
      }

      final unreadMessagesList = roomMessages
          .where((message) =>
              message.createdAt != null &&
              (readReceipts[room.id] == null ||
                  message.createdAt!.isAfter(readReceipts[room.id]!)))
          .toList();

      logger.d("calculateUnreadMessages: ${unreadMessagesList.length}");
      logger.d("readReceipts: ${readReceipts[room.id]}");

      unreadMessages[room.id] = unreadMessagesList.length;
      unreadMessageCount += unreadMessagesList.length;
    }

    emit(UnreadMessagesUpdated(unreadMessageCount, unreadMessages));
  }

  // void setMessagesListener() {
  //   final existingChannels = supabase.getChannels();
  //   final isAlreadySubscribed = existingChannels.any((c) {
  //     return c.toString().contains('public:messages');
  //   });

  //   if (isAlreadySubscribed) {
  //     debugPrint("⚠️ 이미 메시지 채널 구독 중, 중복 방지");
  //     return;
  //   }

  //   _messageChannel = supabase
  //       .channel('public:messages')
  //       .onPostgresChanges(
  //           event: PostgresChangeEvent.insert,
  //           schema: 'public',
  //           table: 'messages',
  //           callback: (payload) async {
  //             if (blockedUsers.contains(payload.newRecord['user_id'])) return;
  //             final profileRet = await supabase
  //                 .from('profiles')
  //                 .select()
  //                 .eq('id', payload.newRecord['user_id'])
  //                 .single();
  //             final message = Message.fromMap(
  //               map: payload.newRecord,
  //               myUserId: supabase.auth.currentUser!.id,
  //               profile: Profile.fromMap(map: profileRet),
  //               reactions: [],
  //               readReceipts: {},
  //             );
  //             if (message.imagePath != null) await makeImageUrlMessage(message);
  //             logger.d("setMessagesListener: $message");
  //             // messages = [message, ...messages];
  //             final updatedChatList = List<Room>.from(chatList);
  //             final roomIndex = updatedChatList
  //                 .indexWhere((room) => room.id == message.roomId);

  //             if (roomIndex != -1) {
  //               updatedChatList[roomIndex] =
  //                   updatedChatList[roomIndex].copyWith(
  //                 lastMessage: message,
  //               );
  //             }
  //             chatList = updatedChatList;
  //             // chatList.sort((a, b) => b.lastMessage!.createdAt!
  //             //     .compareTo(a.lastMessage!.createdAt!));

  //             // 6. 정렬 (null safety 적용)
  //             chatList.sort((a, b) {
  //               final aTime = a.lastMessage?.createdAt;
  //               final bTime = b.lastMessage?.createdAt;

  //               if (aTime == null && bTime == null) return 0;
  //               if (bTime == null) return -1;
  //               if (aTime == null) return 1;
  //               return bTime.compareTo(aTime);
  //             });

  //             if (!messages.containsKey(message.roomId)) {
  //               messages[message.roomId] = [];
  //             }
  //             messages[message.roomId] = [
  //               message,
  //               ...messages[message.roomId]!
  //             ];
  //             if (message.roomId == currentRoomId) {
  //               sendReadReceipt(message.roomId, message.id!);
  //             } else {
  //               unreadMessages[message.roomId] =
  //                   (unreadMessages[message.roomId] ?? 0) + 1;
  //               unreadMessageCount++;
  //             }

  //             emit(ChatMessageLoaded());
  //           })
  //       .subscribe();
  // }

  void setChatEventsListener() {
    final existingChannels = supabase.getChannels();
    if (existingChannels
        .any((c) => c.toString().contains('public:chat_events'))) {
      debugPrint("⚠️ 이미 chat_events 채널 구독 중");
      return;
    }

    _messageChannel = supabase.channel('public:chat_events');

    _messageChannel!
      ..onPostgresChanges(
        event: PostgresChangeEvent.insert,
        schema: 'public',
        table: 'messages',
        callback: (payload) async {
          final newMsg = payload.newRecord;
          if (blockedUsers.contains(newMsg['user_id'])) return;

          final profileRet = await supabase
              .from('profiles')
              .select()
              .eq('id', newMsg['user_id'])
              .single();

          final message = Message.fromMap(
            map: newMsg,
            myUserId: supabase.auth.currentUser!.id,
            profile: Profile.fromMap(map: profileRet),
            reactions: [],
            readReceipts: {},
          );

          //메시지 추가
          if (!messages.containsKey(message.roomId)) {
            messages[message.roomId] = [];
          }

          // Take first 5 messages and check for duplicates
          final recentMessages = messages[message.roomId]!.take(5).toList();
          if (recentMessages.any((m) => m.id == message.id)) {
            return;
          }

          //메시지 추가
          messages[message.roomId] = [message, ...messages[message.roomId]!];

          // 한마디가 잘 작동하기위해 메시지 소트
          messages[message.roomId]!.sort((a, b) {
            final aTime = a.createdAt;
            final bTime = b.createdAt;
            if (aTime == null && bTime == null) return 0;
            if (bTime == null) return -1;
            if (aTime == null) return 1;
            return bTime.compareTo(aTime);
          });

          if (message.imagePath != null) {
            await makeImageUrlMessage(message);
          }

          //이미지 메시지 추가
          if (message.imagePath != null) {
            if (!imageMessages.containsKey(message.roomId)) {
              imageMessages[message.roomId] = [];
            }
            imageMessages[message.roomId] = [
              message,
              ...imageMessages[message.roomId]!
            ];
            await makeImageUrlImageMessage(message);
          }

          final index =
              chatList.indexWhere((room) => room.id == message.roomId);
          if (index != -1) {
            chatList[index] = chatList[index].copyWith(lastMessage: message);
          }

          chatList.sort((a, b) {
            final aTime = a.lastMessage?.createdAt;
            final bTime = b.lastMessage?.createdAt;
            if (aTime == null && bTime == null) return 0;
            if (bTime == null) return -1;
            if (aTime == null) return 1;
            return bTime.compareTo(aTime);
          });

          if (message.roomId == currentRoomId) {
            sendReadReceipt(message.roomId, message.id!, message.sequence);
          } else {
            unreadMessageIdsByRoom[message.roomId] ??=
                []; // 리스트가 없다면 빈 리스트로 초기화
            unreadMessageIdsByRoom[message.roomId]!.add(message.id!);
            unreadMessages[message.roomId] =
                (unreadMessages[message.roomId] ?? 0) + 1;
            unreadMessageCount++;
          }

          emit(ChatMessageLoaded());
        },
      )
      ..onPostgresChanges(
        event: PostgresChangeEvent.update,
        schema: 'public',
        table: 'messages',
        callback: (payload) async {
          final updatedMsg = payload.newRecord;
          if (updatedMsg['is_deleted'] == true) {
            try {
              final roomId = updatedMsg['room_id'];
              final messageId = updatedMsg['id'];

              if (messages.containsKey(roomId)) {
                messages[roomId] = messages[roomId]!.map((message) {
                  if (message.id == messageId) {
                    debugPrint("Message marked as deleted: $messageId");
                    return message.copyWith(isDeleted: true);
                  }
                  return message;
                }).toList();
              } else {
                debugPrint("Room not found for deleted message: $roomId");
              }
            } catch (e) {
              debugPrint("Error updating deleted message: ${e.toString()}");
            }
          }
          debugPrint("updatedMsg: $updatedMsg");

          emit(ChatMessageLoaded());
        },
      )
      ..onPostgresChanges(
        event: PostgresChangeEvent.insert,
        schema: 'public',
        table: 'chat_reactions',
        callback: (payload) {
          final reaction = Reaction.fromMap(map: payload.newRecord);
          messages[reaction.roomId] =
              List.from(messages[reaction.roomId]!.map((message) {
            if (message.id == reaction.messageId) {
              message =
                  message.copyWith(reactions: [reaction, ...message.reactions]);
            }
            return message;
          }));
          emit(ChatMessageLoaded());
        },
      )
      ..onPostgresChanges(
        event: PostgresChangeEvent.insert,
        schema: 'public',
        table: 'read_receipts',
        callback: (payload) {
          final roomId = payload.newRecord['room_id'];
          messages[roomId] = List.from(messages[roomId]!.map((message) {
            if (message.id == payload.newRecord['message_id']) {
              message = message.copyWith(readReceipts: {
                ...message.readReceipts,
                payload.newRecord['user_id'],
              });
            }
            return message;
          }));
          emit(ChatMessageLoaded());
          debugPrint("setReadReceiptcallback 실행!");
        },
      )
      ..subscribe();
  }

  /// STOMP(`/ws/chat`)에 연결하고 참가 중인 모든 방의 `/topic/rooms/{roomId}`를 구독한다.
  ///
  /// 위의 Supabase Realtime `chat_events`(messages INSERT) 리스너는 아직 그대로 둔다 —
  /// sendMessage/sendImageMessage를 Spring REST로 전환했지만, 다른 전송 경로(Phase C
  /// 대상: 방 참가 등)가 아직 Supabase를 직접 건드릴 수 있어 완전히 걷어내기엔 이르다.
  /// 같은 메시지가 STOMP와 Realtime 양쪽에서 오더라도 _ingestStompMessage의 id 중복 체크로
  /// 한 번만 반영된다.
  void _connectStomp() {
    chatStompClient.connect(
      onConnected: () {
        for (final room in chatList) {
          chatStompClient.subscribeToRoom(room.id);
          _catchUpRoom(room.id);
        }
      },
      onMessage: _handleStompMessage,
    );
  }

  void _handleStompMessage(String roomId, Map<String, dynamic> payload) async {
    // 같은 /topic/rooms/{roomId} 토픽에 메시지·읽음위치 이벤트가 같이 온다.
    // eventType이 없으면(구버전 서버 호환) 기존처럼 메시지로 취급한다.
    if (payload['eventType'] == 'readPosition') {
      _handleReadPositionEvent(roomId, payload);
      return;
    }

    if (blockedUsers.contains(payload['senderId'])) return;

    final myUserId = supabase.auth.currentUser!.id;
    Room? room;
    try {
      room = chatList.firstWhere((r) => r.id == roomId);
    } catch (_) {
      return; // 내가 모르는 방이면 무시(구독 권한 자체가 서버에서 막히므로 실질적으로 안 옴)
    }

    // 방금 참가한 사람이 보낸 첫 메시지라 로컬 memberMap에 아직 없을 수 있다.
    await _fillMissingSenderProfiles(room, [payload['senderId'] as String?]);

    final message = Message.fromSpringMap(
      map: {
        ...payload,
        // STOMP 브로드캐스트 페이로드(ChatMessageBroadcastPayload)에는 createdAt이 없다
        // — 방금 커밋된 메시지라 지금 시각으로 근사해도 실사용에 문제없다.
        'createdAt': DateTime.now().toUtc().toIso8601String(),
        'isDeleted': false,
      },
      myUserId: myUserId,
      profile: room.memberMap[payload['senderId']],
      reactions: [],
      readReceipts: {},
    );

    _ingestStompMessage(message);
  }

  /// ReadPositionBroadcastPayload 수신 처리 — 다른 참가자(또는 내 다른 기기)가
  /// 읽음 위치를 갱신했을 때 실시간으로 온다. 방을 열어두고 있으면 "안읽음 N명" 배지가
  /// 즉시 줄어든다.
  void _handleReadPositionEvent(String roomId, Map<String, dynamic> payload) {
    final memberId = payload['memberId'] as String?;
    final lastReadSequence = (payload['lastReadSequence'] as num?)?.toInt();
    if (memberId == null || lastReadSequence == null) return;

    final positions = readPositionsByRoom[roomId] ??= {};
    final current = positions[memberId] ?? 0;
    if (lastReadSequence <= current) return; // 뒤로 가는 값(또는 중복)은 무시

    positions[memberId] = lastReadSequence;
    if (memberId == supabase.auth.currentUser?.id) {
      myLastReadSequenceByRoom[roomId] = lastReadSequence;
    }
    _recomputeReadReceiptsForRoom(roomId);
    emit(ChatMessageLoaded());
  }

  /// 재연결 시(onConnect가 다시 호출될 때마다) 끊겨있던 동안 놓친 메시지를 REST로 따라잡는다.
  Future<void> _catchUpRoom(String roomId) async {
    try {
      final loaded = messages[roomId];
      final after = (loaded != null && loaded.isNotEmpty)
          ? (loaded.first.sequence ?? myLastReadSequenceByRoom[roomId] ?? 0)
          : (myLastReadSequenceByRoom[roomId] ?? 0);

      final data = await chatApiClient.getMessages(roomId, after: after, limit: 50);
      if (data.isEmpty) return;

      final myUserId = supabase.auth.currentUser!.id;
      final room = chatList.firstWhere((r) => r.id == roomId);
      final memberMap = room.memberMap;
      await _fillMissingSenderProfiles(
          room, data.map((m) => m['senderId'] as String?));

      for (final m in data) {
        final message = Message.fromSpringMap(
          map: m,
          myUserId: myUserId,
          profile: memberMap[m['senderId']],
          reactions: [],
          readReceipts: {},
        );
        _ingestStompMessage(message);
      }
    } catch (e) {
      logger.e("⛔ [$roomId] STOMP 재연결 gap-recovery 실패: $e");
    }
  }

  /// STOMP·gap-recovery 공용 메시지 반영. Realtime insert 핸들러와 로직은 같지만
  /// (id 중복 체크로 서로 안전하게 공존) 그 핸들러 자체는 건드리지 않기 위해 분리했다.
  void _ingestStompMessage(Message message) {
    if (!messages.containsKey(message.roomId)) {
      messages[message.roomId] = [];
    }

    final recentMessages = messages[message.roomId]!.take(5).toList();
    if (recentMessages.any((m) => m.id == message.id)) {
      return;
    }

    messages[message.roomId] = [message, ...messages[message.roomId]!];
    messages[message.roomId]!.sort((a, b) {
      final aTime = a.createdAt;
      final bTime = b.createdAt;
      if (aTime == null && bTime == null) return 0;
      if (bTime == null) return -1;
      if (aTime == null) return 1;
      return bTime.compareTo(aTime);
    });

    if (message.imagePath != null) {
      makeImageUrlMessage(message, emitLoaded: false);

      imageMessages[message.roomId] ??= [];
      imageMessages[message.roomId] = [message, ...imageMessages[message.roomId]!];
      makeImageUrlImageMessage(message);
    }

    final index = chatList.indexWhere((room) => room.id == message.roomId);
    if (index != -1) {
      chatList[index] = chatList[index].copyWith(lastMessage: message);
    }

    chatList.sort((a, b) {
      final aTime = a.lastMessage?.createdAt;
      final bTime = b.lastMessage?.createdAt;
      if (aTime == null && bTime == null) return 0;
      if (bTime == null) return -1;
      if (aTime == null) return 1;
      return bTime.compareTo(aTime);
    });

    if (message.roomId == currentRoomId) {
      sendReadReceipt(message.roomId, message.id!, message.sequence);
    } else {
      unreadMessageIdsByRoom[message.roomId] ??= [];
      unreadMessageIdsByRoom[message.roomId]!.add(message.id!);
      unreadMessages[message.roomId] = (unreadMessages[message.roomId] ?? 0) + 1;
      unreadMessageCount++;
    }

    emit(ChatMessageLoaded());
  }

  // void setReactionListener() {
  //   final existingChannels = supabase.getChannels();
  //   final isAlreadySubscribed = existingChannels.any((c) {
  //     return c.toString().contains('public:chat_reactions');
  //   });

  //   if (isAlreadySubscribed) {
  //     debugPrint("⚠️ 이미 리액션 채널 구독 중, 중복 방지");
  //     return;
  //   }
  //   _reactionChannel = supabase
  //       .channel('public:chat_reactions')
  //       .onPostgresChanges(
  //           event: PostgresChangeEvent.insert,
  //           schema: 'public',
  //           table: 'chat_reactions',
  //           callback: (payload) {
  //             final reaction = Reaction.fromMap(
  //               map: payload.newRecord,
  //             );
  //             /*
  //             messages = messages.map((message) {
  //               if (message.id == reaction.messageId) {
  //                 message.reactions = [reaction, ...message.reactions];
  //               }
  //               return message;
  //             }).toList();*/
  //             messages[reaction.roomId] =
  //                 List.from(messages[reaction.roomId]!.map((message) {
  //               if (message.id == reaction.messageId) {
  //                 message = message
  //                     .copyWith(reactions: [reaction, ...message.reactions]);
  //               }
  //               return message;
  //             }));
  //             logger.d("setReactionListener: $reaction");
  //             emit(ChatMessageLoaded());
  //           })
  //       .subscribe();
  // }

  // void setReadReceiptListener() {
  //   final existingChannels = supabase.getChannels();
  //   final isAlreadySubscribed = existingChannels.any((c) {
  //     return c.toString().contains('public:read_receipts');
  //   });

  //   if (isAlreadySubscribed) {
  //     debugPrint("⚠️ 이미 읽음 채널 구독 중, 중복 방지");
  //     return;
  //   }
  //   _readReceiptChannel = supabase
  //       .channel('public:read_receipts')
  //       .onPostgresChanges(
  //           event: PostgresChangeEvent.insert,
  //           schema: 'public',
  //           table: 'read_receipts',
  //           callback: (payload) {
  //             final roomId = payload.newRecord['room_id'];
  //             messages[roomId] = List.from(messages[roomId]!.map((message) {
  //               if (message.id == payload.newRecord['message_id']) {
  //                 message = message.copyWith(readReceipts: {
  //                   ...message.readReceipts,
  //                   payload.newRecord['user_id'],
  //                 });
  //               }
  //               return message;
  //             }));
  //             emit(ChatMessageLoaded());
  //             debugPrint("setReadReceiptcallback 실행!");
  //           })
  //       .subscribe();
  // }

  Future<void> joinRoomByRoomName(String roomName) async {
    try {
      emit(JoinRoomLoading()); // ✅ 로딩 시작

      final trimmedName = roomName.trim();

      final res = await supabase.functions
          .invoke('get-room-id-by-name', body: {'room_name': trimmedName});

      final data = res.data;

      if (res.status == 200 && data != null && data['room_id'] != null) {
        final roomId = data['room_id'] as String;
        logger.d("✅ Edge Function 매칭된 room_id: $roomId");
        // joinRoom() 안에서 이미 _loadRoomsAndMessages()로 안읽음 상태까지 다시 계산한다
        // (read_receipts 기반 재계산은 Flutter 전환 D 이후로는 죽은 테이블을 읽는 셈이라 제거).
        await joinRoom(roomId);
      } else {
        logger.e("⛔ 방 이름 매칭 실패: ${data?['error'] ?? 'Unknown'}");
        emit(JoinRoomFailed("방을 찾을 수 없습니다.")); // ❌ 실패 시 상태
        return;
      }
    } catch (e, stack) {
      logger.e("❌ joinRoomByRoomName error", error: e, stackTrace: stack);
      emit(JoinRoomFailed("방을 찾을 수 없습니다.")); // ❌ 실패 시 상태
      return;
    }
  }

  @override
  Future<void> close() {
    debugPrint("👋 ChatCubit close() called");
    authSubscription.cancel(); // ✅ 스트림 해제

    _messageChannel?.unsubscribe(); // ✅ Supabase 채널 해제
    _reactionChannel?.unsubscribe();
    _readReceiptChannel?.unsubscribe();
    chatStompClient.disconnect();

    return super.close();
  }

  Future<void> joinRoom(String roomId) async {
    try {
      await chatApiClient.joinRoom(roomId);
    } on DioException catch (e) {
      if (e.response?.statusCode != 409) {
        emit(JoinRoomFailed("방 참가에 실패했습니다."));
        logger.e("joinRoom error: $e");
        return;
      }
      // 409 = 이미 참가 중인 방(ALREADY_JOINED). 예전엔 이걸 그냥 실패로 처리하고
      // 여기서 끝내버려서, 첫 시도 때 참가자 등록은 이미 성공했는데(멱등) 목록
      // 새로고침을 못 받은 채 재시도하면 "입장했다는데 목록엔 안 보이는" 상태로
      // 남는 버그가 있었다. 이제는 실패로 보지 않고 아래에서 목록만 새로고침한다.
      logger.d("joinRoom: 이미 참가 중인 방 → 목록만 새로고침 (roomId=$roomId)");
    } catch (e) {
      emit(JoinRoomFailed("방 참가에 실패했습니다."));
      logger.e("joinRoom error: $e");
      return;
    }

    try {
      // 새로 참가한 방을 포함해 rooms/messages/images를 통째로 다시 불러온다
      // (기존처럼 방별로 따로따로 REST를 부르는 대신 _initialize()와 같은 경로를 재사용).
      await _loadRoomsAndMessages();
    } catch (e) {
      // 목록 새로고침 자체가 실패하면 "입장 성공"이라고 거짓으로 알리지 않는다 —
      // 참가자 등록(POST /participants)은 멱등이라 재시도하면 그대로 목록에 뜬다.
      logger.e("joinRoom 목록 새로고침 실패: $e");
      emit(JoinRoomFailed("방에 참가했지만 목록을 불러오지 못했습니다. 다시 시도해주세요."));
      return;
    }

    chatStompClient.subscribeToRoom(roomId);

    try {
      final roomInfo = chatList.firstWhere((room) => room.id == roomId);
      await fetchRoomRanking(roomInfo, emitLoaded: false);
      if (roomInfo.startDay != null && roomInfo.endDay != null) {
        try {
          await challengeCubit.enterChallengeByDay(
              roomInfo.startDay!, roomInfo.endDay!);
        } catch (e) {
          logger.e("joinRoom 챌린지 등록 실패, 참가를 롤백합니다: $e");
          await chatApiClient.leaveRoom(roomId);
        }
      }
    } catch (e) {
      // 랭킹·챌린지 연동은 부가 기능이라 실패해도 "방 참가" 자체는 성공으로 본다.
      logger.e("joinRoom 부가 처리 실패: $e");
    }

    emit(JoinRoomSuccess()); // ✅ 성공 시 상태
    emit(ChatMessageLoaded());
  }

  Future<void> enterRoom1(String roomId) async {
    debugPrint("🟢 enterRoom1 실행됨! roomId: $roomId");

    const maxRetries = 10;
    const delay = Duration(milliseconds: 500);

    bool userFetched = false;

    for (int attempt = 0; attempt < maxRetries; attempt++) {
      final user = supabase.auth.currentUser;
      if (user != null) {
        userFetched = true;
        break;
      }
      await Future.delayed(delay);
    }

    if (!userFetched) {
      debugPrint("❗auth.uid()가 끝내 null이었음. roomId=$roomId");
      return; // 여기서 바로 중단해도 좋음!
    }

    try {
      currentRoomId = roomId;

      // 1. 읽지 않은 메시지 ID 목록 가져오기(방별 최신 sequence까지 한 번에 읽음 처리한다)
      final unreadMessageIds = unreadMessageIdsByRoom[roomId] ?? [];
      debugPrint("📥 읽지 않은 메시지 ID들: $unreadMessageIds");

      if (unreadMessageIds.isEmpty) {
        debugPrint("읽지 않은 메시지가 없습니다. 읽음 상태 업데이트를 건너뜁니다.");
        emit(ChatMessageLoaded());
        return;
      }

      // 2. 방에 로드된 메시지 중 최신 sequence를 내 읽음 위치로 올린다.
      //    (메시지 하나하나 read_receipts row를 만들던 예전 방식 대신, 방별
      //    lastReadSequence 하나만 PATCH — CHT 로드맵 3-1)
      final roomMessages = messages[roomId] ?? [];
      final maxSequence = roomMessages
          .map((m) => m.sequence ?? 0)
          .fold<int>(myLastReadSequenceByRoom[roomId] ?? 0,
              (max, seq) => seq > max ? seq : max);

      final currentPosition = myLastReadSequenceByRoom[roomId] ?? 0;
      if (maxSequence > currentPosition) {
        try {
          await chatApiClient.updateReadPosition(roomId, maxSequence);
          myLastReadSequenceByRoom[roomId] = maxSequence;
          final myUserId = supabase.auth.currentUser!.id;
          final positions = readPositionsByRoom[roomId] ??= {};
          positions[myUserId] = maxSequence;
          _recomputeReadReceiptsForRoom(roomId);
          debugPrint("✅ 읽음 위치 업데이트 성공! → $maxSequence");
        } catch (e) {
          debugPrint("❌ 읽음 위치 업데이트 실패! 이유: $e");
        }
      }

      // 3. 읽지 않은 메시지 카운트 초기화
      final previousUnreadCount = unreadMessages[roomId] ?? 0;
      unreadMessageCount -= previousUnreadCount;
      unreadMessages[roomId] = 0;
      unreadMessageIdsByRoom[roomId] = [];

      // 4. 읽음 영수증 타임스탬프 업데이트(레거시 위젯 호환용 필드, 값 자체는 더 안 쓰임)
      readReceipts[roomId] = DateTime.now();
      debugPrint("현재 읽음 시간${readReceipts[roomId]}");

      // 5. UI 갱신을 위한 상태 이벤트 발생
      emit(ChatMessageLoaded());
      emit(UnreadMessagesUpdated(
          unreadMessageCount, unreadMessages)); // 필요한 인자 전달

      debugPrint("✅ 읽음 처리 완료: 이전 미읽수 $previousUnreadCount개 감소됨");
      debugPrint("여기 enterRoom1 끝났어용");
    } catch (e) {
      logger.e("enterRoom1 error: $e");
      debugPrint("❌ enterRoom1 오류 발생: $e");
    }
  }

  // Future<void> enterRoom(String roomId) async {
  //   debugPrint("🟢 enterRoom 실행됨! roomId: $roomId");
  //   const maxRetries = 10;
  //   const delay = Duration(milliseconds: 500);

  //   bool userFetched = false;

  //   for (int attempt = 0; attempt < maxRetries; attempt++) {
  //     final user = supabase.auth.currentUser;
  //     if (user != null) {
  //       userFetched = true;
  //       break;
  //     }
  //     await Future.delayed(delay);
  //   }

  //   if (!userFetched) {
  //     debugPrint("❗auth.uid()가 끝내 null이었음. roomId=$roomId");
  //     return;
  //   }

  //   debugPrint("현재 채팅방 데이터:");
  //   for (var room in getChatList) {
  //     debugPrint("roomId: ${room.id}, roomName: ${room.roomName}");
  //   }

  //   // roomMessages 로드될 때까지 대기
  //   await Future.delayed(delay);

  //   List<Message>? roomMessages;
  //   roomMessages = messages[roomId];

  //   if (roomMessages == null) {
  //     debugPrint("❗ messages[$roomId]가 끝내 null입니다. 메시지 로드 실패");
  //     return;
  //   }

  //   try {
  //     currentRoomId = roomId;

  //     // Get all unread messages for this room
  //     final unreadRoomMessages = roomMessages
  //         .where((message) =>
  //             message.createdAt != null &&
  //             (readReceipts[roomId] == null ||
  //                 message.createdAt!.isAfter(readReceipts[roomId]!)))
  //         .toList();

  //     logger.d("readReceipts: ${readReceipts[roomId]}");
  //     logger.d("enterRoom: $unreadRoomMessages");
  //     logger.d("읽지 않은 메시지 총 ${unreadRoomMessages.length}개");

  //     for (var msg in unreadRoomMessages) {
  //       logger.d(
  //           "📩 messageId: ${msg.id}, content: ${msg.content}, createdAt: ${msg.createdAt}, sender: ${msg.userId}");
  //     }

  //     // Create read receipts for all unread messages
  //     final readReceiptsMap = unreadRoomMessages
  //         .map((message) => {
  //               'room_id': roomId,
  //               'message_id': message.id,
  //               'user_id': supabase.auth.currentUser!.id,
  //               'created_at': DateTime.now().toIso8601String(), // Add timestamp
  //             })
  //         .toList();

  //     debugPrint("업서트 할 readReceiptsMap: $readReceiptsMap");

  //     // Remove duplicates
  //     final seen = <String>{};
  //     final uniqueReadReceiptsMap = <Map<String, dynamic>>[];

  //     for (var receipt in readReceiptsMap) {
  //       final key = '${receipt['room_id']}_${receipt['message_id']}';
  //       if (!seen.contains(key)) {
  //         seen.add(key);
  //         uniqueReadReceiptsMap.add(receipt);
  //       } else {
  //         debugPrint("중복된 데이터 발견: $receipt");
  //       }
  //     }

  //     // Send read receipts to server
  //     if (uniqueReadReceiptsMap.isNotEmpty) {
  //       debugPrint("🔽 upsert 시도 전...");
  //       try {
  //         await supabase.from('read_receipts').upsert(uniqueReadReceiptsMap);
  //         debugPrint("✅ upsert 성공!");

  //         // Update local state after successful server update
  //         readReceipts[roomId] = unreadMessages.isNotEmpty
  //             ? unreadRoomMessages.first.createdAt
  //             : DateTime.now();

  //         // Update unread counts
  //         final previousUnreadCount = unreadMessages[roomId] ?? 0;
  //         unreadMessageCount -= previousUnreadCount;
  //         unreadMessages = unreadMessages.map((key, value) {
  //           if (key == roomId) {
  //             return MapEntry(key, 0);
  //           }
  //           return MapEntry(key, value);
  //         });

  //         // Emit state update
  //         emit(ChatMessageLoaded());

  //         debugPrint("읽음 처리 완료: 이전 미읽수 $previousUnreadCount개 감소됨");
  //       } catch (e) {
  //         debugPrint("❌ upsert 실패! 이유: $e");
  //         // Consider retrying or handling the error appropriately
  //       }
  //     }

  //     debugPrint("여기 엔터룸 끝났어용");
  //   } catch (e) {
  //     logger.e("enterRoom error: $e");
  //   }
  // }

  Future<void> enterRoom(String roomId) async {
    debugPrint("현재 채팅방 데이터:");
    for (var room in getChatList) {
      debugPrint("roomId: ${room.id}, roomName: ${room.roomName}");
    }

    try {
      currentRoomId = roomId;
      final unreadRoomMessages = messages[roomId]!
          .where((message) =>
              message.createdAt != null &&
              (readReceipts[roomId] == null ||
                  message.createdAt!.isAfter(readReceipts[roomId]!)))
          .toList();
      logger.d("readReceipts: ${readReceipts[roomId]}");
      logger.d("enterRoom: $unreadRoomMessages");
      final readReceiptsMap = unreadRoomMessages
          .map((message) => {
                'room_id': roomId,
                'message_id': message.id,
                'user_id': supabase.auth.currentUser!.id,
              })
          .toList();
      if (readReceiptsMap.isEmpty) return;
      await supabase.from('read_receipts').upsert(readReceiptsMap);
      readReceipts[roomId] = unreadMessages.isNotEmpty
          ? unreadRoomMessages.first.createdAt
          : DateTime.now();
      unreadMessageCount -= unreadMessages[roomId] ?? 0;
      unreadMessages = unreadMessages.map((key, value) {
        if (key == roomId) {
          return MapEntry(key, 0);
        }
        return MapEntry(key, value);
      });
      emit(ChatMessageLoaded());
    } catch (e) {
      logger.e("enterRoom error: $e");
    }
  }

  void leaveRoom(String roomId) {
    logger.d("leaveRoom: $roomId");
    currentRoomId = null;
  }

  /// Spring REST(`POST /rooms/{roomId}/messages`)로 전송한다.
  ///
  /// 여기서 로컬 상태를 직접 갱신하지 않는다 — 원래도 그랬듯(Supabase upsert 시절에도
  /// UI 갱신은 realtime insert 콜백이 담당) 서버가 저장을 커밋한 뒤 STOMP로 다시
  /// 나(=참가자)에게 브로드캐스트해주는 걸 그대로 받아서 반영한다(_ingestStompMessage).
  Future<void> sendMessage(String content, String type, String roomId) async {
    try {
      final clientMessageId = const Uuid().v4();
      await chatApiClient.sendMessage(
        roomId,
        clientMessageId: clientMessageId,
        type: type,
        content: content,
      );
    } catch (e) {
      logger.e("sendMessage error: $e");
    }
  }

  Future<void> selectImage(ImageSource pickertype) async {
    if (Platform.isAndroid) {
      isAndroidImageSelected = true;
    }
    final ImagePicker picker = ImagePicker();
    final List<XFile> pickedFiles = await picker.pickMultiImage();
    if (pickedFiles.isNotEmpty) {
      _selectedImages = pickedFiles;
    }
  }

  Future<void> deleteMessage(String roomId, String messageId) async {
    try {
      await chatApiClient.deleteMessage(roomId, messageId);
    } catch (e) {
      logger.e("deleteMessage error: $e");
    }
  }

  Future<File?> compressImage(File file) async {
    try {
      final img.Image? image = img.decodeImage(file.readAsBytesSync());

      if (image == null) {
        throw Exception('Unable to decode image');
      }

      img.Image resizedImage = img.copyResize(image, width: 1024);

      final Directory tempDir = await getTemporaryDirectory();
      final String tempPath = tempDir.path;
      final File compressedImage =
          File('$tempPath/${DateTime.now().microsecondsSinceEpoch}.jpg')
            ..writeAsBytesSync(img.encodeJpg(resizedImage, quality: 60));

      return compressedImage;
    } catch (e) {
      Analytics().logEvent("업로드_압축실패", parameters: {"에러": e.toString()});
      logger.e(e);
      return null;
    }
  }

  Future<String?> uploadImage(String roomId, XFile? otherFile) async {
    final session = supabase.auth.currentSession;
    if (session == null || session.isExpired) {
      await supabase.auth.refreshSession();
    }
    final XFile? file = otherFile ?? _selectedImage;
    if (file == null) {
      logger.e('No image selected');
      return null;
    }

    try {
      final File? compressedImage = await compressImage(File(file.path));
      if (compressedImage == null) {
        logger.e('Failed to compress image');
        return null;
      }
      // CHT-06: 실제 업로드 경로는 항상 서버(Spring)가 발급한다 —
      // 참가자 검증(requireParticipant)을 통과해야만 경로를 받을 수 있다.
      final imagePath = await chatApiClient.approveImageUpload(roomId);

      for (int retry = 0; retry < 3; retry++) {
        try {
          await supabase.storage
              .from('ImageMessages')
              .upload(imagePath, compressedImage)
              .timeout(const Duration(seconds: 15));
          return imagePath;
        } catch (e) {
          if (retry < 2) {
            logger.w("이미지 업로드 재시도 중... (${retry + 1}/3)");
            await Future.delayed(const Duration(milliseconds: 300));
            continue;
          }
          logger.e(e);
          Analytics().logEvent("업로드_이미지실패", parameters: {"에러": e.toString()});
          return null;
        }
      }
      return null;
    } catch (e) {
      logger.e(e);
      Analytics().logEvent("업로드_이미지실패", parameters: {"에러": e.toString()});
      return null;
    }
  }

  Future<List<String>> uploadImages(
      String roomId, List<XFile> otherFiles) async {
    final session = supabase.auth.currentSession;
    if (session == null || session.isExpired) {
      await supabase.auth.refreshSession();
    }
    final List<XFile> files = _selectedImages;
    if (files.isEmpty) {
      logger.e('No images selected');
      return [];
    }

    List<String> uploadedPaths = [];

    for (final file in files) {
      try {
        final File? compressedImage = await compressImage(File(file.path));
        if (compressedImage == null) {
          logger.e('Failed to compress image: ${file.path}');
          continue;
        }

        // CHT-06: 이미지마다 서버가 발급한 경로를 새로 받는다.
        final imagePath = await chatApiClient.approveImageUpload(roomId);
        bool uploadSuccess = false;

        for (int retry = 0; retry < 3; retry++) {
          try {
            await supabase.storage
                .from('ImageMessages')
                .upload(imagePath, compressedImage)
                .timeout(const Duration(seconds: 15));
            uploadSuccess = true;
            uploadedPaths.add(imagePath);
            break;
          } catch (e) {
            if (retry < 2) {
              logger.w("이미지 업로드 재시도 중... (${retry + 1}/3)");
              await Future.delayed(const Duration(milliseconds: 300));
              continue;
            }
            logger.e("이미지 업로드 실패: ${file.path}");
            Analytics().logEvent("업로드_이미지실패", parameters: {"에러": e.toString()});
          }
        }

        if (!uploadSuccess) {
          logger.e("이미지 업로드 최종 실패: ${file.path}");
        }
      } catch (e) {
        logger.e("이미지 처리 중 오류 발생: ${file.path}");
        Analytics().logEvent("업로드_이미지실패", parameters: {"에러": e.toString()});
      }
    }

    return uploadedPaths;
  }

  Future<void> sendImageMessage(String roomId) async {
    try {
      await selectImage(ImageSource.gallery);

      if (_selectedImages.isEmpty) {
        logger.e('No images selected');
        return;
      }
      final imagePaths = await uploadImages(roomId, _selectedImages);

      for (final imagePath in imagePaths) {
        final clientMessageId = const Uuid().v4();
        await chatApiClient.sendMessage(
          roomId,
          clientMessageId: clientMessageId,
          type: 'imageMessage',
          imagePath: imagePath,
        );
      }
      _selectedImage = null;
      _selectedImages = [];
    } catch (e) {
      logger.e("sendImageMessage error: $e");
    }
  }

  /// 방을 열어두고 있는 동안 새 메시지가 도착하면(내가 보낸 메시지 포함) 즉시 내 읽음
  /// 위치를 그 메시지의 sequence까지 올린다. sequence가 없으면(구형 페이로드 등) 안전하게
  /// 건너뛴다 — STOMP 경로는 항상 sequence를 갖고 있어 정상 동작한다.
  void sendReadReceipt(String roomId, String messageId, int? sequence) async {
    try {
      logger.d("sendReadReceipt: $roomId $messageId seq=$sequence");
      if (sequence == null) return;

      final current = myLastReadSequenceByRoom[roomId] ?? 0;
      if (sequence <= current) return;

      await chatApiClient.updateReadPosition(roomId, sequence);
      myLastReadSequenceByRoom[roomId] = sequence;

      final myUserId = supabase.auth.currentUser!.id;
      final positions = readPositionsByRoom[roomId] ??= {};
      positions[myUserId] = sequence;
      _recomputeReadReceiptsForRoom(roomId);

      readReceipts[roomId] = DateTime.now();
    } catch (e) {
      logger.e("sendReadReceipt error: $e");
    }
  }

  void sendReaction(String roomId, String messageId, String emoji) async {
    try {
      await chatApiClient.addReaction(roomId, messageId, emoji);
    } catch (e) {
      logger.e("sendReaction error: $e");
    }
  }

  void blockUser(String userId) async {
    try {
      await moderationApiClient.blockMember(userId);
      blockedUsers.add(userId);
      messages.forEach((roomId, messageList) {
        messages[roomId] = List.from(messageList.where((message) {
          return message.userId != userId;
        }));
      });
      emit(BlockUserFinished());
      logger.d("blockUser: $userId");
    } catch (e) {
      logger.e("blockUser error: $e");
    }
  }

  void blockMessage(String messageId, String roomId) async {
    try {
      await chatApiClient.hideMessage(roomId, messageId);
      blockedMessages.add(messageId);
      messages[roomId] = List.from(messages[roomId]!.where((message) {
        return message.id != messageId;
      }));
      emit(ChatMessageLoaded());
      logger.d("blockMessage: $messageId");
    } catch (e) {
      logger.e("blockMessage error: $e");
    }
  }

  Future<void> togglePushOption(String roomId, bool value) async {
    try {
      final res = await supabase
          .from('room_participants')
          .update({
            'push_option': value,
          })
          .eq('room_id', roomId)
          .eq('user_id', supabase.auth.currentUser!.id)
          .select();
      logger.d(res);
      _pushOptions = {
        ..._pushOptions,
        roomId: value,
      };
      emit(ChatPushLoaded());
    } catch (e) {
      logger.e('Error toggling push option: $e');
    }
  }

  void missionComplete({
    required FeedType type,
    required String review,
    double? weight,
    int? exerciseTime,
    String? mealContent,
    Calorie? calorie,
    required String contentType,
  }) async {
    if (currentRoomId == null) return;
    final userId = supabase.auth.currentUser!.id;
    /*
  // 인증 데이터
  final feedData = {
    'user_id': userId,
    'review': content,
    'image_path': imageUrl,
    'type': 'certification',
  };*/

    final [imagePath, feedData] = await Future.wait([
      uploadImage(currentRoomId!, formCubit.selectedImages[contentType]),
      formCubit.feedInfo(
        type: type,
        review: review,
        contentType: contentType,
        calorie: calorie,
        mealContent: mealContent,
        weight: weight,
        exerciseTime: exerciseTime,
      ),
    ]);
    if (imagePath == null) {
      logger.e('Failed to upload image');
      return;
    }
    feedData['type'] = (feedData['type'] as FeedType).name;

    /*final feedData = await formCubit.feedInfo(
      type: type,
      review: review,
      contentType: contentType,
      calorie: calorie,
      mealContent: mealContent,
    );*/

    logger.d(feedData);
    // 채팅 메시지 데이터
    final messageData = {
      'room_id': currentRoomId,
      'user_id': userId,
      'image_path': imagePath,
      'content': mealContent,
      'type': 'missionMessage',
    };
    logger.d(messageData);

    Map<FeedType, String> feedHash = {
      FeedType.weight: '#체중',
      FeedType.breakfast: '#아침',
      FeedType.lunch: '#점심',
      FeedType.dinner: '#저녁',
      FeedType.snack: '#간식',
      FeedType.exercise: '#운동',
    };

    try {
      // 트랜잭션 실행
      final feedId = await supabase.rpc('mission_complete', params: {
        'user_id': userId,
        'review': feedData['review'],
        'feed_type': feedData['type'],
        'feed_image_path': feedData['image_path'],
        'calorie': feedData['calorie'],
        'room_id': messageData['room_id'],
        'content': '${feedHash[type]} ${messageData['content']}',
        'message_image_path': messageData['image_path'],
        'message_type': messageData['type'],
        'weight_date': DateTime.now().toIso8601String(),
        'weight': feedData['weight'],
      });
      formCubit.missionComplete(
        type: type,
        review: review,
        contentType: contentType,
        feedId: feedId,
        calorie: calorie,
        mealContent: mealContent,
        weight: weight,
        exerciseTime: exerciseTime,
      );

      logger.d('Certification uploaded successfully!');
    } catch (e) {
      logger.e('Error uploading certification: $e');
    }
    challengeCubit.updateMission();
  }

  Future<void> loadImageMessages() async {
    if (chatList.isEmpty) {
      logger.w("⚠️ chatList가 비어있거나 이미 로딩 중입니다!");
      return;
    }

    try {
      // const baseUrl =
      //     'https://ccpcclfqofyvksajnrpg.supabase.co/storage/v1/object/public/ImageMessages/';

      for (final room in chatList) {
        final roomId = room.id;

        final ret = await supabase
            .from('messages')
            .select(
                "*, profiles!messages_user_id_fkey(*), chat_reactions(*), read_receipts(user_id)")
            .eq('room_id', roomId)
            .not('image_path', 'is', null)
            .not('user_id', 'in', blockedUsers)
            .not('id', 'in', blockedMessages)
            .order('created_at', ascending: false)
            .limit(32);

        // ✅ imageMessages에만 저장 + public URL 붙이기
        imageMessages[roomId] = ret
            .map((row) => Message.fromMap(
                  map: row,
                  myUserId: supabase.auth.currentUser!.id,
                  profile: Profile.fromMap(map: row['profiles']),
                  reactions: (row['chat_reactions'] as List<dynamic>)
                      .map((reactionRet) => Reaction.fromMap(map: reactionRet))
                      .toList(),
                  readReceipts: (row['read_receipts'] as List<dynamic>)
                      .map((receiptRet) => receiptRet['user_id'] as String)
                      .toSet(),
                ))
            .map((message) => message.copyWith(
                imageUrl: message.imagePath != null
                    ? '$baseUrl${message.imagePath}'
                    : null))
            .toList();

        logger.d("✅ 방 ID: $roomId의 이미지 메시지 처리 완료");
      }
    } catch (e) {
      logger.e("❌ loadImageMessages error: $e");
    } finally {
      _isLoadingMessages = false;
    }
  }

  // Future<void> loadImageMessages() async {
  //   if (chatList.isEmpty || _isLoadingMessages) {
  //     logger.w("⚠️ chatList가 비어있거나 이미 로딩 중입니다!");
  //     return;
  //   }

  //   try {
  //     _isLoadingMessages = true;

  //     for (final room in chatList) {
  //       final roomId = room.id;
  //       final ret = await supabase
  //           .from('messages')
  //           .select(
  //               "*, profiles!messages_user_id_fkey(*), chat_reactions(*), read_receipts(user_id)")
  //           .eq('room_id', roomId)
  //           .not('image_path', 'is', null) // image_path가 null이 아닌 것만
  //           .not('user_id', 'in', blockedUsers)
  //           .not('id', 'in', blockedMessages)
  //           .order('created_at', ascending: false)
  //           .limit(32);

  //       // ✅ 기존 이미지 메시지 초기화하고 새로 저장하기!
  //       imageMessages[roomId] = ret
  //           .map((row) => Message.fromMap(
  //                 map: row,
  //                 myUserId: supabase.auth.currentUser!.id,
  //                 profile: Profile.fromMap(map: row['profiles']),
  //                 reactions: (row['chat_reactions'] as List<dynamic>)
  //                     .map((reactionRet) => Reaction.fromMap(map: reactionRet))
  //                     .toList(),
  //                 readReceipts: (row['read_receipts'] as List<dynamic>)
  //                     .map((receiptRet) => receiptRet['user_id'] as String)
  //                     .toSet(),
  //               ))
  //           .toList();

  //       final roomMessages = imageMessages[roomId]!;
  //       for (var i = 0; i < roomMessages.length; i += 32) {
  //         final batch = roomMessages.skip(i).take(32).toList();

  //         await Future.wait(
  //           batch.map((message) async {
  //             if (message.imagePath != null) {
  //               try {
  //                 String? url;
  //                 for (int retry = 0; retry < 3; retry++) {
  //                   url = await _getSignedUrlWithRetry(message.imagePath!);
  //                   if (url != null) break;
  //                   if (retry < 2) {
  //                     logger.w("🔄 URL 생성 재시도 중... (${retry + 1}/3)");
  //                     await Future.delayed(
  //                         Duration(milliseconds: 300 * (retry + 1)));
  //                   }
  //                 }

  //                 if (url != null) {
  //                   imageMessages[roomId] =
  //                       List.from(imageMessages[roomId]!.map((m) {
  //                     if (m.id == message.id) {
  //                       return m.copyWith(imageUrl: url);
  //                     }
  //                     return m;
  //                   }));

  //                   if (messages.containsKey(roomId)) {
  //                     messages[roomId] = List.from(messages[roomId]!.map((m) {
  //                       if (m.id == message.id) {
  //                         return m.copyWith(imageUrl: url);
  //                       }
  //                       return m;
  //                     }));
  //                   }

  //                   // logger.d("✅ 메시지 ID: ${message.id}의 이미지 URL 생성 성공");
  //                 } else {
  //                   logger.e("❌ 메시지 ID: ${message.id}의 이미지 URL 생성 실패");
  //                 }
  //               } catch (e) {
  //                 logger.e("❌ 이미지 URL 생성 중 오류 발생: $e");
  //               }
  //             }
  //           }),
  //         );

  //         await Future.delayed(const Duration(milliseconds: 100));
  //       }

  //       logger.d("✅ 방 ID: $roomId의 이미지 메시지 처리 완료");
  //     }

  //     // emit(ChatMessageLoaded());
  //     logger.d("🎉 모든 이미지 메시지 로딩 완료!");
  //   } catch (e) {
  //     logger.e("❌ loadImageMessages error: $e");
  //   } finally {
  //     _isLoadingMessages = false;
  //   }
  // }

  Future<void> makeImageUrlImageMessage(Message message) async {
    if (message.imagePath != null) {
      // final baseUrl =
      //     'https://ccpcclfqofyvksajnrpg.supabase.co/storage/v1/object/public/ImageMessages/';
      final fullUrl = '$baseUrl${message.imagePath}';

      imageMessages[message.roomId] =
          List.from(imageMessages[message.roomId]!.map((m) {
        if (m.id == message.id) {
          m = message.copyWith(imageUrl: fullUrl);
        }
        return m;
      }));

      emit(ChatMessageLoaded());
    } else {
      logger.w("⚠️ [makeImageUrlImageMessage] imagePath가 null입니다. 재시도하겠습니다.");
    }
  }

  // Future<void> makeImageUrlImageMessage(Message message) async {
  //   if (message.imagePath != null) {
  //     try {
  //       final url = await _getSignedUrlWithRetry(message.imagePath!);

  //       if (url == null) {
  //         logger.e("⛔ Signed URL을 생성하지 못했습니다.");
  //         return;
  //       }
  //       imageMessages[message.roomId] =
  //           List.from(imageMessages[message.roomId]!.map((m) {
  //         if (m.id == message.id) {
  //           m = message.copyWith(imageUrl: url);
  //         }
  //         return m;
  //       }));

  //       emit(ChatMessageLoaded());
  //     } catch (e) {
  //       logger.e("⛔ makeImageUrl error: $e");
  //     }
  //   } else {
  //     logger.w("⚠️ [makeImageUrlImageMessage] imagePath가 null입니다. 재시도하겠습니다.");
  //   }
  // }

  Room getRoom(String roomId) =>
      chatList.firstWhere((element) => element.id == roomId);

  Profile? getProfile(String roomId, String userId) =>
      getRoom(roomId).memberMap[userId];

  List<Message> getMessagesByRoomId(String roomId) => messages[roomId] ?? [];
  List<Message> getImageMessagesByRoomId(String roomId) {
    final messages = imageMessages[roomId] ?? [];

    return messages;
  }

  List<Room> get getChatList => chatList;
  Map<String, List<Message>> get getMessages => messages;
  List<String> get getBlockedUsers => blockedUsers;
  Map<String, int> get getUnreadMessages => unreadMessages;
  int get getUnreadMessageCount => unreadMessageCount;
  List<MapEntry<Profile, double>> get getRanking => ranking;
  double get getWeightAverage => weightAverage;
  Map<String, bool> get getPushOptions => _pushOptions;
  bool get isInitialized => _initialized;
  XFile? get selectedImage => _selectedImage;
  Map<String, List<Message>> get getImageMessages => imageMessages;
}
