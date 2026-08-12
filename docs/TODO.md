# TODO — BLE OOB 3모드(spec 001) 검수·마무리 + 콜드 웨이크(spec 002) 착수

- 작성일: 2026-08-12 (이전 버전: v1 OOB 도입기 TODO — 전부 해소됨, git 이력 참조)
- 기준 브랜치: `feature/001-ble-3mode`
- **이 문서는 다른 사람이 받아 테스트를 이어갈 수 있게 쓴 인수인계 문서다.**
  정독 순서: `CLAUDE.md` → `constitution.md` → `specs/001-ble-3mode/` 3종 → 이 문서.

## 현재 상태 요약

| 구분 | 상태 |
|---|---|
| spec 001 코드 (모드 1·2·3 + 병행 송출 + 모드 UI) | 전부 `[maker-ready]` — `test`/`assembleDebug`/`lint` green |
| 사양서 | **v0.4 확정** (§2-1 = 병행 송출, 마스터=콘솔 리포). 이 리포 사본 커밋됨 |
| 콘솔 쪽 (uwb-console-kotlin, spec 008) | 3모드 전부 maker-ready (`feat/008-ble-beacon`) |
| 실기기 검수 | **검수 14 통과** (모드 2 광고 프레임, nRF Connect). 10·11·12·13 진행 중 |
| v1/v2 동시 설치 | 양 리포 모두 applicationId `.v2` 분리 완료 (`…uwbcontrolee.v2` / `…uwbconsole.v2`) |
| spec 002 (콜드 웨이크) | 문서 3종만 작성 — **코드 착수 금지 게이트 G1·G2 있음** (아래) |

⚠ P9: 검수 10~13 통과 전까지 모드 2·3을 "동작한다"고 단정하지 말 것.

---

## A. 진행 중 — 실기기 검수 (사람, [needs-device])

**절차·함수 추적은 `docs/guide/검수10-13_함수_호출_맵.md` 를 펴놓고 진행** (검수별 시퀀스
다이어그램 + 로그 문구↔소스 함수 매핑 표 포함).

### 준비물·설치

- 폰 ①: UWB Galaxy (controlee, 이 리포 앱) / 폰 ②: 콘솔 앱 폰 / DWM3001CDK 보드(USB)
- controlee 설치: `.\gradlew.bat assembleDebug` → `adb install -r app\build\outputs\apk\debug\app-debug.apk`
- 콘솔 설치: 콘솔 리포에서 `.\gradlew.bat assembleHwDebug` →
  `app\build\outputs\apk\hw\debug\app-hw-debug.apk` (**hw flavor** — sim 은 가짜 BLE/보드라 인터롭 불가)
- 콘솔 폰의 앱이 "UWB Console 2"(v2, `feat/008-ble-beacon` 빌드)인지 확인 — v1 은 SCANNER 만 있음

### 검수 항목 (권장 순서, 결과를 `specs/001-ble-3mode/tasks.md` `## 기록` 에 남길 것)

- [ ] **검수 11 — 모드 1 회귀** (T502 후반): 콘솔 SCANNER ↔ 폰 모드 1. v1 흐름
      (광고→연결→Read→UWB→거리) 그대로면 통과. 실패 시 `MainViewModel.startRanging` 의
      `when(oobMode)` 분기 도입이 유일한 변경점 — 여기부터 의심.
- [ ] **검수 10 — 모드 2 E2E** (T502 전반): **콘솔 BEACON 관찰을 먼저 시작** → 폰 모드 2 Start.
      3초 내 콘솔에 폰 주소 자동 반영 + 레인징. 추가: 보드 없이 자동실패 유도 → 주소 재발급 →
      콘솔 "광고 내용 변경 감지" 로그(§7-15) 확인.
      ⚠ R1: 순서를 반대로 하면 폰 세션 10초 자동종료 가능 — 광고는 유지되므로 재Start 가 정상 복구.
- [ ] **검수 12 — 모드 3 E2E** (T503 전반): 콘솔 ADVERTISE(송출+관찰) → 폰 모드 3 Start.
      확인 체인: ① 폰 입력칸 자동 반영("🔵 광고 수신됨") ② 폰 병행 송출 로그
      ③ **콘솔에 "폰 주소 자동 반영" 로그 + 자동 레인징 시작** (§2-1 병행안의 핵심) ④ 거리 표시.
- [ ] **검수 13 — 짝 불일치** (T503 후반): 예) 콘솔 BEACON ↔ 폰 모드 1. 양쪽 다 크래시 없이
      "발견 못함 + 모드 짝 확인" 안내로 종결 + 폰 30초 수동 폴백이면 통과.
- [x] 검수 14 — 모드 2 광고 프레임/31B (2026-08-12 사용자 통과 보고)

### 검수 실패 시

- 로그 문구로 함수맵 부록 표에서 발생 지점을 찾는다. BLE 는 부가 경로(P6)라 어떤 실패도
  UWB 수동 흐름을 막으면 안 된다 — 막으면 그 자체가 P6 위반 버그.
- 파라미터 무증상 실패(세션은 붙는데 측정 0건)는 `UwbDefaults` ↔ 콘솔 `UciParams` 바이트 대조부터.

---

## B. 검수 통과 후 — 마무리 절차 (사람 + 세션)

1. [ ] 검수 결과를 tasks.md T501~T503 에 기록 (완료 `[x]` 판정은 checker 세션 몫 — P11)
2. [ ] **T003 `[human]`**: v1 태그 위치 결정 — 이번 push 로 fix 2건(5f0ee85, 6871967)이
       원격에 올라감. `v1.0_controlee_advertise` 를 fix 포함 지점으로 재태그할지 결정
       (옮기면 콘솔 submodule pin 도 함께 bump — handoff §7)
3. [ ] main 머지 (PR) + 브랜치 삭제 (P12)
4. [ ] **T504 `[human]`**: 짝 태그 부착 — `v2.0_controlee_scanner` (이 리포) ↔
       `v2.0_controller_beacon` (콘솔). **같은 날 부착** (P12)
5. [ ] **T404**: 완성 커밋 SHA 를 콘솔 세션에 통보 (`docs/handoff/` 역방향 문서) →
       콘솔이 submodule pin bump (P13, repo_guide §6 원자 규칙)

---

## C. 다음 스텝 — spec 002 콜드 웨이크 (문서만 있음, 코드 0줄)

목표: 앱 미실행 상태에서 콘솔 비콘(`5F1D0003`) 수신 → OS 가 앱을 깨움 → FGS → 모드 3
자동 시퀀스. 상세: `specs/002-cold-wake/` (spec → plan → tasks).

**착수 게이트 — 이것들이 풀리기 전 코드 금지:**

- [ ] **G0 `[human]` (신규, 2026-08-12)**: **iOS 지원 여부 결정** — iOS 를 범위에 넣으면
      현행 002 설계(Android PendingIntent)가 성립하지 않고 **모드 4(GATT 역방향)** 기반
      재설계가 필요하다. 근거·설계: `docs/guide/ble_dev_guide.md` **§5·§7**, 비교표는
      같은 문서 **§8 FAQ Q12**. 코드가 0줄인 지금이 방향 전환 비용이 가장 싸므로 G1 보다 먼저.
- [ ] **G1 `[human]`**: plan D2 승인 — 세션 조정 로직을 `MainViewModel` 에서
      `RangingCoordinator`(비-UI 싱글턴)로 추출하는 구조 변경. 승인 없이 착수 금지.
- [ ] **G2 `[needs-device]`**: 위 A 절 검수 통과 (리팩터 회귀 기준선 — plan R3)
- [ ] 게이트 해제 후 최우선: **T101 스파이크** — PendingIntent 스캔 + Receiver 에서 FGS
      기동이 Galaxy 에서 실제 허용되는지 (콜드 웨이크 성립 조건, 문서상 가능하나 미검증).
      불허로 판명되면 대기(Arm) 방식(상시 FGS)으로 spec 개정 후 재계획 (사용자 합의 있음).
- 착수 시 새 브랜치 `feature/002-cold-wake` (P12 — 이 브랜치에 002 코드를 넣지 말 것)

---

## D. 참고 — 테스트 보조 도구·함정

- **"콘솔시뮬" 버튼** (폰 앱, 고정 파라미터 줄 오른쪽): 이 폰을 가짜 콘솔(`5F1D0003` 송출,
  보드 MAC·SID 입력값)로 만든다. 콘솔 앱 없이 폰 2대로 모드 3 을 예비 검증하는 용도 —
  정식 검수는 반드시 실제 콘솔로. 첫 탭에서 권한 요청이 뜨면 허용 후 한 번 더 탭.
- 폰 ②가 UWB 없는 기기여도 adb 설치는 가능 (uses-feature 는 스토어 필터일 뿐) — 시뮬 용도로 충분.
- 장시간 테스트: Galaxy "잠자는 앱" 이 FGS 를 죽일 수 있음 — 배터리 최적화 제외 권장.
- push 가 무한 대기하면 GCM 자격증명 만료 — credential fill + `-c http.extraheader` 우회
  (메모리/이전 세션 기록 참조).
- 검증 명령: `.\gradlew.bat test` / `assembleDebug` / `lint` (UI·매니페스트 변경 시 lint 필수).
