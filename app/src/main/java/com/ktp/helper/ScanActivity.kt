package com.ktp.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
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
import kotlin.random.Random

class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var focusRingView: View
    private var camera: androidx.camera.core.Camera? = null
    private var torchOn = false
    private var isProcessing = false
    private var currentZoom = 1.0f
    private var pendingRetryAccounts: List<Account>? = null
    private var lastScannedQr: String = ""
    private var autoRescanCount = 0
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finish()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this)
        val reticleOverlay = View(this).apply { background = makeReticleDrawable() }

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
                currentZoom = when {
                    currentZoom < 2.0f -> 2.5f
                    currentZoom < 3.5f -> 4.0f
                    else -> 1.0f
                }
                camera?.cameraControl?.setZoomRatio(currentZoom)
                if (!isProcessing) {
                    statusText.text = "变焦倍率：${currentZoom}x · 单击对焦 / 双击切换"
                }
                return true
            }
        })

        previewView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            val fill = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x660F172A) }
            val rim = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(1.dp, 0x55FFFFFF) }
            background = LayerDrawable(arrayOf<Drawable>(fill, rim))
            setOnClickListener { finish() }
        }

        val torchBtn = TextView(this).apply {
            text = "🔦"
            textSize = 16f
            gravity = Gravity.CENTER
            val fill = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x660F172A) }
            val rim = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(1.dp, 0x55FFFFFF) }
            background = LayerDrawable(arrayOf<Drawable>(fill, rim))
            setOnClickListener {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
            }
        }

        statusText = TextView(this).apply {
            text = "对准投影仪二维码 · 单击对焦 · 双击变焦"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            val r = 24f * resources.displayMetrics.density
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(0x880F172A.toInt())
            }
            val rim = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(Color.TRANSPARENT)
                setStroke(1.dp, 0x55FFFFFF)
            }
            background = LayerDrawable(arrayOf<Drawable>(fill, rim))
            setPadding(22.dp, 12.dp, 22.dp, 12.dp)
            gravity = Gravity.CENTER
        }

        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(reticleOverlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(focusRingView, FrameLayout.LayoutParams(64.dp, 64.dp))
        root.addView(closeBtn, FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.TOP or Gravity.START).apply {
            leftMargin = 20.dp
            topMargin = 36.dp
        })
        root.addView(torchBtn, FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.TOP or Gravity.END).apply {
            rightMargin = 20.dp
            topMargin = 36.dp
        })
        root.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = 52.dp
        })
        setContentView(root)

        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (imageUri != null) {
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
        val lower = raw.lowercase(Locale.US)
        return lower.contains("ketangpai.com") ||
            lower.contains("attence") ||
            lower.contains("signid") ||
            lower.contains("courseid")
    }

    private fun makeReticleDrawable(): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val size = w * 0.62f
            val left = (w - size) / 2f
            val top = (h - size) / 2f - h * 0.04f
            val right = left + size
            val bottom = top + size
            val arm = size * 0.14f

            val path = Path().apply {
                moveTo(left, top + arm); lineTo(left, top); lineTo(left + arm, top)
                moveTo(right - arm, top); lineTo(right, top); lineTo(right, top + arm)
                moveTo(left, bottom - arm); lineTo(left, bottom); lineTo(left + arm, bottom)
                moveTo(right - arm, bottom); lineTo(right, bottom); lineTo(right, bottom - arm)
            }
            canvas.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun buildScanner() = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .setZoomSuggestionOptions(
                ZoomSuggestionOptions.Builder { zoomRatio ->
                    currentZoom = zoomRatio
                    camera?.cameraControl?.setZoomRatio(zoomRatio)
                    true
                }.setMaxSupportedZoomRatio(5.0f).build()
            )
            .build()
    )

    private fun scanFromImageUri(uri: Uri) {
        statusText.text = "正在解析分享图片中的二维码..."
        try {
            val image = InputImage.fromFilePath(this, uri)
            buildScanner().process(image)
                .addOnSuccessListener { barcodes ->
                    val qr = barcodes.firstOrNull()?.rawValue
                    if (!qr.isNullOrEmpty() && isKetangpaiQr(qr)) {
                        handleQrDetected(qr, "分享截图扫码", allowRescan = false)
                    } else {
                        Toast.makeText(this, "未在图片中识别到课堂派考勤二维码", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                .addOnFailureListener { finish() }
        } catch (_: Exception) {
            finish()
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
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
            Toast.makeText(this, "当前没有符合该课程勾选条件的启用账号", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val tag = if (autoRescanCount > 0) "捕获新码 · 正在为剩余 ${targetAccounts.size} 人补签..." else "捕获成功 · 正在并发提交 (${targetAccounts.size}人)..."
            statusText.text = tag
            delay(Random.nextLong(140L, 420L))
            
            val (okCount, failed) = KtpApiClient.batchCheckIn(
                accounts = targetAccounts,
                qrRawUrl = qrContent,
                onProgress = { done, total ->
                    runOnUiThread { statusText.text = "正在并发提交 ($done/$total)..." }
                }
            )
            
            val cost = System.currentTimeMillis() - startMs

            val hasExpiredFailure = failed.any { it.contains("过期") || it.contains("失效") || it.contains("刷新") }
            if (allowRescan && autoRescanCount < 1 && hasExpiredFailure) {
                val remainAccounts = targetAccounts.filter { acc ->
                    failed.any { it.startsWith(acc.name) }
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
                        val detail = "未补签: ${remainAccounts.joinToString { it.name }}"
                        ProMetaStore(this@ScanActivity).addRecord(modeLabel, summary, detail)
                        Toast.makeText(this@ScanActivity, "$summary ($detail)", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@launch
                }
            }

            vibrateDouble()
            val finalMode = if (autoRescanCount > 0) "$modeLabel(含自动续扫)" else modeLabel
            val summary = "成功 $okCount 人 · 耗时 ${cost}ms"
            val detailParts = mutableListOf<String>()
            if (failed.isNotEmpty()) detailParts.add("失败: ${failed.joinToString()}")
            if (skippedList.isNotEmpty()) detailParts.add("跳过: ${skippedList.joinToString()}")
            val detail = if (detailParts.isEmpty()) "参与全员均已成功签收" else detailParts.joinToString(" | ")
            ProMetaStore(this@ScanActivity).addRecord(finalMode, summary, detail)

            val msg = if (failed.isEmpty()) "全员签到成功 ($okCount 人) · 耗时 ${cost}ms"
            else "成功 $okCount 人，失败: ${failed.joinToString()}"
            Toast.makeText(this@ScanActivity, msg, Toast.LENGTH_LONG).show()
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

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}