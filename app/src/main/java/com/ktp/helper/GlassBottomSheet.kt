package com.ktp.helper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

enum class SheetGlyphType {
    KEYPAD, CHECK, ALERT, PIN, LINK, CHART, BOLT, SPARKLE
}

class GlassBottomSheet(
    private val context: Context,
    private val isLight: Boolean,
    title: String,
    subtitle: String? = null,
    badgeColor: Int = 0xFF0A84FF.toInt(),
    glyphType: SheetGlyphType = SheetGlyphType.SPARKLE,
    badgeText: String? = null
) {
    private val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
    private val overlayRoot: FrameLayout
    private val sheetCard: LinearLayout
    private val bodyContainer: LinearLayout
    private var isDismissing = false

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()

    private val Float.dpF: Float
        get() = this * context.resources.displayMetrics.density

    private fun getNavBarHeight(): Int {
        val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val textPrimary = if (isLight) 0xFF0F172A.toInt() else Color.WHITE
        val textSecondary = if (isLight) 0xFF475569.toInt() else 0x99EBEBF5.toInt()

        overlayRoot = FrameLayout(context).apply {
            setBackgroundColor(if (isLight) 0x590F172A else 0x8C020617.toInt())
            alpha = 0f
            setOnClickListener { dismiss() }
        }

        val bottomSafePad = 24.dp + getNavBarHeight()
        sheetCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = makeSheetBackground()
            setPadding(20.dp, 10.dp, 20.dp, bottomSafePad)
        }

        val handleWrap = FrameLayout(context).apply {
            setPadding(0, 2.dp, 0, 14.dp)
        }
        val handlePill = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3f.dpF
                setColor(if (isLight) 0x330F172A else 0x55FFFFFF)
            }
        }
        handleWrap.addView(handlePill, FrameLayout.LayoutParams(40.dp, 4.5f.dpF.toInt(), Gravity.CENTER))
        attachSwipeToDismiss(handleWrap)
        sheetCard.addView(handleWrap, LinearLayout.LayoutParams(-1, -2))

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badgeBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(badgeColor, blendDarker(badgeColor, 0.68f))
        ).apply {
            shape = GradientDrawable.OVAL
        }

        val iconBadgeView: View = if (!badgeText.isNullOrEmpty()) {
            TextView(context).apply {
                text = badgeText
                textSize = 15.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = badgeBg
            }
        } else {
            FrameLayout(context).apply {
                background = badgeBg
                addView(
                    View(context).apply { background = makeVectorGlyph(glyphType, Color.WHITE) },
                    FrameLayout.LayoutParams(18.dp, 18.dp, Gravity.CENTER)
                )
            }
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 0, 8.dp, 0)
        }
        titleCol.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        if (!subtitle.isNullOrEmpty()) {
            titleCol.addView(TextView(context).apply {
                text = subtitle
                textSize = 12f
                setTextColor(textSecondary)
                setPadding(0, 2.dp, 0, 0)
            })
        }

        val closePill = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isLight) 0x140F172A else 0x24FFFFFF)
            }
            setOnClickListener { dismiss() }
        }
        closePill.addView(
            View(context).apply { background = makeCloseCrossDrawable(textSecondary) },
            FrameLayout.LayoutParams(12.dp, 12.dp, Gravity.CENTER)
        )

        headerRow.addView(iconBadgeView, LinearLayout.LayoutParams(38.dp, 38.dp))
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(closePill, LinearLayout.LayoutParams(30.dp, 30.dp))
        sheetCard.addView(headerRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16.dp })

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        bodyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(bodyContainer)
        sheetCard.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        overlayRoot.addView(sheetCard, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            topMargin = 64.dp
        })

        dialog.setContentView(overlayRoot)
        dialog.window?.apply {
            decorView.setPadding(0, 0, 0, 0)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.FILL)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnCancelListener { dismiss() }
    }

    fun getBody(): LinearLayout = bodyContainer

    @SuppressLint("ClickableViewAccessibility")
    fun addRowCard(
        context: Context,
        glyphType: SheetGlyphType,
        glyphBg: Int,
        title: String,
        sub: String,
        danger: Boolean = false,
        onClick: () -> Unit
    ): LinearLayout {
        val textPrimary = when {
            danger -> 0xFFFF453A.toInt()
            isLight -> 0xFF0F172A.toInt()
            else -> Color.WHITE
        }
        val textSecondary = if (isLight) 0xFF475569.toInt() else 0x8CEBEBF5.toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeInnerBevelGlass(18f, danger)
            setPadding(14.dp, 13.dp, 14.dp, 13.dp)
        }

        val badge = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(glyphBg, blendDarker(glyphBg, 0.72f))
            ).apply {
                shape = GradientDrawable.OVAL
            }
        }
        badge.addView(
            View(context).apply { background = makeVectorGlyph(glyphType, Color.WHITE) },
            FrameLayout.LayoutParams(17.dp, 17.dp, Gravity.CENTER)
        )

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 0, 8.dp, 0)
        }
        col.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        col.addView(TextView(context).apply {
            text = sub
            textSize = 11.5f
            setTextColor(textSecondary)
            setPadding(0, 2.dp, 0, 0)
        })

        val arrow = TextView(context).apply {
            text = "›"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textSecondary)
        }

        row.addView(badge, LinearLayout.LayoutParams(36.dp, 36.dp))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(arrow)

        row.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    onClick()
                }
                MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            true
        }

        bodyContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
        return row
    }

    private fun setUnderlyingBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val act = context as? Activity ?: return
        val contentRoot = act.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return
        contentRoot.setRenderEffect(
            if (enabled) RenderEffect.createBlurEffect(22f, 22f, Shader.TileMode.CLAMP) else null
        )
    }

    fun show() {
        setUnderlyingBlur(true)
        dialog.show()
        sheetCard.post {
            sheetCard.translationY = sheetCard.height.toFloat().coerceAtLeast(380f)
            overlayRoot.animate().alpha(1f).setDuration(220).start()
            sheetCard.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator(0.55f))
                .start()
        }
    }

    fun dismiss(onComplete: (() -> Unit)? = null) {
        if (isDismissing) return
        isDismissing = true
        setUnderlyingBlur(false)
        val targetY = sheetCard.height.toFloat().coerceAtLeast(380f)
        overlayRoot.animate().alpha(0f).setDuration(180).start()
        sheetCard.animate()
            .translationY(targetY)
            .setDuration(190)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                try {
                    dialog.dismiss()
                } catch (_: Exception) {
                }
                onComplete?.invoke()
            }
            .start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeToDismiss(dragView: View) {
        var startY = 0f
        dragView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - startY).coerceAtLeast(0f)
                    sheetCard.translationY = dy
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (sheetCard.translationY > 95.dp) {
                        dismiss()
                    } else {
                        sheetCard.animate()
                            .translationY(0f)
                            .setDuration(200)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun makeSheetBackground(): LayerDrawable {
        val r = 28f.dpF
        val radii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        val topCol = if (isLight) 0xFAFFFFFF.toInt() else 0xFA161F30.toInt()
        val botCol = if (isLight) 0xFCF4F7FB.toInt() else 0xFC0B101B.toInt()

        val fill = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topCol, botCol)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
        }
        val topRim = object : Drawable() {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f.dpF
            }
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                if (w <= 0f || h <= 0f) return
                p.shader = LinearGradient(
                    0f, 0f, 0f, h * 0.32f,
                    if (isLight) 0xFFFFFFFF.toInt() else 0x77FFFFFF,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                val path = Path().apply {
                    addRoundRect(RectF(0.6f.dpF, 0.6f.dpF, w - 0.6f.dpF, h), radii, Path.Direction.CW)
                }
                canvas.drawPath(path, p)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        return LayerDrawable(arrayOf<Drawable>(fill, topRim))
    }

    private fun makeInnerBevelGlass(radiusDp: Float, danger: Boolean): LayerDrawable {
        val r = radiusDp.dpF
        val fillCol = when {
            danger -> 0x1FFF453A
            isLight -> 0xE6FFFFFF.toInt()
            else -> 0x20FFFFFF
        }
        val topRimCol = when {
            danger -> 0x66FF453A
            isLight -> 0xFFFFFFFF.toInt()
            else -> 0x4AFFFFFF
        }
        val botRimCol = when {
            danger -> 0x22FF453A
            isLight -> 0x180F172A
            else -> 0x16FFFFFF
        }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(fillCol)
        }
        val bevel = object : Drawable() {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f.dpF
            }
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                if (w <= 0f || h <= 0f) return
                p.shader = LinearGradient(0f, 0f, 0f, h, topRimCol, botRimCol, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(0.5f.dpF, 0.5f.dpF, w - 0.5f.dpF, h - 0.5f.dpF), r, r, p)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        return LayerDrawable(arrayOf<Drawable>(fill, bevel))
    }

    private fun makeCloseCrossDrawable(tint: Int): Drawable = object : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1.8f.dpF
        }
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val pad = w * 0.16f
            canvas.drawLine(pad, pad, w - pad, h - pad, p)
            canvas.drawLine(w - pad, pad, pad, h - pad, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun makeVectorGlyph(type: SheetGlyphType, tint: Int): Drawable = object : Drawable() {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.FILL
        }
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            stroke.strokeWidth = w * 0.115f
            val p = w * 0.16f

            when (type) {
                SheetGlyphType.KEYPAD -> {
                    val r = w * 0.085f
                    for (row in 0..2) {
                        for (col in 0..2) {
                            val cx = w * (0.24f + col * 0.26f)
                            val cy = h * (0.24f + row * 0.26f)
                            canvas.drawCircle(cx, cy, r, fill)
                        }
                    }
                }
                SheetGlyphType.CHECK -> {
                    val path = Path().apply {
                        moveTo(w * 0.22f, h * 0.52f)
                        lineTo(w * 0.42f, h * 0.72f)
                        lineTo(w * 0.78f, h * 0.30f)
                    }
                    canvas.drawPath(path, stroke)
                }
                SheetGlyphType.ALERT -> {
                    canvas.drawLine(w * 0.5f, h * 0.22f, w * 0.5f, h * 0.58f, stroke)
                    canvas.drawCircle(w * 0.5f, h * 0.76f, w * 0.07f, fill)
                }
                SheetGlyphType.PIN -> {
                    val cx = w * 0.5f
                    canvas.drawCircle(cx, h * 0.38f, w * 0.14f, stroke)
                    val path = Path().apply {
                        moveTo(cx, h - p)
                        cubicTo(w * 0.20f, h * 0.56f, w * 0.22f, p, cx, p)
                        cubicTo(w * 0.78f, p, w * 0.80f, h * 0.56f, cx, h - p)
                    }
                    canvas.drawPath(path, stroke)
                }
                SheetGlyphType.LINK -> {
                    val r = w * 0.18f
                    canvas.drawRoundRect(RectF(p, h * 0.34f, w * 0.56f, h * 0.66f), r, r, stroke)
                    canvas.drawRoundRect(RectF(w * 0.44f, h * 0.34f, w - p, h * 0.66f), r, r, stroke)
                }
                SheetGlyphType.CHART -> {
                    canvas.drawLine(w * 0.26f, h * 0.78f, w * 0.26f, h * 0.50f, stroke)
                    canvas.drawLine(w * 0.50f, h * 0.78f, w * 0.50f, h * 0.26f, stroke)
                    canvas.drawLine(w * 0.74f, h * 0.78f, w * 0.74f, h * 0.40f, stroke)
                }
                SheetGlyphType.BOLT -> {
                    val path = Path().apply {
                        moveTo(w * 0.56f, p)
                        lineTo(w * 0.28f, h * 0.54f)
                        lineTo(w * 0.50f, h * 0.54f)
                        lineTo(w * 0.44f, h - p)
                        lineTo(w * 0.72f, h * 0.46f)
                        lineTo(w * 0.50f, h * 0.46f)
                        close()
                    }
                    canvas.drawPath(path, stroke)
                }
                SheetGlyphType.SPARKLE -> {
                    canvas.drawLine(w * 0.5f, p, w * 0.5f, h - p, stroke)
                    canvas.drawLine(p, h * 0.5f, w - p, h * 0.5f, stroke)
                }
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun blendDarker(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }
}