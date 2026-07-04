package com.mcandle.uwbcontrolee.uwb

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControleeSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.flow.Flow

/**
 * UWB API 접근 창구 — UI(Compose)는 이 클래스(를 소유한 ViewModel)에만 의존한다.
 * 가용성 진단 + controlee 스코프 획득(내 주소) + 레인징 Flow.
 */
class UwbRepository(private val context: Context) {

    private val uwbManager: UwbManager by lazy { UwbManager.createInstance(context) }

    /** 현재 controlee 세션 스코프 — Start(3단계) 시 이 스코프로 prepareSession 한다 */
    private var controleeScope: UwbControleeSessionScope? = null

    /**
     * controlee 스코프를 새로 만들고 내 주소를 반환 (FR-3).
     * 주소는 스코프 생성 시점에 발급되므로 READY 직후 호출해 화면에 먼저 보여준다.
     */
    suspend fun acquireControleeScope(): UwbAddress {
        val scope: UwbControleeSessionScope = uwbManager.controleeSessionScope()
        controleeScope = scope
        return scope.localAddress
    }

    fun clearControleeScope() {
        controleeScope = null
    }

    /**
     * 세션 계약(CLAUDE.md 표)대로 RangingParameters 구성.
     * 보드 MAC과 Session ID만 가변, 나머지는 UwbDefaults 상수.
     */
    fun buildRangingParameters(boardMacBytes: ByteArray, sessionId: Int): RangingParameters =
        RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
            sessionId = sessionId,
            subSessionId = UwbDefaults.SUB_SESSION_ID,
            sessionKeyInfo = UwbDefaults.STATIC_STS_KEY,
            subSessionKeyInfo = null,
            complexChannel = UwbComplexChannel(UwbDefaults.CHANNEL, UwbDefaults.PREAMBLE_INDEX),
            peerDevices = listOf(UwbDevice(UwbAddress(boardMacBytes))),
            updateRateType = UwbDefaults.UPDATE_RATE_TYPE,
        )

    /**
     * 레인징 결과 Flow — 수집 시작이 곧 controlee 대기 시작, 취소가 곧 세션 종료 (FR-5).
     * 스코프는 세션 1회용: 수집이 끝나면 clearControleeScope 후 재발급해야 한다.
     */
    fun rangingResults(parameters: RangingParameters): Flow<RangingResult> {
        val scope: UwbControleeSessionScope = checkNotNull(controleeScope) {
            "controlee 스코프 없음 — 내 주소 발급 후 Start 가능"
        }
        return scope.prepareSession(parameters)
    }

    /** 하드웨어 → 권한 → 어댑터 순으로 판정 (FR-1) */
    suspend fun checkAvailability(): UwbAvailability {
        if (!hasUwbFeature()) return UwbAvailability.NOT_SUPPORTED
        if (!hasRangingPermission()) return UwbAvailability.PERMISSION_DENIED
        return if (isUwbEnabled()) UwbAvailability.READY else UwbAvailability.DISABLED
    }

    fun hasUwbFeature(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)

    fun hasRangingPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.UWB_RANGING) ==
            PackageManager.PERMISSION_GRANTED

    /** 토글 OFF·리전 비활성이면 false. 어떤 예외도 크래시로 이어지지 않게 잡는다 (NFR-2). */
    private suspend fun isUwbEnabled(): Boolean =
        try {
            uwbManager.isAvailable()
        } catch (t: Throwable) {
            false
        }
}

/** 2바이트 short MAC을 "0A:3F" 형식 대문자 hex로 (화면 표시·PC --dest-mac 입력용) */
fun formatUwbAddress(address: UwbAddress): String =
    address.address.joinToString(separator = ":") { byte -> "%02X".format(byte) }

/** 보드 MAC 입력 형식: "XX:XX" 또는 "XXXX" (FR-4) */
private val BOARD_MAC_REGEX: Regex = Regex("^[0-9A-Fa-f]{2}:?[0-9A-Fa-f]{2}$")

/** 형식이 맞으면 2바이트 배열, 아니면 null (Start 비활성 사유) */
fun parseBoardMac(input: String): ByteArray? {
    val trimmed: String = input.trim()
    if (!BOARD_MAC_REGEX.matches(trimmed)) return null
    val hex: String = trimmed.replace(":", "")
    return byteArrayOf(
        hex.substring(0, 2).toInt(16).toByte(),
        hex.substring(2, 4).toInt(16).toByte(),
    )
}

/** Session ID: 양의 정수만 허용 (FR-4) */
fun parseSessionId(input: String): Int? {
    val value: Int = input.trim().toIntOrNull() ?: return null
    return if (value > 0) value else null
}
