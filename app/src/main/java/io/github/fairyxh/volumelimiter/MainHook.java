package io.github.fairyxh.volumelimiter;

import android.content.SharedPreferences;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.fairyxh.volumelimiter.config.ConfigManager;
import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.config.SystemRuleStore;
import io.github.fairyxh.volumelimiter.hook.AudioServiceHook;
import io.github.fairyxh.volumelimiter.hook.SystemServerNewAppMonitor;
import io.github.fairyxh.volumelimiter.utils.AndroidVersionUtils;
import io.github.fairyxh.volumelimiter.utils.LogUtils;
import io.github.libxposed.api.XposedModule;

public final class MainHook extends XposedModule {
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        LogUtils.initialize(this);
        if (param.isSystemServer()) {
            LogUtils.info("Module loaded in system_server; " + AndroidVersionUtils.describe());
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }
        if (!AndroidVersionUtils.isSupported()) {
            LogUtils.info("Android version is below the supported minimum; hooks skipped");
            return;
        }
        if ((getFrameworkProperties() & PROP_CAP_SYSTEM) == 0) {
            LogUtils.error("Framework does not advertise system_server hook capability", null);
            return;
        }
        if ((getFrameworkProperties() & PROP_CAP_REMOTE) == 0) {
            LogUtils.error("Framework does not advertise remote preferences capability", null);
            return;
        }
        SharedPreferences preferences;
        try {
            preferences = getRemotePreferences(PreferenceStorage.GROUP);
        } catch (Throwable error) {
            LogUtils.error("[VLM][Config] Load failed reason=remote preferences unavailable", error);
            return;
        }

        SystemRuleStore systemRuleStore = SystemRuleStore.forSystemServer();
        ConfigManager configManager;
        try {
            configManager = new ConfigManager(preferences, systemRuleStore);
            LogUtils.setDebug(configManager.snapshot().debug);
        } catch (Throwable error) {
            LogUtils.error("[VLM][Config] Load failed reason=" + error, error);
            return;
        }

        try {
            new SystemServerNewAppMonitor(this, preferences, systemRuleStore,
                    configManager::refresh)
                    .install(param.getClassLoader());
        } catch (Throwable error) {
            LogUtils.error("System-server new-app monitor installation failed", error);
        }

        try {
            new AudioServiceHook(this, configManager).install(param.getClassLoader());
        } catch (Throwable error) {
            LogUtils.error("AudioServiceHook installation failed", error);
        }
    }
}
