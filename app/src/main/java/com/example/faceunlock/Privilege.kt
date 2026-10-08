package com.example.faceunlock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/**
 * How the app is allowed to call device-owner APIs (deprecated keyguard /
 * persistent-HOME operations):
 *
 *  - SELF_OWNER: this app is the device owner. Direct DPM calls with our own
 *    MyDeviceAdminReceiver (the classic adb `dpm set-device-owner` setup).
 *  - DHIZUKU:    Dhizuku (com.rosan.dhizuku) is the device owner and granted the
 *    app permission. DPM calls are relayed through Dhizuku using
 *    Dhizuku.binderWrapper() on the raw IDevicePolicyManager binder.
 *  - NONE:       nothing available; PIN/long-press flows degrade gracefully.
 *
 * Shizuku itself cannot perform device-owner DPM calls (a shell uid is not an
 * active admin), so it is only used as a helper to run `dpm set-device-owner`
 * through a user service, turning the app into SELF_OWNER.
 */
enum class PrivilegeMode { SELF_OWNER, DHIZUKU, NONE }

object Privilege {

    const val REQ_CODE_SHIZUKU_PERMISSION = 1001

    fun mode(context: Context): PrivilegeMode {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm.isDeviceOwnerApp(context.packageName)) return PrivilegeMode.SELF_OWNER
        return if (dhizukuReady(context)) PrivilegeMode.DHIZUKU else PrivilegeMode.NONE
    }

    fun isOpsAvailable(context: Context): Boolean = mode(context) != PrivilegeMode.NONE

    fun describe(context: Context): String {
        return when (mode(context)) {
            PrivilegeMode.SELF_OWNER -> context.getString(R.string.priv_self_owner)
            PrivilegeMode.DHIZUKU -> {
                val owner = try {
                    Dhizuku.getOwnerPackageName()
                } catch (t: Throwable) {
                    "com.rosan.dhizuku"
                }
                context.getString(R.string.priv_dhizuku, owner)
            }
            PrivilegeMode.NONE -> context.getString(R.string.priv_none)
        }
    }

    fun dhizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.rosan.dhizuku", 0)
            true
        } catch (t: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------- operations

    fun pinAsDefaultHome(context: Context, filter: IntentFilter, activity: ComponentName): Boolean {
        return when (mode(context)) {
            PrivilegeMode.SELF_OWNER -> SelfOwner(context).pinAsDefaultHome(filter, activity)
            PrivilegeMode.DHIZUKU -> DhizukuOwner().pinAsDefaultHome(filter, activity)
            PrivilegeMode.NONE -> {
                Logger.w("pinAsDefaultHome skipped: no privilege")
                false
            }
        }
    }

    fun clearDefaultHome(context: Context, packageName: String): Boolean {
        return when (mode(context)) {
            PrivilegeMode.SELF_OWNER -> SelfOwner(context).clearDefaultHome(packageName)
            PrivilegeMode.DHIZUKU -> DhizukuOwner().clearDefaultHome(packageName)
            PrivilegeMode.NONE -> {
                Logger.w("clearDefaultHome skipped: no privilege")
                false
            }
        }
    }

    fun setKeyguardDisabled(context: Context, disabled: Boolean): Boolean {
        return when (mode(context)) {
            PrivilegeMode.SELF_OWNER -> SelfOwner(context).setKeyguardDisabled(disabled)
            PrivilegeMode.DHIZUKU -> DhizukuOwner().setKeyguardDisabled(disabled)
            PrivilegeMode.NONE -> {
                Logger.w("setKeyguardDisabled skipped: no privilege")
                false
            }
        }
    }

    // ------------------------------------------------------------- authorization

    /** Best-effort: connect Shizuku then request Dhizuku permission if present. */
    fun connect(context: Context) {
        requestShizukuPermission(context)
        if (dhizukuInstalled(context)) requestDhizukuPermission(context)
    }

    /** Returns true if permission was granted this call, false if dialog shown /
     *  unavailable. Result may arrive asynchronously via onRequestPermissionResult. */
    fun requestShizukuPermission(context: Context): Boolean {
        if (!Shizuku.pingBinder()) {
            Logger.w("shizuku not running")
            return false
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Logger.i("shizuku permission already granted")
            return true
        }
        return try {
            Logger.i("requesting shizuku permission")
            Shizuku.requestPermission(REQ_CODE_SHIZUKU_PERMISSION)
            false
        } catch (t: Throwable) {
            Logger.e("request shizuku permission failed: $t")
            false
        }
    }

    fun requestDhizukuPermission(context: Context): Boolean {
        if (!dhizukuInstalled(context)) {
            Logger.w("dhizuku not installed")
            return false
        }
        return try {
            if (!Dhizuku.init(context)) {
                Logger.e("dhizuku init failed")
                return false
            }
            if (Dhizuku.isPermissionGranted()) {
                Logger.i("dhizuku permission already granted")
                return true
            }
            Logger.i("requesting dhizuku permission")
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    Logger.i("dhizuku request result: grant=$grantResult")
                }
            })
            false
        } catch (t: Throwable) {
            Logger.e("request dhizuku permission failed: $t")
            false
        }
    }

    /**
     * Uses a Shizuku user service to run `dpm set-device-owner` as the shell
     * user, then verifies the result. No-op when Shizuku permission is missing.
     */
    fun setMeAsDeviceOwnerViaShizuku(context: Context, onDone: (Boolean, String) -> Unit) {
        if (!Shizuku.pingBinder()) {
            onDone(false, context.getString(R.string.shizuku_not_running))
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            onDone(false, context.getString(R.string.shizuku_need_permission))
            return
        }
        val args = Shizuku.UserServiceArgs(ComponentName(context, CommandRunner::class.java))
            .daemon(false)
            .processNameSuffix("command")
            .version(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val runner = ICommandRunner.Stub.asInterface(binder)
                    val result = runner.runCommand(arrayOf("dpm", "set-device-owner", context.packageName, ".MyDeviceAdminReceiver"))
                    val dpm = context.getSystemService(DevicePolicyManager::class.java)
                    val isOwner = dpm.isDeviceOwnerApp(context.packageName)
                    Logger.i("set-device-owner done. ownerNow=$isOwner result=$result")
                    onDone(
                        isOwner,
                        if (isOwner) context.getString(R.string.owner_set_ok)
                        else context.getString(R.string.owner_set_failed, result)
                    )
                } catch (t: Throwable) {
                    Logger.e("run owner command failed: $t")
                    onDone(false, context.getString(R.string.owner_set_failed, "$t"))
                } finally {
                    try {
                        Shizuku.unbindUserService(args, this, true)
                    } catch (t: Throwable) {
                        Logger.w("unbind user service: $t")
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onDone(false, context.getString(R.string.shizuku_service_lost))
            }
        }
        try {
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            Logger.e("bind shizuku user service failed: $t")
            onDone(false, "$t")
        }
    }

    // ------------------------------------------------------------- backends

    private fun dhizukuReady(context: Context): Boolean {
        return try {
            Dhizuku.init(context) && Dhizuku.isPermissionGranted()
        } catch (t: Throwable) {
            Logger.w("dhizuku not ready: $t")
            false
        }
    }

    private class SelfOwner(private val context: Context) {
        private val dpm by lazy { context.getSystemService(DevicePolicyManager::class.java) }
        private val admin get() = ComponentName(context, MyDeviceAdminReceiver::class.java)

        fun pinAsDefaultHome(filter: IntentFilter, activity: ComponentName): Boolean {
            return try {
                dpm.addPersistentPreferredActivity(admin, filter, activity)
                true
            } catch (t: Throwable) {
                Logger.e("self pin home failed: $t")
                false
            }
        }

        fun clearDefaultHome(packageName: String): Boolean {
            return try {
                dpm.clearPackagePersistentPreferredActivities(admin, packageName)
                true
            } catch (t: Throwable) {
                Logger.e("self clear home failed: $t")
                false
            }
        }

        fun setKeyguardDisabled(disabled: Boolean): Boolean {
            return try {
                dpm.setKeyguardDisabled(admin, disabled)
                true
            } catch (t: Throwable) {
                Logger.e("self keyguard failed: $t")
                false
            }
        }
    }

    /**
     * DPM calls relayed through Dhizuku. The raw IDevicePolicyManager binder is
     * wrapped with Dhizuku.binderWrapper(), resurrected as a proxy, then called
     * via reflection with the version-aware signatures. Invocations run with
     * the Dhizuku uid (the real device owner), so the admin component passed to
     * the system must be Dhizuku's own owner component.
     */
    private class DhizukuOwner {
        private val ownerComponent: ComponentName = Dhizuku.getOwnerComponent()
        private val ownerPackage: String = Dhizuku.getOwnerPackageName()
        private val proxy: Any

        init {
            val wrapped: IBinder = Dhizuku.binderWrapper(rawDpmBinder())
            val stubClass = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            proxy = asInterface.invoke(null, wrapped)!!
        }

        private fun rawDpmBinder(): IBinder {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            return getService.invoke(null, "device_policy") as IBinder
        }

        private fun method(name: String, vararg types: Class<*>): Method {
            val interfaceClass = Class.forName("android.app.admin.IDevicePolicyManager")
            return interfaceClass.getMethod(name, *types)
        }

        fun pinAsDefaultHome(filter: IntentFilter, activity: ComponentName): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val m = method(
                        "addPersistentPreferredActivity",
                        ComponentName::class.java, String::class.java,
                        IntentFilter::class.java, ComponentName::class.java
                    )
                    m.invoke(proxy, ownerComponent, ownerPackage, filter, activity)
                } else {
                    val m = method(
                        "addPersistentPreferredActivity",
                        ComponentName::class.java, IntentFilter::class.java,
                        ComponentName::class.java
                    )
                    m.invoke(proxy, ownerComponent, filter, activity)
                }
                true
            } catch (t: Throwable) {
                Logger.e("dhizuku pin home failed: $t")
                false
            }
        }

        fun clearDefaultHome(packageName: String): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val m = method(
                        "clearPackagePersistentPreferredActivities",
                        ComponentName::class.java, String::class.java, String::class.java
                    )
                    m.invoke(proxy, ownerComponent, ownerPackage, packageName)
                } else {
                    val m = method(
                        "clearPackagePersistentPreferredActivities",
                        ComponentName::class.java, String::class.java
                    )
                    m.invoke(proxy, ownerComponent, packageName)
                }
                true
            } catch (t: Throwable) {
                Logger.e("dhizuku clear home failed: $t")
                false
            }
        }

        fun setKeyguardDisabled(disabled: Boolean): Boolean {
            return try {
                val m = method(
                    "setKeyguardDisabled",
                    ComponentName::class.java, Boolean::class.java
                )
                m.invoke(proxy, ownerComponent, disabled)
                true
            } catch (t: Throwable) {
                Logger.e("dhizuku keyguard failed: $t")
                false
            }
        }
    }
}
