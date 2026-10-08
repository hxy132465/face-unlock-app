# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-10-07
- Context: Discovered by Agent while sourcing the face-recognition model for the Android Face Unlock app
- Category: Troubleshooting & Debugging
- Instructions:
  - Qualcomm AI Hub ships only a float TFLite for mobile_facenet; tflite-int8.zip and tflite-w8a8.zip both return 403 / do not exist. Do not re-attempt ; the walkaround is the standard single-input float MobileFaceNet.
  - The standard Model to use: estebanuri/face_recognition mobile_face_net.tflite, I/O [1,112,112,3] float, output [1,192] float normalized [-1,1] preprocess (p-127.5)/128.
  - tflite_runtime requires numpy<2 for importing a model; use `pip install --break-system-packages numpy==1.26.4` in this sandbox.
   - No JDK/Android SDK in /workspace sandbox — cannot compile the app locally.

[Project Knowledge Summary]
- Date: 2026-10-07
- Context: Discovered by Agent while integrating Shizuku and Dhizuku authorization modes for the Face Unlock Android app
- Category: Build Methods|Troubleshooting & Debugging
- Instructions:
  - This project's AAPT2 (build-tools 34.0.0) rejects `<provider>` elements placed directly under `<manifest>` root; they must be placed INSIDE `<application>` to link successfully. The `<provider>` is still functionally registered at the manifest level.
  - Shizuku API v13 (`dev.rikka.shizuku:api:13.1.5` + `dev.rikka.shizuku:provider:13.1.5`) requires `ShizukuProvider` registered in the manifest and `Shizuku.addBinderReceivedListenerSticky` + `addRequestPermissionResultListener` in code; `Shizuku.bindUserService(UserServiceArgs, ServiceConnection)` is the supported path for running code in the Shizuku server process (the `Shizuku.newProcess` helper was made private in v13).
  - Dhizuku-API (`io.github.iamr0s:Dhizuku-API:2.5.3`) shares device-owner privileges through `Dhizuku.binderWrapper(rawBinder)`. To call DPM device-owner methods via Dhizuku, wrap the raw `IDevicePolicyManager` binder obtained via `ServiceManager.getService("device_policy")`, then invoke by reflection using `Class.forName("android.app.admin.IDevicePolicyManager")` method lookups — the AOSP framework class is loaded from the boot classpath and shadows any stubs in the app dex.
  - The Dhizuku relay requires passing `Dhizuku.getOwnerComponent()` (not the client's own DeviceAdminReceiver) as the `admin` parameter for every DPM call; otherwise AOSP's `getCallerIdentity(who)` throws `SecurityException` because the admin's uid won't match the caller uid (Dhizuku's).
  - On Android < 34, `addPersistentPreferredActivity` / `clearPackagePersistentPreferredActivities` have 3-arg / 2-arg signatures respectively; on Android >= 34 they gain a `callerPackageName` argument (4-arg / 3-arg). The client must branch on `Build.VERSION.SDK_INT >= 34` when calling the reflected interface.
  - DPM via Shizuku shell is NOT viable: `dpm set-device-owner` can be run through a Shizuku user service (as implemented), but plain Shizuku shell uid cannot call any DPM device-owner method because `getCallerIdentity` requires the caller to be the active admin (shell is not a device admin).
  - Add `org.lsposed.hiddenapibypass:hiddenapibypass:4.3` and call `HiddenApiBypass.addHiddenApiExemptions("L")` in `Application.attachBaseContext` to make reflection onto `ServiceManager`, `IDevicePolicyManager$Stub`, and related hidden classes work on Android 9+.
  - Gradle AGP requires `buildFeatures { aidl true }` (explicit) for AIDL compilation in this project configuration; otherwise AIDL files are silently skipped.
  - Kotlin `class X : JavaStub()` with secondary constructors needs either `@JvmOverloads constructor(ctx: Context? = null)` or an explicit no-arg primary constructor delegating properly; the working pattern is `class CommandRunner @JvmOverloads constructor(context: Context? = null) : ICommandRunner.Stub()`.
