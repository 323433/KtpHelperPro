package com.ktp.helper

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WebLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var captured = false
    private var storageCleared = false
    private var presetName: String? = null

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private val Float.dpF: Float
        get() = this * resources.displayMetrics.density

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 28.dp
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = 0xFF070B14.toInt()
        }
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
            setPadding(16.dp, getStatusBarHeight() + 10.dp, 16.dp, 12.dp)
        }
        val backBtn = TextView(this).apply {
            text = "‹ 返回"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF64D2FF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f.dpF
                setColor(0x1FFFFFFF)
            }
            setPadding(12.dp, 6.dp, 14.dp, 6.dp)
            setOnClickListener { finishWithTransition() }
        }
        val barTitle = TextView(this).apply {
            text = if (presetName != null) "更新「$presetName」凭证" else "添加成员并同步课表"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val sandboxPill = TextView(this).apply {
            text = "🔒 隔离沙盒"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF30D158.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f.dpF
                setColor(0x2230D158)
            }
            setPadding(8.dp, 5.dp, 8.dp, 5.dp)
        }
        topBar.addView(backBtn)
        topBar.addView(barTitle, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(sandboxPill)

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = LayerDrawable(arrayOf<Drawable>(
                ColorDrawable(0xFF1E293B.toInt()),
                ClipDrawable(ColorDrawable(0xFF0A84FF.toInt()), Gravity.START, ClipDrawable.HORIZONTAL)
            ))
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
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!storageCleared) {
                        storageCleared = true
                        view?.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null)
                    }
                }
            }
            loadUrl("https://w.ketangpai.com")
        }

        root.addView(topBar)
        root.addView(progressBar, LinearLayout.LayoutParams(-1, 2.dp))
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        startTokenSniffer()
    }

    private fun startTokenSniffer() {
        val jsProbe = """
            (function(){
                try {
                    var t = localStorage.getItem('token') || localStorage.getItem('token_for_login') || sessionStorage.getItem('token') || '';
                    if (!t) {
                        var u = localStorage.getItem('userInfo') || localStorage.getItem('user_info') || '';
                        if (u && u.indexOf('token') !== -1) {
                            var obj = JSON.parse(u);
                            t = obj.token || '';
                        }
                    }
                    return t;
                } catch(e) { return ''; }
            })();
        """.trimIndent()

        handler.postDelayed(object : Runnable {
            override fun run() {
                if (captured || isFinishing) return
                webView.evaluateJavascript(jsProbe) { result ->
                    val token = result?.trim('"') ?: ""
                    if (token.length > 20 && token != "null") {
                        captured = true
                        val fixedName = presetName
                        if (!fixedName.isNullOrEmpty()) {
                            saveAndSyncCourses(fixedName, token)
                        } else {
                            promptStudentNameSheet(token)
                        }
                    } else {
                        handler.postDelayed(this, 1500)
                    }
                }
            }
        }, 2100)
    }

    private fun promptStudentNameSheet(token: String) {
        val sheet = GlassBottomSheet(
            context = this,
            isLight = false,
            title = "登录凭证提取成功",
            subtitle = "保存后将自动拉取该同学本学期全部课程",
            badgeColor = 0xFF30D158.toInt(),
            badgeGlyph = "✓"
        )
        val body = sheet.getBody()
        val input = EditText(this).apply {
            hint = "请输入该同学姓名（如：张三）"
            setTextColor(Color.WHITE)
            setHintTextColor(0x66FFFFFF)
            textSize = 15f
            background = LayerDrawable(arrayOf<Drawable>(
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f.dpF
                    setColor(0x22FFFFFF)
                },
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f.dpF
                    setColor(Color.TRANSPARENT)
                    setStroke(1.dp, 0x45FFFFFF)
                }
            ))
            setPadding(14.dp, 13.dp, 14.dp, 13.dp)
        }
        body.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16.dp })

        val saveBtn = TextView(this).apply {
            text = "保存并自动同步课表"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF0A84FF.toInt(), 0xFF0055D4.toInt())
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f.dpF
            }
            setPadding(0, 14.dp, 0, 14.dp)
            setOnClickListener {
                val name = input.text.toString().trim().ifEmpty { "同学_${System.currentTimeMillis() % 1000}" }
                sheet.dismiss { saveAndSyncCourses(name, token) }
            }
        }
        body.addView(saveBtn)
        sheet.show()
    }

    private fun saveAndSyncCourses(name: String, token: String) {
        AccountStore(this).addOrUpdateAccount(name, token)
        GlassIslandBanner.show(this, "正在同步 $name 的本学期课表...", GlassIslandBanner.Tone.INFO, false, 1500L)
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
            }
            finishWithTransition()
        }
    }

    @Suppress("DEPRECATION")
    private fun finishWithTransition() {
        finish()
        overridePendingTransition(R.anim.page_pop_enter, R.anim.page_pop_exit)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithTransition()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}