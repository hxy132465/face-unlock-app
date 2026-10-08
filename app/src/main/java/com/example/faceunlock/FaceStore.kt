package com.example.faceunlock

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Persists the enrolled face template in app-private storage (offline only).
 */
object FaceStore {

    private const val FILE_NAME = "face_template.bin"
    private const val MAX_DIM = 4096

    fun save(context: Context, embedding: FloatArray): Boolean = try {
        DataOutputStream(BufferedOutputStream(context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)))
            .use { out ->
                out.writeInt(embedding.size)
                for (v in embedding) out.writeFloat(v)
            }
        true
    } catch (e: IOException) {
        false
    }

    fun load(context: Context): FloatArray? = try {
        DataInputStream(BufferedInputStream(context.openFileInput(FILE_NAME))).use { input ->
            val size = input.readInt()
            if (size <= 0 || size > MAX_DIM) {
                null
            } else {
                val embedding = FloatArray(size)
                for (i in 0 until size) embedding[i] = input.readFloat()
                embedding
            }
        }
    } catch (e: IOException) {
        null
    }

    fun exists(context: Context): Boolean = context.getFileStreamPath(FILE_NAME).exists()

    fun clear(context: Context): Boolean = context.deleteFile(FILE_NAME)
}
