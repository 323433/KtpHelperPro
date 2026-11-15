package com.ktp.helper

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
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
    private lateinit var countBadge: TextView

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private val Float.dpF: Float
        get() = this * resources.displayMetrics.density

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 28.dp
    }

    private fun makeGlassSurface(
        radiusDp: Float,
        topAlpha: Int = 0x26,
        bottomAlpha: Int = 0x10,
        borderAlpha: Int = 0x45,
        tintRgb: Int = 0xFFFFFF
    ): LayerDrawable {
        val r = radiusDp.dpF
        val topColor = (topAlpha shl 24) or (tintRgb and 0x00FFFFFF)
        val bottomColor = (bottomAlpha shl 24) or (tintRgb and 0x00FFFFFF)
        val fill = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topColor, bottomColor)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
        }
        val stroke = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(Color.TRANSPARENT)
            setStroke(1.dp, (borderAlpha shl 24) or 0x00FFFFFF)
        }
        return LayerDrawable(arrayOf(fill, stroke))
    }

    private fun attachSpringTouch(view: View, onClick: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.965f).scaleY(0.965f).setDuration(110)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(DecelerateInterpolator()).start()
                    onClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }
            }
            true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = 0xFF070B14.toInt()
        }

        store = AccountStore(this)
        requestRuntimePermissions()
        startForegroundServiceCompat()

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(0xFF070B14.toInt())
        }

        val backdrop = View(this).apply {
            background = makeBackdropDrawable()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(85f, 85f, Shader.TileMode.CLAMP))
            }
        }
        rootFrame.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, getStatusBarHeight() + 16.dp, 20.dp, 36.dp)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "KETANGPAI · AUTO"
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF64D2FF.toInt())
        })
        titleCol.addView(TextView(this).apply {
            text = "课堂派助手"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 2.dp, 0, 0)
        })

        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeGlassSurface(20f, 0x28, 0x14, 0x45)
            setPadding(12.dp, 7.dp, 14.dp, 7.dp)
        }
        val greenDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF30D158.toInt())
            }
        }
        val statusLabel = TextView(this).apply {
            text = "守护中"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(6.dp, 0, 0, 0)
        }
        statusPill.addView(greenDot, LinearLayout.LayoutParams(7.dp, 7.dp))
        statusPill.addView(statusLabel)

        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(statusPill)
        content.addView(headerRow)

        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val blueGlass = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xCC0A84FF.toInt(), 0x990055D4.toInt())
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24.dpF
            }
            val rim = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24.dpF
                setColor(Color.TRANSPARENT)
                setStroke(1.dp, 0x66FFFFFF)
            }
            background = LayerDrawable(arrayOf(blueGlass, rim))
            setPadding(18.dp, 20.dp, 18.dp, 20.dp)
            attachSpringTouch(this) {
                startActivity(Intent(this@MainActivity, ScanActivity::class.java))
            }
        }

        val heroIconCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
        }
        heroIconCircle.addView(
            View(this).apply { background = makeSfIconDrawable(SfIconType.SCAN, Color.WHITE) },
            FrameLayout.LayoutParams(24.dp, 24.dp, Gravity.CENTER)
        )

        val heroTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 0, 8.dp, 0)
        }
        heroTextCol.addView(TextView(this).apply {
            text = "极速扫动态二维码"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        heroTextCol.addView(TextView(this).apply {
            text = "全员毫秒级并发 · 远距自动变焦"
            textSize = 12.5f
            setTextColor(0xE6E0F2FE.toInt())
            setPadding(0, 3.dp, 0, 0)
        })

        val chevronPill = TextView(this).apply {
            text = "›"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x28FFFFFF)
            }
        }

        heroCard.addView(heroIconCircle, LinearLayout.LayoutParams(48.dp, 48.dp))
        heroCard.addView(heroTextCol, LinearLayout.LayoutParams(0, -2, 1f))
        heroCard.addView(chevronPill, LinearLayout.LayoutParams(32.dp, 32.dp))
        content.addView(heroCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20.dp })

        fun buildGlassTile(
            iconType: SfIconType,
            badgeColor: Int,
            titleStr: String,
            subStr: String,
            onClick: () -> Unit
        ): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = makeGlassSurface(22f, 0x22, 0x0E, 0x3D)
                setPadding(16.dp, 16.dp, 16.dp, 16.dp)
                attachSpringTouch(this, onClick)

                val badge = FrameLayout(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(badgeColor)
                    }
                }
                badge.addView(
                    View(this@MainActivity).apply { background = makeSfIconDrawable(iconType, Color.WHITE) },
                    FrameLayout.LayoutParams(18.dp, 18.dp, Gravity.CENTER)
                )
                addView(badge, LinearLayout.LayoutParams(36.dp, 36.dp))

                addView(TextView(this@MainActivity).apply {
                    text = titleStr
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(0, 12.dp, 0, 2.dp)
                })
                addView(TextView(this@MainActivity).apply {
                    text = subStr
                    textSize = 11.5f
                    setTextColor(0x99EBEBF5.toInt())
                })
            }
        }

        val gridRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRow1.addView(
            buildGlassTile(SfIconType.PLUS, 0xFF30D158.toInt(), "录入同学", "网页登录抓包") {
                startActivity(Intent(this, WebLoginActivity::class.java))
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6.dp }
        )
        gridRow1.addView(
            buildGlassTile(SfIconType.PULSE, 0xFFFF375F.toInt(), "状态体检", "检测凭证存活") {
                verifyAllTokens()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 6.dp }
        )
        content.addView(gridRow1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })

        val gridRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRow2.addView(
            buildGlassTile(SfIconType.SHARE, 0xFF5E5CE6.toInt(), "配置流转", "导出或导入名单") {
                showConfigDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6.dp }
        )
        gridRow2.addView(
            buildGlassTile(SfIconType.BOLT, 0xFFFF9F0A.toInt(), "后台保活", "关闭电池优化") {
                requestIgnoreBatteryOptimizations()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 6.dp }
        )
        content.addView(gridRow2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })

        val sectionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sectionHeader.addView(TextView(this).apply {
            text = "代签团队成员"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        countBadge = TextView(this).apply {
            text = "0 人"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF64D2FF.toInt())
            background = makeGlassSurface(14f, 0x22, 0x10, 0x38)
            setPadding(11.dp, 5.dp, 11.dp, 5.dp)
        }
        sectionHeader.addView(countBadge)
        content.addView(sectionHeader, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = 24.dp
            bottomMargin = 12.dp
        })

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeGlassSurface(22f, 0x20, 0x0E, 0x3D)
        }
        content.addView(listContainer)

        scroll.addView(content)
        rootFrame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootFrame)
    }

    override fun onResume() {
        super.onResume()
        renderAccountList()
    }

    private fun renderAccountList() {
        listContainer.removeAllViews()
        val accounts = store.getAccounts()
        val enabledCount = accounts.count { it.enabled }
        countBadge.text = "已启用 $enabledCount / ${accounts.size}"

        if (accounts.isEmpty()) {
            val emptyBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(24.dp, 34.dp, 24.dp, 34.dp)
            }
            emptyBox.addView(TextView(this).apply {
                text = "暂无团队成员"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xE6FFFFFF.toInt())
                gravity = Gravity.CENTER
            })
            emptyBox.addView(TextView(this).apply {
                text = "点击上方「录入同学」登录课堂派即可自动加入"
                textSize = 12.5f
                setTextColor(0x80EBEBF5.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 6.dp, 0, 0)
            })
            listContainer.addView(emptyBox)
            return
        }

        val avatarGradients = arrayOf(
            intArrayOf(0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt()),
            intArrayOf(0xFF30D158.toInt(), 0xFF00C7BE.toInt()),
            intArrayOf(0xFFFF375F.toInt(), 0xFFFF9F0A.toInt()),
            intArrayOf(0xFFBF5AF2.toInt(), 0xFF5E5CE6.toInt())
        )

        accounts.forEachIndexed { index, acc ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp, 14.dp, 14.dp, 14.dp)
            }

            val gradColors = avatarGradients[index % avatarGradients.size]
            val avatar = TextView(this).apply {
                text = acc.name.firstOrNull()?.toString() ?: "同"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, gradColors).apply {
                    shape = GradientDrawable.OVAL
                }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dp, 0, 8.dp, 0)
            }
            infoCol.addView(TextView(this).apply {
                text = acc.name
                textSize = 15.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (acc.isValid) Color.WHITE else 0xFFFF6961.toInt())
            })
            infoCol.addView(TextView(this).apply {
                text = when {
                    !acc.isValid -> "凭证已过期 · 需重新登录"
                    acc.enabled -> "凭证有效 · 参与自动代签"
                    else -> "已暂停 · 本轮跳过"
                }
                textSize = 12f
                setTextColor(
                    when {
                        !acc.isValid -> 0xFFFF453A.toInt()
                        acc.enabled -> 0xFF30D158.toInt()
                        else -> 0x80EBEBF5.toInt()
                    }
                )
                setPadding(0, 2.dp, 0, 0)
            })

            val iosSwitch = IosToggleSwitch(this, acc.enabled) { checked ->
                accounts[index].enabled = checked
                store.saveAccounts(accounts)
                countBadge.text = "已启用 ${accounts.count { it.enabled }} / ${accounts.size}"
                val subView = infoCol.getChildAt(1) as TextView
                subView.text = when {
                    !acc.isValid -> "凭证已过期 · 需重新登录"
                    checked -> "凭证有效 · 参与自动代签"
                    else -> "已暂停 · 本轮跳过"
                }
                subView.setTextColor(
                    when {
                        !acc.isValid -> 0xFFFF453A.toInt()
                        checked -> 0xFF30D158.toInt()
                        else -> 0x80EBEBF5.toInt()
                    }
                )
            }

            val delBtn = TextView(this).apply {
                text = "×"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(0x99EBEBF5.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x1AFFFFFF)
                }
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("移除成员")
                        .setMessage("确定要移除「${acc.name}」的登录凭证吗？")
                        .setPositiveButton("移除") { _, _ ->
                            accounts.removeAt(index)
                            store.saveAccounts(accounts)
                            renderAccountList()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }

            row.addView(avatar, LinearLayout.LayoutParams(38.dp, 38.dp))
            row.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(iosSwitch, LinearLayout.LayoutParams(48.dp, 28.dp).apply { rightMargin = 10.dp })
            row.addView(delBtn, LinearLayout.LayoutParams(26.dp, 26.dp))

            listContainer.addView(row)

            if (index < accounts.size - 1) {
                val divider = View(this).apply {
                    setBackgroundColor(0x22FFFFFF)
                }
                listContainer.addView(divider, LinearLayout.LayoutParams(-1, 1).apply {
                    leftMargin = 66.dp
                })
            }
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
            AlertDialog.Builder(this@MainActivity).setTitle("状态体检结果").setMessage(msg).setPositiveButton("确定", null).show()
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

    private fun makeBackdropDrawable(): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return

            paint.shader = RadialGradient(
                w * 0.85f, h * 0.14f, w * 0.72f,
                intArrayOf(0x660A84FF, 0x1A0A84FF, Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(w * 0.85f, h * 0.14f, w * 0.72f, paint)

            paint.shader = RadialGradient(
                w * 0.12f, h * 0.38f, w * 0.65f,
                intArrayOf(0x555E5CE6, 0x155E5CE6, Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(w * 0.12f, h * 0.38f, w * 0.65f, paint)

            paint.shader = RadialGradient(
                w * 0.78f, h * 0.76f, w * 0.68f,
                intArrayOf(0x4000C7BE, 0x1000C7BE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(w * 0.78f, h * 0.76f, w * 0.68f, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun makeSfIconDrawable(type: SfIconType, strokeColor: Int): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            paint.strokeWidth = w * 0.105f
            val p = w * 0.16f
            val c = w * 0.24f

            when (type) {
                SfIconType.SCAN -> {
                    val path = Path().apply {
                        moveTo(p, p + c); lineTo(p, p); lineTo(p + c, p)
                        moveTo(w - p - c, p); lineTo(w - p, p); lineTo(w - p, p + c)
                        moveTo(p, h - p - c); lineTo(p, h - p); lineTo(p + c, h - p)
                        moveTo(w - p - c, h - p); lineTo(w - p, h - p); lineTo(w - p, h - p - c)
                        moveTo(p + w * 0.08f, h * 0.5f); lineTo(w - p - w * 0.08f, h * 0.5f)
                    }
                    canvas.drawPath(path, paint)
                }
                SfIconType.PLUS -> {
                    canvas.drawLine(w * 0.5f, p, w * 0.5f, h - p, paint)
                    canvas.drawLine(p, h * 0.5f, w - p, h * 0.5f, paint)
                }
                SfIconType.PULSE -> {
                    val path = Path().apply {
                        moveTo(p * 0.8f, h * 0.52f)
                        lineTo(w * 0.34f, h * 0.52f)
                        lineTo(w * 0.44f, h * 0.24f)
                        lineTo(w * 0.56f, h * 0.78f)
                        lineTo(w * 0.66f, h * 0.52f)
                        lineTo(w - p * 0.8f, h * 0.52f)
                    }
                    canvas.drawPath(path, paint)
                }
                SfIconType.SHARE -> {
                    val path = Path().apply {
                        moveTo(w * 0.5f, p)
                        lineTo(w * 0.5f, h * 0.62f)
                        moveTo(w * 0.33f, p + w * 0.17f)
                        lineTo(w * 0.5f, p)
                        lineTo(w * 0.67f, p + w * 0.17f)
                        moveTo(w * 0.32f, h * 0.42f)
                        lineTo(p + w * 0.04f, h * 0.42f)
                        lineTo(p + w * 0.04f, h - p)
                        lineTo(w - p - w * 0.04f, h - p)
                        lineTo(w - p - w * 0.04f, h * 0.42f)
                        lineTo(w * 0.68f, h * 0.42f)
                    }
                    canvas.drawPath(path, paint)
                }
                SfIconType.BOLT -> {
                    val path = Path().apply {
                        moveTo(w * 0.56f, p)
                        lineTo(w * 0.28f, h * 0.54f)
                        lineTo(w * 0.50f, h * 0.54f)
                        lineTo(w * 0.44f, h - p)
                        lineTo(w * 0.72f, h * 0.46f)
                        lineTo(w * 0.50f, h * 0.46f)
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

class IosToggleSwitch(
    context: Context,
    initialChecked: Boolean,
    private val onChanged: (Boolean) -> Unit
) : View(context) {
    private var checked = initialChecked
    private var progress = if (initialChecked) 1f else 0f
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init {
        background = object : Drawable() {
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                if (w <= 0f || h <= 0f) return
                val r = h / 2f

                val offColor = 0x33FFFFFF
                val onColor = 0xFF30D158.toInt()
                trackPaint.color = blendColor(offColor, onColor, progress)
                canvas.drawRoundRect(0f, 0f, w, h, r, r, trackPaint)

                val pad = h * 0.09f
                val knobR = r - pad
                val minX = r
                val maxX = w - r
                val cx = minX + (maxX - minX) * progress
                canvas.drawCircle(cx, r, knobR, knobPaint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

        setOnClickListener {
            checked = !checked
            ValueAnimator.ofFloat(progress, if (checked) 1f else 0f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    background?.invalidateSelf()
                    invalidate()
                }
                start()
            }
            onChanged(checked)
        }
    }

    private fun blendColor(c1: Int, c2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val a = (Color.alpha(c1) * inv + Color.alpha(c2) * ratio).toInt()
        val r = (Color.red(c1) * inv + Color.red(c2) * ratio).toInt()
        val g = (Color.green(c1) * inv + Color.green(c2) * ratio).toInt()
        val b = (Color.blue(c1) * inv + Color.blue(c2) * ratio).toInt()
        return Color.argb(a, r, g, b)
    }
}

enum class SfIconType { SCAN, PLUS, PULSE, SHARE, BOLT }