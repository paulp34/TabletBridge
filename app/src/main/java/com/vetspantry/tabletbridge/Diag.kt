package com.vetspantry.tabletbridge

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory + on-disk diagnostics that survive process death, exposed via /status.
 */
object Diag {
    private const val MAX_LINES = 200
    private val buf = ArrayDeque<String>()
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, "diag.log")
        installCrashHandler()
        log("Diag initialized (process start)")
    }

    fun log(message: String) {
        val line = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + "  " + message
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > MAX_LINES) buf.removeFirst()
        }
        android.util.Log.i("Diag", message)
        try {
            logFile?.let { f ->
                File(f.parentFile!!.absolutePath).mkdirs()
                f.appendText(line + "\n")
                if (f.length() > 200_000) f.writeText("")
            }
        } catch (_: Exception) {
        }
    }

    fun dump(): String {
        val recent = synchronized(buf) { buf.joinToString("\n") }
        val crashes = try {
            logFile?.takeIf { it.exists() }?.readText()?.trim()?.takeLast(2000) ?: ""
        } catch (e: Exception) {
            ""
        }
        return buildString {
            if (recent.isNotEmpty()) append(recent)
            if (crashes.isNotEmpty()) append("\n--- persisted log (tail) ---\n").append(crashes)
            if (isEmpty()) append("No diagnostics yet.")
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("CRASH on ${thread.name}: ${throwable.javaClass.name}: ${throwable.message}")
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            log(sw.toString().take(4000))
            previous?.uncaughtException(thread, throwable)
        }
    }
}
