# BLE OOB 인터페이스 사양서 — 폰 주소 자동 교환

> **버전: v0.4** · 개정일 2026-08-12 · **마스터 위치: `uwb-console-kotlin/docs/oob/`** (사본: `uwb_controlee_app/docs/oob/`)
> **양쪽 리포에 동일 사본을 커밋하고, 개정 시 반드시 버전을 올려 양쪽을 함께 갱신할 것.**
>
> v0.2 까지의 마스터는 `radar_test_console/docs/oob/` 였다. Python 콘솔이 참조 전용으로 동결되면서
> (Kotlin 포팅 완료, 태그 `v1.0_controller_scanner`) 마스터를 이 리포로 **이관**한다. v0.2 원문은
> `_reference/docs/oob/` 에 남아 있으며 그 내용은 본 문서 "모드 1" 에 전부 승계됐다.
>
> **한 줄 정의:** 콘솔(uwb-console-kotlin)과 폰(uwb_controlee_app)이 BLE 로 UWB 세션 파라미터
> (주소·Session ID)를 자동 교환한다. v0.3 부터 교환 경로가 **3가지 모드**로 확장된다.

---

## 0. v0.2 → v0.3 변경 요약

| | v0.2 | v0.3 |
|---|---|---|
| 교환 경로 | GATT 연결 1가지 | **3모드**: GATT / Beacon(커넥션리스) / Advertise(방향 역전) |
| 페이로드 | OOB_INFO 7B (v1) | **동일 — 변경 없음** (파서 재사용) |
| GATT 스키마 | Service `5F1D0001` + Char `5F1D0002` | **동일 — 변경 없음** |
| 신규 상수 | — | ADV_INFO Service Data UUID `5F1D0003` (모드 3 전용) |
| 콘솔 구현 | Python bleak (central) | Kotlin `BleOobChannel` 구현체 (P5 인터페이스 뒤 교체) |

**하위 호환:** 모드 1(GATT)은 v0.2 와 바이트 단위로 동일하다. v1 콘솔 ↔ v0.3 폰(모드 1) 조합은 그대로 동작한다.

## 1. 목적 및 범위

| 구분 | 내용 |
|---|---|
| 목적 | v0.2 의 GATT 자동 교환에 더해, **연결 없는(커넥션리스) 교환**과 **콘솔 주도 송출**을 추가한다. 광고 수신 즉시 파라미터가 확보되므로 지연이 스캔+연결+read(수백 ms~수 초) → 광고 수신 즉시로 준다 |
| In-Scope | 3모드 정의·짝 규칙, 광고 패킷 규격(Service Data), 모드별 시퀀스·타임라인 매핑·예외 |
| Out-of-Scope | 보안(여전히 평문 — 브링업 도구), FiRa CSML 협상, multicast 자동 구성(후속 spec), 백그라운드 광고 |
| 하위 호환 | OOB 는 여전히 부가 경로. 모든 모드에서 실패 시 수동 입력 흐름이 그대로 동작해야 한다 |

## 2. 모드 정의 (v0.3 의 핵심)

**콘솔과 폰의 모드는 짝으로만 성립한다.** 짝이 어긋난 조합은 "스캔 0건/광고 없음" 으로 끝난다 (오류 아님 — §7-11).

| # | 콘솔 모드 | 폰 모드 | BLE 방향 | 내용 | 태그 예 |
|---|---|---|---|---|---|
| 1 | **SCANNER** (GATT central) | **ADVERTISE-GATT** (peripheral) | 폰→콘솔 | v0.2 그대로. 스캔→GATT 연결→OOB_INFO Read/Notify | `v1.0_controller_scanner` ↔ `v1.0_controlee_advertise` |
| 2 | **BEACON** (관찰) | **BEACON** (송출) | 폰→콘솔 | 폰이 OOB_INFO 를 광고 패킷에 실어 broadcast. 콘솔은 관찰만. **연결 없음** | |
| 3 | **ADVERTISE** (송출) | **SCANNER** (관찰) | **콘솔→폰** | 콘솔이 보드의 주소·Session ID 를 broadcast. 폰이 수신해 입력칸 자동 반영 | `v2.0_controller_beacon` ↔ `v2.0_controlee_scanner` |

- UWB 역할은 모든 모드에서 불변: **보드=controller/initiator, 폰=controlee/responder.** 바뀌는 것은 BLE 교환 방향뿐이다.
- 모드 선택은 양쪽 앱 설정 화면에서 런타임으로 한다 (기본값: 콘솔=SCANNER, 폰=ADVERTISE-GATT — 검증된 v1 경로를 회귀 기준으로 보존).
- 모드 전환은 레인징 중이 아닐 때만 허용 (v0.2 규칙 0 승계).

### 2-1. 모드 3 의 역방향 문제 — ✅ 병행 송출 확정 (2026-08-12, v0.4)

모드 3 에서 폰은 보드 주소·Session ID 를 광고로 받지만, **보드(controller)는 여전히 폰 주소(DST_MAC)가 필요하다.**
커넥션리스에는 응답 채널이 없으므로 **병행 송출**로 정의한다 (2026-08-12 사람 확정 —
폰 리포 spec 001 T002 / 콘솔 spec 008 T004):

- **콘솔 ADVERTISE 모드 = 송출 + 관찰 병행.** ADV_INFO(`5F1D0003`) 를 송출하면서 동시에 폰의 OOB_INFO(`5F1D0001`) 광고를 관찰한다.
- **폰 SCANNER 모드 = 관찰 + 송출 병행.** 콘솔 광고를 수신하면 입력칸을 반영하고, 자신의 OOB_INFO 를 BEACON 으로 송출한다 (모드 2 의 폰 동작 재사용).
- 폴백: 콘솔이 폰 광고를 못 받으면 기존 수동 입력으로 DST_MAC 을 넣는다.
- 검토했던 단순안(폰 주소 수동 입력 전용)은 자동화 목적에 미달해 기각. 병행안의
  전력·광고 혼선 리스크는 §9-3 으로 관리한다.

### 2-2. 역할 정리 (Phase 2 목표 시나리오)

**콘솔의 Phase 2 정체성은 송출·관찰자(BEACON/ADVERTISE)다** — 콘솔(보드 쪽)이 고정 설치되어
광고를 내보내고, 접근하는 폰이 이를 수신해 세션에 참여한다. 콘솔 SCANNER 모드는 v1 회귀
기준으로만 유지한다. 폰의 백그라운드 웨이크(비콘 수신 → 스캔 전환 → 자동 레인징)는
폰 리포 `specs/002-cold-wake` 의 범위이며 **이 사양서의 계약 변경은 없다** — 기존 ADV_INFO
광고가 그대로 트리거다.

## 3. GATT 스키마 (모드 1 전용 — v0.2 §3 무변경)

| 항목 | 값 |
|---|---|
| **Service UUID** | `5F1D0001-9A8B-4C7D-B2E3-6F4A5D8C9B0A` |
| **OOB_INFO Characteristic UUID** | `5F1D0002-9A8B-4C7D-B2E3-6F4A5D8C9B0A` |
| OOB_INFO 속성 | Read + Notify (Write 없음) |
| 광고 페이로드 | Service UUID 포함 + (스캔 응답에) 기기 이름 |
| MTU / 보안 | 기본 23 로 충분 / 없음 (open read) |

## 4. OOB_INFO 페이로드 (v1 — 7 bytes 고정, **모든 모드 공통, 무변경**)

| 오프셋 | 크기 | 필드 | 형식 | 예 |
|---|---|---|---|---|
| 0 | 1B | `protocol_version` | uint8, **v1 = 0x01** | `01` |
| 1 | 2B | `uwb_address` | 표시 순서 그대로 raw 2B. **"5F:DD" → `5F DD`** (반전 금지) | `5F DD` |
| 3 | 4B | `session_id` | uint32 **little-endian**. 42 → `2A 00 00 00` | `2A 00 00 00` |

- 모드 1·2: `uwb_address` = **폰의 UWB 주소** (controlee 가 자신을 알림)
- 모드 3: `uwb_address` = **보드의 MAC** (controller 가 자신을 알림 — 기본 `00:00`)
- 파서 규칙 v0.2 동일: 길이 ≥7B 만 검사, 추가 바이트 무시(전방 호환), `version>0x01` 은 v1 파싱 시도 + 경고.
- 바이트 순서 함정(STS IV 반전 전례) 경고도 그대로 유효하다.

## 5. 광고 패킷 규격 (모드 2·3 신규)

### 5-1. 배치: **Service Data (128-bit UUID)** — 확정 근거 포함

레거시 광고 31B 예산 계산:

| 방안 | 계산 | 판정 |
|---|---|---|
| **Service Data 128-bit** ✅ | Flags 3B + [len 1 + type(0x21) 1 + UUID 16 + payload 7] = **28B** | 들어감. 기존 UUID 재사용, 콘솔·폰 모두 `ScanFilter.setServiceData()` 로 필터 |
| Service UUID 목록 + Manufacturer Data | Flags 3 + UUID목록 18 + MSD 11 = 32B | **초과.** 탈락 |
| Manufacturer Data 단독 | Flags 3 + MSD 11 = 14B | 들어가지만 회사 ID(2B)가 없다 — 시험용 0xFFFF 는 필터 충돌 위험. 탈락 |

- Service Data AD 구조 자체에 UUID 가 들어 있으므로 **별도 Service UUID 목록은 광고에 넣지 않는다** (예산 초과).
- 기기 이름은 스캔 응답에 배치 (v1 폰 구현과 동일한 이유 — Android 는 임의 Local Name 지정 불가).
- **31B 에 남는 여유는 0B 에 가깝다. 페이로드에 필드를 추가하려면 이 사양서 개정이 선행돼야 한다.**

### 5-2. 방향 구분: UUID 로 한다 (페이로드는 그대로)

| 방향 | Service Data UUID | 송출자 | 페이로드 |
|---|---|---|---|
| 폰→콘솔 (모드 2) | `5F1D0001-…` (기존 Service UUID 재사용) | 폰 | OOB_INFO v1 7B (`uwb_address`=폰 주소) |
| 콘솔→폰 (모드 3) | **`5F1D0003-9A8B-4C7D-B2E3-6F4A5D8C9B0A`** (신규) | 콘솔 | OOB_INFO v1 7B (`uwb_address`=보드 MAC) |

- 페이로드에 방향/역할 바이트를 넣지 않고 UUID 로 구분한다 → **파서(`OobParser`/`OobPayload`)는 양쪽 리포 모두 무변경.**
- 모드 1 광고(bare Service UUID, GATT 연결 가능)와 모드 2 광고(Service Data 포함, 연결 불가 `connectable=false`)는
  Service Data 유무로 구분된다. v1 콘솔이 모드 2 광고를 발견해도 연결이 안 될 뿐 오동작하지 않는다.

### 5-3. 광고 파라미터

| 항목 | 값 | 이유 |
|---|---|---|
| 모드 | `ADVERTISE_MODE_BALANCED` (≈250ms) | v1 폰 구현 승계 — 절전 스로틀 회피 |
| connectable | 모드 2·3: **false** / 모드 1: true | 커넥션리스 명시. v1 콘솔의 오연결 시도 차단 |
| timeout | 0 (무제한) — Stop 시 명시 중지 | v1 승계 |

## 6. 시퀀스

### 모드 2 (콘솔 BEACON ↔ 폰 BEACON)

```
폰 앱                                  콘솔                              보드(UCI)
 │ Start (OOB 모드=BEACON)               │                                 │
 │ controleeSessionScope 확보            │                                 │
 │ Service Data 광고 시작 ─────────────▶ │ 관찰 시작(필터 5F1D0001)          │  → BLE_ADV
 │        (연결 없음)                    │ 광고 수신 = 즉시 파싱·검증         │  → BLE_CONN*
 │                                      │ 주소 입력칸 자동 반영             │  → OOB_DONE
 │                                      │ [토글 ON] start_ranging ───────▶ │  UCI 세션 시작
 │ ◀═════════ UWB DS-TWR 레인징 ══════════════════════════════════════════▶│  → RANGING
 │ (주소 재발급 시 새 광고로 갱신)         │ (관찰 유지 — 갱신 감시)           │
```

\* 타임라인 단계 `BLE_CONN` 은 모드 2·3 에서 실제 연결이 없으므로 **광고 수신 확정 시점**에 점등한다
(타임라인 UI·파서를 바꾸지 않기 위한 매핑 — §6-1).

### 모드 3 (콘솔 ADVERTISE ↔ 폰 SCANNER)

```
콘솔                                   폰 앱                              보드(UCI)
 │ ADVERTISE 시작(5F1D0003 송출          │ Start (OOB 모드=SCANNER)          │
 │  + 5F1D0001 관찰 병행)     ─────────▶ │ 광고 수신 → 보드MAC·SID 자동 반영  │
 │                                      │ 자신의 OOB_INFO 광고 송출(병행)    │
 │ 폰 광고 수신 → DST_MAC 확보 ◀──────── │                                  │
 │ start_ranging ───────────────────────────────────────────────────────▶ │ UCI 세션 시작
 │ ◀═════════ UWB DS-TWR 레인징 ══════════════════════════════════════════▶│
```

### 6-1. 콘솔 세션 타임라인 매핑 (모드별)

| 단계 | 모드 1 (v0.2) | 모드 2·3 |
|---|---|---|
| BLE_ADV | 스캔에서 Service UUID 발견 | 관찰 시작 후 첫 광고 발견 |
| BLE_CONN | GATT 연결 성공 | **광고 페이로드 수신 확정** (연결 없음 — 의미 재매핑) |
| OOB_DONE | OOB_INFO 수신+검증 통과 | 파싱·검증 통과 |
| RANGING / ERR | 동일 | 동일 |

## 7. 예외 처리 (v0.2 §7 승계 + 모드 2·3 추가)

v0.2 의 1~10 은 모드 1 에 그대로 적용된다. 추가:

| # | 상황 | 동작 |
|---|---|---|
| 11 | **모드 짝 불일치** (예: 콘솔 BEACON ↔ 폰 ADVERTISE-GATT) | 광고 형식이 달라 발견 못함 → "광고 없음" 안내에 **모드 확인 문구 추가** ("양쪽 모드가 짝인지 확인"). 오류 아님 |
| 12 | 광고 캐시로 죽은 광고 잔존 | `rssi`/`ts` 로 필터 (v1 `OobPeripheral` 필드가 이미 존재하는 이유) |
| 13 | Service Data 길이 < 7B | ERR,REASON:OOB_PARSE + raw hex 로그 (§7-4 동일) |
| 14 | 모드 3 에서 콘솔이 폰 광고 미수신 | 레인징 시작 불가 안내 + **수동 DST_MAC 입력 폴백** |
| 15 | 폰 BEACON 중 주소 재발급 | 새 페이로드로 광고 교체 (Notify 대응물). 콘솔은 내용 변화 감지 시 입력칸 갱신 + 로그 |

## 8. 검수 기준 (E2E — v0.2 8항목에 추가)

10. 콘솔 BEACON ↔ 폰 BEACON: 폰 Start 후 콘솔 관찰 시작 → **3초 이내** 주소 자동 반영 (GATT 왕복이 없으므로 v0.2 의 10초보다 짧게 잡는다).
11. 모드 1 조합(콘솔 SCANNER ↔ 폰 ADVERTISE-GATT)이 v0.2 검수 1~9 를 그대로 통과한다 (회귀 없음).
12. 콘솔 ADVERTISE ↔ 폰 SCANNER: 폰 입력칸에 보드 MAC·Session ID 가 자동 반영되고 레인징이 성립한다.
13. 짝이 어긋난 모드 조합에서 앱이 죽지 않고 §7-11 안내가 뜬다.
14. 모드 2·3 광고 총 길이가 31B 이하다 (adb 로그 또는 nRF Connect 로 확인).

## 9. 리스크

1. **광고 31B 예산 소진** — Service Data 128-bit 방식은 여유가 없다. 필드 추가 요구가 생기면 16-bit UUID 발급 또는 v2 페이로드(버전 업)를 사양서 개정으로 먼저 결정한다.
2. **스캔 권한 매트릭스** — Android 12+: 콘솔 관찰은 `BLUETOOTH_SCAN`(+`neverForLocation`), 송출은 `BLUETOOTH_ADVERTISE`. 폰은 v1 에서 이미 ADVERTISE/CONNECT 를 확보했고 SCANNER 모드에 `BLUETOOTH_SCAN` 이 추가로 필요하다.
3. **병행 송출·관찰(모드 3)** — 일부 칩셋은 advertiser+scanner 동시 동작에 제약. 실패 시 §7-14 수동 폴백으로 종결, UWB 는 무영향.
4. **스펙 드리프트** — 양 리포 사본 + 개정 시 버전 업 + 동시 커밋 + **submodule pin bump 동반** (`docs/repo_guide.md` 원자 규칙). UUID·페이로드 상수는 코드 상수 파일(`ble/OobParams.kt` / `UwbDefaults.kt`)로만 참조.

## 10. 개정 이력

| 버전 | 일자 | 내용 |
|---|---|---|
| v0.1 | 2026-07-13 | 최초 작성 (주소+SessionID+버전 / 마스터 radar_test_console) |
| v0.2 | 2026-07-13 | 연결 방식 선택(수동/OOB 자동) 추가 — 광고는 OOB 모드에서만 |
| v0.3 (Draft) | 2026-08-11 | **3모드 도입**(GATT/Beacon/Advertise), 광고 Service Data 규격, ADV_INFO UUID `5F1D0003` 신설, 마스터를 uwb-console-kotlin 으로 이관. 페이로드 v1 무변경. 근거: 콘솔 spec 008 |
| **v0.4** | 2026-08-12 | §2-1 **병행 송출 확정**(Draft 해제 — 폰 T002/콘솔 T004), §2-2 역할 정리 신설(콘솔=송출·관찰자, 폰 cold-wake 는 폰 spec 002 — 계약 무변경). 바이트 레이아웃 무변경 |
