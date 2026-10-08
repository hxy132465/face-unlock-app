package com.example.faceunlock

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * MobileFaceNet (TFLite) wrapper for the standard single-input embedder.
 *
 * Reference model (bundled as `mobile_facenet.tflite`):
 *   - input : "input" [1, 112, 112, 3]  float32 NHWC
 *   - output: "embeddings" [1, 192]     float32, already L2-normalized
 *   - normalization: (pixel - 127.5) / 128      (range [-1, 1])
 * Source: https://github.com/estebanuri/face_recognition
 *   (assets/mobile_face_net.tflite, Apache-compatible original MobileFaceNet)
 *
 * The wrapper still detects the actual tensor layout (NHWC / NCHW), the number
 * of input tensors (single or siamese dual input), and float32 / uint8 / int8
 * dtypes, so a different MobileFaceNet file can be dropped in unchanged as long
 * as it follows the [-1, 1] normalization convention above.
 */
class MobileFaceNet(context: Context, modelAsset: String = MODEL_ASSET) {

    companion object {
        const val MODEL_ASSET = "mobile_facenet.tflite"
        const val NUM_THREADS = 2
    }

    private val interpreter: Interpreter

    val inputSize: Int
    val embeddingSize: Int

    private val inputCount: Int
    private val nchw: Boolean
    private val channels: Int

    private val inputDataType: DataType
    private val inputScale: Float
    private val inputZero: Int

    private val outputDataType: DataType
    private val outputScale: Float
    private val outputZero: Int

    // Reused across frames to avoid GC pressure on low-end devices.
    private val inputBuffers: Array<ByteBuffer>
    private val outputBuffer: ByteBuffer
    private val pixelScratch: IntArray

    init {
        interpreter = Interpreter(
            loadModel(context, modelAsset),
            Interpreter.Options().apply { setNumThreads(NUM_THREADS) }
        )

        val inTensor = interpreter.getInputTensor(0)
        val shape = inTensor.shape()
        require(shape.size == 4) { "Expected 4D input, got ${shape.toList()}" }

        if (shape[1] == 3) {
            nchw = true
            channels = shape[1]
            inputSize = shape[2]
        } else if (shape[3] == 3) {
            nchw = false
            channels = shape[3]
            inputSize = shape[1]
        } else {
            throw IllegalArgumentException("Unsupported input layout ${shape.toList()}")
        }

        inputDataType = inTensor.dataType()
        inputScale = inTensor.quantizationParams().scale
        inputZero = inTensor.quantizationParams().zeroPoint

        inputCount = interpreter.inputTensorCount
        val bytesPerInput = inputSize * inputSize * channels * bytesOf(inputDataType)
        inputBuffers = Array(inputCount) {
            ByteBuffer.allocateDirect(bytesPerInput).order(ByteOrder.nativeOrder())
        }

        val outTensor = interpreter.getOutputTensor(0)
        val outShape = outTensor.shape()
        embeddingSize = outShape[outShape.size - 1]
        outputDataType = outTensor.dataType()
        outputScale = outTensor.quantizationParams().scale
        outputZero = outTensor.quantizationParams().zeroPoint

        var outElements = 1
        for (d in outShape) outElements *= d
        outputBuffer = ByteBuffer
            .allocateDirect(outElements * bytesOf(outputDataType))
            .order(ByteOrder.nativeOrder())

        pixelScratch = IntArray(inputSize * inputSize)
    }

    fun getEmbedding(bitmap: Bitmap): FloatArray {
        val src = if (bitmap.width == inputSize &&
            bitmap.height == inputSize &&
            bitmap.config == Bitmap.Config.ARGB_8888
        ) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        }

        src.getPixels(pixelScratch, 0, inputSize, 0, 0, inputSize, inputSize)

        for (buffer in inputBuffers) fillInput(buffer)
        for (buffer in inputBuffers) buffer.rewind()
        outputBuffer.rewind()

        interpreter.runForMultipleInputsOutputs(
            Array<Any>(inputCount) { inputBuffers[it] },
            mutableMapOf<Int, Any>(0 to outputBuffer)
        )

        val raw = FloatArray(embeddingSize)
        outputBuffer.rewind()
        when (outputDataType) {
            DataType.FLOAT32 -> for (i in raw.indices) raw[i] = outputBuffer.float

            DataType.UINT8 -> for (i in raw.indices) {
                val q = outputBuffer.get().toInt() and 0xFF
                raw[i] = (q - outputZero) * outputScale
            }

            DataType.INT8 -> for (i in raw.indices) {
                val q = outputBuffer.get().toInt()
                raw[i] = (q - outputZero) * outputScale
            }

            else -> throw IllegalStateException("Unsupported output type $outputDataType")
        }

        return l2Normalize(raw)
    }

    fun close() = interpreter.close()

    private fun fillInput(buffer: ByteBuffer) {
        buffer.rewind()
        if (nchw) {
            // Planar layout: all R, then G, then B
            putPlane(buffer, 16)
            putPlane(buffer, 8)
            putPlane(buffer, 0)
        } else {
            for (p in pixelScratch) {
                putChannel(buffer, (p shr 16) and 0xFF)
                putChannel(buffer, (p shr 8) and 0xFF)
                putChannel(buffer, p and 0xFF)
            }
        }
    }

    private fun putPlane(buffer: ByteBuffer, shift: Int) {
        for (p in pixelScratch) putChannel(buffer, (p shr shift) and 0xFF)
    }

    private fun putChannel(buffer: ByteBuffer, channel: Int) {
        when (inputDataType) {
            DataType.FLOAT32 -> buffer.putFloat((channel - 127.5f) / 128f)
            DataType.UINT8 -> buffer.put(quantize(channel, 0, 255))
            DataType.INT8 -> buffer.put(quantize(channel, -128, 127))
            else -> throw IllegalStateException("Unsupported input type $inputDataType")
        }
    }

    private fun quantize(channel: Int, min: Int, max: Int): Byte {
        val real = (channel - 127.5f) / 128f
        val q = Math.round(real / inputScale + inputZero)
        return q.coerceIn(min, max).toByte()
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum).toFloat().coerceAtLeast(1e-6f)
        for (i in v.indices) v[i] /= norm
        return v
    }

    private fun bytesOf(type: DataType) = if (type == DataType.FLOAT32) 4 else 1

    private fun loadModel(context: Context, asset: String): MappedByteBuffer {
        context.assets.openFd(asset).use { afd ->
            FileInputStream(afd.fileDescriptor).use { fis ->
                return fis.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
            }
        }
    }
}
