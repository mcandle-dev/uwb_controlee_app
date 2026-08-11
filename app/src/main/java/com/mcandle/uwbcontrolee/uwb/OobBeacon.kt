package com.mcandle.uwbcontrolee.uwb

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE OOB 모드 2 (BEACON 송출) — 사양서 v0.3 §5, spec 001 (plan D1).
 *
 * OOB_INFO 7B 를 Service Data(`5F1D0001`)에 실어 광고한다. GATT 서버 없음,
 * `connectable=false` — v1 콘솔(SCANNER)이 이 광고를 발견해도 연결이 안 될 뿐 오동작 없다.
 * 광고 총량: Flags 3B + ServiceData128(1+1+16+7) = 28B ≤ 31B — **여유 0, 필드 추가 금지** (§5-1).
 *
 * `OobGattServer` 의 공개 계약을 복제한다: open()/close()/updatePayload()/status.
 * **어떤 실패도 밖으로 던지지 않는다** (P6) — 로그 + UNAVAILABLE 로 종결, UWB 무영향.
 * 주소 재발급 시 updatePayload() 가 광고를 새 payload 로 교체한다 (§7-15 — Notify 대응물, P7).
 */
@SuppressLint("MissingPermission") // 모든 BT 호출 전에 hasAdvertisePermission()으로 동적 확인
class OobBeacon(
    private val context: Context,
    private val onEvent: (String) -> Unit,
    /**
     * Service Data UUID — 기본은 폰 송출(`5F1D0001`, 모드 2).
     * 콘솔 광고 시뮬레이터(검수 12 테스트 보조)가 `ADV_INFO_UUID`(`5F1D0003`)로 재사용한다.
     */
    private val serviceDataUuid: UUID = UwbDefaults.OOB_SERVICE_UUID,
) {
    private val _status: MutableStateFlow<OobStatus> = MutableStateFlow(OobStatus.OFF)
    val status: StateFlow<OobStatus> = _status.asStateFlow()

    private val lock: Any = Any()
    private var isOpen: Boolean = false
    private var advertiser: BluetoothLeAdvertiser? = null
    private var payload: ByteArray = ByteArray(0)

    /** Start 시 호출. 실패해도 예외를 던지지 않는다 — UNAVAILABLE + 로그로 종결 */
    fun open(initialPayload: ByteArray) {
        synchronized(lock) {
            if (isOpen) {
                // 재Start = 최신 payload 로 광고 갱신 (연결이 없으니 초기화할 central 도 없다)
                applyPayloadLocked(initialPayload)
                return
            }
            payload = initialPayload
            val adapter: BluetoothAdapter? =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            when {
                adapter == null || !adapter.isEnabled ->
                    becomeUnavailable("OOB 비활성 — 블루투스 꺼짐/미지원 (UWB는 정상 동작)")
                !hasAdvertisePermission() ->
                    becomeUnavailable("OOB 비활성 — BLE 광고 권한 없음 (UWB는 정상 동작)")
                else -> try {
                    startBeacon(adapter)
                } catch (t: Throwable) {
                    becomeUnavailable("OOB 시작 실패(무시): ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    /** Stop/onCleared/모드 전환 시 호출 — 광고 완전 종료. 여러 번 불려도 안전 */
    fun close() {
        synchronized(lock) {
            if (!isOpen && advertiser == null) return
            isOpen = false
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            advertiser = null
            _status.value = OobStatus.OFF
            onEvent("OOB 종료 — BEACON 광고 중지")
        }
    }

    /**
     * 주소 재발급 등으로 payload 가 바뀌면 호출 (사양서 §7-15) — 광고를 새 내용으로 교체.
     * 커넥션리스라 Notify 가 없으므로 광고 자체가 갱신 채널이다. 닫혀 있으면 저장만.
     */
    fun updatePayload(newPayload: ByteArray) {
        synchronized(lock) {
            if (!isOpen) {
                payload = newPayload
                return
            }
            applyPayloadLocked(newPayload)
        }
    }

    // ── 내부 (lock 보유 상태에서만 호출) ─────────────────────────────────

    private fun startBeacon(adapter: BluetoothAdapter) {
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            becomeUnavailable("OOB 비활성 — 이 기기는 BLE 광고 미지원")
            return
        }
        isOpen = true
        startAdvertising()
    }

    /** payload 교체 — 내용이 같으면 무동작 (광고 재시작 깜빡임 방지) */
    private fun applyPayloadLocked(newPayload: ByteArray) {
        if (payload.contentEquals(newPayload)) return
        payload = newPayload
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { startAdvertising() }
        onEvent("BEACON payload 갱신 — 새 내용으로 광고 교체")
    }

    /** 광고 시작 — BALANCED(≈250ms), connectable=false, timeout 0 (사양서 §5-3) */
    private fun startAdvertising() {
        val settings: AdvertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(false)
            .setTimeout(ADVERTISE_NO_TIMEOUT)
            .build()
        // Service Data AD 자체에 UUID 가 들어 있어 별도 Service UUID 목록은 넣지 않는다
        // (31B 예산 초과 — 사양서 §5-1). 이름은 v1 과 같은 이유로 스캔 응답에.
        val advertiseData: AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(serviceDataUuid), payload)
            .build()
        val scanResponse: AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
    }

    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            synchronized(lock) {
                if (isOpen) _status.value = OobStatus.ADVERTISING
            }
            onEvent("BEACON 광고 시작 (Service Data $serviceDataUuid, ${payload.size}B)")
        }

        override fun onStartFailure(errorCode: Int) {
            synchronized(lock) {
                becomeUnavailable("BEACON 광고 실패 (code $errorCode) — UWB는 정상 동작")
            }
        }
    }

    private fun becomeUnavailable(reason: String) {
        _status.value = OobStatus.UNAVAILABLE
        onEvent(reason)
    }

    /** BEACON 은 송출뿐이라 ADVERTISE 권한만 필요 (CONNECT 거부돼도 동작 — P6) */
    private fun hasAdvertisePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** AdvertiseSettings.setTimeout(0) = 무제한 (Stop 시 명시적으로 중지) */
        private const val ADVERTISE_NO_TIMEOUT: Int = 0
    }
}
