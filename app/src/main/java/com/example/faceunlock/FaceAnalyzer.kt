package com.example.faceunlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.atan2
import kotlin.math.max

/**
 * CameraX analyzer: ML Kit face detection + MobileFaceNet embedding.
 * Emits one embedding per detected face; applies a dynamic frame-rate policy
 * (fast while a face is present, backed off when idle) to save power.
 */
class FaceAnalyzer(
    context: Context,
    private val embedder: MobileFaceNet,
    private val onFace: (FloatArray, Rect) -> Unit,
    private val onNoFace: () -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val ACTIVE_INTERVAL_MS = 90L
        private const val IDLE_INTERVAL_MAX_MS = 700L
        private const val IDLE_BACKOFF_STEP_MS = 120L
        private const val BBOX_MARGIN = 1.15f
    }

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.2f)
            .enableTracking()
            .build()
    )

    @Volatile
    var running: Boolean = true

    private var lastAnalysisAt = 0L
    private var currentInterval = ACTIVE_INTERVAL_MS

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!running || now - lastAnalysisAt < currentInterval) {
            image.close()
            return
        }
        lastAnalysisAt = now

        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }

        // Feed the raw Image buffer straight to ML Kit; no Bitmap conversion on
        // the per-frame hot path. The frame is only decoded to pixels later, and
        // only when a face is actually present and needs alignment.
        detector.process(
            InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        ).addOnSuccessListener { faces ->
            try {
                val face = faces.maxByOrNull {
                    it.boundingBox.width().toLong() * it.boundingBox.height()
                }
                if (face == null) {
                    slowDown()
                    onNoFace()
                } else {
                    speedUp()
                    val bitmap = buildUprightBitmap(image)
                    val aligned = align(bitmap, face)
                    onFace(embedder.getEmbedding(aligned), face.boundingBox)
                }
            } catch (t: Throwable) {
                onNoFace()
            } finally {
                image.close()
            }
        }.addOnFailureListener {
            image.close()
            onNoFace()
        }
    }

    fun close() {
        running = false
        detector.close()
    }

    private fun speedUp() {
        currentInterval = ACTIVE_INTERVAL_MS
    }

    private fun slowDown() {
        currentInterval = (currentInterval + IDLE_BACKOFF_STEP_MS)
            .coerceAtMost(IDLE_INTERVAL_MAX_MS)
    }

    private fun buildUprightBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowStridePixels = plane.rowStride / pixelStride
        val width = image.width
        val height = image.height

        val padded = Bitmap.createBitmap(rowStridePixels, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = if (rowStridePixels == width) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, width, height)
        }

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return cropped
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(cropped, 0, 0, width, height, matrix, true)
    }

    private fun align(bitmap: Bitmap, face: Face): Bitmap {
        val box = face.boundingBox

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

        var angle = 0f
        if (leftEye != null && rightEye != null && (leftEye.x != rightEye.x || leftEye.y != rightEye.y)) {
            angle = Math.toDegrees(atan2(
                (rightEye.y - leftEye.y).toDouble(),
                (rightEye.x - leftEye.x).toDouble()
            )).toFloat()
        }

        val cx = box.exactCenterX()
        val cy = box.exactCenterY()
        val side = max(box.width(), box.height()).toFloat() * BBOX_MARGIN

        val matrix = Matrix()
        matrix.postTranslate(-cx, -cy)
        matrix.postRotate(-angle)
        val scale = embedder.inputSize / side
        matrix.postScale(scale, scale)
        matrix.postTranslate(embedder.inputSize / 2f, embedder.inputSize / 2f)

        val out = Bitmap.createBitmap(embedder.inputSize, embedder.inputSize, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            bitmap,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        return out
    }
}
