# CHANGELOG — UWB Controlee 테스트 앱

> 날짜별 변경 이력. 새 작업을 커밋할 때마다 **맨 위에** 항목을 추가한다.
> 상세 요구사항·검증 절차는 `앱_기능_화면_요구사항정의서.md`, `TODO.md`,
> `5단계_보드_테스트_가이드.md` 참고.

## 2026-07-17

### OOB 실기기 E2E + 레인징 시작 타이밍 안정화 (`6713fe3` + 후속 변경)

- **자동 종료 감지**: 유효 측정 없이 Android UWB 스택이 약 10초 후 세션을 내리는
  경우를 감지해 UI가 `WAITING`에 고착되지 않고 `ERROR`로 전환하도록 수정. 측정 후
  Flow가 끝난 경우는 `DISCONNECTED`로 구분하고 원인 추정 로그를 남김
- **OOB와 UWB 시작 시점 정렬**: Start 직후 UWB를 먼저 열지 않고 BLE 광고·GATT를 연
  뒤 콘솔이 `OOB_INFO` 7B를 Read한 직후 UWB 세션을 시작. PC 스캔·연결 중 폰 세션의
  10초 타임아웃이 먼저 만료되던 문제를 방지
- **수동 경로 보존**: BLE 권한 거부 시 즉시, OOB Read가 30초 안에 없으면 타임아웃 후
  기존 보드 주소 수동 입력값으로 UWB를 시작해 OOB 실패가 레인징 자체를 막지 않게 함
- **재시도 주소 동기화**: 자동 실패(`ERROR`/`DISCONNECTED`)에서는 GATT를 유지하고
  재발급된 폰 주소를 Notify로 전달. 사용자가 Stop하면 연결된 central을 명시적으로
  끊은 뒤 광고와 GATT 서버를 종료
- `AGENTS.md` 추가 — UWB/OOB 바이트 계약, 저장소 경계, 아키텍처·코딩 규칙,
  자동 검증과 실물 보드 검수 원칙 정리
- **자동 검증**: `.\gradlew.bat test assembleDebug` 성공
- **실기기 E2E 성공**: Galaxy controlee의 OOB 주소를 PC 콘솔이 자동 수신한 뒤
  DWM3001CDK와 연결해 레인징이 정상 동작함을 사용자 확인
