# UNIST Bus

UNIST 구성원을 위한 버스 도착 정보 앱입니다. 제품은 UNIST 전용 서비스를 먼저 완성한 뒤 울산 전역, 국내 전체 순서로 확장합니다. 현재 디자인은 v0.1로 동결하고 실제 데이터와 갱신 신뢰성 구현에 집중합니다.

## 현재 구현 범위

- iOS 17+ SwiftUI 도착 목록
- 핵심 정류장 `40234`, `40233(기점)`, `40235(종점)`을 동시에 조회해 한 목록으로 통합
- UNIST 교내 운행 5개 노선 `513`, `713`, `743`, `753`, `1115` 전체 표시
- 절대 ETA, 관측 시각, `staleAt`, 운행 상태 모델
- Spring Boot 4.1/Kotlin/Java 21 도착 정보 API
- 정류장별 30초 fresh cache와 동시 요청 single-flight로 TAGO 호출량 보호
- provider 장애 시 최근 stale 캐시를 반환하는 백엔드 정책
- TAGO 연결 3초·응답 8초 timeout
- 공식 TAGO 응답 정규화 adapter와 환경변수 기반 인증 설정
- iOS Debug 기본 로컬 API 연결 및 `-fixture-arrivals` 개발 fallback
- 정류장별 부분 성공, 30초 전경 갱신, 앱 재실행용 로컬 캐시
- 로컬 Live Activity 시작, 갱신, 종료
- Lock Screen 및 Dynamic Island의 `남은 시간: N분 N초` 시스템 카운트다운
- OpenAPI 계약과 백엔드·iOS 단위/통합 테스트

현재 다섯 노선 fixture는 [UNIST 공식 오시는 길의 교내 운행 노선 안내](https://www.unist.ac.kr/unist/introduction/location.do)를 기준으로 합니다. 실제 TAGO 호출에는 공공데이터포털 인증키와 TAGO 정류소 ID 매핑이 필요하며, 준비 전에는 백엔드가 결정적 fixture provider로 실행됩니다.

앱과 확장의 bundle identifier는 각각 `com.example.UNISTBus`, `com.example.UNISTBus.LiveActivity`인 임시값입니다. APNs나 배포 설정 전에 최종 식별자로 바꿔야 합니다.

## 백엔드 실행

Java 21은 전역 Gradle 대신 프로젝트 Wrapper와 함께 사용합니다.

```bash
set -a
source .env
set +a
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
cd backend
./gradlew test
./gradlew bootRun
```

다른 터미널에서 API를 확인합니다.

```bash
curl http://127.0.0.1:8080/api/v1/stops/40234/arrivals
curl http://127.0.0.1:8080/api/v1/stops/40233/arrivals
curl http://127.0.0.1:8080/api/v1/stops/40235/arrivals
```

실제 TAGO provider 설정과 확인된 제약은 [데이터 출처 문서](docs/data-sources.md)에 정리되어 있습니다. 비밀값은 `.env.example`의 이름만 참고해 로컬 환경변수로 주입하고 저장소에는 넣지 않습니다.

Cloud Run 배포용 실행 이미지는 `backend/Dockerfile`에서 Java 21로 빌드하며,
런타임은 non-root 사용자와 512MiB 환경에 맞춘 JVM 메모리 제한을 사용합니다.
운영에서는 `TAGO_SERVICE_KEY`를 저장소나 일반 설정값이 아니라 Secret Manager로
주입합니다.

운영 HTTPS API:

```text
https://unist-bus-api-60408467370.asia-northeast3.run.app
```

Cloud Run은 서울 `asia-northeast3` 리전, 최소 인스턴스 `0`, 서비스 전체 최대
인스턴스 `1`로 운영합니다. iOS Release와 Android 빌드는 이 주소를 사용하며,
iOS Debug는 로컬 개발을 위해 계속 `http://127.0.0.1:8080`을 사용합니다.

## Android 친구 테스트

실시간 HTTPS API에 연결하는 Android 8.0(API 26) 이상용 debug APK는 다음 파일입니다.

```text
build/UNISTBus-0.2.0-live-debug.apk
```

이 APK에는 TAGO 인증키가 포함되지 않으며, 앱은 위 Cloud Run API만 호출합니다.
친구 휴대폰 설치와 확인 순서는
[Android 친구 테스트 안내](docs/android-test-install.md)를 참고합니다.

## iOS 프로젝트 생성과 빌드

```bash
cd ios
xcodegen generate
xcodebuild \
  -project UNISTBus.xcodeproj \
  -scheme UNISTBus \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

Debug 앱은 기본적으로 `http://127.0.0.1:8080`의 로컬 백엔드를 사용합니다. 백엔드 없이 기존 fixture만 확인하려면 Xcode Scheme의 launch arguments에 `-fixture-arrivals`를 추가합니다.

테스트와 Live Activity 화면 검증에는 iOS Simulator가 필요합니다. Xcode에서 `ios/UNISTBus.xcodeproj`를 연 뒤 Dynamic Island를 지원하는 iPhone Simulator에서 `UNISTBus` scheme을 실행합니다.

Debug 빌드에는 추적 중인 fixture의 ETA 지연, stale 전환, 도착 처리를 재현하는 테스트 컨트롤이 표시됩니다.

Simulator를 Xcode에서 먼저 부팅한 뒤 테스트를 실행할 수 있습니다.

```bash
cd ios
xcodebuild \
  -project UNISTBus.xcodeproj \
  -scheme UNISTBus \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' \
  test
```

수동 확인 순서는 [docs/ios-manual-test.md](docs/ios-manual-test.md)에 정리되어 있습니다.
