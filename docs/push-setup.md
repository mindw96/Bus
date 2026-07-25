# 원격 잠금화면 갱신 설정

앱과 백엔드의 원격 갱신 코드는 자격증명이 없는 로컬 개발 환경에서도
빌드되도록 기본 비활성화되어 있습니다. 운영 환경은 Firestore, Firebase,
Android FCM, Cloud Run, Cloud Scheduler 연결을 완료했습니다. iOS는 Apple
식별자·Sandbox APNs 키·개발용 서명까지 준비했으며, 별도 Sandbox 스킴에서만
원격 Live Activity를 켭니다.

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
| iOS 원격 Live Activity | 일반 Debug·Release `NO`, Sandbox 스킴 `YES` | 실기기 APNs 시험만 명시적으로 활성 |

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
- Apple Team: `4J7HGK26PF`
- Developer portal: 두 App ID 등록 완료, 메인 앱의 Push Notifications 활성
- 앱 target의 `aps-environment` entitlement: Debug·Sandbox `development`,
  Release `production`
- 앱 target의 `CODE_SIGN_ENTITLEMENTS`: `UNISTBus/UNISTBus.entitlements`
- 원격 Live Activity feature flag: Debug·Release `NO`,
  `DebugAPNsSandbox`만 `YES`
- `UNISTBusAPNsSandbox` 스킴: 운영 Cloud Run API + Sandbox APNs 개발 환경
- Live Activity 60초 heartbeat를 위한 frequent updates 지원
- Apple Development 인증서와 두 target의 provisioning profile 생성 완료

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

Sandbox 원격 Live Activity 실기기 빌드는 다음처럼 검증합니다.

```bash
cd ios
xcodegen generate
xcodebuild \
  -project UNISTBus.xcodeproj \
  -scheme UNISTBusAPNsSandbox \
  -configuration DebugAPNsSandbox \
  -destination 'generic/platform=iOS' \
  -allowProvisioningUpdates \
  build
```

Xcode에서 실제 시험할 때는 `UNISTBusAPNsSandbox` 스킴과 연결된 iPhone을
선택하여 실행합니다. 앱에서 실제 도착 정보가 있는 노선의 추적을 시작하고
화면을 잠근 뒤, ETA 변화가 Cloud Run worker를 통해 반영되는지 확인합니다.
일반 `UNISTBus` 스킴과 Release는 실제 기기 왕복 검증이 끝날 때까지 원격
기능을 계속 비활성으로 유지합니다.

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
APNS_ALLOWED_ENVIRONMENT=DEVELOPMENT
```

- 실제 값은 Cloud Run Secret Manager 참조로 주입합니다.
- `APNS_ALLOWED_ENVIRONMENT`는 Apple Developer에서 해당 키에 지정한 환경과
  반드시 같아야 합니다. 기본값은 안전한 Sandbox용 `DEVELOPMENT`이며, 이때
  `PRODUCTION` 구독은 APNs 요청을 보내기 전에 차단됩니다. Production 전용
  키로 교체할 때만 `PRODUCTION`으로 명시적으로 변경합니다.
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
- Apple App ID: `kr.ac.unist.bus`, `kr.ac.unist.bus.LiveActivity` 등록
- 메인 Apple App ID: Push Notifications 활성
- APNs Auth Key: Sandbox, Team Scoped, Secret Manager 버전 참조
- Cloud Run: Firestore 저장, FCM worker, APNs Sandbox gateway 활성
- Cloud Run APNs 가드: `DEVELOPMENT`만 허용, `PRODUCTION` 전송 차단
- Cloud Scheduler: `unist-bus-push-refresh`, 매분, `Asia/Seoul`

남은 외부 검증은 연결된 실제 iPhone에서 `UNISTBusAPNsSandbox` 스킴을 실행하고
Live Activity 토큰 등록부터 Cloud Run worker의 APNs 갱신까지 왕복을 확인하는
작업입니다. TestFlight·App Store 배포 전에는 별도의 Production APNs 키를
준비하고 `APNS_ALLOWED_ENVIRONMENT=PRODUCTION`으로 명시적으로 전환합니다.
