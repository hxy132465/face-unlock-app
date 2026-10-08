package com.example.faceunlock

import android.app.Application
import android.content.Context
import android.os.Build
import rikka.shizuku.Shizuku

class FaceUnlockApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Allow calling hidden framework APIs (ServiceManager, IDevicePolicyManager
        // stubs) that Shizuku / Dhizuku based apps rely on. No new privileges on
        // top of what the OS already grants to the calling uid.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Logger.i("shizuku binder received")
            }
        })
        Shizuku.addBinderDeadListener(object : Shizuku.OnBinderDeadListener {
            override fun onBinderDead() {
                Logger.w("shizuku binder dead")
            }
        })
    }
}
