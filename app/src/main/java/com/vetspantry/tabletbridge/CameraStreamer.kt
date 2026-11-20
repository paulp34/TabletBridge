package com.vetspantry.tabletbridge

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

/** Callbacks used by CameraStreamer and ScreenStreamer to emit frames/events. */
interface FrameSink {
    fun broadcastBinary(channel: Int, data: ByteArray)
    fun broadcastText(json: String)
    fun clients(): Int
}

/**
 * Streams the camera as JPEG frames over the bridge and runs on-device
 * barcode detection (ML Kit) on the same frames, pushing scan results to hosts.
 */
class CameraStreamer(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val sink: FrameSink
) {

    private val mainHandler = Handler(context.mainLooper)
    private val analyzerThread = HandlerThread("bridge-analyzer").apply { start() }
    private val analyzerHandler = Handler(analyzerThread.looper)
    private val analyzerExecutor = Executor { r -> r?.let { analyzerHandler.post(it) } }

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    @Volatile private var lensFacing = CameraSelector.LENS_FACING_BACK
    @Volatile private var torchOn = false
    @Volatile private var active = false
    @Volatile private var lowBand = false

    private var lastFrameAt = 0L
    private var lastRotation = -1
    private var lastBarcode = ""
    private var lastBarcodeAt = 0L
    private val scanner by lazy { BarcodeScanning.getClient() }

    fun start() {
        mainHandler.post {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    provider = future.get()
                    bind()
                } catch (e: Exception) {
                    sink.broadcastText(
                        JSONObject().put("type", "error").put("message", "camera: ${e.message}").toString()
                    )
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    private fun bind() {
        val p = provider ?: return
        p.unbindAll()
        val targetSize = if (lowBand) Size(640, 480) else Size(1280, 720)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .build()
        analysis.setAnalyzer(analyzerExecutor) { image -> analyze(image) }
        camera = p.bindToLifecycle(
            owner,
            CameraSelector.Builder().requireLensFacing(lensFacing).build(),
            analysis
        )
        camera?.cameraControl?.enableTorch(torchOn)
    }

    /** Only spend CPU on frames while a host is actually watching. */
    fun setActive(active: Boolean) {
        this.active = active
    }

    /** Halve resolution, fps and JPEG quality for marginal links (hotspot/Bluetooth). */
    fun setLowBand(on: Boolean) {
        if (lowBand == on) return
        lowBand = on
        mainHandler.post {
            try {
                bind()
            } catch (_: Exception) {
            }
        }
    }

    fun setTorch(on: Boolean): Boolean {
        torchOn = on
        return try {
            camera?.cameraControl?.enableTorch(on)
            camera != null
        } catch (e: Exception) {
            false
        }
    }

    fun switchLens(lens: String) {
        lensFacing =
            if (lens == "front") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        torchOn = false
        mainHandler.post {
            try {
                bind()
            } catch (_: Exception) {
            }
        }
    }

    private fun analyze(image: ImageProxy) {
        var mlStarted = false
        try {
            if (!active || sink.clients() == 0) return

            val now = SystemClock.elapsedRealtime()
            val frameGate = if (lowBand) 250 else 90 // ~4 fps vs ~10 fps
            if (now - lastFrameAt < frameGate) return
            lastFrameAt = now

            val rotation = image.imageInfo.rotationDegrees

            // 1) JPEG for the live preview (also rotates the frame upright).
            val jpeg = ImageUtils.toJpeg(image, if (lowBand) 40 else 65)
            if (jpeg != null) {
                if (rotation != lastRotation) {
                    lastRotation = rotation
                    val (w, h) =
                        if (rotation == 90 || rotation == 270) Pair(image.height, image.width)
                        else Pair(image.width, image.height)
                    sink.broadcastText(
                        JSONObject()
                            .put("type", "meta")
                            .put("stream", "camera")
                            .put("w", w)
                            .put("h", h)
                            .put("rotation", rotation)
                            .toString()
                    )
                }
                sink.broadcastBinary(BridgeServer.CHANNEL_CAMERA, jpeg)
            }

            // 2) Barcode detection on the original frame (ML Kit handles rotation).
            val mediaImage = image.image
            if (mediaImage != null) {
                mlStarted = try {
                    val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
                    // ML Kit callbacks arrive on the main thread by default;
                    // route them to our analyzer thread — network writes are
                    // forbidden on the Android main thread.
                    scanner.process(inputImage)
                        .addOnSuccessListener(analyzerExecutor) { barcodes -> handleBarcodes(barcodes) }
                        .addOnCompleteListener(analyzerExecutor) { image.close() }
                    true
                } catch (t: Throwable) {
                    Diag.log("barcode process() failed: ${t.javaClass.simpleName}: ${t.message}")
                    false
                }
            }
        } catch (t: Throwable) {
            Diag.log("analyze failed: ${t.javaClass.simpleName}: ${t.message}\n" +
                t.stackTraceToString().lineSequence().take(8).joinToString("\n"))
        } finally {
            if (!mlStarted) image.close()
        }
    }

    private fun handleBarcodes(barcodes: List<Barcode>) {
        try {
            val b = barcodes.firstOrNull() ?: return
            val value = b.rawValue ?: return
            val now = SystemClock.elapsedRealtime()
            if (value == lastBarcode && now - lastBarcodeAt < 1500) return // de-dupe
            lastBarcode = value
            lastBarcodeAt = now

            val json = JSONObject()
                .put("type", "barcode")
                .put("value", value)
                .put("format", formatName(b.format))
            val box = b.boundingBox
            if (box != null) {
                json.put(
                    "box",
                    JSONArray().put(box.left).put(box.top).put(box.width()).put(box.height())
                )
            }
            sink.broadcastText(json.toString())
        } catch (t: Throwable) {
            Diag.log("handleBarcodes failed: ${t.javaClass.simpleName}: ${t.message}\n" +
                t.stackTraceToString().lineSequence().take(8).joinToString("\n"))
        }
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        else -> "UNKNOWN"
    }

    fun stop() {
        active = false
        mainHandler.post {
            try {
                provider?.unbindAll()
            } catch (_: Exception) {
            }
        }
        try {
            scanner.close()
        } catch (_: Exception) {
        }
        analyzerThread.quitSafely()
    }
}
