package com.vetspantry.tabletbridge

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startBridge() }

    private val screenResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, BridgeService::class.java)
                .setAction(BridgeService.ACTION_SCREEN)
                .putExtra(BridgeService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(BridgeService.EXTRA_DATA, result.data)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val needed = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
            val missing = needed.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) startBridge() else perms.launch(missing.toTypedArray())
        }

        findViewById<Button>(R.id.btnScreen).setOnClickListener {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenResult.launch(mgr.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
        }

        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun startBridge() {
        val intent = Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun accessibilityEnabled(): Boolean {
        val expected = ComponentName(this, InputAccessibilityService::class.java).flattenToString()
        val setting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return setting.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun updateStatus() {
        val running = BridgeService.running
        val a11y = accessibilityEnabled()
        val sb = StringBuilder()
        sb.append("Bridge: ").append(if (running) "RUNNING" else "stopped").append('\n')
        if (running) {
            sb.append("Hosts connected: ").append(BridgeService.clientCount).append('\n')
            sb.append("Screen share: ").append(if (BridgeService.screenActive) "on" else "off").append('\n')
            sb.append("Passcode: ").append(getPasscode(this)).append("\n\n")
            sb.append("On the PC (USB cable connected):\n")
            sb.append("  adb forward tcp:8787 tcp:8787\n")
            sb.append("  then open  http://127.0.0.1:8787\n\n")
            sb.append("For scanning into Mendure DME, run TabletBridge\\host\\wedge.ps1\n")
        }
        sb.append("Input control: ")
        sb.append(if (a11y) "enabled" else "DISABLED - tap the button below to enable")
        status.text = sb.toString()
    }

    override fun onResume() {
        super.onResume()
        poll.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }
}
