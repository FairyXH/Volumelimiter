package io.github.fairyxh.volumelimiter.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import io.github.fairyxh.volumelimiter.utils.LogUtils;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Best-effort trigger for active correction after the resumed foreground activity changes. */
public final class ForegroundChangeHook {
    private static final Set<String> METHOD_NAMES = Set.of(
            "setResumedActivityUncheckLocked", "updateResumedAppTrace",
            "onTopResumedActivityChanged", "setLastResumedActivityUncheckLocked");

    private ForegroundChangeHook() {
    }

    public static int install(XposedModule module, ClassLoader classLoader, Runnable callback) {
        int installed = 0;
        Set<String> signatures = new HashSet<>();
        String[] classNames = {
                "com.android.server.wm.ActivityTaskManagerService",
                "com.android.server.wm.ActivityTaskSupervisor",
                "com.android.server.wm.RootWindowContainer"
        };
        for (String className : classNames) {
            try {
                Class<?> type = Class.forName(className, false, classLoader);
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    for (Method method : current.getDeclaredMethods()) {
                        if (!METHOD_NAMES.contains(method.getName()) || method.isSynthetic()
                                || Modifier.isAbstract(method.getModifiers())
                                || !signatures.add(method.toGenericString())) {
                            continue;
                        }
                        try {
                            method.setAccessible(true);
                            module.hook(method)
                                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                                    .intercept(chain -> {
                                        Object result = chain.proceed();
                                        try {
                                            callback.run();
                                        } catch (Throwable error) {
                                            LogUtils.debug("Foreground correction callback failed: " + error);
                                        }
                                        return result;
                                    });
                            installed++;
                        } catch (Throwable error) {
                            LogUtils.debug("Unable to hook foreground method " + method + ": " + error);
                        }
                    }
                }
            } catch (Throwable error) {
                LogUtils.debug(className + " unavailable for foreground trigger: " + error);
            }
        }
        LogUtils.debug("Installed " + installed + " foreground change hooks");
        return installed;
    }
}
