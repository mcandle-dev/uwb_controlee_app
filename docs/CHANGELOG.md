# CHANGELOG — UWB Controlee 테스트 앱

> 날짜별 변경 이력. 새 작업을 커밋할 때마다 **맨 위에** 항목을 추가한다.
> 상세 요구사항·검증 절차는 `앱_기능_화면_요구사항정의서.md`, `TODO.md`,
> `5단계_보드_테스트_가이드.md` 참고.

## 2026-07-25

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
