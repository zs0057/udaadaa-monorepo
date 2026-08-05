class Profile {
  Profile({
    required this.id,
    required this.nickname,
    this.createdAt,
    this.pushOption,
    this.fcmToken,
    this.height,
    this.weight,
  });

  final String id;
  final String nickname;
  final DateTime? createdAt;
  final bool? pushOption;
  final String? fcmToken;
  final double? height;
  final double? weight;

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'id': id,
      'nickname': nickname,
      'fcm_token': fcmToken,
      'push_option': pushOption,
      'height': height,
      'weight': weight,
    };
  }

  Profile.fromMap({
    required Map<String, dynamic> map,
  })  : id = map['id'] as String,
        nickname = map['nickname'] as String,
        createdAt = DateTime.parse(map['created_at'] as String),
        pushOption = map['push_option'] as bool,
        fcmToken = map['fcm_token'] as String?,
        height =
            map['height'] != null ? (map['height'] as num).toDouble() : null,
        weight =
            map['weight'] != null ? (map['weight'] as num).toDouble() : null;

  /// Spring Member API 응답(`MemberProfileResponse`) 파싱 전용.
  ///
  /// Spring은 fcm_token·push_option을 다루지 않으므로 이 값들은 항상 null로
  /// 채워진다. 기존 프로필과 합칠 때는 `copyWith`로 이전 값을 보존해야 한다.
  Profile.fromSpringMap({
    required Map<String, dynamic> map,
  })  : id = map['id'] as String,
        nickname = map['nickname'] as String,
        createdAt = map['createdAt'] != null
            ? DateTime.parse(map['createdAt'] as String)
            : null,
        pushOption = null,
        fcmToken = null,
        height =
            map['height'] != null ? (map['height'] as num).toDouble() : null,
        weight =
            map['weight'] != null ? (map['weight'] as num).toDouble() : null;

  /// Spring 응답으로 만든 Profile(fcm_token·push_option은 항상 null)에
  /// 이전 프로필의 fcm_token·push_option 값을 그대로 이어붙인다.
  ///
  /// `copyWith`는 null을 "값 유지"로 해석해 height·weight가 실제로
  /// null로 바뀐 경우를 반영하지 못하므로 이 병합에는 사용하지 않는다.
  Profile withPreservedNotificationFields(Profile? previous) {
    return Profile(
      id: id,
      nickname: nickname,
      createdAt: createdAt,
      height: height,
      weight: weight,
      pushOption: previous?.pushOption,
      fcmToken: previous?.fcmToken,
    );
  }

  Profile copyWith({
    String? id,
    String? nickname,
    DateTime? createdAt,
    bool? pushOption,
    String? fcmToken,
    double? height,
    double? weight,
  }) {
    return Profile(
      id: id ?? this.id,
      nickname: nickname ?? this.nickname,
      createdAt: createdAt ?? this.createdAt,
      pushOption: pushOption ?? this.pushOption,
      fcmToken: fcmToken == "" ? null : fcmToken ?? this.fcmToken,
      height: height ?? this.height,
      weight: weight ?? this.weight,
    );
  }
}
