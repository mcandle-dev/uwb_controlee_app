# BLE OOB 인터페이스 사양서 — 폰 주소 자동 교환

> **버전: v0.5** · 개정일 2026-08-12 · **마스터 위치: `uwb-console-kotlin/docs/oob/`** (사본: `uwb_controlee_app/docs/oob/`)
> **양쪽 리포에 동일 사본을 커밋하고, 개정 시 반드시 버전을 올려 양쪽을 함께 갱신할 것.**
>
> v0.2 까지의 마스터는 `radar_test_console/docs/oob/` 였다. Python 콘솔이 참조 전용으로 동결되면서
> (Kotlin 포팅 완료, 태그 `v1.0_controller_scanner`) 마스터를 이 리포로 **이관**한다. v0.2 원문은
> `_reference/docs/oob/` 에 남아 있으며 그 내용은 본 문서 "모드 1" 에 전부 승계됐다.
>
> **한 줄 정의:** 콘솔(uwb-console-kotlin)과 폰(uwb_controlee_app)이 BLE 로 UWB 세션 파라미터
> (주소·Session ID)를 자동 교환한다. v0.3 에서 교환 경로가 **3가지 모드**로 확장됐고,
> v0.5 에서 크로스 플랫폼(iOS) 대응을 위한 **모드 4 (GATT 역방향)** 가 추가된다.

---

## 0. v0.4 → v0.5 변경 요약

| | v0.4 | v0.5 |
|---|---|---|
| 모드 | 3모드 | **+ 모드 4 (GATT 역방향)** — 콘솔=peripheral(GATT 서버), 폰=central. **iOS 가 성립하는 유일한 자동 경로** |
| 페이로드 | OOB_INFO 7B (v1) | **동일 — 변경 없음** (빌더/파서 양 리포 재사용, P3) |
| 신규 상수 | — | BOARD_INFO 특성 `5F1D0004`(Read) · PHONE_INFO 특성 `5F1D0005`(Write Without Response). 서비스는 `5F1D0003` 재사용 |
| 신설 절 | — | §3-1(모드 4 GATT 스키마), §5-4(모드 4 콘솔 광고), §6 모드 4 시퀀스, **§10 iOS 적합성** |
| 모드 1~3 | — | **무변경 존치** (Android 브링업·회귀 경로) |

**개정 배경 (G0 — 2026-08-12 사용자 확정):** 최종 목표는 Android + iOS 폰 모두 지원이며,
충돌 시 iOS 기준으로 맞춘다. iOS 는 ① 광고에 Service Data/Manufacturer Data 를 실을 수
없고(모드 2·3 의 폰 송출 불가), ② 백그라운드 스캔에 Service UUID 목록 필터가 필수인데 기존
모드 2·3 광고에는 31B 예산 때문에 UUID 목록 AD 가 없으며(콜드 웨이크 불가), ③ 백그라운드
광고는 overflow 영역으로 밀려 발견 불가(모드 1 백그라운드 불가)다. 따라서 폰이 아무것도
송출하지 않는 모드 4 를 신설한다. 근거: 폰 리포 `docs/guide/ble_dev_guide.md` §5~§7,
콘솔 리포 `docs/handoff/HANDOFF_모드4_사양서개정_요청.md`.

### 0-1. (이력) v0.2 → v0.3 변경 요약

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
| In-Scope | 4모드 정의·짝 규칙, 광고 패킷 규격(Service Data / UUID 목록), 모드별 시퀀스·타임라인 매핑·예외, iOS 적합성(§10) |
| Out-of-Scope | 보안(여전히 평문 — 브링업 도구), FiRa CSML 협상, multicast 자동 구성(후속 spec), 폰 백그라운드 광고(모드 4 가 대체) |
| 하위 호환 | OOB 는 여전히 부가 경로. 모든 모드에서 실패 시 수동 입력 흐름이 그대로 동작해야 한다 |

## 2. 모드 정의 (v0.3 의 핵심)

**콘솔과 폰의 모드는 짝으로만 성립한다.** 짝이 어긋난 조합은 "스캔 0건/광고 없음" 으로 끝난다 (오류 아님 — §7-11).

| # | 콘솔 모드 | 폰 모드 | BLE 방향 | 내용 | 태그 예 |
|---|---|---|---|---|---|
| 1 | **SCANNER** (GATT central) | **ADVERTISE-GATT** (peripheral) | 폰→콘솔 | v0.2 그대로. 스캔→GATT 연결→OOB_INFO Read/Notify | `v1.0_controller_scanner` ↔ `v1.0_controlee_advertise` |
| 2 | **BEACON** (관찰) | **BEACON** (송출) | 폰→콘솔 | 폰이 OOB_INFO 를 광고 패킷에 실어 broadcast. 콘솔은 관찰만. **연결 없음** | |
| 3 | **ADVERTISE** (송출) | **SCANNER** (관찰) | **콘솔→폰** | 콘솔이 보드의 주소·Session ID 를 broadcast. 폰이 수신해 입력칸 자동 반영 | `v2.0_controller_beacon` ↔ `v2.0_controlee_scanner` |
| 4 | **GATT-SERVER** (peripheral) | **GATT-CLIENT** (central) | 양방향 (연결) | **v0.5 신설, iOS 대응.** 콘솔이 connectable 광고 + GATT 서버. 폰이 스캔·연결 → BOARD_INFO **Read** / PHONE_INFO **Write**. §3-1·§5-4 | |

- UWB 역할은 모든 모드에서 불변: **보드=controller/initiator, 폰=controlee/responder.** 바뀌는 것은 BLE 교환 방향뿐이다 (모드 4 도 동일 — P15).
- 모드 선택은 양쪽 앱 설정 화면에서 런타임으로 한다 (기본값: 콘솔=SCANNER, 폰=ADVERTISE-GATT — 검증된 v1 경로를 회귀 기준으로 보존).
- **모드 4 는 모드 3(ADVERTISE)을 대체하는 별도 선택지다 — 동시 운용하지 않는다** (§5-4).
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

**콘솔의 Phase 2 정체성은 송출자(광고로 폰을 부르는 쪽)다** — 콘솔(보드 쪽)이 고정 설치되어
광고를 내보내고, 접근하는 폰이 이를 수신해 세션에 참여한다. 콘솔 SCANNER 모드는 v1 회귀
기준으로만 유지한다. 폰의 백그라운드 웨이크(콜드 웨이크 → 자동 레인징)는 폰 리포
`specs/002-cold-wake` 의 범위이며, **v0.5 부터 그 트리거는 모드 4 의 connectable 광고다**
(v0.4 까지의 "ADV_INFO 광고 트리거" 안은 iOS 불성립으로 폐기 — §0·§10).

## 3. GATT 스키마 (모드 1 전용 — v0.2 §3 무변경)

| 항목 | 값 |
|---|---|
| **Service UUID** | `5F1D0001-9A8B-4C7D-B2E3-6F4A5D8C9B0A` |
| **OOB_INFO Characteristic UUID** | `5F1D0002-9A8B-4C7D-B2E3-6F4A5D8C9B0A` |
| OOB_INFO 속성 | Read + Notify (Write 없음) |
| 광고 페이로드 | Service UUID 포함 + (스캔 응답에) 기기 이름 |
| MTU / 보안 | 기본 23 로 충분 / 없음 (open read) |

### 3-1. GATT 스키마 (모드 4 전용 — 콘솔이 서버, v0.5 신설)

| 항목 | 값 |
|---|---|
| **Service UUID** | `5F1D0003-9A8B-4C7D-B2E3-6F4A5D8C9B0A` — 콘솔 방향 식별자 승계 (모드 3 의 Service Data UUID 와 같은 값. AD 종류가 달라 혼동 없음: 모드 3=Service Data, 모드 4=Service UUID 목록 — §5-4) |
| **BOARD_INFO Characteristic** | `5F1D0004-9A8B-4C7D-B2E3-6F4A5D8C9B0A` — **Read**. payload v1 7B (`uwb_address`=보드 MAC, `session_id` LE) |
| **PHONE_INFO Characteristic** | `5F1D0005-9A8B-4C7D-B2E3-6F4A5D8C9B0A` — **Write Without Response**. payload v1 7B (`uwb_address`=폰 주소) |
| 주소 재발급 | 연결 유지 중 폰이 PHONE_INFO 를 재 Write (모드 1 Notify 의 대응물) |
| MTU / 보안 | 기본 23 로 충분 (7B) / 없음 (§3 승계) |

- Write 타입은 **Write Without Response 로 확정** — iOS 백그라운드에서 왕복을 최소화한다.
  응답이 없으므로 콘솔은 검증 실패를 폰에 알릴 수 없다 → 실패는 콘솔 로그로만 남긴다 (§7-13 동형).

## 4. OOB_INFO 페이로드 (v1 — 7 bytes 고정, **모든 모드 공통, 무변경**)

| 오프셋 | 크기 | 필드 | 형식 | 예 |
|---|---|---|---|---|
| 0 | 1B | `protocol_version` | uint8, **v1 = 0x01** | `01` |
| 1 | 2B | `uwb_address` | 표시 순서 그대로 raw 2B. **"5F:DD" → `5F DD`** (반전 금지) | `5F DD` |
| 3 | 4B | `session_id` | uint32 **little-endian**. 42 → `2A 00 00 00` | `2A 00 00 00` |

- 모드 1·2: `uwb_address` = **폰의 UWB 주소** (controlee 가 자신을 알림)
- 모드 3: `uwb_address` = **보드의 MAC** (controller 가 자신을 알림 — 기본 `00:00`)
- 모드 4: BOARD_INFO 는 `uwb_address` = **보드의 MAC** (모드 3 과 동일 의미), PHONE_INFO 는 `uwb_address` = **폰의 UWB 주소** (모드 1·2 와 동일 의미)
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

### 5-4. 모드 4 콘솔 광고 (v0.5 신설 — payload 없는 발견용)

| 항목 | 값 | 이유 |
|---|---|---|
| 구성 | Flags 3B + **Complete 128-bit Service UUID 목록**(`5F1D0003`) 18B = **21B** | payload 없음 → 31B 예산에 10B 여유. **UUID 목록 AD 가 있어야 iOS 백그라운드 스캔 필터가 매치**된다 (모드 2·3 광고에는 없던 것) |
| connectable | **true** | 폰이 연결해 Read/Write (모드 2·3 의 false 와 반대) |
| 모드 / timeout | BALANCED / 0 | §5-3 승계 |
| 기기 이름 | 스캔 응답 | §5-1 승계 |
| Service Data | **없음** | 파라미터는 광고가 아니라 GATT 로 교환 |

- **모드 3(ADVERTISE)과 동시 운용하지 않는다** — 콘솔 모드는 4개 중 하나를 선택한다.
  동시 광고는 31B 예산·광고 슬롯 관리·짝 규칙을 복잡하게 만들 뿐 이득이 없어 대체 관계로 확정 (2026-08-12).
- 교차 매치 없음: 모드 4 광고에는 Service Data 가 없으므로 모드 3 폰(SCANNER, Service Data 필터)이 반응하지 않고,
  모드 3 광고에는 UUID 목록이 없으므로 모드 4 폰(Service UUID 필터)도 반응하지 않는다 → §7-11 짝 불일치 규칙이 그대로 적용된다.

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

### 모드 4 (콘솔 GATT-SERVER ↔ 폰 GATT-CLIENT) — v0.5 신설

```
콘솔                                    폰 (Android/iOS 공통)              보드(UCI)
 │ GATT 서버 기동 +                       │ [1회 등록] UUID 필터 스캔          │
 │ connectable 광고(5F1D0003) ─────────▶ │ (백그라운드면 OS 가 앱을 깨움)      │
 │ ◀───────────── GATT 연결 ──────────── │                                  │
 │ ◀──────────── BOARD_INFO Read ─────── │ 보드 MAC·SID 자동 반영             │
 │ ◀── PHONE_INFO Write(WoR) ─────────── │                                  │
 │ DST_MAC 확보 → start_ranging ──────────────────────────────────────────▶│ UCI 세션 시작
 │ ◀═════════ UWB DS-TWR 레인징 ══════════════════════════════════════════▶│
 │ (연결 유지 — 주소 재발급 시 폰이 재Write) │                                  │
```

### 6-1. 콘솔 세션 타임라인 매핑 (모드별)

| 단계 | 모드 1 (v0.2) | 모드 2·3 | 모드 4 (v0.5) |
|---|---|---|---|
| BLE_ADV | 스캔에서 Service UUID 발견 | 관찰 시작 후 첫 광고 발견 | **광고 시작** (송출자 관점 — 모드 3 콘솔과 동형) |
| BLE_CONN | GATT 연결 성공 | **광고 페이로드 수신 확정** (연결 없음 — 의미 재매핑) | 폰의 GATT 연결 수신 |
| OOB_DONE | OOB_INFO 수신+검증 통과 | 파싱·검증 통과 | PHONE_INFO Write 수신·검증 통과 |
| RANGING / ERR | 동일 | 동일 | 동일 |

## 7. 예외 처리 (v0.2 §7 승계 + 모드 2·3 추가)

v0.2 의 1~10 은 모드 1 에 그대로 적용된다. 추가:

| # | 상황 | 동작 |
|---|---|---|
| 11 | **모드 짝 불일치** (예: 콘솔 BEACON ↔ 폰 ADVERTISE-GATT) | 광고 형식이 달라 발견 못함 → "광고 없음" 안내에 **모드 확인 문구 추가** ("양쪽 모드가 짝인지 확인"). 오류 아님 |
| 12 | 광고 캐시로 죽은 광고 잔존 | `rssi`/`ts` 로 필터 (v1 `OobPeripheral` 필드가 이미 존재하는 이유) |
| 13 | Service Data 길이 < 7B | ERR,REASON:OOB_PARSE + raw hex 로그 (§7-4 동일) |
| 14 | 모드 3 에서 콘솔이 폰 광고 미수신 | 레인징 시작 불가 안내 + **수동 DST_MAC 입력 폴백** |
| 15 | 폰 BEACON 중 주소 재발급 | 새 페이로드로 광고 교체 (Notify 대응물). 콘솔은 내용 변화 감지 시 입력칸 갱신 + 로그 |
| 16 | **모드 4: 연결은 됐으나 PHONE_INFO Write 미수신** (타임아웃) | 레인징 시작 불가 안내 + **수동 DST_MAC 입력 폴백** (§7-14 동형) |
| 17 | **모드 4: GATT 연결 끊김** | 콘솔은 **광고를 재개**해 재발견 가능 상태로 복귀 (모드 1 재스캔 대응물). 레인징 중이면 UWB 는 무영향 (BLE≠UWB) |
| 18 | **모드 4: PHONE_INFO 검증 실패** | Write Without Response 라 폰에 알릴 수 없다 → ERR,REASON:OOB_PARSE + raw hex 콘솔 로그만 (§7-13 동형). 기존 유효 값은 유지 |

## 8. 검수 기준 (E2E — v0.2 8항목에 추가)

10. 콘솔 BEACON ↔ 폰 BEACON: 폰 Start 후 콘솔 관찰 시작 → **3초 이내** 주소 자동 반영 (GATT 왕복이 없으므로 v0.2 의 10초보다 짧게 잡는다).
11. 모드 1 조합(콘솔 SCANNER ↔ 폰 ADVERTISE-GATT)이 v0.2 검수 1~9 를 그대로 통과한다 (회귀 없음).
12. 콘솔 ADVERTISE ↔ 폰 SCANNER: 폰 입력칸에 보드 MAC·Session ID 가 자동 반영되고 레인징이 성립한다.
13. 짝이 어긋난 모드 조합에서 앱이 죽지 않고 §7-11 안내가 뜬다.
14. 모드 2·3 광고 총 길이가 31B 이하다 (adb 로그 또는 nRF Connect 로 확인).
15. 콘솔 GATT-SERVER ↔ 폰 GATT-CLIENT: 폰이 연결·Read/Write 교환 후 콘솔에 폰 주소가 자동 반영되고 레인징이 성립한다.
16. 모드 4 콘솔 광고가 Flags + 128-bit UUID 목록(21B) 구성으로 송출되고 nRF Connect 에서 **connectable** 로 보인다 (Service Data 없음).
17. 모드 4 에서 폰이 송출하는 광고·Service Data 가 **0건**이다 (§10 — iOS 성립 조건의 계약 검증).
18. 모드 4 연결 끊김 후 콘솔이 광고를 재개해 폰이 재발견·재연결할 수 있다 (§7-17).

## 9. 리스크

1. **광고 31B 예산 소진** — Service Data 128-bit 방식은 여유가 없다. 필드 추가 요구가 생기면 16-bit UUID 발급 또는 v2 페이로드(버전 업)를 사양서 개정으로 먼저 결정한다.
2. **스캔 권한 매트릭스** — Android 12+: 콘솔 관찰은 `BLUETOOTH_SCAN`(+`neverForLocation`), 송출은 `BLUETOOTH_ADVERTISE`. 폰은 v1 에서 이미 ADVERTISE/CONNECT 를 확보했고 SCANNER 모드에 `BLUETOOTH_SCAN` 이 추가로 필요하다.
3. **병행 송출·관찰(모드 3)** — 일부 칩셋은 advertiser+scanner 동시 동작에 제약. 실패 시 §7-14 수동 폴백으로 종결, UWB 는 무영향.
4. **스펙 드리프트** — 양 리포 사본 + 개정 시 버전 업 + 동시 커밋 + **submodule pin bump 동반** (`docs/repo_guide.md` 원자 규칙). UUID·페이로드 상수는 코드 상수 파일(`ble/OobParams.kt` / `UwbDefaults.kt`)로만 참조.
5. **콘솔 peripheral 역할 (모드 4)** — `BluetoothGattServer` + connectable 광고 동시 동작은 칩셋별 제약 가능성이 있다. 실패 시 §7-16 수동 폴백으로 종결, UWB 는 무영향. 다중 폰 동시 연결은 이 버전 범위 밖 (1 연결 가정 — multicast 후속 spec).
6. **iOS 백그라운드 정책** — pending connect/State Restoration 의 실제 웨이크 지연·성공률은 iOS 실기기에서만 검증 가능. 계약(§10)은 성립 조건을 보장할 뿐, 검증은 iOS 앱 리포 첫 스파이크 몫.

## 10. iOS 적합성 (v0.5 신설)

**계약 수준 보장: 모드 4 에서 폰이 송출하는 광고·Service Data 는 0건이다.** 폰은 central
역할만 하므로 CoreBluetooth 의 송출 제약에 걸리지 않는다 — 이것이 iOS 성립의 조건이다.

| 모드 | 폰 광고 송출 | 폰 Service Data | iOS 성립 | 판정 |
|---|---|---|---|---|
| 1 (SCANNER ↔ ADVERTISE-GATT) | 있음 | 없음 | 포그라운드 한정 — 백그라운드 광고는 overflow 영역으로 밀려 Android 콘솔이 발견 불가 | **Android 전용** |
| 2 (BEACON ↔ BEACON) | 있음 | **있음** | 불가 — iOS 는 광고에 Service Data 를 실을 수 없다 | **Android 전용** |
| 3 (ADVERTISE ↔ SCANNER) | 있음 (병행 송출 §2-1) | **있음** | 불가 — 병행 송출이 Service Data 기반 | **Android 전용** |
| **4 (GATT-SERVER ↔ GATT-CLIENT)** | **없음** | **없음** | **성립** — UUID 목록 광고 → 백그라운드 필터 매치 + pending connect | **크로스 플랫폼** |

- iOS 백그라운드 대응물: State Restoration / pending connect (폰 리포 `docs/guide/ble_dev_guide.md` §5-2·§7-3).
- 모드 1~3 은 Android 브링업·회귀 경로로 **무변경 존치**한다.

## 11. 개정 이력

| 버전 | 일자 | 내용 |
|---|---|---|
| v0.1 | 2026-07-13 | 최초 작성 (주소+SessionID+버전 / 마스터 radar_test_console) |
| v0.2 | 2026-07-13 | 연결 방식 선택(수동/OOB 자동) 추가 — 광고는 OOB 모드에서만 |
| v0.3 (Draft) | 2026-08-11 | **3모드 도입**(GATT/Beacon/Advertise), 광고 Service Data 규격, ADV_INFO UUID `5F1D0003` 신설, 마스터를 uwb-console-kotlin 으로 이관. 페이로드 v1 무변경. 근거: 콘솔 spec 008 |
| v0.4 | 2026-08-12 | §2-1 **병행 송출 확정**(Draft 해제 — 폰 T002/콘솔 T004), §2-2 역할 정리 신설(콘솔=송출·관찰자, 폰 cold-wake 는 폰 spec 002 — 계약 무변경). 바이트 레이아웃 무변경 |
| **v0.5** | 2026-08-12 | **모드 4 (GATT 역방향) 신설** — 콘솔=GATT-SERVER(`5F1D0003` 서비스 + BOARD_INFO `5F1D0004` Read / PHONE_INFO `5F1D0005` Write Without Response), 폰=GATT-CLIENT. 콘솔 광고=Flags+128-bit UUID 목록 21B connectable(§5-4), §10 iOS 적합성 신설. 모드 3 과 대체 관계(동시 운용 안 함). payload v1 7B·모드 1~3 무변경. 근거: G0(iOS-first), 폰 handoff — 콘솔 spec 009 |
