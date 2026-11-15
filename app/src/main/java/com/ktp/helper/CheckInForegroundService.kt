package com.ktp.helper

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import java.util.Calendar
import kotlin.random.Random

class CheckInForegroundService : Service() {

    companion object {
        const val CHANNEL_SILENT = "ktp_silent_monitor"
        const val CHANNEL_ALERT = "ktp_urgent_alert"
        const val NOTIF_FOREGROUND_ID = 1001
        const val KEY_TEXT_REPLY = "key_digit_code"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notifiedTaskId = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        val notification = buildForegroundNotification("课堂派守护运行中 · 等待上课巡检")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIF_FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_FOREGROUND_ID, notification)
        }
        startSmartPollingLoop()
    }

    private fun startSmartPollingLoop() {
        serviceScope.launch {
            var backoffMs = 0L
            while (isActive) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (hour >= 23 || hour < 7) {
                    updateForegroundText("夜间自动休眠中（07:00 自动唤醒）")
                    delay(15 * 60 * 1000L)
                    continue
                }

                val store = AccountStore(this@CheckInForegroundService)
                val accounts = store.getAccounts().filter { it.enabled }
                if (accounts.isEmpty()) {
                    updateForegroundText("暂无已启用账号，请在主页添加")
                    delay(15000)
                    continue
                }

                val probe = accounts.first()
                val task = KtpApiClient.pollActiveAttendance(probe.token)
                if (task != null) {
                    backoffMs = 0L
                    when (task.type) {
                        1 -> {
                            val (ok, failed) = KtpApiClient.batchCheckIn(accounts, attenceId = task.attenceId)
                            val summary = if (failed.isEmpty()) "✅ [${task.courseName}] 自动签到成功 ($ok 人)"
                            else "⚠️ [${task.courseName}] 成功 $ok 人，失败: ${failed.joinToString()}"
                            sendSilentReport(summary)
                        }
                        2 -> {
                            if (notifiedTaskId != task.attenceId) {
                                notifiedTaskId = task.attenceId
                                sendDirectReplyAlert(task)
                            }
                        }
                        3 -> {
                            if (notifiedTaskId != task.attenceId) {
                                notifiedTaskId = task.attenceId
                                sendQrAlert(task)
                            }
                        }
                    }
                }

                val baseInterval = Random.nextLong(5000, 8000)
                delay(baseInterval + backoffMs)
            }
        }
    }

    private fun sendDirectReplyAlert(task: ActiveTask) {
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("在此输入 4 位签到数字")
            .build()

        val replyIntent = Intent(this, DirectReplyReceiver::class.java).apply {
            putExtra("attence_id", task.attenceId)
            putExtra("course_name", task.courseName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPendingIntent = PendingIntent.getBroadcast(this, task.attenceId.hashCode(), replyIntent, flags)

        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "直接输入口令提交全员",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val notif = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🔔 [${task.courseName}] 开启了数字考勤")
            .setContentText("下拉或锁屏直接点击下方按钮输入 4 位数字，全员秒签")
            .addAction(action)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(task.attenceId.hashCode(), notif)
    }

    private fun sendQrAlert(task: ActiveTask) {
        val scanIntent = Intent(this, ScanActivity::class.java)
        val pi = PendingIntent.getActivity(this, 2002, scanIntent, PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("📷 [${task.courseName}] 开启了二维码考勤")
            .setContentText("点击立即打开极速扫码，一人扫全员签")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(task.attenceId.hashCode(), notif)
    }

    private fun sendSilentReport(text: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_SILENT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("考勤自动处理完成")
            .setContentText(text)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun updateForegroundText(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_FOREGROUND_ID, buildForegroundNotification(text))
    }

    private fun buildForegroundNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_SILENT)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("课堂派极速助手")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val silent = NotificationChannel(CHANNEL_SILENT, "静默运行与自动签到战报", NotificationManager.IMPORTANCE_LOW)
        val alert = NotificationChannel(CHANNEL_ALERT, "紧急口令与扫码提醒", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(silent)
        nm.createNotificationChannel(alert)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}