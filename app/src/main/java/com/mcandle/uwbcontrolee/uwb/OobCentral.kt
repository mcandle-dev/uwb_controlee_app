package com.mcandle.uwbcontrolee.uwb

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE OOB 모드 4 (GATT-CLIENT / central) — 사양서 v0.5 §3-1·§5-4·§6, spec 002 T202.
 *
 * 콘솔(GATT-SERVER)의 connectable 광고(UUID 목록 `5F1D0003`)를 스캔 → GATT 연결 →
 * BOARD_INFO(`5F1D0004`) **Read** → PHONE_INFO(`5F1D0005`) **Write Without Response**.
 * **폰은 아무것도 송출하지 않는다** — iOS 가 성립하는 유일한 자동 경로 (§10).
 *
 * `OobGattServer`/`OobBeacon`/`OobScanner` 의 공개 계약 복제: open/close/updatePayload/status.
 * **어떤 실패도 밖으로 던지지 않는다** (P6). 주소 재발급 시 updatePayload() 가
 * 연결 유지 중 재Write 한다 (모드 1 Notify 의 대응물 — §3-1).
 *
 * status 매핑 (§6-1 동형): 스캔 중 = ADVERTISING(UI 표기 "스캔중"), GATT 연결 = CONNECTED.
 * 연결 끊김 시 콘솔이 광고를 재개하므로(§7-17) 열려 있는 동안 스캔으로 복귀해 재연결한다.
 */
@SuppressLint("MissingPermission") // 모든 BT 호출 전에 hasCentralPermissions()로 동적 확인
class OobCentral(
    private val context: Context,
    private val onEvent: (String) -> Unit,
    /** BOARD_INFO Read 성공 payload (≥7B). 호출 스레드 비보장 — 받는 쪽에서 마샬링 */
    private val onBoardInfoReceived: (ByteArray) -> Unit,
) {
    private val _status: MutableStateFlow<OobStatus> = MutableStateFlow(OobStatus.OFF)
    val status: StateFlow<OobStatus> = _status.asStateFlow()

    private val lock: Any = Any()
    private var isOpen: Boolean = false
    private var scanner: BluetoothLeScanner? = null
    private var scanning: Boolean = false
    private var gatt: BluetoothGatt? = null
    private var phoneInfoCharacteristic: BluetoothGattCharacteristic? = null
    /** PHONE_INFO 로 Write 할 폰 쪽 payload (주소 재발급·SID 변경 시 updatePayload 로 교체) */
    private var payload: ByteArray = ByteArray(0)

    /** Start 시 호출. 실패해도 예외를 던지지 않는다 — UNAVAILABLE + 로그로 종결 */
    fun open(initialPayload: ByteArray) {
        synchronized(lock) {
            if (isOpen) {
                // 재Start: 연결이 살아 있으면 최신 payload 재Write, 아니면 스캔 재개
                payload = initialPayload
                if (phoneInfoCharacteristic != null) {
                    writePhoneInfoLocked("재Start — 최신 payload 재Write")
                } else if (!scanning) {
                    startScanLocked()
                }
                return
            }
            payload = initialPayload
            val adapter: BluetoothAdapter? =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            when {
                adapter == null || !adapter.isEnabled ->
                    becomeUnavailable("OOB 비활성 — 블루투스 꺼짐/미지원 (UWB는 정상 동작)")
                !hasCentralPermissions() ->
                    becomeUnavailable("OOB 비활성 — BLE 스캔/연결 권한 없음 (UWB는 정상 동작)")
                else -> try {
                    scanner = adapter.bluetoothLeScanner
                    if (scanner == null) {
                        becomeUnavailable("OOB 비활성 — 이 기기는 BLE 스캔 미지원")
                        return
                    }
                    isOpen = true
                    startScanLocked()
                } catch (t: Throwable) {
                    becomeUnavailable("OOB 시작 실패(무시): ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    /** Stop/모드 전환/shutdown 시 호출 — 스캔·연결 완전 종료. 여러 번 불려도 안전 */
    fun close() {
        synchronized(lock) {
            if (!isOpen && gatt == null && !scanning) return
            isOpen = false
            stopScanLocked()
            val current: BluetoothGatt? = gatt
            gatt = null
            phoneInfoCharacteristic = null
            runCatching { current?.disconnect() }
            runCatching { current?.close() }
            scanner = null
            _status.value = OobStatus.OFF
            onEvent("OOB 종료 — GATT-CLIENT 스캔·연결 중지")
        }
    }

    /**
     * 주소 재발급·SID 변경 시 호출 — 연결 유지 중이면 PHONE_INFO 재Write (§3-1).
     * 닫혀 있거나 아직 미연결이면 저장만 (연결 후 첫 Write 에 반영).
     */
    fun updatePayload(newPayload: ByteArray) {
        synchronized(lock) {
            if (payload.contentEquals(newPayload)) return
            payload = newPayload
            if (!isOpen || phoneInfoCharacteristic == null) return
            writePhoneInfoLocked("payload 갱신 — PHONE_INFO 재Write (재발급 대응, §3-1)")
        }
    }

    // ── 내부: 스캔 (lock 보유 상태에서만 호출) ───────────────────────────

    private fun startScanLocked() {
        // 모드 4 광고에는 UUID 목록 AD 가 있어 HW 필터가 그대로 매치된다 (§5-4 —
        // 모드 3 광고와 교차 매치 없음: 그쪽은 Service Data 뿐이라 이 필터에 안 걸림)
        val filter: ScanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UwbDefaults.ADV_INFO_UUID))
            .build()
        val settings: ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner?.startScan(listOf(filter), settings, scanCallback) }
            .onSuccess {
                scanning = true
                _status.value = OobStatus.ADVERTISING // §6-1 동형 매핑 — UI 표기 "스캔중"
                onEvent("GATT-CLIENT 스캔 시작 — 콘솔 connectable 광고(${UwbDefaults.ADV_INFO_UUID}) 대기")
            }
            .onFailure { t ->
                becomeUnavailable("스캔 시작 실패(무시): ${t.message ?: t.javaClass.simpleName}")
            }
    }

    private fun stopScanLocked() {
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            synchronized(lock) { onConsoleFound(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            synchronized(lock) {
                scanning = false
                becomeUnavailable("스캔 실패 (code $errorCode) — UWB는 정상 동작")
            }
        }
    }

    // ── 내부: 연결·교환 (lock 보유 상태에서만 호출) ──────────────────────

    private fun onConsoleFound(device: BluetoothDevice) {
        if (!isOpen || gatt != null) return // 이미 연결 시도 중이면 무시 (1 연결 가정 — §9-5)
        stopScanLocked()
        onEvent("콘솔 발견 (${device.address}) — GATT 연결 시도")
        gatt = runCatching {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            onEvent("GATT 연결 시작 실패 — 스캔 재개")
            startScanLocked()
        }
    }

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            synchronized(lock) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        _status.value = OobStatus.CONNECTED
                        onEvent("콘솔 GATT 연결됨 — 서비스 탐색")
                        runCatching { g.discoverServices() }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> onDisconnectedLocked(g)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            synchronized(lock) {
                if (!isOpen) return
                val service: BluetoothGattService? =
                    g.getService(UwbDefaults.ADV_INFO_UUID)
                val boardInfo: BluetoothGattCharacteristic? =
                    service?.getCharacteristic(UwbDefaults.BOARD_INFO_CHARACTERISTIC_UUID)
                phoneInfoCharacteristic =
                    service?.getCharacteristic(UwbDefaults.PHONE_INFO_CHARACTERISTIC_UUID)
                if (service == null || boardInfo == null) {
                    onEvent("콘솔 GATT 에 모드 4 서비스/특성 없음 — 짝(콘솔 GATT-SERVER 모드) 확인, §7-11")
                    return
                }
                onEvent("BOARD_INFO Read 요청")
                runCatching { g.readCharacteristic(boardInfo) }
            }
        }

        @Deprecated("API 33 미만 경로 — 새 시그니처가 없는 기기용")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            handleBoardInfoRead(characteristic.uuid == UwbDefaults.BOARD_INFO_CHARACTERISTIC_UUID,
                characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleBoardInfoRead(
                characteristic.uuid == UwbDefaults.BOARD_INFO_CHARACTERISTIC_UUID, value, status,
            )
        }
    }

    private fun handleBoardInfoRead(isBoardInfo: Boolean, value: ByteArray, status: Int) {
        synchronized(lock) {
            if (!isOpen || !isBoardInfo) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onEvent("BOARD_INFO Read 실패 (status $status) — 수동 입력 폴백 대기, §7-16")
                return
            }
            onEvent("BOARD_INFO 수신 (${value.size}B)")
            // 교환 후반부: 자기 주소를 콘솔에 전달 — 콘솔이 DST_MAC 확보 (§6 모드 4)
            writePhoneInfoLocked("PHONE_INFO Write (콘솔이 폰 주소 확보)")
            onBoardInfoReceived(value.copyOf())
        }
    }

    /** PHONE_INFO Write Without Response (lock 보유 상태에서 호출). 실패는 로그로만 (§7-18) */
    private fun writePhoneInfoLocked(reason: String) {
        val g: BluetoothGatt = gatt ?: return
        val characteristic: BluetoothGattCharacteristic = phoneInfoCharacteristic ?: return
        if (payload.isEmpty()) return
        onEvent(reason)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    characteristic, payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
        }.onFailure { t ->
            onEvent("PHONE_INFO Write 실패(무시): ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 연결 끊김 — 콘솔이 광고를 재개하므로(§7-17) 열려 있으면 스캔으로 복귀해 재연결 */
    private fun onDisconnectedLocked(g: BluetoothGatt) {
        runCatching { g.close() }
        if (gatt !== g) return // 이미 close() 로 정리된 유령 콜백
        gatt = null
        phoneInfoCharacteristic = null
        if (!isOpen) return
        onEvent("콘솔 GATT 연결 끊김 — 스캔 재개 (재발견 대기)")
        startScanLocked()
    }

    private fun becomeUnavailable(reason: String) {
        _status.value = OobStatus.UNAVAILABLE
        onEvent(reason)
    }

    /** central 은 스캔+연결 — SCAN·CONNECT 만 필요 (송출 0건이라 ADVERTISE 불필요, §10) */
    private fun hasCentralPermissions(): Boolean =
        CENTRAL_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }

    companion object {
        /** 모드 4 에 필요한 런타임 권한 — bleOobPermissionsFor(CENTRAL) 와 동일해야 한다 */
        val CENTRAL_PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
