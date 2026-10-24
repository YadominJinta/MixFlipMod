package com.parallelc.mixflipmod.ui.util

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.parallelc.mixflipmod.Prefs
import com.parallelc.mixflipmod.hook.GboardHook
import com.parallelc.mixflipmod.model.AppConfig
import com.parallelc.mixflipmod.model.PrefSpec
import com.parallelc.mixflipmod.model.appConfigs
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedService.OnScopeEventListener
import io.github.libxposed.service.XposedService.ServiceException
import io.github.libxposed.service.XposedServiceHelper

object XposedServiceState {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false

    var service by mutableStateOf<XposedService?>(null)
        private set

    var prefs by mutableStateOf<SharedPreferences?>(null)
        private set

    val isConnected: Boolean
        get() = service != null && prefs != null

    fun ensureRegistered() {
        if (registered) return
        registered = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                runCatching { service.getRemotePreferences(Prefs.NAME) }
                    .onSuccess { remotePrefs ->
                        runCatching { checkEnabledScopes(service, remotePrefs) }
                        mainHandler.post {
                            this@XposedServiceState.service = service
                            prefs = remotePrefs
                        }
                    }
                    .onFailure { e ->
                        if (e is ServiceException) {
                            mainHandler.post { clear() }
                        }
                    }
            }

            override fun onServiceDied(service: XposedService) {
                mainHandler.post { clear() }
            }
        })
    }

    private fun clear() {
        service = null
        prefs = null
    }
}

val AppConfig.scopePackage: String
    get() = if (packageName == "android") "system" else packageName

fun checkEnabledScopes(service: XposedService, prefs: SharedPreferences) {
    val enabledScopePackages = appConfigs.mapNotNull { config ->
        config.scopePackage.takeIf {
            config.prefs.filterIsInstance<PrefSpec.Switch>().any { spec ->
                prefs.getBoolean(spec.prefKey, false)
            }
        }
    }.distinct()
    requestMissingScopes(service, enabledScopePackages + extraScopes(prefs))
}

fun checkScopes(service: XposedService?, scopePackages: List<String>, enabled: Boolean) {
    if (!enabled || service == null) return
    requestMissingScopes(service, scopePackages + extraScopes(service.getRemotePreferences(Prefs.NAME)))
}

private fun extraScopes(prefs: SharedPreferences): List<String> =
    if (prefs.getBoolean(Prefs.SYSTEM_GBOARD_CUTOUT_FIX, false)) listOf("system", GboardHook.PACKAGE) else emptyList()

private fun requestMissingScopes(service: XposedService, packages: List<String>) {
    val scope = service.scope
    val missingPackages = packages.distinct().filterNot { it in scope }
    if (missingPackages.isNotEmpty()) {
        service.requestScope(missingPackages, object : OnScopeEventListener {})
    }
}
