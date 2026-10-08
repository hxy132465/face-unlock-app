package com.example.faceunlock

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin receiver so the app can act as Device Owner, which grants the
 * rights used by the fake-desktop flow:
 *   - disable the system keyguard (setKeyguardDisabled)
 *   - (un)pin this app as the default CATEGORY_HOME launcher
 */
class MyDeviceAdminReceiver : DeviceAdminReceiver()
