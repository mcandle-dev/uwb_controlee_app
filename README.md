# uwb_controlee_app — UWB Controlee 브링업 테스트 앱 (Android/Galaxy)

Qorvo **DWM3001CDK** 보드(UCI 펌웨어, controller/initiator)와 FiRa UWB 레인징을 수행하는
**Android Galaxy용 controlee 테스트 앱**입니다. Kotlin + Jetpack Compose + `androidx.core.uwb` 기반.

상용 앱이 아니라 **초도 기능 검증(bring-up) 도구**입니다 — 화려함보다 "빠른 기본 동작 확인"과
"안 될 때 화면만 보고 원인을 좁힐 수 있는 진단 가능성"이 우선입니다.

```
[DWM3001CDK 보드] ←──── FiRa DS-TWR 레인징 ────→ [Galaxy 폰 (이 앱)]
   controller                                        controlee
      ↑ USB(UCI)                                       화면에 거리·각도 표시
[PC: run_fira_twr.py]  ← sasodoma/uwb-ranging의 UCI 호스트 스크립트가 보드를 구동
```

- 보드 펌웨어는 UCI 바이너리 프로토콜이라 스스로 동작하지 않으며, PC의 UCI 호스트
  스크립트가 세션을 설정·시작합니다.
- 역할 방향은 **보드=controller / 폰=controlee**로 고정 (반대 방향은 타임아웃 잦음 —
  Qorvo 포럼 Pixel 사례로 검증된 방향만 사용).

## 화면 구성 (단일 화면)

```
┌─────────────────────────────────────┐
│ ⚠ 상태 배너 (문제 있을 때만)           │ ← 미탑재/토글OFF/권한거부 구분 안내 + 해결 버튼
├─────────────────────────────────────┤
│ 내 UWB 주소 [ 5F:DD ] ⚪광고중 (탭=복사)│ ← 콘솔에 넘길 값 + OOB 상태 배지
├─────────────────────────────────────┤
│ 보드 MAC [00:00]  Session ID [42]    │ ← 입력 2개, 나머지 파라미터는 상수
│ [OOB 1·GATT광고(v1)▾] CH 9 · PRE 9 …│ ← OOB 모드 선택 + 고정 파라미터 표기
├─────────────────────────────────────┤
│      [ ▶ Start ]   [ ■ Stop ]       │
│ ① 앱 Start → ② 콘솔에서 시작          │ ← 순서 안내 (controlee 먼저!)
├─────────────────────────────────────┤
│  ●RANGING    거리 123cm   각도 -8°   │ ← 큰 숫자, 상태 배지, 마지막 갱신 시각
├─────────────────────────────────────┤
│ 12:00:01 세션 시작 (controlee 대기)   │ ← 이벤트 로그 콘솔 (500줄, 자동 스크롤)
└─────────────────────────────────────┘
```

OOB 배지: `⚪ 광고중`(모드 3은 `스캔중`) / `🔵 콘솔 연결됨`(모드 3은 `광고 수신됨`) /
`OOB 비활성`(BT 꺼짐·권한 거부 — **UWB 수동 흐름은 그대로 동작**).
모드 선택 옆 `콘솔시뮬` 버튼은 이 폰을 가짜 콘솔로 만들어 모드 3을 예비 검증하는 테스트 보조.

상태 머신: `IDLE → (Start) → WAITING → (첫 측정) → RANGING → DISCONNECTED/ERROR`,
RANGING 중 2초 무수신이면 "수신없음" 배지(세션은 유지, 자동 재시작 없음 — 증상 보존).

## 소스 구조

```
app/src/main/java/com/mcandle/uwbcontrolee/
├── MainActivity.kt        # 진입점. 권한 런처(모드별 BLE 권한), 설정 이동, onResume마다 가용성 재판정
├── MainViewModel.kt       # UiState(StateFlow) 단일 상태. Start 시퀀스 모드 분기·워치독·OOB 수명
├── RangingForegroundService.kt # 백그라운드 세션 유지용 FGS (로직 없음 — 프로세스 유지 전용)
├── uwb/
│   ├── UwbDefaults.kt     # ★ 세션 계약 상수 + OOB UUID·payload 빌더/파서 — 보드·콘솔과 대조하는 파일
│   ├── UwbAvailability.kt # 가용성 enum (CHECKING/NOT_SUPPORTED/PERMISSION_DENIED/DISABLED/READY)
│   ├── RangingState.kt    # 레인징 상태 머신 enum (IDLE/WAITING/RANGING/DISCONNECTED/ERROR)
│   ├── UwbRepository.kt   # UWB API 유일 창구. 가용성 판정·스코프 획득·RangingParameters·파서
│   ├── OobMode.kt         # BLE OOB 3모드 enum + 영속화 매핑 (기본 ADVERTISE_GATT)
│   ├── OobGattServer.kt   # 모드 1 — GATT peripheral (광고 + OOB_INFO Read/Notify)
│   ├── OobBeacon.kt       # 모드 2 — Service Data 광고 송출 (모드 3 병행 송출에도 재사용)
│   ├── OobScanner.kt      # 모드 3 — 콘솔 광고 관찰 + 모드별 BLE 권한 헬퍼
│   └── OobScanFilter.kt   # 모드 3 수신 판정 순수 함수 (죽은 광고·중복 거름 — JVM 테스트 대상)
└── ui/
    └── MainScreen.kt      # 단일 Compose 화면 (배너/주소카드/입력/제어/측정/로그 콘솔)

docs/
├── 앱_기능_화면_요구사항정의서.md      # FR-1~10·화면·상태머신·NFR·검수 기준 (ground truth)
├── 파라미터_대조_4단계.md             # sasodoma 리포와의 파라미터 쌍 대조표 (바이트 순서 함정 포함)
├── 작업일지_2026-07-04_구현1-4단계.md  # 구현 이력·검수 결과·미해결 리스크
├── oob/ · handoff/                    # BLE OOB 계약(사본)·세션 간 인수인계 (경로 고정 — 이동 금지)
└── guide/                             # 사람용 가이드 (계약 아님)
    ├── 5단계_보드_테스트_가이드.md     # 보드 확보 후 실물 테스트 절차·체크리스트 (전달용)
    ├── 검수10-13_함수_호출_맵.md       # 3모드 검수용 폰·콘솔 함수 체인 + 로그↔소스 매핑
    ├── ble_dev_guide.md               # BLE OOB 입문 가이드 (4모드 시퀀스·제약·iOS 차이)
    └── 콜드웨이크_동작_시나리오.md     # 모드 4 콜드 웨이크 단계별 폰/콘솔 동작·타이머·T304 판정

CLAUDE.md                              # 기술 계약(세션 파라미터)·아키텍처 규칙·함정 목록
```

## BLE OOB 3모드 (Phase 2 — spec 001)

폰의 UWB 주소는 **세션마다 재발급**되므로 사람이 옮겨 적는 대신 BLE로 자동 교환합니다(OOB).
교환 경로는 화면에서 런타임 선택하며, **콘솔(짝)의 모드와 짝이 맞아야** 성립합니다.

| # | 폰 모드 | 짝 (콘솔) | 방향 | 내용 |
|---|---|---|---|---|
| 1 | **ADVERTISE-GATT** (기본값) | SCANNER | 폰→콘솔 | v1 경로. GATT 광고→연결→Read/Notify. 유일하게 Notify로 주소 갱신 |
| 2 | **BEACON** (송출) | BEACON | 폰→콘솔 | OOB_INFO 7B를 Service Data 광고로 방송. 연결 없음, UWB 즉시 시작 |
| 3 | **SCANNER** (관찰) | ADVERTISE | 콘솔→폰 | 보드 MAC·SessionID를 광고로 수신해 자동 반영 + 자기 주소 병행 송출 |

- 짝이 어긋나면 오류가 아니라 **"발견 못함"** 으로 끝납니다 (타임아웃 후 모드 확인 안내).
- **BLE의 어떤 실패도 UWB 수동 흐름을 막지 않습니다** — 권한 거부·광고 실패는 배지·로그로만 종결.
- 계약(UUID·7B payload)은 `docs/oob/BLE_OOB_인터페이스_사양서.md`(콘솔 리포가 마스터, 여기는 사본).
- **입문 설명·시퀀스 다이어그램·제약·iOS 차이**: [`docs/guide/ble_dev_guide.md`](docs/guide/ble_dev_guide.md)

## 아키텍처

- **단방향 데이터 흐름**: `MainScreen(Compose)` → 이벤트 → `MainViewModel` →
  `StateFlow<UiState>` → 화면. UWB API 호출은 `UwbRepository`에만 존재하고
  Composable에는 없습니다.
- **세션 소유**: ViewModel이 레인징 Job을 소유 — 화면 회전에도 세션 유지.
  백그라운드에서도 `RangingForegroundService`(FGS)가 프로세스를 유지해 세션이 지속됩니다
  (NFR-3 개정). **FGS 없이 백그라운드로 가면 UWB 스택이 세션을 무증상으로 내립니다.**
- **핵심 코드 흐름 (controlee)**:

```kotlin
val sessionScope = uwbManager.controleeSessionScope()   // READY 즉시 자동 — 주소 선표시용
val myAddress = sessionScope.localAddress               // 화면 표시 → PC --dest-mac에 입력

rangingJob = scope.launch {
    sessionScope.prepareSession(params).collect { result ->   // 수집 시작 = controlee 대기
        when (result) {
            is RangingResult.RangingResultPosition -> ...      // 거리(m→cm)·azimuth(°)
            is RangingResult.RangingResultPeerDisconnected -> ...
        }
    }
}
// Stop = rangingJob.cancel()  — Flow 취소가 곧 세션 종료. 스코프는 1회용 → 종료 후 재발급
```

- 외부 라이브러리 최소화: DI/네트워크/DB 없음. coroutine + StateFlow만 사용.

## UWB 세션 계약 (이 프로젝트의 핵심 — 보드 쪽과 바이트 단위 일치 필수)

| 항목 | 값 | 근거 |
|---|---|---|
| Config | FiRa DS-TWR deferred, unicast (`CONFIG_UNICAST_DS_TWR`) | sasodoma 쌍 |
| Session ID | 42 (UI 변경 가능) | PC `-s` 기본값과 쌍 |
| 채널 / 프리앰블 | 9 / 9 | 〃 |
| Static STS | `08 07 01 02 03 04 05 06` (Vendor 2B + IV 6B) | PC `VENDOR_ID 0x0708` + `STATIC_STS_IV 0x060504030201` (리틀엔디언 → 바이트 반전!) |
| 갱신 주기 | `FREQUENT` = 120ms | PC `RANGING_DURATION=120`과 쌍 |
| 보드 주소 | `00:00` 기본 (UI 입력) | PC `--mac 00:00`과 쌍 |

기준값 출처: [sasodoma/uwb-ranging](https://github.com/sasodoma/uwb-ranging) @ `aad72a0`의
PC 스크립트 + Android 앱 **쌍**. 상세 대조표와 바이트 순서 함정은
[`docs/파라미터_대조_4단계.md`](docs/파라미터_대조_4단계.md) 참조.
**파라미터가 하나라도 어긋나면 에러 없이 조용히 아무것도 안 나옵니다** — 이 도메인 최대의 함정.

## 요구 사항

| 항목 | 값 |
|---|---|
| 폰 | UWB 탑재 Galaxy (Note20 Ultra, S21+/Ultra 이상 +/Ultra, S24+/Ultra, Z Fold2 이후 등. **베이스/FE 모델 UWB 없음**) + 설정에서 UWB(초광대역) 토글 ON |
| OS | Android 12+ (minSdk 31), targetSdk 36 |
| 빌드 | JDK 17+ (JDK 21 검증), Android SDK 36, Gradle 9.3.1(wrapper 포함), AGP 9.1.1, Kotlin 2.2.10 |
| UWB 라이브러리 | `androidx.core.uwb:uwb:1.0.0` (stable 고정 — alpha 사용 금지) |
| 런타임 권한 | `UWB_RANGING` + `BLUETOOTH_ADVERTISE`/`BLUETOOTH_CONNECT`(OOB) + `BLUETOOTH_SCAN`(모드 3에서만) + `POST_NOTIFICATIONS`(FGS 알림, 거부돼도 동작) |
| 보드 | DWM3001CDK + sasodoma 리포 동봉 UCI 펌웨어 |
| 짝(콘솔) | **`uwb-console-kotlin`** (Android 콘솔 앱, 별도 리포 — 보드 USB 연결·레이더 표시 담당). 수동 경로로 PC 스크립트(Python 3.8+)도 사용 가능 |

## 빌드 & 설치

빌드 없이 바로 설치하려면 [Releases](https://github.com/mcandle-dev/uwb_controlee_app/releases)에서
`app-debug.apk`를 받아 `adb install -r app-debug.apk`.

```bash
git clone https://github.com/mcandle-dev/uwb_controlee_app.git
cd uwb_controlee_app
# local.properties에 sdk.dir 설정 (Android Studio로 열면 자동 생성)

./gradlew test                   # JVM 단위테스트 (payload 인코딩·파싱, 모드 영속화, 스캔 필터)
./gradlew assembleDebug          # 빌드
./gradlew lint                   # 린트 (UI/매니페스트 변경 시 필수)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**v1과 동시 설치됩니다.** Phase 2부터 `applicationId`를 `com.mcandle.uwbcontrolee.v2`로
분리해(런처 이름 `UWB Controlee v2`) v1 설치본과 한 폰에 공존합니다 — 회귀 비교용.
짝인 콘솔 앱도 같은 규칙(`com.mcandle.uwbconsole.v2`)이며, 콘솔은 실물 테스트 시
반드시 **hw flavor**(`assembleHwDebug`)로 설치해야 합니다(sim은 가짜 BLE/보드).

## 테스트 방법

### 보드 없이 (에뮬레이터/아무 기기) — 진단 UI 확인

1. UWB 없는 기기/에뮬레이터: "이 기기는 UWB를 지원하지 않습니다" 배너, 크래시 없음
2. UWB Galaxy: 권한 플로우 → 내 주소 hex 표시 → 탭하면 복사
3. 보드 MAC에 `zz` 등 입력 → 빨간 안내문 + Start 비활성
4. Start → WAITING 전환, Stop → IDLE 복귀, 로그 기록

### 보드와 페어 테스트 — 요약 (상세·체크리스트: [`docs/guide/5단계_보드_테스트_가이드.md`](docs/guide/5단계_보드_테스트_가이드.md))

```powershell
# 사전: 보드에 sasodoma 리포 new_firmware/*.hex 플래시, new_python_script에 pip install
# 1) 앱 실행 → 내 주소 확인 (예 5F:DD) → Start (WAITING)   ← 반드시 앱이 먼저!
# 2) PC:
python run_fira_twr.py -p COM5 --mac 00:00 --dest-mac 5F:DD -t -1
# 3) 10초 내 앱이 RANGING(초록) + 거리 표시되면 성공
```

**보드 연결 체크리스트 (요약판)** — 전체 9항목 표는 5단계 가이드 문서에:

- [ ] 펌웨어가 **sasodoma 리포 동봉** `new_firmware/DWM3001CDK-DW3_QM33_SDK_UCI-FreeRTOS.hex`인가
- [ ] 스크립트가 **sasodoma 사본** `new_python_script/run_fira_twr.py`인가 (원본 Qorvo 스크립트는 기본값이 달라 조용히 실패)
- [ ] 케이블이 J-Link 반대쪽 **통신용 USB 포트**에 꽂혀 있고 COM 번호 확인했는가
- [ ] 폰 UWB 토글 ON + 권한 허용 + 문제 배너 없음
- [ ] **순서**: 폰 Start(WAITING) → 그 다음 PC 스크립트 실행
- [ ] `--dest-mac` = 폰 화면의 **현재** 주소 (Stop/재시작하면 주소가 바뀜 — 로그에 변경 안내 뜸)
- [ ] `--mac 00:00` = 앱의 보드 MAC 입력값, Session ID 양쪽 42
- [ ] 거리 30cm~2m, 장애물 없음 (백그라운드로 가도 FGS가 세션을 유지 — 알림이 떠 있는지 확인)
- [ ] 안 되면: 가이드 §6 트러블슈팅 순서대로 → 폰 로그 콘솔 + PC 콘솔 출력 확보

**BLE OOB 3모드 검수** (콘솔 앱과 페어): 모드별 함수 호출 체인과 로그↔소스 매핑은
[`docs/guide/검수10-13_함수_호출_맵.md`](docs/guide/검수10-13_함수_호출_맵.md),
진행 상황·절차는 [`docs/TODO.md`](docs/TODO.md) A절 참조.

## Fork해서 개발할 때 알아야 할 것

1. **Ground truth 문서**: `constitution.md`(불변 원칙 P1~P15)가 최상위 →
   현재 작업 중인 `specs/NNN-*/`(spec→plan→tasks) → OOB 계약은 `docs/oob/*사양서.md`,
   세션 파라미터는 `UwbDefaults.kt` → `CLAUDE.md` → v1 기능·화면은
   `docs/앱_기능_화면_요구사항정의서.md`. **충돌 시 위쪽이 이깁니다.**
2. **아키텍처 규칙**: UI는 ViewModel(→Repository)에만 의존, 상태는 StateFlow 단방향,
   모든 함수 명시적 타입, 매직넘버 금지(상수화), 한 함수 30줄 이내.
   `androidx.core.uwb`·`android.bluetooth` import는 `uwb/` 패키지 전용입니다 (P1).
3. **세션 파라미터를 바꿀 때**: 반드시 `UwbDefaults.kt` 한 곳에서 바꾸고, 보드/콘솔
   쪽 값과 **쌍으로** 바꿀 것. 한쪽만 바꾸면 에러 없이 조용히 실패합니다.
   **OOB 계약(UUID·payload) 변경은 양 리포 동시 커밋 + 콘솔 pin bump가 따라옵니다** (P5/P13).
4. **스코프 추가 금지 항목**: 다중 피어(multicast), 측정 저장/내보내기, controller 모드,
   별도 설정 화면, DI/네트워크/DB 라이브러리 (P14).
   ~~BLE OOB 자동 교환·백그라운드 레인징~~ 은 **구현 완료**되어 금지 목록에서 빠졌습니다
   (각각 v1 FR-11~16 / NFR-3 개정 FGS).
5. **함정 목록** (전체는 CLAUDE.md):
   - 파라미터 불일치 = 무증상 실패 (에러 콜백조차 없음)
   - controlee(앱)를 **먼저** 시작한 뒤 controller(보드)를 start
   - `azimuth`는 nullable — 기기/자세에 따라 안 나옴 ('N/A' 처리)
   - 거리 단위: androidx는 미터(Float) → 표시 시 cm 환산
   - 보드(DWM3001CDK)는 안테나 1개 → 각도 검증은 폰 쪽 담당
   - UWB 토글 OFF 시 availability false — 앱이 안내해야 사용자가 안 헤맴
6. **진행 상태**: v1 완료 — 1~5단계 전부 통과(**2026-07-17 실물 페어 E2E 성공**),
   태그 `v1.0_controlee_advertise`. 현재 **Phase 2 (BLE OOB 3모드, spec 001)** 진행 중으로
   코드는 `[maker-ready]`, 실기기 검수 10~13이 남아 있습니다 (`docs/TODO.md` 참조).
   후속 `specs/002-cold-wake`(백그라운드 비콘 웨이크)는 게이트 대기 중 — 코드 0줄.
   **P9: 실물 페어 재검증 전에는 "동작한다"고 단정하지 않습니다.**

## 참고 링크

- 기준 구현(파라미터 쌍의 출처): https://github.com/sasodoma/uwb-ranging
- 공식 샘플(구조 참고): https://github.com/android/connectivity-samples/tree/main/UwbRanging
- Qorvo 포럼 — 역할 방향 검증: [DWM3001CDK and Pixel 8 Pro, ranging only works one way](https://forum.qorvo.com/t/dwm3001cdk-and-google-pixel-8-pro-ranging-only-works-one-way/18083)
- **짝 프로젝트(현행)**: `uwb-console-kotlin` — Android 콘솔 앱. 보드 USB(UCI) 연결·레이더
  표시·OOB 3모드 상대역 담당. OOB 사양서의 **마스터가 이 리포**입니다 (여기는 사본 — P5)
- 이전 짝(동결·참조 전용): `radar_test_console` (Python Flet PC 콘솔)
