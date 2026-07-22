package io.github.fairyxh.volumelimiter.hook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import java.lang.reflect.Method;
import java.util.Collections;

import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.config.TemplateRepository;
import io.github.fairyxh.volumelimiter.config.VolumeTemplate;
import io.github.fairyxh.volumelimiter.ui.AppRuleActivity;
import io.github.fairyxh.volumelimiter.ui.SettingsActivity;
import io.github.fairyxh.volumelimiter.utils.LogUtils;

/** Handles package-added events entirely inside system_server. */
public final class SystemServerNewAppMonitor {
    private static final String MODULE_PACKAGE = "io.github.fairyxh.volumelimiter";
    private static final String CHANNEL_ID = "volumelimiter_new_apps";

    private final SharedPreferences preferences;
    private final Handler worker;

    public SystemServerNewAppMonitor(SharedPreferences preferences) {
        this.preferences = preferences;
        HandlerThread thread = new HandlerThread("Volumelimiter-install-events");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public boolean install(ClassLoader classLoader) {
        Context context = findSystemContext(classLoader);
        if (context == null) {
            LogUtils.error("System context unavailable; new-app monitor skipped", null);
            return false;
        }
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    Uri data = intent.getData();
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) || data == null) {
                        return;
                    }
                    String packageName = data.getSchemeSpecificPart();
                    worker.post(() -> processPackage(context, packageName));
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, null, worker,
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, null, worker);
            }
            LogUtils.debug("Installed system_server PACKAGE_ADDED monitor");
            return true;
        } catch (Throwable error) {
            LogUtils.error("Unable to register system_server package monitor", error);
            return false;
        }
    }

    private void processPackage(Context context, String packageName) {
        try {
            LogUtils.setDebug(preferences.getBoolean(PreferenceStorage.KEY_DEBUG, false));
            if (packageName == null || packageName.isBlank() || MODULE_PACKAGE.equals(packageName)
                    || !preferences.getBoolean(PreferenceStorage.KEY_AUTO_APPLY_NEW_APPS, false)) {
                return;
            }
            if (PreferenceStorage.readAppPackages(preferences).contains(packageName)) {
                LogUtils.debug("NewApp ignored existingRule package=" + packageName);
                return;
            }
            VolumeTemplate template = TemplateRepository.find(preferences,
                    preferences.getString(PreferenceStorage.KEY_DEFAULT_TEMPLATE_ID, ""));
            if (template == null) {
                LogUtils.debug("NewApp ignored missingTemplate package=" + packageName);
                return;
            }
            boolean saved = TemplateRepository.applyToPackages(preferences, template,
                    Collections.singleton(packageName));
            LogUtils.debug("NewApp package=" + packageName + " template=" + template.name
                    + " saved=" + saved);
            if (saved && preferences.getBoolean(PreferenceStorage.KEY_NOTIFY_NEW_APPS, false)) {
                notifyConfigured(context, packageName, template.name);
            }
        } catch (Throwable error) {
            LogUtils.error("New-app processing failed for " + packageName, error);
        }
    }

    private static Context findSystemContext(ClassLoader classLoader) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread", false, classLoader);
            Method current = activityThread.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread == null) {
                return null;
            }
            Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Object value = getSystemContext.invoke(thread);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable error) {
            LogUtils.debug("Unable to obtain system context: " + error);
            return null;
        }
    }

    @SuppressLint("NotificationPermission") // system_server owns the Android System notification identity.
    private static void notifyConfigured(Context context, String packageName, String templateName) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) {
                return;
            }
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "Volumelimiter", NotificationManager.IMPORTANCE_DEFAULT));
            String label = packageName;
            try {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
                label = context.getPackageManager().getApplicationLabel(info).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
                // The package may have been removed before this worker ran.
            }
            Intent open = new Intent().setComponent(new ComponentName(MODULE_PACKAGE,
                    SettingsActivity.class.getName()))
                    .putExtra(AppRuleActivity.EXTRA_PACKAGE, packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, packageName.hashCode(),
                    open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Volumelimiter")
                    .setContentText("已为新安装应用 " + label + " 自动应用模板 " + templateName)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build();
            manager.notify(CHANNEL_ID, packageName.hashCode(), notification);
            LogUtils.debug("NewApp system notification sent package=" + packageName);
        } catch (Throwable error) {
            LogUtils.error("System notification failed for " + packageName, error);
        }
    }
}
