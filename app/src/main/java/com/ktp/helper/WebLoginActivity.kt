package com.ktp.helper

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
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

class WebLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var captured = false

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0B1120.toInt()

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
            text = "录入同学凭证"
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
            progressDrawable = LayerDrawable(arrayOf(
                ColorDrawable(0xFF1E293B.toInt()),
                ClipDrawable(ColorDrawable(0xFF0A84FF.toInt()), Gravity.START, ClipDrawable.HORIZONTAL)
            ))
        }

        val tipBar = TextView(this).apply {
            text = "已开启独立隔离环境 · 登录课堂派后自动保存 Token"
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
                        promptStudentName(token)
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
            .setMessage("已自动捕获 Token，请输入备注姓名保存：")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "同学_${System.currentTimeMillis() % 1000}" }
                AccountStore(this).addOrUpdateAccount(name, token)
                Toast.makeText(this, "已加密保存：$name", Toast.LENGTH_SHORT).show()
                finish()
            }
            .show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}