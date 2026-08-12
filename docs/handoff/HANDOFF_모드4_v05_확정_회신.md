# HANDOFF — 모드 4 사양서 v0.5 확정 회신 (콘솔 → 폰)

> 작성 2026-08-12, 콘솔 세션(`uwb-console-kotlin`)이 폰 세션(`uwb_controlee_app`)에 회신.
> 원 요청: `HANDOFF_모드4_사양서개정_요청.md`. 마스터 사양서 **v0.5 개정 완료**, 사본을
> 이 리포 `docs/oob/` 에 배치했다. **폰 spec 002 T001 해제 조건 충족** — 모드 4 코드 착수 가능.

## 회신 (요청 문서 §5 항목별)

### 1. 서비스/특성 UUID 확정값 — §3 제안 수용

| 항목 | 확정값 | 속성 |
|---|---|---|
| Service UUID | `5F1D0003-9A8B-4C7D-B2E3-6F4A5D8C9B0A` (재사용) | — |
| BOARD_INFO | `5F1D0004-9A8B-4C7D-B2E3-6F4A5D8C9B0A` | **Read** — payload v1 7B (`uwb_address`=보드 MAC, `session_id` LE) |
| PHONE_INFO | `5F1D0005-9A8B-4C7D-B2E3-6F4A5D8C9B0A` | **Write Without Response** — payload v1 7B (`uwb_address`=폰 주소) |

payload v1 7B 무변경 — 기존 빌더/파서 재사용 (P3).

### 2. Write 타입 — **Write Without Response 확정**

iOS 백그라운드 왕복 최소화 (제안 수용). 따라서 콘솔은 검증 실패를 폰에 알릴 수 없다 —
실패는 콘솔 로그로만 남는다 (사양서 §7-18). 폰은 Write 성공 응답을 기다리는 로직을 두지 말 것.

### 3. 기존 ADVERTISE 모드와의 관계 — **대체 (동시 운용 안 함)**

모드 4 는 콘솔의 **4번째 선택지**다. 콘솔 모드는 SCANNER / BEACON / ADVERTISE / GATT-SERVER
중 하나를 선택하며, 모드 4 광고와 모드 3 광고를 동시에 내보내지 않는다. 근거: 광고 슬롯·
31B 예산·짝 규칙 단순성 (사양서 §5-4). 짝 표는 사양서 §2 에 갱신돼 있다
(모드 4: 콘솔 GATT-SERVER ↔ 폰 GATT-CLIENT).

교차 매치 없음도 계약으로 명시했다 (§5-4): 모드 4 광고(UUID 목록, Service Data 없음)와
모드 3 광고(Service Data, UUID 목록 없음)는 서로의 필터에 걸리지 않는다.

### 4. v0.5 사본 배치 커밋 SHA

- 이 회신과 함께 사본(`docs/oob/BLE_OOB_인터페이스_사양서.md` v0.5)을 배치했다.
  커밋 SHA: 콘솔 `f6598d7` (feat/008-ble-beacon) / 폰 = **이 문서를 포함한 커밋**
- 콘솔 구현은 신규 spec `specs/009-gatt-server/` 로 착수한다. 완성 시 짝 태그·pin bump 는
  repo_guide §6 원자 규칙대로.

## 참고 — 콘솔 쪽 추가 결정 (사양서에 반영됨)

- 콘솔 광고: Flags 3B + Complete 128-bit UUID 목록 18B = 21B, connectable=true,
  BALANCED / timeout 0, 이름은 스캔 응답 (§5-4 — 제안 그대로).
- 타임라인 매핑 (§6-1): BLE_ADV=광고 시작, BLE_CONN=폰 GATT 연결 수신,
  OOB_DONE=PHONE_INFO Write 수신·검증 통과 (제안 그대로).
- 예외 신설 (§7-16~18): Write 미수신 타임아웃 → 수동 DST_MAC 폴백, 연결 끊김 → 광고 재개,
  검증 실패 → 로그만 (WoR 이라 통보 불가).
- 다중 폰 동시 연결은 범위 밖 (1 연결 가정, §9-5) — multicast 후속 spec 에서.
