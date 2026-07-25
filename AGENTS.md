# AGENTS.md — UWB Controlee 테스트 앱

## 프로젝트 목적

이 저장소는 Qorvo DWM3001CDK 보드(UCI 펌웨어, controller/initiator)와 FiRa UWB 레인징을 수행하는 Android Galaxy용 controlee 앱이다. Kotlin, Jetpack Compose, `androidx.core.uwb`를 사용한다.

상용 앱이 아니라 초도 기능 검증(Bring-up Test) 도구다. 기능 확장보다 빠르고 재현 가능한 기본 동작 확인을 우선한다.

## 작업 전 확인할 기준 문서

- 기능, 화면, 상태 머신, NFR, 검수 기준: `docs/앱_기능_화면_요구사항정의서.md`
- BLE OOB 인터페이스: `docs/oob/BLE_OOB_인터페이스_사양서.md`
- UWB 파라미터 대조 기록: `docs/파라미터_대조_4단계.md`
- 실물 보드 테스트 절차: `docs/5단계_보드_테스트_가이드.md`
- 남은 작업: `docs/TODO.md`

기능 요구사항은 요구사항정의서를 따른다. UWB 세션 파라미터와 기술 계약은 이 파일을 우선한다. OOB 상수와 페이로드 정의가 충돌하면 OOB 사양서와 `UwbDefaults.kt`를 기준으로 삼는다.

관련 PC 콘솔은 별도 저장소 `D:\dev\radar_test_console`에 있다. 현재 작업 범위가 이 Android 저장소라면 사용자의 명시적 요청 없이 다른 저장소를 수정하지 않는다.

## 고정 기술 스택

- Kotlin / Jetpack Compose
- 단일 Activity, 단일 화면
- `androidx.core.uwb:uwb:1.0.0` stable (`1.1.0-alpha01` 사용 금지)
- minSdk 31, targetSdk는 프로젝트 설정 유지
- coroutine + `StateFlow`
- DI 프레임워크, 네트워크 계층, 데이터베이스 추가 금지
- 외부 라이브러리는 꼭 필요한 경우가 아니면 추가하지 않는다.

## 대상 기기와 전제

- UWB가 탑재된 Galaxy에서만 실제 레인징이 가능하다. 베이스/FE 모델에는 UWB가 없을 수 있다.
- 기기의 설정에서 UWB(초광대역) 토글이 켜져 있어야 한다.
- 실행 시 `PackageManager.FEATURE_UWB`와 `UWB_RANGING` 런타임 권한을 확인한다.
- 역할은 보드=controller/initiator, 폰=controlee로 고정한다.
- controlee 앱을 먼저 Start한 후 보드 controller를 시작한다.
- 백그라운드 세션 유지(NFR-3): UWB 스택은 포그라운드 앱 또는 Foreground Service에만
  레인징을 허용한다. 세션 중에는 `RangingForegroundService`(`connectedDevice` 타입,
  프로세스 유지 전용)가 떠 있어야 하며, FGS 수명은 "OOB GATT 유지 ∪ 세션 활성"과 같다.
  FGS 시작은 반드시 포그라운드(사용자 Start)에서 한다. 태스크 스와이프 제거 시 세션이
  끝나는 것은 의도된 범위다.
- 실물 보드로 검증하지 않은 동작을 "동작한다"고 단정하지 않는다. 코드/단위 테스트 결과와 실기기 검증 결과를 구분해 보고한다.

## UWB 세션 계약

아래 값은 보드 측과 바이트 단위로 일치해야 한다. 하나라도 다르면 오류 없이 측정 결과가 나오지 않을 수 있다.

| 항목 | 기본값 | 앱 API |
|---|---|---|
| Config | FiRa DS-TWR deferred, unicast | `RangingParameters.CONFIG_UNICAST_DS_TWR` |
| Session ID | 42, UI에서 변경 가능 | `sessionId` |
| 채널 / 프리앰블 | 9 / 9 | `UwbComplexChannel(9, 9)` |
| Static STS | Vendor ID 2B + IV 6B = 8B | `08 07 01 02 03 04 05 06` |
| 보드 주소 | short MAC 2B, UI 입력 기본 `00:00` | `UwbDevice(UwbAddress(...))` |
| 폰 주소 | controlee 세션 스코프가 발급 | `sessionScope.localAddress` |
| 갱신 주기 | FREQUENT, 120ms | `RANGING_UPDATE_RATE_FREQUENT` |

Static STS의 PC 값은 리틀엔디언 정수이므로 바이트 나열이 뒤집힌다. 세션 기본값은 `app/src/main/java/com/mcandle/uwbcontrolee/uwb/UwbDefaults.kt` 한 곳에서 관리한다. 관련 값을 바꾸면 Android 앱, PC 스크립트, 문서 간 일치 여부를 함께 점검한다.

거리 값은 AndroidX에서 미터(Float)로 제공된다. 화면에 표시할 때 cm로 환산한다. `azimuth`는 nullable이므로 값이 없으면 `N/A`로 처리한다.

## BLE OOB 계약

| 항목 | 값 |
|---|---|
| Service UUID | `5F1D0001-9A8B-4C7D-B2E3-6F4A5D8C9B0A` |
| OOB_INFO UUID | `5F1D0002-9A8B-4C7D-B2E3-6F4A5D8C9B0A` |
| 속성 | Read + Notify, Write 없음 |
| 페이로드 | 7B 고정 |

페이로드 순서는 다음과 같다.

1. `protocol_version`: 1B uint8, v1=`0x01`
2. `uwb_address`: 2B, 화면 표시 순서 그대로 전송하며 반전하지 않음
3. `session_id`: 4B uint32 little-endian

Session ID 42는 `2A 00 00 00`이다. `00 00 00 2A`로 보내면 안 된다. OOB는 수동 입력을 대체하는 필수 경로가 아니라 부가 경로다. OOB 실패가 기존 수동 레인징 흐름을 깨뜨리지 않게 한다.

## 아키텍처와 코딩 규칙

- Compose UI에서 UWB API를 직접 호출하지 않는다. UI는 ViewModel/Repository 경계를 통해서만 기능을 사용한다.
- 상태는 `StateFlow<UiState>` 기반 단방향 흐름으로 관리한다.
- 콜백과 Flow 수집은 `viewModelScope`에서 수행하고 세션 종료 시 관련 Job을 취소한다.
- 공개 여부와 관계없이 함수의 반환 타입과 주요 지역 변수 타입을 명시한다.
- 매직 넘버를 사용하지 말고 이름 있는 상수로 정의한다.
- 함수는 가능하면 30줄 이내로 유지하고, 역할이 섞이면 작은 함수로 분리한다.
- 주소 바이트 순서, 엔디언, 단위 변환을 암묵적으로 처리하지 않는다. 코드와 테스트에서 의도를 드러낸다.
- 기존 단일 화면/단순 구조를 유지하고 요구사항에 없는 기능으로 범위를 넓히지 않는다.

## 주요 코드 위치

- 앱 진입점: `app/src/main/java/com/mcandle/uwbcontrolee/MainActivity.kt`
- UI: `app/src/main/java/com/mcandle/uwbcontrolee/ui/MainScreen.kt`
- 상태 및 세션 조정: `app/src/main/java/com/mcandle/uwbcontrolee/MainViewModel.kt`
- 백그라운드 유지 FGS: `app/src/main/java/com/mcandle/uwbcontrolee/RangingForegroundService.kt`
- UWB API 경계: `app/src/main/java/com/mcandle/uwbcontrolee/uwb/UwbRepository.kt`
- 세션/OOB 상수: `app/src/main/java/com/mcandle/uwbcontrolee/uwb/UwbDefaults.kt`
- BLE GATT 서버: `app/src/main/java/com/mcandle/uwbcontrolee/uwb/OobGattServer.kt`
- OOB 단위 테스트: `app/src/test/java/com/mcandle/uwbcontrolee/uwb/OobPayloadTest.kt`

## 변경 시 주의할 함정

- 파라미터 불일치는 에러 콜백 없이 무응답으로 나타날 수 있다.
- UWB 토글 OFF, UWB 미탑재, 권한 거부 상태를 서로 구분해 안내한다.
- `prepareSession(params)` Flow 취소가 세션 종료 수단이다.
- 첫 측정 전 WAITING과 측정 후 RANGING/무신호 상태를 혼동하지 않는다.
- 보드는 안테나가 하나이므로 보드 측 각도 결과를 기대하지 않는다. 각도 검증은 폰 측에서 한다.
- BLE 광고/GATT 수명과 UWB 세션 수명은 의도적으로 다를 수 있으므로 종료 로직을 변경할 때 재시도 흐름까지 검토한다.
- PC 콘솔은 Notify 구독을 위해 GATT 연결을 유지한다. 재Start 시 폰이 연결을 초기화해 "광고 → 연결 → Read → UWB" 흐름을 강제한다 — 이 초기화를 없애면 재Start가 30초 OOB 타임아웃까지 지연된다.
- 백그라운드 레인징은 FGS가 떠 있을 때만 허용된다. FGS 없이 백그라운드에 진입하면 세션이 에러 없이 조용히 종료된다.
- Galaxy 절전 기능(잠자는 앱)이 FGS를 죽일 수 있다. 장시간 백그라운드 테스트는 배터리 최적화 제외를 안내한다.

## 작업 방식

- 변경 전에 관련 기준 문서와 현재 구현을 함께 읽는다.
- 사용자 요청에 필요한 최소 범위만 수정한다.
- 이미 존재하는 사용자 변경을 덮어쓰거나 되돌리지 않는다.
- 세션 계약 또는 OOB 페이로드 변경에는 가능한 경우 단위 테스트를 추가한다.
- 문서와 구현의 계약이 바뀌면 관련 문서도 같은 변경에 포함한다.
- 결과 보고 시 수행한 자동 검증과 아직 필요한 실기기 검증을 명확히 구분한다.

## 검증 명령

Windows PowerShell 기준:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
.\gradlew.bat lint
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

변경 범위에 비례해 검증한다. 최소한 Kotlin/UWB 로직 변경은 `test`와 `assembleDebug`를 실행하고, UI/매니페스트/리소스 변경은 `lint`도 실행한다. `adb install` 및 실기기 페어 테스트는 연결된 적합 기기가 있을 때만 수행한다.
