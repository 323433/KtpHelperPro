package com.ktp.helper

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var store: AccountStore
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AccountStore(this)

        requestRuntimePermissions()
        startForegroundServiceCompat()

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 48)
        }

        val title = TextView(this).apply {
            text = "课堂派极速代签助手"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        }

        val btnScan = Button(this).apply {
            text = "📷 极速扫动态二维码（全员并发）"
            setOnClickListener { startActivity(Intent(this@MainActivity, ScanActivity::class.java)) }
        }

        val btnAddWeb = Button(this).apply {
            text = "➕ 网页登录录入同学账号"
            setOnClickListener { startActivity(Intent(this@MainActivity, WebLoginActivity::class.java)) }
        }

        val btnCheckAlive = Button(this).apply {
            text = "🩺 一键检测全员 Token 存活状态"
            setOnClickListener { verifyAllTokens() }
        }

        val btnShareConfig = Button(this).apply {
            text = "📤 导出 / 导入全组配置"
            setOnClickListener { showConfigDialog() }
        }

        val btnBattery = Button(this).apply {
            text = "🔋 关闭电池优化（防止后台被杀）"
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        }

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 32, 0, 0)
        }

        root.addView(title)
        root.addView(btnScan)
        root.addView(btnAddWeb)
        root.addView(btnCheckAlive)
        root.addView(btnShareConfig)
        root.addView(btnBattery)
        root.addView(listContainer)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        renderAccountList()
    }

    private fun renderAccountList() {
        listContainer.removeAllViews()
        val accounts = store.getAccounts()
        if (accounts.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "当前暂无账号，点击上方「网页登录录入同学账号」添加"
                setTextColor(0xFF9E9E9E.toInt())
            })
            return
        }

        accounts.forEachIndexed { index, acc ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 20, 16, 20)
                setBackgroundColor(0xFF1A1A1A.toInt())
            }
            val cb = CheckBox(this).apply {
                val statusTag = if (acc.isValid) "✅" else "❌已过期"
                text = "${acc.name}  [$statusTag]"
                isChecked = acc.enabled
                setTextColor(if (acc.isValid) 0xFFFFFFFF.toInt() else 0xFFFF5252.toInt())
                setOnCheckedChangeListener { _, isChecked ->
                    accounts[index].enabled = isChecked
                    store.saveAccounts(accounts)
                }
            }
            val delBtn = Button(this).apply {
                text = "删除"
                setOnClickListener {
                    accounts.removeAt(index)
                    store.saveAccounts(accounts)
                    renderAccountList()
                }
            }
            row.addView(cb, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(delBtn)
            listContainer.addView(row)
        }
    }

    private fun verifyAllTokens() {
        Toast.makeText(this, "正在检测全员登录状态...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val accounts = store.getAccounts()
            accounts.forEach { acc ->
                acc.isValid = KtpApiClient.checkTokenValid(acc.token)
            }
            store.saveAccounts(accounts)
            renderAccountList()
            val expired = accounts.filter { !it.isValid }.map { it.name }
            val msg = if (expired.isEmpty()) "全员 Token 均正常有效！" else "以下同学凭证已过期，请重新网页登录：${expired.joinToString()}"
            AlertDialog.Builder(this@MainActivity).setTitle("检测结果").setMessage(msg).setPositiveButton("确定", null).show()
        }
    }

    private fun showConfigDialog() {
        AlertDialog.Builder(this)
            .setTitle("团队配置流转")
            .setItems(arrayOf("复制全组配置到剪贴板（发给室友接管）", "从剪贴板导入配置", "一键清空所有本地凭证")) { _, which ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                when (which) {
                    0 -> {
                        cm.setPrimaryClip(ClipData.newPlainText("ktp_config", store.exportJson()))
                        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (store.importJson(text)) {
                            renderAccountList()
                            Toast.makeText(this, "导入成功", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "剪贴板内容格式无效", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> {
                        store.clearAll()
                        renderAccountList()
                        Toast.makeText(this, "已彻底清除所有本地数据", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "已处于后台白名单中", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startForegroundServiceCompat() {
        val intent = Intent(this, CheckInForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}