package com.mcandle.uwbcontrolee.uwb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid

/**
 * 콜드 웨이크용 PendingIntent 스캔 등록/해제 (spec 002 T301 — plan D5).
 *
 * `startScan(filters, settings, PendingIntent)` 은 **앱 프로세스가 죽어도 OS 가 스캔을
 * 유지**하고, 매치 시 명시적 브로드캐스트([OobWakeReceiver])로 앱을 깨운다.
 * 필터는 모드 4 콘솔 광고의 Service UUID 목록(`5F1D0003`) — HW 필터로 매치된다 (§5-4).
 *
 * 주의: 등록은 재부팅 시 소멸한다 (BOOT_COMPLETED 재등록은 spec 002 범위 밖).
 * 상시 등록이므로 LOW_POWER — 포그라운드 모드 4 의 LOW_LATENCY 와 다른 이유.
 */
@SuppressLint("MissingPermission") // 호출측(coordinator)이 BLUETOOTH_SCAN 확인 후 호출
object OobWakeScan {

    /** OobWakeReceiver 가 이 액션만 처리한다 (명시적 브로드캐스트) */
    const val ACTION_WAKE: String = "com.mcandle.uwbcontrolee.action.OOB_WAKE"

    private const val REQUEST_CODE: Int = 1001

    /**
     * 등록/해제가 같은 PendingIntent 로 짝을 이뤄야 하므로 생성 규칙을 한 곳에 고정.
     * FLAG_MUTABLE 필수 — 시스템이 스캔 결과 extras 를 채워 넣는다.
     */
    private fun wakePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, OobWakeReceiver::class.java).setAction(ACTION_WAKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    /** 등록. 실패(BT 꺼짐·미지원·스택 거부) 시 false — 예외는 밖으로 던지지 않는다 (P6) */
    fun register(context: Context): Boolean {
        val scanner: BluetoothLeScanner = bleScanner(context) ?: return false
        val filter: ScanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UwbDefaults.ADV_INFO_UUID))
            .build()
        val settings: ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        return runCatching {
            scanner.startScan(listOf(filter), settings, wakePendingIntent(context)) == 0
        }.getOrDefault(false)
    }

    /** 해제 — 여러 번 불려도 안전 */
    fun unregister(context: Context) {
        val scanner: BluetoothLeScanner = bleScanner(context) ?: return
        runCatching { scanner.stopScan(wakePendingIntent(context)) }
    }

    private fun bleScanner(context: Context): BluetoothLeScanner? {
        val adapter: BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return null
        return adapter.bluetoothLeScanner
    }
}
