package io.github.fairyxh.volumelimiter.hook;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.os.Binder;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.fairyxh.volumelimiter.utils.LogUtils;

/** Best-effort foreground package lookup from system_server. */
public final class ForegroundAppResolver {
    private static final long CACHE_MILLIS = 500L;

    private final Method getService;
    private final Method getTasks;
    private volatile long cachedAt;
    private volatile String cachedPackage;

    @SuppressLint("BlockedPrivateApi") // Runs only inside LSPosed-injected system_server.
    public ForegroundAppResolver(ClassLoader classLoader) {
        Method serviceMethod = null;
        Method tasksMethod = null;
        try {
            Class<?> activityTaskManager = Class.forName(
                    "android.app.ActivityTaskManager", false, classLoader);
            serviceMethod = activityTaskManager.getDeclaredMethod("getService");
            serviceMethod.setAccessible(true);
            Object service = serviceMethod.invoke(null);
            if (service != null) {
                tasksMethod = findGetTasks(service.getClass());
            }
        } catch (Throwable error) {
            LogUtils.debug("Foreground app lookup unavailable: " + error);
        }
        getService = serviceMethod;
        getTasks = tasksMethod;
    }

    public String resolve() {
        long now = SystemClock.elapsedRealtime();
        if (now - cachedAt < CACHE_MILLIS) {
            return cachedPackage;
        }
        String resolved = queryForegroundPackage();
        cachedPackage = resolved;
        cachedAt = now;
        return resolved;
    }

    private String queryForegroundPackage() {
        if (getService == null || getTasks == null) {
            return null;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            Object service = getService.invoke(null);
            Object[] args = createArguments(getTasks.getParameterTypes());
            Object value = getTasks.invoke(service, args);
            if (!(value instanceof List<?> tasks) || tasks.isEmpty()) {
                return null;
            }
            Object task = tasks.get(0);
            Field topActivity = task.getClass().getField("topActivity");
            Object component = topActivity.get(task);
            return component instanceof ComponentName
                    ? ((ComponentName) component).getPackageName() : null;
        } catch (Throwable error) {
            LogUtils.debug("Unable to resolve foreground app: " + error);
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static Method findGetTasks(Class<?> serviceClass) {
        Method best = null;
        for (Method method : serviceClass.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("getTasks") && types.length > 0
                    && types[0] == int.class && List.class.isAssignableFrom(method.getReturnType())
                    && supportsArguments(types)) {
                try {
                    method.setAccessible(true);
                    if (best == null || types.length < best.getParameterCount()) {
                        best = method;
                    }
                } catch (Throwable ignored) {
                    // Try another overload.
                }
            }
        }
        return best;
    }

    private static boolean supportsArguments(Class<?>[] types) {
        for (Class<?> type : types) {
            if (type != int.class && type != boolean.class) {
                return false;
            }
        }
        return true;
    }

    private static Object[] createArguments(Class<?>[] types) {
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) {
                args[i] = i == 0 ? 1 : 0;
            } else if (types[i] == boolean.class) {
                args[i] = false;
            } else {
                args[i] = null;
            }
        }
        return args;
    }
}
