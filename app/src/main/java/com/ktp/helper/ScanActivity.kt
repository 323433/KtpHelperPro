package com.ktp.helper

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var focusRingView: View
    private lateinit var torchBtn: TextView
    private var camera: androidx.camera.core.Camera? = null
    private var torchOn = false
    private var isProcessing = false
    private var currentZoom = 1.0f
    private var pendingRetryAccounts: List<Account>? = null
    private var lastScannedQr: String = ""
    private var autoRescanCount = 0
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var laserAnimator: ValueAnimator? = null
    private var zoomAnimator: ValueAnimator? = null
    private val zoomButtons = mutableListOf<Pair<Float, TextView>>()

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private val Float.dpF: Float
        get() = this * resources.displayMetrics.density

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finish()
    }

    private val pickGalleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && !isProcessing) {
            isProcessing = true
            scanFromImageUri(uri)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.BLACK
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this)

        var laserProgress = 0f
        val reticleOverlay = ReticleLaserOverlayView(this) { laserProgress }
        laserAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2100L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                laserProgress = anim.animatedValue as Float
                reticleOverlay.invalidate()
            }
            start()
        }

        focusRingView = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(2.dp, 0xFF30D158.toInt())
            }
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                focusAndMeterAt(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val nextZoom = when {
                    currentZoom < 2.0f -> 2.5f
                    currentZoom < 3.5f -> 4.0f
                    else -> 1.0f
                }
                applyZoomSmooth(nextZoom)
                return true
            }
        })

        previewView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = makeRoundGlass(0x770F172A, 0x55FFFFFF)
            setOnClickListener { finish() }
        }

        val galleryBtn = TextView(this).apply {
            text = "🖼️ 相册识码"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            val r = 20f.dpF
            val fill = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(0x880A84FF.toInt()) }
            val rim = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(Color.TRANSPARENT); setStroke(1.dp, 0x77FFFFFF) }
            background = LayerDrawable(arrayOf<Drawable>(fill, rim))
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            setOnClickListener { pickGalleryLauncher.launch("image/*") }
        }

        torchBtn = TextView(this).apply {
            text = "🔦"
            textSize = 15f
            gravity = Gravity.CENTER
            background = makeRoundGlass(0x770F172A, 0x55FFFFFF)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
                background = if (torchOn) makeRoundGlass(0xCCFF9F0A.toInt(), 0xFFFFFFFF.toInt())
                else makeRoundGlass(0x770F172A, 0x55FFFFFF)
            }
        }

        val zoomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val r = 20f.dpF
            val fill = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(0x880F172A.toInt()) }
            val rim = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(Color.TRANSPARENT); setStroke(1.dp, 0x45FFFFFF) }
            background = LayerDrawable(arrayOf<Drawable>(fill, rim))
            setPadding(5.dp, 4.dp, 5.dp, 4.dp)
        }
        val zoomLevels = listOf(1.0f to "1.0x", 2.5f to "2.5x", 4.0f to "4.0x")
        zoomLevels.forEachIndexed { idx, (ratio, label) ->
            val pill = TextView(this).apply {
                text = label
                textSize = 12f
                fontFeatureSettings = "tnum"
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(14.dp, 6.dp, 14.dp, 6.dp)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    applyZoomSmooth(ratio)
                }
            }
            zoomButtons.add(ratio to pill)
            zoomBar.addView(pill, LinearLayout.LayoutParams(-2, -2).apply {
                if (idx < zoomLevels.lastIndex) rightMargin = 4.dp
            })
        }
        updateZoomPillsUI(1.0f)

        statusText = TextView(this).apply {
            text = "对准投影仪二维码 · 单击测光对焦"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            val r = 24f.dpF
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(0x990F172A.toInt())
            }
            val rim = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(Color.TRANSPARENT)
                setStroke(1.dp, 0x55FFFFFF)
            }
            background = LayerDrawable(arrayOf<Drawable>(fill, rim))
            setPadding(22.dp, 11.dp, 22.dp, 11.dp)
            gravity = Gravity.CENTER
        }

        val bottomHud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        bottomHud.addView(zoomBar, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 12.dp })
        bottomHud.addView(statusText, LinearLayout.LayoutParams(-2, -2))

        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(reticleOverlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(focusRingView, FrameLayout.LayoutParams(64.dp, 64.dp))
        root.addView(closeBtn, FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.TOP or Gravity.START).apply {
            leftMargin = 20.dp
            topMargin = 42.dp
        })
        root.addView(galleryBtn, FrameLayout.LayoutParams(-2, 40.dp, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = 42.dp
        })
        root.addView(torchBtn, FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.TOP or Gravity.END).apply {
            rightMargin = 20.dp
            topMargin = 42.dp
        })
        root.addView(bottomHud, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = 46.dp
        })
        setContentView(root)

        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (imageUri != null) {
                isProcessing = true
                scanFromImageUri(imageUri)
                return
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun makeRoundGlass(fillCol: Int, strokeCol: Int): LayerDrawable {
        val fill = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fillCol) }
        val rim = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(1.dp, strokeCol) }
        return LayerDrawable(arrayOf<Drawable>(fill, rim))
    }

    private fun applyZoomSmooth(targetRatio: Float) {
        zoomAnimator?.cancel()
        val startRatio = currentZoom
        updateZoomPillsUI(targetRatio)
        zoomAnimator = ValueAnimator.ofFloat(startRatio, targetRatio).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                currentZoom = v
                camera?.cameraControl?.setZoomRatio(v)
            }
            start()
        }
    }

    private fun updateZoomPillsUI(activeRatio: Float) {
        var nearestRatio = 1.0f
        var minDiff = Float.MAX_VALUE
        for ((r, _) in zoomButtons) {
            val diff = kotlin.math.abs(r - activeRatio)
            if (diff < minDiff) {
                minDiff = diff
                nearestRatio = r
            }
        }
        for ((r, pill) in zoomButtons) {
            if (r == nearestRatio) {
                pill.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 15f.dpF
                    setColor(0xFF0A84FF.toInt())
                }
                pill.setTextColor(Color.WHITE)
            } else {
                pill.background = null
                pill.setTextColor(0xB3FFFFFF.toInt())
            }
        }
    }

    private fun focusAndMeterAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)

        focusRingView.translationX = x - 32.dp
        focusRingView.translationY = y - 32.dp
        focusRingView.visibility = View.VISIBLE
        focusRingView.scaleX = 1.25f
        focusRingView.scaleY = 1.25f
        focusRingView.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            focusRingView.postDelayed({ focusRingView.visibility = View.GONE }, 900)
        }.start()
    }

    private fun isKetangpaiQr(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.US)
        if (lower.contains("ketangpai.com") || lower.contains("attence") || lower.contains("signid") || lower.contains("courseid") || lower.contains("checkin")) {
            return true
        }
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.contains("://")) {
            return false
        }
        return trimmed.length in 4..256
    }

    private fun buildScanner() = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .setZoomSuggestionOptions(
                ZoomSuggestionOptions.Builder { zoomRatio ->
                    runOnUiThread { applyZoomSmooth(zoomRatio) }
                    true
                }.setMaxSupportedZoomRatio(5.0f).build()
            )
            .build()
    )

    private fun scanFromImageUri(uri: Uri) {
        statusText.text = "正在解析图片中的二维码..."
        try {
            val image = InputImage.fromFilePath(this, uri)
            buildScanner().process(image)
                .addOnSuccessListener { barcodes ->
                    val qr = barcodes.firstOrNull()?.rawValue
                    if (!qr.isNullOrEmpty() && isKetangpaiQr(qr)) {
                        handleQrDetected(qr, "相册截图扫码", allowRescan = false)
                    } else {
                        isProcessing = false
                        statusText.text = "未在图片中识别到课堂派考勤二维码"
                        GlassIslandBanner.show(this, "未识别到课堂派考勤二维码", GlassIslandBanner.Tone.WARN, false)
                    }
                }
                .addOnFailureListener {
                    isProcessing = false
                    GlassIslandBanner.show(this, "图片解析失败", GlassIslandBanner.Tone.ERROR, false)
                }
        } catch (_: Exception) {
            isProcessing = false
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { p ->
                p.setSurfaceProvider(previewView.surfaceProvider)
            }
            val scanner = buildScanner()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null && !isProcessing) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            val qr = barcodes.firstOrNull()?.rawValue
                            if (!qr.isNullOrEmpty() && !isProcessing) {
                                if (!isKetangpaiQr(qr)) {
                                    runOnUiThread {
                                        statusText.text = "已忽略非课堂派二维码 · 请对准考勤码"
                                    }
                                    return@addOnSuccessListener
                                }
                                if (autoRescanCount > 0 && qr == lastScannedQr) {
                                    return@addOnSuccessListener
                                }
                                isProcessing = true
                                handleQrDetected(qr, "现场动态扫码", allowRescan = true)
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleQrDetected(qrContent: String, modeLabel: String, allowRescan: Boolean) {
        vibrateSingle()
        val startMs = System.currentTimeMillis()
        val baseAccounts = pendingRetryAccounts ?: AccountStore(this).getAccounts()
        val (targetAccounts, skippedList) = KtpCourseSyncer.filterAccountsByCourse(this, baseAccounts, qrContent)

        if (targetAccounts.isEmpty()) {
            ProMetaStore(this).savePendingScanReport(
                title = "扫码提示",
                summary = "未执行提交",
                detail = "当前没有符合该课程勾选条件的启用账号。",
                qrRawUrl = "",
                failedNames = emptyList()
            )
            finish()
            return
        }

        lifecycleScope.launch {
            val tag = if (autoRescanCount > 0) "捕获新码 · 正在为剩余 ${targetAccounts.size} 人补签..." else "捕获成功 · 正在并发提交 (${targetAccounts.size}人)..."
            statusText.text = tag

            val (okCount, failed) = KtpQrBatchEngine.batchQrCheckIn(
                context = this@ScanActivity,
                accounts = targetAccounts,
                qrRawUrl = qrContent
            ) { done, total ->
                runOnUiThread { statusText.text = "正在并发提交 ($done/$total)..." }
            }

            val cost = System.currentTimeMillis() - startMs

            val hasExpiredFailure = failed.any { msg -> msg.contains("过期") || msg.contains("失效") || msg.contains("刷新") }
            if (allowRescan && autoRescanCount < 1 && hasExpiredFailure) {
                val remainAccounts = targetAccounts.filter { acc ->
                    failed.any { msg -> msg.startsWith(acc.name) }
                }
                if (remainAccounts.isNotEmpty()) {
                    pendingRetryAccounts = remainAccounts
                    lastScannedQr = qrContent
                    autoRescanCount++
                    isProcessing = false
                    statusText.text = "动态码已刷新，保持对准自动为剩余 ${remainAccounts.size} 人补签..."

                    delay(4000L)
                    if (!isFinishing && !isProcessing) {
                        val summary = "部分成功 $okCount 人 · 续扫超时"
                        val detail = "未补签: ${remainAccounts.joinToString(separator = ", ") { acc -> acc.name }}"
                        val store = ProMetaStore(this@ScanActivity)
                        store.addRecord(modeLabel, summary, detail)
                        store.savePendingScanReport(
                            title = "扫码考勤战报（$modeLabel）",
                            summary = summary,
                            detail = detail,
                            qrRawUrl = qrContent,
                            failedNames = remainAccounts.map { acc -> acc.name }
                        )
                        finish()
                    }
                    return@launch
                }
            }

            vibrateDouble()
            val finalMode = if (autoRescanCount > 0) "$modeLabel(含自动续扫)" else modeLabel
            val summary = "成功 $okCount 人 · 耗时 ${cost}ms"
            val detailParts = mutableListOf<String>()
            if (failed.isNotEmpty()) detailParts.add("失败: ${failed.joinToString(separator = ", ")}")
            if (skippedList.isNotEmpty()) detailParts.add("跳过: ${skippedList.joinToString(separator = ", ")}")
            val detail = if (detailParts.isEmpty()) "参与全员均已成功签收" else detailParts.joinToString(separator = " | ")

            val failedNames = targetAccounts.filter { acc ->
                failed.any { msg -> msg.startsWith(acc.name) }
            }.map { acc -> acc.name }

            val store = ProMetaStore(this@ScanActivity)
            store.addRecord(finalMode, summary, detail)
            store.savePendingScanReport(
                title = "扫码考勤战报（$finalMode）",
                summary = summary,
                detail = detail,
                qrRawUrl = qrContent,
                failedNames = failedNames
            )
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateSingle() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    @Suppress("DEPRECATION")
    private fun vibrateDouble() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 80, 70), -1))
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.page_pop_enter, R.anim.page_pop_exit)
    }

    override fun onDestroy() {
        laserAnimator?.cancel()
        zoomAnimator?.cancel()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

class ReticleLaserOverlayView(
    context: Context,
    private val progressProvider: () -> Float
) : View(context) {
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x7A040711 }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64D2FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3.8f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val size = w * 0.64f
        val left = (w - size) / 2f
        val top = (h - size) / 2f - h * 0.05f
        val right = left + size
        val bottom = top + size
        val cutRadius = 22f * resources.displayMetrics.density

        canvas.drawRect(0f, 0f, w, h, dimPaint)
        canvas.drawRoundRect(RectF(left, top, right, bottom), cutRadius, cutRadius, clearPaint)

        val arm = size * 0.14f
        val path = Path().apply {
            moveTo(left, top + arm); lineTo(left, top); lineTo(left + arm, top)
            moveTo(right - arm, top); lineTo(right, top); lineTo(right, top + arm)
            moveTo(left, bottom - arm); lineTo(left, bottom); lineTo(left + arm, bottom)
            moveTo(right - arm, bottom); lineTo(right, bottom); lineTo(right, bottom - arm)
        }
        canvas.drawPath(path, cornerPaint)

        val pad = 16f * resources.displayMetrics.density
        val y = top + pad + (size - pad * 2f) * progressProvider()
        laserPaint.shader = LinearGradient(
            left + pad, y, right - pad, y,
            intArrayOf(Color.TRANSPARENT, 0xCC30D158.toInt(), 0xFF64D2FF.toInt(), 0xCC30D158.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        laserPaint.strokeWidth = 2.6f * resources.displayMetrics.density
        canvas.drawLine(left + pad, y, right - pad, y, laserPaint)
    }
}