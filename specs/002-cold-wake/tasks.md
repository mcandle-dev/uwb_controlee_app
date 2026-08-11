# 002 — 태스크 (tasks)

plan.md (D1~D4) 실행 순서. maker 는 `[maker-ready]` 까지만 (P11).

## Phase 0 — 게이트 (착수 조건)

- [ ] G1 `[human]` plan D2 승인 — 조정 로직을 `RangingCoordinator` 로 추출하는 구조 변경
- [ ] G2 `[needs-device]` spec 001 실기기 검증(검수 10~14) 통과 — 리팩터 기준선 확보 (plan R3)

## Phase 1 — 성립 조건 확인 (코드 최소)

- [ ] T101 `[needs-device]` 검증용 스파이크: PendingIntent 스캔 + Receiver 에서 FGS 기동이
      Galaxy(대상 기기)에서 허용되는지 확인 (plan D3 근거 확정). 불허 시 → 대기(Arm) 방식으로
      spec 개정 후 재계획

## Phase 2 — 리팩터 (동작 무변경)

- [ ] T201 `RangingCoordinator` 추출 — Start 시퀀스·워치독·OOB 수명을 ViewModel 에서 이동,
      ViewModel 은 위임·구독자로. 기존 JVM 테스트 무수정 green (P10)
- [ ] T202 모드 1~3 수동 흐름 회귀 확인 `[needs-device]`

## Phase 3 — 콜드 웨이크

- [ ] T301 자동 감시 토글 + PendingIntent 스캔 등록/해제 (D1)
- [ ] T302 `OobWakeReceiver` → FGS ACTION_AUTO_START → 모드 3 자동 시퀀스 (D3)
- [ ] T303 FGS 기동 실패 폴백 — 고우선 알림, 탭 시 포그라운드 Start (수용 2)
- [ ] T304 권한·안내 — 토글 ON 조건(BLE SCAN·알림 권한), 배터리 최적화 제외 안내 (R2)

## Phase 4 — 검증·문서

- [ ] T401 `gradlew test`+`assembleDebug`+`lint` green / CHANGELOG
- [ ] T402 `[needs-device]` 수용 기준 1~5 실물 확인 (스와이프 제거 + 화면 OFF 시나리오 포함)

## 기록

- 2026-08-12 spec 신설 — 사용자 확정: 콜드 웨이크 방식(실패 시 Arm 후퇴), §2-1 병행 송출.
  001 Phase 3 이 선행 의존이라 G1/G2 게이트 전까지 코드 착수하지 않는다.
