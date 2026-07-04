package com.mcandle.uwbcontrolee

import android.app.Application
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.mcandle.uwbcontrolee.uwb.RangingState
import com.mcandle.uwbcontrolee.uwb.UwbAvailability
import com.mcandle.uwbcontrolee.uwb.UwbDefaults
import com.mcandle.uwbcontrolee.uwb.UwbRepository
import com.mcandle.uwbcontrolee.uwb.formatUwbAddress
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

    private val _uiState: MutableStateFlow<UiState> = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val logTimeFormat: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 스코프 획득 중복 방지 (onResume마다 refresh가 오므로) */
    private var acquiringAddress: Boolean = false

    private var rangingJob: Job? = null
    private var watchdogJob: Job? = null
    private var measurementCount: Int = 0
    private val recentDistancesCm: MutableList<Int> = mutableListOf()

    /** 앱 전체 백그라운드 진입 감지 — 화면 회전에는 반응하지 않음 (NFR-3) */
    private val processLifecycleObserver: LifecycleEventObserver =
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onAppBackgrounded()
        }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
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
        appendLog("세션 시작 — controlee 대기 (보드 ${state.boardMacInput.trim()}, session $sessionId)")
        _uiState.update { current ->
            current.copy(
                rangingState = RangingState.WAITING,
                noSignal = false,
                distanceCm = null,
                azimuthDeg = null,
                lastMeasurementAtMillis = null,
            )
        }
        rangingJob = viewModelScope.launch { collectRanging(boardMac, sessionId) }
        startWatchdog()
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
        }
    }

    private fun onAppBackgrounded() {
        if (!_uiState.value.isSessionActive) return
        appendLog("앱 백그라운드 진입 — 세션 정지 (백그라운드 레인징 미지원)")
        endSession(RangingState.IDLE)
    }

    /** 세션 종료 공통 처리: job 취소(NFR-4) + 스코프 재발급(주소 변경 감지) */
    private fun endSession(finalState: RangingState) {
        rangingJob?.cancel()
        rangingJob = null
        watchdogJob?.cancel()
        watchdogJob = null
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
        if (state.rangingState != RangingState.RANGING || state.noSignal) return
        val lastAtMillis: Long = state.lastMeasurementAtMillis ?: return
        if (System.currentTimeMillis() - lastAtMillis > NO_SIGNAL_TIMEOUT_MS) {
            appendLog("수신없음 — ${NO_SIGNAL_TIMEOUT_MS / 1000}초간 측정 없음 (세션 유지)")
            _uiState.update { current -> current.copy(noSignal = true) }
        }
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
    }

    companion object {
        private const val CM_PER_METER: Float = 100f
        private const val MEASUREMENT_LOG_INTERVAL: Int = 10
        private const val NO_SIGNAL_TIMEOUT_MS: Long = 2_000L
        private const val WATCHDOG_INTERVAL_MS: Long = 500L
    }
}
