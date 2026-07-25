package com.mcandle.uwbcontrolee

import android.app.Application
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.mcandle.uwbcontrolee.uwb.OobGattServer
import com.mcandle.uwbcontrolee.uwb.OobStatus
import com.mcandle.uwbcontrolee.uwb.RangingState
import com.mcandle.uwbcontrolee.uwb.UwbAvailability
import com.mcandle.uwbcontrolee.uwb.UwbDefaults
import com.mcandle.uwbcontrolee.uwb.UwbRepository
import com.mcandle.uwbcontrolee.uwb.formatUwbAddress
import com.mcandle.uwbcontrolee.uwb.hasBleOobPermissions
import com.mcandle.uwbcontrolee.uwb.parseBoardMac
import com.mcandle.uwbcontrolee.uwb.parseSessionId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 화면 전체 상태 — 단방향 StateFlow */
data class UiState(
    val availability: UwbAvailability = UwbAvailability.CHECKING,
    /** 내 UWB 주소 hex (예 "0A:3F"). 미발급이면 null → 화면엔 "--:--" (FR-3) */
    val myAddress: String? = null,
    val rangingState: RangingState = RangingState.IDLE,
    /** RANGING 중 2초 무수신 (FR-8) — 상태 배지만 바꾸고 세션은 유지 */
    val noSignal: Boolean = false,
    val boardMacInput: String = UwbDefaults.DEFAULT_BOARD_MAC,
    val sessionIdInput: String = UwbDefaults.SESSION_ID.toString(),
    val boardMacError: String? = null,
    val sessionIdError: String? = null,
    /** 마지막 측정 거리 (cm 환산). 측정 전 null → "---" (FR-6) */
    val distanceCm: Int? = null,
    /** 마지막 azimuth (도, 좌측 음수). null이면 "N/A" (FR-6) */
    val azimuthDeg: Int? = null,
    val lastMeasurementAtMillis: Long? = null,
    val logLines: List<String> = emptyList(),
    /** OOB 배지 (FR-16): OFF=배지 없음 / ADVERTISING ⚪ / CONNECTED 🔵 / UNAVAILABLE */
    val oobStatus: OobStatus = OobStatus.OFF,
    /** BLE 권한 거부됨 — OOB 안내 배너 노출 (FR-14). UWB 흐름과 무관 */
    val blePermissionDenied: Boolean = false,
) {
    val isSessionActive: Boolean
        get() = rangingState == RangingState.WAITING || rangingState == RangingState.RANGING

    /** Start 가능 조건: READY + 주소 발급 + 입력 유효 + 세션 비활성 (FR-5) */
    val canStart: Boolean
        get() = availability == UwbAvailability.READY && myAddress != null &&
            !isSessionActive && boardMacError == null && sessionIdError == null

    val canStop: Boolean
        get() = isSessionActive
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: UwbRepository = UwbRepository(application)

    /**
     * OOB GATT 서버 (FR-11~13) — Start 시 open, Stop/onCleared 시 close.
     * 이벤트는 바인더 스레드에서 올 수 있어 viewModelScope로 마샬링해 로그에 남긴다.
     */
    private val oobServer: OobGattServer = OobGattServer(
        context = application,
        onEvent = { message -> viewModelScope.launch { appendLog(message) } },
        onOobInfoRead = { viewModelScope.launch { startPendingRangingFromOob() } },
    )

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
        viewModelScope.launch {
            oobServer.status.collect { status ->
                _uiState.update { state -> state.copy(oobStatus = status) }
            }
        }
    }

    // ── 가용성 / 주소 (FR-1~3) ──────────────────────────────────────────

    /** onResume·권한 결과마다 재판정 — 설정에서 토글을 바꾸고 돌아온 경우 반영 (FR-1) */
    fun refreshAvailability() {
        viewModelScope.launch {
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
        viewModelScope.launch {
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

    /** 주소 재발급 시 OOB_INFO Notify (FR-13). 서버가 닫혀 있으면 저장만 되고 무동작 */
    private fun pushOobPayloadUpdate(addressHex: String) {
        val addressBytes: ByteArray = parseBoardMac(addressHex) ?: return
        runCatching {
            oobServer.updatePayload(UwbDefaults.buildOobPayload(addressBytes, activeSessionId))
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
        RangingForegroundService.start(getApplication())
        if (hasBleOobPermissions(getApplication())) {
            openOobServer(sessionId)
            waitForOobRead()
        } else {
            waitingForOobRead = true
            appendLog("OOB 보류 — BLE 권한 응답 대기")
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
            if (_uiState.value.isSessionActive && rangingJob == null) {
                openOobServer(activeSessionId)
                waitForOobRead()
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
        oobWaitJob = viewModelScope.launch {
            delay(OOB_READ_WAIT_TIMEOUT_MS)
            if (waitingForOobRead && _uiState.value.isSessionActive) {
                appendLog("OOB 대기 시간 초과 — 수동 입력 경로로 UWB 시작")
                beginPendingRanging("OOB 대기 시간 초과")
            }
        }
    }

    private fun beginPendingRanging(reason: String) {
        if (rangingJob != null || !_uiState.value.isSessionActive) return
        val boardMac: ByteArray = pendingBoardMac ?: return
        waitingForOobRead = false
        oobWaitJob?.cancel()
        oobWaitJob = null
        measurementCount = 0
        recentDistancesCm.clear()
        sessionStartedAtMillis = System.currentTimeMillis()
        appendLog("UWB 세션 시작 — $reason")
        rangingJob = viewModelScope.launch { collectRanging(boardMac, activeSessionId) }
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
            // FGS도 함께 유지 — 백그라운드에서 실패해도 GATT·프로세스가 살아 있어
            // 재발급 주소 Notify가 콘솔에 닿는다. 다음 Start/Stop/onCleared에서 정리.
            appendLog("OOB 유지 — 새 주소를 Notify로 콘솔에 자동 전달 (재스캔 불필요)")
        } else {
            runCatching { oobServer.close() }
            RangingForegroundService.stop(getApplication())
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
        watchdogJob = viewModelScope.launch {
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

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        rangingJob?.cancel()
        watchdogJob?.cancel()
        oobWaitJob?.cancel()
        runCatching { oobServer.close() }
        RangingForegroundService.stop(getApplication())
    }

    companion object {
        private const val CM_PER_METER: Float = 100f
        private const val MEASUREMENT_LOG_INTERVAL: Int = 10
        private const val NO_SIGNAL_TIMEOUT_MS: Long = 2_000L
        private const val WATCHDOG_INTERVAL_MS: Long = 500L
        private const val OOB_READ_WAIT_TIMEOUT_MS: Long = 30_000L

        /** 프레임워크 자동 종료(10초)보다 여유 있게 — WAITING에서 이 시간 내 측정 없으면 ERROR */
        private const val WAITING_TIMEOUT_MS: Long = 12_000L
    }
}
