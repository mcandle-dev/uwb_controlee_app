# HANDOFF — Phase 2: controlee 3모드 (BEACON 송출 / SCANNER 관찰)

> 작성 2026-08-11, 콘솔 세션(`D:\dev\mcandle\uwb-console-kotlin`)이 폰 세션(이 리포)에 전달.
> **이 문서와 함께 `docs/oob/BLE_OOB_인터페이스_사양서.md` 가 v0.2 → v0.3 으로 갱신되어 들어왔다.**
> 사양서가 유일한 바이트 계약이다 — 이 문서는 안내일 뿐, 충돌 시 사양서가 이긴다.
> 이 두 파일의 커밋은 이 리포 세션의 몫이다 (콘솔 세션은 파일만 배치, 커밋하지 않았다).

## 1. 배경 — 무엇이 결정됐나

- Phase 1 완료 태그가 양 리포에 붙었다: `v1.0_controller_scanner`(콘솔) ↔ `v1.0_controlee_advertise`(이 리포).
  태그 규칙 `v<M>.<m>_<UWB역할>_<BLE역할>` — 이름이 짝을 명시한다.
- Phase 2: BLE OOB 교환을 **3모드**로 확장한다 (2026-08-11 사용자 확정). UWB 역할은 불변(폰=controlee/responder).
- 콘솔 쪽은 `specs/008-ble-beacon/` 으로 진행 중. 완성 시 짝 태그:
  **`v2.0_controller_beacon`(콘솔) ↔ `v2.0_controlee_scanner`(이 리포)**.
- 새 리포·장수 v2 브랜치 금지, main 단일 trunk + feature 브랜치. 상세: 콘솔 리포 `docs/repo_guide.md`.

## 2. 이 리포가 구현할 것 (폰 3모드)

| # | 폰 모드 | 짝(콘솔 모드) | 방향 | 폰이 할 일 |
|---|---|---|---|---|
| 1 | **ADVERTISE-GATT** (현행 v1) | SCANNER | 폰→콘솔 | **무변경** — `OobGattServer` 그대로. 회귀 기준 |
| 2 | **BEACON** (송출) | BEACON | 폰→콘솔 | OOB_INFO 7B 를 **Service Data(`5F1D0001`)** 에 실어 광고. GATT 서버 없음, `connectable=false` |
| 3 | **SCANNER** (관찰) | ADVERTISE | 콘솔→폰 | Service Data(**`5F1D0003`**) 광고를 스캔 → 보드 MAC·Session ID 입력칸 자동 반영. **동시에 자신의 OOB_INFO 를 모드 2 로 송출(병행)** — 콘솔이 DST_MAC 을 얻는 경로 |

⚠ **모드 3 의 병행 송출은 사양서 §2-1 미결(Draft)이다.** 콘솔 쪽 tasks T004 에서 사람이 확정한다.
구현 순서를 모드 2 먼저로 잡으면 뒤집혀도 손실이 없다 (콘솔도 같은 순서).

## 3. 계약 요점 (사양서 v0.3 — 반드시 원문 §4~5 를 읽을 것)

- **payload 는 v1 7B 그대로.** 기존 `OobPayload`/`UwbDefaults` 의 빌더 재사용 — 파서·빌더 무변경 목표.
  (version `0x01` + address 2B raw 반전금지 + sessionId u32 **LE**)
- 방향은 **UUID 로 구분**: 폰 송출 = `5F1D0001`, 콘솔 송출 = `5F1D0003-9A8B-4C7D-B2E3-6F4A5D8C9B0A` (신규 상수 → `UwbDefaults.kt` 에 추가).
- 광고 구조: Flags 3B + ServiceData128(1+1+16+7) = **28B ≤ 31B. 여유 0 — 필드 추가는 사양서 개정 선행.**
  Service UUID 목록 AD 는 넣지 않는다(예산 초과). 이름은 스캔 응답에.
- 광고 파라미터: `ADVERTISE_MODE_BALANCED`, `connectable=false`(모드 2·3), timeout 0.
- 모드 2 는 v1 광고와 Service Data 유무로 구분된다 — v1 콘솔이 봐도 오동작 없음.

## 4. 구현 힌트 (이 리포 기준)

- `OobGattServer.kt` 의 advertiser 부분(`startAdvertising()`)이 모드 2 의 출발점 —
  `AdvertiseData.Builder().addServiceData(ParcelUuid(OOB_SERVICE_UUID), payload)` 로 바꾸고 GATT 서버를 열지 않는다.
- 모드 3 스캔: `ScanFilter.Builder().setServiceData(ParcelUuid(ADV_INFO_UUID), null)` —
  **`BLUETOOTH_SCAN` 권한이 신규로 필요**하다 (`neverForLocation` 플래그, v1 은 ADVERTISE/CONNECT 만 보유).
- 스캔 캐시 함정: 같은 기기 광고가 합쳐 보고될 수 있다 — rssi/ts 로 죽은 광고 필터 (사양서 §7-12).
- 모드 선택은 런타임(설정/토글), 기본값 = **ADVERTISE-GATT** (검증된 v1 경로가 회귀 기준, 사양서 §2).
- 모드 전환은 레인징 중 금지 (v0.2 규칙 0 승계). BLE 실패는 UWB 를 절대 막지 않는다 (기존 `becomeUnavailable` 패턴).

## 5. 검수 (사양서 §8 — 폰 쪽 몫)

- 검수 10: 폰 BEACON ↔ 콘솔 BEACON — 콘솔 관찰 시작 후 3초 내 주소 자동 반영
- 검수 11: 모드 1 회귀 없음 (v0.2 검수 1~9)
- 검수 12: 폰 SCANNER — 보드 MAC·SID 자동 반영 + 레인징 성립
- 검수 13: 짝 불일치 조합에서 죽지 않고 안내
- 검수 14: 광고 31B 이하 (nRF Connect)

## 6. 완료 시 절차 (크로스 리포 원자 규칙 — repo_guide §6)

1. 이 리포 push → 태그 `v2.0_controlee_scanner`
2. 콘솔 세션에 완성 커밋 SHA 통보 → 콘솔이 submodule pin bump (같은 PR) + `v2.0_controller_beacon` 태그
3. 사양서를 고쳤다면(§2-1 확정 등) **버전 업 + 양 리포 동시 커밋** 필수

## 7. ⚠ 지금 이 리포에서 어긋나 있는 것 (콘솔 세션이 발견)

- 로컬 HEAD `6871967` (fix 2건: 재Start 연결 초기화)가 원격 main(`38ca0c8`)보다 앞서 있고
  **태그 `v1.0_controlee_advertise` 는 fix 이전 커밋(38ca0c8)에 붙어 있다.**
  push 후 태그를 fix 포함 지점으로 옮길지(재태그) 사람이 결정해야 한다 —
  옮기면 콘솔의 submodule pin(현재 38ca0c8)도 함께 bump.
