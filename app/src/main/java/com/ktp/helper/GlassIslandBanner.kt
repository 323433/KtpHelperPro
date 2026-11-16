package com.ktp.helper

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
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
    private const val TAG_PILL = "ktp_glass_island_pill"
    private const val TAG_LABEL = "ktp_glass_island_label"

    enum class Tone(val dotColor: Int) {
        INFO(0xFF0A84FF.toInt()),
        SUCCESS(0xFF30D158.toInt()),
        WARN(0xFFFF9F0A.toInt()),
        ERROR(0xFFFF453A.toInt())
    }

    fun updateMessage(activity: Activity, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val pill = decor.findViewWithTag<LinearLayout>(TAG_PILL) ?: return
        pill.findViewWithTag<TextView>(TAG_LABEL)?.text = message
    }

    fun show(
        activity: Activity,
        message: String,
        tone: Tone = Tone.INFO,
        isLight: Boolean = false,
        durationMs: Long = 2400L,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView as? ViewGroup ?: return

        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        decor.findViewWithTag<LinearLayout>(TAG_PILL)?.let { decor.removeView(it) }

        val d = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        val resId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusH = if (resId > 0) activity.resources.getDimensionPixelSize(resId) else dp(28)

        val pill = LinearLayout(activity).apply {
            this.tag = TAG_PILL
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 16f * d
            setPadding(dp(14), dp(10), if (actionLabel != null) dp(10) else dp(16), dp(10))

            val r = 22f * d
            val fill = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (isLight) intArrayOf(0xF7FFFFFF.toInt(), 0xF0EEF2F9.toInt())
                else intArrayOf(0xF51E293B.toInt(), 0xF80F172A.toInt())
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
            }
            val rim = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), if (isLight) 0x330F172A else 0x55FFFFFF)
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
        dotWrap.addView(halo, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        dotWrap.addView(coreDot, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))

        val label = TextView(activity).apply {
            this.tag = TAG_LABEL
            text = message
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (isLight) 0xFF0F172A.toInt() else Color.WHITE)
            setPadding(dp(9), 0, 0, 0)
        }

        pill.addView(dotWrap, LinearLayout.LayoutParams(dp(16), dp(16)))
        pill.addView(label, LinearLayout.LayoutParams(-2, -2))

        fun animateOut() {
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            pill.animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .translationY(-22f * d)
                .setDuration(190L)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    try {
                        decor.removeView(pill)
                    } catch (_: Exception) {
                    }
                }
                .start()
        }

        if (!actionLabel.isNullOrEmpty() && onAction != null) {
            val actionPill = TextView(activity).apply {
                text = actionLabel
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f * d
                    setColor(0xFF0A84FF.toInt())
                }
                setPadding(dp(11), dp(5), dp(11), dp(5))
                setOnClickListener {
                    animateOut()
                    onAction()
                }
            }
            pill.addView(actionPill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        }

        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = statusH + dp(8)
            leftMargin = dp(18)
            rightMargin = dp(18)
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
            .setDuration(270L)
            .setInterpolator(OvershootInterpolator(1.05f))
            .start()

        val runnable = Runnable { animateOut() }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, durationMs)
    }
}