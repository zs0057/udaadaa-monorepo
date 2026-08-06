import 'dart:typed_data';

import 'package:udaadaa/models/chat_reaction.dart';
import 'package:udaadaa/models/profile.dart';

DateTime convertToKst(DateTime utcTime) {
  return utcTime.toUtc().add(const Duration(hours: 9));
}

class Message {
  Message({
    this.id,
    required this.roomId,
    required this.userId,
    this.content,
    required this.type,
    this.profile,
    this.createdAt,
    required this.isMine,
    this.imagePath,
    required this.reactions,
    required this.readReceipts,
    this.image,
    this.imageUrl,
    this.isDeleted,
    this.sequence,
  });

  final String? id;
  final String userId;
  final String roomId;
  final String? content;
  final String type;
  final Profile? profile;
  final DateTime? createdAt;
  final bool isMine;
  final Uint8List? image;
  final String? imagePath;
  final bool? isDeleted;
  final int? sequence;
  List<Reaction> reactions;
  Set<String> readReceipts;
  String? imageUrl;

  Map<String, dynamic> toMap() {
    return {
      'user_id': userId,
      'room_id': roomId,
      'content': content,
      'image_path': imagePath,
      'type': type,
      'is_deleted': isDeleted,
    };
  }

  Message.fromMap({
    required Map<String, dynamic> map,
    required String myUserId,
    required this.reactions,
    required this.readReceipts,
    this.profile,
    this.image,
    this.imageUrl,
  })  : id = map['id'],
        roomId = map['room_id'],
        userId = map['user_id'],
        content = map['content'],
        imagePath = map['image_path'],
        createdAt = convertToKst(DateTime.parse(map['created_at'])),
        type = map['type'],
        isMine = map['user_id'] == myUserId,
        isDeleted = map['is_deleted'],
        sequence = null;

  /// Spring Chat API 응답(`MessageSummaryResponse`) 파싱 전용.
  ///
  /// snake_case가 아니라 camelCase 필드(roomId, senderId, imagePath, createdAt,
  /// isDeleted)를 쓰고, 보낸 사람 필드명이 user_id가 아니라 senderId다.
  /// reactions·readReceipts는 이 API가 아직 내려주지 않아 호출부에서 채운다.
  Message.fromSpringMap({
    required Map<String, dynamic> map,
    required String myUserId,
    required this.reactions,
    required this.readReceipts,
    this.profile,
    this.image,
    this.imageUrl,
  })  : id = map['id'] as String,
        roomId = map['roomId'] as String,
        userId = map['senderId'] as String,
        content = map['content'] as String?,
        imagePath = map['imagePath'] as String?,
        createdAt = convertToKst(DateTime.parse(map['createdAt'] as String)),
        type = map['type'] as String,
        isMine = map['senderId'] == myUserId,
        isDeleted = map['isDeleted'] as bool? ?? false,
        sequence = (map['sequence'] as num?)?.toInt();

  Message copyWith({
    String? id,
    String? userId,
    String? roomId,
    String? text,
    String? type,
    Profile? profile,
    DateTime? createdAt,
    bool? isMine,
    List<Reaction>? reactions,
    Set<String>? readReceipts,
    Uint8List? image,
    String? imageUrl,
    String? imagePath,
    bool? isDeleted,
    int? sequence,
  }) {
    return Message(
      id: id ?? this.id,
      userId: userId ?? this.userId,
      roomId: roomId ?? this.roomId,
      content: text ?? content,
      createdAt: createdAt ?? this.createdAt,
      type: type ?? this.type,
      profile: profile ?? this.profile,
      isMine: isMine ?? this.isMine,
      reactions: reactions ?? this.reactions,
      readReceipts: readReceipts ?? this.readReceipts,
      image: image ?? this.image,
      imageUrl: imageUrl ?? this.imageUrl,
      imagePath: imagePath ?? this.imagePath,
      isDeleted: isDeleted ?? this.isDeleted,
      sequence: sequence ?? this.sequence,
    );
  }
}
