# HANDOFF — 모드 4 (GATT 역방향) 신설을 위한 사양서 v0.5 개정 요청

> 작성 2026-08-12, 폰 세션(`uwb_controlee_app`)이 콘솔 세션(`uwb-console-kotlin`)에 전달.
> 방향: 폰 → 콘솔 (P13 — 교차 변경은 문서로 요청). 마스터 사양서는 콘솔 리포이므로
> **개정의 주체는 콘솔 세션**이고, 이 문서는 폰 쪽이 정리한 근거와 제안이다.

## 1. 배경 — 무엇이 결정됐나 (2026-08-12 사용자 확정)

- **최종 목표: Android + iOS 폰 모두 지원. 충돌 시 iOS 기준으로 맞춘다** (G0).
- iOS 제약 검토 결과(폰 리포 `docs/guide/ble_dev_guide.md` §5·§6):
  1. iOS 는 광고에 **Service Data/Manufacturer Data 를 실을 수 없다** → 모드 2·3 병행
     송출(폰 주소 자동 전달)이 iOS 에서 성립 불가
  2. iOS 백그라운드 스캔은 **Service UUID 목록 필터가 필수**인데, 현행 모드 2·3 광고에는
     31B 예산 때문에 UUID 목록 AD 가 없다 → **iOS 콜드 웨이크 불가 (패킷 포맷 문제)**
  3. iOS 백그라운드 광고는 overflow 영역으로 밀려 Android 콘솔이 발견 불가 → 모드 1 도
     백그라운드에서는 못 쓴다
- 결론: **모드 4 (GATT 역방향)** 신설 — 콘솔 = peripheral(발견용 광고 + GATT 서버),
  폰 = central(스캔·연결 → Read/Write). 상세 설계·시퀀스: 폰 리포 가이드 §7.
- 기존 모드 1~3 은 **무변경 존치** (Android 브링업·회귀 경로).

## 2. 요청 사항

1. **사양서 v0.4 → v0.5 개정** (마스터: 콘솔 리포 `docs/oob/`) — 모드 4 절 신설 +
   "iOS 적합성" 절 (아래 §3 제안 참조). 양 리포 사본 동시 커밋 (P5).
2. 콘솔 쪽 신규 spec — GATT 서버(peripheral) 역할 + connectable 광고. 완성 시
   짝 태그·pin bump 는 repo_guide §6 원자 규칙대로.
3. 개정본 확정 시 폰 리포에 사본 배치 + 확정값(UUID·특성) 통보 — 폰 spec 002 T001 해제 조건.

## 3. 계약 제안 (폰 쪽 초안 — 콘솔이 결정)

### 3-1. 콘솔 광고 (모드 4)

| 항목 | 제안 | 근거 |
|---|---|---|
| 구성 | Flags 3B + **Complete 128-bit Service UUID 목록** 18B = **21B** | payload 없음 → 31B 여유 10B. **UUID 목록이 있어야 iOS 백그라운드 필터가 매치** |
| connectable | **true** | 폰이 연결해 Read/Write |
| 모드/timeout | BALANCED / 0 (기존 §5-3 승계) | |
| 이름 | 스캔 응답 (기존 승계) | |

### 3-2. 콘솔 GATT 서비스 (모드 4)

| 항목 | 제안 |
|---|---|
| Service UUID | `5F1D0003-…` 재사용 (콘솔 방향 식별자 승계 — 새 UUID 발급도 무방, 콘솔 판단) |
| BOARD_INFO 특성 | 예: `5F1D0004-…` — **Read**. payload v1 7B 그대로 (`uwb_address`=보드 MAC, `session_id` LE) |
| PHONE_INFO 특성 | 예: `5F1D0005-…` — **Write** (Write Without Response 권장 — iOS 백그라운드에서 왕복 최소화). payload v1 7B 그대로 (`uwb_address`=폰 주소) |
| 주소 재발급 | 연결 유지 중 폰이 재Write (모드 1 Notify 의 대응물) |
| 보안/MTU | 없음 / 기본 23 (7B 라 충분) — 기존 §3 승계 |

payload v1 7B 는 **무변경** — 양 리포 빌더/파서 재사용 (P3). 파서 규칙(≥7B, 전방 호환)도 승계.

### 3-3. 시퀀스 (제안)

```
콘솔                                    폰 (Android/iOS 공통)
 │ 광고 시작 (UUID 목록, connectable)      │ [1회 등록] UUID 필터 스캔/pending connect
 │ ──────────────────────────────────▶  │ OS 가 앱을 백그라운드로 깨움
 │ ◀───────────── GATT 연결 ──────────── │
 │ ◀──────────── BOARD_INFO Read ─────── │ → 보드 MAC·SID 자동 반영
 │ ◀──────────── PHONE_INFO Write ────── │ → 콘솔이 DST_MAC 확보
 │ start_ranging ──▶ 보드(UCI)           │ UWB 세션 시작
```

- 타임라인 매핑(§6-1 승계 제안): BLE_ADV=광고 시작, BLE_CONN=폰 연결 수신,
  OOB_DONE=PHONE_INFO Write 수신·검증 통과.
- 예외(§7 추가 제안): Write 미수신 타임아웃 → 수동 DST_MAC 폴백(§7-14 동형),
  연결 끊김 → 광고 재개(모드 1 §5 규칙 2 동형).

### 3-4. "iOS 적합성" 절 제안 (v0.5 신설)

모드별로 "폰이 송출하는 광고/Service Data 유무"를 표로 명시하고, **모드 4 는 폰 송출이
0건**임을 계약 수준에서 보장한다 — 이것이 iOS 성립의 조건이다. (모드 1~3 은
"Android 전용" 표기 제안.)

## 4. 폰 쪽 상태·계획 (참고)

- spec 001 (3모드): 코드 `[maker-ready]`, 실기기 검수 10~13 진행 중 — 무변경 존치
- spec 002: 모드 4 기반으로 전면 개정 완료 (`specs/002-cold-wake/`). **T001(이 개정) 확정
  전에는 모드 4 코드 착수 금지** — 그동안 조정자 리팩터(계약 무관)만 진행 가능
- iOS 앱은 별도 리포로 후속 (T501) — 이 계약이 그 입력이 된다

## 5. 회신 요청 항목

1. 서비스/특성 UUID 확정값 (3-2 제안 수용 여부)
2. Write 타입 (With/Without Response)
3. 콘솔 광고가 기존 ADVERTISE 모드(5F1D0003 Service Data)와 **동시 운용**될지,
   모드 4 선택 시 대체될지 (짝 규칙 §2 표 갱신)
4. v0.5 사본 배치 커밋 SHA
