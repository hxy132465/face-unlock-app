package com.example.faceunlock

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.faceunlock.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Dual-mode entry point:
 *
 *  1. Fake desktop (launched as CATEGORY_HOME): black screen + face recognition.
 *     On match, clears the app's HOME role and launches the real desktop.
 *     Includes escape hatches: 3s long-press or 5 consecutive taps -> password
 *     dialog; 5 consecutive recognition failures -> auto-release to real home.
 *     Runs a startup self-check and bails out to the real home if anything is
 *     wrong (missing model, no camera permission, no escape password).
 *
 *  2. Setup screen (launched via launcher icon): enroll the face, set the escape
 *     password, pin the app as default HOME, view logs, or jump to the real home.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val ENROLL_TARGET = 8
        const val FAIL_THRESHOLD = 5
        const val AUTO_UNLOCK_TIMEOUT_MS = 20000L
        const val ESCAPE_TAPS = 5
        const val ESCAPE_TAP_WINDOW_MS = 2000L
        const val LONG_PRESS_MS = 3000L
        const val MODEL_ASSET = "mobile_facenet.tflite"
    }

    private lateinit var binding: ActivityMainBinding
    private var embedder: MobileFaceNet? = null
    private var analyzer: FaceAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null

    private val matcher = FaceMatcher()

    private var enrolling = false
    private val enrollSamples = mutableListOf<FloatArray>()
    private var reference: FloatArray? = null
    private var failCount = 0

    // Escape gesture state
    private var lastTapAt = 0L
    private var tapCount = 0
    private var pressStartedAt = 0L
    private var lockReleased = false
    private var releaseRequested = false
    private val autoUnlockTimeoutMs = 20000L

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoUnlockRunnable = Runnable {
        Logger.w("auto unlock after 20s: no successful recognition")
        releaseToRealHome()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        Logger.i("shizuku permission result code=$requestCode grant=$grantResult")
        if (requestCode == Privilege.REQ_CODE_SHIZUKU_PERMISSION) {
            runOnUiThread {
                updatePrivilegeStatus()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    toast(getString(R.string.shizuku_granted))
                } else {
                    toast(getString(R.string.shizuku_denied))
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true ||
            hasPermission(Manifest.permission.CAMERA)
        if (cameraGranted) {
            if (enrolling) startCameraOnly()
        } else {
            toast(getString(R.string.need_camera_permission))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(this)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        if (isHomeLaunch()) {
            onCreateFakeDesktop()
        } else {
            onCreateSetup()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHomeLaunch()) {
            updateStatus()
            updatePrivilegeStatus()
            if (enrolling && hasPermission(Manifest.permission.CAMERA)) startCameraOnly()
        }
    }

    override fun onPause() {
        unbindCamera()
        if (!isHomeLaunch() && enrolling) enrolling = false
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        releaseResources()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup mode

    private fun onCreateSetup() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        saveDefaultHomePackageIfNeeded()

        binding.btnEnroll.setOnClickListener { startEnrollment() }
        binding.btnSetPassword.setOnClickListener { promptSetPassword() }
        binding.btnPinHome.setOnClickListener { pinAsDefaultHome() }
        binding.btnGoHome.setOnClickListener { launchRealHome() }
        binding.btnLogs.setOnClickListener { showLogs() }
        binding.btnClear.setOnClickListener { clearTemplate() }
        binding.btnAuthorize.setOnClickListener { onAuthorizeClick() }
        binding.btnShizukuOwner.setOnClickListener { onShizukuOwnerClick() }

        requestPermissionsIfNeeded()
        updatePrivilegeStatus()
    }

    private fun requestPermissionsIfNeeded() {
        if (hasPermission(Manifest.permission.CAMERA)) return
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private fun startEnrollment() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            requestPermissionsIfNeeded()
            return
        }
        synchronized(enrollSamples) { enrollSamples.clear() }
        enrolling = true
        ensureAnalysisTooling()
        binding.statusText.text = getString(R.string.enroll_progress, 0, ENROLL_TARGET)
        startCameraOnly()
    }

    private fun onFaceEnrolled(embedding: FloatArray) {
        if (!enrolling) return
        var complete = false
        synchronized(enrollSamples) {
            if (enrollSamples.size < ENROLL_TARGET) {
                enrollSamples.add(embedding)
                val count = enrollSamples.size
                binding.statusText.text = getString(R.string.enroll_progress, count, ENROLL_TARGET)
                complete = count >= ENROLL_TARGET
            }
        }
        if (complete) finishEnrollment()
    }

    private fun finishEnrollment() {
        val samples = synchronized(enrollSamples) { enrollSamples.toList() }
        enrolling = false
        unbindCamera()

        val net = embedder
        if (net == null || samples.isEmpty()) {
            toast(getString(R.string.enroll_failed))
            return
        }
        val dim = net.embeddingSize
        val avg = FloatArray(dim)
        for (sample in samples) {
            for (i in 0 until dim) avg[i] += sample[i]
        }
        var sumSq = 0.0
        for (v in avg) sumSq += v.toDouble() * v
        val norm = sqrt(sumSq).toFloat().coerceAtLeast(1e-6f)
        for (i in 0 until dim) avg[i] /= norm

        val ok = FaceStore.save(this, avg)
        Logger.i("enrollment saved=$ok dim=$dim")
        toast(getString(if (ok) R.string.enroll_success else R.string.enroll_failed))
        updateStatus()
    }

    private fun promptSetPassword() {
        showPasswordDialog(getString(R.string.pwd_set_title)) { plain ->
            if (plain.isNotEmpty()) {
                FakePrefs.setPassword(this, plain)
                Logger.i("escape password set")
                toast(getString(R.string.pwd_set_done))
            }
        }
    }

    private fun pinAsDefaultHome() {
        if (!Privilege.isOpsAvailable(this)) {
            toast(getString(R.string.home_not_owner))
            Logger.w("pinHome skipped: no privilege")
            return
        }
        saveDefaultHomePackageIfNeeded()
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val ok = Privilege.pinAsDefaultHome(this, filter, ComponentName(this, MainActivity::class.java))
        if (ok) {
            toast(getString(R.string.home_pinned))
            Logger.i("app pinned as persistent HOME")
        } else {
            toast(getString(R.string.home_not_owner))
            Logger.e("pinHome failed")
        }
        updatePrivilegeStatus()
    }

    private fun clearTemplate() {
        FaceStore.clear(this)
        matcher.reset()
        toast(getString(R.string.template_cleared))
        updateStatus()
    }

    private fun showLogs() {
        val logs = Logger.read().ifEmpty { getString(R.string.logs_placeholder) }
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_logs)
            .setMessage(logs)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updateStatus() {
        if (!::binding.isInitialized) return
        val enrolled = FaceStore.exists(this)
        val password = FakePrefs.isPasswordSet(this)
        binding.statusText.text = getString(
            R.string.status_format,
            getString(if (enrolled) R.string.state_enrolled else R.string.state_not_enrolled),
            getString(if (password) R.string.state_on else R.string.state_off)
        )
    }

    // ------------------------------------------------------------------ privilege

    private fun updatePrivilegeStatus() {
        if (!::binding.isInitialized) return
        binding.privilegeStatus.text = getString(R.string.priv_status, Privilege.describe(this))
    }

    private fun onAuthorizeClick() {
        Privilege.connect(this)
        updatePrivilegeStatus()
        toast(getString(R.string.authorize_hint))
    }

    private fun onShizukuOwnerClick() {
        if (Privilege.mode(this) != PrivilegeMode.NONE) {
            toast(getString(R.string.owner_already_set))
            return
        }
        Privilege.setMeAsDeviceOwnerViaShizuku(this) { ok, message ->
            runOnUiThread {
                toast(message)
                updatePrivilegeStatus()
            }
        }
    }

    private fun onCreateFakeDesktop() {
        Logger.i("fake-desktop started (HOME launch)")

        // Startup self-check: any failure -> straight to real home, no recognition.
        val problem = selfCheck()
        if (problem != null) {
            Logger.w("self-check failed: $problem")
            releaseToRealHome()
            return
        }

        setContentView(R.layout.activity_fake_desktop)
        disableKeyguard()
        setupEscapeGestures()

        reference = FaceStore.load(this)
        if (createRecognitionTooling() == null) {
            Logger.e("recognition tooling init failed")
            releaseToRealHome()
            return
        }
        Logger.i("self-check passed, starting recognition")
        startCameraOnly()
        // Insurance: if no successful recognition within 20s, release to real home.
        uiHandler.postDelayed(autoUnlockRunnable, AUTO_UNLOCK_TIMEOUT_MS)
    }

    private fun selfCheck(): String? {
        val assetOk = try {
            assets.open(MODEL_ASSET).use { true }
        } catch (t: Throwable) {
            false
        }
        if (!assetOk) return getString(R.string.self_check_fail_model)
        if (!hasPermission(Manifest.permission.CAMERA)) return getString(R.string.self_check_fail_camera)
        if (!FakePrefs.isPasswordSet(this)) return getString(R.string.self_check_fail_password)
        return null
    }

    private fun createRecognitionTooling(): MobileFaceNet? {
        // Lazy-create the embedder, analyzer and executor used by recognition.
        if (embedder != null) return embedder
        return try {
            val executor = Executors.newSingleThreadExecutor()
            val net = MobileFaceNet(this)
            val a = FaceAnalyzer(
                context = this,
                embedder = net,
                onFace = { embedding, _ -> onFakeFace(embedding) },
                onNoFace = { }
            )
            embedder = net
            analyzer = a
            analysisExecutor = executor
            net
        } catch (t: Throwable) {
            Logger.e("tooling init failed: $t")
            null
        }
    }

    private fun ensureAnalysisTooling() {
        if (analyzer != null) return
        val executor = Executors.newSingleThreadExecutor()
        val net = MobileFaceNet(this)
        analyzer = FaceAnalyzer(
            context = this,
            embedder = net,
            onFace = { embedding, _ -> onFaceEnrolled(embedding) },
            onNoFace = { }
        )
        embedder = net
        analysisExecutor = executor
    }

    private fun onFakeFace(embedding: FloatArray) {
        val ref = reference
        val net = embedder
        if (ref == null || net == null) return
        if (matcher.match(embedding, ref)) {
            Logger.i("FACE MATCH -> releasing to real home")
            runOnUiThread { releaseToRealHome() }
        } else {
            failCount++
            if (failCount >= FAIL_THRESHOLD) {
                Logger.w("recognition failed $failCount times -> auto bail-out")
                runOnUiThread {
                    toast(getString(R.string.fake_desktop_bail))
                    releaseToRealHome()
                }
            }
        }
    }

    /** 3s long-press or 5 quick taps -> password dialog. */
    private fun setupEscapeGestures() {
        val root = findViewById<View>(R.id.fakeHint).rootView
        root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressStartedAt = SystemClock.elapsedRealtime()
                    v.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (SystemClock.elapsedRealtime() - pressStartedAt >= LONG_PRESS_MS) {
                        onEscapeTriggered()
                    } else {
                        registerTap()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun registerTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapAt > ESCAPE_TAP_WINDOW_MS) tapCount = 0
        lastTapAt = now
        tapCount++
        if (tapCount >= ESCAPE_TAPS) {
            tapCount = 0
            onEscapeTriggered()
        }
    }

    private fun onEscapeTriggered() {
        Logger.i("escape gesture triggered")
        if (!FakePrefs.isPasswordSet(this)) {
            toast(getString(R.string.pwd_empty))
            releaseToRealHome()
            return
        }
        showPasswordDialog(getString(R.string.pwd_title)) { plain ->
            if (FakePrefs.matchesPassword(this, plain)) {
                toast(getString(R.string.pwd_ok))
                releaseToRealHome()
            } else {
                toast(getString(R.string.pwd_wrong))
            }
        }
    }

    private fun showPasswordDialog(title: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.pwd_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ device owner / homes

    private fun disableKeyguard() {
        if (!Privilege.isOpsAvailable(this)) {
            Logger.w("not privileged; keyguard not disabled")
            return
        }
        if (Privilege.setKeyguardDisabled(this, true)) {
            Logger.i("keyguard disabled (${Privilege.mode(this)})")
        } else {
            Logger.e("setKeyguardDisabled failed")
        }
    }

    private fun saveDefaultHomePackageIfNeeded() {
        if (FakePrefs.homePackage(this) != null) return
        val pkg = resolveRealHomePackage()
        if (pkg != null) {
            FakePrefs.setHomePackage(this, pkg)
            Logger.i("saved real home package: $pkg")
            toast(getString(R.string.home_pkg_saved, pkg))
        } else {
            Logger.w("no real home package resolvable")
        }
    }

    private fun resolveRealHomePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val infos = packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return infos
            .map { it.activityInfo.packageName }
            .firstOrNull { it != packageName }
    }

    private fun releaseToRealHome() {
        if (lockReleased) return
        lockReleased = true
        uiHandler.removeCallbacks(autoUnlockRunnable)
        Logger.i("releaseToRealHome()")
        releaseResources()
        if (Privilege.isOpsAvailable(this)) {
            if (Privilege.clearDefaultHome(this, packageName)) {
                Logger.i("persistent HOME role cleared")
            } else {
                Logger.e("clearPackagePersistentPreferredActivities failed")
            }
        }
        launchRealHome()
        finishAndRemoveTask()
    }

    /**
     * Launches the real desktop from the saved package; falls back to the CATEGORY_HOME
     * resolver on any failure.
     */
    private fun launchRealHome() {
        val saved = FakePrefs.homePackage(this)
        var launched = false
        if (!saved.isNullOrEmpty()) {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(saved)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try {
                startActivity(intent)
                launched = true
            } catch (t: Throwable) {
                Logger.w("launch saved home failed: $t")
            }
        }
        if (!launched) {
            val fallback = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try {
                startActivity(fallback)
            } catch (t: Throwable) {
                Logger.e("launch fallback home failed: $t")
            }
        }
    }

    // ------------------------------------------------------------------ camera (ImageAnalysis only)

    private fun startCameraOnly() {
        if (cameraProvider != null || !hasPermission(Manifest.permission.CAMERA)) return
        val executor = analysisExecutor ?: return
        if (analyzer == null) return

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val resolutionSelector = ResolutionSelector.Builder()
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
                    .setResolutionSelector(resolutionSelector)
                    .build()
                analysis.setAnalyzer(executor, analyzer!!)

                provider.unbindAll()
                // Only ImageAnalysis is bound: no Preview is created anywhere.
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (t: Throwable) {
                Logger.e("camera bind failed: $t")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    private fun releaseResources() {
        unbindCamera()
        try { analyzer?.close() } catch (_: Throwable) {}
        analyzer = null
        try { embedder?.close() } catch (_: Throwable) {}
        embedder = null
        try { analysisExecutor?.shutdown() } catch (_: Throwable) {}
        analysisExecutor = null
    }

    private fun isHomeLaunch(): Boolean {
        val i = intent ?: return false
        if (i.action != Intent.ACTION_MAIN) return false
        return i.categories?.contains(Intent.CATEGORY_HOME) == true
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
