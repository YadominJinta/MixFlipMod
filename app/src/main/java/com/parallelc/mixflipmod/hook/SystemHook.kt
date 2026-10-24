package com.parallelc.mixflipmod.hook

import android.content.ComponentName
import android.graphics.Rect
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.DisplayCutout
import android.view.WindowInsets
import com.parallelc.mixflipmod.Prefs
import com.parallelc.mixflipmod.Prefs.FlipScreenMode
import com.parallelc.mixflipmod.hook.util.after
import com.parallelc.mixflipmod.hook.util.callMethod
import com.parallelc.mixflipmod.hook.util.createDexKitBridge
import com.parallelc.mixflipmod.hook.util.findClass
import com.parallelc.mixflipmod.hook.util.getField
import com.parallelc.mixflipmod.hook.util.hook
import com.parallelc.mixflipmod.hook.util.log
import com.parallelc.mixflipmod.hook.util.method
import com.parallelc.mixflipmod.hook.util.runWithCleanup
import com.parallelc.mixflipmod.hook.util.safeHook
import com.parallelc.mixflipmod.module
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

object SystemHook {

    fun hook(param: SystemServerStartingParam) {
        safeHook("[android] prefs cache") { initSystemPrefsCache() }
        safeHook("[android] ${Prefs.SYSTEM_COMPAT_CONFIG}") { hookCompatConfig(param) }
        safeHook("[android] ${Prefs.SYSTEM_FLIP_CONTINUITY}") { hookFlipContinuity(param) }
        safeHook("[android] ${Prefs.SYSTEM_FLIP_IME_PKG}") { hookFlipInputMethod(param) }
        safeHook("[android] ${Prefs.SYSTEM_FLIP_SCREEN_MODE}") { hookFlipScreenMode(param) }
        safeHook("[android] ${Prefs.SYSTEM_FLIP_IME_INSETS_FIX}") { hookFlipImeInsetsFix(param) }
    }

    // ── Prefs cache ──────────────────────────────────────────────

    private fun initSystemPrefsCache() {
        val prefs = module!!.getRemotePreferences(Prefs.NAME)
        refreshSystemPrefsCache(prefs)
        if (systemPrefsListener == null) {
            systemPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                if (key != null) updateSystemPrefsCache(sharedPreferences, key)
            }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
        }
    }

    private fun refreshSystemPrefsCache(prefs: SharedPreferences) {
        systemCompatConfigEnabled = prefs.getBoolean(Prefs.SYSTEM_COMPAT_CONFIG, false)
        systemFlipContinuityEnabled = prefs.getBoolean(Prefs.SYSTEM_FLIP_CONTINUITY, false)
        systemFlipImeInsetsFixEnabled = prefs.getBoolean(Prefs.SYSTEM_FLIP_IME_INSETS_FIX, false)
        flipInputMethodPackageCache = normalizeFlipInputMethodPackage(
            prefs.getString(Prefs.SYSTEM_FLIP_IME_PKG, Prefs.DEFAULT_FLIP_IME_PKG)
        )
        flipScreenModeCache = prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(Prefs.FLIP_SCREEN_MODE_PREFIX)) return@mapNotNull null
            val modeInt = value as? Int ?: return@mapNotNull null
            val mode = FlipScreenMode.fromPref(modeInt) ?: return@mapNotNull null
            if (mode == FlipScreenMode.DEFAULT) return@mapNotNull null
            Prefs.flipScreenModePackage(key) to mode
        }.toMap()
        flipScreenScaleCache = prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(Prefs.FLIP_SCREEN_SCALE_PREFIX)) return@mapNotNull null
            val scale = (value as? Float) ?: return@mapNotNull null
            Prefs.flipScreenScalePackage(key) to scale
        }.toMap()
    }

    private fun updateSystemPrefsCache(prefs: SharedPreferences, key: String) {
        when {
            key == Prefs.SYSTEM_COMPAT_CONFIG -> {
                systemCompatConfigEnabled = prefs.getBoolean(key, false)
            }
            key == Prefs.SYSTEM_FLIP_CONTINUITY -> {
                systemFlipContinuityEnabled = prefs.getBoolean(key, false)
            }
            key == Prefs.SYSTEM_FLIP_IME_INSETS_FIX -> {
                systemFlipImeInsetsFixEnabled = prefs.getBoolean(key, false)
            }
            key == Prefs.SYSTEM_FLIP_IME_PKG -> {
                flipInputMethodPackageCache = normalizeFlipInputMethodPackage(
                    prefs.getString(key, Prefs.DEFAULT_FLIP_IME_PKG)
                )
                syncFlipInputMethodIfFolded()
            }
            key.startsWith(Prefs.FLIP_SCREEN_MODE_PREFIX) -> {
                val packageName = Prefs.flipScreenModePackage(key)
                val mode = FlipScreenMode.fromPref(prefs.getInt(key, FlipScreenMode.DEFAULT.prefValue))
                    ?: FlipScreenMode.DEFAULT
                val updated = flipScreenModeCache.toMutableMap()
                if (mode == FlipScreenMode.DEFAULT) {
                    updated.remove(packageName)
                } else {
                    updated[packageName] = mode
                }
                flipScreenModeCache = updated
            }
            key.startsWith(Prefs.FLIP_SCREEN_SCALE_PREFIX) -> {
                val packageName = Prefs.flipScreenScalePackage(key)
                val scale = prefs.getFloat(key, Prefs.DEFAULT_FLIP_SCREEN_SCALE)
                val updated = flipScreenScaleCache.toMutableMap()
                updated[packageName] = scale
                flipScreenScaleCache = updated
            }
        }
    }

    // ── Compat config ────────────────────────────────────────────

    private fun hookCompatConfig(param: SystemServerStartingParam) {
        val props = arrayOf("miui.continuity.policy", "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN")
        val mgr = param.classLoader.findClass("com.android.server.wm.ApplicationCompatManager")
        val propertyIntHook = Hooker { chain ->
            if (!systemCompatConfigEnabled) return@Hooker chain.proceed()
            when (chain.args[0]) {
                "miui.continuity.policy" -> 5
                "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN" -> 1
                else -> chain.proceed()
            }
        }
        hook(mgr.method("getPropertyIntByApplication", String::class.java, String::class.java), propertyIntHook)
        hook(mgr.method("getPropertyIntByActivity", String::class.java, ComponentName::class.java), propertyIntHook)

        val hasPropertyHook = Hooker { chain ->
            if (!systemCompatConfigEnabled) return@Hooker chain.proceed()
            if (chain.args[0] in props) true else chain.proceed()
        }
        hook(mgr.method("hasPropertyByApplication", String::class.java, String::class.java), hasPropertyHook)
        hook(mgr.method("hasPropertyByActivity", String::class.java, ComponentName::class.java), hasPropertyHook)
    }

    // ── Flip continuity ──────────────────────────────────────────

    private fun hookFlipContinuity(param: SystemServerStartingParam) {
        val c = param.classLoader.findClass("com.android.server.wm.InterceptActivityController")
        hook(c.method("isFlipContinuityEnabledFromSetting", String::class.java, Int::class.java, String::class.java)) { chain ->
            if (systemFlipContinuityEnabled) true else chain.proceed()
        }
    }

    // ── Flip screen mode ─────────────────────────────────────────

    private fun hookFlipScreenMode(param: SystemServerStartingParam) {
        val ipm = param.classLoader.findClass("com.android.server.pm.IPackageManagerBase")
        hook(ipm.method("getActivityInfo", ComponentName::class.java, Long::class.java, Int::class.java), after { _, result ->
            (result as? ActivityInfo)?.applyFlipScreenMode()
            result
        })
        hook(ipm.method("getApplicationInfo", String::class.java, Long::class.java, Int::class.java), after { _, result ->
            (result as? ApplicationInfo)?.applyFlipScreenMode()
            result
        })

        val sizeCompatStub = param.classLoader.findClass("android.sizecompat.MiuiAppSizeCompatModeStub")
        val getSizeCompatStub = sizeCompatStub.method("get")
        isFlipFolded = {
            runCatching { getSizeCompatStub.invoke(null)?.callMethod("isFlipFolded") as? Boolean ?: false }.getOrDefault(false)
        }

        val activityRecord = param.classLoader.findClass("com.android.server.wm.ActivityRecord")
        val atmsImpl = param.classLoader.findClass("com.android.server.wm.ActivityTaskManagerServiceImpl")
        val controller = param.classLoader.findClass("com.android.server.wm.BoundsCompatController")
        val windowLayout = param.classLoader.findClass("android.view.WindowLayoutStubImpl")

        hook(controller.method("canUseFixedAspectRatio", Configuration::class.java), after { chain, result ->
            if (!isFlipFolded()) return@after result
            val packageName = activityPackageName(chain.thisObject?.getField("mOwner"))
            when (packageName?.let { flipScreenModeFor(it) }) {
                FlipScreenMode.FULL_SCREEN -> false
                FlipScreenMode.NO_SCALE,
                FlipScreenMode.SCALE -> true
                else -> result
            }
        })
        hook(atmsImpl.method("getGlobalScale", activityRecord), after { chain, result ->
            if (!isFlipFolded()) return@after result
            val packageName = activityPackageName(chain.args[0]) ?: return@after result
            when (flipScreenModeFor(packageName)) {
                FlipScreenMode.NO_SCALE -> FLIP_UNSCALE
                FlipScreenMode.SCALE -> flipScreenScaleFor(packageName)
                else -> result
            }
        })

        val propertyClass = param.classLoader.findClass($$"android.content.pm.PackageManager$Property")
        hook(
            ipm.method(
                "getPropertyAsUser",
                String::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!,
            )
        ) { chain ->
            val propertyName = chain.args[0] as? String
            val packageName = chain.args[1] as? String
            if (propertyName == WATCH_OVERLAY_PROPERTY &&
                packageName != null &&
                flipScreenModeFor(packageName) == FlipScreenMode.FULL_SCREEN
            ) {
                val className = chain.args[2] as? String
                runCatching {
                    propertyClass
                        .getDeclaredConstructor(
                            String::class.java, Boolean::class.javaPrimitiveType!!, String::class.java, String::class.java
                        )
                        .also { it.isAccessible = true }
                        .newInstance(propertyName, false, packageName, className)
                }.onFailure { log("getPropertyAsUser hook failed", it) }
                    .getOrElse { chain.proceed() }
            } else {
                chain.proceed()
            }
        }

        hook(windowLayout.method("getLayoutInDisplayCutoutMode", WindowManager.LayoutParams::class.java)) { chain ->
            if (!isFlipFolded()) return@hook chain.proceed()
            val attrs = chain.args[0] as? WindowManager.LayoutParams
            val packageName = attrs?.packageName ?: return@hook chain.proceed()
            if (flipScreenModeFor(packageName) != FlipScreenMode.FULL_SCREEN) return@hook chain.proceed()
            LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        val displayCutout = param.classLoader.findClass("android.view.DisplayCutout")
        val windowInsetsType = param.classLoader.findClass($$"android.view.WindowInsets$Type")
        val noDisplayCutout = displayCutout.getDeclaredField("NO_CUTOUT").also { it.isAccessible = true }.get(null)
        val displayCutoutType = windowInsetsType.method("displayCutout").invoke(null) as? Int ?: 0

        // Remove display cutout from InsetsState. fillInsetsState feeds both sync paths
        // (addWindowInner / relayoutWindow) and async paths (reportResized / notifyInsetsControlChanged).
        val windowState = param.classLoader.findClass("com.android.server.wm.WindowState")
        val insetsStateClass = param.classLoader.findClass("android.view.InsetsState")
        hook(windowState.method("fillInsetsState", insetsStateClass, Boolean::class.javaPrimitiveType!!), after { chain, _ ->
            if (!isFlipFolded()) return@after null
            val packageName = chain.thisObject?.callMethod("getOwningPackage") as? String ?: return@after null
            if (flipScreenModeFor(packageName) != FlipScreenMode.FULL_SCREEN) return@after null
            val state = chain.args[0]
            noDisplayCutout?.let { state.callMethod("setDisplayCutout", it) }
            for (i in (state.callMethod("sourceSize") as? Int ?: 0) - 1 downTo 0) {
                val source = state.callMethod("sourceAt", i) ?: continue
                if (source.callMethod("getType") as? Int == displayCutoutType) {
                    state.callMethod("removeSourceAt", i)
                }
            }
        })

        // Fix appBounds in LaunchActivityItem (cold start). Both fields are owned by the item.
        val launchActivityItemClass = param.classLoader.findClass("android.app.servertransaction.LaunchActivityItem")
        hook(launchActivityItemClass.constructors.first { it.parameterCount > 10 }, after { chain, _ ->
            if (!isFlipFolded()) return@after null
            val info = chain.thisObject?.getField("mInfo") as? ActivityInfo ?: return@after null
            val packageName = info.packageName ?: return@after null
            if (flipScreenModeFor(packageName) != FlipScreenMode.FULL_SCREEN) return@after null
            val overrideConfig = chain.thisObject?.getField("mOverrideConfig") ?: return@after null
            fixConfigurationAppBounds(overrideConfig)
            chain.thisObject?.getField("mCurConfig")?.let { fixConfigurationAppBounds(it) }
        })

        val activityWindowInfoClass = param.classLoader.findClass("android.window.ActivityWindowInfo")

        // Fix appBounds for per-activity config updates. args[0] is mMergedOverrideConfiguration —
        // a persistent system field, so restore original appBounds after proceed() to avoid pollution.
        hook(activityRecord.method("scheduleConfigurationChanged", Configuration::class.java, activityWindowInfoClass)) { chain ->
            if (!isFlipFolded()) return@hook chain.proceed()
            val packageName = activityPackageName(chain.thisObject) ?: return@hook chain.proceed()
            if (flipScreenModeFor(packageName) != FlipScreenMode.FULL_SCREEN) return@hook chain.proceed()
            val windowConfig = runCatching { chain.args[0].getField("windowConfiguration") }.getOrNull()
            val originalAppBounds = (windowConfig?.callMethod("getAppBounds") as? Rect)?.let { Rect(it) }
            val bounds = windowConfig?.callMethod("getBounds") as? Rect
            if (bounds != null && !bounds.isEmpty) windowConfig.callMethod("setAppBounds", bounds)
            runWithCleanup({ windowConfig?.callMethod("setAppBounds", originalAppBounds) }) {
                chain.proceed()
            }
        }

        // Fix appBounds in ConfigurationChangeItem (process-global config). mConfiguration is a copy.
        val windowProcessController = param.classLoader.findClass("com.android.server.wm.WindowProcessController")
        val iApplicationThread = param.classLoader.findClass("android.app.IApplicationThread")
        val clientTransactionItem = param.classLoader.findClass("android.app.servertransaction.ClientTransactionItem")
        val configurationChangeItemClass = param.classLoader.findClass("android.app.servertransaction.ConfigurationChangeItem")
        hook(windowProcessController.method("scheduleClientTransactionItem", iApplicationThread, clientTransactionItem)) { chain ->
            if (!isFlipFolded()) return@hook chain.proceed()
            val item = chain.args[1] ?: return@hook chain.proceed()
            if (!configurationChangeItemClass.isInstance(item)) return@hook chain.proceed()
            val pkgList = runCatching { chain.thisObject?.getField("mPkgList") as? List<*> }.getOrNull()
            val packages = pkgList?.filterIsInstance<String>() ?: return@hook chain.proceed()
            if (packages.none { flipScreenModeFor(it) == FlipScreenMode.FULL_SCREEN }) return@hook chain.proceed()
            item.getField("mConfiguration")?.let { fixConfigurationAppBounds(it) }
            chain.proceed()
        }
    }

    private fun ActivityInfo.applyFlipScreenMode() {
        val mode = flipScreenModeFor(packageName)
        if (mode == FlipScreenMode.DEFAULT) return
        metaData = (if (metaData != null) Bundle(metaData) else Bundle()).also {
            it.putInt(FLIP_SCREEN_META_DATA, mode.prefValue)
        }
    }

    private fun ApplicationInfo.applyFlipScreenMode() {
        val mode = flipScreenModeFor(packageName)
        if (mode == FlipScreenMode.DEFAULT) return
        metaData = (if (metaData != null) Bundle(metaData) else Bundle()).also {
            it.putInt(FLIP_SCREEN_META_DATA, mode.prefValue)
        }
    }

    private fun flipScreenModeFor(packageName: String): FlipScreenMode {
        return flipScreenModeCache[packageName] ?: FlipScreenMode.DEFAULT
    }

    private fun flipScreenScaleFor(packageName: String): Float {
        return flipScreenScaleCache[packageName] ?: Prefs.DEFAULT_FLIP_SCREEN_SCALE
    }

    // ── Flip input method ───────────────────────────────────────

    private fun hookFlipInputMethod(param: SystemServerStartingParam) {
        val switcher = param.classLoader.findClass("com.android.server.inputmethod.SogouInputMethodSwitcher")
        val immsClass = param.classLoader.findClass("com.android.server.inputmethod.InputMethodManagerService")
        inputMethodServiceImplClass = param.classLoader.findClass("com.android.server.inputmethod.InputMethodManagerServiceImpl")
        inputMethodSettingsRepositoryClass = param.classLoader.findClass("com.android.server.inputmethod.InputMethodSettingsRepository")

        val switcherIsSogou = switcher.method("isSogouMethodLocked", String::class.java)
        val serviceImplIsSogou = inputMethodServiceImplClass!!.method("isSogouMethodLocked", immsClass, String::class.java)

        createDexKitBridge(param.classLoader).use { bridge ->
            listOf(switcherIsSogou, serviceImplIsSogou).forEach { target ->
                bridge.findMethod {
                    matcher {
                        invokeMethods {
                            add {
                                declaredClass(target.declaringClass.name)
                                name = target.name
                            }
                        }
                    }
                }.forEach { runCatching { module!!.deoptimize(it.getMethodInstance(param.classLoader)) } }
            }
        }

        hook(switcherIsSogou) { chain ->
            val methodId = chain.args[0] as? String ?: return@hook chain.proceed()
            val userId = chain.thisObject?.getField("mService")?.getField("mCurrentImeUserId") as? Int
                ?: return@hook chain.proceed()
            inputMethodPackage(methodId, userId)?.let { it == flipInputMethodPackageCache }
                ?: chain.proceed()
        }

        hook(serviceImplIsSogou) { chain ->
            val service = chain.args[0] ?: return@hook chain.proceed()
            val methodId = chain.args[1] as? String ?: return@hook chain.proceed()
            val userId = service.getField("mCurrentImeUserId") as? Int ?: return@hook chain.proceed()
            inputMethodPackage(methodId, userId)?.let { it == flipInputMethodPackageCache }
                ?: chain.proceed()
        }
    }

    // Xiaomi consumes DisplayCutout in size-compat windows, but leaves its InsetsSource.
    // At ROTATION_180 the scaled left source overlaps the unscaled window origin.
    // Visible insets then contain a left inset absent from content insets, triggering
    // ViewRootImpl.scrollToRectOrFocus and panning an already IME-aware app a second time.
    private fun hookFlipImeInsetsFix(param: SystemServerStartingParam) {
        val windowState = param.classLoader.findClass("com.android.server.wm.WindowState")
        val insetsState = param.classLoader.findClass("android.view.InsetsState")
        val copyState = insetsState.getConstructor(insetsState, Boolean::class.javaPrimitiveType!!)
        val sizeCompatStub = param.classLoader.findClass("android.sizecompat.MiuiAppSizeCompatModeStub")
        val getSizeCompatStub = sizeCompatStub.method("get")
        var failureLogged = false

        // Invalidate compiled callers so getCompatInsetsState cannot be bypassed
        // by an inlined copy in the client inset dispatch path.
        module!!.deoptimize(windowState.method("fillInsetsState", insetsState, Boolean::class.javaPrimitiveType!!))

        // This feeds both initial relayout and later asynchronous inset updates. Work
        // on a copy: the original may be shared with other windows or the display.
        hook(windowState.method("getCompatInsetsState"), after { chain, result ->
            if (!systemFlipImeInsetsFixEnabled || result == null) return@after result
            runCatching {
                if (getSizeCompatStub.invoke(null)?.callMethod("isFlipFolded") != true) return@runCatching result
                val window = chain.thisObject ?: return@runCatching result
                if (window.callMethod("getDisplayId") != 0) return@runCatching result
                val activity = window.getField("mActivityRecord") ?: return@runCatching result
                if (activity.getField("mHyperStub")?.callMethod("inMiuiSizeCompatMode") != true) return@runCatching result
                val cutout = result.callMethod("getDisplayCutout") as? DisplayCutout ?: return@runCatching result
                if (cutout.callMethod("isEmpty") != true) return@runCatching result
                val cutoutType = WindowInsets.Type.displayCutout()
                val sourceCount = result.callMethod("sourceSize") as Int
                val indices = (sourceCount - 1 downTo 0).filter { index ->
                    result.callMethod("sourceAt", index)?.callMethod("getType") == cutoutType
                }
                if (indices.isEmpty()) return@runCatching result
                copyState.newInstance(result, true).also { copy ->
                    indices.forEach { copy.callMethod("removeSourceAt", it) }
                }
            }.getOrElse {
                if (!failureLogged) {
                    failureLogged = true
                    log("flip IME insets fix failed", it)
                }
                result
            }
        })
    }

    private fun normalizeFlipInputMethodPackage(packageName: String?): String {
        return packageName
            ?.trim()
            ?.ifEmpty { Prefs.DEFAULT_FLIP_IME_PKG }
            ?: Prefs.DEFAULT_FLIP_IME_PKG
    }

    private fun inputMethodPackage(methodId: String, userId: Int): String? {
        val repository = inputMethodSettingsRepositoryClass ?: return null
        val settings = repository.method("get", Int::class.java).invoke(null, userId) ?: return null
        val imi = settings.callMethod("getMethodMap")?.callMethod("get", methodId) ?: return null
        return imi.callMethod("getPackageName") as? String
    }

    private fun syncFlipInputMethodIfFolded() {
        runCatching {
            val serviceImplClass = inputMethodServiceImplClass ?: return
            val serviceImpl = serviceImplClass.method("getInstance").invoke(null) ?: return
            if (serviceImpl.getField("mDeviceFolded") as? Boolean != true) return

            val context = serviceImpl.getField("mContext") as? Context ?: return
            val imms = serviceImpl.getField("mImms") ?: return
            val userId = imms.getField("mCurrentImeUserId") as? Int ?: return
            val methodId = inputMethodIdForPackage(flipInputMethodPackageCache, userId) ?: return

            val resolver = context.contentResolver
            val secureSettingsClass = Settings.Secure::class.java
            val getStringForUser = secureSettingsClass.method(
                "getStringForUser",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!
            )
            val putStringForUser = secureSettingsClass.method(
                "putStringForUser",
                android.content.ContentResolver::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!
            )
            val current = getStringForUser.invoke(null, resolver, DEFAULT_INPUT_METHOD, userId) as? String
            if (current != methodId) {
                putStringForUser.invoke(null, resolver, DEFAULT_INPUT_METHOD, methodId, userId)
            }
        }.onFailure { log("syncFlipInputMethodIfFolded failed", it) }
    }

    private fun inputMethodIdForPackage(packageName: String, userId: Int): String? {
        val repository = inputMethodSettingsRepositoryClass ?: return null
        val settings = repository.method("get", Int::class.java).invoke(null, userId) ?: return null
        val methodMap = settings.callMethod("getMethodMap") ?: return null
        val backingMap = methodMap.getField("mMap") as? Map<*, *> ?: return null
        return backingMap.entries.firstNotNullOfOrNull { (methodId, imi) ->
            val id = methodId as? String ?: return@firstNotNullOfOrNull null
            val imePackageName = imi?.callMethod("getPackageName") as? String
            if (imePackageName == packageName) id else null
        }
    }

    private fun fixConfigurationAppBounds(configuration: Any?) {
        val config = configuration ?: return
        runCatching {
            val windowConfiguration = config.getField("windowConfiguration") ?: return@runCatching
            val bounds = windowConfiguration.callMethod("getBounds") as? Rect
            if (bounds != null && !bounds.isEmpty) {
                windowConfiguration.callMethod("setAppBounds", bounds)
            }
        }
    }

    // ── Utility ──────────────────────────────────────────────────

    private fun activityPackageName(activityRecord: Any?): String? {
        return runCatching {
            activityRecord?.getField("packageName") as? String
        }.getOrNull()
    }

    // ── Constants & state ────────────────────────────────────────

    private const val FLIP_SCREEN_META_DATA = "miui.supportFlipFullScreen"
    private const val WATCH_OVERLAY_PROPERTY = "miui.supportFlipWatchOverlayGroupView"
    private const val DEFAULT_INPUT_METHOD = "default_input_method"
    private const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3
    private const val FLIP_UNSCALE = 1.0f
    @Volatile
    private var systemCompatConfigEnabled = false
    @Volatile
    private var systemFlipContinuityEnabled = false
    @Volatile
    private var systemFlipImeInsetsFixEnabled = false
    @Volatile
    private var flipInputMethodPackageCache = Prefs.DEFAULT_FLIP_IME_PKG
    @Volatile
    private var flipScreenModeCache: Map<String, FlipScreenMode> = emptyMap()
    @Volatile
    private var flipScreenScaleCache: Map<String, Float> = emptyMap()
    @Volatile
    private var inputMethodServiceImplClass: Class<*>? = null
    @Volatile
    private var inputMethodSettingsRepositoryClass: Class<*>? = null
    private var systemPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var isFlipFolded: () -> Boolean = { false }
}
