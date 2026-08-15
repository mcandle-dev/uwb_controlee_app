package com.mcandle.uwbcontrolee.uwb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mcandle.uwbcontrolee.MainActivity
import com.mcandle.uwbcontrolee.RangingForegroundService

/**
 * 콜드 웨이크 수신기 (spec 002 T302/T303 — plan D3/D5).
 *
 * OS 가 [OobWakeScan] 매치 시 이 리시버를 깨운다 — **Activity·UI 없이** 프로세스가
 * 콜드 스타트될 수 있으므로 여기서는 FGS 기동까지만 하고, 시퀀스는 FGS 가
 * `RangingCoordinator` 로 돌린다. `onReceive` 는 ~10초 예산 — 무거운 일 금지.
 *
 * FGS 기동이 거부되면(백그라운드 FGS 시작 제한 — T304 스파이크 대상) 고우선 알림으로
 * 폴백한다: 탭하면 앱이 열리며 사용자가 포그라운드 Start (수용 기준 3).
 */
class OobWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OobWakeScan.ACTION_WAKE) return
        val errorCode: Int = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, NO_ERROR)
        if (errorCode != NO_ERROR) {
            Log.w(TAG, "웨이크 스캔 오류 code=$errorCode — 무시 (수동 경로는 유효)")
            return
        }
        // 광고가 계속 매치되면 브로드캐스트가 반복된다 — 스로틀 + coordinator 쪽 no-op 이중 방어
        val now: Long = SystemClock.elapsedRealtime()
        if (now - lastWakeAtMillis < WAKE_THROTTLE_MS) return
        lastWakeAtMillis = now
        Log.i(TAG, "콘솔 광고 매치 — FGS 자동 시작 시도")
        try {
            RangingForegroundService.startAutoWake(context)
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException 등 — 백그라운드 FGS 제한 (T304)
            Log.w(TAG, "FGS 기동 거부 (${t.javaClass.simpleName}) — 알림 폴백 (T303)")
            postFallbackNotification(context)
        }
    }

    /** 고우선 알림 폴백 — 탭하면 MainActivity (포그라운드 Start 유도). 권한 없으면 조용히 생략 */
    private fun postFallbackNotification(context: Context) {
        runCatching {
            val manager: NotificationManager =
                context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "콘솔 발견 알림", NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            val contentIntent: PendingIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("UWB 콘솔 발견")
                .setContentText("자동 시작이 제한됨 — 탭하여 레인징을 시작하세요")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure { t ->
            Log.w(TAG, "폴백 알림 실패 (${t.javaClass.simpleName}) — 알림 권한 확인")
        }
    }

    companion object {
        private const val TAG: String = "OobWakeReceiver"
        private const val NO_ERROR: Int = -1
        private const val CHANNEL_ID: String = "oob_wake"
        private const val NOTIFICATION_ID: Int = 2

        /** 반복 브로드캐스트 스로틀 — 광고 주기(~250ms)마다 FGS 재기동 시도 방지 */
        private const val WAKE_THROTTLE_MS: Long = 10_000L

        @Volatile
        private var lastWakeAtMillis: Long = 0L
    }
}
