# 차담 (Chadam) — Pleos Connect 차량용 앱

**말 한 줄이면, 차가 알려 준 위치와 배터리로 집에서 집까지의 여행 시각표를 만들고 차량 내비로 넘겨 주는 여행 동선 생성형 AI.**

현대자동차그룹 차량용 앱 개발 공모전 (Code your App into Pleos App Market) 출품작입니다.

## 이 저장소에 있는 것

차량 앱 (Android Automotive OS, Kotlin) 입니다. 차담 화면 (웹앱) 을 띄우고, 그 화면이 차의 기능을 쓸 수 있도록
Pleos Playground SDK 를 부르는 다리 (`window.pleos`) 를 화면에 놓습니다.
일정 계산 엔진 · 서버 · 웹앱 소스는 이 저장소에 없습니다 (공지에 따라 자체 서버 소스는 넣지 않았습니다). 앱은 운영 중인 차담 웹앱을 불러옵니다.

| 화면이 부르는 것 | 차량 앱이 쓰는 Pleos SDK |
|---|---|
| `car()` — 배터리 (% · Wh), 주행가능거리, 충전 중 여부, 기어 | Vehicle SDK 2.3.2 |
| `location()` — 지금 위치 (출발지) | FusedLocation SDK 1.1.3 (안 되면 NaviHelper 의 현재 위치) |
| `watchStops(stops, radius)` — 경유지 근접 알림 (들어옴 · 나감 → 경유지 안내판) | FusedLocation SDK 1.1.3 `addProximityAlert` |
| `navigate(stops)` — 차량 내비로 경로 요청 + 경유지 추가 | NaviHelper SDK 3.0.5 |
| `speak(text)` — 차량의 목소리로 읽기 | TextToSpeech SDK 3.3.1 (기기 안, ko-KR) |
| `listen()` — 차량의 음성 인식으로 말 받기 | SpeechToText SDK 3.3.1 (기기 안, ko-KR — 차량 마이크 음성을 외부 서비스로 보내지 않음) |

- `app/src/main/java/.../MainActivity.kt` — 화면 (WebView) 과 다리 심기
- `app/src/main/java/.../PleosNative.kt` — Pleos SDK 호출
- `app/src/main/assets/pleos-bridge.js` — 화면 쪽 다리 (동기 호출 → Promise)

## APK

- `apk/Witty_Chadam_release.apk` (release, 실행 가능한 서명 포함)

## 실행 방법

1. Pleos Connect 에뮬레이터를 띄웁니다 (아래 테스트 환경).
2. 설치하고 실행합니다.
   ```bash
   adb install -r apk/Witty_Chadam_release.apk
   adb shell am start -n com.journeymanager.pleos/.MainActivity --windowingMode 1
   ```
3. 처음 한 번은 차량 내비게이션을 열어 둡니다 (내비가 준비되기 전에는 위치 · 경로 요청이 응답하지 않음).
   ```bash
   adb shell monkey -p ai.umos.maps.android.navigation.app -c android.intent.category.LAUNCHER 1
   ```
4. 에뮬레이터의 위치를 한국으로 옮깁니다 (기본 위치는 미국): `adb emu geo fix 127.1112 37.3948` (판교).
5. 앱에서 "가평 1박 2일 캠핑" 처럼 말하거나 적으면 일정이 나오고, [차량 내비로 안내] 를 누르면 차량 내비가 경유지를 포함한 경로 안내를 시작합니다.

## 테스트 환경 및 필요한 설정

- 에뮬레이터: **Pleos Connect v2.1.0** (AVD 이미지 crp-arm64, `-gpu host` 로 실행)
- 화면 규격: 앱이 뜨는 App Screen 영역 **840 × 1203 px** (넓은 앱 영역에서는 1682 × 1210 px)
- 인터넷 연결 필요 (차담 웹앱과 일정 계산 서버)
- 권한: 위치 (`pleos.car.permission.FUSED_LOCATION`), 음성 출력 (`TTS_SERVICE`), 음성 인식 (`STT_SERVICE` · `RECORD_AUDIO`) — 처음 실행할 때 묻습니다
- 배터리 값을 바꿔 보려면 에뮬레이터의 Virtual Console (`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088/) 에서
  `POST /vehicle/ev-battery?field=soc&value=45` 처럼 넣습니다. 배터리 45% 에서는 일정 안에 충전이 한 번 들어갑니다.

## 직접 빌드

JDK 17 이상, Android SDK (platform 34, build-tools 34), Pleos SDK 저장소 (`https://nexus-playground.pleos.ai/repository/maven-releases/`) 에 접속할 수 있어야 합니다.

```bash
./gradlew :app:assembleRelease
```

- `-PcarMaker=현대 -PcarModel="아이오닉 6"` — 차 모델 이름 (SDK 가 모델을 알려 주지 않아 빌드 값으로 둠, 기본값 = 에뮬레이터의 차)
- release 는 이 저장소에 서명 키가 없어 빌드하는 기기의 디버그 키로 서명됩니다.
