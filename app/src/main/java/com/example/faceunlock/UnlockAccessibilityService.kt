package com.example.faceunlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent

/**
 * Performs the unlock gesture (swipe up) when a recognized face is reported.
 *
 * Note: on a device with a secure lock screen (PIN/pattern/password) this only
 * reveals the credential prompt. Android intentionally prevents a normal app
 * from bypassing the secure lock, so the system password is always preserved.
 */
class UnlockAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: UnlockAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Wakes the screen if needed and swipes up from lower to upper screen.
     * @return true if the gesture was dispatched.
     */
    fun performUnlockSwipe(): Boolean {
        wakeScreen()

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val path = Path().apply {
            moveTo(width * 0.5f, height * 0.85f)
            lineTo(width * 0.5f, height * 0.25f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    private fun wakeScreen() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isInteractive) return
        val wakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "faceunlock:wake"
        )
        wakeLock.acquire(3000L)
    }
}
