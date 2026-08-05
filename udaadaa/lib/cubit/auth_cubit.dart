import 'dart:convert';

import 'package:bloc/bloc.dart';
import 'package:crypto/crypto.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_web_auth_2/flutter_web_auth_2.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:udaadaa/data/member_api_client.dart';
import 'package:udaadaa/models/profile.dart';
import 'package:udaadaa/utils/constant.dart';

part 'auth_state.dart';

class AuthCubit extends Cubit<AuthState> {
  Profile? _profile;
  bool _isChallenger = false;
  bool _wasChallenger = false;
  bool _isAuthenticating = false;

  AuthCubit() : super(AuthInitial()) {
    final currentUser = supabase.auth.currentUser;
    if (currentUser != null) {
      memberApiClient.getMe().then((res) {
        final profile = Profile.fromSpringMap(map: res)
            .withPreservedNotificationFields(_profile);
        _profile = profile;
        emit(Authenticated(profile));
        FirebaseMessaging.instance.onTokenRefresh.listen((token) {
          _updateFCMToken(token, profile);
        });
      }).onError((error, stackTrace) {
        logger.e(error.toString());
        emit(AuthError());
        _anonymousLogin();
      });
    } else {
      _anonymousLogin();
    }
    supabase.auth.onAuthStateChange.listen((data) async {
      if (data.event == AuthChangeEvent.signedIn) {
        try {
          final provider = data.session?.user.appMetadata['provider'];
          if (provider == 'kakao' || provider == 'apple') {
            try {
              final existing = await memberApiClient.getMeOrNull();

              if (existing != null) {
                _profile = Profile.fromSpringMap(map: existing)
                    .withPreservedNotificationFields(_profile);
                emit(Authenticated(_profile!));
              } else {
                makeProfile();
              }
            } catch (e) {
              emit(AuthError());
              logger.e('Error getting profile: ${e.toString()}');
            }
          } else {
            makeProfile();
          }
        } catch (e) {
          logger.e('Error logging sign-in details: ${e.toString()}');
        }
      } else if (data.event == AuthChangeEvent.signedOut) {
        emit(AuthInitial());
      }
    }, onError: (error) {
      logger.e(error.toString());
      emit(AuthError());
    });
  }

  Future<void> _anonymousLogin() async {
    try {
      final response = await supabase.auth.signInAnonymously();
      if (response.user == null) {
        throw Exception('Failed to sign in');
      }

      // 닉네임 생성·중복 처리는 Spring이 담당한다 (멱등 초기화 API).
      final res = await memberApiClient.initialize();
      final profile = Profile.fromSpringMap(map: res)
          .withPreservedNotificationFields(_profile);
      _profile = profile;
      emit(Authenticated(profile));
      FirebaseMessaging.instance.onTokenRefresh.listen((token) {
        _updateFCMToken(token, profile);
      });
    } catch (e) {
      logger.e(e.toString());
      emit(AuthError());
    }
  }

  Future<void> setFCMToken() async {
    if (_profile == null) {
      final currentUser = supabase.auth.currentUser;
      if (currentUser == null) {
        throw Exception('User is not authenticated');
      }
      final res = await supabase
          .from('profiles')
          .select()
          .eq('id', currentUser.id)
          .single();
      Profile profile = Profile.fromMap(map: res);
      _profile = profile;
    }
    await _setFCMToken(_profile!);
  }

  Future<void> _setFCMToken(Profile profile) async {
    try {
      final currentUser = supabase.auth.currentUser;
      if (currentUser == null) {
        throw Exception('User is not authenticated');
      }

      await FirebaseMessaging.instance.requestPermission();

      await FirebaseMessaging.instance.getAPNSToken();
      final fcmToken = await FirebaseMessaging.instance.getToken();
      if (fcmToken == null) {
        throw Exception('Failed to get FCM token');
      }
      _updateFCMToken(fcmToken, profile);
    } catch (e) {
      logger.e(e.toString());
    }
  }

  Future<void> _updateFCMToken(String token, Profile profile) async {
    try {
      profile = profile.copyWith(fcmToken: token);
      _profile = profile;
      await supabase.from('profiles').upsert(profile.toMap());
    } catch (e) {
      logger.e(e.toString());
    }
  }

  Future<void> updateNickname(String nickname) async {
    try {
      final res = await memberApiClient.updateMe(nickname: nickname);
      final profile = Profile.fromSpringMap(map: res)
          .withPreservedNotificationFields(_profile);
      _profile = profile;
      emit(Authenticated(profile));
    } catch (e) {
      logger.e(e.toString());
    }
  }

  Future<void> turnOffPush(Profile profile) async {
    try {
      profile = profile.copyWith(pushOption: false);
      _profile = profile;
      await supabase.from('profiles').upsert(profile.toMap());
    } catch (e) {
      logger.e(e.toString());
    }
  }

  Future<void> togglePush() async {
    try {
      final currentUser = supabase.auth.currentUser;
      if (currentUser == null) {
        throw Exception('User is not authenticated');
      }
      if (_profile == null) {
        final res = await supabase
            .from('profiles')
            .select()
            .eq('id', currentUser.id)
            .single();
        Profile profile = Profile.fromMap(map: res);
        _profile = profile;
      }
      if (_profile?.pushOption == false) {
        await _setFCMToken(_profile!.copyWith(pushOption: true));
      } else {
        await turnOffPush(_profile!);
      }
      emit(Authenticated(_profile!));
    } catch (e) {
      logger.e(e.toString());
    }
  }

  void setIsChallenger(bool newValue) {
    _isChallenger = newValue;
  }

  void setWasChallenger(bool newValue) {
    _wasChallenger = newValue;
  }

  // Future<int> linkEmail(String email, String password) async {
  //   try {
  //     await supabase.auth
  //         .updateUser(UserAttributes(email: email, password: password));
  //     return 1;
  //   } catch (e) {
  //     logger.e(e.toString());
  //     if (e is AuthException) {
  //       if (e.code == 'weak_password' && e.statusCode == '422') {
  //         return 4;
  //       } else if (e.code == 'email_exists' && e.statusCode == '422') {
  //         return 5;
  //       } else if (e.code == 'validation_failed' && e.statusCode == '400') {
  //         return 6;
  //       }
  //       return 0;
  //     }
  //     return 0;
  //   }
  // }

  Future<int> signInWithEmail(String email, String password) async {
    try {
      final response = await supabase.auth
          .signInWithPassword(email: email, password: password);
      if (response.user == null) {
        emit(AuthError());
        return 0;
      }
      final currentUser = supabase.auth.currentUser;
      logger.d("currentUser: $currentUser");
      if (_profile == null || _profile!.id != currentUser!.id) {
        final res = await memberApiClient.getMeOrNull();
        if (res != null) {
          _profile = Profile.fromSpringMap(map: res)
              .withPreservedNotificationFields(_profile);
        }
      }
      emit(Authenticated(_profile!));
      return 3;
    } catch (e) {
      logger.e(e.toString());
      if (e is AuthException) {
        if (e.code == 'validation_failed' && e.statusCode == '400') {
          emit(AuthError());
          return 7;
        } else if (e.code == 'invalid_credentials' && e.statusCode == '400') {
          emit(AuthError());
          return 8;
        }
        return 2;
      }
      return 2;
    }
  }

  Future<AuthResponse> signInWithApple() async {
    _isAuthenticating = true;
    final rawNonce = supabase.auth.generateRawNonce();
    final hashedNonce = sha256.convert(utf8.encode(rawNonce)).toString();

    final credential = await SignInWithApple.getAppleIDCredential(
      scopes: [
        AppleIDAuthorizationScopes.email,
        AppleIDAuthorizationScopes.fullName,
      ],
      nonce: hashedNonce,
    );

    final idToken = credential.identityToken;
    if (idToken == null) {
      throw const AuthException(
          'Could not find ID Token from generated credential');
    }
    return supabase.auth.signInWithIdToken(
      provider: OAuthProvider.apple,
      idToken: idToken,
      nonce: rawNonce,
    );
  }

  // Future<bool> signInWithAppleAndroid() async {
  //   return supabase.auth.signInWithOAuth(
  //     OAuthProvider.apple,
  //     redirectTo: redirectUrl,
  //     authScreenLaunchMode:
  //         kIsWeb ? LaunchMode.platformDefault : LaunchMode.externalApplication,
  //   );
  // }

  Future<void> signInWithKakao() async {
    _isAuthenticating = true;
    try {
      await supabase.auth.signInWithOAuth(
        OAuthProvider.kakao,
        redirectTo: redirectUrl,
        authScreenLaunchMode: kIsWeb
            ? LaunchMode.platformDefault
            : LaunchMode.externalApplication,
      );
    } catch (e) {
      logger.e(e.toString());
    }
  }

  Future<void> signInWithKakaoByWebView() async {
    _isAuthenticating = true;
    final url =
        '$supabaseUrl/auth/v1/authorize?provider=kakao&redirect_to=com.thatyearus.diet-challenge://oauth';

    try {
      final result = await FlutterWebAuth2.authenticate(
        url: url,
        callbackUrlScheme: 'com.thatyearus.diet-challenge',
      );

      // 1. URI fragment에서 토큰 추출
      final uri = Uri.parse(result);
      final params = Uri.splitQueryString(uri.fragment);
      final accessToken = params['access_token'];
      final refreshToken = params['refresh_token'];

      if (accessToken == null || refreshToken == null) {
        emit(AuthError());
        logger.e('토큰 추출 실패');
        return;
      }

      // 2. 세션 수동 설정
      final session = await supabase.auth.setSession(refreshToken);

      // 3. 사용자 정보 추출 및 프로필 연동
      final provider = session.user?.appMetadata['provider'];
      final userId = session.user?.id;

      if (provider == 'kakao' || provider == 'apple') {
        try {
          final existing = await memberApiClient.getMeOrNull();

          if (existing != null) {
            _profile = Profile.fromSpringMap(map: existing)
                .withPreservedNotificationFields(_profile);
            emit(Authenticated(_profile!));
          } else {
            await makeProfile(); // 👈 생성 시 유저 정보 넘겨줌
          }
          emit(AuthKakaoLoginSuccess(_profile!));
        } catch (e) {
          emit(AuthError());
          logger.e('프로필 조회 실패: $e');
        }
      } else {
        await makeProfile();
      }
    } catch (e) {
      emit(AuthError());
      logger.e('로그인 실패: $e');
    }
  }

  Future<void> makeProfile() async {
    if (_profile?.id == supabase.auth.currentUser!.id) {
      return;
    }
    try {
      // 닉네임 생성·중복 처리는 Spring이 담당한다 (멱등 초기화 API).
      final res = await memberApiClient.initialize();
      final profile = Profile.fromSpringMap(map: res)
          .withPreservedNotificationFields(_profile);
      _profile = profile;
      emit(Authenticated(profile));
      FirebaseMessaging.instance.onTokenRefresh.listen((token) {
        _updateFCMToken(token, profile);
      });
    } catch (error) {
      logger.e('Failed to initialize member profile: ${error.toString()}');
      emit(AuthError());
    }
  }

  Future<void> signOut() async {
    await supabase.auth.signOut();
    emit(AuthInitial());
  }

  String getChallengeStatus() {
    try {
      // Check challenge status and return appropriate string
      if (_isChallenger) {
        return 'isChallenger';
      } else if (!_isChallenger && _wasChallenger) {
        return 'wasChallenger';
      } else {
        return 'noChallenger';
      }
    } catch (e) {
      // Log error for debugging purposes
      logger.e('Error determining challenge status: ${e.toString()}');
      return 'noChallenger'; // Default fallback value
    }
  }

  void refreshProfile() {
    if (_profile != null) {
      emit(Authenticated(_profile!));
    }
  }

  Future<void> updateProfile(String height, String weight) async {
    final parsedHeight = double.tryParse(height);
    final parsedWeight = double.tryParse(weight);

    try {
      final res = await memberApiClient.updateMe(
        height: parsedHeight,
        weight: parsedWeight,
      );
      _profile = Profile.fromSpringMap(map: res)
          .withPreservedNotificationFields(_profile);
    } catch (e) {
      // 예: 서버 검증 범위(50~250cm, 20~500kg)를 벗어나면 여기서 실패한다.
      logger.e('Error updating profile via Spring: ${e.toString()}');
    }
    emit(Authenticated(_profile!));
  }

  Future<void> withdrawAccount() async {
    try {
      final currentUser = supabase.auth.currentUser;
      if (currentUser == null) {
        throw Exception('User is not authenticated');
      }

      // 1. Delete user's profile from profiles table
      await supabase.from('profiles').delete().eq('id', currentUser.id);

      // 2. Delete user's auth account using Edge Function
      final response = await supabase.functions
          .invoke('delete-auth-user', body: {'userId': currentUser.id});

      if (response.status != 200) {
        throw Exception('Failed to delete user account');
      }

      // 3. Sign out and reset state
      await signOut();
      emit(AuthInitial());
    } catch (e) {
      logger.e('Error during account withdrawal: ${e.toString()}');
      throw Exception('Failed to withdraw account');
    }
  }

  Profile? get getProfile {
    if (state is Authenticated) {
      return (state as Authenticated).user;
    }
    if (state is AuthKakaoLoginSuccess) {
      return (state as AuthKakaoLoginSuccess).user;
    }
    return null;
  }

  Profile? get getCurProfile => _profile;

  bool? get getPushOption {
    logger.d("${getCurProfile?.fcmToken}");
    logger.d("getPushOption: ${getCurProfile?.fcmToken != null}");
    return getCurProfile?.pushOption == true && getCurProfile?.fcmToken != null;
  }

  bool get getIsChallenger => _isChallenger;
  bool get wasChallenger => _wasChallenger;

  set setIsAuthenticating(bool value) {
    _isAuthenticating = value;
  }

  bool get getIsAuthenticating => _isAuthenticating;
}
