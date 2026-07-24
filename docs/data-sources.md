# 버스 데이터 출처와 연동 상태

> 확인일: 2026-07-24

## 1차 공식 공급자: 국토교통부 TAGO

UNIST 1단계의 우선 공급자는 공공데이터포털의
[국토교통부 (TAGO) 버스도착정보](https://www.data.go.kr/data/15098530/openapi.do)입니다.

- REST, JSON/XML
- 실시간 업데이트
- 개발·운영 단계 자동승인
- 무료
- 개발계정 신청 가능 트래픽 10,000건
- 이용허락범위 제한 없음
- 동시 호출은 최대 10개로 제한

정류소별 도착 정보 요청은 아래 공식 endpoint를 사용합니다.

```text
GET https://apis.data.go.kr/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList
```

필수값은 공공데이터포털 `serviceKey`, TAGO `cityCode`, TAGO `nodeId`입니다.
도착 응답의 핵심 필드는 노선 ID·번호와 도착 예상시간(초)입니다.

정류소 번호 `40234`, `40233`, `40235`는 사용자에게 보이는 울산 지역 번호이며
TAGO `nodeId`와 같다고 가정하지 않습니다. 공식
[TAGO 버스정류소정보](https://www.data.go.kr/data/15098534/openapi.do)로 세 ID를
조회·대조해 아래 환경변수에 매핑합니다.

[TAGO 버스노선정보](https://www.data.go.kr/data/15098529/openapi.do)는
매핑된 정류소를 지나는 노선 ID와 노선번호를 대조하는 데 사용합니다.

```dotenv
TAGO_CITY_CODE=26
TAGO_NODE_40234=USB196040234
TAGO_NODE_40233=USB196040233
TAGO_NODE_40235=USB999000145
```

2026-07-24에 울산광역시 공식 정류소 CSV의 명칭·서비스 ID·좌표와 최신 TAGO
노선 데이터를 교차 대조했습니다. 위 세 `nodeId`를 사용한 TAGO 도착 API도 모두
`resultCode=00`, `NORMAL SERVICE`를 반환했습니다. 같은 날 운행 시간 중 실제
백엔드 스모크 테스트에서는 `40234`에서 513번, `40235`에서
713·743·753·1115번 도착 행과 절대 ETA가 정상적으로 정규화됐습니다.
`40233`은 확인 시점에 도착 예정 차량이 없어 정상적인 빈 배열을 반환했습니다.

## 검증·보조 공급자: 울산광역시 BIS

[울산광역시 BIS 정보](https://www.data.go.kr/data/15052669/openapi.do)는
버스 도착, 정류장, 노선별 정류장, 노선, 시간표 정보를 제공합니다.
TAGO의 세 정류장·다섯 노선 매핑을 검증하거나 TAGO 장애 시 공급자를 다변화할 때
사용합니다.

- XML 응답
- 인증키와 활용신청 필요
- 무료, 자동승인
- 개발계정 신청 가능 트래픽 1,000건
- 공식 명세의 호출 주소가 HTTP이므로 모바일 앱이 아니라 백엔드에서만 호출

[울산광역시 버스 정류소 위치 정보](https://www.data.go.kr/data/15052671/fileData.do)의
서비스 ID, 정류장명, 좌표도 최초 매핑 검증에 함께 사용합니다.

## 보안 원칙

- `TAGO_SERVICE_KEY`는 백엔드 환경변수에만 둡니다.
- 모바일 앱, Git, fixture, 로그에는 키를 넣지 않습니다.
- 외부 요청 예외에 전체 URI를 포함하지 않아 키가 로그로 유출되지 않게 합니다.
- 앱은 TAGO를 직접 호출하지 않고 자체 `/api/v1` API만 사용합니다.

## 현재 구현 상태

- `fixture`: 키 없이 3개 정류장과 5개 노선을 재현하며 기본 모드입니다.
- `tago`: 공식 JSON 응답을 내부 절대 ETA 모델로 정규화합니다.
- 성공 응답은 정류장별로 30초간 재사용하고 같은 정류장의 동시 요청은 하나의
  TAGO 호출로 병합합니다.
- TAGO 연결 timeout은 3초, 응답 timeout은 8초입니다.
- provider 실패 시 10분 이내의 마지막 백엔드 snapshot을 `STALE`로 반환합니다.
- 인증키, 울산 도시코드, 세 TAGO 정류소 ID를 설정한 실제 호출을 확인했습니다.

```bash
export UNIST_BUS_ARRIVALS_MODE=tago
export TAGO_SERVICE_KEY='공공데이터포털에서 받은 디코딩 인증키'
export TAGO_CITY_CODE='26'
export TAGO_NODE_40234='USB196040234'
export TAGO_NODE_40233='USB196040233'
export TAGO_NODE_40235='USB999000145'
```

인증 정보가 없거나 매핑이 비어 있으면 TAGO 모드는 외부 호출을 시도하지 않고
명시적인 `503`을 반환합니다.
