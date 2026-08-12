package com.mcandle.uwbcontrolee

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mcandle.uwbcontrolee.uwb.OobMode
import com.mcandle.uwbcontrolee.uwb.OobStatus
import com.mcandle.uwbcontrolee.uwb.RangingCoordinator
import com.mcandle.uwbcontrolee.uwb.RangingState
import com.mcandle.uwbcontrolee.uwb.UwbAvailability
import com.mcandle.uwbcontrolee.uwb.UwbDefaults
import kotlinx.coroutines.flow.StateFlow

/** 화면 전체 상태 — 단방향 StateFlow (원천: RangingCoordinator) */
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
    /** BLE OOB 교환 모드 (spec 001, 사양서 v0.3 §2). 레인징 중 변경 금지 (규칙 0) */
    val oobMode: OobMode = OobMode.DEFAULT,
    /** BLE 권한 거부됨 — OOB 안내 배너 노출 (FR-14). UWB 흐름과 무관 */
    val blePermissionDenied: Boolean = false,
    /** 콘솔 광고 시뮬레이터 송출 중 (검수 12 테스트 보조 — 이 폰을 가짜 콘솔로) */
    val consoleSimActive: Boolean = false,
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

/**
 * UI ↔ 조정자 사이의 위임층 (spec 002 T101 — plan D4).
 *
 * Start 시퀀스·워치독·OOB 수명 등 모든 조정 로직은 [RangingCoordinator] 로 추출됐다
 * (2026-08-12 G1 승인, 동작 무변경). 이 클래스는 Compose 가 기대하는 ViewModel 형태를
 * 유지하고(P1 — UI 는 ViewModel 하나에만 의존) 호출을 넘길 뿐이다.
 * 수명도 v1 과 동일: onCleared = 조정자 전체 종료 (콜드 웨이크 T302 에서 분리 예정).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val coordinator: RangingCoordinator = RangingCoordinator.get(application)

    val uiState: StateFlow<UiState> = coordinator.uiState

    // ── 가용성 / 주소 (FR-1~3) ──────────────────────────────────────────
    fun refreshAvailability() = coordinator.refreshAvailability()
    fun onPermissionResult(granted: Boolean) = coordinator.onPermissionResult(granted)
    fun onAddressCopied(address: String) = coordinator.onAddressCopied(address)

    // ── 입력 · 모드 (FR-4, spec 001 D3) ─────────────────────────────────
    fun onBoardMacChanged(value: String) = coordinator.onBoardMacChanged(value)
    fun onSessionIdChanged(value: String) = coordinator.onSessionIdChanged(value)
    fun onOobModeChanged(mode: OobMode) = coordinator.onOobModeChanged(mode)

    // ── Start / Stop · BLE 권한 (FR-5/14) ───────────────────────────────
    fun startRanging() = coordinator.startRanging()
    fun stopRanging() = coordinator.stopRanging()
    fun onBlePermissionResult(granted: Boolean) = coordinator.onBlePermissionResult(granted)

    // ── 테스트 보조 ──────────────────────────────────────────────────────
    fun toggleConsoleSimulator() = coordinator.toggleConsoleSimulator()

    override fun onCleared() {
        coordinator.shutdown()
    }
}
