package com.ktp.helper

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

typealias Account = StudentAccount

data class CourseItem(
    val courseId: String,
    val courseName: String,
    var enabled: Boolean = true,
    var lastActiveTs: Long = 0L
)

data class StudentCourseMeta(
    var semester: String = "当前学期",
    var courses: MutableList<CourseItem> = mutableListOf()
)

data class CheckInRecord(
    val time: String,
    val mode: String,
    val summary: String,
    val detail: String
)

data class GpsPreset(
    val label: String,
    val lat: Double,
    val lng: Double
)

data class NumericBatchResult(
    val okCount: Int,
    val skipped: List<String>,
    val failed: List<String>
)

object KtpHttpClient {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun postJson(urlStr: String, token: String, bodyJson: String): String? {
        return try {
            val req = Request.Builder()
                .url(urlStr)
                .header("token", token)
                .header("Content-Type", "application/json;charset=UTF-8")
                .post(bodyJson.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

object CheckInCooldownGuard {
    private val recentKeys = ConcurrentHashMap<String, Long>()

    fun tryAcquire(key: String, cooldownMs: Long = 3000L): Boolean {
        val now = System.currentTimeMillis()
        val last = recentKeys[key]
        if (last != null && now - last < cooldownMs) {
            return false
        }
        recentKeys[key] = now
        return true
    }
}

class ProMetaStore(context: Context) {
    private val prefs = context.getSharedPreferences("ktp_pro_glass_meta", Context.MODE_PRIVATE)

    fun isLightTheme(): Boolean = prefs.getBoolean("is_light_theme", false)

    fun setLightTheme(light: Boolean) {
        prefs.edit().putBoolean("is_light_theme", light).apply()
    }

    fun getStudentMeta(studentName: String): StudentCourseMeta {
        val raw = prefs.getString("courses_$studentName", null) ?: return StudentCourseMeta()
        return try {
            val obj = JSONObject(raw)
            val sem = obj.optString("semester", "当前学期")
            val arr = obj.optJSONArray("courses") ?: JSONArray()
            val list = mutableListOf<CourseItem>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                list.add(
                    CourseItem(
                        courseId = item.optString("id"),
                        courseName = item.optString("name"),
                        enabled = item.optBoolean("enabled", true),
                        lastActiveTs = item.optLong("lastActiveTs", 0L)
                    )
                )
            }
            list.sortByDescending { it.lastActiveTs }
            StudentCourseMeta(sem, list)
        } catch (_: Exception) {
            StudentCourseMeta()
        }
    }

    fun saveStudentMeta(studentName: String, meta: StudentCourseMeta) {
        meta.courses.sortByDescending { it.lastActiveTs }
        val arr = JSONArray()
        meta.courses.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.courseId)
                put("name", c.courseName)
                put("enabled", c.enabled)
                put("lastActiveTs", c.lastActiveTs)
            })
        }
        val root = JSONObject().apply {
            put("semester", meta.semester)
            put("courses", arr)
        }
        prefs.edit().putString("courses_$studentName", root.toString()).apply()
    }

    fun markCourseActive(studentName: String, courseId: String) {
        if (courseId.isEmpty()) return
        val meta = getStudentMeta(studentName)
        val target = meta.courses.firstOrNull { it.courseId == courseId } ?: return
        target.lastActiveTs = System.currentTimeMillis()
        saveStudentMeta(studentName, meta)
    }

    fun removeStudentMeta(studentName: String) {
        prefs.edit().remove("courses_$studentName").apply()
    }

    fun getGpsPreset(): GpsPreset? {
        val label = prefs.getString("gps_label", null) ?: return null
        val lat = prefs.getString("gps_lat", null)?.toDoubleOrNull() ?: return null
        val lng = prefs.getString("gps_lng", null)?.toDoubleOrNull() ?: return null
        return GpsPreset(label, lat, lng)
    }

    fun saveGpsPreset(label: String, lat: Double, lng: Double) {
        prefs.edit()
            .putString("gps_label", label)
            .putString("gps_lat", lat.toString())
            .putString("gps_lng", lng.toString())
            .apply()
    }

    fun clearGpsPreset() {
        prefs.edit().remove("gps_label").remove("gps_lat").remove("gps_lng").apply()
    }

    fun getRecords(): List<CheckInRecord> {
        val raw = prefs.getString("checkin_records", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<CheckInRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    CheckInRecord(
                        time = o.optString("time"),
                        mode = o.optString("mode"),
                        summary = o.optString("summary"),
                        detail = o.optString("detail")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecord(mode: String, summary: String, detail: String) {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val current = getRecords().toMutableList()
        current.add(0, CheckInRecord(fmt.format(Date()), mode, summary, detail))
        val trimmed = current.take(15)
        val arr = JSONArray()
        trimmed.forEach { r ->
            arr.put(JSONObject().apply {
                put("time", r.time)
                put("mode", r.mode)
                put("summary", r.summary)
                put("detail", r.detail)
            })
        }
        prefs.edit().putString("checkin_records", arr.toString()).apply()
    }

    fun clearRecords() {
        prefs.edit().remove("checkin_records").apply()
    }
}

object KtpCourseSyncer {

    fun extractCourseIdFromUrl(rawUrl: String): String? {
        return try {
            val uri = Uri.parse(rawUrl)
            uri.getQueryParameter("courseid")
                ?: uri.getQueryParameter("courseId")
                ?: uri.getQueryParameter("cid")
        } catch (_: Exception) {
            null
        }
    }

    fun filterAccountsByCourse(
        context: Context,
        accounts: List<Account>,
        rawUrl: String
    ): Pair<List<Account>, List<String>> {
        val metaStore = ProMetaStore(context)
        val targetCourseId = extractCourseIdFromUrl(rawUrl)
        val active = mutableListOf<Account>()
        val skipped = mutableListOf<String>()

        for (acc in accounts) {
            if (!acc.enabled) continue
            if (!targetCourseId.isNullOrEmpty()) {
                val meta = metaStore.getStudentMeta(acc.name)
                val matched = meta.courses.firstOrNull { it.courseId == targetCourseId }
                if (matched != null && !matched.enabled) {
                    skipped.add("${acc.name}(已取消勾选${matched.courseName})")
                    continue
                }
                metaStore.markCourseActive(acc.name, targetCourseId)
            }
            active.add(acc)
        }
        return Pair(active, skipped)
    }

    suspend fun syncCourses(token: String): Pair<String, MutableList<CourseItem>> = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://openapiv5.ketangpai.com/CourseApi/semesterCourseList" to """{"isstudy":"1","search":""}""",
            "https://openapiv5.ketangpai.com/CourseApi/getCourseList" to """{"isstudy":"1"}"""
        )

        for ((urlStr, bodyJson) in endpoints) {
            try {
                val text = KtpHttpClient.postJson(urlStr, token, bodyJson) ?: continue
                val root = JSONObject(text)
                val dataNode = root.opt("data")
                val parsed = mutableListOf<CourseItem>()
                var semesterLabel = "当前学期"

                when (dataNode) {
                    is JSONArray -> {
                        for (i in 0 until dataNode.length()) {
                            val block = dataNode.optJSONObject(i) ?: continue
                            if (block.has("list") || block.has("courses")) {
                                if (i == 0) {
                                    val y = block.optString("semester", "")
                                    val t = block.optString("term", "")
                                    if (y.isNotEmpty()) semesterLabel = "$y $t".trim()
                                }
                                val subArr = block.optJSONArray("list") ?: block.optJSONArray("courses") ?: JSONArray()
                                for (j in 0 until subArr.length()) {
                                    parseSingleCourse(subArr.optJSONObject(j))?.let { parsed.add(it) }
                                }
                            } else {
                                parseSingleCourse(block)?.let { parsed.add(it) }
                            }
                        }
                    }
                    is JSONObject -> {
                        val arr = dataNode.optJSONArray("list") ?: dataNode.optJSONArray("courses") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            parseSingleCourse(arr.optJSONObject(i))?.let { parsed.add(it) }
                        }
                    }
                }

                if (parsed.isNotEmpty()) {
                    return@withContext Pair(semesterLabel, parsed)
                }
            } catch (_: Exception) {
            }
        }
        Pair("当前学期", mutableListOf())
    }

    private fun parseSingleCourse(obj: JSONObject?): CourseItem? {
        if (obj == null) return null
        val id = obj.optString("id").ifEmpty { obj.optString("courseid") }
        val name = obj.optString("coursename").ifEmpty { obj.optString("name") }
        if (id.isEmpty() || name.isEmpty()) return null
        return CourseItem(courseId = id, courseName = name, enabled = true)
    }
}

object KtpQrBatchEngine {

    suspend fun checkTokenValid(token: String): Boolean = withContext(Dispatchers.IO) {
        val resp = KtpHttpClient.postJson(
            "https://openapiv5.ketangpai.com/CourseApi/semesterCourseList",
            token,
            """{"isstudy":"1","search":""}"""
        ) ?: return@withContext false
        return@withContext try {
            val json = JSONObject(resp)
            json.optInt("code", -1) == 10000 || json.optInt("status", -1) == 1
        } catch (_: Exception) {
            false
        }
    }

    suspend fun batchQrCheckIn(
        context: Context,
        accounts: List<Account>,
        qrRawUrl: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): Pair<Int, List<String>> = coroutineScope {
        val gps = ProMetaStore(context).getGpsPreset()
        var doneCount = 0

        val tasks = accounts.map { acc ->
            async(Dispatchers.IO) {
                delay(Random.nextLong(110L, 380L))
                val err = executeSingleQr(acc, qrRawUrl, gps)
                synchronized(this@KtpQrBatchEngine) {
                    doneCount++
                    onProgress(doneCount, accounts.size)
                }
                err
            }
        }

        val results = tasks.awaitAll()
        var ok = 0
        val failed = mutableListOf<String>()
        results.forEach { err ->
            if (err == null) ok++ else failed.add(err)
        }
        Pair(ok, failed)
    }

    private fun executeSingleQr(acc: Account, qrRawUrl: String, gps: GpsPreset?): String? {
        return try {
            val uri = Uri.parse(qrRawUrl)
            val attId = uri.getQueryParameter("id")
                ?: uri.getQueryParameter("attenceid")
                ?: uri.getQueryParameter("signid")
                ?: ""
            if (attId.isEmpty()) return "${acc.name}(二维码链接缺少考勤ID)"

            val payload = JSONObject().apply {
                for (key in uri.queryParameterNames) {
                    val v = uri.getQueryParameter(key)
                    if (!v.isNullOrEmpty()) put(key, v)
                }
                put("id", attId)
                put("attenceid", attId)
                if (gps != null) {
                    val latOffset = (Random.nextDouble() - 0.5) * 0.00014
                    val lngOffset = (Random.nextDouble() - 0.5) * 0.00014
                    put("latitude", String.format(Locale.US, "%.6f", gps.lat + latOffset))
                    put("longitude", String.format(Locale.US, "%.6f", gps.lng + lngOffset))
                }
            }

            val resp = KtpHttpClient.postJson(
                "https://openapiv5.ketangpai.com/AttenceApi/checkin",
                acc.token,
                payload.toString()
            ) ?: return "${acc.name}(请求超时)"

            val json = JSONObject(resp)
            val code = json.optInt("code", -1)
            val status = json.optInt("status", -1)
            val msg = json.optString("message", json.optString("msg", ""))

            if (code == 10000 || status == 1 || msg.contains("成功") || msg.contains("已签到")) {
                null
            } else {
                "${acc.name}(${msg.ifEmpty { "二维码已过期或签到失败" }})"
            }
        } catch (_: Exception) {
            "${acc.name}(请求异常)"
        }
    }
}

object KtpNumericCheckInEngine {

    private sealed class SingleOutcome {
        object Success : SingleOutcome()
        data class Skipped(val reason: String) : SingleOutcome()
        data class Failed(val reason: String) : SingleOutcome()
    }

    suspend fun batchNumericCheckIn(
        context: Context,
        accounts: List<Account>,
        code: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): NumericBatchResult = coroutineScope {
        val enabledList = accounts.filter { it.enabled }
        val metaStore = ProMetaStore(context)
        val gps = metaStore.getGpsPreset()
        var doneCount = 0

        val tasks = enabledList.map { acc ->
            async(Dispatchers.IO) {
                delay(Random.nextLong(120L, 450L))
                val outcome = executeForSingleStudent(metaStore, acc, code, gps)
                synchronized(this@KtpNumericCheckInEngine) {
                    doneCount++
                    onProgress(doneCount, enabledList.size)
                }
                outcome
            }
        }

        val results = tasks.awaitAll()
        var ok = 0
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()

        results.forEach { res ->
            when (res) {
                is SingleOutcome.Success -> ok++
                is SingleOutcome.Skipped -> skipped.add(res.reason)
                is SingleOutcome.Failed -> failed.add(res.reason)
            }
        }
        NumericBatchResult(ok, skipped, failed)
    }

    private suspend fun executeForSingleStudent(
        metaStore: ProMetaStore,
        acc: Account,
        code: String,
        gps: GpsPreset?
    ): SingleOutcome {
        val activeTask = findActiveAttence(metaStore, acc)
            ?: return SingleOutcome.Failed("${acc.name}(未检测到进行中的签到任务)")

        val (attenceId, courseId, serverCode) = activeTask
        if (courseId.isNotEmpty()) {
            val meta = metaStore.getStudentMeta(acc.name)
            val matched = meta.courses.firstOrNull { it.courseId == courseId }
            if (matched != null && !matched.enabled) {
                return SingleOutcome.Skipped("${acc.name}(已取消勾选${matched.courseName})")
            }
            metaStore.markCourseActive(acc.name, courseId)
        }

        val finalCode = if (code.isNotEmpty() && code != "AUTO") code else serverCode
        if (finalCode.isEmpty()) {
            return SingleOutcome.Failed("${acc.name}(接口未直接返回明文码，请手动输入4位口令)")
        }

        val payload = JSONObject().apply {
            put("id", attenceId)
            put("attenceid", attenceId)
            put("code", finalCode)
            put("sign", finalCode)
            if (gps != null) {
                val latOffset = (Random.nextDouble() - 0.5) * 0.00014
                val lngOffset = (Random.nextDouble() - 0.5) * 0.00014
                put("latitude", String.format(Locale.US, "%.6f", gps.lat + latOffset))
                put("longitude", String.format(Locale.US, "%.6f", gps.lng + lngOffset))
            }
        }

        val respText = KtpHttpClient.postJson(
            "https://openapiv5.ketangpai.com/AttenceApi/checkin",
            acc.token,
            payload.toString()
        ) ?: return SingleOutcome.Failed("${acc.name}(请求超时)")

        return try {
            val json = JSONObject(respText)
            val statusCode = json.optInt("code", -1)
            val statusFlag = json.optInt("status", -1)
            val msg = json.optString("message", json.optString("msg", ""))

            if (statusCode == 10000 || statusFlag == 1 || msg.contains("成功") || msg.contains("已签到")) {
                SingleOutcome.Success
            } else {
                val reason = msg.ifEmpty { "口令错误或已结束" }
                SingleOutcome.Failed("${acc.name}($reason)")
            }
        } catch (_: Exception) {
            SingleOutcome.Failed("${acc.name}(响应异常)")
        }
    }

    private suspend fun findActiveAttence(
        metaStore: ProMetaStore,
        acc: Account
    ): Triple<String, String, String>? {
        val notFinishResp = KtpHttpClient.postJson(
            "https://openapiv5.ketangpai.com/AttenceApi/getNotFinishAttenceStudent",
            acc.token,
            "{}"
        )
        if (!notFinishResp.isNullOrEmpty()) {
            parseAttenceFromJson(notFinishResp, "")?.let { return it }
        }

        var meta = metaStore.getStudentMeta(acc.name)
        if (meta.courses.isEmpty()) {
            val (sem, synced) = KtpCourseSyncer.syncCourses(acc.token)
            if (synced.isNotEmpty()) {
                meta = StudentCourseMeta(sem, synced)
                metaStore.saveStudentMeta(acc.name, meta)
            }
        }

        for (course in meta.courses) {
            if (!course.enabled) continue
            val listResp = KtpHttpClient.postJson(
                "https://openapiv5.ketangpai.com/AttenceApi/attenceList",
                acc.token,
                """{"courseid":"${course.courseId}"}"""
            ) ?: continue
            parseAttenceFromJson(listResp, course.courseId)?.let { return it }
        }
        return null
    }

    private fun parseAttenceFromJson(rawJson: String, fallbackCourseId: String): Triple<String, String, String>? {
        return try {
            val root = JSONObject(rawJson)
            val dataNode = root.opt("data")
            val arr = when (dataNode) {
                is JSONArray -> dataNode
                is JSONObject -> dataNode.optJSONArray("list") ?: dataNode.optJSONArray("attence") ?: dataNode.optJSONArray("lists") ?: JSONArray()
                else -> JSONArray()
            }
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id").ifEmpty { item.optString("attenceid") }.ifEmpty { item.optString("signid") }
                if (id.isEmpty()) continue
                val state = item.optInt("state", 0)
                val isSign = item.optInt("is_sign", 0)
                val status = item.optInt("status", 0)
                if (state == 0 && isSign == 0 && status != 2) {
                    val cid = item.optString("courseid").ifEmpty { fallbackCourseId }
                    val leakedCode = item.optString("code")
                        .ifEmpty { item.optString("sign_code") }
                        .ifEmpty { item.optString("signcode") }
                        .ifEmpty { item.optString("number") }
                        .ifEmpty { item.optString("verify_code") }
                    return Triple(id, cid, leakedCode)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

object KtpShortcutHelper {
    fun registerDynamicShortcuts(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        try {
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return
            val scanIntent = Intent(context, ScanActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val numericIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("open_numeric_dialog", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val s1 = ShortcutInfo.Builder(context, "shortcut_scan_qr")
                .setShortLabel("极速扫码")
                .setLongLabel("极速扫动态二维码")
                .setIcon(Icon.createWithResource(context, android.R.drawable.ic_menu_camera))
                .setIntent(scanIntent)
                .build()

            val s2 = ShortcutInfo.Builder(context, "shortcut_numeric_code")
                .setShortLabel("数字口令")
                .setLongLabel("数字口令全员代签")
                .setIcon(Icon.createWithResource(context, android.R.drawable.ic_dialog_dialer))
                .setIntent(numericIntent)
                .build()

            sm.dynamicShortcuts = listOf(s1, s2)
        } catch (_: Exception) {
        }
    }
}

@RequiresApi(Build.VERSION_CODES.N)
class KtpQuickTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ScanActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}