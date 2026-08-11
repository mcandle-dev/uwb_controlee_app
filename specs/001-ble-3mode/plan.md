# 001 — 구현 계획 (plan)

spec.md 의 "무엇/왜" 를 **어떻게** 만들지 정한다. tasks.md 는 이 계획을 태스크로 쪼갠 것이다.

## 설계 결정

### D1. `OobGattServer` 는 건드리지 않는다 — 모드별 클래스 신설

| 방식 | 평가 |
|---|---|
| **(A) 기존 클래스 무변경 + 모드별 신설** ✅ | `OobGattServer`(모드 1, 회귀 기준) 그대로. `OobBeacon`(모드 2 송출), `OobScanner`(모드 3 관찰) 신설. ViewModel 이 모드로 선택 |
| (B) `OobGattServer` 에 mode 파라미터 | 검증된 391줄 GATT 코드에 분기가 파고든다 — 회귀 위험. 탈락 |
| (C) 공통 인터페이스 + 구현 3종 | 콘솔의 P5 같은 교체 제약이 이 리포엔 없다. bring-up 도구에 과설계 (P14). 탈락 |

콘솔이 (A)-인터페이스유지를 택한 것과 결이 다른 이유: 콘솔은 `BleOobChannel` 인터페이스가
헌법(P5)으로 고정돼 있고, 폰은 ViewModel 이 이미 유일한 조정자다 — 각자의 제약을 따른다.

공통 계약만 맞춘다: 두 신설 클래스 모두 `open()/close()/status: StateFlow<OobStatus>` +
"실패를 밖으로 던지지 않는다"(P6) — `OobGattServer` 의 기존 시그니처·`becomeUnavailable` 패턴 복제.

### D2. Start 시퀀스의 모드별 분기 (이 spec 의 실제 난점)

v1 규칙 "BLE 먼저 → OOB Read 후 UWB 시작"(af562e4, 30초 타임아웃)은 Read 가 존재하는 모드 1 전용이다.

| 모드 | Start 시퀀스 |
|---|---|
| 1 (GATT) | 현행 유지 — 광고→Read 대기(30s)→UWB. **분기 코드도 기존 경로를 그대로 지나가야 한다** |
| 2 (BEACON) | 광고 시작 + **UWB 즉시 시작** (Read 가 없다 — 콘솔이 언제 봤는지 폰은 모른다). 세션 자동실패 시 광고 payload 갱신 유지 (P7) |
| 3 (SCANNER) | 스캔 시작 → 콘솔 광고 수신 → 보드 MAC·SID 반영 → UWB 시작. 미수신 30초(기존 `OOB_READ_WAIT_TIMEOUT_MS` 재사용) 후 수동 입력값 폴백 |

분기는 `MainViewModel` 의 Start 함수 한 곳에만 둔다 (조정자 단일 — P2).

### D3. 모드 선택은 메인 화면 드롭다운 + SharedPreferences

별도 설정 화면을 만들지 않는다 (P14 — 단일 화면 원칙 유지). 보드 MAC·Session ID 입력칸
옆에 모드 선택을 배치 — 콘솔과 폰을 나란히 놓고 짝을 맞추는 사용 장면에서 한 화면에 보여야 한다.
키 `oob_mode`, 기본 `ADVERTISE_GATT` (검증된 v1 경로 — 사양서 §2). 레인징 중 변경 금지 (사양서 규칙 0).

### D4. 상수는 `UwbDefaults.kt` 에만 추가 (P5)

```kotlin
val ADV_INFO_UUID: UUID = ...("5F1D0003-9A8B-4C7D-B2E3-6F4A5D8C9B0A") // 콘솔→폰 (사양서 v0.3 §5-2)
const val SCAN_WAIT_TIMEOUT_MS = 30_000L  // 모드 3 폴백 (기존 OOB_READ_WAIT 와 동일값)
```
payload 빌더·파서는 무변경 (spec 계약 요점).

### D5. 권한 — 모드 3 선택 시 `BLUETOOTH_SCAN` 요청

Manifest 에 `BLUETOOTH_SCAN`(`neverForLocation`) 추가. 런타임 요청은 기존 패턴
(Start 때 일괄 — 다이얼로그 연속 발사 금지 규칙 승계)에 SCAN 을 합류시키되,
모드 1·2 에서는 요청하지 않는다 (불필요 권한 요구 금지 — P6 의 정신).

### D6. 스캔 캐시 필터 (모드 3)

같은 콘솔의 광고가 합쳐 보고될 수 있다 — rssi/timestamp 로 죽은 광고 제거 (사양서 §7-12).
payload 내용이 직전 수신과 같으면 재반영하지 않는다 (입력칸 깜빡임 방지).

## 영향 범위

| 파일 | 변경 |
|---|---|
| `uwb/OobBeacon.kt` | **신규** — 모드 2 송출 (D1) |
| `uwb/OobScanner.kt` | **신규** — 모드 3 관찰 (D1/D6) |
| `uwb/UwbDefaults.kt` | 수정 — `ADV_INFO_UUID` 등 (D4) |
| `MainViewModel.kt` | 수정 — 모드 상태·Start 분기 (D2)·영속화 (D3) |
| `ui/MainScreen.kt` | 수정 — 모드 선택 UI (D3) |
| `AndroidManifest.xml` | 수정 — `BLUETOOTH_SCAN` (D5) |
| `uwb/OobGattServer.kt` · payload 빌더/파서 | **0줄** (D1 — spec 수용기준 1) |

## 검증 전략

- JVM: payload 재사용 검증(기존 `OobPayloadTest` 무수정 green — P10), 모드 영속화,
  D6 필터 로직(순수 함수로 뽑아 테스트 — P3).
- `gradlew test` + `assembleDebug` + (UI 변경이므로) `lint`.
- 광고 프레임 구조·31B·실물 페어는 `[needs-device]` (P9) — nRF Connect + 콘솔 spec 008 쪽과 합동.

## 리스크

- **R1.** 모드 2 에서 UWB 즉시 시작 → 콘솔이 광고를 늦게 보면 폰 세션 10초 타임아웃(프레임워크
  자동 종료)이 먼저 올 수 있다 → 자동실패 후에도 광고가 유지되므로(P7) 콘솔이 붙는 순간
  재Start 하면 된다 — 이 UX 를 로그·상태 문구로 안내. 근본 해결(광고 수신 확인 채널)은 커넥션리스에 없음.
- **R2.** §2-1 미결이 "병행 송출" 로 확정되면 모드 3 은 스캔+광고 동시 — 일부 칩셋 제약.
  실패 시 UNAVAILABLE + 수동 폴백 (P6). 구현 순서를 모드 2 먼저로 배치 (tasks 근거).
- **R3.** 콘솔 쪽(spec 008)과 드리프트 → 사양서 v0.3 만 계약. 완성 시 SHA 통보 → 콘솔 pin bump (P13).
