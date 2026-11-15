package com.ktp.helper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private var camera: Camera? = null
    private var isProcessing = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        previewView = PreviewView(this)
        statusText = TextView(this).apply {
            text = "对准投影仪二维码（支持远距离自动变焦）"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setBackgroundColor(0x99000000.toInt())
            setPadding(36, 24, 36, 24)
            gravity = Gravity.CENTER
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(statusText, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
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

    private fun buildScanner() = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .setZoomSuggestionOptions(
                ZoomSuggestionOptions.Builder { zoomRatio ->
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
                    if (!qr.isNullOrEmpty()) handleQrDetected(qr)
                    else {
                        Toast.makeText(this, "未在图片中识别到二维码", Toast.LENGTH_SHORT).show()
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
                                isProcessing = true
                                handleQrDetected(qr)
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

    private fun handleQrDetected(qrContent: String) {
        vibrateSingle()
        val startMs = System.currentTimeMillis()
        val accounts = AccountStore(this).getAccounts()
        if (accounts.none { it.enabled }) {
            Toast.makeText(this, "请先勾选需要代签的账号", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            statusText.text = "捕获成功！正在并发提交全员签到..."
            val (okCount, failed) = KtpApiClient.batchCheckIn(
                accounts = accounts,
                qrRawUrl = qrContent
            ) { done, total ->
                runOnUiThread { statusText.text = "⚡ 正在并发提交 ($done/$total)..." }
            }
            val cost = System.currentTimeMillis() - startMs
            vibrateDouble()
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