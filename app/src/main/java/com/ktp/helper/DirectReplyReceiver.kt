package com.ktp.helper

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val digitCode = remoteInput.getCharSequence(CheckInForegroundService.KEY_TEXT_REPLY)?.toString()?.trim() ?: return
        val attenceId = intent.getStringExtra("attence_id") ?: return
        val courseName = intent.getStringExtra("course_name") ?: "数字考勤"

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val accounts = AccountStore(context).getAccounts()
                val (ok, failed) = KtpApiClient.batchCheckIn(
                    accounts = accounts,
                    attenceId = attenceId,
                    code = digitCode
                )
                val resultText = if (failed.isEmpty()) "✅ 口令 [$digitCode] 全员提交成功 ($ok 人)"
                else "⚠️ 成功 $ok 人，失败: ${failed.joinToString()}"

                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val updated = NotificationCompat.Builder(context, CheckInForegroundService.CHANNEL_SILENT)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("[$courseName] 数字考勤结果")
                    .setContentText(resultText)
                    .build()
                nm.notify(attenceId.hashCode(), updated)
            } finally {
                pendingResult.finish()
            }
        }
    }
}