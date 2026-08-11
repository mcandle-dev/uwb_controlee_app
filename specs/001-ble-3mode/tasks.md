# 001 — 태스크 (tasks)

plan.md 의 결정(D1~D6)을 실행 순서로 쪼갠 것. maker 는 `[maker-ready]` 까지만 (P11).

## Phase 0 — 하네스·계약 (코드 금지 구간)

- [x] T001 SDD 하네스 이식 — constitution.md 제정(P1~P15), specs/001 3종, CLAUDE.md/AGENTS.md 갱신
- [ ] T002 `[human]` 사양서 §2-1 미결 확정 (콘솔 spec 008 T004 와 동일 건 — 한쪽에서 정하면 양쪽 반영)
- [ ] T003 `[human]` v1 태그 위치 결정 — 로컬 fix 2건(5f0ee85, 6871967) push 후
      `v1.0_controlee_advertise` 재태그 여부 + 콘솔 pin bump 통보 (handoff §7)

## Phase 1 — 상수·모델 (JVM 검증 구간)

- [ ] T101 `UwbDefaults.kt` 에 `ADV_INFO_UUID`·`SCAN_WAIT_TIMEOUT_MS` + `OobMode` enum (D4/D3)
- [ ] T102 모드 영속화(SharedPreferences `oob_mode`, 기본 ADVERTISE_GATT) + JVM 테스트
- [ ] T103 D6 캐시 필터를 순수 함수로 작성 + JVM 테스트 (P3). 기존 `OobPayloadTest` 무수정 green 확인 (P10)

## Phase 2 — 모드 2 (BEACON 송출)

- [ ] T201 `uwb/OobBeacon.kt` — Service Data(`5F1D0001`) 광고, connectable=false, BALANCED,
      open/close/status + 실패 무전파 (D1/P6)
- [ ] T202 세션 자동실패 시 payload 갱신(새 주소로 광고 교체 — P7, 사양서 §7-15)
- [ ] T203 `MainViewModel` Start 분기: 모드 2 = 광고 + UWB 즉시 시작 (D2). 모드 1 경로 diff 없음 확인

## Phase 3 — 모드 3 (SCANNER 관찰) ※ T002 확정 후 착수

- [ ] T301 `uwb/OobScanner.kt` — ScanFilter(ServiceData `5F1D0003`), D6 필터 적용, 30초 폴백 (D2)
- [ ] T302 수신 → 보드 MAC·SID 자동 반영 → UWB 시작. 미수신 폴백 = 수동 입력값
- [ ] T303 (§2-1 병행안 확정 시) 모드 3 에서 `OobBeacon` 병행 송출
- [ ] T304 Manifest `BLUETOOTH_SCAN`(neverForLocation) + 모드 3 선택 시에만 런타임 요청 (D5)

## Phase 4 — UI·문서

- [ ] T401 `MainScreen` 모드 선택 드롭다운 (D3) — 레인징 중 비활성
- [ ] T402 grep 게이트: `ui/` 에 bluetooth/uwb import 0건 (P1)
- [ ] T403 `gradlew test` + `assembleDebug` + `lint` green / README·CHANGELOG 갱신
- [ ] T404 콘솔 세션에 완성 커밋 SHA 통보 문서 (`docs/handoff/` 역방향 — P13)

## Phase 5 — 실기기 (사람)

- [ ] T501 `[needs-device]` 모드 2 광고 프레임·31B 확인 (nRF Connect — 사양서 검수 14)
- [ ] T502 `[needs-device]` 콘솔 BEACON ↔ 폰 BEACON E2E (검수 10) / 모드 1 회귀 (검수 11)
- [ ] T503 `[needs-device]` 콘솔 ADVERTISE ↔ 폰 SCANNER E2E (검수 12) / 짝 불일치 안내 (검수 13)
- [ ] T504 `[human]` 짝 태그 `v2.0_controlee_scanner` 부착 + 콘솔 pin bump 확인 (P12/P13)

## 기록

- 2026-08-11 T001 완료 — 콘솔 세션이 SDD 하네스 이식 (constitution 제정, specs/001 3종,
  handoff·사양서 v0.3 수령). 이후 태스크는 이 리포 담당 세션이 수행한다.
