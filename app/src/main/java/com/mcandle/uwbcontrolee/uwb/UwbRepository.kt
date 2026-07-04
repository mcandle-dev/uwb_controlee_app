package com.mcandle.uwbcontrolee.uwb

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbControleeSessionScope
import androidx.core.uwb.UwbManager

/**
 * UWB API 접근 창구 — UI(Compose)는 이 클래스(를 소유한 ViewModel)에만 의존한다.
 * 2단계 현재: 가용성 진단 + controlee 스코프 획득(내 주소). 레인징은 3단계에서 추가.
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
