package com.mcandle.uwbcontrolee

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.uwb.UwbAddress
import com.mcandle.uwbcontrolee.uwb.UwbAvailability
import com.mcandle.uwbcontrolee.uwb.UwbDefaults
import com.mcandle.uwbcontrolee.uwb.UwbRepository
import com.mcandle.uwbcontrolee.uwb.formatUwbAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 화면 전체 상태 — 단방향 StateFlow (2단계 범위: 가용성 + 내 주소 + 로그) */
data class UiState(
    val availability: UwbAvailability = UwbAvailability.CHECKING,
    /** 내 UWB 주소 hex (예 "0A:3F"). 미발급이면 null → 화면엔 "--:--" (FR-3) */
    val myAddress: String? = null,
    val logLines: List<String> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: UwbRepository = UwbRepository(application)

    private val _uiState: MutableStateFlow<UiState> = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val logTimeFormat: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 스코프 획득 중복 방지 (onResume마다 refresh가 오므로) */
    private var acquiringAddress: Boolean = false

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
            ensureControleeScope()
        } else {
            clearAddress()
        }
    }

    /**
     * READY인데 주소가 없으면 controlee 스코프를 만들어 주소 발급 (FR-3).
     * Start 전에 사용자가 PC 스크립트 --dest-mac에 입력할 수 있어야 하므로 자동 수행.
     */
    private fun ensureControleeScope() {
        if (_uiState.value.myAddress != null || acquiringAddress) return
        acquiringAddress = true
        viewModelScope.launch {
            try {
                val address: UwbAddress = repository.acquireControleeScope()
                val hex: String = formatUwbAddress(address)
                appendLog("내 UWB 주소 발급: $hex (PC 스크립트 --dest-mac에 입력)")
                _uiState.update { state -> state.copy(myAddress = hex) }
            } catch (t: Throwable) {
                appendLog("주소 발급 실패: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                acquiringAddress = false
            }
        }
    }

    private fun clearAddress() {
        if (_uiState.value.myAddress == null) return
        repository.clearControleeScope()
        appendLog("UWB 사용 불가 상태 — 내 주소 무효화")
        _uiState.update { state -> state.copy(myAddress = null) }
    }

    /** 타임스탬프 한 줄 추가, 상한 초과 시 앞에서 삭제 (FR-9, NFR-5) */
    private fun appendLog(message: String) {
        val line: String = "${logTimeFormat.format(Date())} $message"
        _uiState.update { state ->
            val lines: List<String> = (state.logLines + line)
                .takeLast(UwbDefaults.MAX_LOG_LINES)
            state.copy(logLines = lines)
        }
    }
}
