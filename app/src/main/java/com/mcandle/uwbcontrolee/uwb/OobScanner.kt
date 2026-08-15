package com.mcandle.uwbcontrolee.uwb

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE OOB 모드 3 (SCANNER 관찰) — 사양서 v0.3 §2/§5-2, spec 001 T301.
 *
 * 콘솔이 송출하는 ADV_INFO(`5F1D0003`) Service Data 광고를 관찰해, OOB_INFO 7B
 * (보드 MAC·Session ID)를 수신하면 `onAdvertReceived` 로 전달한다. 수신 판정은
 * `OobScanFilter`(순수 함수 — D6)가 한다: 죽은 광고(rssi/ts)·중복 payload 제거.
 *
 * status 매핑 (§6-1): 스캔 중 = ADVERTISING(UI 표기 "스캔중"), 수신 확정 = CONNECTED.
 * **어떤 실패도 밖으로 던지지 않는다** (P6) — `OobGattServer` 의 계약 복제 (plan D1).
 */
@SuppressLint("MissingPermission") // 모든 BT 호출 전에 hasScanPermission()으로 동적 확인
class OobScanner(
    private val context: Context,
    private val onEvent: (String) -> Unit,
    /** APPLY 판정을 통과한 Service Data 원본 (≥7B). 호출 스레드 비보장 — 받는 쪽에서 마샬링 */
    private val onAdvertReceived: (ByteArray) -> Unit,
) {
    private val _status: MutableStateFlow<OobStatus> = MutableStateFlow(OobStatus.OFF)
    val status: StateFlow<OobStatus> = _status.asStateFlow()

    private val lock: Any = Any()
    private var isOpen: Boolean = false
    private var scanner: BluetoothLeScanner? = null
    private var lastAppliedPayload: ByteArray? = null
    private var malformedLogged: Boolean = false

    /** Start 시 호출. 실패해도 예외를 던지지 않는다 — UNAVAILABLE + 로그로 종결 */
    fun open() {
        synchronized(lock) {
            if (isOpen) return
            lastAppliedPayload = null
            malformedLogged = false
            val adapter: BluetoothAdapter? =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            when {
                adapter == null || !adapter.isEnabled ->
                    becomeUnavailable("OOB 비활성 — 블루투스 꺼짐/미지원 (UWB는 정상 동작)")
                !hasScanPermission() ->
                    becomeUnavailable("OOB 비활성 — BLE 스캔 권한 없음 (UWB는 정상 동작)")
                else -> try {
                    startScan(adapter)
                } catch (t: Throwable) {
                    becomeUnavailable("OOB 시작 실패(무시): ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    /** Stop/onCleared/모드 전환 시 호출 — 스캔 완전 종료. 여러 번 불려도 안전 */
    fun close() {
        synchronized(lock) {
            if (!isOpen && scanner == null) return
            isOpen = false
            runCatching { scanner?.stopScan(scanCallback) }
            scanner = null
            _status.value = OobStatus.OFF
            onEvent("OOB 종료 — 콘솔 광고 스캔 중지")
        }
    }

    // ── 내부 (lock 보유 상태에서만 호출) ─────────────────────────────────

    private fun startScan(adapter: BluetoothAdapter) {
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            becomeUnavailable("OOB 비활성 — 이 기기는 BLE 스캔 미지원")
            return
        }
        // Service Data 존재 자체로 필터 — 빈 data/mask 는 "해당 UUID 의 Service Data 가
        // 있으면 매치". 모드 3 광고에는 Service UUID 목록 AD 가 없어(31B 예산 — §5-1)
        // setServiceUuid 필터로는 잡히지 않는다.
        val filter: ScanFilter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(UwbDefaults.ADV_INFO_UUID), ByteArray(0))
            .build()
        // 수신 지연이 30초 폴백(§7-14)과 직결되므로 브링업 도구답게 LOW_LATENCY
        val settings: ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        isOpen = true
        scanner?.startScan(listOf(filter), settings, scanCallback)
        // startScan 은 성공 콜백이 없다 — 낙관 전환, 실패는 onScanFailed 가 되돌린다
        _status.value = OobStatus.ADVERTISING
        onEvent("SCANNER 시작 — 콘솔 광고(${UwbDefaults.ADV_INFO_UUID}) 관찰")
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            synchronized(lock) { handleScanResult(result) }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            synchronized(lock) { results.forEach { result -> handleScanResult(result) } }
        }

        override fun onScanFailed(errorCode: Int) {
            synchronized(lock) {
                becomeUnavailable("스캔 실패 (code $errorCode) — UWB는 정상 동작")
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        if (!isOpen) return
        val data: ByteArray? =
            result.scanRecord?.getServiceData(ParcelUuid(UwbDefaults.ADV_INFO_UUID))
        val advertAgeMs: Long =
            (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / NANOS_PER_MILLI
        when (OobScanFilter.evaluate(data, result.rssi, advertAgeMs, lastAppliedPayload)) {
            OobScanFilter.Verdict.APPLY -> {
                lastAppliedPayload = data!!.copyOf()
                _status.value = OobStatus.CONNECTED // §6-1: 광고 수신 확정 (UI 표기 "수신됨")
                onEvent("콘솔 광고 수신 (${result.device?.address ?: "?"}, rssi ${result.rssi})")
                onAdvertReceived(data.copyOf())
            }
            OobScanFilter.Verdict.MALFORMED -> {
                // 광고는 ~250ms 간격으로 반복된다 — 같은 오류 로그 폭주 방지 (1회만)
                if (!malformedLogged) {
                    malformedLogged = true
                    val hex: String = data?.joinToString(" ") { "%02X".format(it) } ?: "null"
                    onEvent("OOB_PARSE 오류 — Service Data [$hex] (§7-13, 이후 동일 오류 생략)")
                }
            }
            // STALE/WEAK/DUPLICATE — 조용히 무시 (D6: 입력칸 깜빡임 방지)
            else -> Unit
        }
    }

    private fun becomeUnavailable(reason: String) {
        _status.value = OobStatus.UNAVAILABLE
        onEvent(reason)
    }

    private fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}

/**
 * 모드별 필요 BLE 권한 (plan D5) — 불필요 권한은 요청하지 않는다:
 * 모드 1·2 = ADVERTISE+CONNECT / 모드 3 = +SCAN / 모드 4 = SCAN+CONNECT
 * (central 은 송출 0건이라 ADVERTISE 불필요 — 사양서 v0.5 §10).
 */
fun bleOobPermissionsFor(mode: OobMode): Array<String> = when (mode) {
    OobMode.SCANNER -> BLE_OOB_PERMISSIONS + Manifest.permission.BLUETOOTH_SCAN
    OobMode.CENTRAL -> OobCentral.CENTRAL_PERMISSIONS
    else -> BLE_OOB_PERMISSIONS
}

/** 모드 기준 OOB 권한 보유 여부 — Activity(요청 시점)와 ViewModel(Start 가드)이 공용 */
fun hasBleOobPermissions(context: Context, mode: OobMode): Boolean =
    bleOobPermissionsFor(mode).all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
