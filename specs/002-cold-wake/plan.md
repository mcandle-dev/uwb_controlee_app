# 002 — 구현 계획 (plan) · 전면 개정 2026-08-12 (모드 4 기반)

> 이전 판(Android PendingIntent + payload 광고)은 G0 확정(iOS 지원, 충돌 시 iOS 기준)으로
> 폐기 — git 이력 참조. 폐기 근거: iOS 는 현 광고 포맷을 백그라운드에서 매치할 수 없고
> (가이드 §5-3), payload 송출 자체가 불가(§6). **iOS 를 기준으로 맞춘다** 는 결정에 따라
> 교환 구조를 모드 4 로 바꾼다.

## 설계 결정

### D1. 교환 구조 = 모드 4 (GATT 역방향) — G0 로 확정

| 방식 | 평가 |
|---|---|
| **(A) 모드 4: 콘솔=peripheral, 폰=central, Read/Write** ✅ | 광고는 발견 전용 21B(UUID 목록 포함 → iOS 백그라운드 필터 가능), 데이터는 GATT. 폰이 central 이라 iOS 의 송출 제약·overflow 문제 전부 회피. Android 도 HW UUID 필터로 단순해짐 |
| (B) 이전 판: payload 광고 + PendingIntent | iOS 성립 불가 — G0 위반. 폐기 |
| (C) 16비트 UUID 로 광고 개조 | iOS 백그라운드 매치는 되지만 송출(폰 주소)이 여전히 불가 + SIG 할당 문제. 탈락 |

### D2. 계약 선행 — 사양서 v0.5 개정이 코드보다 먼저 (P5)

Write 특성 신설·콘솔 GATT 서버·connectable=true 는 전부 계약 변경이다.
- 마스터는 콘솔 리포 → `docs/handoff/HANDOFF_모드4_사양서개정_요청.md` 로 개정 요청 (P13)
- 확정 전 이 리포가 할 수 있는 것: 조정자 리팩터(Phase 1 — 계약 무관), JVM 테스트 정비
- UUID·특성 상수는 확정 후 `UwbDefaults.kt` 에만 추가 (기존 D4 규칙 승계)

### D3. 폰 쪽 채널 = `OobCentral` 신설 (기존 채널 0줄)

`OobGattServer`(모드 1)·`OobBeacon`(모드 2)·`OobScanner`(모드 3)와 같은 계약을 복제:
`open()/close()/status: StateFlow<OobStatus>` + 실패 무전파 (P6).
scan → connect → discoverServices → BOARD_INFO Read → PHONE_INFO Write 를 내부 상태머신으로.
`OobMode` enum 에 `CENTRAL` 추가 (storageValue 하위호환 규칙 동일).

### D4. 조정 로직 추출 (`RangingCoordinator`) — G1 게이트 유지

콜드 웨이크는 Activity 없이 시퀀스를 돌려야 하므로 이전 판의 D2 를 승계한다:
Start 시퀀스·워치독·OOB 수명을 ViewModel 에서 프로세스 싱글턴으로 추출, ViewModel 은
구독자로. **동작 무변경 리팩터로 먼저** 수행하고 기존 JVM 테스트 무수정 green (P10).
버린 대안(FGS 가 시퀀스 일부 복제)의 탈락 사유도 승계 — 조정자 이중화는 P2 실질 위반.

### D5. 콜드 웨이크 등록 경로

```
Android: 토글 ON → PendingIntent 스캔 등록 (ScanFilter.setServiceUuid — HW 필터,
         이전 판의 Service Data SW 필터 불필요) → Receiver → FGS(ACTION_AUTO_START)
         → coordinator 가 모드 4 시퀀스
iOS(후속 리포): 동일 계약 위에서 pending connect / State Restoration — 이 리포 범위 밖,
         계약이 이를 막지 않는지만 spec 수용 5 로 검증
```

### D6. 모드 1~3 존치, 모드 4 는 추가

기존 모드는 Android 브링업·회귀 기준으로 유지한다 (spec 001 검수가 기준선 — G2).
기본값도 ADVERTISE_GATT 유지. 모드 4 가 실기기에서 안정되면 기본값 전환을 **별도 결정**으로.

## 영향 범위 (예상)

| 파일 | 변경 |
|---|---|
| `uwb/RangingCoordinator.kt` | **신규** — 조정 로직 추출 (D4) |
| `MainViewModel.kt` | 수정 — coordinator 위임 |
| `uwb/OobCentral.kt` | **신규** — 모드 4 채널 (D3) |
| `uwb/OobMode.kt` · `UwbDefaults.kt` | 수정 — CENTRAL 추가·v0.5 상수 (D2 확정 후) |
| `uwb/OobWakeReceiver.kt` | **신규** — PendingIntent 수신 → FGS (D5) |
| `RangingForegroundService.kt` | 수정 — ACTION_AUTO_START |
| `ui/MainScreen.kt` | 수정 — 모드 4 항목·자동 감시 토글 |
| 기존 OOB 채널 3종·payload 빌더/파서 | **0줄** |

## 검증 전략

- JVM: coordinator 리팩터는 기존 테스트 무수정 green (P10), 모드 4 상태머신·payload 재사용 테스트
- 실기기: 수용 1~4 `[needs-device]` — 콘솔 v0.5 구현과 합동
- iOS 적합성: 코드가 아니라 **계약 문서 검증** (수용 5) — 폰 송출 광고 0건 확인

## 리스크

- **R1.** 콘솔 작업량 — GATT 서버는 콘솔에 신규 역할 (콘솔 세션 일정에 종속). 완화:
  handoff 에 제안 계약을 상세히 실어 왕복을 줄인다
- **R2.** Android FGS 백그라운드 시작 예외 미확인 (T304 스파이크). 불허 시 Arm 후퇴 (기존 합의)
- **R3.** iOS 실검증 공백 — iOS 앱이 없는 동안 계약의 iOS 적합성은 문서 검증뿐 (P9).
  iOS 리포 신설 시 첫 태스크 = 백그라운드 복원·UWB 스파이크
- **R4.** iOS 구조적 한계는 남는다: 사용자 강제 종료 시 미복원·재부팅 후 1회 실행 필요 —
  기능 축소가 아니라 **사용자 안내**로 종결 (가이드 §7-5)
- **R5.** D4 리팩터 회귀 — G2(001 검수)로 기준선 확보 후 착수 (승계)
