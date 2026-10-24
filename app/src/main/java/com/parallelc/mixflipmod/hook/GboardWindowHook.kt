package com.parallelc.mixflipmod.hook

import android.graphics.Rect
import android.view.DisplayCutout
import android.view.WindowInsets
import android.view.WindowManager
import com.parallelc.mixflipmod.hook.util.callMethod
import com.parallelc.mixflipmod.hook.util.after
import com.parallelc.mixflipmod.hook.util.findClass
import com.parallelc.mixflipmod.hook.util.getField
import com.parallelc.mixflipmod.hook.util.hook
import com.parallelc.mixflipmod.hook.util.hookScope
import com.parallelc.mixflipmod.hook.util.log
import com.parallelc.mixflipmod.hook.util.method
import com.parallelc.mixflipmod.module
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal object GboardWindowHook {
    private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"

    fun hook(param: SystemServerStartingParam, enabled: () -> Boolean) {
        val loader = param.classLoader
        val windowState = loader.findClass("com.android.server.wm.WindowState")
        val displayFrames = loader.findClass("com.android.server.wm.DisplayFrames")
        val displayPolicy = loader.findClass("com.android.server.wm.DisplayPolicy")
        val windowLayout = loader.findClass("android.view.WindowLayout")
        val insetsState = loader.findClass("android.view.InsetsState")
        val clientFrames = loader.findClass("android.window.ClientWindowFrames")
        val sizeCompat = loader.findClass("android.sizecompat.MiuiAppSizeCompatModeStub")
        val getSizeCompat = sizeCompat.method("get")
        val layoutWindow = displayPolicy.method("layoutWindowLw", windowState, windowState, displayFrames)
        val computeFrames = windowLayout.method(
            "computeFrames", WindowManager.LayoutParams::class.java, insetsState,
            Rect::class.java, Rect::class.java,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!, clientFrames,
        )
        val copyState = insetsState.getConstructor(insetsState, Boolean::class.javaPrimitiveType!!)
        var failureLogged = false
        val safeLayout = hookScope(computeFrames) { chain ->
            val attrs = chain.args[0] as? WindowManager.LayoutParams ?: return@hookScope chain.proceed()
            val args = runCatching {
                val args = chain.args.toTypedArray()
                val state = args[1] ?: return@runCatching null
                val bounds = args[3] as? Rect ?: return@runCatching null
                val belowCamera = boundsBelowTopLeftCutout(state, bounds)
                // Fit the actual window so keys, candidates, popups and touch coordinates
                // agree. Copy parameters rather than changing the IME's persistent attrs.
                args[0] = WindowManager.LayoutParams().also { copy ->
                    copy.copyFrom(attrs)
                    val types = attrs.callMethod("getFitInsetsTypes") as Int
                    copy.callMethod("setFitInsetsTypes", if (belowCamera != null) {
                        types and WindowInsets.Type.displayCutout().inv()
                    } else {
                        types or WindowInsets.Type.displayCutout()
                    })
                }
                if (belowCamera != null) {
                    // Keep the full display height: Gboard reserves space for the app
                    // inside it. GboardHook caps its native keyboard height instead.
                    args[1] = withoutCutout(state, copyState.newInstance(state, true))
                }
                args
            }.getOrElse {
                if (!failureLogged) {
                    failureLogged = true
                    log("Gboard cover window layout failed", it)
                }
                null
            } ?: return@hookScope chain.proceed()
            chain.proceed(args)
        }
        fun isTarget(window: Any): Boolean {
            if (!enabled()) return false
            val attrs = window.getField("mAttrs") as? WindowManager.LayoutParams ?: return false
            return attrs.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD &&
                attrs.packageName == GBOARD_PACKAGE && window.callMethod("getDisplayId") == 0 &&
                getSizeCompat.invoke(null)?.callMethod("isFlipFolded") == true
        }
        module!!.deoptimize(layoutWindow)
        hook(layoutWindow) { chain ->
            val window = chain.args[0] ?: return@hook chain.proceed()
            if (!isTarget(window)) return@hook chain.proceed()
            safeLayout.run { chain.proceed() }
        }
        module!!.deoptimize(windowState.method("fillInsetsState", insetsState, Boolean::class.javaPrimitiveType!!))
        hook(windowState.method("getCompatInsetsState"), after { chain, result ->
            val window = chain.thisObject ?: return@after result
            if (result == null || !isTarget(window)) return@after result
            val display = result.callMethod("getDisplayFrame") as? Rect ?: return@after result
            if (boundsBelowTopLeftCutout(result, display) == null) return@after result
            // DisplayCutout.inset() retains a side safe inset even when the physical
            // cutout is entirely above this window. Do not send that stale side inset.
            withoutCutout(result, copyState.newInstance(result, true))
        })
    }

    private fun boundsBelowTopLeftCutout(state: Any, bounds: Rect): Rect? {
        val cutout = state.callMethod("getDisplayCutout") as? DisplayCutout ?: return null
        return boundsBelowTopLeftCutout(cutout, bounds)
    }

    internal fun boundsBelowTopLeftCutout(cutout: DisplayCutout, bounds: Rect): Rect? {
        if (bounds.isEmpty || cutout.safeInsetLeft <= 0) return null
        val rects = cutout.boundingRects.filterNot { it.isEmpty }
        if (rects.isEmpty() || rects.any { it.left > bounds.left || it.top > bounds.top }) return null
        val bottom = rects.maxOf { it.bottom }
        if (bottom <= bounds.top || bottom >= bounds.bottom) return null
        return Rect(bounds.left, bottom, bounds.right, bounds.bottom)
    }

    private fun withoutCutout(original: Any, copy: Any): Any {
        val emptyCutout = DisplayCutout::class.java.getField("NO_CUTOUT").get(null)
        copy.callMethod("setDisplayCutout", emptyCutout)
        for (index in (original.callMethod("sourceSize") as Int) - 1 downTo 0) {
            if (original.callMethod("sourceAt", index)?.callMethod("getType") == WindowInsets.Type.displayCutout()) {
                copy.callMethod("removeSourceAt", index)
            }
        }
        return copy
    }
}
