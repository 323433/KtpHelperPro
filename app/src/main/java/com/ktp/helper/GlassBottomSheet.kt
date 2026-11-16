package com.ktp.helper

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class GlassBottomSheet(
    context: Context,
    private val isLight: Boolean,
    title: String,
    subtitle: String? = null,
    badgeColor: Int = 0xFF0A84FF.toInt(),
    badgeGlyph: String = "✦"
) {
    private val dialog = Dialog(context)
    private val overlayRoot: FrameLayout
    private val sheetCard: LinearLayout
    private val bodyContainer: LinearLayout
    private var isDismissing = false

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()

    private val Float.dpF: Float
        get() = this * context.resources.displayMetrics.density

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val textPrimary = if (isLight) 0xFF0F172A.toInt() else Color.WHITE
        val textSecondary = if (isLight) 0xFF475569.toInt() else 0x99EBEBF5.toInt()

        overlayRoot = FrameLayout(context).apply {
            setBackgroundColor(if (isLight) 0x660F172A else 0xA6020617.toInt())
            alpha = 0f
            setOnClickListener { dismiss() }
        }

        sheetCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = makeSheetBackground()
            setPadding(20.dp, 10.dp, 20.dp, 26.dp)
        }

        val handleWrap = FrameLayout(context).apply {
            setPadding(0, 2.dp, 0, 12.dp)
        }
        val handlePill = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3f.dpF
                setColor(if (isLight) 0x330F172A else 0x4DFFFFFF)
            }
        }
        handleWrap.addView(handlePill, FrameLayout.LayoutParams(40.dp, 4.5f.dpF.toInt(), Gravity.CENTER))
        attachSwipeToDismiss(handleWrap)
        sheetCard.addView(handleWrap, LinearLayout.LayoutParams(-1, -2))

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconBadge = TextView(context).apply {
            text = badgeGlyph
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(badgeColor, blendDarker(badgeColor, 0.68f))
            ).apply {
                shape = GradientDrawable.OVAL
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

        val closePill = TextView(context).apply {
            text = "✕"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(textSecondary)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isLight) 0x140F172A else 0x22FFFFFF)
            }
            setOnClickListener { dismiss() }
        }

        headerRow.addView(iconBadge, LinearLayout.LayoutParams(36.dp, 36.dp))
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(closePill, LinearLayout.LayoutParams(30.dp, 30.dp))
        sheetCard.addView(headerRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14.dp })

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        bodyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(bodyContainer)
        sheetCard.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        overlayRoot.addView(sheetCard, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        dialog.setContentView(overlayRoot)
        dialog.setOnCancelListener { dismiss() }
    }

    fun getBody(): LinearLayout = bodyContainer

    @SuppressLint("ClickableViewAccessibility")
    fun addRowCard(
        context: Context,
        glyph: String,
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

        val badge = TextView(context).apply {
            text = glyph
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(glyphBg, blendDarker(glyphBg, 0.72f))
            ).apply {
                shape = GradientDrawable.OVAL
            }
        }

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

    fun show() {
        dialog.show()
        sheetCard.post {
            sheetCard.translationY = sheetCard.height.toFloat().coerceAtLeast(380f)
            overlayRoot.animate().alpha(1f).setDuration(220).start()
            sheetCard.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator(0.65f))
                .start()
        }
    }

    fun dismiss(onComplete: (() -> Unit)? = null) {
        if (isDismissing) return
        isDismissing = true
        val targetY = sheetCard.height.toFloat().coerceAtLeast(380f)
        overlayRoot.animate().alpha(0f).setDuration(190).start()
        sheetCard.animate()
            .translationY(targetY)
            .setDuration(200)
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
        val topCol = if (isLight) 0xF7F8FAFC.toInt() else 0xF5111827.toInt()
        val botCol = if (isLight) 0xFAFFFFFF.toInt() else 0xFA090D16.toInt()

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
                    0f, 0f, 0f, h * 0.35f,
                    if (isLight) 0xFFFFFFFF.toInt() else 0x66FFFFFF,
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
            isLight -> 0xD9FFFFFF.toInt()
            else -> 0x1CFFFFFF
        }
        val topRimCol = when {
            danger -> 0x66FF453A
            isLight -> 0xFFFFFFFF.toInt()
            else -> 0x45FFFFFF
        }
        val botRimCol = when {
            danger -> 0x22FF453A
            isLight -> 0x180F172A
            else -> 0x14FFFFFF
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

    private fun blendDarker(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }
}