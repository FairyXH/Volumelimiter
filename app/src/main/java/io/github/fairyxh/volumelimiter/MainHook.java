package io.github.fairyxh.volumelimiter;

import android.content.SharedPreferences;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.fairyxh.volumelimiter.config.ConfigManager;
import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.hook.AudioServiceHook;
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
        try {
            SharedPreferences preferences = getRemotePreferences(PreferenceStorage.GROUP);
            ConfigManager configManager = new ConfigManager(preferences);
            LogUtils.setDebug(configManager.snapshot().debug);
            new io.github.fairyxh.volumelimiter.hook.SystemServerNewAppMonitor(preferences)
                    .install(param.getClassLoader());
            new AudioServiceHook(this, configManager).install(param.getClassLoader());
        } catch (Throwable error) {
            LogUtils.error("Fatal initialization error; all hooks skipped", error);
        }
    }
}
