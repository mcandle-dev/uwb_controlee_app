# 001 — 태스크 (tasks)

plan.md 의 결정(D1~D6)을 실행 순서로 쪼갠 것. maker 는 `[maker-ready]` 까지만 (P11).

## Phase 0 — 하네스·계약 (코드 금지 구간)

- [x] T001 SDD 하네스 이식 — constitution.md 제정(P1~P15), specs/001 3종, CLAUDE.md/AGENTS.md 갱신
- [x] T002 `[human]` 사양서 §2-1 확정 — **병행 송출** (2026-08-12 사용자 확정, 이 리포 세션에서.
      사양서 개정(Draft 해제·버전 업)은 마스터인 콘솔 리포 몫 — `docs/handoff/HANDOFF_T002_병행송출_확정.md` 로 통보)
- [ ] T003 `[human]` v1 태그 위치 결정 — 로컬 fix 2건(5f0ee85, 6871967) push 후
      `v1.0_controlee_advertise` 재태그 여부 + 콘솔 pin bump 통보 (handoff §7)

## Phase 1 — 상수·모델 (JVM 검증 구간)

- [ ] T101 `[maker-ready]` `UwbDefaults.kt` 에 `ADV_INFO_UUID`·`SCAN_WAIT_TIMEOUT_MS` + `OobMode` enum (D4/D3)
- [ ] T102 `[maker-ready]` 모드 영속화(SharedPreferences `oob_mode`, 기본 ADVERTISE_GATT) + JVM 테스트
- [ ] T103 `[maker-ready]` D6 캐시 필터를 순수 함수로 작성 + JVM 테스트 (P3). 기존 `OobPayloadTest` 무수정 green 확인 (P10)

## Phase 2 — 모드 2 (BEACON 송출)

- [ ] T201 `[maker-ready]` `uwb/OobBeacon.kt` — Service Data(`5F1D0001`) 광고, connectable=false, BALANCED,
      open/close/status + 실패 무전파 (D1/P6)
- [ ] T202 `[maker-ready]` 세션 자동실패 시 payload 갱신(새 주소로 광고 교체 — P7, 사양서 §7-15)
- [ ] T203 `[maker-ready]` `MainViewModel` Start 분기: 모드 2 = 광고 + UWB 즉시 시작 (D2). 모드 1 경로 diff 없음 확인

## Phase 3 — 모드 3 (SCANNER 관찰) ※ T002 확정 후 착수 (2026-08-12 확정됨 — 착수)

- [ ] T301 `[maker-ready]` `uwb/OobScanner.kt` — ScanFilter(ServiceData `5F1D0003`), D6 필터 적용, 30초 폴백 (D2)
- [ ] T302 `[maker-ready]` 수신 → 보드 MAC·SID 자동 반영 → UWB 시작. 미수신 폴백 = 수동 입력값
- [ ] T303 `[maker-ready]` (§2-1 병행안 확정) 모드 3 에서 `OobBeacon` 병행 송출 — 광고 수신 시점에 시작
- [ ] T304 `[maker-ready]` Manifest `BLUETOOTH_SCAN`(neverForLocation) + 모드 3 선택 시에만 런타임 요청 (D5)

## Phase 4 — UI·문서

- [ ] T401 `[maker-ready]` `MainScreen` 모드 선택 드롭다운 (D3) — 레인징 중 비활성.
      SCANNER 항목은 미노출 (T301 에서 추가 — 미구현 모드를 사용자에게 노출하지 않음)
- [ ] T402 `[maker-ready]` grep 게이트: `ui/` 에 bluetooth/uwb import 0건 (P1)
- [ ] T403 (부분) `gradlew test` + `assembleDebug` + `lint` green / CHANGELOG 갱신 — 모드 3 완료 후 재실행
- [ ] T404 콘솔 세션에 완성 커밋 SHA 통보 문서 (`docs/handoff/` 역방향 — P13)

## Phase 5 — 실기기 (사람)

- [ ] T501 `[needs-device]` 모드 2 광고 프레임·31B 확인 (nRF Connect — 사양서 검수 14)
- [ ] T502 `[needs-device]` 콘솔 BEACON ↔ 폰 BEACON E2E (검수 10) / 모드 1 회귀 (검수 11)
- [ ] T503 `[needs-device]` 콘솔 ADVERTISE ↔ 폰 SCANNER E2E (검수 12) / 짝 불일치 안내 (검수 13)
- [ ] T504 `[human]` 짝 태그 `v2.0_controlee_scanner` 부착 + 콘솔 pin bump 확인 (P12/P13)

## 기록

- 2026-08-11 T001 완료 — 콘솔 세션이 SDD 하네스 이식 (constitution 제정, specs/001 3종,
  handoff·사양서 v0.3 수령). 이후 태스크는 이 리포 담당 세션이 수행한다.
- 2026-08-11 (범위 내 대화 요청) v2 병행 설치 — `applicationId` 를 `com.mcandle.uwbcontrolee.v2`
  로 분리(versionName 2.0-dev, label "UWB Controlee v2"). v1 설치본과 한 폰에서 페어 테스트
  가능. namespace(코드 패키지)·OOB 계약은 무변경 — 콘솔은 UUID 로 필터하므로 영향 없음.
- 2026-08-11 T101 [maker-ready] — `ADV_INFO_UUID`(5F1D0003)·`SCAN_WAIT_TIMEOUT_MS`(30s) 를
  `UwbDefaults.kt` 에, `OobMode` enum(storageValue·label·DEFAULT=ADVERTISE_GATT)을 `uwb/OobMode.kt` 에 추가.
- 2026-08-11 T102 [maker-ready] — 영속화는 순수 매핑(`OobMode.fromStorageValue`, null/미지→기본값)
  + ViewModel 의 SharedPreferences(`uwb_controlee_prefs`/`oob_mode`) 조합. JVM 테스트 `OobModeTest`(5건).
- 2026-08-11 T103 [maker-ready] — `uwb/OobScanFilter.kt` 순수 판정 함수(MALFORMED/STALE/WEAK/
  DUPLICATE/APPLY, 나이 5s·rssi −95dBm 보수 하한). JVM 테스트 `OobScanFilterTest`(11건).
  기존 `OobPayloadTest` 무수정 green (P10).
- 2026-08-11 T201 [maker-ready] — `uwb/OobBeacon.kt` 신설: Service Data(5F1D0001) 광고 28B,
  connectable=false·BALANCED·timeout 0, open/close/updatePayload/status — `OobGattServer` 계약 복제,
  실패 무전파(P6). ADVERTISE 권한만 요구(CONNECT 불필요).
- 2026-08-11 T202 [maker-ready] — 주소 재발급 → `pushOobPayloadUpdate` 가 양 채널에 전파
  (닫힌 쪽 no-op). BEACON 은 내용 변경 시 광고 stop→start 교체 (§7-15). 자동실패(keepOob) 시
  기존 P7 경로가 BEACON 에도 그대로 적용됨 (채널을 닫지 않으므로).
- 2026-08-11 T203 [maker-ready] — `startRanging` 에 when(oobMode) 분기 (plan D2, 조정자 한 곳).
  ADVERTISE_GATT 분기는 기존 문장 그대로 이동(로직 diff 없음), BEACON = 광고 + `beginPendingRanging`
  즉시, SCANNER = 미구현 안내 + 수동 폴백(T301 대기). `onBlePermissionResult` 도 모드 분기.
  모드 전환(onOobModeChanged)은 세션 중 거부 + 유지 중이던 OOB/FGS 정리.
- 2026-08-11 T401 [maker-ready] — 드롭다운을 고정 파라미터 요약 줄 옆에 배치 (D3), 레인징 중
  비활성. 선택지는 모드 1·2 만 (SCANNER 는 T301 에서 추가). T402 grep 0건, T403 부분:
  `test`+`assembleDebug`+`lint` green (BUILD SUCCESSFUL), CHANGELOG 갱신. **실기기 미검증 —
  모드 2 동작 단정 금지 (P9), 검수 10~14 는 Phase 5.**
- 2026-08-12 T002 사람 확정 — §2-1 = **병행 송출** (사양서 초안 채택). 배경 요청: "폰이
  백그라운드에서 비콘을 받아 자동 레인징" (콜드 웨이크 — 범위 밖이라 `specs/002-cold-wake` 신설).
  사양서 Draft 해제·버전 업은 콘솔 세션에 handoff 로 요청 (P5/P13).
- 2026-08-12 T301 [maker-ready] — `uwb/OobScanner.kt` 신설: ServiceData(5F1D0003) 빈 데이터
  매치 필터(광고에 Service UUID 목록 AD 가 없어 setServiceUuid 로는 안 잡힘), LOW_LATENCY,
  D6 `OobScanFilter` 적용, MALFORMED 로그 1회 제한. 상태 매핑 §6-1: 스캔중=ADVERTISING,
  수신 확정=CONNECTED. 실패 무전파 (P6).
- 2026-08-12 T302 [maker-ready] — `parseOobPayload`/`OobInfo` 를 `UwbDefaults.kt` 에 추가
  (빌더 역함수 — 기존 빌더는 무변경, JVM 테스트 `OobPayloadParseTest` 7건). 수신 →
  입력칸 자동 반영 → UWB 시작, 미수신 30초(`SCAN_WAIT_TIMEOUT_MS`) 폴백 = 수동 입력값 (§7-14).
  세션 중 광고 변경은 로그만 (재Start 안내).
- 2026-08-12 T303 [maker-ready] — 광고 수신 시점에 `OobBeacon` 병행 송출 시작 (§2-1 확정안,
  수신한 session id 로 payload 구성). 스캔은 유지 (§2-1: 관찰+송출 병행).
- 2026-08-12 T304 [maker-ready] — Manifest `BLUETOOTH_SCAN`(neverForLocation) 추가.
  `bleOobPermissionsFor(mode)`/`hasBleOobPermissions(context, mode)` 신설 — SCANNER 선택
  시에만 SCAN 을 요청 목록에 포함 (D5). MainActivity 는 결과 맵 대신 결과 시점 보유 상태로 판정.
  T401 후속: 드롭다운에 SCANNER 노출, 배지 표기 모드 분기(스캔중/광고 수신됨).
  `test`+`assembleDebug`+`lint` green. **실기기 미검증 (P9) — 검수 12·13 은 Phase 5.**
- 2026-08-12 T501 사용자 실기기 확인 — 모드 2 광고 프레임 테스트(nRF Connect) **통과 보고**
  (검수 14). 완료 판정은 checker 몫 (P11).
- 2026-08-12 (범위 내 대화 요청) 콘솔 광고 시뮬레이터 — 검수 12 를 nRF Connect 없이 실제 앱
  둘로 테스트하기 위한 보조 기능. `OobBeacon` 에 serviceDataUuid 파라미터 추가(기본값은 기존
  5F1D0001 — 모드 2 동작 무변경), ViewModel 에 `consoleSimBeacon`(5F1D0003, 보드 MAC·SID
  입력값 payload) + "콘솔시뮬" 토글 버튼. UWB 세션·OOB 채널 수명과 독립. 계약 무변경
  (기존 사양서 §5-2 형식 그대로 송출). `test`+`assembleDebug`+`lint` green.
- 2026-08-15 사용자 실기기 확인 — **검수 14 잔여(광고 프레임 LE 실측)·검수 10(모드 2 E2E)
  통과 보고** (T501·T502 몫). 검수 10 최초 실패는 콘솔 모드 미전환 또는 관찰 창 10초 만료
  추정 — 코드 결함 아님. T102(리팩터 회귀)의 모드 2 몫 겸함. 잔여: 검수 12·13.
- 2026-08-15 사용자 실기기 확인 — **검수 12 (모드 3 E2E) 통과 보고** (T503 전반): 콘솔
  광고 송출 → 폰 자동 반영 → 병행 송출 → 콘솔 폰 주소 자동 확보 → 자동 레인징까지
  4단계 체인 성립. §2-1 병행안 실물 검증. T102 의 모드 3 몫 겸함 — **T102 전체(모드 1~3)
  회귀 확인 완료.** 잔여: 검수 13 (짝 불일치).
- 2026-08-15 사용자 실기기 확인 — **검수 13 (짝 불일치) 통과 보고** (T503 후반): 스캔 측
  "스캔 완료, 0대 발견" 안내 종결 + 상대 측 크래시 없음 (§7-11). **이로써 사양서 §8 검수
  10~14 전부 통과 보고 — Phase 5 실기기 몫(T501~T503) 완료.** 남은 것: checker 판정(P11)
  → T003(v1 태그 위치)·T504(짝 태그)·T404(SHA 통보) 등 B 절 마무리.
- 2026-08-15 콘솔 세션 통보 — `docs/handoff/HANDOFF_검수10-14_결과통보.md` 작성 + 콘솔 리포
  작업트리 배치 (미커밋, 관례). 콘솔 spec 008 T401~T403 `## 기록` 갱신 입력 자료
  (검수↔태스크 매핑표·운영 주의점·후속 절차 포함). 콘솔 문서 직접 수정은 P13 금지라 handoff 로.
- 2026-08-13 사용자 실기기 확인 — **검수 11 (모드 1 회귀) 통과 보고**: 최초 시도에서 콘솔이
  레인징 실패 → 진단 후 정상 동작 확인 (유력 원인은 v1/v2 동시 설치 또는 모드 영속화 잔존 —
  코드 회귀 아님, 모드 1 경로는 동작판 대비 diff 0줄 재확인). 완료 판정은 checker 몫 (P11).
- 2026-08-12 검수 준비 문서 — `docs/guide/검수10-13_함수_호출_맵.md` 작성 (폰·콘솔 함수 호출 체인,
  로그↔함수 매핑). 콘솔 spec 008 은 3모드 maker-ready + 사양서 v0.4 확정 확인 (읽기 전용).
  v0.4 사본이 이 리포 작업트리에 배치돼 있음 — 커밋 시 포함 예정 (P5).
