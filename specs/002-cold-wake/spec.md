# 002 — 콜드 웨이크: 백그라운드 비콘 수신 → 자동 레인징 (모드 4 기반, 크로스 플랫폼)

작성 2026-08-12 · **전면 개정 2026-08-12** (G0 확정 반영 — 이전 판은 Android 전용
PendingIntent 설계였다, git 이력 참조).

## 확정된 결정 (G0 — 2026-08-12 사용자)

1. **최종 목표는 Android + iOS 모두 지원**이다. Android 는 되고 iOS 는 안 되는 설계가
   나오면 **iOS 를 기준으로 맞춘다.**
2. 따라서 콜드 웨이크의 교환 구조는 **모드 4 (GATT 역방향)** 를 채택한다 —
   콘솔 = peripheral(발견용 광고 + GATT 서버), 폰 = central(스캔·연결 → Read/Write).
   근거·설계·바이트 계산: `docs/guide/ble_dev_guide.md` §5·§7.

이전 판의 Android 전용 방식(payload 광고 + PendingIntent)은 iOS 에서 성립하지 않아
(§5-3: UUID 목록 AD 부재 → 백그라운드 매치 불가, Service Data 송출 API 부재) 폐기한다.

## 목적

폰이 앱 미실행/백그라운드 상태에서 **콘솔의 connectable 광고를 트리거로 깨어나
자동으로 세션 파라미터를 교환(Read/Write)하고 레인징을 시작**한다.
같은 계약이 Android(이 리포)와 iOS(후속 리포)에서 동일하게 성립해야 한다.

## 범위

### 포함 (이 리포 = Android 폰 쪽)
- **모드 4 채널** (`OobCentral` 가칭): 콘솔 광고 스캔(Service UUID 필터) → GATT 연결 →
  보드 MAC·SID **Read** → 자기 UWB 주소 **Write** → UWB 시작
- "자동 감시" 토글: PendingIntent 스캔 등록/해제 (이제 광고에 UUID 목록이 있어
  **HW UUID 필터**로 등록 가능 — iOS 제약을 맞춘 부산물로 Android 도 단순해진다)
- 비콘 수신 Receiver → FGS 기동 → 화면 없이 모드 4 시퀀스 자동 실행
- FGS 기동 실패 시 고우선 알림 폴백

### 제외
- **iOS 앱 구현** — 별도 리포(후속). 단 **계약은 iOS 제약을 만족하도록 이 spec 이 강제**한다
  (iOS-first). iOS 쪽 대응물: State Restoration / pending connect (가이드 §5-2·§7-3)
- 콘솔 쪽 구현 (콘솔 리포 몫 — GATT 서버·connectable 광고 신설, P13)
- 모드 1·2·3 변경 — **존치.** Android 전용 브링업·검증 경로로 유지하고, 모드 4 는
  크로스 플랫폼 자동 경로로 추가된다 (P15: UWB 역할은 모든 모드에서 불변)
- 부팅 자동 재등록(BOOT_COMPLETED), 보안, multicast

## 계약 요점 (⚠ 초안 — 진실원천은 사양서 v0.5, 개정 전 코드 착수 금지)

마스터 사양서(콘솔 리포) 개정을 handoff 로 요청했다
(`docs/handoff/HANDOFF_모드4_사양서개정_요청.md` — 제안 상세는 그쪽). 골자:

| 항목 | 제안값 |
|---|---|
| 콘솔 광고 | Flags 3B + **128-bit Service UUID 목록**(콘솔 서비스) 18B = **21B**, `connectable=true` |
| 콘솔 GATT 서비스 | `5F1D0003` 재사용 (콘솔 방향 식별자 승계) |
| BOARD_INFO 특성 | **Read** — 기존 7B payload 그대로 (`uwb_address`=보드 MAC, `session_id` LE) |
| PHONE_INFO 특성 | **Write** — 기존 7B payload 그대로 (`uwb_address`=폰 주소) ← 신설 |
| payload | v1 7B **무변경** — 기존 빌더/파서 재사용 (P3) |
| 주소 재발급 | 연결 유지 중 재Write (모드 1 Notify 의 대응물) |

- 광고에 payload 가 없으므로 31B 예산 문제·16비트 UUID 발급 문제가 모두 사라진다.
- iOS 폰은 central 역할만 하므로 CoreBluetooth 제약(Service Data 송출 불가·백그라운드
  광고 overflow)에 걸리지 않는다.

## 수용 기준

1. (Android) 토글 ON → 앱 태스크 제거 + 화면 OFF → 콘솔 광고 시작 → 폰이 FGS 알림을
   띄우고 Read/Write 교환 후 레인징 자동 시작 `[needs-device]`
2. 콘솔 로그에 폰 주소(Write 수신)가 찍히고 보드 레인징이 자동 시작된다 `[needs-device]`
3. FGS 기동 거부 시 고우선 알림 폴백 — 탭하면 포그라운드 Start (P6 의 정신)
4. 모드 1~3 수동 흐름 회귀 없음 (토글 기본 OFF, `OobGattServer` 등 기존 채널 0줄)
5. **계약이 iOS 에서 성립함을 문서로 검증** — 사양서 v0.5 에 "iOS 적합성" 절이 있고,
   폰이 송출하는 광고·Service Data 가 **0건**임을 확인 (iOS 앱 실기기 검증은 후속 리포)
6. `gradlew test`·`assembleDebug`·`lint` green, `ui/` bluetooth import 0건 (P1)

## 미해결

- 사양서 v0.5 확정값 (UUID·특성 배치는 위 표가 제안일 뿐 — 콘솔 마스터가 결정)
- Android 백그라운드 FGS 시작 예외 적용 여부 — T304 스파이크 `[needs-device]`
  (불허 시 대기/Arm 방식 후퇴는 기존 합의 유지)
- iOS 백그라운드 UWB(Nearby Interaction) 정책 — iOS 앱 리포 신설 시 첫 스파이크 (P9)
- G1: 조정 로직 추출(`RangingCoordinator`) 승인 — plan D4
