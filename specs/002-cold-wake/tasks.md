# 002 — 태스크 (tasks) · 전면 개정 2026-08-12 (모드 4 기반)

plan.md (D1~D6) 실행 순서. maker 는 `[maker-ready]` 까지만 (P11).

## Phase 0 — 게이트·계약 (코드 금지 구간)

- [x] G0 `[human]` **iOS 지원 여부 확정** — 2026-08-12 사용자: **Android+iOS 모두 지원,
      충돌 시 iOS 기준.** 이에 따라 모드 4(GATT 역방향) 채택, spec/plan 전면 개정
- [ ] T001 `[human/cross-repo]` **사양서 v0.5 개정** — 콘솔 세션에
      `docs/handoff/HANDOFF_모드4_사양서개정_요청.md` 전달, 개정본(양 리포 사본) 수령.
      **확정 전 모드 4 코드(T2xx 이후) 착수 금지 (P5)**
- [x] T002 `[human]` G1 — 조정 로직 `RangingCoordinator` 추출 **승인** (2026-08-12 사용자)
- [ ] T003 `[needs-device]` G2 — spec 001 실기기 검수 10~13 통과 (리팩터 기준선, plan R5)

## Phase 1 — 조정자 리팩터 (계약 무관 — T001 대기 중에도 G1·G2 후 착수 가능)

- [ ] T101 `RangingCoordinator` 추출 — Start 시퀀스·워치독·OOB 수명 이동, ViewModel 은
      위임·구독자로. **동작 무변경** — 기존 JVM 테스트 무수정 green (P10)
- [ ] T102 `[needs-device]` 모드 1~3 수동 흐름 회귀 확인 — **모드 1 통과 보고**
      (2026-08-13 사용자, 리팩터 빌드 실기기). 모드 2·3 남음

## Phase 2 — 모드 4 (CENTRAL) 폰 구현 ※ T001 확정 후

- [ ] T201 `UwbDefaults.kt` v0.5 상수(콘솔 서비스·BOARD_INFO·PHONE_INFO UUID) +
      `OobMode.CENTRAL` 추가 + JVM 테스트 (기존 storageValue 하위호환 — P10)
- [ ] T202 `uwb/OobCentral.kt` — UUID 필터 스캔 → 연결 → BOARD_INFO Read →
      PHONE_INFO Write, open/close/status + 실패 무전파 (D3/P6). 주소 재발급 시 재Write
- [ ] T203 coordinator Start 분기에 CENTRAL 추가 + `MainScreen` 모드 항목 (기존 경로 diff 없음)
- [ ] T204 payload 재사용 검증 — 기존 빌더/파서 무수정, Read/Write 양방향 JVM 테스트

## Phase 3 — 콜드 웨이크 (Android)

- [ ] T301 자동 감시 토글 + PendingIntent 스캔 등록/해제 (`setServiceUuid` HW 필터 — D5)
- [ ] T302 `OobWakeReceiver` → FGS `ACTION_AUTO_START` → coordinator 모드 4 자동 시퀀스
- [ ] T303 FGS 기동 실패 폴백 — 고우선 알림, 탭 시 포그라운드 Start (수용 3)
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
- 2026-08-12 T101 [maker-ready] — `uwb/RangingCoordinator.kt` 신설: MainViewModel 의 조정
  로직(Start 분기·워치독·OOB 수명·시뮬레이터·영속화) 전량을 동작 무변경으로 이식.
  viewModelScope→자체 Main.immediate scope, getApplication()→appContext 치환 외 문장 동일.
  프로세스 싱글턴(get/peek — T302 의 FGS 진입점 예비), shutdown = 기존 onCleared 동일 정리
  +싱글턴 해제. MainViewModel 은 UiState 선언 + 위임 메서드 10개로 축소 — ui/·Activity
  무변경, androidx.core.uwb import 가 root 패키지에서 사라져 P1 정합 개선.
  기존 JVM 테스트 무수정 (P10).
