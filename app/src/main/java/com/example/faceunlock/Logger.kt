package com.example.faceunlock

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal local log file + logcat sink. Appends ISO-ish timestamps so the
 * fake-desktop flow can be audited after the fact (recognition outcomes,
 * home-screen switches, bail-out triggers).
 */
object Logger {

    private const val TAG = "FaceUnlock"
    private const val FILE_NAME = "faceunlock.log"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun i(msg: String) = log("I", msg)
    fun w(msg: String) = log("W", msg)
    fun e(msg: String) = log("E", msg)

    private fun log(level: String, msg: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts $level $msg"
        Log.d(TAG, line)
        val ctx = appContext ?: return
        try {
            val file = File(ctx.filesDir, FILE_NAME)
            FileWriter(file, true).use {
                it.append(line).append('\n')
            }
        } catch (t: Throwable) {
            // Never crash logging.
        }
    }

    /** Whole log file (or empty string); used by the setup screen. */
    fun read(): String {
        val ctx = appContext ?: return ""
        return try {
            File(ctx.filesDir, FILE_NAME).readText()
        } catch (t: Throwable) {
            ""
        }
    }
}
