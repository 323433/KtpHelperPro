package com.ktp.helper

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class WebLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var captured = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tipBar = TextView(this).apply {
            text = "请直接登录课堂派，登录成功后将自动提取凭证"
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xFF1E1E1E.toInt())
            setTextColor(0xFF4CAF50.toInt())
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            loadUrl("https://w.ketangpai.com")
        }

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
        val input = EditText(this).apply { hint = "输入该同学姓名/备注（如：张三）" }
        AlertDialog.Builder(this)
            .setTitle("提取凭证成功")
            .setMessage("已自动捕获 Token，请输入备注姓名保存：")
            .setView(input)
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