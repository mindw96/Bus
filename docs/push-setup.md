# 원격 잠금화면 갱신 설정

앱과 백엔드의 원격 갱신 코드는 자격증명이 없는 로컬 개발 환경에서도
빌드되도록 기본 비활성화되어 있습니다. 운영 환경은 Firestore, Firebase,
Android FCM, Cloud Run, Cloud Scheduler 연결을 완료했습니다. iOS APNs만
Apple Team과 키가 준비될 때까지 비활성 상태입니다.

## 구현된 데이터 흐름

1. 사용자가 버스 추적을 시작합니다.
2. Android는 Firebase Installation ID(FID), iOS는 해당 Live Activity의 푸시
   토큰을 백엔드에 등록합니다.
3. 백엔드는 추적 구독과 마지막으로 성공 전송한 ETA를 Firestore에 저장합니다.
4. Cloud Scheduler가 worker 엔드포인트를 호출하면 백엔드가 TAGO를 다시
   조회합니다.
5. ETA가 마지막 성공 전송값에서 누적 60초 이상 바뀌거나 상태가 바뀌면
   FCM 또는 APNs 메시지를 전송합니다. iOS에는 정보가 정상임을 유지하도록
   기본 60초 heartbeat도 보냅니다.
6. 도착, 취소, 사용자 종료 또는 만료 시 잠금화면 표시를 종료합니다.

공개 모바일 계약은
`PUT/DELETE /api/v1/tracking-subscriptions/{trackingSessionId}`이며, 정확한
요청·응답 형식은 [`contracts/openapi.yaml`](../contracts/openapi.yaml)을
기준으로 합니다.

## 현재 기본값

| 구성 | 기본값 | 의미 |
|---|---:|---|
| 백엔드 추적 저장소 | `memory` | 로컬·테스트 전용, 재시작 시 삭제 |
| 백엔드 push worker | `false` | worker 엔드포인트와 전송 비활성 |
| Android FCM | Gradle property 없음 | Firebase SDK와 서비스 비활성 |
| iOS 원격 Live Activity | `NO` | 로컬 Live Activity만 사용 |

이 기본값에서는 기존 도착 조회와 로컬 카운트다운이 그대로 동작합니다.

## Android Firebase 준비

Firebase 프로젝트에 다음 application ID를 각각 등록합니다.

- 친구 테스트용 Debug: `kr.ac.unist.bus.debug`
- 이후 Release: `kr.ac.unist.bus`

Debug 앱의 `google-services.json`을 `android/app/google-services.json`에
두되 Git에는 커밋하지 않습니다. 설정이 준비된 빌드는 다음과 같습니다.

```bash
cd android
./gradlew -PunistFirebaseEnabled=true clean testDebugUnitTest assembleDebug
```

일반 `./gradlew assembleDebug`는 계속 Firebase 없이 빌드됩니다. FCM 메시지는
notification payload가 아닌 data-only payload를 사용하며, 앱은 같은 추적
알림 ID를 갱신합니다.

## iOS APNs 준비

저장소 안의 로컬 준비는 다음 상태입니다.

- 앱 bundle identifier: `kr.ac.unist.bus`
- Live Activity extension: `kr.ac.unist.bus.LiveActivity`
- 테스트 bundle: `kr.ac.unist.busTests`
- 앱 target의 `aps-environment` entitlement: Debug `development`, Release
  `production`
- 앱 target의 `CODE_SIGN_ENTITLEMENTS`: `UNISTBus/UNISTBus.entitlements`
- 원격 Live Activity feature flag: Debug·Release 모두 `NO`

따라서 Apple 계정이 없는 환경에서도 아래 unsigned Simulator 빌드는 계속
가능합니다.

```bash
cd ios
xcodegen generate
xcodebuild \
  -project UNISTBus.xcodeproj \
  -scheme UNISTBus \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build-for-testing
```

실제 원격 Live Activity를 켜기 위해 남은 외부 입력과 작업은 다음과 같습니다.

1. Apple Developer Program의 **Team ID**를 준비하고 Xcode signing team으로 선택
2. Developer portal에 `kr.ac.unist.bus` App ID를 등록하고 Push Notifications
   capability 및 provisioning profile 준비
3. ActivityKit 원격 갱신 권한을 포함한 APNs Auth Key(`.p8`)를 발급하고
   **Key ID**와 파일 내용을 안전하게 보관
4. Cloud Run Secret Manager에 Team ID, Key ID, `.p8` 내용을 주입하고
   `APNS_BUNDLE_ID=kr.ac.unist.bus`로 설정
5. 실제 기기에서 APNs 왕복을 검증한 뒤에만
   `UNIST_BUS_REMOTE_LIVE_ACTIVITY_ENABLED=YES`로 전환

원격 기능을 켜면 앱은 `pushType: .token`으로 Live Activity를 시작하고, 해당
Activity의 토큰이 바뀔 때마다 같은 추적 세션을 idempotent하게 갱신합니다.
토큰은 콘솔에 출력하지 않습니다.

## 백엔드 운영 환경변수

변수 이름의 전체 목록은 [`.env.example`](../.env.example)에 있습니다. 운영
최소 구성은 다음과 같습니다.

```text
UNIST_BUS_TRACKING_STORAGE=firestore
UNIST_BUS_TRACKING_ENCRYPTION_KEY=<Base64 32-byte key>
FIREBASE_PROJECT_ID=<Google Cloud project id>
UNIST_BUS_PUSH_ENABLED=true
UNIST_BUS_PUSH_REFRESH_KEY=<long random secret>
UNIST_BUS_FCM_ENABLED=true
```

APNs까지 켤 때 추가합니다.

```text
UNIST_BUS_APNS_ENABLED=true
APNS_KEY_ID=<Apple key id>
APNS_TEAM_ID=<Apple team id>
APNS_BUNDLE_ID=kr.ac.unist.bus
APNS_AUTH_KEY=<the .p8 content>
```

- 실제 값은 Cloud Run Secret Manager 참조로 주입합니다.
- Firebase Admin은 Cloud Run 서비스 계정의 Application Default Credentials를
  사용하며 서비스 계정 JSON 파일을 배포하지 않습니다.
- Firestore에는 푸시 대상을 AES-GCM 암호문으로 저장하고, Android 설치
  식별자는 암호화 키에서 분리 파생한 HMAC-SHA256 값으로만 저장합니다.
- 운영 데이터베이스에는 `expiresAt` 필드의 Firestore TTL 정책을 켜서 종료된
  세션 문서가 장기 누적되지 않게 합니다. TTL 삭제는 즉시성을 보장하지 않으므로
  잠금화면 종료 판정은 worker의 만료 로직이 담당합니다.
- `.p8`, `google-services.json`, 서비스 계정 JSON은 `.gitignore` 대상입니다.

로컬용 32바이트 암호화 키는 다음처럼 만들 수 있습니다.

```bash
openssl rand -base64 32
```

## Worker와 배포 상태

worker는 다음 내부 요청 한 번에 활성 구독을 묶어서 갱신합니다.

```http
POST /internal/v1/push/refresh
X-UNIST-Push-Key: <UNIST_BUS_PUSH_REFRESH_KEY>
```

Cloud Run의 응답 이후 백그라운드 thread나 `@Scheduled`에 의존하지 않습니다.
운영에서는 Cloud Scheduler가 1분 간격으로 worker를 호출하되, 실제 푸시는
60초 ETA 변화나 상태 변화가 있을 때 전송합니다. iOS Live Activity는 안정적인
ETA에서도 잘못 stale로 표시되지 않도록 기본 60초 heartbeat를 사용합니다.
개별 전송 실패는 비밀값 없는 `push_delivery_failed` 경고 로그로 남고 worker가
HTTP 502를 반환하므로 Scheduler에서도 실패로 관찰됩니다.

현재 worker 보호는 Cloud Run에 Secret Manager로 주입한 공유 키와
`X-UNIST-Push-Key` 헤더를 사용합니다. 1단계 운영에는 충분하지만, 서비스가
확대되면 worker를 별도 비공개 Cloud Run 서비스로 분리하고 Scheduler OIDC와
IAM 호출 권한으로 보호하는 구성을 권장합니다.

현재 운영 상태:

- Firestore `(default)`: 서울 `asia-northeast3`, Native mode, TTL
  `tracking-subscriptions.expiresAt` 활성
- Firebase Android 앱: `kr.ac.unist.bus.debug`, `kr.ac.unist.bus`
- Cloud Run: Firestore 저장과 FCM worker 활성, APNs 비활성
- Cloud Scheduler: `unist-bus-push-refresh`, 매분, `Asia/Seoul`

남은 외부 작업은 Apple Developer App ID·capability·provisioning profile과
APNs Team ID·Key ID·`.p8` 연결 및 실제 iPhone 왕복 검증입니다.
