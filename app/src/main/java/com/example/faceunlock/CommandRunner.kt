package com.example.faceunlock

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs in the Shizuku (or Sui) server process when started as a user service.
 * The only purpose is to execute `dpm set-device-owner` as the shell user so the
 * app can become its own device owner without a physical ADB connection.
 *
 * Shizuku instantiates this via reflection, requiring both a no-arg and a
 * context-taking constructor. @JvmOverloads generates the two overloads;
 * @Keep prevents R8 from removing them.
 */
@Keep
class CommandRunner @JvmOverloads constructor(private val context: Context? = null) : ICommandRunner.Stub() {

    override fun runCommand(command: Array<String>): String {
        return try {
            Log.i("CommandRunner", "exec: ${command.joinToString(" ")}")
            runProcess(command)
        } catch (t: Throwable) {
            "ERROR: $t"
        }
    }

    private fun runProcess(command: Array<String>): String {
        val process = Runtime.getRuntime().exec(command)
        val out = readAll(process.inputStream)
        val err = readAll(process.errorStream)
        val exit = process.waitFor()
        val sb = StringBuilder()
        sb.append("exit=").append(exit)
        if (out.isNotEmpty()) sb.append('\n').append(out)
        if (err.isNotEmpty()) sb.append('\n').append(err)
        Log.i("CommandRunner", "result: $sb")
        return sb.toString()
    }

    private fun readAll(stream: java.io.InputStream): String {
        val reader = BufferedReader(InputStreamReader(stream))
        return reader.readText()
    }
}
