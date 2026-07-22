package io.github.fairyxh.volumelimiter.utils;

import android.os.Build;

public final class AndroidVersionUtils {
    private AndroidVersionUtils() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public static String describe() {
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }

    public static String romDescription() {
        return Build.MANUFACTURER + " " + Build.BRAND + " / " + Build.DISPLAY;
    }
}
