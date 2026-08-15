package com.mcandle.uwbcontrolee.uwb

import android.content.Context
import android.content.SharedPreferences
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.mcandle.uwbcontrolee.RangingForegroundService
import com.mcandle.uwbcontrolee.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 세션 조정자 (spec 002 T101 — plan D4) — Start 시퀀스·워치독·OOB 수명의 단일 소유자.
 *
 * MainViewModel 에서 동작 무변경으로 추출했다 (2026-08-12, G1 승인). 추출 이유:
 * 콜드 웨이크(spec 002)는 Activity/ViewModel 없이 FGS 가 이 시퀀스를 돌려야 한다.
 * ViewModel 은 이 클래스의 위임·구독자다 — 조정자는 여기 하나뿐 (P2: 조정 단일).
 *
 * 수명: 프로세스 싱글턴([get]/[peek]). 현재는 MainViewModel 이 수명을 그대로 소유해
 * (onCleared → [shutdown]) v1 과 동작이 같고, FGS 자동 시작(T302)이 붙을 때
 * [peek] 로 접근하는 것이 예정된 확장점이다.
 */
class RangingCoordinator private constructor(private val appContext: Context) {

    /** viewModelScope 의 대체 — Main.immediate (기존과 동일한 디스패처 의미) */
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val repository: UwbRepository = UwbRepository(appContext)

    /**
     * OOB GATT 서버 (FR-11~13) — Start 시 open, Stop/shutdown 시 close.
     * 이벤트는 바인더 스레드에서 올 수 있어 scope 로 마샬링해 로그에 남긴다.
     */
    private val oobServer: OobGattServer = OobGattServer(
        context = appContext,
        onEvent = { message -> scope.launch { appendLog(message) } },
        onOobInfoRead = { scope.launch { startPendingRangingFromOob() } },
    )

    /** OOB 모드 2 (BEACON 송출, spec 001) — 모드에 따라 oobServer 대신 이쪽을 연다 (plan D1) */
    private val oobBeacon: OobBeacon = OobBeacon(
        context = appContext,
        onEvent = { message -> scope.launch { appendLog(message) } },
    )

    /** OOB 모드 3 (SCANNER 관찰, spec 001 T301) — 수신 콜백은 바인더 스레드에서 올 수 있어 마샬링 */
    private val oobScanner: OobScanner = OobScanner(
        context = appContext,
        onEvent = { message -> scope.launch { appendLog(message) } },
        onAdvertReceived = { payload -> scope.launch { onOobAdvertReceived(payload) } },
    )

    /** OOB 모드 4 (GATT-CLIENT, spec 002 T202) — 콘솔 연결·Read/Write. 콜백은 바인더 스레드 → 마샬링 */
    private val oobCentral: OobCentral = OobCentral(
        context = appContext,
        onEvent = { message -> scope.launch { appendLog(message) } },
        onBoardInfoReceived = { payload -> scope.launch { onOobBoardInfoReceived(payload) } },
    )

    /**
     * 콘솔 광고 시뮬레이터 (검수 12 테스트 보조) — 이 폰을 "가짜 콘솔"로 만들어
     * ADV_INFO(`5F1D0003`)를 보드 MAC·SID 입력값으로 송출한다. 두 번째 폰에 같은 앱을
     * 설치해 모드 3 테스트 상대로 쓰는 용도. UWB 세션·OOB 채널 수명과 완전 독립.
     */
    private val consoleSimBeacon: OobBeacon = OobBeacon(
        context = appContext,
        onEvent = { message -> scope.launch { appendLog("[시뮬] $message") } },
        serviceDataUuid = UwbDefaults.ADV_INFO_UUID,
    )

    /** 모드 영속화 (plan D3) — 키 oob_mode, 기본 ADVERTISE_GATT */
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Start 시점 Session ID — 주소 재발급 Notify 페이로드 재조립용 (FR-13) */
    private var activeSessionId: Int = UwbDefaults.SESSION_ID

    private val _uiState: MutableStateFlow<UiState> = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val logTimeFormat: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 스코프 획득 중복 방지 (onResume마다 refresh가 오므로) */
    private var acquiringAddress: Boolean = false

    private var rangingJob: Job? = null
    private var watchdogJob: Job? = null
    private var oobWaitJob: Job? = null
    private var pendingBoardMac: ByteArray? = null
    private var waitingForOobRead: Boolean = false
    /** 모드 3: 콘솔 광고 수신 대기 중 (미수신 30초 → 수동 입력값 폴백, §7-14) */
    private var waitingForScanAdvert: Boolean = false
    /** Start 시각 — WAITING 고착 감지 기준 (프레임워크 10초 자동 종료는 Flow에 신호가 없다) */
    private var sessionStartedAtMillis: Long = 0L
    private var measurementCount: Int = 0
    private val recentDistancesCm: MutableList<Int> = mutableListOf()

    /** 앱 전체 백그라운드 진입 감지 — 화면 회전에는 반응하지 않음. 로그 기록용 (NFR-3) */
    private val processLifecycleObserver: LifecycleEventObserver =
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onAppBackgrounded()
        }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        _uiState.update { state ->
            state.copy(oobMode = OobMode.fromStorageValue(prefs.getString(KEY_OOB_MODE, null)))
        }
        // 배지는 현재 모드의 채널 것만 반영 — 비활성 채널은 close 되어 OFF 로 조용하다
        scope.launch {
            oobServer.status.collect { status ->
                if (_uiState.value.oobMode == OobMode.ADVERTISE_GATT) {
                    _uiState.update { state -> state.copy(oobStatus = status) }
                }
            }
        }
        scope.launch {
            oobBeacon.status.collect { status ->
                if (_uiState.value.oobMode == OobMode.BEACON) {
                    _uiState.update { state -> state.copy(oobStatus = status) }
                }
            }
        }
        scope.launch {
            oobScanner.status.collect { status ->
                if (_uiState.value.oobMode == OobMode.SCANNER) {
                    _uiState.update { state -> state.copy(oobStatus = status) }
                }
            }
        }
        scope.launch {
            oobCentral.status.collect { status ->
                if (_uiState.value.oobMode == OobMode.CENTRAL) {
                    _uiState.update { state -> state.copy(oobStatus = status) }
                }
            }
        }
        scope.launch {
            consoleSimBeacon.status.collect { status ->
                _uiState.update { state ->
                    state.copy(consoleSimActive = status == OobStatus.ADVERTISING)
                }
            }
        }
    }

    /**
     * 콘솔 광고 시뮬레이터 토글 (검수 12 테스트 보조). 현재 보드 MAC·SID 입력값을
     * payload 로 송출한다 — 값을 바꾸려면 껐다 켠다. 배지가 아닌 버튼 문구로만 표시.
     */
    fun toggleConsoleSimulator() {
        if (_uiState.value.consoleSimActive) {
            runCatching { consoleSimBeacon.close() }
            return
        }
        val state: UiState = _uiState.value
        val boardMac: ByteArray? = parseBoardMac(state.boardMacInput)
        val sessionId: Int? = parseSessionId(state.sessionIdInput)
        if (boardMac == null || sessionId == null) {
            appendLog("[시뮬] 시작 불가 — 보드 MAC/Session ID 입력값이 유효하지 않음")
            return
        }
        appendLog(
            "[시뮬] 콘솔 광고(5F1D0003) 송출 — 보드 ${state.boardMacInput.trim()} · session $sessionId " +
                "(상대 폰을 모드 3 으로 Start)",
        )
        runCatching { consoleSimBeacon.open(UwbDefaults.buildOobPayload(boardMac, sessionId)) }
    }

    // ── OOB 모드 선택 (spec 001, plan D3) ───────────────────────────────

    /** 레인징 중 변경 금지 (사양서 규칙 0). 전환 시 유지 중이던 OOB 채널은 정리한다 */
    fun onOobModeChanged(mode: OobMode) {
        val state: UiState = _uiState.value
        if (state.oobMode == mode) return
        if (state.isSessionActive) {
            appendLog("레인징 중에는 OOB 모드를 바꿀 수 없음 (Stop 후 변경)")
            return
        }
        // 자동 실패 후 keepOob 로 살아 있던 채널도 모드 전환은 명시적 사용자 행위이므로 닫는다
        closeOobChannels()
        RangingForegroundService.stop(appContext)
        prefs.edit().putString(KEY_OOB_MODE, mode.storageValue).apply()
        _uiState.update { current -> current.copy(oobMode = mode, oobStatus = OobStatus.OFF) }
        appendLog("OOB 모드 변경: ${mode.label}")
    }

    // ── 가용성 / 주소 (FR-1~3) ──────────────────────────────────────────

    /** onResume·권한 결과마다 재판정 — 설정에서 토글을 바꾸고 돌아온 경우 반영 (FR-1) */
    fun refreshAvailability() {
        scope.launch {
            val newAvailability: UwbAvailability = repository.checkAvailability()
            applyAvailability(newAvailability)
        }
    }

    fun onPermissionResult(granted: Boolean) {
        appendLog(if (granted) "UWB_RANGING 권한 허용됨" else "UWB_RANGING 권한 거부됨")
        refreshAvailability()
    }

    /** 주소 복사 시 로그 기록 (실제 클립보드 쓰기는 UI 쪽) */
    fun onAddressCopied(address: String) {
        appendLog("내 주소 $address 클립보드에 복사됨")
    }

    private fun applyAvailability(newAvailability: UwbAvailability) {
        val previous: UwbAvailability = _uiState.value.availability
        if (previous != newAvailability) {
            appendLog("가용성: $previous → $newAvailability")
        }
        _uiState.update { state -> state.copy(availability = newAvailability) }
        if (newAvailability == UwbAvailability.READY) {
            if (_uiState.value.myAddress == null) renewControleeScope()
        } else {
            clearAddress()
        }
    }

    /** 새 controlee 스코프 발급. 주소가 바뀌면 로그로 알림 (FR-3 상세 규칙) */
    private fun renewControleeScope() {
        if (acquiringAddress) return
        acquiringAddress = true
        scope.launch {
            try {
                val address: UwbAddress = repository.acquireControleeScope()
                applyNewAddress(formatUwbAddress(address))
            } catch (t: Throwable) {
                appendLog("주소 발급 실패: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                acquiringAddress = false
            }
        }
    }

    private fun applyNewAddress(hex: String) {
        val previous: String? = _uiState.value.myAddress
        when {
            previous == null -> appendLog("내 UWB 주소 발급: $hex (PC 스크립트 --dest-mac에 입력)")
            previous != hex -> appendLog("내 주소 변경: $previous → $hex — PC에 다시 입력 필요")
        }
        _uiState.update { state -> state.copy(myAddress = hex) }
        pushOobPayloadUpdate(hex)
    }

    /**
     * 주소 재발급·SID 변경 시 갱신 전파 — 모드 1 = Notify(FR-13), 모드 2 = 광고 교체(§7-15),
     * 모드 4 = PHONE_INFO 재Write(§3-1). 전 채널에 밀어도 닫힌 쪽은 저장만 하고 무동작이라 안전.
     */
    private fun pushOobPayloadUpdate(addressHex: String) {
        val addressBytes: ByteArray = parseBoardMac(addressHex) ?: return
        runCatching {
            val payload: ByteArray = UwbDefaults.buildOobPayload(addressBytes, activeSessionId)
            oobServer.updatePayload(payload)
            oobBeacon.updatePayload(payload)
            oobCentral.updatePayload(payload)
        }
    }

    private fun clearAddress() {
        if (_uiState.value.myAddress == null) return
        repository.clearControleeScope()
        appendLog("UWB 사용 불가 상태 — 내 주소 무효화")
        _uiState.update { state -> state.copy(myAddress = null) }
    }

    // ── 입력 검증 (FR-4) ────────────────────────────────────────────────

    fun onBoardMacChanged(value: String) {
        val error: String? =
            if (parseBoardMac(value) == null) "hex 2바이트 형식 (예 00:01)" else null
        _uiState.update { state -> state.copy(boardMacInput = value, boardMacError = error) }
    }

    fun onSessionIdChanged(value: String) {
        val error: String? =
            if (parseSessionId(value) == null) "양의 정수만 가능" else null
        _uiState.update { state -> state.copy(sessionIdInput = value, sessionIdError = error) }
    }

    // ── Start / Stop (FR-5) ────────────────────────────────────────────

    fun startRanging() {
        val state: UiState = _uiState.value
        if (!state.canStart) return
        val boardMac: ByteArray = parseBoardMac(state.boardMacInput) ?: return
        val sessionId: Int = parseSessionId(state.sessionIdInput) ?: return
        measurementCount = 0
        recentDistancesCm.clear()
        appendLog("세션 준비 — controlee 대기 (보드 ${state.boardMacInput.trim()}, session $sessionId)")
        _uiState.update { current ->
            current.copy(
                rangingState = RangingState.WAITING,
                noSignal = false,
                distanceCm = null,
                azimuthDeg = null,
                lastMeasurementAtMillis = null,
            )
        }
        pendingBoardMac = boardMac
        activeSessionId = sessionId
        // NFR-3: FGS로 프로세스를 유지해야 백그라운드에서도 OOB 광고·레인징이 지속된다.
        // 사용자 Start 직후(포그라운드)라 백그라운드 FGS 시작 제한에 걸리지 않는다.
        RangingForegroundService.start(appContext)
        when (state.oobMode) {
            // 모드 1 (v1 현행): 광고 → OOB Read 대기(30초) → UWB 시작. 아래 경로는 spec 001
            // 이전과 문장 단위로 동일해야 한다 (plan D2 — 회귀 기준).
            OobMode.ADVERTISE_GATT ->
                if (hasBleOobPermissions(appContext)) {
                    openOobServer(sessionId)
                    waitForOobRead()
                } else {
                    waitingForOobRead = true
                    appendLog("OOB 보류 — BLE 권한 응답 대기")
                }
            // 모드 2: Read 가 없다 — 콘솔이 광고를 언제 봤는지 폰은 모른다 (plan D2).
            // UWB 를 즉시 시작하고, 콘솔이 늦으면 자동실패 후 광고 유지(P7)로 재Start 유도.
            OobMode.BEACON -> {
                if (hasBleOobPermissions(appContext)) {
                    openOobBeacon(sessionId)
                } else {
                    appendLog("OOB 보류 — BLE 권한 응답 대기")
                }
                beginPendingRanging("BEACON 모드 — Read 대기 없이 즉시 시작")
            }
            // 모드 3 (T301/T302): 스캔 → 콘솔 광고 수신 → 보드 MAC·SID 반영 → UWB 시작.
            // 미수신 30초 후 수동 입력값 폴백 (§7-14). §2-1 병행 송출은 수신 시점에 (T303).
            OobMode.SCANNER ->
                if (hasBleOobPermissions(appContext, OobMode.SCANNER)) {
                    openOobScanner()
                    waitForOobAdvert()
                } else {
                    waitingForScanAdvert = true
                    appendLog("OOB 보류 — BLE 권한 응답 대기")
                }
            // 모드 4 (spec 002 T203): 스캔 → 연결 → BOARD_INFO Read → PHONE_INFO Write →
            // UWB 시작. 미교환 30초 후 수동 입력값 폴백 (§7-16 — §7-14 동형).
            OobMode.CENTRAL ->
                if (hasBleOobPermissions(appContext, OobMode.CENTRAL)) {
                    openOobCentral(sessionId)
                    waitForBoardInfo()
                } else {
                    waitingForScanAdvert = true
                    appendLog("OOB 보류 — BLE 권한 응답 대기")
                }
        }
    }

    /**
     * BLE 권한 응답 (FR-14) — 허용 시 세션이 살아 있으면 OOB를 뒤늦게 연다.
     * 거부는 OOB만 비활성: UWB 수동 흐름은 그대로, 배너로만 안내.
     */
    fun onBlePermissionResult(granted: Boolean) {
        _uiState.update { state -> state.copy(blePermissionDenied = !granted) }
        if (granted) {
            appendLog("BLE 권한 허용됨")
            if (!_uiState.value.isSessionActive) return
            when (_uiState.value.oobMode) {
                OobMode.ADVERTISE_GATT -> if (rangingJob == null) {
                    openOobServer(activeSessionId)
                    waitForOobRead()
                }
                // 모드 2 는 UWB 가 이미 돌고 있다 — 광고만 뒤늦게 합류
                OobMode.BEACON -> openOobBeacon(activeSessionId)
                OobMode.SCANNER -> if (rangingJob == null) {
                    openOobScanner()
                    waitForOobAdvert()
                }
                OobMode.CENTRAL -> if (rangingJob == null) {
                    openOobCentral(activeSessionId)
                    waitForBoardInfo()
                }
            }
        } else {
            appendLog("BLE 권한 거부됨 — OOB 비활성, 수동 레인징 시작")
            beginPendingRanging("BLE 권한 없음")
        }
    }

    /** OOB Read 직후 폰 UWB를 시작해 PC 보드 시작과 10초 타임아웃 창을 맞춘다. */
    private fun startPendingRangingFromOob() {
        if (!waitingForOobRead || !_uiState.value.isSessionActive) return
        beginPendingRanging("OOB_INFO 전달 완료")
    }

    private fun waitForOobRead() {
        if (rangingJob != null) return
        waitingForOobRead = true
        oobWaitJob?.cancel()
        appendLog("OOB_INFO Read 대기 — 읽힌 직후 UWB 시작")
        oobWaitJob = scope.launch {
            delay(OOB_READ_WAIT_TIMEOUT_MS)
            if (waitingForOobRead && _uiState.value.isSessionActive) {
                appendLog("OOB 대기 시간 초과 — 수동 입력 경로로 UWB 시작")
                beginPendingRanging("OOB 대기 시간 초과")
            }
        }
    }

    /** 모드 3: 콘솔 광고 수신 대기 (T302). 미수신 시 수동 입력값 폴백 (§7-14) */
    private fun waitForOobAdvert() {
        if (rangingJob != null) return
        waitingForScanAdvert = true
        oobWaitJob?.cancel()
        appendLog("콘솔 광고 수신 대기(최대 ${UwbDefaults.SCAN_WAIT_TIMEOUT_MS / 1000}초) — 수신 즉시 자동 반영")
        oobWaitJob = scope.launch {
            delay(UwbDefaults.SCAN_WAIT_TIMEOUT_MS)
            if (waitingForScanAdvert && _uiState.value.isSessionActive) {
                appendLog("콘솔 광고 미수신 — 수동 입력값으로 UWB 시작 (양쪽 모드가 짝인지 확인, §7-11/14)")
                beginPendingRanging("스캔 대기 시간 초과")
            }
        }
    }

    /**
     * 모드 3: APPLY 판정 광고 수신 (T302/T303) — 보드 MAC·SID 반영 → 병행 송출 → UWB 시작.
     * 스캔은 유지한다 (§2-1 병행안: 관찰 + 송출 동시 — 콘솔 광고 변경 감지도 겸함).
     */
    private fun onOobAdvertReceived(payload: ByteArray) {
        if (!_uiState.value.isSessionActive) return
        val info: OobInfo = UwbDefaults.parseOobPayload(payload) ?: run {
            appendLog("콘솔 광고 파싱 실패 (${payload.size}B) — 무시")
            return
        }
        if (info.protocolVersion != UwbDefaults.OOB_PROTOCOL_VERSION.toInt()) {
            appendLog("경고 — OOB 버전 ${info.protocolVersion} 수신, v1 로 해석 시도 (사양서 §4)")
        }
        if (rangingJob != null) {
            appendLog("콘솔 광고 변경 감지 (보드 ${info.addressHex}, session ${info.sessionId}) — 세션 중 변경 미지원, 재Start 필요")
            return
        }
        waitingForScanAdvert = false
        appendLog("콘솔 광고 수신 — 보드 ${info.addressHex} · session ${info.sessionId} 자동 반영")
        _uiState.update { state ->
            state.copy(
                boardMacInput = info.addressHex,
                boardMacError = null,
                sessionIdInput = info.sessionId.toString(),
                sessionIdError = null,
            )
        }
        pendingBoardMac = parseBoardMac(info.addressHex)
        activeSessionId = info.sessionId
        openOobBeacon(info.sessionId) // §2-1 병행 송출 (T303) — 콘솔이 폰 주소(DST_MAC) 확보 경로
        beginPendingRanging("콘솔 광고 수신")
    }

    /** 모드 4: BOARD_INFO 교환 대기 (T203). 미교환 시 수동 입력값 폴백 (§7-16) */
    private fun waitForBoardInfo() {
        if (rangingJob != null) return
        waitingForScanAdvert = true // 의미: "콘솔발 파라미터 대기" — 모드 3 과 공용 플래그
        oobWaitJob?.cancel()
        appendLog("콘솔 GATT 교환 대기(최대 ${UwbDefaults.SCAN_WAIT_TIMEOUT_MS / 1000}초) — 연결·Read 즉시 자동 반영")
        oobWaitJob = scope.launch {
            delay(UwbDefaults.SCAN_WAIT_TIMEOUT_MS)
            if (waitingForScanAdvert && _uiState.value.isSessionActive) {
                appendLog("콘솔 GATT 교환 미완료 — 수동 입력값으로 UWB 시작 (양쪽 모드가 짝인지 확인, §7-11/16)")
                beginPendingRanging("GATT 교환 대기 시간 초과")
            }
        }
    }

    /**
     * 모드 4: BOARD_INFO Read 성공 (T203) — 보드 MAC·SID 반영 → UWB 시작.
     * PHONE_INFO Write 는 OobCentral 이 Read 직후 수행하고, SID 가 바뀌면 아래
     * pushOobPayloadUpdate 경유 재Write 로 정정된다 (Write Without Response — 부담 없음).
     */
    private fun onOobBoardInfoReceived(payload: ByteArray) {
        if (!_uiState.value.isSessionActive) return
        val info: OobInfo = UwbDefaults.parseOobPayload(payload) ?: run {
            appendLog("BOARD_INFO 파싱 실패 (${payload.size}B) — 무시 (§7-13)")
            return
        }
        if (info.protocolVersion != UwbDefaults.OOB_PROTOCOL_VERSION.toInt()) {
            appendLog("경고 — OOB 버전 ${info.protocolVersion} 수신, v1 로 해석 시도 (사양서 §4)")
        }
        if (rangingJob != null) {
            appendLog("BOARD_INFO 변경 감지 (보드 ${info.addressHex}, session ${info.sessionId}) — 세션 중 변경 미지원, 재Start 필요")
            return
        }
        waitingForScanAdvert = false
        appendLog("BOARD_INFO 수신 — 보드 ${info.addressHex} · session ${info.sessionId} 자동 반영")
        _uiState.update { state ->
            state.copy(
                boardMacInput = info.addressHex,
                boardMacError = null,
                sessionIdInput = info.sessionId.toString(),
                sessionIdError = null,
            )
        }
        pendingBoardMac = parseBoardMac(info.addressHex)
        activeSessionId = info.sessionId
        // SID 가 Start 시점 값과 다르면 PHONE_INFO 를 새 SID 로 재Write (콘솔 SID 일치 검증 대비)
        _uiState.value.myAddress?.let(::pushOobPayloadUpdate)
        beginPendingRanging("BOARD_INFO 수신 (GATT 교환 완료)")
    }

    private fun beginPendingRanging(reason: String) {
        if (rangingJob != null || !_uiState.value.isSessionActive) return
        val boardMac: ByteArray = pendingBoardMac ?: return
        waitingForOobRead = false
        waitingForScanAdvert = false
        oobWaitJob?.cancel()
        oobWaitJob = null
        measurementCount = 0
        recentDistancesCm.clear()
        sessionStartedAtMillis = System.currentTimeMillis()
        appendLog("UWB 세션 시작 — $reason")
        rangingJob = scope.launch { collectRanging(boardMac, activeSessionId) }
        startWatchdog()
    }

    /** OOB 서버 시작 — 어떤 실패도 UWB 흐름을 막지 않는다 (로그로만 종결) */
    private fun openOobServer(sessionId: Int) {
        val addressBytes: ByteArray? = _uiState.value.myAddress?.let(::parseBoardMac)
        if (addressBytes == null) {
            appendLog("OOB 생략 — 내 주소를 2바이트로 해석 불가")
            return
        }
        try {
            val payload: ByteArray = UwbDefaults.buildOobPayload(addressBytes, sessionId)
            oobServer.open(payload) // 자동 실패 후 재Start면 이미 열려 있음 (no-op)
            oobServer.updatePayload(payload) // Session ID 변경 등 최신값 반영
        } catch (t: Throwable) {
            appendLog("OOB 시작 실패(무시): ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 모드 3 SCANNER 시작 (spec 001 T301) — openOobServer 와 동일한 실패 무전파 규칙 (P6) */
    private fun openOobScanner() {
        try {
            oobScanner.open()
        } catch (t: Throwable) {
            appendLog("OOB 시작 실패(무시): ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 모드 4 CENTRAL 시작 (spec 002 T203) — openOobServer 와 동일한 실패 무전파 규칙 (P6) */
    private fun openOobCentral(sessionId: Int) {
        val addressBytes: ByteArray? = _uiState.value.myAddress?.let(::parseBoardMac)
        if (addressBytes == null) {
            appendLog("OOB 생략 — 내 주소를 2바이트로 해석 불가")
            return
        }
        try {
            oobCentral.open(UwbDefaults.buildOobPayload(addressBytes, sessionId))
        } catch (t: Throwable) {
            appendLog("OOB 시작 실패(무시): ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 모드 2 BEACON 시작 (spec 001 T203) — openOobServer 와 동일한 실패 무전파 규칙 (P6) */
    private fun openOobBeacon(sessionId: Int) {
        val addressBytes: ByteArray? = _uiState.value.myAddress?.let(::parseBoardMac)
        if (addressBytes == null) {
            appendLog("OOB 생략 — 내 주소를 2바이트로 해석 불가")
            return
        }
        try {
            oobBeacon.open(UwbDefaults.buildOobPayload(addressBytes, sessionId))
        } catch (t: Throwable) {
            appendLog("OOB 시작 실패(무시): ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun stopRanging() {
        if (!_uiState.value.canStop) return
        appendLog("레인징 정지 (Stop)")
        endSession(RangingState.IDLE)
    }

    private suspend fun collectRanging(boardMac: ByteArray, sessionId: Int) {
        try {
            repository.rangingResults(repository.buildRangingParameters(boardMac, sessionId))
                .collect { result -> handleRangingResult(result) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            appendLog("세션 오류: $t")
            endSession(RangingState.ERROR)
            return
        }
        onRangingFlowCompleted()
    }

    /**
     * prepareSession Flow가 예외·emit 없이 정상 완료 = 프레임워크가 세션을 내린 것.
     * (예: ranging_error_streak_timeout 10초 — 유효 측정 0건이면 스택이 자동 종료)
     * 이걸 안 잡으면 UI가 WAITING에 고착된 채 라디오만 죽는다 — 실기기에서 확인된 버그.
     */
    private fun onRangingFlowCompleted() {
        if (!_uiState.value.isSessionActive) return // Stop 등으로 이미 정리됨
        if (measurementCount == 0) {
            appendLog(
                "세션 종료 감지 — 유효 측정 0건, 프레임워크 자동 종료(약 10초). " +
                    "보드 미송신 또는 주소/파라미터 불일치 의심",
            )
            endSession(RangingState.ERROR)
        } else {
            appendLog("세션 종료 감지 — 프레임워크가 세션을 닫음 (측정 ${measurementCount}건 수신 후)")
            endSession(RangingState.DISCONNECTED)
        }
    }

    /**
     * NFR-3 개정: 백그라운드에서도 세션을 유지한다 — FGS가 프로세스 importance를 보장.
     * 만약 FGS 없이 백그라운드로 갔다면 UWB 스택이 세션을 무증상으로 내리는데,
     * 그 경우는 Flow 정상 완료(onRangingFlowCompleted)나 WAITING 워치독이 잡는다.
     */
    private fun onAppBackgrounded() {
        if (!_uiState.value.isSessionActive) return
        appendLog("앱 백그라운드 진입 — Foreground Service로 세션 유지")
    }

    /**
     * 세션 종료 공통 처리: job 취소(NFR-4) + OOB 정리 + 스코프 재발급(주소 변경 감지).
     *
     * OOB 수명 분기: 사용자 Stop(IDLE)은 사양서 §5대로 GATT 종료. 반면 자동 실패
     * (ERROR/DISCONNECTED)는 OOB를 유지한다 — 종료 직후 재발급되는 새 주소가
     * Notify로 콘솔에 자동 전달돼, 재시도 때 콘솔이 재스캔 없이 새 주소를 갖게 된다.
     * (실패마다 주소가 바뀌는데 GATT까지 끊으면 콘솔이 옛 주소로 보드를 돌리는 함정)
     */
    private fun endSession(finalState: RangingState) {
        waitingForOobRead = false
        waitingForScanAdvert = false
        pendingBoardMac = null
        oobWaitJob?.cancel()
        oobWaitJob = null
        rangingJob?.cancel()
        rangingJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        val keepOob: Boolean =
            finalState == RangingState.ERROR || finalState == RangingState.DISCONNECTED
        if (keepOob) {
            // FGS도 함께 유지 — 백그라운드에서 실패해도 채널·프로세스가 살아 있어
            // 재발급 주소가 콘솔에 닿는다 (모드 1=Notify, 모드 2=광고 교체, 모드 4=재Write — P7).
            // 다음 Start/Stop/모드 전환/shutdown에서 정리.
            appendLog("OOB 유지 — 새 주소를 콘솔에 자동 전달 (모드 1 Notify / 모드 2 광고 교체 / 모드 4 재Write)")
        } else {
            closeOobChannels()
            RangingForegroundService.stop(appContext)
        }
        repository.clearControleeScope()
        _uiState.update { state -> state.copy(rangingState = finalState, noSignal = false) }
        renewControleeScope()
    }

    // ── 측정 결과 (FR-6/7/9) ────────────────────────────────────────────

    private fun handleRangingResult(result: RangingResult) {
        when (result) {
            is RangingResult.RangingResultPosition -> handlePosition(result)
            is RangingResult.RangingResultPeerDisconnected -> {
                appendLog("피어 끊김 (PeerDisconnected)")
                endSession(RangingState.DISCONNECTED)
            }
            else -> appendLog("알 수 없는 레인징 결과: $result")
        }
    }

    private fun handlePosition(result: RangingResult.RangingResultPosition) {
        measurementCount += 1
        val distanceCm: Int? = result.position.distance?.value
            ?.let { meters -> (meters * CM_PER_METER).roundToInt() }
        val azimuthDeg: Int? = result.position.azimuth?.value?.roundToInt()
        if (measurementCount == 1) appendLog("첫 측정 수신")
        if (_uiState.value.noSignal) appendLog("측정 재개 (수신없음 해제)")
        distanceCm?.let { recentDistancesCm.add(it) }
        logMeasurementSummaryIfDue()
        _uiState.update { state ->
            state.copy(
                rangingState = RangingState.RANGING,
                noSignal = false,
                distanceCm = distanceCm,
                azimuthDeg = azimuthDeg,
                lastMeasurementAtMillis = System.currentTimeMillis(),
            )
        }
    }

    /** 측정 10건마다 요약 1줄 — 로그 폭주 방지 (FR-9) */
    private fun logMeasurementSummaryIfDue() {
        if (measurementCount % MEASUREMENT_LOG_INTERVAL != 0) return
        val averageCm: Int? =
            if (recentDistancesCm.isEmpty()) null else recentDistancesCm.average().roundToInt()
        appendLog("측정 ${measurementCount}건 (최근 avg ${averageCm?.toString() ?: "-"}cm)")
        recentDistancesCm.clear()
    }

    // ── 무수신 워치독 (FR-8) ────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                updateNoSignal()
            }
        }
    }

    private fun updateNoSignal() {
        val state: UiState = _uiState.value
        if (state.rangingState == RangingState.WAITING) {
            checkWaitingTimeout()
            return
        }
        if (state.rangingState != RangingState.RANGING || state.noSignal) return
        val lastAtMillis: Long = state.lastMeasurementAtMillis ?: return
        if (System.currentTimeMillis() - lastAtMillis > NO_SIGNAL_TIMEOUT_MS) {
            appendLog("수신없음 — ${NO_SIGNAL_TIMEOUT_MS / 1000}초간 측정 없음 (세션 유지)")
            _uiState.update { current -> current.copy(noSignal = true) }
        }
    }

    /**
     * WAITING 고착 감지 — 프레임워크는 유효 측정 0건이면 약 10초에 세션을 자동 종료하는데
     * (ranging_error_streak_timeout_ms=10000), 이때 prepareSession Flow는 완료도 emit도
     * 없이 조용히 열려 있다 (S24 Ultra 실기기에서 확인). 시간 기반으로만 잡을 수 있다.
     */
    private fun checkWaitingTimeout() {
        if (System.currentTimeMillis() - sessionStartedAtMillis <= WAITING_TIMEOUT_MS) return
        appendLog(
            "레인징 실패 — ${WAITING_TIMEOUT_MS / 1000}초 내 측정 없음. " +
                "프레임워크가 세션을 이미 내렸을 수 있음 (보드 미송신 또는 주소/파라미터 불일치 의심)",
        )
        endSession(RangingState.ERROR)
    }

    // ── 공통 ────────────────────────────────────────────────────────────

    /** 타임스탬프 한 줄 추가, 상한 초과 시 앞에서 삭제 (FR-9, NFR-5) */
    private fun appendLog(message: String) {
        val line: String = "${logTimeFormat.format(Date())} $message"
        _uiState.update { state ->
            val lines: List<String> = (state.logLines + line)
                .takeLast(UwbDefaults.MAX_LOG_LINES)
            state.copy(logLines = lines)
        }
    }

    /** 모든 OOB 채널을 닫는다 — 열려 있지 않은 쪽 close 는 no-op 이라 항상 안전 */
    private fun closeOobChannels() {
        runCatching { oobServer.close() }
        runCatching { oobBeacon.close() }
        runCatching { oobScanner.close() }
        runCatching { oobCentral.close() }
    }

    /**
     * 전체 종료 — 기존 MainViewModel.onCleared 와 동일한 정리 + 싱글턴 해제.
     * 현재 호출자는 ViewModel 하나이므로 앱 수명과 동작이 v1 과 같다 (동작 무변경).
     */
    fun shutdown() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        rangingJob?.cancel()
        watchdogJob?.cancel()
        oobWaitJob?.cancel()
        closeOobChannels()
        runCatching { consoleSimBeacon.close() }
        RangingForegroundService.stop(appContext)
        scope.cancel()
        release(this)
    }

    companion object {
        private const val CM_PER_METER: Float = 100f
        private const val MEASUREMENT_LOG_INTERVAL: Int = 10
        private const val NO_SIGNAL_TIMEOUT_MS: Long = 2_000L
        private const val WATCHDOG_INTERVAL_MS: Long = 500L
        private const val OOB_READ_WAIT_TIMEOUT_MS: Long = 30_000L

        /** 프레임워크 자동 종료(10초)보다 여유 있게 — WAITING에서 이 시간 내 측정 없으면 ERROR */
        private const val WAITING_TIMEOUT_MS: Long = 12_000L

        /** 모드 영속화 (spec 001, plan D3) */
        private const val PREFS_NAME: String = "uwb_controlee_prefs"
        private const val KEY_OOB_MODE: String = "oob_mode"

        @Volatile
        private var instance: RangingCoordinator? = null

        /** 프로세스 싱글턴 획득 — ViewModel(현재)과 FGS 자동 시작(spec 002 T302 예정)이 공용 */
        fun get(context: Context): RangingCoordinator =
            instance ?: synchronized(this) {
                instance ?: RangingCoordinator(context.applicationContext).also { instance = it }
            }

        /** 살아 있는 인스턴스만 — 콜드 웨이크 경로에서 "이미 돌고 있는지" 판별용 (T302) */
        fun peek(): RangingCoordinator? = instance

        private fun release(target: RangingCoordinator) {
            synchronized(this) {
                if (instance === target) instance = null
            }
        }
    }
}
