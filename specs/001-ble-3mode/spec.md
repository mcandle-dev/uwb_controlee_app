# 001 — BLE OOB 3모드 (ADVERTISE-GATT / BEACON / SCANNER)

작성일 2026-08-11. **이 리포 최초의 SDD spec** — Phase 2 의 폰 쪽 절반이다.
constitution 제정(P1~P15)을 수반한다. v1 까지의 이력은 `docs/`(CHANGELOG·작업일지)에 있다.

입력 문서: `docs/handoff/HANDOFF_008_controlee_scanner.md` (콘솔 세션이 전달)
계약 원문: `docs/oob/BLE_OOB_인터페이스_사양서.md` **v0.3** (마스터는 콘솔 리포 — P5)

## 목적

BLE OOB 교환을 1가지(GATT peripheral)에서 **3모드 런타임 선택**으로 확장한다.

| # | 폰 모드 | 짝(콘솔 모드) | 방향 | 폰이 할 일 |
|---|---|---|---|---|
| 1 | **ADVERTISE-GATT** (현행 v1, 기본값) | SCANNER | 폰→콘솔 | 무변경 — `OobGattServer` 그대로. 회귀 기준 |
| 2 | **BEACON** (송출) | BEACON | 폰→콘솔 | OOB_INFO 7B 를 Service Data(`5F1D0001`)에 실어 광고. GATT 없음, connectable=false |
| 3 | **SCANNER** (관찰) | ADVERTISE | **콘솔→폰** | Service Data(`5F1D0003`) 스캔 → 보드 MAC·Session ID 자동 반영. 자신의 OOB_INFO 는 BEACON 으로 병행 송출(§2-1 미결) |

UWB 역할 불변 (P15). 완성 시 짝 태그: `v2.0_controlee_scanner` ↔ `v2.0_controller_beacon` (P12).

## 범위

### 포함
- 사양서 v0.3 모드 2·3 의 폰 측 구현 (`uwb/` 안에서만 — P1)
- 메인 화면에 OOB 모드 선택 + 영속화 (기본 ADVERTISE-GATT)
- `BLUETOOTH_SCAN` 권한 (신규, `neverForLocation`) — 기존 ADVERTISE/CONNECT 에 추가
- Start 시퀀스의 모드별 분기 (v1 "OOB Read 후 UWB 시작" 규칙이 모드 2·3 에선 성립 안 함)

### 제외
- 콘솔 쪽 구현 (콘솔 리포 spec 008 — P13)
- multicast (후속), 광고 payload 필드 추가(31B 소진 — 사양서 개정 선행), 보안

## 계약 요점 (진실원천은 사양서 §4~5)

- payload v1 7B **무변경** — 기존 빌더/파서 재사용 (P3)
- 방향은 UUID 로 구분: 폰 송출 `5F1D0001` / 콘솔 송출 `5F1D0003` (→ `UwbDefaults.kt` 상수 추가)
- 광고 = Flags 3B + ServiceData128 25B = 28B ≤ 31B. **여유 0 — 필드 추가 금지** (P5 절차로만)
- 모드 2·3 광고는 `connectable=false`, BALANCED, timeout 0
- 짝 불일치 조합은 오류가 아니라 "발견 못함" — 안내 문구로 종결 (사양서 §7-11)

## 수용 기준

1. 모드 1 이 v1 과 동작 동일 — 기존 검수(사양서 §8 1~9) 회귀 없음, `OobGattServer.kt` diff 없음.
2. 모드 2: Start 시 GATT 서버 없이 Service Data 광고가 뜨고(nRF Connect 확인), UWB 는 Read 대기 없이 시작된다.
3. 모드 3: 콘솔 광고 수신 시 보드 MAC·Session ID 입력칸이 자동 반영되고 레인징이 성립한다.
4. 모든 모드에서 BLE 실패가 UWB 수동 흐름을 막지 않는다 (P6) — 권한 거부·광고 실패 시나리오 포함.
5. 세션 자동 실패 시 모드 2 는 광고 갱신(새 주소 payload 로 교체 — Notify 대응물, 사양서 §7-15)이 동작한다 (P7).
6. `ui/` 에 bluetooth/uwb import 0건 (P1). `gradlew test`·`assembleDebug` green.
7. `[needs-device]` 사양서 §8 검수 10~14 실물 페어 통과 (P9 — 그전까지 "동작" 단정 금지).

## 미해결

- **사양서 §2-1 (Draft)**: 모드 3 의 "자신 OOB_INFO 병행 송출" 여부 — 콘솔 spec 008 T004 에서
  사람이 확정한다. 확정 전 모드 3 구현(T3xx)은 착수 금지, 모드 2 까지는 무관하게 진행 가능.
- 모드 선택 UI 의 위치(메인 화면 vs 별도 설정) — plan D3 에서 결정.
