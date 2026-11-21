package com.vetspantry.tabletbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONObject

fun getPasscode(context: Context): String {
    val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    return prefs.getString("passcode", null) ?: run {
        val code = (100000..999999).random().toString()
        prefs.edit().putString("passcode", code).apply()
        code
    }
}

class BridgeService : LifecycleService() {

    companion object {
        const val PORT = 8787
        const val ACTION_START = "com.vetspantry.tabletbridge.START"
        const val ACTION_STOP = "com.vetspantry.tabletbridge.STOP"
        const val ACTION_SCREEN = "com.vetspantry.tabletbridge.SCREEN"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 1

        @Volatile var running = false
            private set
        @Volatile var screenActive = false
            private set
        @Volatile var clientCount = 0
            private set
    }

    private var server: BridgeServer? = null
    private var cameraStreamer: CameraStreamer? = null
    private var screenStreamer: ScreenStreamer? = null
    private var lastCameraMeta: String? = null
    private var lastScreenMeta: String? = null
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun frameSink() = object : FrameSink {
        override fun broadcastBinary(channel: Int, data: ByteArray) {
            server?.broadcastBinary(channel, data)
        }

        override fun broadcastText(json: String) {
            server?.broadcastText(json)
            try {
                val o = JSONObject(json)
                if (o.optString("type") == "meta") {
                    if (o.optString("stream") == "camera") lastCameraMeta = json else lastScreenMeta = json
                }
            } catch (_: Exception) {
            }
        }

        override fun clients(): Int = server?.clientCount() ?: 0
    }

    private val sink = object : BridgeSink {
        override fun onCommand(cmd: JSONObject): JSONObject = handleCommand(cmd)

        override fun onSessionsChanged(count: Int) {
            clientCount = count
            cameraStreamer?.setActive(count > 0)
            broadcastState()
        }

        override fun assetBytes(path: String): ByteArray? = try {
            assets.open(path).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }

        override fun onError(message: String) {
            Diag.log("SERVER ERROR: $message")
            // A dead server (e.g. port busy) must not keep showing "RUNNING".
            running = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SCREEN -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (code != -1 && data != null) {
                    // Screen capture setup does network writes — keep it off the main thread.
                    bgExecutor.execute { startScreen(code, data) }
                }
            }
            else -> startBridge()
        }
        return START_STICKY
    }

    private fun startBridge() {
        if (running) return
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification("Bridge active on port $PORT"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
        server = BridgeServer(PORT, getPasscode(this), sink).also { it.start() }
        cameraStreamer = CameraStreamer(this, this, frameSink()).also { it.start() }
        running = true
    }

    private fun startScreen(resultCode: Int, data: Intent) {
        if (screenActive) return
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification("Bridge active (screen sharing)"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        try {
            screenStreamer = ScreenStreamer(this, frameSink()).also { it.start(resultCode, data) }
            screenActive = true
            broadcastState()
        } catch (e: Exception) {
            android.util.Log.e("BridgeService", "screen share failed", e)
        }
    }

    private fun handleCommand(cmd: JSONObject): JSONObject {
        return try {
            when (cmd.optString("type")) {
                "hello" -> JSONObject()
                    .put("type", "hello")
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    .put("android", Build.VERSION.RELEASE)
                    .put("screen", screenActive)
                    .put("clients", clientCount)
                    .put("a11y", InputInjector.available())
                    .also { j ->
                        lastCameraMeta?.let { j.put("cameraMeta", it) }
                        lastScreenMeta?.let { j.put("screenMeta", it) }
                    }
                "state" -> JSONObject()
                    .put("type", "state")
                    .put("clients", clientCount)
                    .put("screen", screenActive)
                    .put("a11y", InputInjector.available())
                "tap" -> ack("tap", InputInjector.tap(cmd.getDouble("x"), cmd.getDouble("y")))
                "swipe" -> ack(
                    "swipe",
                    InputInjector.swipe(
                        cmd.getDouble("x1"), cmd.getDouble("y1"),
                        cmd.getDouble("x2"), cmd.getDouble("y2"),
                        cmd.optLong("ms", 300)
                    )
                )
                "longpress" -> ack(
                    "longpress",
                    InputInjector.longPress(cmd.getDouble("x"), cmd.getDouble("y"))
                )
                "key" -> ack("key", InputInjector.globalAction(cmd.getString("action")))
                "text" -> ack(
                    "text",
                    InputInjector.setText(cmd.getString("value"), cmd.optBoolean("append", false))
                )
                "del" -> ack("del", InputInjector.deleteChars(cmd.optInt("count", 1)))
                "torch" -> ack("torch", cameraStreamer?.setTorch(cmd.optBoolean("on", true)) ?: false)
                "lowband" -> {
                    val on = cmd.optBoolean("on", false)
                    cameraStreamer?.setLowBand(on)
                    screenStreamer?.setLowBand(on)
                    broadcastState()
                    ack("lowband", true)
                }
                "camera" -> {
                    cameraStreamer?.switchLens(cmd.optString("lens", "back"))
                    ack("camera", true)
                }
                else -> JSONObject()
                    .put("type", "error")
                    .put("message", "unknown command: ${cmd.optString("type")}")
            }
        } catch (e: Exception) {
            JSONObject().put("type", "error").put("message", "${e.message}")
        }
    }

    private fun ack(forType: String, ok: Boolean): JSONObject = JSONObject()
        .put("type", "ack")
        .put("for", forType)
        .put("ok", ok)
        .put("a11y", InputInjector.available())

    private fun broadcastState() {
        server?.broadcastText(
            JSONObject()
                .put("type", "state")
                .put("clients", clientCount)
                .put("screen", screenActive)
                .put("a11y", InputInjector.available())
                .toString()
        )
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("bridge", "Bridge", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, "bridge")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("TabletBridge")
            .setContentText(text)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        cameraStreamer?.stop()
        screenStreamer?.stop()
        server?.shutdown()
        server = null
        cameraStreamer = null
        screenStreamer = null
        running = false
        screenActive = false
        super.onDestroy()
    }
}
