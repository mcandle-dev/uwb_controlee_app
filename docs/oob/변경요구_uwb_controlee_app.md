# 변경요구서 — uwb_controlee_app: BLE OOB Peripheral 추가

> 대상 리포: `mcandle-dev/uwb_controlee_app` · 기준 사양: `BLE_OOB_인터페이스_사양서.md v0.2`
> 작성일 2026-07-13 · Claude Code 실행 위치: `D:\dev\uwb_controlee_app`

---

## 0. 선행 작업 — 문서 개정 (코드보다 먼저, 필수)

현재 리포의 ground truth가 BLE OOB를 **명시적으로 금지**하고 있어, 개정 없이 구현을 지시하면 Claude Code가 자기 지침과 충돌한다.

| 파일 | 개정 내용 |
|---|---|
| `docs/앱_기능_화면_요구사항정의서.md` §2 | 스코프 제외 목록에서 "BLE OOB 자동 교환" **삭제** → In-Scope로 이동. 나머지 제외 항목(다중 피어, 백그라운드 레인징, controller 모드 등)은 유지 |
| `CLAUDE.md` | 스코프 금지 목록 동일 개정 + OOB 계약 상수(Service/Char UUID, 페이로드 포맷) 추가. "OOB 상수는 `UwbDefaults.kt`와 사양서가 유일 기준" 명시 |
| `docs/oob/BLE_OOB_인터페이스_사양서.md` | 마스터(radar_test_console) 사본 커밋 |

## 1. 기능 요구사항 (기존 FR-1~10에 추가)

| ID | 기능 | 우선 | 상세 |
|---|---|---|---|
| FR-11 | BLE 광고 | 必 | **OOB 모드에서** Start(WAITING 진입) 시 광고 시작, Stop 시 중지. 수동 모드에서는 광고하지 않음. Service UUID 포함, Local Name `UWB-OOB`, 인터벌 balanced. GATT 연결 중 광고 중지, 해제 후 WAITING이면 재개 |
| FR-12 | GATT 서버 OOB_INFO | 必 | 사양서 §3~4의 특성 제공(Read+Notify). 페이로드 7B: version(0x01)+주소 2B(표시 순서)+session_id 4B(LE) |
| FR-13 | 주소 재발급 Notify | 必 | 스코프 재발급으로 주소가 바뀌면 새 OOB_INFO를 Notify로 푸시 |
| FR-14 | BLE 권한 플로우 | 必 | `BLUETOOTH_ADVERTISE`/`BLUETOOTH_CONNECT`(API 31+) 런타임 요청. 거부 시 **OOB만 비활성** — 기존 UWB 수동 흐름은 그대로 동작 (배너 패턴 재사용) |
| FR-15 | OOB 이벤트 로그 | 必 | 광고 시작/중지, central 연결/해제, Read/Notify 발신을 기존 로그 콘솔에 기록 |
| FR-16 | OOB 상태 표시 | 必 | 주소 카드 옆에 광고/연결 상태 소형 배지 (⚪광고중 / 🔵콘솔연결됨 / 없음=수동) |
| FR-17 | **연결 방식 선택 UI** | 必 | Start 전에 `수동`/`OOB 자동` 세그먼트 선택. **기본값 수동.** 레인징 중(WAITING/RANGING)에는 변경 비활성. 선택값은 앱 재시작 간 유지(選). 수동 선택 시 화면·동작이 기존과 100% 동일해야 함 |

## 1a. 화면 변경 (UI 요구사항)

기존 단일 화면에 **연결 방식 세그먼트 1줄 추가** + 모드에 따라 주소 카드 문구·배지 변경.

```
┌─────────────────────────────────────┐
│ ⚠ 상태 배너 (기존과 동일)              │
├─────────────────────────────────────┤
│ 연결 방식  [ ● 수동 │ ○ OOB 자동 ]    │ ← ★신규 FR-17. 레인징 중 비활성
├─────────────────────────────────────┤
│ 내 UWB 주소 [ 5F:DD ] (탭=복사)       │ ← 수동: 기존 그대로 "PC에 입력할 값"
│              ⚪광고중                 │ ← OOB: 캡션 "콘솔에 자동 전달됨" + 배지(FR-16)
├─────────────────────────────────────┤
│ 보드 MAC [00:00]  Session ID [42]    │ ← 두 모드 공통 (변경 없음)
├─────────────────────────────────────┤
│      [ ▶ Start ]   [ ■ Stop ]       │
│ 수동:  ① 앱 Start → ② PC 스크립트     │ ← 안내문이 모드에 따라 전환
│ OOB:  ① 앱 Start → ② 콘솔 [OOB 스캔] │
├─────────────────────────────────────┤
│  (측정 표시·로그 콘솔 — 기존과 동일)    │
└─────────────────────────────────────┘
```

**모드별 동작 요약**

| 항목 | 수동 (기본) | OOB 자동 |
|---|---|---|
| BLE 광고/GATT | 안 함 | Start 시 광고, OOB_INFO 제공 |
| 주소 카드 | 탭=복사, "PC에 입력" 안내 | 표시는 유지(진단용), "자동 전달됨" + 배지 |
| Start/Stop·측정·로그 | 기존 동일 | 기존 동일 (BLE는 부가 동작) |
| BLE 권한 | 요구 안 함 | OOB 모드 첫 진입 시 요청, 거부 시 수동으로 안내 |

## 2. 비기능·아키텍처 요구사항

- BLE 코드는 신규 `uwb/OobGattServer.kt`(가칭)에 격리 — `UwbRepository`와 대등한 계층. **Composable에 BluetoothManager 직접 접근 금지** (기존 단방향 규칙 유지).
- OOB 상수(UUID·버전)는 `UwbDefaults.kt`에 추가 — 세션 파라미터와 같은 "한 곳" 원칙.
- 광고·GATT 실패는 앱을 죽이지 않는다. 실패 시 로그 + 배지 표시, UWB 기능 무영향.
- ViewModel이 OOB 서버 수명 소유: Start→open, Stop/onCleared→close (좀비 GATT 서버 금지).
- 에뮬레이터/BLE 미지원 기기에서 크래시 없이 OOB 비활성 처리 (기존 UWB 가용성 판정 패턴).

## 3. 검수 기준 (앱 단독 — 콘솔 불필요)

1. UWB Galaxy에서 Start → **nRF Connect**(다른 폰/PC)로 스캔 시 `UWB-OOB` + Service UUID가 보인다.
2. nRF Connect로 연결 → OOB_INFO Read → 7B 페이로드가 화면 주소·SessionID와 일치한다 (바이트 순서 사양서 §4 그대로).
3. Stop→재Start(주소 변경) 시 Notify가 수신되고 새 주소와 일치한다.
4. Stop 시 광고가 사라지고 연결이 끊긴다.
5. BLE 권한 거부 상태에서도 기존 수동 레인징 플로우가 정상 동작한다.
6. 로그 콘솔에 OOB 이벤트가 시각과 함께 남는다.
7. **수동 모드 선택 시 광고가 전혀 발생하지 않고**(nRF Connect 스캔에 안 보임) 화면·동작이 기존 버전과 동일하다.
8. 레인징 중에는 연결 방식 세그먼트가 비활성화된다.

## 4. Claude Code 복붙 프롬프트

**개정 단계 (먼저):**
```
docs/oob/BLE_OOB_인터페이스_사양서.md 를 읽어라.
1) docs/앱_기능_화면_요구사항정의서.md §2 스코프에서 'BLE OOB 자동 교환'을 제외 목록에서 빼고
   In-Scope로 옮겨라 (다른 제외 항목은 유지).
2) CLAUDE.md의 스코프 금지 목록을 동일하게 개정하고, OOB 계약(Service UUID, Char UUID,
   페이로드 7B 포맷)을 기술 계약 섹션에 추가하라.
3) 변경요구_uwb_controlee_app.md 의 FR-11~17과 §1a 화면 변경(연결 방식 세그먼트,
   모드별 동작 표)을 요구사항정의서의 FR·화면 설계 장에 추가하라.
코드는 아직 만들지 마라. diff를 보여주고 멈춰라.
```

**구현 단계:**
```
BLE_OOB_인터페이스_사양서.md와 개정된 CLAUDE.md 기준으로 구현하라.
1단계: UwbDefaults.kt에 OOB 상수(UUID·버전·페이로드 빌더) 추가 + 페이로드 인코딩 단위테스트
       (주소 "5F:DD"→0x5F,0xDD 순서, session_id 42→2A 00 00 00 LE 케이스 포함).
2단계: uwb/OobGattServer.kt — 광고+GATT 서버+Notify. ViewModel이 Start/Stop과 수명 연동.
3단계: 연결 방식 세그먼트 UI(FR-17, 기본 수동, 레인징 중 비활성) + 모드별 주소 카드
       문구·배지(FR-16)·안내문 전환 — §1a 목업 기준. 수동 모드는 기존과 100% 동일해야 함.
4단계: 권한 플로우(FR-14)와 로그(FR-15).
각 단계 끝에서 멈추고, 검수는 nRF Connect 기준(변경요구서 §3)으로 안내하라.
BLE 실패가 UWB 흐름을 절대 막지 않게 하고, 수동 모드에서는 BLE 코드가 아예 실행되지 않게 하라.
```

## 5. 리스크

- 일부 Galaxy 기기의 광고 스로틀링(절전) → 광고 인터벌 balanced 고정, 화면 ON 상태 테스트 원칙 (기존 포그라운드 유지 원칙과 동일).
- GATT 서버와 UWB 동시 동작은 문제없으나, **BLE 코드가 UWB 세션 콜백 스레드를 블로킹하지 않도록** 별도 코루틴 스코프 사용.
- targetSdk 36 기준 권한 문구 검토 (ADVERTISE는 위치 권한 불필요 — neverForLocation 플래그).
