# 002 — 태스크 (tasks) · 전면 개정 2026-08-12 (모드 4 기반)

plan.md (D1~D6) 실행 순서. maker 는 `[maker-ready]` 까지만 (P11).

## Phase 0 — 게이트·계약 (코드 금지 구간)

- [x] G0 `[human]` **iOS 지원 여부 확정** — 2026-08-12 사용자: **Android+iOS 모두 지원,
      충돌 시 iOS 기준.** 이에 따라 모드 4(GATT 역방향) 채택, spec/plan 전면 개정
- [x] T001 `[human/cross-repo]` **사양서 v0.5 개정 — 완료** (콘솔 세션 회신, 사본 배치·커밋
      `a3c0d17`). 확정값: 서비스 `5F1D0003` 승계, BOARD_INFO `5F1D0004`(Read),
      PHONE_INFO `5F1D0005`(**Write Without Response**), 콘솔 광고 = Flags+UUID 목록 21B
      connectable, 모드 3 과 **대체 관계**(동시 운용 안 함). → T2xx 착수 가능
- [x] T002 `[human]` G1 — 조정 로직 `RangingCoordinator` 추출 **승인** (2026-08-12 사용자)
- [ ] T003 `[needs-device]` G2 — spec 001 실기기 검수 10~13 통과 (리팩터 기준선, plan R5)

## Phase 1 — 조정자 리팩터 (계약 무관 — T001 대기 중에도 G1·G2 후 착수 가능)

- [ ] T101 `RangingCoordinator` 추출 — Start 시퀀스·워치독·OOB 수명 이동, ViewModel 은
      위임·구독자로. **동작 무변경** — 기존 JVM 테스트 무수정 green (P10)
- [ ] T102 `[needs-device]` 모드 1~3 수동 흐름 회귀 확인 — **모드 1(08-13)·모드 2·
      모드 3(08-15) 전부 통과 보고** (리팩터 빌드 실기기, 검수 11/10/12 겸함).
      완료 판정은 checker 몫 (P11)

## Phase 2 — 모드 4 (CENTRAL) 폰 구현 ※ T001 확정 후

- [ ] T201 `UwbDefaults.kt` v0.5 상수(콘솔 서비스·BOARD_INFO·PHONE_INFO UUID) +
      `OobMode.CENTRAL` 추가 + JVM 테스트 (기존 storageValue 하위호환 — P10)
- [ ] T202 `uwb/OobCentral.kt` — UUID 필터 스캔 → 연결 → BOARD_INFO Read →
      PHONE_INFO Write, open/close/status + 실패 무전파 (D3/P6). 주소 재발급 시 재Write
- [ ] T203 coordinator Start 분기에 CENTRAL 추가 + `MainScreen` 모드 항목 (기존 경로 diff 없음)
- [ ] T204 payload 재사용 검증 — 기존 빌더/파서 무수정, Read/Write 양방향 JVM 테스트

## Phase 3 — 콜드 웨이크 (Android)

- [ ] T301 `[maker-ready]` 자동 감시 토글 + PendingIntent 스캔 등록/해제 (`setServiceUuid` HW 필터 — D5)
- [ ] T302 `[maker-ready]` `OobWakeReceiver` → FGS `ACTION_AUTO_START` → coordinator 모드 4 자동 시퀀스
- [ ] T303 `[maker-ready]` FGS 기동 실패 폴백 — 고우선 알림, 탭 시 포그라운드 Start (수용 3)
- [ ] T304 `[needs-device]` FGS 백그라운드 시작 예외 스파이크 (불허 시 Arm 후퇴 — R2) +
      배터리 최적화 제외 안내
- [ ] T305 `[needs-device]` 수용 1·2 실물 E2E (콘솔 v0.5 구현과 합동)

## Phase 4 — 검증·문서

- [ ] T401 `gradlew test`+`assembleDebug`+`lint` green / `ui/` bluetooth import 0건 (P1)
- [ ] T402 사양서 v0.5 "iOS 적합성" 검증 — 폰 송출 광고·Service Data 0건 확인 (수용 5)
- [ ] T403 가이드(`docs/guide/ble_dev_guide.md`) §7 을 확정 계약으로 갱신 + CHANGELOG
- [ ] T404 `[human]` 완성 SHA 콘솔 통보 + 짝 태그 (P12/P13)

## Phase 5 — iOS (범위 밖 — 별도 리포 신설 시 이 spec 이 입력)

- [ ] T501 `[human]` iOS controlee 리포 신설 결정 — 첫 태스크는 백그라운드 복원
      (pending connect / State Restoration) + Nearby Interaction 백그라운드 정책 스파이크 (P9)

## 기록

- 2026-08-12 spec 신설 — 사용자 확정: 콜드 웨이크 방식(실패 시 Arm 후퇴), §2-1 병행 송출.
  001 Phase 3 이 선행 의존이라 G1/G2 게이트 전까지 코드 착수하지 않는다.
- 2026-08-12 (논의) iOS 검토 결과 방향 재검토 항목 추가 — iOS 는 현 광고 포맷으로 콜드
  웨이크 불가(UUID 목록 AD 부재 + 백그라운드 nil 스캔 금지), Service Data 송출 API 부재로
  폰 주소 자동 전달도 불가. 대안 모드 4(GATT 역방향) 도출 → 가이드 §7 에 제안 기록.
- 2026-08-12 **G0 확정 (사용자)** — Android+iOS 모두 지원, 충돌 시 iOS 기준. 모드 4 채택.
  spec/plan/tasks 전면 개정 (이전 Android 전용 설계는 git 이력). 사양서 v0.5 개정 요청
  handoff 작성·전달 (T001). 모드 1~3 은 Android 브링업 경로로 존치 (plan D6).
- 2026-08-12 T001 진행 — handoff 사본을 콘솔 리포 작업트리(`uwb-console-kotlin/docs/handoff/`)에
  배치 (미커밋 — 콘솔 세션이 수령·커밋, 기존 관례의 역방향). **G1 승인 (사용자)** — Phase 1
  을 G2(001 검수)와 병행 착수.
- 2026-08-15 콘솔 착수 신호 수령 (`HANDOFF_콘솔_모드4_준비완료.md`) — 콘솔 spec 009
  전부 [maker-ready] + nRF 실측(검수 16 통과, 검수 15 콘솔 단독분 확인). 콘솔 제약 반영
  확인: WoR·연결 후 30초 내 Write·연결 중 광고 중단(재시도=끊고 재스캔)·재발급=재Write —
  기 구현된 OobCentral 이 모두 충족. **Phase 3 부터 `feature/002-cold-wake` 브랜치로 진행**
  (Phase 1·2 커밋이 001 브랜치에 실린 것은 P12 이탈로 기록 — 001 머지에 포함됨).
- 2026-08-15 T301~T303 [maker-ready] — 콜드 웨이크 (Android):
  · T301: `uwb/OobWakeScan.kt` — PendingIntent 스캔 등록/해제 (UUID HW 필터, LOW_POWER,
    FLAG_MUTABLE — 시스템이 결과 extras 를 채움). coordinator `toggleAutoWatch()` +
    `auto_watch` 영속화, 모드 4 전용(모드 이탈 시 자동 OFF). UI: 모드 4 선택 시에만
    Switch 노출, Activity 에서 SCAN·CONNECT+알림 권한 선요청
  · T302: `uwb/OobWakeReceiver.kt` (manifest 등록, exported=false) — 웨이크 스로틀 10초 →
    `RangingForegroundService.startAutoWake()` (ACTION_AUTO_START) → startForeground 직후
    `coordinator.startAutoSession()`: 가용성 판정 → 주소 확보 대기(10초) → 모드 4 Start.
    세션 활성/모드 불일치/준비 실패 시 로그 + FGS 종료 (P6)
  · T303: FGS 기동 거부(백그라운드 제한) catch → 고우선 알림(탭=MainActivity) 폴백.
    알림 권한 없으면 조용히 생략 (로그만)
  **실기기 미검증 (P9)** — T304 스파이크(FGS 예외 허용 여부)가 최우선, 불허 시 Arm 후퇴.
- 2026-08-15 T201~T204 [maker-ready] — 모드 4 (CENTRAL) 폰 구현:
  · T201: `UwbDefaults` 에 BOARD_INFO(5F1D0004)·PHONE_INFO(5F1D0005) 상수 + ADV_INFO_UUID
    주석을 v0.5 이중 역할(모드 3 Service Data / 모드 4 GATT 서비스)로 갱신.
    `OobMode.CENTRAL`("4 · GATT 연결 (iOS)") 추가 — 기존 storageValue 무변경 (P10)
  · T202: `uwb/OobCentral.kt` 신설 — UUID 목록 HW 필터 스캔 → connectGatt →
    BOARD_INFO Read → PHONE_INFO Write(WoR, API 33 분기). 재Start=재Write/스캔 재개,
    연결 끊김=스캔 복귀(§7-17), 주소 재발급=updatePayload 재Write(§3-1),
    실패 무전파(P6). 권한은 SCAN+CONNECT 만 (송출 0건 — §10, ADVERTISE 불요청)
  · T203: coordinator Start 분기 CENTRAL 추가(미교환 30초 폴백 §7-16, 기존 경로 무변경),
    BOARD_INFO 수신 → 입력 반영 → SID 변경 시 재Write → UWB 시작. 배지 표기:
    관찰형(3·4)=스캔중, 모드 4 CONNECTED="콘솔 연결됨". 드롭다운은 entries 라 자동 노출
  · T204: `Mode4ContractTest` 6건 — UUID 리터럴 스냅샷 + Read/Write 양방향 payload 의미
    (v1 빌더/파서 재사용 — 신규 포맷 0). 가이드 §7 을 확정 계약으로 갱신 (T403 일부).
    **실기기 미검증 (P9) — 검수 15~18 은 콘솔 spec 009 구현과 합동 [needs-device].**
- 2026-08-12 T101 [maker-ready] — `uwb/RangingCoordinator.kt` 신설: MainViewModel 의 조정
  로직(Start 분기·워치독·OOB 수명·시뮬레이터·영속화) 전량을 동작 무변경으로 이식.
  viewModelScope→자체 Main.immediate scope, getApplication()→appContext 치환 외 문장 동일.
  프로세스 싱글턴(get/peek — T302 의 FGS 진입점 예비), shutdown = 기존 onCleared 동일 정리
  +싱글턴 해제. MainViewModel 은 UiState 선언 + 위임 메서드 10개로 축소 — ui/·Activity
  무변경, androidx.core.uwb import 가 root 패키지에서 사라져 P1 정합 개선.
  기존 JVM 테스트 무수정 (P10).
