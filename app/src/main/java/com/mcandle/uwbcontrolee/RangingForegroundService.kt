package com.mcandle.uwbcontrolee

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 세션 유지용 Foreground Service (NFR-3).
 *
 * 로직은 없다 — 프로세스 importance를 FOREGROUND_SERVICE로 올리는 것이 전부다:
 * (1) UWB 스택은 "포그라운드 앱 또는 FGS"에만 레인징을 허용하므로, 이게 없으면
 *     앱이 백그라운드로 가는 순간 세션이 무증상으로 내려간다.
 * (2) BLE 광고/GATT가 백그라운드에서 살아 있도록 프로세스를 보존한다 (Galaxy는
 *     백그라운드 프로세스를 공격적으로 정리).
 * 세션·OOB 소유권은 그대로 MainViewModel — 최근 앱에서 태스크를 제거하면 세션도 끝난다.
 */
class RangingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        return START_NOT_STICKY // 프로세스가 죽으면 세션도 없다 — 서비스만 재시작해도 무의미
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "UWB 세션 유지",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent: PendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("UWB 세션 동작 중")
            .setContentText("백그라운드에서 OOB 광고·레인징을 유지합니다")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID: String = "uwb_session"
        private const val NOTIFICATION_ID: Int = 1

        /** 반드시 앱이 포그라운드일 때(사용자 Start) 호출 — 백그라운드 FGS 시작 제한 */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RangingForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RangingForegroundService::class.java))
        }
    }
}
