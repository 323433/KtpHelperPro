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
import android.text.InputFilter
import android.text.InputType
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var store: AccountStore
    private lateinit var metaStore: ProMetaStore
    private lateinit var listContainer: LinearLayout
    private lateinit var countBadge: TextView
    private lateinit var activeAccountsStat: TextView
    private lateinit var activeCoursesStat: TextView
    private lateinit var clipboardBanner: LinearLayout
    private lateinit var clipboardUrlPreview: TextView
    private var detectedClipboardUrl: String = ""
    private val expandedStates = mutableMapOf<String, Boolean>()
    private var isLight = false

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
        darkTopAlpha: Int = 0x26,
        darkBottomAlpha: Int = 0x10,
        darkBorderAlpha: Int = 0x45
    ): LayerDrawable {
        val r = radiusDp.dpF
        val topColor = if (isLight) 0xD9FFFFFF.toInt() else ((darkTopAlpha shl 24) or 0x00FFFFFF)
        val bottomColor = if (isLight) 0xA6FFFFFF.toInt() else ((darkBottomAlpha shl 24) or 0x00FFFFFF)
        val borderColor = if (isLight) 0xFFFFFFFF.toInt() else ((darkBorderAlpha shl 24) or 0x00FFFFFF)

        val fill = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topColor, bottomColor)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
        }
        val stroke = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(Color.TRANSPARENT)
            setStroke(1.dp, borderColor)
        }
        return LayerDrawable(arrayOf<Drawable>(fill, stroke))
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
        store = AccountStore(this)
        metaStore = ProMetaStore(this)
        isLight = metaStore.isLightTheme()

        KtpShortcutHelper.registerDynamicShortcuts(this)

        @Suppress("DEPRECATION")
        window.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            if (isLight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            decorView.systemUiVisibility = flags
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = if (isLight) 0xFFEEF2F9.toInt() else 0xFF070B14.toInt()
        }

        requestRuntimePermissions()
        // removed foreground service

        val textPrimary = if (isLight) 0xFF0F172A.toInt() else Color.WHITE
        val textSecondary = if (isLight) 0xFF475569.toInt() else 0x99EBEBF5.toInt()

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(if (isLight) 0xFFEEF2F9.toInt() else 0xFF070B14.toInt())
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
            setPadding(20.dp, getStatusBarHeight() + 14.dp, 20.dp, 40.dp)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "KETANGPAI · GLASS PRO"
            textSize = 10.5f
            letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isLight) 0xFF0284C7.toInt() else 0xFF64D2FF.toInt())
        })
        titleCol.addView(TextView(this).apply {
            text = "课堂派助手"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
            setPadding(0, 1.dp, 0, 0)
        })

        val syncAllBtn = FrameLayout(this).apply {
            background = makeGlassSurface(18f, 0x26, 0x12, 0x45)
            attachSpringTouch(this) { syncAllAccountsCourses() }
        }
        syncAllBtn.addView(
            View(this).apply { background = makeSfIconDrawable(SfIconType.SYNC, textPrimary) },
            FrameLayout.LayoutParams(17.dp, 17.dp, Gravity.CENTER)
        )

        val themeBtn = FrameLayout(this).apply {
            background = makeGlassSurface(18f, 0x26, 0x12, 0x45)
            attachSpringTouch(this) {
                metaStore.setLightTheme(!isLight)
                recreate()
            }
        }
        themeBtn.addView(
            View(this).apply { background = makeThemeToggleDrawable(textPrimary) },
            FrameLayout.LayoutParams(17.dp, 17.dp, Gravity.CENTER)
        )

        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeGlassSurface(18f, 0x26, 0x12, 0x45)
            setPadding(10.dp, 7.dp, 12.dp, 7.dp)
        }
        statusPill.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF30D158.toInt())
            }
        }, LinearLayout.LayoutParams(7.dp, 7.dp))
        statusPill.addView(TextView(this).apply {
            text = "守护中"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
            setPadding(5.dp, 0, 0, 0)
        })

        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(syncAllBtn, LinearLayout.LayoutParams(34.dp, 34.dp).apply { rightMargin = 8.dp })
        headerRow.addView(themeBtn, LinearLayout.LayoutParams(34.dp, 34.dp).apply { rightMargin = 8.dp })
        headerRow.addView(statusPill)
        content.addView(headerRow)

        clipboardBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeGlassSurface(18f, 0x35, 0x1A, 0x60)
            setPadding(14.dp, 11.dp, 12.dp, 11.dp)
            visibility = View.GONE
        }
        val clipTextCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clipTextCol.addView(TextView(this).apply {
            text = "检测到剪贴板课堂派签到链接"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isLight) 0xFF0369A1.toInt() else 0xFF64D2FF.toInt())
        })
        clipboardUrlPreview = TextView(this).apply {
            text = ""
            textSize = 11f
            maxLines = 1
            setTextColor(textSecondary)
        }
        clipTextCol.addView(clipboardUrlPreview)

        val clipGoBtn = TextView(this).apply {
            text = "立即全员代签"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f.dpF
                setColor(0xFF0A84FF.toInt())
            }
            setPadding(12.dp, 7.dp, 12.dp, 7.dp)
            setOnClickListener {
                if (detectedClipboardUrl.isNotEmpty()) {
                    executeUrlCheckIn(detectedClipboardUrl, "剪贴板链接")
                }
            }
        }
        clipboardBanner.addView(clipTextCol, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8.dp })
        clipboardBanner.addView(clipGoBtn)
        content.addView(clipboardBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })

        val statsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeGlassSurface(22f, 0x24, 0x10, 0x42)
            setPadding(18.dp, 16.dp, 18.dp, 14.dp)
        }
        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val col1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col1.addView(TextView(this).apply {
            text = "启用账号"
            textSize = 12f
            setTextColor(textSecondary)
        })
        activeAccountsStat = TextView(this).apply {
            text = "0"
            textSize = 28f
            fontFeatureSettings = "tnum"
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        }
        col1.addView(activeAccountsStat)

        val col2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col2.addView(TextView(this).apply {
            text = "生效课程"
            textSize = 12f
            setTextColor(textSecondary)
        })
        activeCoursesStat = TextView(this).apply {
            text = "0"
            textSize = 28f
            fontFeatureSettings = "tnum"
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF0A84FF.toInt())
        }
        col2.addView(activeCoursesStat)

        statsRow.addView(col1, LinearLayout.LayoutParams(0, -2, 1f))
        statsRow.addView(col2, LinearLayout.LayoutParams(0, -2, 1f))
        statsCard.addView(statsRow)

        statsCard.addView(TextView(this).apply {
            text = "🔒 HTTP/2 并发池 · 支持动态二维码续扫、免码口令提取与单课过滤"
            textSize = 11f
            setTextColor(textSecondary)
            setPadding(0, 6.dp, 0, 0)
        })
        content.addView(statsCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })

        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val blueGlass = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xD90A84FF.toInt(), 0xAA0055D4.toInt())
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f.dpF
            }
            val rim = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f.dpF
                setColor(Color.TRANSPARENT)
                setStroke(1.dp, 0x66FFFFFF)
            }
            background = LayerDrawable(arrayOf<Drawable>(blueGlass, rim))
            setPadding(18.dp, 18.dp, 18.dp, 16.dp)
        }

        val heroTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
            FrameLayout.LayoutParams(23.dp, 23.dp, Gravity.CENTER)
        )

        val heroTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 0, 8.dp, 0)
        }
        heroTextCol.addView(TextView(this).apply {
            text = "极速扫动态二维码"
            textSize = 17.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        heroTextCol.addView(TextView(this).apply {
            text = "白名单防误触 · 点击对焦 · 刷新自动续扫"
            textSize = 12f
            setTextColor(0xE6E0F2FE.toInt())
            setPadding(0, 2.dp, 0, 0)
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
        heroTopRow.addView(heroIconCircle, LinearLayout.LayoutParams(46.dp, 46.dp))
        heroTopRow.addView(heroTextCol, LinearLayout.LayoutParams(0, -2, 1f))
        heroTopRow.addView(chevronPill, LinearLayout.LayoutParams(30.dp, 30.dp))
        heroCard.addView(heroTopRow)

        fun buildHeroPill(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f.dpF
                    setColor(0x2EFFFFFF)
                }
                setPadding(0, 9.dp, 0, 9.dp)
                setOnClickListener { onClick() }
            }
        }

        val heroActionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        heroActionRow.addView(
            buildHeroPill("🔢 数字口令") { promptNumericCodeDialog() },
            LinearLayout.LayoutParams(0, -2, 1.15f).apply { rightMargin = 5.dp }
        )
        heroActionRow.addView(
            buildHeroPill("📋 粘贴链接") { promptPasteLinkDialog() },
            LinearLayout.LayoutParams(0, -2, 1.15f).apply { leftMargin = 3.dp; rightMargin = 3.dp }
        )
        heroActionRow.addView(
            buildHeroPill("📊 战报") { showCheckInRecordsDialog() },
            LinearLayout.LayoutParams(0, -2, 0.85f).apply { leftMargin = 5.dp }
        )
        heroCard.addView(heroActionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })

        content.addView(heroCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })

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
                setPadding(15.dp, 14.dp, 15.dp, 14.dp)
                attachSpringTouch(this, onClick)

                val badge = FrameLayout(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(badgeColor)
                    }
                }
                badge.addView(
                    View(this@MainActivity).apply { background = makeSfIconDrawable(iconType, Color.WHITE) },
                    FrameLayout.LayoutParams(17.dp, 17.dp, Gravity.CENTER)
                )
                addView(badge, LinearLayout.LayoutParams(34.dp, 34.dp))

                addView(TextView(this@MainActivity).apply {
                    text = titleStr
                    textSize = 14.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                    setPadding(0, 10.dp, 0, 2.dp)
                })
                addView(TextView(this@MainActivity).apply {
                    text = subStr
                    textSize = 11.5f
                    setTextColor(textSecondary)
                })
            }
        }

        val gridRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRow1.addView(
            buildGlassTile(SfIconType.PLUS, 0xFF30D158.toInt(), "添加账号", "登录并同步课表") {
                startActivity(Intent(this, WebLoginActivity::class.java))
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6.dp }
        )
        gridRow1.addView(
            buildGlassTile(SfIconType.PULSE, 0xFFFF375F.toInt(), "状态体检", "检测全员凭证存活") {
                verifyAllTokens()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 6.dp }
        )
        content.addView(gridRow1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })

        val gridRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRow2.addView(
            buildGlassTile(SfIconType.PIN, 0xFF5E5CE6.toInt(), "地点与流转", "GPS预设与名单导出") {
                showToolsDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6.dp }
        )
        gridRow2.addView(
            buildGlassTile(SfIconType.BOLT, 0xFFFF9F0A.toInt(), "后台保活", "关闭系统电池优化") {
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
            text = "本机账号池（点击展开课程）"
            textSize = 16.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        countBadge = TextView(this).apply {
            text = "0 人"
            textSize = 12f
            fontFeatureSettings = "tnum"
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isLight) 0xFF0284C7.toInt() else 0xFF64D2FF.toInt())
            background = makeGlassSurface(14f, 0x22, 0x10, 0x38)
            setPadding(10.dp, 4.dp, 10.dp, 4.dp)
        }
        sectionHeader.addView(countBadge)
        content.addView(sectionHeader, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = 22.dp
            bottomMargin = 10.dp
        })

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(listContainer)

        scroll.addView(content)
        rootFrame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootFrame)

        if (intent?.getBooleanExtra("open_numeric_dialog", false) == true) {
            promptNumericCodeDialog()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_numeric_dialog", false)) {
            promptNumericCodeDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        checkClipboardForKtpUrl()
        renderAccountList()
    }

    private fun promptNumericCodeDialog() {
        val box = FrameLayout(this).apply { setPadding(28.dp, 14.dp, 28.dp, 4.dp) }
        val input = EditText(this).apply {
            hint = "输入 4 位数字口令 (可留空)"
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 22f
            letterSpacing = 0.2f
            setTypeface(null, Typeface.BOLD)
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle("数字口令全员代签")
            .setMessage("可直接点「免码自动提取」嗅探接口明文口令，或手动输入 4 位口令提交：")
            .setView(box)
            .setPositiveButton("提交输入口令") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) {
                    executeNumericCheckIn(code)
                } else {
                    executeNumericCheckIn("AUTO")
                }
            }
            .setNeutralButton("⚡ 免码自动提取") { _, _ ->
                executeNumericCheckIn("AUTO")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeNumericCheckIn(code: String, overrideAccounts: List<Account>? = null) {
        val targetList = overrideAccounts ?: store.getAccounts()
        if (targetList.none { it.enabled }) {
            Toast.makeText(this, "请先在账号池启用至少一位同学", Toast.LENGTH_SHORT).show()
            return
        }

        if (overrideAccounts == null && !CheckInCooldownGuard.tryAcquire("num_$code")) {
            Toast.makeText(this, "请求已提交，请勿在 3 秒内重复连点", Toast.LENGTH_SHORT).show()
            return
        }

        val tip = if (code == "AUTO") "正在自动嗅探接口明文口令并并发提交..." else "正在检索考勤并提交口令 $code ..."
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            val result = KtpNumericCheckInEngine.batchNumericCheckIn(
                context = this@MainActivity,
                accounts = targetList,
                code = code
            ) { _, _ -> }
            val cost = System.currentTimeMillis() - startMs

            val summary = "成功 ${result.okCount} 人 · 耗时 ${cost}ms"
            val detailParts = mutableListOf<String>()
            if (result.failed.isNotEmpty()) detailParts.add("未成功: ${result.failed.joinToString()}")
            if (result.skipped.isNotEmpty()) detailParts.add("已跳过: ${result.skipped.joinToString()}")
            val detail = if (detailParts.isEmpty()) "参与全员均已完成数字签到" else detailParts.joinToString(" | ")

            val modeTag = if (code == "AUTO") "数字口令(免码提取)" else "数字口令($code)"
            metaStore.addRecord(modeTag, summary, detail)

            val failedAccounts = targetList.filter { acc ->
                result.failed.any { it.startsWith(acc.name) }
            }

            val builder = AlertDialog.Builder(this@MainActivity)
                .setTitle("数字口令签到战报")
                .setMessage("$summary\n$detail")
                .setPositiveButton("确定", null)

            if (failedAccounts.isNotEmpty()) {
                builder.setNeutralButton("🔄 仅对失败的 ${failedAccounts.size} 人补签") { _, _ ->
                    executeNumericCheckIn(code, failedAccounts)
                }
            }
            builder.show()
        }
    }

    private fun checkClipboardForKtpUrl() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            if (clip.contains("ketangpai.com") && (clip.contains("http://") || clip.contains("https://"))) {
                detectedClipboardUrl = clip
                clipboardUrlPreview.text = clip
                clipboardBanner.visibility = View.VISIBLE
            } else {
                clipboardBanner.visibility = View.GONE
            }
        } catch (_: Exception) {
            clipboardBanner.visibility = View.GONE
        }
    }

    private fun updateDashboardStats(accounts: List<Account>) {
        val enabledAccounts = accounts.filter { it.enabled }
        activeAccountsStat.text = enabledAccounts.size.toString()
        var totalCourses = 0
        enabledAccounts.forEach { acc ->
            val meta = metaStore.getStudentMeta(acc.name)
            totalCourses += meta.courses.count { it.enabled }
        }
        activeCoursesStat.text = totalCourses.toString()
        countBadge.text = "${enabledAccounts.size} / ${accounts.size} 人"
    }

    private fun renderAccountList() {
        listContainer.removeAllViews()
        val accounts = store.getAccounts()
        updateDashboardStats(accounts)

        val textPrimary = if (isLight) 0xFF0F172A.toInt() else Color.WHITE
        val textSecondary = if (isLight) 0xFF475569.toInt() else 0x8CEBEBF5.toInt()

        if (accounts.isEmpty()) {
            val emptyBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = makeGlassSurface(22f, 0x20, 0x0E, 0x3D)
                setPadding(24.dp, 32.dp, 24.dp, 32.dp)
            }
            emptyBox.addView(TextView(this).apply {
                text = "本机账号池为空"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
                gravity = Gravity.CENTER
            })
            emptyBox.addView(TextView(this).apply {
                text = "点击上方「添加账号」登录课堂派，将自动拉取本学期课程"
                textSize = 12.5f
                setTextColor(textSecondary)
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
            val meta = metaStore.getStudentMeta(acc.name)
            val isExpanded = expandedStates[acc.name] ?: false

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = makeGlassSurface(22f, 0x22, 0x0E, 0x40)
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    expandedStates[acc.name] = !isExpanded
                    renderAccountList()
                }
            }

            val gradColors = avatarGradients[index % avatarGradients.size]
            val avatar = TextView(this).apply {
                text = acc.name.firstOrNull()?.toString() ?: "同"
                textSize = 15.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, gradColors).apply {
                    shape = GradientDrawable.OVAL
                }
            }

            val enabledCourseCount = meta.courses.count { it.enabled }
            val subSummary = when {
                !acc.isValid -> "凭证已过期 · 点击展开刷新"
                meta.courses.isNotEmpty() -> "${meta.courses.size} 门课程 · 已启用 $enabledCourseCount  ${if (isExpanded) "▲" else "▼"}"
                acc.enabled -> "凭证有效 · 点击同步课程  ${if (isExpanded) "▲" else "▼"}"
                else -> "已暂停 · 本轮跳过  ${if (isExpanded) "▲" else "▼"}"
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dp, 0, 8.dp, 0)
            }
            infoCol.addView(TextView(this).apply {
                text = acc.name
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (acc.isValid) textPrimary else 0xFFFF453A.toInt())
            })
            infoCol.addView(TextView(this).apply {
                text = subSummary
                textSize = 12f
                setTextColor(
                    when {
                        !acc.isValid -> 0xFFFF453A.toInt()
                        acc.enabled -> if (isLight) 0xFF059669.toInt() else 0xFF30D158.toInt()
                        else -> textSecondary
                    }
                )
                setPadding(0, 2.dp, 0, 0)
            })

            val iosSwitch = IosToggleSwitch(this, acc.enabled, isLight) { checked ->
                accounts[index].enabled = checked
                store.saveAccounts(accounts)
                renderAccountList()
            }

            topRow.addView(avatar, LinearLayout.LayoutParams(40.dp, 40.dp))
            topRow.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))
            topRow.addView(iosSwitch, LinearLayout.LayoutParams(48.dp, 28.dp))
            card.addView(topRow)

            if (isExpanded) {
                val divider = View(this).apply {
                    setBackgroundColor(if (isLight) 0x1A0F172A else 0x22FFFFFF)
                }
                card.addView(divider, LinearLayout.LayoutParams(-1, 1).apply {
                    topMargin = 12.dp
                    bottomMargin = 10.dp
                })

                val semHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                semHeader.addView(TextView(this).apply {
                    text = "${meta.semester} · 活跃课程已自动置顶"
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textSecondary)
                }, LinearLayout.LayoutParams(0, -2, 1f))

                val syncOneBtn = TextView(this).apply {
                    text = "🔄 同步课表"
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(0xFF0A84FF.toInt())
                    setPadding(8.dp, 4.dp, 4.dp, 4.dp)
                    setOnClickListener {
                        lifecycleScope.launch {
                            Toast.makeText(this@MainActivity, "正在同步 ${acc.name} 的课表...", Toast.LENGTH_SHORT).show()
                            val (sem, courses) = KtpCourseSyncer.syncCourses(acc.token)
                            if (courses.isNotEmpty()) {
                                val oldMap = meta.courses.associateBy { it.courseId }
                                courses.forEach { c ->
                                    oldMap[c.courseId]?.let { old ->
                                        c.enabled = old.enabled
                                        c.lastActiveTs = old.lastActiveTs
                                    }
                                }
                                metaStore.saveStudentMeta(acc.name, StudentCourseMeta(sem, courses))
                                renderAccountList()
                                Toast.makeText(this@MainActivity, "${acc.name} 已同步 ${courses.size} 门课程", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "未拉取到新课程，请检查登录状态", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                semHeader.addView(syncOneBtn)
                card.addView(semHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })

                if (meta.courses.isEmpty()) {
                    card.addView(TextView(this).apply {
                        text = "暂未同步课程列表，点击右上角「同步课表」获取"
                        textSize = 12f
                        setTextColor(textSecondary)
                        setPadding(4.dp, 6.dp, 4.dp, 8.dp)
                    })
                } else {
                    meta.courses.forEachIndexed { cIdx, course ->
                        val cRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(4.dp, 7.dp, 4.dp, 7.dp)
                            setOnClickListener {
                                meta.courses[cIdx].enabled = !course.enabled
                                meta.courses[cIdx].lastActiveTs = System.currentTimeMillis()
                                metaStore.saveStudentMeta(acc.name, meta)
                                renderAccountList()
                            }
                        }
                        val checkBox = TextView(this).apply {
                            text = if (course.enabled) "✓" else ""
                            textSize = 12f
                            setTypeface(null, Typeface.BOLD)
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = 6f.dpF
                                setColor(if (course.enabled) 0xFF30D158.toInt() else (if (isLight) 0x220F172A else 0x26FFFFFF))
                            }
                        }
                        val cName = TextView(this).apply {
                            text = course.courseName
                            textSize = 13.5f
                            setTextColor(if (course.enabled) textPrimary else textSecondary)
                            setPadding(10.dp, 0, 0, 0)
                        }
                        cRow.addView(checkBox, LinearLayout.LayoutParams(20.dp, 20.dp))
                        cRow.addView(cName, LinearLayout.LayoutParams(0, -2, 1f))
                        card.addView(cRow)
                    }
                }

                val footerActions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                val refreshCredBtn = TextView(this).apply {
                    text = "更新凭证"
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(0xFF0A84FF.toInt())
                    setPadding(12.dp, 8.dp, 12.dp, 4.dp)
                    setOnClickListener {
                        startActivity(Intent(this@MainActivity, WebLoginActivity::class.java).apply {
                            putExtra("preset_name", acc.name)
                        })
                    }
                }
                val removeBtn = TextView(this).apply {
                    text = "移除"
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(0xFFFF453A.toInt())
                    setPadding(12.dp, 8.dp, 4.dp, 4.dp)
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("移除成员")
                            .setMessage("确定要移除「${acc.name}」的登录凭证与课程设置吗？")
                            .setPositiveButton("移除") { _, _ ->
                                accounts.removeAt(index)
                                store.saveAccounts(accounts)
                                metaStore.removeStudentMeta(acc.name)
                                renderAccountList()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
                footerActions.addView(refreshCredBtn)
                footerActions.addView(removeBtn)
                card.addView(footerActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })
            }

            listContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
        }
    }

    private fun syncAllAccountsCourses() {
        val accounts = store.getAccounts()
        if (accounts.isEmpty()) {
            Toast.makeText(this, "请先添加同学账号", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在同步全员本学期课表...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var totalSynced = 0
            accounts.forEach { acc ->
                val (sem, courses) = KtpCourseSyncer.syncCourses(acc.token)
                if (courses.isNotEmpty()) {
                    val oldMeta = metaStore.getStudentMeta(acc.name)
                    val oldMap = oldMeta.courses.associateBy { it.courseId }
                    courses.forEach { c ->
                        oldMap[c.courseId]?.let { old ->
                            c.enabled = old.enabled
                            c.lastActiveTs = old.lastActiveTs
                        }
                    }
                    metaStore.saveStudentMeta(acc.name, StudentCourseMeta(sem, courses))
                    totalSynced += courses.size
                }
            }
            renderAccountList()
            Toast.makeText(this@MainActivity, "同步完成，共更新 $totalSynced 门课程", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptPasteLinkDialog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        val box = FrameLayout(this).apply { setPadding(22.dp, 12.dp, 22.dp, 4.dp) }
        val input = EditText(this).apply {
            hint = "粘贴课堂派签到链接（https://...）"
            setText(if (clipText.contains("http")) clipText else "")
        }
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle("粘贴链接全员代签")
            .setView(box)
            .setPositiveButton("立即全员提交") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    executeUrlCheckIn(url, "手动粘贴链接")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeUrlCheckIn(rawUrl: String, modeLabel: String, overrideAccounts: List<Account>? = null) {
        if (overrideAccounts == null && !CheckInCooldownGuard.tryAcquire("url_$rawUrl")) {
            Toast.makeText(this, "请求已提交，请勿在 3 秒内重复连点", Toast.LENGTH_SHORT).show()
            return
        }

        val baseAccounts = overrideAccounts ?: store.getAccounts()
        val (targetAccounts, skippedList) = KtpCourseSyncer.filterAccountsByCourse(this, baseAccounts, rawUrl)
        if (targetAccounts.isEmpty()) {
            Toast.makeText(this, "当前没有符合该课程勾选条件的启用账号", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "正在为 ${targetAccounts.size} 位同学并发提交...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            delay(Random.nextLong(150L, 480L))
            val (okCount, failed) = KtpApiClient.batchCheckIn(
                accounts = targetAccounts,
                qrRawUrl = rawUrl
            ) { _, _ -> }
            val cost = System.currentTimeMillis() - startMs
            val summary = "成功 $okCount 人 · 耗时 ${cost}ms"
            val detailParts = mutableListOf<String>()
            if (failed.isNotEmpty()) detailParts.add("失败: ${failed.joinToString()}")
            if (skippedList.isNotEmpty()) detailParts.add("跳过: ${skippedList.joinToString()}")
            val detail = if (detailParts.isEmpty()) "参与全员均已成功签收" else detailParts.joinToString(" | ")

            metaStore.addRecord(modeLabel, summary, detail)

            val failedAccounts = targetAccounts.filter { acc ->
                failed.any { it.startsWith(acc.name) }
            }

            val builder = AlertDialog.Builder(this@MainActivity)
                .setTitle("考勤战报（$modeLabel）")
                .setMessage("$summary\n$detail")
                .setPositiveButton("确定", null)

            if (failedAccounts.isNotEmpty()) {
                builder.setNeutralButton("🔄 仅对失败的 ${failedAccounts.size} 人补签") { _, _ ->
                    executeUrlCheckIn(rawUrl, "$modeLabel·补签", failedAccounts)
                }
            }
            builder.show()
        }
    }

    private fun showCheckInRecordsDialog() {
        val records = metaStore.getRecords()
        if (records.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("本机签到战报")
                .setMessage("本机还没有签到记录，扫码、输入口令或粘贴链接代签后将自动生成战报。")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        val text = records.joinToString("\n\n") { r ->
            "【${r.time}】${r.mode}\n${r.summary}\n${r.detail}"
        }
        AlertDialog.Builder(this)
            .setTitle("最近考勤战报（近 ${records.size} 次）")
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空记录") { _, _ ->
                metaStore.clearRecords()
                Toast.makeText(this, "已清空本地战报", Toast.LENGTH_SHORT).show()
            }
            .show()
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
            val msg = if (expired.isEmpty()) "全员 Token 均正常有效！" else "以下同学凭证已过期，请展开卡片点「更新凭证」：${expired.joinToString()}"
            AlertDialog.Builder(this@MainActivity).setTitle("状态体检结果").setMessage(msg).setPositiveButton("确定", null).show()
        }
    }

    private fun showToolsDialog() {
        val currentGps = metaStore.getGpsPreset()
        val gpsLabel = if (currentGps != null) "GPS 坐标预设（当前：${currentGps.label}）" else "设置常用教学楼 GPS 坐标"
        AlertDialog.Builder(this)
            .setTitle("地点预设与配置流转")
            .setItems(arrayOf(gpsLabel, "复制全组配置到剪贴板（发给室友接管）", "从剪贴板导入配置", "一键清空所有本地凭证")) { _, which ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                when (which) {
                    0 -> promptGpsPresetDialog()
                    1 -> {
                        cm.setPrimaryClip(ClipData.newPlainText("ktp_config", store.exportJson()))
                        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (store.importJson(text)) {
                            renderAccountList()
                            Toast.makeText(this, "导入成功", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "剪贴板内容格式无效", Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> {
                        store.clearAll()
                        renderAccountList()
                        Toast.makeText(this, "已彻底清除所有本地数据", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun promptGpsPresetDialog() {
        val old = metaStore.getGpsPreset()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 12.dp, 22.dp, 4.dp)
        }
        val nameInput = EditText(this).apply {
            hint = "地点备注（如：教学楼A栋）"
            setText(old?.label ?: "")
        }
        val latInput = EditText(this).apply {
            hint = "纬度 Latitude（如：26.058321）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(old?.lat?.toString() ?: "")
        }
        val lngInput = EditText(this).apply {
            hint = "经度 Longitude（如：119.197452）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(old?.lng?.toString() ?: "")
        }
        layout.addView(nameInput)
        layout.addView(latInput)
        layout.addView(lngInput)

        AlertDialog.Builder(this)
            .setTitle("常用教学楼 GPS 坐标预设")
            .setMessage("位置与口令签到时将自动叠加 ±8m 随机偏移防雷同：")
            .setView(layout)
            .setPositiveButton("保存预设") { _, _ ->
                val label = nameInput.text.toString().trim().ifEmpty { "常用教室" }
                val lat = latInput.text.toString().trim().toDoubleOrNull()
                val lng = lngInput.text.toString().trim().toDoubleOrNull()
                if (lat != null && lng != null) {
                    metaStore.saveGpsPreset(label, lat, lng)
                    Toast.makeText(this, "已保存地点：$label", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "请输入有效的经纬度数字", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
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

    private fun // removed foreground service {
        val intent = Intent(this, CheckInForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun makeBackdropDrawable(): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return

            val c1 = if (isLight) intArrayOf(0x4438BDF8, 0x1238BDF8, Color.TRANSPARENT) else intArrayOf(0x660A84FF, 0x1A0A84FF, Color.TRANSPARENT)
            paint.shader = RadialGradient(w * 0.85f, h * 0.14f, w * 0.72f, c1, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(w * 0.85f, h * 0.14f, w * 0.72f, paint)

            val c2 = if (isLight) intArrayOf(0x38818CF8, 0x10818CF8, Color.TRANSPARENT) else intArrayOf(0x555E5CE6, 0x155E5CE6, Color.TRANSPARENT)
            paint.shader = RadialGradient(w * 0.12f, h * 0.38f, w * 0.65f, c2, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(w * 0.12f, h * 0.38f, w * 0.65f, paint)

            val c3 = if (isLight) intArrayOf(0x382DD4BF, 0x0E2DD4BF, Color.TRANSPARENT) else intArrayOf(0x4000C7BE, 0x1000C7BE, Color.TRANSPARENT)
            paint.shader = RadialGradient(w * 0.78f, h * 0.76f, w * 0.68f, c3, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(w * 0.78f, h * 0.76f, w * 0.68f, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun makeThemeToggleDrawable(tint: Int): Drawable = object : Drawable() {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * resources.displayMetrics.density
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.FILL
        }
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val r = w * 0.42f
            val cx = w / 2f
            val cy = h / 2f
            canvas.drawCircle(cx, cy, r, stroke)
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, -90f, 180f, true, fill)
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
                SfIconType.PIN -> {
                    val cx = w * 0.5f
                    val cy = h * 0.40f
                    canvas.drawCircle(cx, cy, w * 0.14f, paint)
                    val path = Path().apply {
                        moveTo(cx, h - p)
                        cubicTo(w * 0.20f, h * 0.56f, w * 0.22f, p, cx, p)
                        cubicTo(w * 0.78f, p, w * 0.80f, h * 0.56f, cx, h - p)
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
                SfIconType.SYNC -> {
                    val rect = RectF(p, p, w - p, h - p)
                    canvas.drawArc(rect, -60f, 260f, false, paint)
                    canvas.drawLine(w - p - w * 0.04f, p + h * 0.04f, w - p - w * 0.04f, p + h * 0.22f, paint)
                    canvas.drawLine(w - p - w * 0.04f, p + h * 0.22f, w - p - w * 0.22f, p + h * 0.22f, paint)
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
    private val isLight: Boolean,
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

                val offColor = if (isLight) 0x260F172A else 0x33FFFFFF
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

enum class SfIconType { SCAN, PLUS, PULSE, PIN, BOLT, SYNC }