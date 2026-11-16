package com.ktp.helper

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.location.LocationManager
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
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
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

data class PendingScanReport(
    val title: String,
    val summary: String,
    val detail: String,
    val qrRawUrl: String,
    val failedNames: List<String>
)

object KtpHttpClient {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun warmup() = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://openapiv5.ketangpai.com/")
                .head()
                .header("User-Agent", MOBILE_UA)
                .build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {
        }
    }

    fun postJson(urlStr: String, token: String, bodyJson: String): String? {
        return try {
            val req = Request.Builder()
                .url(urlStr)
                .header("token", token)
                .header("User-Agent", MOBILE_UA)
                .header("Origin", "https://w.ketangpai.com")
                .header("Referer", "https://w.ketangpai.com/")
                .header("Accept", "application/json, text/plain, */*")
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
        if (recentKeys.size > 32) {
            recentKeys.entries.removeIf { now - it.value > 15_000L }
        }
        val last = recentKeys[key]
        if (last != null && now - last < cooldownMs) {
            return false
        }
        recentKeys[key] = now
        return true
    }
}

object KtpQrUrlParser {
    fun parseParams(rawInput: String, depth: Int = 0): Map<String, String> {
        val map = linkedMapOf<String, String>()
        if (depth > 2) return map
        val trimmed = rawInput.trim()

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (v.isNotEmpty()) {
                        map[k] = v
                        map[k.lowercase(Locale.US)] = v
                    }
                }
                return map
            } catch (_: Exception) {
            }
        }

        val parts = trimmed.split("#")
        for (part in parts) {
            val qIdx = part.indexOf('?')
            if (qIdx >= 0 && qIdx + 1 < part.length) {
                val queryStr = part.substring(qIdx + 1)
                for (pair in queryStr.split("&")) {
                    val eqIdx = pair.indexOf('=')
                    if (eqIdx > 0) {
                        val rawKey = pair.substring(0, eqIdx).trim()
                        val rawVal = pair.substring(eqIdx + 1).trim()
                        val decodedVal = try {
                            URLDecoder.decode(rawVal, "UTF-8")
                        } catch (_: Exception) {
                            rawVal
                        }
                        if (decodedVal.isNotEmpty()) {
                            map[rawKey] = decodedVal
                            map[rawKey.lowercase(Locale.US)] = decodedVal
                            val lk = rawKey.lowercase(Locale.US)
                            if (lk in listOf("redirect", "url", "target", "return_url", "next") &&
                                (decodedVal.contains("?") || decodedVal.contains("#"))
                            ) {
                                map.putAll(parseParams(decodedVal, depth + 1))
                            }
                        }
                    }
                }
            }
        }

        val pathRegex = Regex("""/(?:attence|checkin|sign|q)/([A-Za-z0-9_-]{4,})""", RegexOption.IGNORE_CASE)
        pathRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { seg ->
            if (!map.containsKey("path_id")) {
                map["path_id"] = seg
            }
        }
        return map
    }

    fun extractAttenceId(params: Map<String, String>): String {
        val keys = listOf("id", "attenceid", "attence_id", "signid", "sign_id", "checkinid", "checkin_id", "aid", "path_id")
        for (k in keys) {
            val v = params[k]
            if (!v.isNullOrEmpty() && v.all { ch -> ch.isDigit() }) {
                return v
            }
        }
        for (k in keys) {
            val v = params[k]
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }

    fun extractCourseId(params: Map<String, String>): String {
        val keys = listOf("courseid", "cid", "course_id")
        for (k in keys) {
            val v = params[k]
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }

    fun extractDynamicCode(rawInput: String, params: Map<String, String>): String {
        val keys = listOf("code", "sign", "signcode", "sign_code", "verify_code", "qrcode", "token", "key", "t")
        for (k in keys) {
            val v = params[k]
            if (!v.isNullOrEmpty()) return v
        }
        val trimmed = rawInput.trim()
        if (!trimmed.startsWith("http", ignoreCase = true) && !trimmed.startsWith("{") && trimmed.length in 4..64) {
            return trimmed
        }
        return params["path_id"] ?: ""
    }
}

class ProMetaStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("ktp_pro_glass_meta", Context.MODE_PRIVATE)

    companion object {
        private val metaCache = ConcurrentHashMap<String, StudentCourseMeta>()
    }

    fun isLightTheme(): Boolean = prefs.getBoolean("is_light_theme", false)

    fun setLightTheme(light: Boolean) {
        prefs.edit().putBoolean("is_light_theme", light).apply()
    }

    fun getStudentMeta(studentName: String): StudentCourseMeta {
        metaCache[studentName]?.let { cached ->
            return StudentCourseMeta(
                semester = cached.semester,
                courses = cached.courses.map { it.copy() }.toMutableList()
            )
        }
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
            val parsed = StudentCourseMeta(sem, list)
            metaCache[studentName] = StudentCourseMeta(sem, list.map { it.copy() }.toMutableList())
            parsed
        } catch (_: Exception) {
            StudentCourseMeta()
        }
    }

    fun saveStudentMeta(studentName: String, meta: StudentCourseMeta) {
        meta.courses.sortByDescending { it.lastActiveTs }
        metaCache[studentName] = StudentCourseMeta(
            semester = meta.semester,
            courses = meta.courses.map { it.copy() }.toMutableList()
        )
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
        metaCache.remove(studentName)
        prefs.edit().remove("courses_$studentName").apply()
    }

    fun clearAllMetaCache() {
        metaCache.clear()
        prefs.edit().clear().apply()
    }

    fun getGpsPreset(): GpsPreset? {
        val label = prefs.getString("gps_label", null) ?: return null
        val lat = prefs.getString("gps_lat", null)?.toDoubleOrNull() ?: return null
        val lng = prefs.getString("gps_lng", null)?.toDoubleOrNull() ?: return null
        return GpsPreset(label, lat, lng)
    }

    fun getGpsPresets(): List<GpsPreset> {
        val raw = prefs.getString("gps_presets_list", "[]") ?: "[]"
        val list = mutableListOf<GpsPreset>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val l = o.optString("label")
                val la = o.optDouble("lat", Double.NaN)
                val ln = o.optDouble("lng", Double.NaN)
                if (l.isNotEmpty() && !la.isNaN() && !ln.isNaN()) {
                    list.add(GpsPreset(l, la, ln))
                }
            }
        } catch (_: Exception) {
        }
        val current = getGpsPreset()
        if (current != null && list.none { it.label == current.label }) {
            list.add(0, current)
        }
        return list
    }

    fun saveGpsPreset(label: String, lat: Double, lng: Double) {
        val existing = getGpsPresets().filterNot { it.label == label }.toMutableList()
        existing.add(0, GpsPreset(label, lat, lng))
        val arr = JSONArray()
        existing.take(6).forEach { p ->
            arr.put(JSONObject().apply {
                put("label", p.label)
                put("lat", p.lat)
                put("lng", p.lng)
            })
        }
        prefs.edit()
            .putString("gps_label", label)
            .putString("gps_lat", lat.toString())
            .putString("gps_lng", lng.toString())
            .putString("gps_presets_list", arr.toString())
            .apply()
    }

    fun removeGpsPreset(label: String) {
        val remaining = getGpsPresets().filterNot { it.label == label }
        val arr = JSONArray()
        remaining.forEach { p ->
            arr.put(JSONObject().apply {
                put("label", p.label)
                put("lat", p.lat)
                put("lng", p.lng)
            })
        }
        val ed = prefs.edit().putString("gps_presets_list", arr.toString())
        if (getGpsPreset()?.label == label) {
            ed.remove("gps_label").remove("gps_lat").remove("gps_lng")
        }
        ed.apply()
    }

    fun clearGpsPreset() {
        prefs.edit().remove("gps_label").remove("gps_lat").remove("gps_lng").apply()
    }

    @SuppressLint("MissingPermission")
    fun resolveEffectiveGps(): GpsPreset? {
        getGpsPreset()?.let { return it }
        return try {
            val fineOk = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarseOk = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fineOk && !coarseOk) return null
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: return null
            GpsPreset("当前位置", loc.latitude, loc.longitude)
        } catch (_: Exception) {
            null
        }
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

    fun saveLastTask(type: String, value: String) {
        prefs.edit()
            .putString("last_task_type", type)
            .putString("last_task_val", value)
            .putLong("last_task_ts", System.currentTimeMillis())
            .apply()
    }

    fun getLastTask(): Pair<String, String>? {
        val ts = prefs.getLong("last_task_ts", 0L)
        if (System.currentTimeMillis() - ts > 20 * 60 * 1000L) return null
        val t = prefs.getString("last_task_type", null) ?: return null
        val v = prefs.getString("last_task_val", null) ?: return null
        return Pair(t, v)
    }

    fun savePendingScanReport(title: String, summary: String, detail: String, qrRawUrl: String, failedNames: List<String>) {
        val arr = JSONArray()
        failedNames.forEach { n -> arr.put(n) }
        val obj = JSONObject().apply {
            put("title", title)
            put("summary", summary)
            put("detail", detail)
            put("qrRawUrl", qrRawUrl)
            put("failedNames", arr)
            put("ts", System.currentTimeMillis())
        }
        prefs.edit().putString("pending_scan_report", obj.toString()).apply()
    }

    fun consumePendingScanReport(): PendingScanReport? {
        val raw = prefs.getString("pending_scan_report", null) ?: return null
        prefs.edit().remove("pending_scan_report").apply()
        return try {
            val obj = JSONObject(raw)
            if (System.currentTimeMillis() - obj.optLong("ts", 0L) > 30_000L) return null
            val arr = obj.optJSONArray("failedNames") ?: JSONArray()
            val names = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                names.add(arr.optString(i))
            }
            PendingScanReport(
                title = obj.optString("title"),
                summary = obj.optString("summary"),
                detail = obj.optString("detail"),
                qrRawUrl = obj.optString("qrRawUrl"),
                failedNames = names
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearRecords() {
        prefs.edit().remove("checkin_records").apply()
    }
}

object KtpCourseSyncer {

    fun extractCourseIdFromUrl(rawUrl: String): String? {
        val params = KtpQrUrlParser.parseParams(rawUrl)
        return KtpQrUrlParser.extractCourseId(params).ifEmpty { null }
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

    private sealed class QrSingleResult {
        object Success : QrSingleResult()
        object Skipped : QrSingleResult()
        data class Failed(val reason: String) : QrSingleResult()
    }

    suspend fun checkTokenValid(token: String): Boolean = withContext(Dispatchers.IO) {
        val checkUrls = listOf(
            "https://openapiv5.ketangpai.com/CourseApi/semesterCourseList" to """{"isstudy":"1","search":""}""",
            "https://openapiv5.ketangpai.com/UserApi/getUserInfo" to "{}"
        )
        for ((url, body) in checkUrls) {
            val resp = KtpHttpClient.postJson(url, token, body) ?: continue
            try {
                val json = JSONObject(resp)
                if (json.optInt("code", -1) == 10000 || json.optInt("status", -1) == 1) {
                    return@withContext true
                }
            } catch (_: Exception) {
            }
        }
        false
    }

    suspend fun batchQrCheckIn(
        context: Context,
        accounts: List<Account>,
        qrRawUrl: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): Pair<Int, List<String>> = coroutineScope {
        val metaStore = ProMetaStore(context)
        metaStore.saveLastTask("QR", qrRawUrl)
        val gps = metaStore.resolveEffectiveGps()
        val doneCounter = AtomicInteger(0)

        val tasks = accounts.map { acc ->
            async(Dispatchers.IO) {
                delay(Random.nextLong(90L, 310L))
                val res = executeSingleQr(metaStore, acc, qrRawUrl, gps)
                onProgress(doneCounter.incrementAndGet(), accounts.size)
                res
            }
        }

        val results = tasks.awaitAll()
        var ok = 0
        val failed = mutableListOf<String>()
        results.forEach { res ->
            when (res) {
                is QrSingleResult.Success -> ok++
                is QrSingleResult.Skipped -> {}
                is QrSingleResult.Failed -> failed.add(res.reason)
            }
        }
        Pair(ok, failed)
    }

    private suspend fun executeSingleQr(
        metaStore: ProMetaStore,
        acc: Account,
        qrRawUrl: String,
        gps: GpsPreset?
    ): QrSingleResult {
        return try {
            val params = KtpQrUrlParser.parseParams(qrRawUrl)
            var attId = KtpQrUrlParser.extractAttenceId(params)
            val dynCode = KtpQrUrlParser.extractDynamicCode(qrRawUrl, params)

            if (attId.isEmpty()) {
                val active = KtpNumericCheckInEngine.findActiveAttenceForAccount(metaStore, acc)
                if (active != null) {
                    if (active.second.isNotEmpty()) {
                        val matched = metaStore.getStudentMeta(acc.name).courses.firstOrNull { it.courseId == active.second }
                        if (matched != null && !matched.enabled) {
                            return QrSingleResult.Skipped
                        }
                        metaStore.markCourseActive(acc.name, active.second)
                    }
                    attId = active.first
                }
            }

            if (attId.isEmpty()) {
                return QrSingleResult.Failed("${acc.name}(未识别到考勤ID，且当前无进行中签到)")
            }

            val payload = JSONObject().apply {
                for ((k, v) in params) {
                    if (k != "path_id" && v.isNotEmpty()) {
                        put(k, v)
                    }
                }
                put("id", attId)
                put("attenceid", attId)
                put("attence_id", attId)
                if (dynCode.isNotEmpty()) {
                    if (!has("code")) put("code", dynCode)
                    if (!has("sign")) put("sign", dynCode)
                    if (!has("qrcode")) put("qrcode", dynCode)
                }
                if (gps != null) {
                    val latOffset = (Random.nextDouble() - 0.5) * 0.00014
                    val lngOffset = (Random.nextDouble() - 0.5) * 0.00014
                    put("latitude", String.format(Locale.US, "%.6f", gps.lat + latOffset))
                    put("longitude", String.format(Locale.US, "%.6f", gps.lng + lngOffset))
                }
            }

            var resp = KtpHttpClient.postJson(
                "https://openapiv5.ketangpai.com/AttenceApi/checkin",
                acc.token,
                payload.toString()
            )
            if (resp != null && (resp.contains("方式") || resp.contains("二维码"))) {
                val fallbackResp = KtpHttpClient.postJson(
                    "https://openapiv5.ketangpai.com/AttenceApi/AttenceQrCode",
                    acc.token,
                    payload.toString()
                )
                if (!fallbackResp.isNullOrEmpty()) resp = fallbackResp
            }
            if (resp == null) return QrSingleResult.Failed("${acc.name}(请求超时)")

            val json = JSONObject(resp)
            val code = json.optInt("code", -1)
            val status = json.optInt("status", -1)
            val msg = json.optString("message", json.optString("msg", ""))

            if (code == 10000 || status == 1 || msg.contains("成功") || msg.contains("已签到") || msg.contains("重复")) {
                QrSingleResult.Success
            } else {
                QrSingleResult.Failed("${acc.name}(${msg.ifEmpty { "二维码已过期或签到失败" }})")
            }
        } catch (_: Exception) {
            QrSingleResult.Failed("${acc.name}(请求异常)")
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
        metaStore.saveLastTask("NUM", code)
        val gps = metaStore.resolveEffectiveGps()
        val doneCounter = AtomicInteger(0)

        val tasks = enabledList.map { acc ->
            async(Dispatchers.IO) {
                delay(Random.nextLong(100L, 360L))
                val outcome = executeForSingleStudent(metaStore, acc, code, gps)
                onProgress(doneCounter.incrementAndGet(), enabledList.size)
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
        val activeTask = findActiveAttenceForAccount(metaStore, acc)
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
            put("attence_id", attenceId)
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

            if (statusCode == 10000 || statusFlag == 1 || msg.contains("成功") || msg.contains("已签到") || msg.contains("重复")) {
                SingleOutcome.Success
            } else {
                val reason = msg.ifEmpty { "口令错误或已结束" }
                SingleOutcome.Failed("${acc.name}($reason)")
            }
        } catch (_: Exception) {
            SingleOutcome.Failed("${acc.name}(响应异常)")
        }
    }

    suspend fun findActiveAttenceForAccount(
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