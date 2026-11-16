package com.ktp.helper

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WebLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var captured = false
    private var presetName: String? = null

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0B1120.toInt()
        presetName = intent.getStringExtra("preset_name")

        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF070B14.toInt())
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0B1120.toInt())
            setPadding(12.dp, 12.dp, 16.dp, 12.dp)
        }
        val backBtn = TextView(this).apply {
            text = "‹ 返回"
            textSize = 16f
            setTextColor(0xFF0A84FF.toInt())
            setPadding(8.dp, 6.dp, 12.dp, 6.dp)
            setOnClickListener { finish() }
        }
        val barTitle = TextView(this).apply {
            text = if (presetName != null) "更新「$presetName」凭证" else "添加账号并同步课表"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        topBar.addView(backBtn)
        topBar.addView(barTitle, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(View(this), LinearLayout.LayoutParams(54.dp, 1))

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = LayerDrawable(arrayOf<Drawable>(
                ColorDrawable(0xFF1E293B.toInt()),
                ClipDrawable(ColorDrawable(0xFF0A84FF.toInt()), Gravity.START, ClipDrawable.HORIZONTAL)
            ))
        }

        val tipBar = TextView(this).apply {
            text = "已开启独立隔离环境 · 登录成功后自动提取凭证并拉取本学期课表"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(16.dp, 9.dp, 16.dp, 9.dp)
            setBackgroundColor(0xFF11192E.toInt())
            setTextColor(0xFF30D158.toInt())
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
            webViewClient = WebViewClient()
            loadUrl("https://w.ketangpai.com")
        }

        root.addView(topBar)
        root.addView(progressBar, LinearLayout.LayoutParams(-1, 2.dp))
        root.addView(tipBar)
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        startTokenSniffer()
    }

    private fun startTokenSniffer() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (captured || isFinishing) return
                webView.evaluateJavascript(
                    "(function(){ return localStorage.getItem('token') || localStorage.getItem('token_for_login') || ''; })();"
                ) { result ->
                    val token = result?.trim('"') ?: ""
                    if (token.length > 20 && token != "null") {
                        captured = true
                        val fixedName = presetName
                        if (!fixedName.isNullOrEmpty()) {
                            saveAndSyncCourses(fixedName, token)
                        } else {
                            promptStudentName(token)
                        }
                    } else {
                        handler.postDelayed(this, 1500)
                    }
                }
            }
        }, 1500)
    }

    private fun promptStudentName(token: String) {
        val box = FrameLayout(this).apply { setPadding(24.dp, 12.dp, 24.dp, 4.dp) }
        val input = EditText(this).apply { hint = "输入该同学姓名（如：张三）" }
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle("提取凭证成功")
            .setMessage("请输入备注姓名（保存时将自动同步该同学本学期课表）：")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("保存并同步") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "同学_${System.currentTimeMillis() % 1000}" }
                saveAndSyncCourses(name, token)
            }
            .show()
    }

    private fun saveAndSyncCourses(name: String, token: String) {
        AccountStore(this).addOrUpdateAccount(name, token)
        lifecycleScope.launch {
            val metaStore = ProMetaStore(this@WebLoginActivity)
            val oldMeta = metaStore.getStudentMeta(name)
            val (sem, courses) = KtpCourseSyncer.syncCourses(token)
            if (courses.isNotEmpty()) {
                val oldMap = oldMeta.courses.associateBy { it.courseId }
                courses.forEach { c ->
                    oldMap[c.courseId]?.let { old ->
                        c.enabled = old.enabled
                        c.lastActiveTs = old.lastActiveTs
                    }
                }
                metaStore.saveStudentMeta(name, StudentCourseMeta(sem, courses))
                Toast.makeText(this@WebLoginActivity, "$name 已保存并同步 ${courses.size} 门课程", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@WebLoginActivity, "已加密保存：$name", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}