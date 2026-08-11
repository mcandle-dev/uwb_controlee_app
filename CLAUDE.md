# CLAUDE.md — UWB Controlee 테스트 앱 (uwb_controlee_app)

## 프로젝트 개요
Qorvo DWM3001CDK 보드(UCI 펌웨어, **controller/initiator**)와 FiRa UWB 레인징을 수행하는
**Android Galaxy용 controlee 앱**. Kotlin + Jetpack Compose + `androidx.core.uwb` 기반.
상용 앱이 아닌 **초도 기능 검증(Bring-up Test) 도구** — 많은 기능보다 "빠른 기본 동작 확인"이 최우선.

## 반드시 먼저 읽을 것 (순서대로 — 2026-08-11 SDD 체계 도입)
1. `constitution.md` — **불변 원칙 (P1~P15). 모든 판단의 최상위.** 이 문서와 충돌하면 constitution 이 이긴다.
2. 현재 작업 중인 `specs/NNN-*/` 의 `spec.md` → `plan.md` → `tasks.md`
3. `docs/oob/BLE_OOB_인터페이스_사양서.md` — BLE OOB 계약 (**마스터는 콘솔 리포** `uwb-console-kotlin/docs/oob/`, 여기는 사본 — P5)
4. `docs/앱_기능_화면_요구사항정의서.md` — v1 기능(FR-1~16)·화면·상태 머신. 신규 기능은 specs/ 가 기준
5. `docs/handoff/` — 콘솔 세션과의 인수인계 문서
- `AGENTS.md` — Codex용 지침. **계약·규칙을 바꾸면 두 파일을 함께 갱신.**

## SDD 문서 3종 (spec → plan → tasks)

feature 디렉터리(`specs/NNN-*/`)는 **항상 세 문서를 모두 갖는다.** 하나라도 없으면 코드 작성 전에 만든다.

| 문서 | 답하는 질문 |
|---|---|
| `spec.md` | 무엇을·왜 — 범위, 계약 표, 수용 기준 |
| `plan.md` | 어떻게 — 설계 결정(D1..), 영향 파일 표, 검증 전략, 리스크. **버린 대안과 이유를 남긴다** |
| `tasks.md` | 순서 — 실행 태스크 + `## 기록` 절 |

- 대화로 들어온 요청도 문서에 남긴다. 기존 spec 범위 안이면 해당 `tasks.md` `## 기록` 에 한 줄,
  범위 밖이면 새 `specs/NNN-*/` 를 만든다. plan 없이 tasks 부터 쓰지 않는다.
- 원칙과 충돌하면 constitution 개정(개정일 + 근거 spec 번호)이 코드보다 먼저다.
- maker 는 태스크를 `[maker-ready]` 까지만, 실기기 필요 항목은 `[needs-device]` (P9/P11).

## 푸시 전 체크리스트

1. `docs/CHANGELOG.md` 갱신 (같은 날짜 절이 있으면 덧붙임)
2. 해당 `specs/NNN-*/tasks.md` `## 기록` 에 태스크별 한 줄
3. **OOB 계약을 건드렸으면** 사양서 버전 업 + 콘솔 리포 동시 반영 + 완성 SHA 통보 (P5/P13)
4. `gradlew test` + `assembleDebug` (UI/매니페스트 변경 시 `lint` 추가)

## 배경 (이 문서가 존재하는 이유 — 이전 세션에서 확정된 사항)
- **짝이 되는 콘솔 앱 (Phase 2~): `D:\dev\mcandle\uwb-console-kotlin`** (Kotlin/Android 콘솔,
  별도 세션이 담당 — 이 세션은 그 리포를 수정하지 않는다, P13). 리포·태그 규칙은 그쪽
  `docs/repo_guide.md` 가 공동 원문이다. v1 짝 태그: `v1.0_controlee_advertise` ↔ `v1.0_controller_scanner`.
- 이전 짝 PC 앱: `D:\dev\radar_test_console` (Python Flet — 동결, 참조 전용).
  그쪽 `docs/QA_2026-07-03_UCI_CLI_펌웨어와_Pixel_인터롭.md`에 조사 내용 전체가 있다.
- 보드 펌웨어는 `DWM3001CDK-UCI-FreeRTOS.hex` (UCI 바이너리 프로토콜) **고정**.
  UCI 펌웨어는 스스로 동작하지 않으므로 PC의 UCI 호스트 스크립트가 보드를 구동한다.
- 검증된 역할 분담: **보드=controller, 폰=controlee** (반대 방향은 타임아웃 잦음 — Qorvo 포럼 Pixel 8 Pro 사례).
- 기준(레퍼런스) 구현: https://github.com/sasodoma/uwb-ranging
  — PC측 `run_fira_twr.py`(UCI 호스트) + 짝이 되는 Android 앱. **세션 파라미터의 기준값은
  반드시 이 리포의 쌍에서 가져올 것.** 구조 참고용 공식 샘플:
  https://github.com/android/connectivity-samples/tree/main/UwbRanging
- **실기기 E2E 검증 완료 (2026-07-17):** Galaxy controlee의 OOB 주소를 PC 콘솔이 자동
  수신한 뒤 DWM3001CDK와 연결해 레인징 정상 동작 확인 (`docs/CHANGELOG.md` 참고).
  단, 새 변경은 실물 페어 재검증 전까지 "동작한다"고 단정하지 말 것.

## 기술 스택 (고정 — 변경 금지)
- Kotlin / Jetpack Compose (단일 Activity, 단일 화면)
- `androidx.core.uwb:uwb:1.0.0` (stable; 1.1.0-alpha01은 쓰지 않는다)
- minSdk 31 (Android 12), targetSdk는 현행 최신
- 외부 라이브러리 최소화: DI 프레임워크·네트워크·DB 금지. coroutine + StateFlow면 충분.

## 대상 기기 전제
- UWB 탑재 Galaxy만 동작: Note20 Ultra, S21+/Ultra, S22+/Ultra, S23+/Ultra,
  S24+/Ultra, S25+/Ultra, Z Fold 2 이후 등. **베이스/FE 모델은 UWB 없음.**
- 설정 → 연결 → **UWB(초광대역) 토글 ON** 필수. 일부 리전 펌웨어는 UWB 비활성.
- 실행 시 `PackageManager.FEATURE_UWB` + `UWB_RANGING` 런타임 권한 확인/요청.

## UWB 세션 계약 (보드 쪽과 바이트 단위로 일치해야 함 — 이 표가 이 프로젝트의 핵심)
| 항목 | 값 (기본값) | 앱 쪽 API |
|---|---|---|
| Config | FiRa DS-TWR deferred, unicast | `RangingParameters.CONFIG_UNICAST_DS_TWR` |
| Session ID | 42 (UI에서 변경 가능) | `sessionId` |
| 채널 / 프리앰블 | 9 / 9 | `UwbComplexChannel(9, 9)` |
| Static STS | Vendor ID 2B + IV 6B = 8바이트 | `sessionKeyInfo` (기본 `08 07 01 02 03 04 05 06`) |
| 보드 주소 | short MAC 2바이트 (UI 입력, 기본 `00:00`) | `peerDevices = listOf(UwbDevice(UwbAddress(...)))` |
| 내 주소 | 세션 스코프가 발급 | `sessionScope.localAddress` → **화면에 크게 표시** |
| 갱신 주기 | FREQUENT (= 120ms, PC RANGING_DURATION=120과 쌍) | `updateRateType` |

**주의:** 위 값은 sasodoma 리포(@aad72a0)의 `run_fira_twr.py` + Android 앱에서 **쌍으로**
추출해 대조 완료 (2026-07-04, `docs/파라미터_대조_4단계.md`). STS는 PC 쪽이 리틀엔디언
정수(`VENDOR_ID 0x0708`, `STATIC_STS_IV 0x060504030201`)라서 바이트 나열이 뒤집힌다는 점에
주의. PC는 반드시 sasodoma 사본 스크립트를 기본 옵션으로 실행(slots/round 6, hopping on,
RSSI on이 이미 기본값). 파라미터가 하나라도 어긋나면 에러 없이 **조용히 아무것도 안
나온다** — 이것이 이 도메인 최대의 함정.

## OOB 계약 (BLE — PC 콘솔과 바이트 단위로 일치해야 함, 마스터: `docs/oob/BLE_OOB_인터페이스_사양서.md`)
| 항목 | 값 |
|---|---|
| Service UUID | `5F1D0001-9A8B-4C7D-B2E3-6F4A5D8C9B0A` |
| OOB_INFO Characteristic UUID | `5F1D0002-9A8B-4C7D-B2E3-6F4A5D8C9B0A` |
| OOB_INFO 속성 | Read + Notify (Write 없음) |
| 페이로드 (7B 고정) | `protocol_version`(1B, uint8, v1=`0x01`) + `uwb_address`(2B, 화면 표시 순서 그대로 — 반전 없음) + `session_id`(4B, uint32 little-endian) |

**주의:** `uwb_address`는 표시 문자열 순서 = 전송 순서(반전 금지). `session_id`는 리틀엔디언
(42 = `2A 00 00 00`, ❌ `00 00 00 2A` 아님). OOB 상수는 `UwbDefaults.kt`와 위 사양서가 유일 기준 —
이 표는 그 사본이며 불일치 시 사양서가 우선한다.

## 핵심 코드 흐름 (controlee)
```kotlin
val uwbManager = UwbManager.createInstance(context)
val sessionScope = uwbManager.controleeSessionScope()   // suspend
val myAddress = sessionScope.localAddress               // 화면 표시 → PC 스크립트 --dest-mac에 입력

val params = RangingParameters(
    uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
    sessionId = 42, subSessionId = 0,
    sessionKeyInfo = byteArrayOf(0x08, 0x07, 1, 2, 3, 4, 5, 6),
    subSessionKeyInfo = null,
    complexChannel = UwbComplexChannel(9, 9),
    peerDevices = listOf(UwbDevice(UwbAddress(boardMacBytes))),
    updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT,
)
rangingJob = scope.launch {
    sessionScope.prepareSession(params).collect { result ->
        when (result) {
            is RangingResult.RangingResultPosition -> {
                result.position.distance?.value   // 미터(Float) — cm 환산 표시
                result.position.azimuth?.value    // 도(°) — Galaxy는 AoA 지원, nullable
            }
            is RangingResult.RangingResultPeerDisconnected -> { /* 끊김 상태 표시 */ }
        }
    }
}
// 정지 = rangingJob.cancel() (Flow 취소가 곧 세션 종료)
```
매니페스트: `<uses-feature android:name="android.hardware.uwb" android:required="true"/>` +
`<uses-permission android:name="android.permission.UWB_RANGING"/>` (런타임 요청 필요).

### Start 시퀀스 (OOB 이후 확정 — af562e4)
Start를 눌러도 UWB를 즉시 열지 않는다. **BLE 광고·GATT를 먼저 열고, 콘솔이 OOB_INFO 7B를
Read한 직후에 UWB 세션을 시작**한다 (PC 스캔·연결 중 폰 세션의 10초 타임아웃이 먼저
만료되던 문제 방지). 수동 경로 폴백: BLE 권한 거부 시 즉시, OOB Read가 30초
(`OOB_READ_WAIT_TIMEOUT_MS`) 안에 없으면 타임아웃 후 보드 주소 수동 입력값으로 UWB 시작.
OOB 실패가 레인징 자체를 막으면 안 된다.

### 세션 자동 종료 처리 (6713fe3 — S24 Ultra 실기기에서 확인)
Android UWB 스택은 유효 측정 0건이면 약 10초(`ranging_error_streak_timeout_ms=10000`) 후
세션을 **자동 종료**하는데, 이때 `prepareSession` Flow는 에러 없이 조용하다:
- Flow가 예외·emit 없이 **정상 완료**되면 = 프레임워크가 세션을 내린 것.
  측정 0건이면 `ERROR`(주소/파라미터 불일치 의심 로그), 측정 후면 `DISCONNECTED`.
- Flow가 완료조차 안 하고 열려 있는 경우도 있다 → 시간 기반 워치독으로만 잡힌다:
  WAITING에서 12초(`WAITING_TIMEOUT_MS`) 내 측정 없으면 `ERROR` 전환.
- **OOB 수명 분기**: 자동 실패(`ERROR`/`DISCONNECTED`)는 GATT 유지 — 재발급된 새 폰 주소를
  Notify로 콘솔에 자동 전달(재스캔 불필요). 사용자 Stop(`IDLE`)만 GATT 종료
  (연결된 central을 명시적으로 끊은 뒤 close).
- **재Start = 연결 초기화**: 서버가 열린 채 재Start하면 유지 중인 central을 모두 끊고
  광고부터 다시 시작한다. 콘솔(PC)은 Notify 구독을 위해 연결을 유지하는 설계라,
  안 끊으면 새 Read가 오지 않아 UWB 시작이 30초 타임아웃까지 지연되고 배지도
  `연결됨`에 머문다. Notify의 목적(실패 직후 새 주소 전달)은 세션 종료 시점에 이미 달성됨.

### 백그라운드 세션 유지 (NFR-3 개정 — Foreground Service)
UWB 스택은 **포그라운드 앱 또는 Foreground Service에만 레인징을 허용**한다 — FGS 없이
백그라운드로 가면 세션이 무증상으로 내려간다(이 도메인 특유의 조용한 실패).
`RangingForegroundService`(`connectedDevice` 타입, 로직 없는 프로세스 유지용)를
Start 시 띄우고, 사용자 Stop/onCleared에서 내린다. **FGS 수명 = OOB GATT 유지 ∪ 세션
활성** — 자동 실패(`ERROR`/`DISCONNECTED`) 시에는 OOB와 함께 FGS도 유지해 백그라운드에서도
재발급 주소 Notify가 콘솔에 닿게 한다. 알림 권한(API 33+)은 Start 때 BLE 권한과 한 번에
요청(다이얼로그 연속 발사는 앞 요청이 취소됨) — 거부돼도 알림만 숨겨지고 동작은 유지.
태스크 스와이프 제거는 세션 종료(ViewModel 소유 한계 — 의도된 범위). Galaxy 절전
("잠자는 앱")이 FGS를 죽일 수 있으니 장시간 테스트는 배터리 최적화 제외 권장.

## 기능 요구사항 요약 (상세는 docs/앱_기능_화면_요구사항정의서.md — 스코프 추가 금지)
1. UWB 가용성 배너: 미탑재/토글 OFF/권한 거부를 구분해 안내
2. **내 UWB 주소 표시** (hex, 탭하면 클립보드 복사) — PC 스크립트에 입력할 값
3. 입력 필드: 보드 MAC(hex 2바이트), Session ID. 나머지 파라미터는 상수(파일 상단)
4. Start / Stop 버튼 (controlee를 먼저 시작하고 PC에서 controller를 start하는 순서를 UI에 안내 문구로)
5. 실시간 표시: 거리(cm), 각도(azimuth °, 없으면 'N/A'), 상태(대기/레인징/끊김)
6. 로그 콘솔: 타임스탬프 + 이벤트(시작/정지/측정 n건마다 1줄/끊김/에러) — 스크롤 리스트
7. **BLE OOB 자동 교환** (FR-11~16): OOB_INFO GATT 광고·Read/Notify로 주소·SessionID를 콘솔에 자동 전달.
   기본은 수동 입력 유지, OOB는 부가 경로 — 실패해도 기존 수동 흐름 무영향

## 아키텍처 규칙
- UI(Compose)는 `UwbRepository`(또는 ViewModel) 하나에만 의존. UWB API 호출을 Composable에 직접 쓰지 않는다.
- 상태는 StateFlow<UiState> 단방향. 콜백/Flow 수집은 viewModelScope.
- 모든 함수 type hint(Kotlin이므로 명시적 타입), 매직넘버 금지(상수화), 한 함수 30줄 이내.
- 세션 파라미터 기본값은 한 파일(예: `UwbDefaults.kt`)에 모아 보드 쪽과 대조하기 쉽게.

## 주요 코드 위치
- 앱 진입점: `app/src/main/java/com/mcandle/uwbcontrolee/MainActivity.kt`
- UI: `app/src/main/java/com/mcandle/uwbcontrolee/ui/MainScreen.kt`
- 상태 및 세션 조정(Start 시퀀스·워치독·OOB 수명): `app/src/main/java/com/mcandle/uwbcontrolee/MainViewModel.kt`
- 백그라운드 유지 FGS: `app/src/main/java/com/mcandle/uwbcontrolee/RangingForegroundService.kt`
- UWB API 경계: `app/src/main/java/com/mcandle/uwbcontrolee/uwb/UwbRepository.kt`
- 세션/OOB 상수: `app/src/main/java/com/mcandle/uwbcontrolee/uwb/UwbDefaults.kt`
- BLE GATT 서버: `app/src/main/java/com/mcandle/uwbcontrolee/uwb/OobGattServer.kt`
- OOB 단위 테스트: `app/src/test/java/com/mcandle/uwbcontrolee/uwb/OobPayloadTest.kt`

## 구현 순서 (한 단계씩, 각 단계 끝에서 멈춰 사용자 확인)
1. 프로젝트 스캐폴드 + 매니페스트/권한/가용성 체크 화면 (UWB 없는 기기에서도 안내가 뜨는지)
2. controleeSessionScope 획득 + 내 주소 표시 (실기기에서 주소가 나오면 성공)
3. RangingParameters 구성 + Start/Stop + 결과 Flow 수집 + 거리/각도/로그 UI
4. sasodoma 리포의 PC 스크립트와 파라미터 대조·정렬 (보드 없이 코드 리뷰 수준으로)
5. 실물 페어 테스트: 앱 Start → PC 콘솔(OOB 자동 수신 또는 `--dest-mac <폰주소>`) → 거리 확인

단계 1~5 완료 (5단계 E2E는 2026-07-17 성공). 이후 변경도 **실물 페어 재검증 전에는
"동작한다"고 단정하지 말 것** — 자동 검증(test/assembleDebug) 결과와 실기기 검증 결과를
구분해 보고한다.

## 함정 목록 (이전 세션 조사에서 확인된 것들)
- 파라미터 불일치 = 무증상 실패 (에러 콜백조차 없이 조용함)
- Galaxy UWB 토글 OFF → availability false, 앱에서 안내해야 사용자가 헤매지 않음
- controlee(앱)를 **먼저** 시작한 뒤 controller(보드)를 start해야 함
- `azimuth`는 nullable — 기기/자세에 따라 안 나올 수 있음, 'N/A' 처리
- 거리 단위: androidx는 **미터(Float)**, 레이더 콘솔 쪽은 cm — 표시할 때 환산
- 보드(DWM3001CDK)는 안테나 1개라 보드 쪽에서는 각도가 안 나옴 — 각도 검증은 폰 쪽이 담당
- 프레임워크 10초 자동 종료는 Flow에 신호가 없거나(워치독으로만 감지) 정상 완료로만 나타남
  — 위 '세션 자동 종료 처리' 참고. WAITING 고착처럼 보이면 이미 라디오는 죽어 있을 수 있다
- 세션 종료마다 폰 주소가 재발급된다 — 자동 실패 후 GATT까지 닫으면 콘솔이 옛 주소로
  보드를 돌리는 함정. BLE 광고/GATT 수명과 UWB 세션 수명은 의도적으로 다르다
- 첫 측정 전 WAITING과 측정 후 RANGING/무신호(noSignal 플래그)를 혼동하지 않는다
- 백그라운드 레인징은 FGS가 떠 있을 때만 허용 — FGS 없이 백그라운드 진입 = 무증상 세션 종료.
  FGS 시작은 반드시 포그라운드(사용자 Start)에서 (백그라운드 FGS 시작 제한)

## 검증 명령
Windows PowerShell 기준:
```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
.\gradlew.bat lint
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
변경 범위에 비례해 검증: Kotlin/UWB 로직 변경은 최소 `test` + `assembleDebug`,
UI/매니페스트/리소스 변경은 `lint`도. `adb install`·실기기 페어 테스트는 연결된 적합
기기가 있을 때만.
