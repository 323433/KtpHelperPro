package com.ktp.helper

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

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
    private var pendingGpsInputs: Pair<EditText, EditText>? = null
    private var auroraAnimator: ValueAnimator? = null
    private var sheenAnimator: ValueAnimator? = null
    private var radarAnimator: ValueAnimator? = null
    private var hasAnimatedEntrance = false
    private var lastRenderedAccountCount = 0
    private var lastRenderedCourseCount = 0

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            pendingGpsInputs?.let { (latEt, lngEt) -> fillCurrentLocation(latEt, lngEt) }
        } else {
            GlassIslandBanner.show(this, "未授予位置权限，可手动输入经纬度", GlassIslandBanner.Tone.WARN, isLight)
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private val Float.dpF: Float
        get() = this * resources.displayMetrics.density

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 28.dp
    }

    private fun makeBevelGlassSurface(
        radiusDp: Float,
        darkTopAlpha: Int = 0x28,
        darkBottomAlpha: Int = 0x10,
        topRimAlpha: Int = 0x58
    ): LayerDrawable {
        val r = radiusDp.dpF
        val topColor = if (isLight) 0xE6FFFFFF.toInt() else ((darkTopAlpha shl 24) or 0x00FFFFFF)
        val bottomColor = if (isLight) 0xB3FFFFFF.toInt() else ((darkBottomAlpha shl 24) or 0x00FFFFFF)
        val rimStart = if (isLight) 0xFFFFFFFF.toInt() else ((topRimAlpha shl 24) or 0x00FFFFFF)
        val rimEnd = if (isLight) 0x1F0F172A else 0x12FFFFFF

        val fill = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topColor, bottomColor)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
        }
        val bevelRim = object : Drawable() {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f.dpF
            }
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                if (w <= 0f || h <= 0f) return
                p.shader = LinearGradient(0f, 0f, 0f, h, rimStart, rimEnd, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(0.5f.dpF, 0.5f.dpF, w - 0.5f.dpF, h - 0.5f.dpF), r, r, p)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        return LayerDrawable(arrayOf<Drawable>(fill, bevelRim))
    }

    private fun makeGlassInputBg(): LayerDrawable {
        val r = 14f.dpF
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(if (isLight) 0xF2FFFFFF.toInt() else 0x22FFFFFF)
        }
        val stroke = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(Color.TRANSPARENT)
            setStroke(1.dp, if (isLight) 0x250F172A else 0x3DFFFFFF)
        }
        return LayerDrawable(arrayOf<Drawable>(fill, stroke))
    }

    private fun makePrimaryButtonBg(startColor: Int = 0xFF0A84FF.toInt(), endColor: Int = 0xFF0055D4.toInt()): LayerDrawable {
        val r = 16f.dpF
        val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(startColor, endColor)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
        }
        val rim = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(Color.TRANSPARENT)
            setStroke(1.dp, 0x66FFFFFF)
        }
        return LayerDrawable(arrayOf<Drawable>(fill, rim))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSpringTouch(view: View, scaleDown: Float = 0.965f, onClick: (View) -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(scaleDown).scaleY(scaleDown).setDuration(105)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(165)
                        .setInterpolator(DecelerateInterpolator()).start()
                    onClick(v)
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(165).start()
                }
            }
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun launchSmoothActivity(intent: Intent) {
        startActivity(intent)
        overridePendingTransition(R.anim.page_enter, R.anim.page_exit)
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

        val textPrimary = if (isLight) 0xFF0F172A.toInt() else Color.WHITE
        val textSecondary = if (isLight) 0xFF475569.toInt() else 0x99EBEBF5.toInt()

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(if (isLight) 0xFFEEF2F9.toInt() else 0xFF070B14.toInt())
        }

        var auroraPhase = 0f
        var scrollParallaxY = 0f
        val backdrop = View(this).apply {
            background = object : Drawable() {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun draw(canvas: Canvas) {
                    val w = bounds.width().toFloat()
                    val h = bounds.height().toFloat()
                    if (w <= 0f || h <= 0f) return
                    val shiftX = (auroraPhase - 0.5f) * w * 0.14f
                    val shiftY = (auroraPhase - 0.5f) * h * 0.08f - scrollParallaxY

                    val c1 = if (isLight) intArrayOf(0x4A38BDF8, 0x1438BDF8, Color.TRANSPARENT) else intArrayOf(0x6E0A84FF, 0x1E0A84FF, Color.TRANSPARENT)
                    paint.shader = RadialGradient(w * 0.85f - shiftX, h * 0.14f + shiftY, w * 0.74f, c1, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawCircle(w * 0.85f - shiftX, h * 0.14f + shiftY, w * 0.74f, paint)

                    val c2 = if (isLight) intArrayOf(0x3E818CF8, 0x12818CF8, Color.TRANSPARENT) else intArrayOf(0x5C5E5CE6, 0x185E5CE6, Color.TRANSPARENT)
                    paint.shader = RadialGradient(w * 0.12f + shiftX, h * 0.38f - shiftY * 0.6f, w * 0.66f, c2, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawCircle(w * 0.12f + shiftX, h * 0.38f - shiftY * 0.6f, w * 0.66f, paint)

                    val c3 = if (isLight) intArrayOf(0x3C2DD4BF, 0x102DD4BF, Color.TRANSPARENT) else intArrayOf(0x4800C7BE, 0x1200C7BE, Color.TRANSPARENT)
                    paint.shader = RadialGradient(w * 0.78f + shiftX * 0.6f, h * 0.76f + shiftY * 0.4f, w * 0.70f, c3, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawCircle(w * 0.78f + shiftX * 0.6f, h * 0.76f + shiftY * 0.4f, w * 0.70f, paint)
                }
                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(colorFilter: ColorFilter?) {}
                @Deprecated("Deprecated in Java")
                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(88f, 88f, Shader.TileMode.CLAMP))
            }
        }
        auroraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 8500L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                auroraPhase = anim.animatedValue as Float
                backdrop.background?.invalidateSelf()
            }
            start()
        }
        rootFrame.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, getStatusBarHeight() + 14.dp, 20.dp, 42.dp)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        scroll.viewTreeObserver.addOnScrollChangedListener {
            val sy = scroll.scrollY.toFloat()
            scrollParallaxY = (sy * 0.22f).coerceAtMost(140f.dpF)
            backdrop.background?.invalidateSelf()
            val ratio = (sy / 110f.dpF).coerceIn(0f, 1f)
            headerRow.alpha = 1f - ratio * 0.18f
            headerRow.translationY = -sy * 0.12f
        }

        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "KETANGPAI · GLASS PRO"
            textSize = 10.5f
            letterSpacing = 0.09f
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
            background = makeBevelGlassSurface(18f, 0x28, 0x12, 0x52)
            attachSpringTouch(this, 0.90f) { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                syncAllAccountsCourses()
            }
        }
        syncAllBtn.addView(
            View(this).apply { background = makeSfIconDrawable(SfIconType.SYNC, textPrimary) },
            FrameLayout.LayoutParams(17.dp, 17.dp, Gravity.CENTER)
        )

        val themeBtn = FrameLayout(this).apply {
            background = makeBevelGlassSurface(18f, 0x28, 0x12, 0x52)
            attachSpringTouch(this, 0.90f) { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                metaStore.setLightTheme(!isLight)
                recreate()
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
        themeBtn.addView(
            View(this).apply { background = makeThemeToggleDrawable(textPrimary) },
            FrameLayout.LayoutParams(17.dp, 17.dp, Gravity.CENTER)
        )

        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeBevelGlassSurface(18f, 0x28, 0x12, 0x52)
            setPadding(10.dp, 7.dp, 12.dp, 7.dp)
        }
        statusPill.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF30D158.toInt())
            }
        }, LinearLayout.LayoutParams(7.dp, 7.dp))
        statusPill.addView(TextView(this).apply {
            text = "轻量版"
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
            background = makeBevelGlassSurface(18f, 0x38, 0x1A, 0x66)
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
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(textSecondary)
        }
        clipTextCol.addView(clipboardUrlPreview)

        val clipGoBtn = TextView(this).apply {
            text = "立即代签"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = makePrimaryButtonBg()
            setPadding(13.dp, 7.dp, 13.dp, 7.dp)
            attachSpringTouch(this) {
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
            background = makeBevelGlassSurface(22f, 0x26, 0x10, 0x52)
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
            text = "🔒 零后台零耗电 · HTTP/2 并发池 · 长按课程胶囊可开启单课专注"
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(textSecondary)
            setPadding(0, 6.dp, 0, 0)
        })
        content.addView(statsCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })

        var sheenProgress = -0.5f
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val r = 24f.dpF
            val blueGlass = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xE00A84FF.toInt(), 0xB80055D4.toInt())
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
            }
            val sheenLayer = object : Drawable() {
                private val p = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun draw(canvas: Canvas) {
                    val w = bounds.width().toFloat()
                    val h = bounds.height().toFloat()
                    if (w <= 0f || h <= 0f || sheenProgress < 0f || sheenProgress > 1f) return
                    val cx = w * (-0.3f + sheenProgress * 1.6f)
                    p.shader = LinearGradient(
                        cx - w * 0.25f, 0f, cx + w * 0.25f, h,
                        intArrayOf(Color.TRANSPARENT, 0x28FFFFFF, Color.TRANSPARENT),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
                }
                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(colorFilter: ColorFilter?) {}
                @Deprecated("Deprecated in Java")
                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }
            val rim = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(Color.TRANSPARENT)
                setStroke(1.dp, 0x77FFFFFF)
            }
            background = LayerDrawable(arrayOf<Drawable>(blueGlass, sheenLayer, rim))
            setPadding(18.dp, 18.dp, 18.dp, 16.dp)
        }
        sheenAnimator = ValueAnimator.ofFloat(-1.8f, 1.0f).apply {
            duration = 4800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                sheenProgress = anim.animatedValue as Float
                heroCard.background?.invalidateSelf()
            }
            start()
        }

        val heroTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            attachSpringTouch(this) { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                launchSmoothActivity(Intent(this@MainActivity, ScanActivity::class.java))
            }
        }

        // 带脉冲雷达波纹的扫码图标容器
        val heroIconContainer = FrameLayout(this)
        val radarPulseRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(1.5f.dpF.toInt(), 0x88FFFFFF.toInt())
            }
        }
        val heroIconCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x38FFFFFF)
            }
        }
        heroIconCircle.addView(
            View(this).apply { background = makeSfIconDrawable(SfIconType.SCAN, Color.WHITE) },
            FrameLayout.LayoutParams(22.dp, 22.dp, Gravity.CENTER)
        )
        heroIconContainer.addView(radarPulseRing, FrameLayout.LayoutParams(46.dp, 46.dp, Gravity.CENTER))
        heroIconContainer.addView(heroIconCircle, FrameLayout.LayoutParams(44.dp, 44.dp, Gravity.CENTER))

        radarAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val s = 0.94f + f * 0.24f
                radarPulseRing.scaleX = s
                radarPulseRing.scaleY = s
                radarPulseRing.alpha = (1f - f) * 0.75f
            }
            start()
        }

        val heroTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 0, 8.dp, 0)
        }
        heroTextCol.addView(TextView(this).apply {
            text = "极速扫动态二维码"
            textSize = 17.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        heroTextCol.addView(TextView(this).apply {
            text = "现场扫码 · 相册识码 · 刷新自动续扫"
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
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
                setColor(0x2EFFFFFF)
            }
        }
        heroTopRow.addView(heroIconContainer, LinearLayout.LayoutParams(50.dp, 50.dp))
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
                val r = 14f.dpF
                val fill = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(0x2EFFFFFF) }
                val stroke = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(Color.TRANSPARENT); setStroke(1.dp, 0x38FFFFFF) }
                background = LayerDrawable(arrayOf<Drawable>(fill, stroke))
                setPadding(0, 9.dp, 0, 9.dp)
                attachSpringTouch(this, 0.94f) { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }
            }
        }

        val heroActionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        heroActionRow.addView(
            buildHeroPill("🔢 数字口令") { showNumericKeypadSheet() },
            LinearLayout.LayoutParams(0, -2, 1.15f).apply { rightMargin = 5.dp }
        )
        heroActionRow.addView(
            buildHeroPill("📋 粘贴链接") { showPasteLinkSheet() },
            LinearLayout.LayoutParams(0, -2, 1.15f).apply { leftMargin = 3.dp; rightMargin = 3.dp }
        )
        heroActionRow.addView(
            buildHeroPill("📊 战报") { showBattleReportSheet() },
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
                background = makeBevelGlassSurface(22f, 0x24, 0x0E, 0x4A)
                setPadding(15.dp, 14.dp, 15.dp, 14.dp)
                attachSpringTouch(this) { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }

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
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(textSecondary)
                })
            }
        }

        val gridRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRow1.addView(
            buildGlassTile(SfIconType.PLUS, 0xFF30D158.toInt(), "添加账号", "登录并同步课表") {
                launchSmoothActivity(Intent(this, WebLoginActivity::class.java))
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
            buildGlassTile(SfIconType.PIN, 0xFF5E5CE6.toInt(), "地点与流转", "GPS预设与配置同步") {
                showToolsSheet()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6.dp }
        )
        gridRow2.addView(
            buildGlassTile(SfIconType.BOLT, 0xFFFF9F0A.toInt(), "免码抢签", "一键嗅探进行中签到") {
                executeNumericCheckIn("AUTO")
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
            background = makeBevelGlassSurface(14f, 0x24, 0x10, 0x45)
            setPadding(10.dp, 4.dp, 10.dp, 4.dp)
        }
        sectionHeader.addView(countBadge)
        content.addView(sectionHeader, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = 22.dp
            bottomMargin = 10.dp
        })

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = LayoutTransition().apply {
                setDuration(240L)
                enableTransitionType(LayoutTransition.CHANGING)
            }
        }
        content.addView(listContainer)

        scroll.addView(content)
        rootFrame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootFrame)

        if (intent?.getBooleanExtra("open_numeric_dialog", false) == true) {
            window.decorView.post { showNumericKeypadSheet() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_numeric_dialog", false)) {
            showNumericKeypadSheet()
        }
    }

    override fun onResume() {
        super.onResume()
        checkClipboardForKtpUrl()
        renderAccountList(animateEntrance = !hasAnimatedEntrance)
        hasAnimatedEntrance = true

        // 接收 ScanActivity 传递过来的战报，自动弹出毛玻璃战报抽屉
        val pendingReport = metaStore.consumePendingScanReport()
        if (pendingReport != null) {
            val allAccounts = store.getAccounts()
            val failedAccounts = allAccounts.filter { acc ->
                pendingReport.failedNames.any { fn -> fn == acc.name }
            }
            window.decorView.postDelayed({
                showResultReportSheet(
                    title = pendingReport.title,
                    summary = pendingReport.summary,
                    detail = pendingReport.detail,
                    failedAccounts = failedAccounts
                ) {
                    if (pendingReport.qrRawUrl.isNotEmpty() && failedAccounts.isNotEmpty()) {
                        executeUrlCheckIn(pendingReport.qrRawUrl, "扫码·补签", failedAccounts)
                    }
                }
            }, 180L)
        }
    }

    override fun onDestroy() {
        auroraAnimator?.cancel()
        sheenAnimator?.cancel()
        radarAnimator?.cancel()
        super.onDestroy()
    }

    private fun animateStatNumber(view: TextView, fromVal: Int, toVal: Int) {
        if (fromVal == toVal) {
            view.text = toVal.toString()
            return
        }
        ValueAnimator.ofInt(fromVal, toVal).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                view.text = (anim.animatedValue as Int).toString()
            }
            start()
        }
    }

    private fun showNumericKeypadSheet() {
        val sheet = GlassBottomSheet(
            context = this,
            isLight = isLight,
            title = "数字口令代签",
            subtitle = "输满 4 位自动并发提交 · 或直接免码嗅探",
            badgeColor = 0xFF0A84FF.toInt(),
            badgeGlyph = "🔢"
        )
        val body = sheet.getBody()
        val textPrimary = if (isLight) 0xFF0F172A.toInt() else Color.WHITE
        val textSecondary = if (isLight) 0xFF475569.toInt() else 0x99EBEBF5.toInt()

        val autoHero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makePrimaryButtonBg(0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt())
            setPadding(16.dp, 13.dp, 16.dp, 13.dp)
            attachSpringTouch(this) {
                sheet.dismiss { executeNumericCheckIn("AUTO") }
            }
        }
        val autoCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        autoCol.addView(TextView(this).apply {
            text = "⚡ 免码自动嗅探抢签"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        autoCol.addView(TextView(this).apply {
            text = "自动检索进行中考勤并尝试提取明文口令"
            textSize = 11.5f
            setTextColor(0xE6E0F2FE.toInt())
            setPadding(0, 2.dp, 0, 0)
        })
        autoHero.addView(autoCol, LinearLayout.LayoutParams(0, -2, 1f))
        autoHero.addView(TextView(this).apply {
            text = "›"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        body.addView(autoHero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16.dp })

        var codeBuffer = ""
        var isLockedSubmitting = false
        val pinBoxes = Array(4) {
            TextView(this).apply {
                text = ""
                textSize = 24f
                fontFeatureSettings = "tnum"
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(textPrimary)
                background = makeGlassInputBg()
            }
        }

        fun refreshPinBoxes(confirmed: Boolean = false) {
            for (i in 0 until 4) {
                val ch = codeBuffer.getOrNull(i)?.toString() ?: ""
                pinBoxes[i].text = ch
                val isFocused = (!confirmed && i == codeBuffer.length)
                val fill = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f.dpF
                    setColor(
                        when {
                            confirmed -> 0x3330D158
                            isLight -> 0xF2FFFFFF.toInt()
                            else -> 0x24FFFFFF
                        }
                    )
                }
                val stroke = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f.dpF
                    setColor(Color.TRANSPARENT)
                    setStroke(
                        if (confirmed || isFocused) 2.dp else 1.dp,
                        when {
                            confirmed -> 0xFF30D158.toInt()
                            isFocused -> 0xFF0A84FF.toInt()
                            isLight -> 0x220F172A
                            else -> 0x38FFFFFF
                        }
                    )
                }
                pinBoxes[i].background = LayerDrawable(arrayOf<Drawable>(fill, stroke))
            }
        }

        val pinRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (i in 0 until 4) {
            pinRow.addView(pinBoxes[i], LinearLayout.LayoutParams(58.dp, 62.dp).apply {
                if (i < 3) rightMargin = 12.dp
            })
        }
        refreshPinBoxes()
        body.addView(pinRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16.dp })

        val keys = arrayOf(
            arrayOf("1", "2", "3"),
            arrayOf("4", "5", "6"),
            arrayOf("7", "8", "9"),
            arrayOf("清空", "0", "⌫")
        )

        for (r in keys.indices) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 3) {
                val label = keys[r][c]
                val isActionKey = (label == "清空" || label == "⌫")
                val keyBtn = TextView(this).apply {
                    text = label
                    textSize = if (isActionKey) 14f else 21f
                    fontFeatureSettings = "tnum"
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(if (isActionKey) textSecondary else textPrimary)
                    background = makeBevelGlassSurface(16f, 0x28, 0x12, 0x45)
                }
                attachSpringTouch(keyBtn, 0.93f) { v ->
                    if (isLockedSubmitting) return@attachSpringTouch
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    when (label) {
                        "清空" -> {
                            codeBuffer = ""
                            refreshPinBoxes()
                        }
                        "⌫" -> {
                            if (codeBuffer.isNotEmpty()) {
                                codeBuffer = codeBuffer.dropLast(1)
                                refreshPinBoxes()
                            }
                        }
                        else -> {
                            if (codeBuffer.length < 4) {
                                codeBuffer += label
                                if (codeBuffer.length == 4) {
                                    isLockedSubmitting = true
                                    refreshPinBoxes(confirmed = true)
                                    val finalCode = codeBuffer
                                    v.postDelayed({
                                        sheet.dismiss { executeNumericCheckIn(finalCode) }
                                    }, 220L)
                                } else {
                                    refreshPinBoxes()
                                }
                            }
                        }
                    }
                }
                rowLayout.addView(keyBtn, LinearLayout.LayoutParams(0, 50.dp, 1f).apply {
                    if (c < 2) rightMargin = 10.dp
                })
            }
            body.addView(rowLayout, LinearLayout.LayoutParams(-1, -2).apply {
                if (r < 3) bottomMargin = 10.dp
            })
        }

        sheet.show()
    }

    private fun showToolsSheet() {
        val currentGps = metaStore.getGpsPreset()
        val gpsSub = if (currentGps != null) {
            "${currentGps.label} (${String.format(Locale.US, "%.4f, %.4f", currentGps.lat, currentGps.lng)})"
        } else {
            "未设置 · 点击采集当前教室定位或手动输入"
        }

        val sheet = GlassBottomSheet(
            context = this,
            isLight = isLight,
            title = "地点预设与配置流转",
            subtitle = "定位防雷同偏移 · 寝室全组配置一键互通",
            badgeColor = 0xFF5E5CE6.toInt(),
            badgeGlyph = "📍"
        )

        sheet.addRowCard(
            context = this,
            glyph = "📡",
            glyphBg = 0xFF0A84FF.toInt(),
            title = "教学楼 GPS 坐标预设",
            sub = gpsSub
        ) {
            sheet.dismiss { showGpsPresetSheet() }
        }

        sheet.addRowCard(
            context = this,
            glyph = "📤",
            glyphBg = 0xFF30D158.toInt(),
            title = "复制全组配置到剪贴板",
            sub = "导出当前全员加密凭证，发到寝室群给室友接管"
        ) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ktp_config", store.exportJson()))
            sheet.dismiss {
                GlassIslandBanner.show(this, "全组加密配置已复制到剪贴板", GlassIslandBanner.Tone.SUCCESS, isLight)
            }
        }

        sheet.addRowCard(
            context = this,
            glyph = "📥",
            glyphBg = 0xFFFF9F0A.toInt(),
            title = "从剪贴板导入室友配置",
            sub = "自动识别剪贴板配置串并同步到本地账号池"
        ) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            sheet.dismiss {
                if (store.importJson(text)) {
                    renderAccountList(animateEntrance = true)
                    GlassIslandBanner.show(this, "配置导入成功，账号池已同步", GlassIslandBanner.Tone.SUCCESS, isLight)
                } else {
                    GlassIslandBanner.show(this, "剪贴板中没有有效的配置数据", GlassIslandBanner.Tone.WARN, isLight)
                }
            }
        }

        sheet.addRowCard(
            context = this,
            glyph = "🗑️",
            glyphBg = 0xFFFF453A.toInt(),
            title = "清空本机所有本地凭证",
            sub = "彻底移除本机保存的所有成员 Token 与课表记录",
            danger = true
        ) {
            sheet.dismiss {
                showConfirmSheet(
                    title = "确认清空所有凭证？",
                    message = "清空后需要重新登录或从室友处重新导入配置，此操作不可撤销。",
                    confirmText = "确认清空",
                    danger = true
                ) {
                    store.clearAll()
                    renderAccountList()
                    GlassIslandBanner.show(this, "已彻底清除所有本地数据", GlassIslandBanner.Tone.ERROR, isLight)
                }
            }
        }

        sheet.show()
    }

    private fun showGpsPresetSheet() {
        val old = metaStore.getGpsPreset()
        val sheet = GlassBottomSheet(
            context = this,
            isLight = isLight,
            title = "教学楼 GPS 坐标预设",
            subtitle = "提交签到时自动叠加 ±8m 随机偏移防雷同",
            badgeColor = 0xFF0A84FF.toInt(),
            badgeGlyph = "📡"
        )
        val body = sheet.getBody()
        val textPrimary = if (isLight) 0xFF0F172A.toInt() else Color.WHITE
        val hintColor = if (isLight) 0xFF94A3B8.toInt() else 0x66FFFFFF

        val nameInput = EditText(this).apply {
            hint = "地点备注（如：教学楼A栋302）"
            setText(old?.label ?: "")
            setTextColor(textPrimary)
            setHintTextColor(hintColor)
            textSize = 14.5f
            background = makeGlassInputBg()
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        }
        val latInput = EditText(this).apply {
            hint = "纬度 Latitude（如：26.058321）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(old?.lat?.toString() ?: "")
            setTextColor(textPrimary)
            setHintTextColor(hintColor)
            textSize = 14.5f
            background = makeGlassInputBg()
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        }
        val lngInput = EditText(this).apply {
            hint = "经度 Longitude（如：119.197452）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(old?.lng?.toString() ?: "")
            setTextColor(textPrimary)
            setHintTextColor(hintColor)
            textSize = 14.5f
            background = makeGlassInputBg()
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        }

        val locateBtn = TextView(this).apply {
            text = "📍 一键读取当前教室 GPS 坐标"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (isLight) 0xFF0284C7.toInt() else 0xFF64D2FF.toInt())
            background = makeBevelGlassSurface(14f, 0x2A, 0x14, 0x55)
            setPadding(0, 11.dp, 0, 11.dp)
            attachSpringTouch(this) {
                pendingGpsInputs = Pair(latInput, lngInput)
                val fineOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarseOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fineOk || coarseOk) {
                    fillCurrentLocation(latInput, lngInput)
                } else {
                    locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
        }

        body.addView(locateBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp })
        body.addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
        body.addView(latInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
        body.addView(lngInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16.dp })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (old != null) {
            val clearBtn = TextView(this).apply {
                text = "清除预设"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(0xFFFF453A.toInt())
                background = makeBevelGlassSurface(16f, 0x20, 0x10, 0x35)
                setPadding(0, 13.dp, 0, 13.dp)
                attachSpringTouch(this) {
                    metaStore.clearGpsPreset()
                    sheet.dismiss {
                        GlassIslandBanner.show(this@MainActivity, "已清除定位预设", GlassIslandBanner.Tone.INFO, isLight)
                    }
                }
            }
            btnRow.addView(clearBtn, LinearLayout.LayoutParams(0, -2, 0.9f).apply { rightMargin = 10.dp })
        }

        val saveBtn = TextView(this).apply {
            text = "保存坐标预设"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = makePrimaryButtonBg()
            setPadding(0, 13.dp, 0, 13.dp)
            attachSpringTouch(this) {
                val label = nameInput.text.toString().trim().ifEmpty { "常用教室" }
                val lat = latInput.text.toString().trim().toDoubleOrNull()
                val lng = lngInput.text.toString().trim().toDoubleOrNull()
                if (lat != null && lng != null) {
                    metaStore.saveGpsPreset(label, lat, lng)
                    sheet.dismiss {
                        GlassIslandBanner.show(this@MainActivity, "已保存定位：$label", GlassIslandBanner.Tone.SUCCESS, isLight)
                    }
                } else {
                    GlassIslandBanner.show(this@MainActivity, "请输入有效的经纬度数字", GlassIslandBanner.Tone.WARN, isLight)
                }
            }
        }
        btnRow.addView(saveBtn, LinearLayout.LayoutParams(0, -2, 1.4f))
        body.addView(btnRow)

        sheet.show()
    }

    @SuppressLint("MissingPermission")
    private fun fillCurrentLocation(latEt: EditText, lngEt: EditText) {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    LocationManager.NETWORK_PROVIDER
                } else {
                    LocationManager.GPS_PROVIDER
                }
                GlassIslandBanner.show(this, "正在主动获取室内教室坐标...", GlassIslandBanner.Tone.INFO, isLight, 1500L)
                lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                    val target = loc
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    if (target != null) {
                        latEt.setText(String.format(Locale.US, "%.6f", target.latitude))
                        lngEt.setText(String.format(Locale.US, "%.6f", target.longitude))
                        GlassIslandBanner.show(this, "已捕获当前教室经纬度", GlassIslandBanner.Tone.SUCCESS, isLight)
                    } else {
                        GlassIslandBanner.show(this, "暂未获取到定位，请开启系统定位开关", GlassIslandBanner.Tone.WARN, isLight)
                    }
                }
                return
            }
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (loc != null) {
                latEt.setText(String.format(Locale.US, "%.6f", loc.latitude))
                lngEt.setText(String.format(Locale.US, "%.6f", loc.longitude))
                GlassIslandBanner.show(this, "已捕获当前教室经纬度", GlassIslandBanner.Tone.SUCCESS, isLight)
            } else {
                GlassIslandBanner.show(this, "暂未获取到定位，请开启系统 GPS 开关", GlassIslandBanner.Tone.WARN, isLight)
            }
        } catch (_: Exception) {
            GlassIslandBanner.show(this, "定位读取失败，请手动填写", GlassIslandBanner.Tone.WARN, isLight)
        }
    }

    private fun showPasteLinkSheet() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()

        val sheet = GlassBottomSheet(
            context = this,
            isLight = isLight,
            title = "粘贴链接全员代签",
            subtitle = "支持课堂派考勤二维码解析后的直链",
            badgeColor = 0xFF30D158.toInt(),
            badgeGlyph = "🔗"
        )
        val body = sheet.getBody()
        val textPrimary = if (isLight) 0xFF0F172A.toInt() else Color.WHITE
        val hintColor = if (isLight) 0xFF94A3B8.toInt() else 0x66FFFFFF

        val input = EditText(this).apply {
            hint = "https://w.ketangpai.com/..."
            setText(if (clipText.contains("http")) clipText else "")
            setTextColor(textPrimary)
            setHintTextColor(hintColor)
            textSize = 13.5f
            minLines = 2
            maxLines = 4
            background = makeGlassInputBg()
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        }
        body.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14.dp })

        val submitBtn = TextView(this).apply {
            text = "🚀 立即全员并发提交"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = makePrimaryButtonBg()
            setPadding(0, 14.dp, 0, 14.dp)
            attachSpringTouch(this) {
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    sheet.dismiss { executeUrlCheckIn(url, "手动粘贴链接") }
                } else {
                    GlassIslandBanner.show(this@MainActivity, "请先粘贴签到链接", GlassIslandBanner.Tone.WARN, isLight)
                }
            }
        }
        body.addView(submitBtn)
        sheet.show()
    }

    private fun showResultReportSheet(
        title: String,
        summary: String,
        detail: String,
        failedAccounts: List<Account>,
        onRetryFailed: () -> Unit
    ) {
        val hasFailed = failedAccounts.isNotEmpty()
        val sheet = GlassBottomSheet(
            context = this,
            isLight = isLight,
            title = title,
            subtitle = summary,
            badgeColor = if (hasFailed) 0xFFFF9F0A.toInt() else 0xFF30D158.toInt(),
            badgeGlyph = if (hasFailed) "!" else "✓"
        )
        val body = sheet.getBody()
        val textPrimary = if (isLight) 0xFF0F172A.toInt() else Color.WHITE

        val detailCard = TextView(this).apply {
            text = detail
            textSize = 13.5f
            setLineSpacing(4f, 1.15f)
            setTextColor(textPrimary)
            background = makeBevelGlassSurface(18f, 0x22, 0x10, 0x40)
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
        }
        body.addView(detailCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16.dp })

        if (hasFailed) {
            val retryBtn = TextView(this).apply {
                text = "🔄 仅对失败的 ${failedAccounts.size} 人极速补签"
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = makePrimaryButtonBg(0xFFFF9F0A.toInt(), 0xFFFF375F.toInt())
                setPadding(0, 14.dp, 0, 14.dp)
                attachSpringTouch(this) {
                    sheet.dismiss { onRetryFailed() }
                }
            }
            body.addView(retryBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
        }

        val okBtn = TextView(this).apply {
            text = "完成"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (hasFailed) textPrimary else Color.WHITE)
            background = if (hasFailed) makeBevelGlassSurface(16f, 0x22, 0x10, 0x38) else makePrimaryButtonBg()
            setPadding(0, 13.dp, 0, 13.dp)
            attachSpringTouch(this) { sheet.dismiss() }
        }
        body.addView(okBtn)
        sheet.show()
    }

    private fun showBattleReportSheet() {
        val records = metaStore.getRecords()
        val sheet = GlassBottomSheet(
            context = this,
            isLight = isLight,
            title = "考勤战报记录",
            subtitle = if (records.isEmpty()) "暂无签到记录" else "最近 ${records.size} 次并发代签明细",
            badgeColor = 0xFF0A84FF.toInt(),
            badgeGlyph = "📊"
        )
        val body = sheet.getBody()
        val textPrimary = if (isLight) 0xFF0F172A.toInt() else Color.WHITE
        val textSecondary = if (isLight) 0xFF475569.toInt() else 0x8CEBEBF5.toInt()

        if (records.isEmpty()) {
            body.addView(TextView(this).apply {
                text = "扫码、输入数字口令或粘贴链接代签后，将在此自动生成带耗时与名单的战报。"
                textSize = 13.5f
                setTextColor(textSecondary)
                gravity = Gravity.CENTER
                setPadding(16.dp, 24.dp, 16.dp, 24.dp)
            })
        } else {
            records.forEach { r ->
                val itemCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = makeBevelGlassSurface(18f, 0x22, 0x0E, 0x3E)
                    setPadding(14.dp, 12.dp, 14.dp, 12.dp)
                }
                val top = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(TextView(this).apply {
                    text = r.mode
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (isLight) 0xFF0284C7.toInt() else 0xFF64D2FF.toInt())
                }, LinearLayout.LayoutParams(0, -2, 1f))
                top.addView(TextView(this).apply {
                    text = r.time
                    textSize = 11f
                    fontFeatureSettings = "tnum"
                    setTextColor(textSecondary)
                })
                itemCard.addView(top)

                itemCard.addView(TextView(this).apply {
                    text = r.summary
                    textSize = 14.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                    setPadding(0, 4.dp, 0, 2.dp)
                })
                itemCard.addView(TextView(this).apply {
                    text = r.detail
                    textSize = 12f
                    setTextColor(textSecondary)
                })
                body.addView(itemCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
            }

            val clearBtn = TextView(this).apply {
                text = "清空历史战报"
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(0xFFFF453A.toInt())
                background = makeBevelGlassSurface(16f, 0x1E, 0x0E, 0x35)
                setPadding(0, 12.dp, 0, 12.dp)
                attachSpringTouch(this) {
                    metaStore.clearRecords()
                    sheet.dismiss {
                        GlassIslandBanner.show(this@MainActivity, "已清空本地战报", GlassIslandBanner.Tone.INFO, isLight)
                    }
                }
            }
            body.addView(clearBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp })
        }

        sheet.show()
    }

    private fun showConfirmSheet(
        title: String,
        message: String,
        confirmText: String,
        danger: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val sheet = GlassBottomSheet(
            context = this,
            isLight = isLight,
            title = title,
            badgeColor = if (danger) 0xFFFF453A.toInt() else 0xFF0A84FF.toInt(),
            badgeGlyph = if (danger) "!" else "?"
        )
        val body = sheet.getBody()
        val textSecondary = if (isLight) 0xFF475569.toInt() else 0xB3EBEBF5.toInt()

        body.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setLineSpacing(4f, 1.15f)
            setTextColor(textSecondary)
            setPadding(4.dp, 0, 4.dp, 18.dp)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cancelBtn = TextView(this).apply {
            text = "取消"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (isLight) 0xFF0F172A.toInt() else Color.WHITE)
            background = makeBevelGlassSurface(16f, 0x22, 0x10, 0x38)
            setPadding(0, 13.dp, 0, 13.dp)
            attachSpringTouch(this) { sheet.dismiss() }
        }
        val okBtn = TextView(this).apply {
            text = confirmText
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = if (danger) makePrimaryButtonBg(0xFFFF453A.toInt(), 0xFFD70015.toInt()) else makePrimaryButtonBg()
            setPadding(0, 13.dp, 0, 13.dp)
            attachSpringTouch(this) {
                sheet.dismiss { onConfirm() }
            }
        }
        row.addView(cancelBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 10.dp })
        row.addView(okBtn, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(row)
        sheet.show()
    }

    private fun executeNumericCheckIn(code: String, overrideAccounts: List<Account>? = null) {
        val targetList = overrideAccounts ?: store.getAccounts()
        if (targetList.none { it.enabled }) {
            GlassIslandBanner.show(this, "请先在账号池启用至少一位同学", GlassIslandBanner.Tone.WARN, isLight)
            return
        }

        if (overrideAccounts == null && !CheckInCooldownGuard.tryAcquire("num_$code")) {
            GlassIslandBanner.show(this, "请求已提交，请勿在 3 秒内重复连点", GlassIslandBanner.Tone.WARN, isLight)
            return
        }

        val tip = if (code == "AUTO") "正在自动嗅探明文口令并并发提交..." else "正在检索考勤并提交口令 $code ..."
        GlassIslandBanner.show(this, tip, GlassIslandBanner.Tone.INFO, isLight, 1800L)

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
            if (result.failed.isNotEmpty()) detailParts.add("未成功: ${result.failed.joinToString(separator = ", ")}")
            if (result.skipped.isNotEmpty()) detailParts.add("已跳过: ${result.skipped.joinToString(separator = ", ")}")
            val detail = if (detailParts.isEmpty()) "参与全员均已完成数字签到" else detailParts.joinToString(separator = " | ")

            val modeTag = if (code == "AUTO") "数字口令(免码提取)" else "数字口令($code)"
            metaStore.addRecord(modeTag, summary, detail)

            val failedAccounts = targetList.filter { acc ->
                result.failed.any { msg -> msg.startsWith(acc.name) }
            }

            showResultReportSheet(
                title = "数字口令签到战报",
                summary = summary,
                detail = detail,
                failedAccounts = failedAccounts
            ) {
                executeNumericCheckIn(code, failedAccounts)
            }
        }
    }

    private fun executeUrlCheckIn(rawUrl: String, modeLabel: String, overrideAccounts: List<Account>? = null) {
        if (overrideAccounts == null && !CheckInCooldownGuard.tryAcquire("url_$rawUrl")) {
            GlassIslandBanner.show(this, "请求已提交，请勿在 3 秒内重复连点", GlassIslandBanner.Tone.WARN, isLight)
            return
        }

        val baseAccounts = overrideAccounts ?: store.getAccounts()
        val (targetAccounts, skippedList) = KtpCourseSyncer.filterAccountsByCourse(this, baseAccounts, rawUrl)
        if (targetAccounts.isEmpty()) {
            GlassIslandBanner.show(this, "当前没有符合该课程勾选条件的启用账号", GlassIslandBanner.Tone.WARN, isLight)
            return
        }

        GlassIslandBanner.show(this, "正在为 ${targetAccounts.size} 位同学并发提交...", GlassIslandBanner.Tone.INFO, isLight, 1800L)
        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            val (okCount, failed) = KtpQrBatchEngine.batchQrCheckIn(
                context = this@MainActivity,
                accounts = targetAccounts,
                qrRawUrl = rawUrl
            ) { _, _ -> }
            val cost = System.currentTimeMillis() - startMs
            val summary = "成功 $okCount 人 · 耗时 ${cost}ms"
            val detailParts = mutableListOf<String>()
            if (failed.isNotEmpty()) detailParts.add("失败: ${failed.joinToString(separator = ", ")}")
            if (skippedList.isNotEmpty()) detailParts.add("跳过: ${skippedList.joinToString(separator = ", ")}")
            val detail = if (detailParts.isEmpty()) "参与全员均已成功签收" else detailParts.joinToString(separator = " | ")

            metaStore.addRecord(modeLabel, summary, detail)

            val failedAccounts = targetAccounts.filter { acc ->
                failed.any { msg -> msg.startsWith(acc.name) }
            }

            showResultReportSheet(
                title = "考勤战报（$modeLabel）",
                summary = summary,
                detail = detail,
                failedAccounts = failedAccounts
            ) {
                executeUrlCheckIn(rawUrl, "$modeLabel·补签", failedAccounts)
            }
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
        val newAccCount = enabledAccounts.size
        var totalCourses = 0
        enabledAccounts.forEach { acc ->
            val meta = metaStore.getStudentMeta(acc.name)
            totalCourses += meta.courses.count { it.enabled }
        }
        animateStatNumber(activeAccountsStat, lastRenderedAccountCount, newAccCount)
        animateStatNumber(activeCoursesStat, lastRenderedCourseCount, totalCourses)
        lastRenderedAccountCount = newAccCount
        lastRenderedCourseCount = totalCourses
        countBadge.text = "$newAccCount / ${accounts.size} 人"
    }

    private fun makeCourseChipBg(enabled: Boolean): LayerDrawable {
        val r = 13f.dpF
        val fillCol = when {
            enabled && isLight -> 0x2430D158
            enabled && !isLight -> 0x2630D158
            isLight -> 0x120F172A
            else -> 0x12FFFFFF
        }
        val strokeCol = when {
            enabled -> 0x7730D158
            isLight -> 0x1A0F172A
            else -> 0x24FFFFFF
        }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(fillCol)
        }
        val stroke = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(Color.TRANSPARENT)
            setStroke(1.dp, strokeCol)
        }
        return LayerDrawable(arrayOf<Drawable>(fill, stroke))
    }

    private fun renderAccountList(animateEntrance: Boolean = false) {
        listContainer.removeAllViews()
        val accounts = store.getAccounts().toMutableList()
        updateDashboardStats(accounts)

        val textPrimary = if (isLight) 0xFF0F172A.toInt() else Color.WHITE
        val textSecondary = if (isLight) 0xFF475569.toInt() else 0x8CEBEBF5.toInt()

        if (accounts.isEmpty()) {
            val emptyBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = makeBevelGlassSurface(22f, 0x22, 0x0E, 0x45)
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
                background = makeBevelGlassSurface(22f, 0x24, 0x0E, 0x4E)
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    expandedStates[acc.name] = !isExpanded
                    renderAccountList(animateEntrance = false)
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
                renderAccountList(animateEntrance = false)
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
                    text = "${meta.semester} · 长按可单选"
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textSecondary)
                }, LinearLayout.LayoutParams(0, -2, 1f))

                if (meta.courses.isNotEmpty()) {
                    val allChecked = meta.courses.all { c -> c.enabled }
                    val toggleAllBtn = TextView(this).apply {
                        text = if (allChecked) "全不选" else "全选"
                        textSize = 11.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(if (isLight) 0xFF059669.toInt() else 0xFF30D158.toInt())
                        setPadding(8.dp, 4.dp, 10.dp, 4.dp)
                        setOnClickListener {
                            val targetState = !allChecked
                            meta.courses.forEach { c -> c.enabled = targetState }
                            metaStore.saveStudentMeta(acc.name, meta)
                            renderAccountList(animateEntrance = false)
                        }
                    }
                    semHeader.addView(toggleAllBtn)
                }

                val syncOneBtn = TextView(this).apply {
                    text = "🔄 同步课表"
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(0xFF0A84FF.toInt())
                    setPadding(6.dp, 4.dp, 4.dp, 4.dp)
                    setOnClickListener {
                        lifecycleScope.launch {
                            GlassIslandBanner.show(this@MainActivity, "正在同步 ${acc.name} 的课表...", GlassIslandBanner.Tone.INFO, isLight, 1500L)
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
                                renderAccountList(animateEntrance = false)
                                GlassIslandBanner.show(this@MainActivity, "${acc.name} 已同步 ${courses.size} 门课程", GlassIslandBanner.Tone.SUCCESS, isLight)
                            } else {
                                GlassIslandBanner.show(this@MainActivity, "未拉取到新课程，请检查登录状态", GlassIslandBanner.Tone.WARN, isLight)
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
                    var rowPair: LinearLayout? = null
                    meta.courses.forEachIndexed { cIdx, course ->
                        if (cIdx % 2 == 0) {
                            rowPair = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                            }
                            card.addView(rowPair, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
                        }

                        val chip = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = makeCourseChipBg(course.enabled)
                            setPadding(10.dp, 9.dp, 10.dp, 9.dp)
                            setOnClickListener { v ->
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                meta.courses[cIdx].enabled = !course.enabled
                                meta.courses[cIdx].lastActiveTs = System.currentTimeMillis()
                                metaStore.saveStudentMeta(acc.name, meta)
                                renderAccountList(animateEntrance = false)
                            }
                            // 长按触发“单课专注模式”（仅保留此课，再次长按恢复全选）
                            setOnLongClickListener { v ->
                                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                val isAlreadySolo = course.enabled && meta.courses.count { it.enabled } == 1
                                if (isAlreadySolo) {
                                    meta.courses.forEach { it.enabled = true }
                                    GlassIslandBanner.show(this@MainActivity, "已恢复 ${acc.name} 的全部课程", GlassIslandBanner.Tone.INFO, isLight)
                                } else {
                                    meta.courses.forEachIndexed { idx, item ->
                                        item.enabled = (idx == cIdx)
                                    }
                                    meta.courses[cIdx].lastActiveTs = System.currentTimeMillis()
                                    GlassIslandBanner.show(this@MainActivity, "单课专注：仅签到《${course.courseName}》", GlassIslandBanner.Tone.SUCCESS, isLight)
                                }
                                metaStore.saveStudentMeta(acc.name, meta)
                                renderAccountList(animateEntrance = false)
                                true
                            }
                        }

                        val dotIndicator = TextView(this).apply {
                            text = if (course.enabled) "✓" else "○"
                            textSize = 11f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(if (course.enabled) (if (isLight) 0xFF059669.toInt() else 0xFF30D158.toInt()) else textSecondary)
                        }

                        val chipLabel = TextView(this).apply {
                            text = course.courseName
                            textSize = 12.5f
                            setTypeface(null, if (course.enabled) Typeface.BOLD else Typeface.NORMAL)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            setTextColor(if (course.enabled) textPrimary else textSecondary)
                            setPadding(6.dp, 0, 0, 0)
                        }

                        chip.addView(dotIndicator)
                        chip.addView(chipLabel, LinearLayout.LayoutParams(0, -2, 1f))

                        val chipLp = LinearLayout.LayoutParams(0, -2, 1f).apply {
                            if (cIdx % 2 == 0) rightMargin = 4.dp else leftMargin = 4.dp
                        }
                        rowPair?.addView(chip, chipLp)

                        if (cIdx == meta.courses.lastIndex && cIdx % 2 == 0) {
                            rowPair?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = 4.dp })
                        }
                    }
                }

                val footerActions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                fun makeActionMiniPill(label: String, tint: Int, onClick: () -> Unit): TextView {
                    return TextView(this).apply {
                        text = label
                        textSize = 11.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(tint)
                        val r = 11f.dpF
                        val fill = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = r
                            setColor((tint and 0x00FFFFFF) or 0x1A000000)
                        }
                        background = fill
                        setPadding(10.dp, 6.dp, 10.dp, 6.dp)
                        attachSpringTouch(this, 0.94f) { onClick() }
                    }
                }

                val singleRetryBtn = makeActionMiniPill("⚡ 单独补签", 0xFFFF9F0A.toInt()) {
                    val singleCopy = acc.copy(enabled = true)
                    val lastTask = metaStore.getLastTask()
                    if (lastTask != null && lastTask.first == "QR") {
                        executeUrlCheckIn(lastTask.second, "${acc.name}·单独补签", listOf(singleCopy))
                    } else {
                        executeNumericCheckIn(lastTask?.second ?: "AUTO", listOf(singleCopy))
                    }
                }
                val refreshCredBtn = makeActionMiniPill("更新凭证", 0xFF0A84FF.toInt()) {
                    launchSmoothActivity(Intent(this@MainActivity, WebLoginActivity::class.java).apply {
                        putExtra("preset_name", acc.name)
                    })
                }
                val removeBtn = makeActionMiniPill("移除", 0xFFFF453A.toInt()) {
                    showConfirmSheet(
                        title = "移除成员「${acc.name}」",
                        message = "移除后将删除该同学的本地登录凭证及勾选的课程设置。",
                        confirmText = "确认移除",
                        danger = true
                    ) {
                        accounts.removeAt(index)
                        store.saveAccounts(accounts)
                        metaStore.removeStudentMeta(acc.name)
                        renderAccountList(animateEntrance = false)
                    }
                }
                footerActions.addView(singleRetryBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8.dp })
                footerActions.addView(refreshCredBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8.dp })
                footerActions.addView(removeBtn)
                card.addView(footerActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })
            }

            if (animateEntrance) {
                card.alpha = 0f
                card.translationY = 24f.dpF
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((index * 45L).coerceAtMost(280L))
                    .setDuration(300L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            listContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
        }
    }

    private fun syncAllAccountsCourses() {
        val accounts = store.getAccounts()
        if (accounts.isEmpty()) {
            GlassIslandBanner.show(this, "请先添加同学账号", GlassIslandBanner.Tone.WARN, isLight)
            return
        }
        GlassIslandBanner.show(this, "正在同步全员本学期课表...", GlassIslandBanner.Tone.INFO, isLight, 1600L)
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
            renderAccountList(animateEntrance = false)
            GlassIslandBanner.show(this@MainActivity, "同步完成，共更新 $totalSynced 门课程", GlassIslandBanner.Tone.SUCCESS, isLight)
        }
    }

    private fun verifyAllTokens() {
        val accounts = store.getAccounts()
        if (accounts.isEmpty()) {
            GlassIslandBanner.show(this, "请先添加同学账号", GlassIslandBanner.Tone.WARN, isLight)
            return
        }
        GlassIslandBanner.show(this, "正在并发检测全员凭证存活状态...", GlassIslandBanner.Tone.INFO, isLight, 1600L)
        lifecycleScope.launch {
            accounts.forEach { acc ->
                acc.isValid = KtpQrBatchEngine.checkTokenValid(acc.token)
            }
            store.saveAccounts(accounts)
            renderAccountList(animateEntrance = false)
            val expired = accounts.filter { !it.isValid }.map { acc -> acc.name }
            val msg = if (expired.isEmpty()) {
                "全员共 ${accounts.size} 位同学凭证均正常有效，可随时发起并发抢签。"
            } else {
                "以下同学凭证已过期，请展开对应卡片点击「更新凭证」重新登录：\n${expired.joinToString(separator = "、")}"
            }
            showResultReportSheet(
                title = "全员凭证体检报告",
                summary = if (expired.isEmpty()) "全部有效 (${accounts.size}/${accounts.size})" else "发现 ${expired.size} 人凭证过期",
                detail = msg,
                failedAccounts = emptyList()
            ) {}
        }
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
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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