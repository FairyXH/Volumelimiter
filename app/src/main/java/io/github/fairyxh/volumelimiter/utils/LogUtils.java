package io.github.fairyxh.volumelimiter.utils;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

public final class LogUtils {
    public static final String TAG = "Volumelimiter";
    private static volatile XposedModule module;
    private static volatile boolean debug;

    private LogUtils() {
    }

    public static void initialize(XposedModule xposedModule) {
        module = xposedModule;
    }

    public static void setDebug(boolean enabled) {
        debug = enabled;
    }

    public static void info(String message) {
        write(Log.INFO, message, null);
    }

    public static void debug(String message) {
        if (debug) {
            write(Log.DEBUG, message, null);
        }
    }

    public static void error(String message, Throwable throwable) {
        write(Log.ERROR, message, throwable);
    }

    private static void write(int priority, String message, Throwable throwable) {
        XposedModule current = module;
        if (current != null) {
            if (throwable == null) {
                current.log(priority, TAG, message);
            } else {
                current.log(priority, TAG, message, throwable);
            }
        } else if (throwable == null) {
            Log.println(priority, TAG, message);
        } else {
            Log.println(priority, TAG, message + "\n" + Log.getStackTraceString(throwable));
        }
    }
}
