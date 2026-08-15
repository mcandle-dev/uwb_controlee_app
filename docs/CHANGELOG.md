# CHANGELOG — UWB Controlee 테스트 앱

> 날짜별 변경 이력. 새 작업을 커밋할 때마다 **맨 위에** 항목을 추가한다.
> 상세 요구사항·검증 절차는 `앱_기능_화면_요구사항정의서.md`, `TODO.md`,
> `guide/5단계_보드_테스트_가이드.md` 참고.

## 2026-08-16

### T304 판정: 콜드 웨이크 성립 (조건 = 배터리 최적화 제외) + 모드 4 keepOob 결함 수정

- **T304 실측 (사용자, 2회)**: 기본 상태에선 웨이크 성공·FGS 거부(폴백 알림 정상),
  **배터리 최적화 "제한 없음" 설정 후 콜드 웨이크 완전 성립** (FGS 자동 기동 + 자동 레인징)
- **constitution 개정 (코드보다 먼저)**: P8 예외(성립 조건 = 배터리 최적화 제외) +
  P7 예외(모드 4 는 자동 실패라도 채널·FGS 를 닫고 휴면 복귀 — 재교환은 웨이크가 대체)
- **keepOob 결함 수정** — 사용자 보고 "콘솔 해제 후에도 폰이 연결된 상태": 자동 실패 시
  채널 유지 탓에 콘솔 재광고에 조용히 재연결돼 폰 세션 없이 콘솔만 트리거되는 반쪽 상태
  → `endSession` 에서 CENTRAL 은 항상 전체 정리
- 토글 ON 시 배터리 최적화 허용 대화상자(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 권한 추가)
  + 미설정 경고 로그 + UI 문구. 시나리오 가이드 §8-0(최종 판정)·§6 분기표 갱신
- 알려진 특성: 콘솔이 광고를 켜둔 동안 폰 세션 실패 시 휴면→웨이크→재시도 루프 (스로틀 10초,
  의도된 자동 재연결)

## 2026-08-15

### 콜드 웨이크 구현 — spec 002 Phase 3 (maker-ready, feature/002-cold-wake)

- 콘솔 착수 신호 수령(`HANDOFF_콘솔_모드4_준비완료.md` — spec 009 maker-ready + nRF 실측).
  기 구현 `OobCentral` 이 콘솔 제약 4건(WoR·30초 Write 창·끊고 재스캔·재발급 재Write) 충족 확인
- **자동 감시 토글 (T301)** — `OobWakeScan`: PendingIntent 스캔 등록(앱이 죽어도 OS 유지,
  UUID HW 필터·LOW_POWER·FLAG_MUTABLE). 모드 4 전용, 모드 이탈 시 자동 OFF, 재부팅 시 재설정 필요
- **웨이크 경로 (T302)** — `OobWakeReceiver`(스로틀 10초) → FGS `ACTION_AUTO_START` →
  `RangingCoordinator.startAutoSession()`: 가용성 → 주소 확보(10초 대기) → 모드 4 Start.
  Activity 없이 백그라운드에서 전체 시퀀스 동작
- **알림 폴백 (T303)** — FGS 기동 거부 시 고우선 알림(탭=앱 열기 → 포그라운드 Start)
- **실기기 미검증 (P9)** — T304 스파이크(백그라운드 FGS 예외 허용 여부)가 최우선 확인 항목,
  불허 시 대기(Arm) 방식 후퇴 (기존 합의)

### 모드 4 (GATT-CLIENT) 폰 구현 — spec 002 Phase 2 (maker-ready)

- **`uwb/OobCentral.kt` 신설** — 사양서 v0.5 §3-1/§5-4/§6: 콘솔 connectable 광고를
  UUID 목록 HW 필터로 스캔 → GATT 연결 → BOARD_INFO(`5F1D0004`) Read →
  PHONE_INFO(`5F1D0005`) Write Without Response. **폰 송출 0건 — iOS 성립 조건 충족 (§10)**
- `OobMode.CENTRAL`("4 · GATT 연결 (iOS)") + coordinator Start 분기(미교환 30초 폴백 §7-16)
  + 주소 재발급 시 재Write(§3-1) + 연결 끊김 시 스캔 복귀(§7-17). 기존 모드 1~3 경로 무변경
- 권한: 모드 4 는 SCAN+CONNECT 만 요청 (ADVERTISE 불요청 — 불필요 권한 금지)
- JVM 테스트 `Mode4ContractTest` 6건 (UUID 스냅샷·payload 양방향). 가이드 §7 확정 계약으로 갱신
- **실기기 미검증 (P9)** — 검수 15~18 은 콘솔 spec 009(GATT-SERVER) 구현과 합동

### 실기기 검수 10~14 전부 통과 보고 — spec 001 Phase 5 실기기 몫 완료

- 검수 11 (모드 1 회귀, 08-13) · 14 (광고 프레임 7B·LE 실측) · 10 (모드 2 E2E — 3초 내
  주소 자동 반영) · 12 (모드 3 E2E — §2-1 병행 송출 4단계 체인) · 13 (짝 불일치 —
  "0대 발견" 안내 종결, 크래시 없음) 모두 사용자 실기기 통과 보고
- 전 항목이 조정자 리팩터 빌드에서 확인됨 → **spec 002 T102(모드 1~3 회귀)도 완료 보고**
  — 002 게이트 G2 충족. 완료 판정은 checker 몫 (P11)
- 남은 마무리: checker 판정 → T003(v1 태그 위치 결정) → main 머지 → 짝 태그
  `v2.0_controlee_scanner` → T404(콘솔 SHA 통보·pin bump)

## 2026-08-12

### spec 002 Phase 1 — 세션 조정자 추출 (RangingCoordinator, 동작 무변경)

- **G1 승인 (사용자)** 에 따라 `MainViewModel` 의 조정 로직 전량(Start 시퀀스 모드 분기·
  워치독·OOB 수명·콘솔 시뮬레이터·모드 영속화)을 `uwb/RangingCoordinator.kt` 로 이식.
  콜드 웨이크(T302)에서 FGS 가 Activity 없이 시퀀스를 돌리기 위한 선행 구조 (plan D4)
- 프로세스 싱글턴(`get`/`peek`) — 현재는 ViewModel 이 수명을 그대로 소유(onCleared →
  `shutdown()`)해 v1 과 동작 동일. `peek()` 는 T302 의 FGS 진입점 예비
- `MainViewModel` 은 UiState 선언 + 위임 메서드로 축소 — `ui/`·`MainActivity` 무변경,
  root 패키지에서 `androidx.core.uwb` import 가 사라져 P1 정합 개선
- **T001 (사양서 v0.5) 진행** — 개정 요청 handoff 사본을 콘솔 리포 작업트리에 배치
  (미커밋 — 콘솔 세션 수령·커밋 예정). 확정 전 모드 4 코드 착수 금지 유지
- 검증: 기존 JVM 테스트 무수정 (P10). **실기기 회귀(T102)는 A 절 검수와 병행 확인 예정 (P9)**

### G0 확정: Android+iOS 모두 지원 — spec 002 를 모드 4 기반으로 전면 개정

- **G0 (사용자 확정)**: 최종 목표는 **Android + iOS 폰 모두 지원, 충돌 시 iOS 기준.**
- **spec 002 (콜드 웨이크) 전면 개정** — 교환 구조를 모드 4(GATT 역방향: 콘솔=peripheral
  광고 21B+GATT 서버, 폰=central Read/Write)로 채택. 이전 Android 전용 설계
  (payload 광고 + PendingIntent)는 iOS 성립 불가로 폐기 (git 이력 보존).
  모드 1~3 은 Android 브링업·회귀 경로로 존치 (plan D6)
- **사양서 v0.5 개정 요청 handoff** (`docs/handoff/HANDOFF_모드4_사양서개정_요청.md`) —
  콘솔 광고(UUID 목록·connectable)·BOARD_INFO(Read)/PHONE_INFO(Write) 특성·iOS 적합성 절
  제안 포함. **확정 전 모드 4 코드 착수 금지 (P5)** — 그동안 가능한 것은 조정자 리팩터뿐
- 가이드 §7 을 "제안"에서 "채택 — 개정 대기"로, FAQ Q12 를 결정 기록으로 갱신.
  TODO C 절 게이트 재구성 (G0 완료, T001 신설)

### BLE OOB 모드 3 (SCANNER 관찰 + 병행 송출) 구현 — spec 001 Phase 3 (maker-ready)

- **T002 확정 (사람, 2026-08-12)** — 사양서 §2-1 = **병행 송출**. 마스터 사양서 개정(v0.4)은
  콘솔 세션에 handoff 로 요청 (`docs/handoff/HANDOFF_T002_병행송출_확정.md`)
- **`uwb/OobScanner.kt` 신설 (모드 3)** — 콘솔 ADV_INFO(`5F1D0003`) Service Data 스캔,
  D6 캐시 필터(`OobScanFilter`) 적용, 실패 무전파 (P6). 수신 → 보드 MAC·Session ID 입력칸
  자동 반영 → `OobBeacon` 병행 송출(§2-1) → UWB 시작. 미수신 30초 = 수동 입력값 폴백 (§7-14)
- **`parseOobPayload`/`OobInfo`** — 빌더 역함수 파서를 `UwbDefaults.kt` 에 추가 (기존 빌더
  무변경), JVM 테스트 `OobPayloadParseTest` 7건
- **`BLUETOOTH_SCAN`(neverForLocation)** Manifest 추가 — 모드 3 선택 시에만 런타임 요청 (D5,
  `bleOobPermissionsFor(mode)`)
- 드롭다운에 SCANNER 노출, OOB 배지 모드별 표기 (스캔중/광고 수신됨 — §6-1 매핑)
- **`specs/002-cold-wake` 신설** — "앱 미실행 상태에서 콘솔 비콘 수신 → 자동 레인징"
  (사용자 확정: 콜드 웨이크 방식). 착수 게이트: 조정자 추출 승인(G1) + 001 실기기 검증(G2)
- 검증: `gradlew test`·`assembleDebug`·`lint` green. **실물 페어 미검증 (P9)** — 검수 10~14 는
  `[needs-device]`
- **`docs/guide/` 신설** — 사람용 가이드(계약 아님) 분리: 보드 테스트 가이드·검수 함수 호출
  맵 이동 + **BLE 3모드 입문 가이드(`ble_dev_guide.md`) 신규** (mermaid 시퀀스 3종, 모드별
  제약, iOS 차이 — 모드 2 는 iOS 성립 불가). 계약 경로(`oob/`·`handoff/` 등)는 이동 금지
- **가이드 §5·§7 신설 + 섹션 번호 복구** — FAQ 삽입으로 깨졌던 순서(1,2,3,4,5,8,6,7)를
  §1~§10 으로 정리하고 읽는 순서 표 추가. **§5 백그라운드·콜드 웨이크**(Android
  PendingIntent 스캔의 오해 3가지·FGS 가 진짜 관문 / iOS State Restoration 차이 /
  **iOS 는 현 광고 포맷으로 콜드 웨이크 불가** — UUID 목록 AD 부재 + 백그라운드 nil 스캔 금지),
  **§7 모드 4 제안**(GATT 역방향: 콘솔=peripheral·폰=central+Write, 발견 전용 광고 21B,
  iOS pending connect, 채택 시 계약 변경 항목) — **미채택·계약 미변경**. FAQ Q9~Q12 추가
- **README 최신화** — v1 OOB 도입 이전 상태였던 내용을 현행화: **잘못된 스코프 금지 목록
  수정**("BLE OOB 자동 교환·백그라운드 레인징" — 둘 다 구현 완료), 소스 구조에 `Oob*.kt`·
  FGS 추가, 화면 구성에 OOB 배지·모드 드롭다운 반영, **BLE OOB 3모드 절 신설**,
  ground truth 우선순위에 constitution·specs 반영, v1/v2 동시 설치·짝 리포
  (`uwb-console-kotlin`)·진행 상태(2026-07-17 E2E 성공, Phase 2 진행 중) 갱신
- **spec 002 방향 재검토 항목 추가** — iOS 지원 시 현행 설계(Android PendingIntent)가
  성립하지 않으므로 모드 4 기반 재설계 검토 필요. `docs/TODO.md` 에 **게이트 G0(iOS 지원
  여부 결정)** 을 G1 앞에 신설 — 코드 0줄인 지금이 방향 전환 비용이 가장 싸다
- **가이드에 §8 FAQ 추가 (iOS 지원·모드 선택)** — 사용자 지적 검토 결과: iOS 제약은
  "가변 Service UUID" 가 아니라 **Service Data/Manufacturer Data 송출 API 부재**가 원인이며,
  제외 대상은 모드 2 뿐 아니라 **모드 3 병행 송출까지** (폰이 payload 를 송출하는 모든 경로).
  크로스 플랫폼 기본 경로 = 모드 1, UUID 인코딩 우회는 사양서 개정 선행이라 비권장.
  ※ CoreBluetooth 문서 기반 — iOS 실기기 미검증 (P9)
- **검수 14 사용자 통과 보고** (모드 2 광고 프레임/31B, nRF Connect)
- **콘솔 광고 시뮬레이터 추가** (검수 12 테스트 보조) — "콘솔시뮬" 버튼으로 이 폰이 가짜
  콘솔(`5F1D0003`, 보드 MAC·SID 입력값)이 됨. 같은 앱 2대로 모드 3 테스트 가능.
  `OobBeacon` UUID 파라미터화(기본값 유지 — 모드 2 무변경), 계약 무변경

## 2026-08-11

### BLE OOB 모드 2 (BEACON 송출) 구현 — spec 001 Phase 1·2 + 모드 선택 UI (maker-ready)

- **`OobMode` enum + 영속화** — SharedPreferences `oob_mode`, 기본 ADVERTISE_GATT(검증된 v1
  경로). 저장값 매핑은 순수 함수로 JVM 테스트 (`OobModeTest`)
- **`uwb/OobBeacon.kt` 신설 (모드 2)** — OOB_INFO 7B 를 Service Data(`5F1D0001`) 광고로 송출.
  GATT 없음, connectable=false, BALANCED. 광고 28B ≤ 31B (사양서 §5-1).
  주소 재발급 시 광고 교체(§7-15 — Notify 대응물), 실패 무전파 (P6). `OobGattServer.kt` 0줄 변경 (D1)
- **Start 시퀀스 모드 분기 (D2)** — 모드 1: 기존 "광고→Read 대기 30s→UWB" 유지 /
  모드 2: 광고 + UWB 즉시 시작 / 모드 3: 미구현 폴백(수동 입력값, T301·§2-1 확정 대기)
- **모드 선택 드롭다운** — 고정 파라미터 줄 옆, 레인징 중 비활성 (규칙 0). SCANNER 항목은 미노출
- **모드 3 준비물 선반영** — `ADV_INFO_UUID`(`5F1D0003`)·`SCAN_WAIT_TIMEOUT_MS` 상수,
  스캔 캐시 필터 순수 함수 `OobScanFilter`(§7-12) + JVM 테스트
- **v2 병행 설치** — `applicationId` → `com.mcandle.uwbcontrolee.v2` (label "UWB Controlee v2",
  versionName 2.0-dev). v1 설치본과 한 폰에 공존 — 페어 테스트용. 코드 패키지·계약 무변경
- 검증: `gradlew test`·`assembleDebug`·`lint` green. **실물 페어 미검증 (P9)** —
  사양서 검수 10~14 는 `[needs-device]` (tasks.md Phase 5)

### SDD 하네스 도입 + Phase 2 착수 (specs/001, 콘솔 세션이 이식)

- **`constitution.md` 제정 (P1~P15)** — CLAUDE.md/AGENTS.md 에 흩어져 있던 불변 원칙
  (계층·바이트 계약·수명 규칙·검증 규율·리포/버전 규칙)을 승격. 충돌 시 constitution 이 이긴다
- **`specs/001-ble-3mode/` 신설 (spec/plan/tasks)** — BLE OOB 3모드
  (ADVERTISE-GATT 현행 / BEACON 송출 / SCANNER 관찰). plan D1: `OobGattServer` 무변경 +
  모드별 클래스 신설, D2: Start 시퀀스 모드별 분기
- **BLE OOB 사양서 v0.3 사본 수령** — 마스터가 콘솔 리포(`uwb-console-kotlin/docs/oob/`)로
  이관됨. 3모드·광고 Service Data 규격·콘솔→폰 UUID `5F1D0003`. payload v1 7B 무변경
- **`docs/handoff/HANDOFF_008_controlee_scanner.md` 수령** — 콘솔 세션의 인수인계
  (계약 요점·구현 힌트·완료 절차·태그 위치 미결)
- CLAUDE.md/AGENTS.md 읽기 순서·SDD 3종 규칙·푸시 전 체크리스트 반영.
  Phase 2 짝 리포가 `radar_test_console`(동결) → `uwb-console-kotlin` 으로 교체됨을 명시

## 2026-07-25

### 재Start 시 OOB 연결 초기화 — 배지 CONNECTED 고착 + 30초 지연 수정 (유령 정리 후속)

- **아래 '유령 연결 정리'로도 증상 지속** — 원인은 유령이 아니라 **진짜 연결**이었음.
  PC 콘솔(`radar_test_console/ble_oob.py`)은 주소 재발급 Notify 구독을 위해 GATT 연결을
  의도적으로 유지한다. FGS 도입 전에는 백그라운드 전환 때마다 폰이 GATT를 닫아 이
  지속 연결이 드러나지 않았음
- **기능 문제**: 재Start 시 폰은 새 OOB_INFO Read를 기다리는데, 이미 연결된 콘솔은 다시
  Read하지 않으므로 30초 타임아웃까지 UWB 시작이 지연됨
- **수정**: 재Start(`open()`이 이미 열려 있을 때) 유지 중인 central을 모두 끊고 광고부터
  재개 — 매 Start가 E2E 검증된 "광고 → 스캔 → 연결 → Read → UWB" 흐름으로 돌게 함.
  실패 직후 새 주소 Notify(keepOob 목적)는 세션 종료 시점에 이미 전달되므로 유지됨
- 재Start 초기화가 이미 정리한 기기의 늦은 해제 콜백이 광고를 이중 시작
  (`ALREADY_STARTED` → UNAVAILABLE)하지 않도록 가드 추가
- **자동 검증**: `.\gradlew.bat test assembleDebug` 성공. 실기기 재검증 필요

### OOB 유령 연결 정리 — 재Start 시 배지 CONNECTED 고착 수정 (증상 지속 — 위 항목으로 대체)

- **증상**: FGS 도입 후 첫 연결은 정상이나, 이후 재Start 시 PC 스캐너 상태와 무관하게
  배지가 곧바로 `연결됨`으로 표시됨
- **원인**: FGS 도입 전에는 앱이 백그라운드로 갈 때마다 세션 종료 → GATT 완전 리셋이라
  매 시도가 깨끗한 상태에서 시작했음. 도입 후 GATT 서버가 시도를 넘어 장수하면서,
  central 해제 콜백을 놓친 경우 `connectedDevices`에 옛 기기가 남아 `open()` no-op 경로에서
  상태가 `CONNECTED`로 고착되고 광고도 재개되지 않음
- **수정**: 재Start 시(`open()`이 이미 열려 있을 때) BLE 스택의 실제 연결 목록
  (`getConnectedDevices(GATT_SERVER)`)과 대조해 유령 연결을 제거하고, 연결이 없으면
  광고 재개 + `ADVERTISING` 복귀
- 참고: PC(Windows) 쪽이 GATT 연결을 실제로 물고 있으면 `연결됨` 표시는 정확한 상태다 —
  이 경우 콘솔 쪽에서 연결을 정리해야 함
- **자동 검증**: `.\gradlew.bat test assembleDebug` 성공. 실기기 재현 시나리오
  (연결 → 종료 → 스캐너 끄고 재Start)로 재검증 필요

### 백그라운드 세션 유지 — Foreground Service 도입 (NFR-3 개정)

- **요구사항 개정**: "앱 백그라운드 진입 시 세션 정지"(구 NFR-3) → **백그라운드에서도
  OOB 광고·레인징 지속**. 요구사항정의서 범위(제외 목록에서 백그라운드 레인징 삭제)와
  NFR-3 개정
- **`RangingForegroundService` 추가**: `connectedDevice` 타입, 로직 없는 프로세스 유지
  전용. UWB 스택이 포그라운드 앱/FGS에만 레인징을 허용하므로 필수 — 없으면 백그라운드
  진입 즉시 세션이 무증상 종료됨. Start 시 시작, 사용자 Stop/onCleared 시 종료.
  자동 실패(`ERROR`/`DISCONNECTED`)에서는 OOB GATT와 함께 FGS도 유지해 백그라운드에서도
  재발급 주소 Notify가 콘솔에 닿게 함 (FGS 수명 = OOB 유지 ∪ 세션 활성)
- **`onAppBackgrounded()`**: 세션 정지 → 로그만 남기고 유지로 변경
- **권한**: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `POST_NOTIFICATIONS`(API 33+, Start 때 BLE 권한과 한 번에 요청 — 연속 다이얼로그는
  앞 요청이 취소되는 문제 회피, 거부 시 알림만 숨겨지고 동작 유지)
- ViewModel 소유 구조는 유지 — 태스크 스와이프 제거 시 세션 종료는 의도된 범위
- 문서: 요구사항정의서·CLAUDE.md·AGENTS.md 동기 갱신
- **자동 검증**: `.\gradlew.bat test assembleDebug` 성공. **화면 꺼짐/앱 전환 중 레인징
  지속 여부는 실기기(+보드) 재검증 필요** — AOSP 기준 동작이며 삼성 펌웨어 확인 전

### CLAUDE.md를 AGENTS.md·최근 커밋 내용과 동기화 (문서만, 코드 변경 없음)

- **Start 시퀀스 반영**: OOB_INFO Read 직후 UWB 시작, BLE 권한 거부/30초 타임아웃 시
  수동 경로 폴백 (`af562e4` 내용)
- **세션 자동 종료 처리 절 신설**: 프레임워크 10초 자동 종료(측정 0건 `ERROR` /
  측정 후 `DISCONNECTED`), WAITING 12초 워치독, 자동 실패 시 OOB GATT 유지·Stop 시만
  종료 (`6713fe3` 내용)
- **상태 최신화**: "보드 없음" 전제 삭제 → 2026-07-17 실물 E2E 성공 기록, 구현 순서
  1~5단계 완료 표기
- Ground Truth 문서 목록에 OOB 사양서·CHANGELOG·TODO·AGENTS.md 추가
  ("계약 변경 시 CLAUDE.md/AGENTS.md 함께 갱신" 규칙 명시), 주요 코드 위치 절 추가,
  함정 목록 보강, 검증 명령을 PowerShell 기준 `test`/`assembleDebug`/`lint`로 갱신

## 2026-07-17

### OOB 실기기 E2E + 레인징 시작 타이밍 안정화 (`6713fe3` + 후속 변경)

- **자동 종료 감지**: 유효 측정 없이 Android UWB 스택이 약 10초 후 세션을 내리는
  경우를 감지해 UI가 `WAITING`에 고착되지 않고 `ERROR`로 전환하도록 수정. 측정 후
  Flow가 끝난 경우는 `DISCONNECTED`로 구분하고 원인 추정 로그를 남김
- **OOB와 UWB 시작 시점 정렬**: Start 직후 UWB를 먼저 열지 않고 BLE 광고·GATT를 연
  뒤 콘솔이 `OOB_INFO` 7B를 Read한 직후 UWB 세션을 시작. PC 스캔·연결 중 폰 세션의
  10초 타임아웃이 먼저 만료되던 문제를 방지
- **수동 경로 보존**: BLE 권한 거부 시 즉시, OOB Read가 30초 안에 없으면 타임아웃 후
  기존 보드 주소 수동 입력값으로 UWB를 시작해 OOB 실패가 레인징 자체를 막지 않게 함
- **재시도 주소 동기화**: 자동 실패(`ERROR`/`DISCONNECTED`)에서는 GATT를 유지하고
  재발급된 폰 주소를 Notify로 전달. 사용자가 Stop하면 연결된 central을 명시적으로
  끊은 뒤 광고와 GATT 서버를 종료
- `AGENTS.md` 추가 — UWB/OOB 바이트 계약, 저장소 경계, 아키텍처·코딩 규칙,
  자동 검증과 실물 보드 검수 원칙 정리
- **자동 검증**: `.\gradlew.bat test assembleDebug` 성공
- **실기기 E2E 성공**: Galaxy controlee의 OOB 주소를 PC 콘솔이 자동 수신한 뒤
  DWM3001CDK와 연결해 레인징이 정상 동작함을 사용자 확인
