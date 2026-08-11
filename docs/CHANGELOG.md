# CHANGELOG — UWB Controlee 테스트 앱

> 날짜별 변경 이력. 새 작업을 커밋할 때마다 **맨 위에** 항목을 추가한다.
> 상세 요구사항·검증 절차는 `앱_기능_화면_요구사항정의서.md`, `TODO.md`,
> `5단계_보드_테스트_가이드.md` 참고.

## 2026-08-11

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
