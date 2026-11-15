package com.ktp.helper

import android.net.Uri
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class ActiveTask(
    val attenceId: String,
    val courseName: String,
    val type: Int
)

object KtpApiClient {
    private const val BASE_URL = "https://openapiv5.ketangpai.com"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val completedTasks = Collections.synchronizedSet(mutableSetOf<String>())

    fun isTaskCompleted(attenceId: String): Boolean = completedTasks.contains(attenceId)
    fun markTaskCompleted(attenceId: String) { completedTasks.add(attenceId) }

    suspend fun checkTokenValid(token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("reqtimestamp", System.currentTimeMillis()).toString()
            val req = Request.Builder()
                .url("$BASE_URL/UserApi/getUserBasinInfo")
                .addHeader("token", token)
                .post(body.toRequestBody(JSON_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                resp.isSuccessful && text.contains("\"code\":10000")
            }
        } catch (e: Exception) {
            true
        }
    }

    suspend fun pollActiveAttendance(token: String): ActiveTask? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("reqtimestamp", System.currentTimeMillis()).toString()
            val req = Request.Builder()
                .url("$BASE_URL/AttenceApi/getNotFinishAttenceStudent")
                .addHeader("token", token)
                .post(body.toRequestBody(JSON_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || !text.contains("\"code\":10000")) return@withContext null
                val data = JSONObject(text).optJSONObject("data") ?: return@withContext null
                val lists = data.optJSONArray("lists") ?: return@withContext null
                for (i in 0 until lists.length()) {
                    val item = lists.getJSONObject(i)
                    val id = item.optString("id")
                    val type = item.optInt("type", 0)
                    val courseName = item.optString("coursename", "课堂考勤")
                    if (id.isNotEmpty() && !isTaskCompleted(id) && type in listOf(1, 2, 3)) {
                        return@withContext ActiveTask(id, courseName, type)
                    }
                }
            }
        } catch (_: Exception) {
        }
        null
    }

    suspend fun batchCheckIn(
        accounts: List<StudentAccount>,
        attenceId: String = "",
        code: String = "",
        qrRawUrl: String = "",
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Pair<Int, List<String>> = coroutineScope {
        val activeList = accounts.filter { it.enabled }
        val total = activeList.size
        var completedCount = 0
        val failedNames = Collections.synchronizedList(mutableListOf<String>())

        val parsedParams = parseQrParams(qrRawUrl)
        val targetId = parsedParams["id"] ?: parsedParams["attenceid"] ?: attenceId
        val ticket = parsedParams["ticket"] ?: parsedParams["code"] ?: code

        val jobs = activeList.map { acc ->
            async(Dispatchers.IO) {
                delay(Random.nextLong(50, 200))
                val ok = submitSingleWithRetry(acc.token, targetId, ticket)
                if (!ok) failedNames.add(acc.name)
                synchronized(this@coroutineScope) {
                    completedCount++
                    onProgress?.invoke(completedCount, total)
                }
            }
        }
        jobs.awaitAll()
        if (targetId.isNotEmpty() && failedNames.isEmpty()) {
            markTaskCompleted(targetId)
        }
        Pair(total - failedNames.size, failedNames)
    }

    private suspend fun submitSingleWithRetry(token: String, attenceId: String, codeOrTicket: String): Boolean {
        val payload = JSONObject().apply {
            put("id", attenceId)
            if (codeOrTicket.isNotEmpty()) put("code", codeOrTicket)
            put("reqtimestamp", System.currentTimeMillis())
        }.toString()

        repeat(3) { attempt ->
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/AttenceApi/checkin")
                    .addHeader("token", token)
                    .post(payload.toRequestBody(JSON_TYPE))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && (bodyStr.contains("\"code\":10000") || bodyStr.contains("已签到"))) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }
            if (attempt < 2) delay(250)
        }
        return false
    }

    private fun parseQrParams(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val uri = Uri.parse(raw)
            uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}