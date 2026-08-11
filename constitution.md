# Constitution — UWB Controlee 앱 (uwb_controlee_app)

이 문서는 모든 feature, 모든 세션에 적용되는 **불변 원칙**이다.
spec/plan/tasks 는 바뀔 수 있으나 이 문서의 규칙은 위반할 수 없다.
원칙과 충돌하는 변경은 **이 문서 개정(개정일 + 근거 spec 번호 병기)이 코드보다 먼저다.**

> 제정 2026-08-11 (spec 001). 그전까지 이 규칙들은 `CLAUDE.md`/`AGENTS.md` 에 흩어져 있었다 —
> 여기로 승격했고, 충돌 시 이 문서가 이긴다. 짝 리포(`uwb-console-kotlin`)의 constitution 과
> 번호는 독립이다 (그쪽 P5 ≠ 이쪽 P5).

## 1. 계층 구조 (위반 금지)

```
UI (Compose, MainScreen)
   ↑ StateFlow<UiState> 만 구독
ViewModel (MainViewModel — 상태·시퀀스·수명 조정)
   ↑
uwb/ (UwbRepository = UWB API 경계, OobGattServer 등 = BLE 경계, UwbDefaults = 상수)
```

- **P1.** UI(Composable)는 ViewModel 하나에만 의존한다. `androidx.core.uwb`·`android.bluetooth`
  import 는 `uwb/` 패키지 전용이다.
- **P2.** 상태는 `StateFlow<UiState>` 단방향. 콜백(GATT 바인더 스레드 등)은 상태만 갱신하고
  UI 를 직접 만지지 않는다.
- **P3.** 페이로드 빌드·파싱은 순수 함수로 두고 JVM 단위테스트로 검증한다
  (`OobPayloadTest` 패턴 — 실기기 없이 바이트 계약을 잡는다).

## 2. 바이트 계약 (이 도메인 최대 리스크)

- **P4.** UWB 세션 파라미터의 단일 출처는 `uwb/UwbDefaults.kt` 다. 보드 쪽
  (`uwb-console-kotlin` 의 `device/uci/UciParams.kt`)과 **바이트 단위로 일치**해야 한다.
  하나만 어긋나도 에러 없이 "세션은 붙는데 측정 0건" — 무증상 실패. 임의 변경 금지.
- **P5.** BLE OOB 계약의 **마스터는 콘솔 리포** `uwb-console-kotlin/docs/oob/BLE_OOB_인터페이스_사양서.md`
  (v0.3~)다. 이 리포의 `docs/oob/` 동명 파일은 사본이다. 개정 = **버전 업 + 양 리포 동시 커밋**.
  UUID·페이로드 상수는 `UwbDefaults.kt` 로만 참조한다.
- **P6.** OOB(BLE)는 부가 경로다. **BLE 의 어떤 실패도 UWB 수동 흐름을 막지 않는다** —
  예외를 밖으로 던지지 않고 로그 + UNAVAILABLE 로 종결한다 (`OobGattServer` 의 기존 규칙).

## 3. 수명 규칙 (실기기에서 확인된 것 — 완화 금지)

- **P7.** BLE 광고/GATT 수명 ≠ UWB 세션 수명 (의도적 분리). 세션 자동 실패
  (`ERROR`/`DISCONNECTED`) 시 OOB 는 유지해 재발급 주소를 콘솔에 전달한다.
  사용자 Stop 만 OOB 를 닫는다. 재Start = 연결 초기화.
- **P8.** FGS(`RangingForegroundService`) 수명 = "OOB 유지 ∪ 세션 활성".
  FGS 없이 백그라운드 진입 = 무증상 세션 종료. FGS 시작은 반드시 포그라운드에서.

## 4. 검증 규율

- **P9.** 실물 페어(보드/콘솔) 재검증 전에는 **"동작한다"고 단정하지 않는다.**
  자동 검증(`gradlew test`/`assembleDebug`) 결과와 실기기 검증 결과를 구분해 보고한다.
  실기기 확인 항목은 `[needs-device]` 태그로 사람에게 넘긴다.
- **P10.** 테스트가 통과하도록 테스트를 약화시키지 않는다. 테스트 수정은 spec 변경이 선행된다.
- **P11.** maker 세션은 태스크를 `[maker-ready]` 까지만 표시한다 (done 금지).
  완료 판정은 fresh 세션의 checker 몫 — 기준은 해당 tasks.md 의 수용 기준.

## 5. 리포·버전 관리 (양 리포 공동 규칙)

- **P12.** 버전은 태그로 고정한다: `v<major>.<minor>_<UWB역할>_<BLE역할>`
  (예: `v1.0_controlee_advertise` ↔ 콘솔 `v1.0_controller_scanner` — **짝 태그는 같은 날 부착**).
  포크·장수 v2 브랜치·버전별 문서 분기 금지. 브랜치는 `feature/NNN-slug` = spec 1:1,
  머지 후 삭제. 상세: 콘솔 리포 `docs/repo_guide.md` (공동 규칙 원문).
- **P13.** 이 리포 세션은 콘솔 리포를 수정하지 않는다 (역방향 동일 — 교차 변경은
  handoff 문서로 요청). OOB 계약 변경 완료 시 콘솔 쪽 submodule pin bump 가 뒤따라야
  한다 — 완성 커밋 SHA 를 콘솔 세션에 통보한다.

## 6. 범위 규율

- **P14.** 이 앱은 bring-up 테스트 도구다. DI 프레임워크·네트워크·DB·멀티모듈 금지.
  기술 스택 고정: Kotlin/Compose, `androidx.core.uwb:uwb:1.0.0` stable, minSdk 31,
  coroutine + StateFlow (상세: `CLAUDE.md` 기술 스택 절).
- **P15.** UWB 역할은 불변: 보드=controller/initiator, **폰=controlee/responder.**
  Phase 2 의 3모드는 BLE 교환 방향만 바꾼다.
