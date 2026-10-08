package com.example.faceunlock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that keeps the front camera analysing while the app is not
 * in the foreground. On a match it asks the accessibility service to unlock.
 */
class FaceUnlockService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "face_unlock_channel"
        const val NOTIFICATION_ID = 1001
        private const val MIN_UNLOCK_INTERVAL_MS = 1500L
    }

    private var embedder: MobileFaceNet? = null
    private var analyzer: FaceAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var analysisExecutor: ExecutorService

    private val matcher = FaceMatcher()

    @Volatile
    private var reference: FloatArray? = null

    private var lastUnlockAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // Pause analysis instead of unbinding: keeps the camera stream warm
                // so re-binding races (which can stall unlock after screen-on) are
                // avoided, while still dropping the heavy ML/TFLite work on idle.
                Intent.ACTION_SCREEN_OFF -> {
                    matcher.reset()
                    analyzer?.running = false
                }
                Intent.ACTION_SCREEN_ON -> {
                    matcher.reset()
                    analyzer?.running = true
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        analysisExecutor = Executors.newSingleThreadExecutor()

        reference = FaceStore.load(this)
        if (reference == null) {
            stopSelf()
            return
        }

        val net = MobileFaceNet(this)
        embedder = net
        analyzer = FaceAnalyzer(
            context = this,
            embedder = net,
            onFace = { embedding, _ -> handleEmbedding(embedding) },
            onNoFace = { }
        )
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        cameraProvider?.unbindAll()
        analyzer?.close()
        embedder?.close()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun handleEmbedding(embedding: FloatArray) {
        val ref = reference ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnlockAt < MIN_UNLOCK_INTERVAL_MS) return

        val matched = matcher.match(embedding, ref)
        if (matched) {
            val unlocks = UnlockAccessibilityService.instance
            if (unlocks != null && unlocks.performUnlockSwipe()) {
                lastUnlockAt = now
            }
            matcher.reset()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(256, 256),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(selector)
                    .build()
                analysis.setAnalyzer(analysisExecutor, analyzer!!)

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (t: Throwable) {
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_stat_face)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // ServiceCompat ignores the type on API < 29.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
    }
}
