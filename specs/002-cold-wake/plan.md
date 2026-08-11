# 002 — 구현 계획 (plan)

## 설계 결정

### D1. 등록 방식 = PendingIntent 스캔

| 방식 | 평가 |
|---|---|
| **(A) `BluetoothLeScanner.startScan(filters, settings, PendingIntent)`** ✅ | 프로세스가 죽어도 OS 가 스캔을 유지, 매치 시 명시적 브로드캐스트로 앱을 깨움. 콜드 웨이크의 유일한 표준 경로 |
| (B) ScanCallback + 상시 FGS | 대기(Arm) 방식 — 콜드 웨이크가 아님. D3 실패 시 후퇴안으로 보존 |
| (C) Companion Device Manager | 페어링 UX 강제 + 기기 제약 — 브링업 도구에 과함 (P14). 탈락 |

- ScanFilter 는 001 과 동일 (ServiceData `5F1D0003`, 빈 data 매치). SCAN_MODE_LOW_POWER
  (상시 등록이므로 — 001 의 LOW_LATENCY 와 다른 이유를 명시).

### D2. 조정 로직 소유권 — ViewModel 에서 추출 (⚠ 착수 게이트)

현재 Start 시퀀스·워치독·OOB 수명은 전부 `MainViewModel` 소유다. 콜드 웨이크는 **Activity/
ViewModel 없이** 이 시퀀스를 돌려야 하므로, 조정 로직을 프로세스 싱글턴(가칭
`uwb/RangingCoordinator`)으로 추출하고 ViewModel·FGS 가 함께 구동하는 구조가 필요하다.

- constitution P1/P2 는 "UI→ViewModel 단방향"을 규정할 뿐 조정자의 위치는 못박지 않았지만,
  기존 관행(조정자=ViewModel)이 바뀐다 — **리팩터 규모가 크고 회귀 위험이 있어 사람 승인을
  게이트로 둔다** (tasks G1). 추출은 동작 무변경 리팩터로 먼저, 콜드 웨이크 기능은 그 위에.
- 버린 대안: FGS 가 ViewModel 을 흉내내 시퀀스 일부만 복제 — 두 곳에 같은 상태 머신이 생겨
  P2(조정 단일)를 실질 위반. 탈락.

### D3. 웨이크 경로

```
콘솔 광고 → OS(PendingIntent 매치) → OobWakeReceiver(명시적 broadcast)
  → startForegroundService(RangingForegroundService, ACTION_AUTO_START)
  → FGS onStartCommand: startForeground() 즉시 → coordinator 로 모드 3 시퀀스
```

- 근거: Android 12+ 백그라운드 FGS 시작 제한의 **예외 목록에 "BLUETOOTH_SCAN 권한이 필요한
  Bluetooth 브로드캐스트 수신"이 포함** — PendingIntent 스캔 결과가 이에 해당하는지가 성립
  조건. 문서상 가능하나 **Galaxy 실기기 확인 전에는 단정 금지** (P9) — T101 이 최우선.
- 실패 시(ForegroundServiceStartNotAllowedException): 고우선 알림 폴백 (spec 수용 2).

### D4. 재발급 주소 문제는 병행 송출이 흡수

콜드 스타트마다 controlee 스코프가 새로 발급돼 폰 주소가 매번 다르다 — §2-1 병행 송출(001
T303)이 새 주소를 광고하므로 콘솔이 자동 확보. 002 에 추가 채널 불요.

## 영향 범위 (예상)

| 파일 | 변경 |
|---|---|
| `uwb/RangingCoordinator.kt` | **신규** — MainViewModel 에서 조정 로직 추출 (D2) |
| `MainViewModel.kt` | 수정 — coordinator 위임(상태 구독자로 축소) |
| `uwb/OobWakeReceiver.kt` | **신규** — PendingIntent 수신 → FGS 기동 (D3) |
| `RangingForegroundService.kt` | 수정 — ACTION_AUTO_START 처리 (현재는 로직 없는 유지용) |
| `ui/MainScreen.kt` | 수정 — 자동 감시 토글 |
| `AndroidManifest.xml` | 수정 — receiver 등록 |

## 검증 전략

- 리팩터(D2)는 동작 무변경 — 기존 JVM 테스트 green + 모드 1~3 수동 흐름 회귀 없음으로 판정
- 콜드 웨이크 성립(D3)·절전 생존은 전부 `[needs-device]` — JVM 으로 잡을 수 없다

## 리스크

- **R1.** FGS 백그라운드 시작 예외 불성립 → 대기(Arm) 방식 후퇴 (사용자 합의, spec 미해결)
- **R2.** Galaxy 잠자는 앱이 PendingIntent 스캔/Receiver 를 막음 → 배터리 최적화 제외 안내 필수
- **R3.** D2 리팩터 회귀 — 001 실기기 검증(검수 10~14)을 먼저 통과시켜 기준선을 만든 뒤 착수
