# KICKOFF_PROMPT — 새 세션 시작 시 붙여넣는 프롬프트

> 이 파일은 `D:\dev\uwb_controlee_app` 에서 Claude 세션을 처음 열 때 전달하는 프롬프트의 원본이다.
> (콘솔 리포 `uwb-console-kotlin` 의 KICKOFF_PROMPT.md 와 같은 용도)

---

너는 이 리포(`D:\dev\uwb_controlee_app`, UWB Controlee 앱)의 **maker 세션**이다.
Phase 2 (BLE OOB 3모드) 를 SDD 방식으로 구현한다.

## 먼저 읽어라 (순서대로, 코드 작성 전)

1. `constitution.md` — 불변 원칙 P1~P15. 모든 판단의 최상위
2. `specs/001-ble-3mode/` 의 `spec.md` → `plan.md` → `tasks.md`
3. `docs/handoff/HANDOFF_008_controlee_scanner.md` — 콘솔 세션이 남긴 인수인계
4. `docs/oob/BLE_OOB_인터페이스_사양서.md` **v0.3** — 유일한 바이트 계약 (마스터는 콘솔 리포, 여기는 사본)

## 현재 상태 (2026-08-11, 콘솔 세션이 셋업)

- 브랜치: `feature/001-ble-3mode` (커밋 `51449d0` — SDD 하네스·사양서 v0.3·handoff까지 반영됨)
- 이 브랜치는 `feature/ble-oob` 의 미push fix 2건(`5f0ee85`, `6871967`) 위에 있다 — T003 참조
- 코드는 아직 0줄. tasks.md 의 T001 만 완료 상태다
- 짝 리포: `D:\dev\mcandle\uwb-console-kotlin` (별도 세션, `feat/008-ble-beacon`) — **절대 수정 금지 (P13)**

## 해야 할 일

`specs/001-ble-3mode/tasks.md` 를 위에서부터 순서대로 수행한다:

- **지금 착수 가능**: Phase 1 (T101~T103 — 상수·OobMode enum·영속화·캐시 필터 + JVM 테스트)
  → Phase 2 (T201~T203 — `OobBeacon` 모드 2 송출)
- **착수 금지 (게이트)**: Phase 3 (T301~ 모드 3 SCANNER) 은 **T002(사양서 §2-1 미결) 가
  사람에 의해 확정되기 전까지 시작하지 않는다.** T002/T003 은 `[human]` 태그 — 네 일이 아니다.

## 지켜라

- 태스크 완료 표시는 `[maker-ready]` 까지만. done 금지 (P11). 각 태스크마다 tasks.md `## 기록` 에 한 줄
- `OobGattServer.kt` 와 payload 빌더/파서는 **0줄 변경** (plan D1 — 수용기준 1)
- 실기기 확인이 필요한 항목은 `[needs-device]` 로 사람에게 넘긴다. 실물 페어 검증 전
  "동작한다" 단정 금지 (P9)
- 검증: `.\gradlew.bat test` + `assembleDebug` (UI/매니페스트 변경 시 `lint` 추가). push 전
  체크리스트는 CLAUDE.md 참조
- OOB 계약(UUID·payload)을 바꿔야 할 상황이 오면 **멈추고 사람에게 보고** — 사양서 개정은
  양 리포 동시 커밋 + 콘솔 pin bump 가 필요한 크로스 리포 절차다 (P5/P13)

Phase 1 부터 시작해라.
