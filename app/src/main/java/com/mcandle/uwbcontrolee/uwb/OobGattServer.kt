package com.mcandle.uwbcontrolee.uwb

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** OOB 서버 상태 — 주소 카드 옆 배지(FR-16)의 원천 */
enum class OobStatus {
    /** 서버 미동작 (Stop 상태 또는 아직 Start 전) */
    OFF,

    /** 광고 중 — 콘솔이 발견 가능 (⚪) */
    ADVERTISING,

    /** central(콘솔/nRF Connect) 연결됨 — 광고는 일시 중지 (🔵) */
    CONNECTED,

    /** BT 꺼짐/권한 없음/광고 실패 — OOB만 비활성, UWB는 무영향 */
    UNAVAILABLE,
}

/**
 * BLE OOB GATT peripheral (FR-11~13) — 사양서 docs/oob/BLE_OOB_인터페이스_사양서.md §3~5.
 *
 * - Start 시 open(): GATT 서버 + OOB_INFO(Read/Notify) + 광고(balanced, Service UUID 포함)
 * - central 연결 중 광고 중지, 해제 후 서버가 열려 있으면 광고 재개 (사양서 §5 규칙 2)
 * - updatePayload(): 주소 재발급 시 구독 중인 central에 Notify 푸시 (FR-13)
 * - Stop/onCleared 시 close(): 광고·GATT 완전 종료 (좀비 GATT 서버 금지)
 *
 * **어떤 실패도 밖으로 던지지 않는다** — BLE는 부가 경로라 실패 시 로그 + UNAVAILABLE로
 * 끝나야 하고 UWB 흐름을 절대 막지 않는다. 권한 체크 후에도 벤더 스택이 SecurityException을
 * 던질 수 있어 공개 메서드 전체를 try/catch로 감싼다.
 *
 * GATT 콜백은 바인더 스레드에서 오므로 공유 상태는 lock으로 보호하고,
 * onEvent는 호출 스레드를 보장하지 않는다 (받는 쪽에서 마샬링).
 */
@SuppressLint("MissingPermission") // 모든 BT 호출 전에 hasBlePermissions()로 동적 확인
class OobGattServer(
    private val context: Context,
    private val onEvent: (String) -> Unit,
    private val onOobInfoRead: () -> Unit,
) {
    private val _status: MutableStateFlow<OobStatus> = MutableStateFlow(OobStatus.OFF)
    val status: StateFlow<OobStatus> = _status.asStateFlow()

    private val lock: Any = Any()
    private var isOpen: Boolean = false
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var oobCharacteristic: BluetoothGattCharacteristic? = null
    private var payload: ByteArray = ByteArray(0)
    private val connectedDevices: MutableSet<BluetoothDevice> = mutableSetOf()
    private val subscribedDevices: MutableSet<BluetoothDevice> = mutableSetOf()

    /** Start 시 호출. 실패해도 예외를 던지지 않는다 — UNAVAILABLE + 로그로 종결 */
    fun open(initialPayload: ByteArray) {
        synchronized(lock) {
            if (isOpen) {
                // NFR-3 이후 서버가 Start를 넘어 오래 살므로, 재Start 때 스택 기준으로
                // 유령 연결을 정리한다 — 안 하면 배지가 CONNECTED에 고착될 수 있다.
                reconcileStaleConnections()
                return
            }
            payload = initialPayload
            val adapter: BluetoothAdapter? =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            when {
                adapter == null || !adapter.isEnabled ->
                    becomeUnavailable("OOB 비활성 — 블루투스 꺼짐/미지원 (UWB는 정상 동작)")
                !hasBlePermissions() ->
                    becomeUnavailable("OOB 비활성 — BLE 권한 없음 (UWB는 정상 동작)")
                else -> try {
                    startServer(adapter)
                } catch (t: Throwable) {
                    becomeUnavailable("OOB 시작 실패(무시): ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    /** Stop/onCleared 시 호출 — 광고·GATT 완전 종료. 여러 번 불려도 안전 */
    fun close() {
        synchronized(lock) {
            if (!isOpen && gattServer == null) return
            isOpen = false
            val server: BluetoothGattServer? = gattServer
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            connectedDevices.toList().forEach { device ->
                runCatching { server?.cancelConnection(device) }
            }
            runCatching { server?.close() }
            gattServer = null
            advertiser = null
            oobCharacteristic = null
            connectedDevices.clear()
            subscribedDevices.clear()
            _status.value = OobStatus.OFF
            onEvent("OOB 종료 — 광고·GATT 중지")
        }
    }

    /** 주소 재발급 등으로 페이로드가 바뀌면 호출 (FR-13). 닫혀 있으면 저장만 하고 무동작 */
    fun updatePayload(newPayload: ByteArray) {
        synchronized(lock) {
            payload = newPayload
            if (!isOpen) return
            val targets: List<BluetoothDevice> = subscribedDevices.toList()
            targets.forEach { device -> runCatching { notifyDevice(device, newPayload) } }
            if (targets.isNotEmpty()) {
                onEvent("OOB_INFO 변경 Notify 발신 (${targets.size}대)")
            }
        }
    }

    /**
     * 추적 중인 연결을 BLE 스택의 실제 연결 목록과 대조해 유령 연결을 제거 (lock 보유 상태에서 호출).
     * 해제 콜백을 놓친 채 서버가 계속 살아 있으면(keepOob·백그라운드 유지) connectedDevices에
     * 옛 central이 남아 광고 재개가 안 되고 배지가 CONNECTED로 고착된다.
     */
    private fun reconcileStaleConnections() {
        val manager: BluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val actuallyConnected: Set<BluetoothDevice> = runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT_SERVER).toSet()
        }.getOrElse { return }
        val staleDevices: List<BluetoothDevice> =
            connectedDevices.filterNot { device -> device in actuallyConnected }
        if (staleDevices.isEmpty()) return
        staleDevices.forEach { device ->
            connectedDevices.remove(device)
            subscribedDevices.remove(device)
            onEvent("OOB 유령 연결 정리 (${device.address}) — 스택 기준 미연결")
        }
        if (connectedDevices.isEmpty()) {
            _status.value = OobStatus.ADVERTISING
            runCatching { startAdvertising() }
        }
    }

    // ── 내부: 서버·광고 구성 ────────────────────────────────────────────

    private fun startServer(adapter: BluetoothAdapter) {
        val manager: BluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val server: BluetoothGattServer? = manager.openGattServer(context, gattServerCallback)
        if (server == null) {
            becomeUnavailable("OOB 비활성 — GATT 서버 열기 실패")
            return
        }
        gattServer = server
        server.addService(buildOobService())
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            runCatching { server.close() }
            gattServer = null
            becomeUnavailable("OOB 비활성 — 이 기기는 BLE 광고 미지원")
            return
        }
        isOpen = true
        startAdvertising()
    }

    /** OOB_INFO 특성(Read+Notify, Write 없음) + CCCD — 사양서 §3 */
    private fun buildOobService(): BluetoothGattService {
        val service = BluetoothGattService(
            UwbDefaults.OOB_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val characteristic = BluetoothGattCharacteristic(
            UwbDefaults.OOB_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(characteristic)
        oobCharacteristic = characteristic
        return service
    }

    /** 광고 시작 — balanced 인터벌(≈250ms, 절전 스로틀 회피), Service UUID 포함 */
    private fun startAdvertising() {
        val settings: AdvertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(ADVERTISE_NO_TIMEOUT)
            .build()
        // 128-bit UUID가 31B 광고 패킷의 대부분을 차지 → 이름은 스캔 응답에 배치.
        // Android는 임의 Local Name 지정 불가(어댑터 이름만 포함 가능) — 콘솔은 UUID로 필터하므로 무방.
        val advertiseData: AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UwbDefaults.OOB_SERVICE_UUID))
            .build()
        val scanResponse: AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
    }

    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            synchronized(lock) {
                if (isOpen && connectedDevices.isEmpty()) _status.value = OobStatus.ADVERTISING
            }
            onEvent("OOB 광고 시작 (Service UUID ${UwbDefaults.OOB_SERVICE_UUID})")
        }

        override fun onStartFailure(errorCode: Int) {
            synchronized(lock) { becomeUnavailable("OOB 광고 실패 (code $errorCode) — UWB는 정상 동작") }
        }
    }

    // ── 내부: GATT 콜백 (바인더 스레드) ─────────────────────────────────

    private val gattServerCallback: BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {

            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                synchronized(lock) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> onCentralConnected(device)
                        BluetoothProfile.STATE_DISCONNECTED -> onCentralDisconnected(device)
                    }
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                synchronized(lock) {
                    respondToRead(device, requestId, offset, characteristic.uuid)
                }
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor,
            ) {
                synchronized(lock) {
                    val value: ByteArray = if (device in subscribedDevices) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    }
                    sendResponseSafely(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                synchronized(lock) {
                    handleCccdWrite(device, value)
                    if (responseNeeded) {
                        sendResponseSafely(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value,
                        )
                    }
                }
            }
        }

    private fun onCentralConnected(device: BluetoothDevice) {
        connectedDevices.add(device)
        if (!isOpen) return
        // 사양서 §5 규칙 2: 연결 중에는 광고 중지
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        _status.value = OobStatus.CONNECTED
        onEvent("OOB central 연결됨 (${device.address}) — 광고 일시 중지")
    }

    private fun onCentralDisconnected(device: BluetoothDevice) {
        connectedDevices.remove(device)
        subscribedDevices.remove(device)
        if (!isOpen) return
        onEvent("OOB central 연결 해제 (${device.address})")
        if (connectedDevices.isEmpty()) {
            // 사양서 §5 규칙 2: 해제 후 서버가 열려 있으면(WAITING/RANGING) 광고 재개
            _status.value = OobStatus.ADVERTISING
            runCatching { startAdvertising() }
        }
    }

    private fun respondToRead(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        uuid: UUID,
    ) {
        if (uuid != UwbDefaults.OOB_CHARACTERISTIC_UUID) {
            sendResponseSafely(
                device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null,
            )
            return
        }
        if (offset > payload.size) {
            sendResponseSafely(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            return
        }
        val value: ByteArray = payload.copyOfRange(offset, payload.size)
        sendResponseSafely(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        onEvent("OOB_INFO Read 응답 (${device.address}, ${payload.size}B)")
        onOobInfoRead()
    }

    private fun handleCccdWrite(device: BluetoothDevice, value: ByteArray) {
        val subscribed: Boolean =
            value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (subscribed) subscribedDevices.add(device) else subscribedDevices.remove(device)
        onEvent(if (subscribed) "OOB Notify 구독됨 (${device.address})" else "OOB Notify 구독 해제 (${device.address})")
    }

    private fun sendResponseSafely(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        runCatching { gattServer?.sendResponse(device, requestId, status, offset, value) }
    }

    /** API 33+ 신규 시그니처 / 이하 deprecated 경로 분기 */
    private fun notifyDevice(device: BluetoothDevice, value: ByteArray) {
        val server: BluetoothGattServer = gattServer ?: return
        val characteristic: BluetoothGattCharacteristic = oobCharacteristic ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, value)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun becomeUnavailable(reason: String) {
        _status.value = OobStatus.UNAVAILABLE
        onEvent(reason)
    }

    private fun hasBlePermissions(): Boolean = hasBleOobPermissions(context)

    companion object {
        /** Client Characteristic Configuration Descriptor — Notify 구독용 표준 UUID */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** AdvertiseSettings.setTimeout(0) = 무제한 (Stop 시 명시적으로 중지) */
        private const val ADVERTISE_NO_TIMEOUT: Int = 0
    }
}

/** OOB에 필요한 런타임 권한 (API 31+, FR-14). ADVERTISE는 위치 권한 불필요 */
val BLE_OOB_PERMISSIONS: Array<String> = arrayOf(
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT,
)

/** OOB 권한 보유 여부 — Activity(요청 시점 판단)와 서버(open 가드)가 공용 */
fun hasBleOobPermissions(context: Context): Boolean =
    BLE_OOB_PERMISSIONS.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
