# HANDOFF — T002 확정 통보: 사양서 §2-1 = 병행 송출

> 작성 2026-08-12, 폰 세션(`uwb_controlee_app`)이 콘솔 세션(`uwb-console-kotlin`)에 전달.
> 방향: 폰 → 콘솔 (역방향 handoff — P13: 교차 변경은 문서로 요청).

## 확정 내용

**사양서 v0.3 §2-1 미결(Draft)이 사람에 의해 확정됐다 (2026-08-12, 폰 리포 세션에서):**

- **병행 송출 채택** (v0.3 초안 그대로):
  - 콘솔 ADVERTISE 모드 = ADV_INFO(`5F1D0003`) 송출 + 폰 OOB_INFO(`5F1D0001`) 관찰 병행
  - 폰 SCANNER 모드 = 콘솔 광고 관찰 + 자신 OOB_INFO 를 BEACON 으로 병행 송출
- 폰 쪽 근거 기록: `specs/001-ble-3mode/tasks.md` T002 · `## 기록` 2026-08-12 항목

## 콘솔 세션에 요청하는 것

1. **사양서 개정** — 마스터(`uwb-console-kotlin/docs/oob/BLE_OOB_인터페이스_사양서.md`)에서
   §2-1 의 Draft 표기 해제 + 버전 업(v0.4) + 양 리포 동시 커밋 (P5 절차).
   폰 리포의 사본 갱신 커밋은 이쪽 세션이 수행한다 — 개정본을 전달해 달라.
2. 콘솔 spec 008 T004 (동일 건) 를 이 확정으로 종결 처리.

## 참고 — 폰 쪽 구현 상태 (2026-08-12)

- spec 001 Phase 1~4: 모드 2(BEACON)·모드 3(SCANNER, 병행 송출 포함) `[maker-ready]`,
  `test`/`assembleDebug`/`lint` green. 실기기 검수 10~14 미실시 (P9).
- 폰 앱은 v2 병행 설치용으로 applicationId 가 `com.mcandle.uwbcontrolee.v2` 로 분리됨
  (BLE 계약 무영향 — UUID 필터 기준).
- 후속: `specs/002-cold-wake` (백그라운드 비콘 웨이크 → 자동 레인징) 신설 — 콘솔 계약
  변경 없음 (기존 ADVERTISE 광고가 그대로 트리거).
