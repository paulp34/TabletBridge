package com.vetspantry.tabletbridge

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager
import org.json.JSONObject

/**
 * Optional screen mirroring: captures the tablet screen (with user consent)
 * and streams it as JPEG frames. This is what the host clicks on to inject
 * taps/swipes at real screen coordinates.
 */
class ScreenStreamer(
    private val context: Context,
    private val sink: FrameSink
) {

    private val thread = HandlerThread("bridge-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var lastFrameAt = 0L
    private var stopped = false

    fun start(resultCode: Int, data: Intent) {
        if (stopped) return
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(resultCode, data) ?: return
        projection = proj

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION") val display = wm.defaultDisplay
        val point = Point()
        display.getRealSize(point)
        var w = point.x
        var h = point.y
        if (maxOf(w, h) > 1280) {
            val s = 1280f / maxOf(w, h)
            w = (w * s).toInt()
            h = (h * s).toInt()
        }
        val dpi = context.resources.displayMetrics.densityDpi

        reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 3).also { r ->
            r.setOnImageAvailableListener({ rd -> pump(rd) }, handler)
        }
        virtualDisplay = proj.createVirtualDisplay(
            "TabletBridge",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler
        )
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }, handler)

        sink.broadcastText(
            JSONObject().put("type", "meta").put("stream", "screen")
                .put("w", w).put("h", h).put("rotation", 0).toString()
        )
        sink.broadcastText(
            JSONObject().put("type", "state").put("clients", sink.clients()).put("screen", true).toString()
        )
    }

    private fun pump(rd: ImageReader) {
        if (stopped) return
        val image = rd.acquireLatestImage() ?: return
        try {
            if (sink.clients() == 0) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 120) return // ~8 fps is plenty for control
            lastFrameAt = now
            val jpeg = ImageUtils.rgbaToJpeg(image, 55) ?: return
            sink.broadcastBinary(BridgeServer.CHANNEL_SCREEN, jpeg)
        } finally {
            image.close()
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        try {
            reader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        thread.quitSafely()
        sink.broadcastText(
            JSONObject().put("type", "state").put("clients", sink.clients()).put("screen", false).toString()
        )
    }
}
