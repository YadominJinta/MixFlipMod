package com.parallelc.mixflipmod.hook

import android.inputmethodservice.InputMethodService
import android.content.Context
import android.view.DisplayCutout
import android.view.WindowInsets
import android.view.WindowManager
import com.parallelc.mixflipmod.Prefs
import com.parallelc.mixflipmod.BuildConfig
import android.graphics.Rect
import com.parallelc.mixflipmod.hook.util.after
import com.parallelc.mixflipmod.hook.util.callMethod
import com.parallelc.mixflipmod.hook.util.createDexKitBridge
import com.parallelc.mixflipmod.hook.util.findClass
import com.parallelc.mixflipmod.hook.util.hook
import com.parallelc.mixflipmod.hook.util.log
import com.parallelc.mixflipmod.hook.util.method
import com.parallelc.mixflipmod.hook.util.prefEnabled
import com.parallelc.mixflipmod.hook.util.safeHook
import com.parallelc.mixflipmod.module
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Modifier

internal object GboardHook {
    const val PACKAGE = "com.google.android.inputmethod.latin"

    fun hook(param: PackageReadyParam) {
        safeHook("[$PACKAGE] native keyboard height") {
            createDexKitBridge(param.classLoader).use { bridge ->
                // Find by a stable flag name; Gboard's implementation names change
                // between releases. Leave its normal height calculation intact.
                val helper = bridge.findClass {
                    matcher { usingStrings("config_keyboard_max_height") }
                }.singleOrNull()?.getInstance(param.classLoader)
                    ?: error("Gboard maximum keyboard height helper not found")
                val maxHeight = helper.declaredMethods.single {
                    !Modifier.isStatic(it.modifiers) && it.returnType == Int::class.javaPrimitiveType &&
                        it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType!!))
                }.also { it.isAccessible = true }
                val headerHeight = helper.declaredMethods.single {
                    it.returnType == Int::class.javaPrimitiveType && it.parameterCount == 2 &&
                        it.parameterTypes[0].isArray && it.parameterTypes[0].componentType?.isEnum == true &&
                        it.parameterTypes[1] == Boolean::class.javaPrimitiveType
                }.also { it.isAccessible = true }
                val headerType = requireNotNull(headerHeight.parameterTypes[0].componentType)
                val header = requireNotNull(headerType.enumConstants).single { (it as Enum<*>).name == "HEADER" }
                val headerArray = ReflectArray.newInstance(headerType, 1).also { ReflectArray.set(it, 0, header) }
                val serviceField = helper.declaredFields.single {
                    InputMethodService::class.java.isAssignableFrom(it.type)
                }.also { it.isAccessible = true }
                val cutoutStub = param.classLoader.findClass("android.view.DisplayCutoutStub")
                val getCutoutStub = cutoutStub.method("get")
                fun isFolded() = getCutoutStub.invoke(null)?.callMethod("isFlipFolded") == true
                val paddings = bridge.findClass {
                    matcher { usingStrings("config_keyboard_left_padding_portrait", "config_keyboard_right_padding_portrait") }
                }.singleOrNull()?.getInstance(param.classLoader)
                    ?: error("Gboard keyboard padding helper not found")
                val getPaddingCutout = paddings.declaredMethods.single {
                    Modifier.isStatic(it.modifiers) && it.returnType == DisplayCutout::class.java &&
                        it.parameterTypes.contentEquals(arrayOf(Context::class.java))
                }.also { it.isAccessible = true }
                hook(getPaddingCutout, after { chain, result ->
                    val cutout = result as? DisplayCutout ?: return@after result
                    if (!prefEnabled(Prefs.SYSTEM_GBOARD_CUTOUT_FIX) || !isFolded()) return@after result
                    val context = chain.args[0] as? Context ?: return@after result
                    if (context.display.displayId != 0) return@after result
                    val metricsBounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                    val bounds = Rect(0, 0, metricsBounds.width(), metricsBounds.height())
                    if (GboardWindowHook.boundsBelowTopLeftCutout(cutout, bounds) != null) null else result
                })
                paddings.declaredMethods.forEach { method ->
                    runCatching { module!!.deoptimize(method) }
                }
                var failureLogged = false
                var limitReported = false
                hook(maxHeight, after { chain, result ->
                    val original = result as? Int ?: return@after result
                    if (!prefEnabled(Prefs.SYSTEM_GBOARD_CUTOUT_FIX)) return@after result
                    runCatching {
                        if (!isFolded()) return@runCatching result
                        val service = serviceField.get(chain.thisObject) as? InputMethodService ?: return@runCatching result
                        val manager = service.getSystemService(WindowManager::class.java)
                        if (service.display?.displayId != 0) return@runCatching result
                        val metrics = manager.maximumWindowMetrics
                        val cutout = service.display?.cutout ?: return@runCatching result
                        // Window metrics may be sandboxed relative to the editor app;
                        // Display.getCutout() uses physical display coordinates.
                        val bounds = Rect(0, 0, metrics.bounds.width(), metrics.bounds.height())
                        val safe = GboardWindowHook.boundsBelowTopLeftCutout(cutout, bounds)
                            ?: return@runCatching result
                        val headerPixels = headerHeight.invoke(chain.thisObject, headerArray, true) as Int
                        val bottom = metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
                        val bodyLimit = safe.height() - headerPixels - bottom
                        if (BuildConfig.DEBUG && !limitReported) {
                            limitReported = true
                            log("Gboard full-width height: available=${safe.height()}, header=$headerPixels, bottom=$bottom, bodyLimit=$bodyLimit, original=$original")
                        }
                        if (bodyLimit <= 0) result else minOf(original, bodyLimit)
                    }.getOrElse {
                        if (!failureLogged) {
                            failureLogged = true
                            log("Gboard native height limit failed", it)
                        }
                        result
                    }
                })
                bridge.findMethod {
                    matcher {
                        invokeMethods {
                            add { declaredClass(helper.name); name = maxHeight.name }
                        }
                    }
                }.forEach { caller ->
                    runCatching { module!!.deoptimize(caller.getMethodInstance(param.classLoader)) }
                }
                bridge.findMethod {
                    matcher { invokeMethods { add { declaredClass(paddings.name) } } }
                }.forEach { caller ->
                    runCatching { module!!.deoptimize(caller.getMethodInstance(param.classLoader)) }
                }
            }
        }
    }
}
