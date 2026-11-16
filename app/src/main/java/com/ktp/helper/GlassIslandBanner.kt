package com.ktp.helper

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object GlassIslandBanner {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    enum class Tone(val dotColor: Int) {
        INFO(0xFF0A84FF.toInt()),
        SUCCESS(0xFF30D158.toInt()),
        WARN(0xFFFF9F0A.toInt()),
        ERROR(0xFFFF453A.toInt())
    }

    fun show(
        activity: Activity,
        message: String,
        tone: Tone = Tone.INFO,
        isLight: Boolean = false,
        durationMs: Long = 2400L
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val tag = "ktp_glass_island_pill"

        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        decor.findViewWithTag<LinearLayout>(tag)?.let { decor.removeView(it) }

        val d = activity.resources.displayMetrics.density
        fun Int.dp(): Int = (this * d).toInt()

        val resId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusH = if (resId > 0) activity.resources.getDimensionPixelSize(resId) else 28.dp()

        val pill = LinearLayout(activity).apply {
            this.tag = tag
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 16f * d
            setPadding(14.dp(), 10.dp(), 16.dp(), 10.dp)

            val r = 22f * d
            val fill = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (isLight) intArrayOf(0xF5FFFFFF.toInt(), 0xEBEEF2F9.toInt())
                else intArrayOf(0xF21E293B.toInt(), 0xF50F172A.toInt())
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
            }
            val rim = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(Color.TRANSPARENT)
                setStroke(1.dp(), if (isLight) 0x330F172A else 0x55FFFFFF)
            }
            background = LayerDrawable(arrayOf<Drawable>(fill, rim))
        }

        val dotWrap = FrameLayout(activity)
        val halo = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((tone.dotColor and 0x00FFFFFF) or 0x38000000)
            }
        }
        val coreDot = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tone.dotColor)
            }
        }
        dotWrap.addView(halo, FrameLayout.LayoutParams(16.dp(), 16.dp(), Gravity.CENTER))
        dotWrap.addView(coreDot, FrameLayout.LayoutParams(8.dp(), 8.dp(), Gravity.CENTER))

        val label = TextView(activity).apply {
            text = message
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (isLight) 0xFF0F172A.toInt() else Color.WHITE)
            setPadding(9.dp(), 0, 0, 0)
        }

        pill.addView(dotWrap, LinearLayout.LayoutParams(16.dp(), 16.dp()))
        pill.addView(label, LinearLayout.LayoutParams(-2, -2))

        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = statusH + 8.dp()
            leftMargin = 20.dp()
            rightMargin = 20.dp()
        }
        decor.addView(pill, lp)

        pill.alpha = 0f
        pill.scaleX = 0.86f
        pill.scaleY = 0.86f
        pill.translationY = -28f * d
        pill.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(280L)
            .setInterpolator(OvershootInterpolator(1.05f))
            .start()

        val runnable = Runnable {
            pill.animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .translationY(-22f * d)
                .setDuration(200L)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    try {
                        decor.removeView(pill)
                    } catch (_: Exception) {
                    }
                }
                .start()
        }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, durationMs)
    }
}