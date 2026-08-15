# HANDOFF — 콘솔 모드 4 (GATT-SERVER) 구현 완료 → 폰 spec 002 착수 요청

> 작성 2026-08-15, 콘솔 세션(`uwb-console-kotlin`)이 폰 세션(`uwb_controlee_app`)에 전달.
> 선행 문서: `HANDOFF_모드4_v05_확정_회신.md` (계약 확정, 8-12). 이 문서는 **상대측(콘솔)
> 구현이 준비됐음을 알리는 착수 신호**다 — 폰 spec 002 Phase 2(모드 4 코드)를 시작해도
> 콘솔 쪽 대기 없이 합동 검증까지 이어질 수 있다.

## 1. 콘솔 쪽 상태 — 무엇이 준비됐나

| 구분 | 상태 |
|---|---|
| 구현 | **spec 009 T101~T304 전부 [maker-ready]** — `feat/009-gatt-server`, 커밋 `4c5969c` |
| 핵심 클래스 | `ble/GattServerBleOobChannel.kt` — connectable 광고 + GATT 서버 |
| 설정 UI | "자동 연결 대기" 선택지 (모드 4). 선택 시 광고 권한 자동 요청 |
| 테스트 | `testSimDebugUnitTest` 152/152 green (기존 147 무수정 + 모드 4 신규 5) |
| **실측** | **nRF Connect 통과 보고 (8-15 사용자)** — 아래 §2. 검수 16 + 검수 15 전반 해당 |

즉 폰 개발 중 언제든 **실제 콘솔 앱을 상대로** 개발·디버깅할 수 있다 (nRF 대체 불필요).

## 2. nRF 실측으로 확인된 계약 동작 (폰 구현의 기준점)

- 광고: `5F1D0003-9A8B-4C7D-B2E3-6F4A5D8C9B0A` 가 **Complete 128-bit Service UUID 목록**으로
  표시 (§5-4) — 폰의 HW UUID 필터(`ScanFilter.setServiceUuid` / iOS `withServices:`)가 매치된다
- **connectable** 확인, Service Data 없음 (모드 3 광고와 교차 매치 없음)
- 연결 → `5F1D0004` **BOARD_INFO Read**: `01` + 보드 MAC 2B + 세션 ID 4B LE 정상
- `5F1D0005` **PHONE_INFO Write**: 콘솔에 폰 주소 자동 반영 확인

## 3. 폰 쪽이 알아야 할 콘솔 동작 (사양서 v0.5 + 콘솔 구현 확정 사항)

1. **Write 응답을 기다리지 말 것** — PHONE_INFO 는 Write Without Response 확정 (§3-1).
   콘솔은 특성에 WRITE 속성도 열어 두어 With Response 로 보내도 수용은 하지만,
   검증 실패를 알려줄 방법은 어느 쪽에도 없다 (§7-18 — 콘솔 로그만)
2. **연결 후 30초 내 Write** — 콘솔 `WRITE_TIMEOUT_MS = 30_000`. 초과 시 콘솔은 수동 입력
   안내로 전환한다 (§7-16, 오류 아님). 콜드 웨이크 FGS 기동 시간을 고려해 넉넉히 잡았다
3. **연결 중에는 콘솔 광고가 멈춘다** — 1 연결 가정 (§9-5). 끊으면 광고 재개 (§7-17) →
   재스캔·재연결 가능. 폰 쪽 재시도 로직은 "끊고 다시 스캔" 이 안전한 경로
4. **주소 재발급 = 연결 유지 중 재Write** — 콘솔이 값 변경을 감지해 재반영한다 (모드 1
   Notify 대응물). 재Write 때 OOB_DONE 재발행은 없다
5. 콘솔 설정 라벨은 "자동 연결 대기" — 합동 테스트 절차서에 이 명칭으로 쓸 것

## 4. 착수 요청 — 폰 spec 002 Phase 2

- 게이트 상태: T001(v0.5 확정) 해제됨 · G1(조정자 리팩터) [maker-ready] ·
  G2(3모드 검수)는 **8-15 전 항목 통과 보고**로 사실상 해소 (checker 판정만 잔여)
- 착수 브랜치: `feature/002-cold-wake` (폰 spec 002 문서의 기존 방침)
- 남은 스파이크: T304 (Receiver 에서 FGS 기동 허용 여부 — 불허 시 Arm 후퇴)

## 5. 합동 검수 계획 (양쪽 구현 완성 후 — 사양서 §8 검수 15~18)

| 검수 | 내용 | 비고 |
|---|---|---|
| 15 | 폰 연결·Read/Write → 콘솔 폰 주소 자동 반영 + 레인징 | 전반(콘솔 단독분)은 nRF 로 통과 보고됨 |
| 16 | 콘솔 광고 21B·connectable·Service Data 없음 | **통과 보고됨 (8-15)** |
| 17 | 모드 4 에서 폰 송출 광고·Service Data 0건 | 폰 구현 후 nRF 로 확인 |
| 18 | 끊김 후 광고 재개 → 재발견·재연결 | 콘솔 시뮬 테스트 통과, 실물은 합동 시 |

완성 시 절차: 검수 통과 → 양쪽 checker 판정 → 짝 태그 + 콘솔 submodule pin bump
(repo_guide §6 원자 규칙 — 콘솔 T403 / 폰 대응 태스크). 합동 검수 절차서는 폰 리포
`docs/TODO.md` 를 개정해 추가한다 (8-13 개정판의 §7 에 예고돼 있음).
