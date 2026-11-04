package io.drivegrowth.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

class RecordingService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("recording", "주행 기록", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "recording")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("드라이브 키우기")
            .setContentText("차량 연결 상태를 확인하며 주행을 자동 기록합니다.")
            .setContentIntent(launch).setOngoing(true).build())
        (application as DriveApplication).runtime.start()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { (application as DriveApplication).runtime.stop(); super.onDestroy() }
}
