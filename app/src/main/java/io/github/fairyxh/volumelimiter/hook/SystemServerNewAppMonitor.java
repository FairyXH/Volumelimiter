package io.github.fairyxh.volumelimiter.hook;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.config.SystemRuleStore;
import io.github.fairyxh.volumelimiter.config.TemplateRepository;
import io.github.fairyxh.volumelimiter.config.VolumeTemplate;
import io.github.fairyxh.volumelimiter.ui.AppRuleActivity;
import io.github.fairyxh.volumelimiter.ui.SettingsActivity;
import io.github.fairyxh.volumelimiter.utils.LogUtils;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Hooks the late ActivityManager lifecycle from system_server and registers the package monitor
 * only after {@code ActivityManagerService.systemReady()} has completed.
 */
public final class SystemServerNewAppMonitor {
    private static final String MODULE_PACKAGE = "io.github.fairyxh.volumelimiter";
    private static final String LOG_PREFIX = "[VLM][PackageMonitor] ";
    private static final String NOTIFICATION_LOG_PREFIX = "[VLM][Notification] ";
    private static final String CHANNEL_ID = "volumelimiter_new_apps";
    private static final String CHANNEL_NAME = "Volumelimiter 自动配置";
    private static final String[] ACTIVITY_MANAGER_SERVICE_CLASSES = {
            "com.android.server.am.ActivityManagerService",
            "com.android.server.am.ActivityManagerService$Lifecycle"
    };

    private final XposedModule module;
    private final SharedPreferences preferences;
    private final SystemRuleStore systemRuleStore;
    private final Runnable configurationChanged;
    private final AtomicBoolean receiverRegistrationStarted = new AtomicBoolean(false);
    private final AtomicBoolean configServiceRegistrationStarted = new AtomicBoolean(false);
    private volatile Handler worker;

    public SystemServerNewAppMonitor(XposedModule module, SharedPreferences preferences,
            SystemRuleStore systemRuleStore, Runnable configurationChanged) {
        this.module = module;
        this.preferences = preferences;
        this.systemRuleStore = systemRuleStore;
        this.configurationChanged = configurationChanged;
    }

    /**
     * Runs during onSystemServerStarting. It deliberately installs hooks only and never touches
     * Context.registerReceiver(), because ActivityManager is not ready at this point.
     */
    public int install(ClassLoader classLoader) {
        int installed = 0;
        Set<String> installedSignatures = new HashSet<>();
        for (String className : ACTIVITY_MANAGER_SERVICE_CLASSES) {
            try {
                Class<?> type = Class.forName(className, false, classLoader);
                installed += hookSystemReadyMethods(type, classLoader, installedSignatures);
            } catch (Throwable error) {
                LogUtils.debug(LOG_PREFIX + className + " unavailable: " + error);
            }
        }
        if (installed == 0) {
            LogUtils.error(LOG_PREFIX + "Waiting for systemReady hook failed\nreason: no compatible "
                    + "ActivityManagerService.systemReady method was found", null);
        } else {
            LogUtils.info(LOG_PREFIX + "Waiting for systemReady; hooked " + installed
                    + " compatible method(s)");
        }
        return installed;
    }

    private int hookSystemReadyMethods(Class<?> type, ClassLoader classLoader,
            Set<String> installedSignatures) {
        int installed = 0;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!"systemReady".equals(method.getName()) || method.isSynthetic()
                        || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                String signature = method.toGenericString();
                if (!installedSignatures.add(signature)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    module.hook(method)
                            .setPriority(XposedInterface.PRIORITY_LOWEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                registerReceiverAfterSystemReady(classLoader);
                                return result;
                            });
                    installed++;
                    LogUtils.debug(LOG_PREFIX + "Hooked " + method);
                } catch (Throwable error) {
                    LogUtils.error(LOG_PREFIX + "Unable to hook " + method + "\nreason: "
                            + error, error);
                }
            }
        }
        return installed;
    }

    private void registerReceiverAfterSystemReady(ClassLoader classLoader) {
        if (!receiverRegistrationStarted.compareAndSet(false, true)) {
            return;
        }
        Context context = findSystemContext(classLoader);
        if (context == null) {
            LogUtils.error(LOG_PREFIX + "Register failed\nreason: system context unavailable after "
                    + "ActivityManagerService.systemReady", null);
            return;
        }
        if (configServiceRegistrationStarted.compareAndSet(false, true)
                && !io.github.fairyxh.volumelimiter.config.SystemConfigService.install(
                context, systemRuleStore, getOrCreateWorker())) {
            LogUtils.error("[VLM][Config] system config service unavailable after systemReady", null);
        }
        Handler registrationWorker = getOrCreateWorker();
        try {
            IntentFilter packageFilter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            packageFilter.addDataScheme("package");
            BroadcastReceiver packageReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    Uri data = intent.getData();
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) || data == null) {
                        return;
                    }
                    String packageName = data.getSchemeSpecificPart();
                    LogUtils.info(LOG_PREFIX + "Package installed: " + packageName);
                    registrationWorker.post(() -> processPackage(context, packageName));
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(packageReceiver, packageFilter, null, registrationWorker,
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(packageReceiver, packageFilter, null, registrationWorker);
            }
            LogUtils.info(LOG_PREFIX + "PACKAGE_ADDED receiver registered");
        } catch (Throwable error) {
            LogUtils.error(LOG_PREFIX + "Register failed\nreason: " + error, error);
        }
    }

    private Handler getOrCreateWorker() {
        Handler current = worker;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (worker != null) {
                return worker;
            }
            HandlerThread thread = new HandlerThread("Volumelimiter-install-events");
            thread.start();
            worker = new Handler(thread.getLooper());
            return worker;
        }
    }

    private void processPackage(Context context, String packageName) {
        LogUtils.info("[VLM][NewApp] PACKAGE_ADDED received");
        String step = "validation";
        try {
            LogUtils.setDebug(preferences.getBoolean(PreferenceStorage.KEY_DEBUG, false));
            if (packageName == null || packageName.isBlank() || MODULE_PACKAGE.equals(packageName)
                    || !preferences.getBoolean(PreferenceStorage.KEY_AUTO_APPLY_NEW_APPS, false)) {
                LogUtils.info("[VLM][NewApp] Ignored"
                        + "\npackage=" + packageName
                        + "\nstep=" + step
                        + "\nreason=event disabled or package name rejected");
                return;
            }
            LogUtils.info("[VLM][NewApp] packageName=" + packageName);
            step = "reading template";
            String templateId = preferences.getString(
                    PreferenceStorage.KEY_DEFAULT_TEMPLATE_ID, "");
            step = "matching template";
            if (PreferenceStorage.readAppPackages(preferences).contains(packageName)
                    || systemRuleStore.readPackages().contains(packageName)) {
                LogUtils.info("[VLM][NewApp] Ignored"
                        + "\npackage=" + packageName
                        + "\nstep=" + step
                        + "\nreason=existing rule");
                return;
            }
            VolumeTemplate template = TemplateRepository.find(preferences, templateId);
            if (template == null) {
                LogUtils.info("[VLM][NewApp] Ignored"
                        + "\npackage=" + packageName
                        + "\nstep=" + step
                        + "\nreason=default template not found"
                        + "\ntemplateId=" + templateId);
                return;
            }
            LogUtils.info("[VLM][NewApp] Template matched: " + template.name);
            step = "creating rule";
            SystemRuleStore.SaveResult saveResult = systemRuleStore.applyTemplateIfAbsent(
                    preferences, packageName, template);
            if (!saveResult.saved) {
                logProcessingFailure(packageName, saveResult.step,
                        saveResult.reason, saveResult.error);
                return;
            }
            step = "configuration save";
            configurationChanged.run();
            LogUtils.info("[VLM][NewApp] Configuration saved");
            step = "notification";
            if (!notifyConfigured(context, packageName, template.name)) {
                return;
            }
            LogUtils.info("[VLM][NewApp] Completed");
        } catch (Throwable error) {
            logProcessingFailure(packageName, step, error.toString(), error);
        }
    }

    private static void logProcessingFailure(String packageName, String step, String reason,
            Throwable error) {
        String exception = error == null ? "none" : error.getClass().getName()
                + ": " + error.getMessage();
        LogUtils.error("[VLM][NewApp] Processing failed"
                + "\npackage=" + packageName
                + "\nstep=" + step
                + "\nreason=" + reason
                + "\nexception=" + exception,
                error);
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
            LogUtils.debug(LOG_PREFIX + "Unable to obtain system context: " + error);
            return null;
        }
    }

    @SuppressLint("NotificationPermission")
    private boolean notifyConfigured(Context context, String packageName, String templateName) {
        LogUtils.info(NOTIFICATION_LOG_PREFIX + "Preparing notification for package: "
                + packageName);
        try {
            if (!preferences.getBoolean(PreferenceStorage.KEY_NOTIFY_NEW_APPS, false)) {
                LogUtils.info(NOTIFICATION_LOG_PREFIX + "Notification disabled by module setting");
                return true;
            }
            if (packageName == null || packageName.isBlank()
                    || templateName == null || templateName.isBlank()) {
                throw new IllegalArgumentException("missing package or template name in completion");
            }
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) {
                throw new IllegalStateException("NotificationManager is unavailable");
            }
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
            if (channel == null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                        CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT));
                channel = manager.getNotificationChannel(CHANNEL_ID);
            }
            if (channel == null) {
                throw new IllegalStateException("notification channel was not created");
            }
            if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                throw new IllegalStateException("notification channel is disabled by the user");
            }
            if (!manager.areNotificationsEnabled()) {
                throw new IllegalStateException("notifications are disabled for Android System");
            }

            String label = resolveApplicationLabel(context, packageName);
            Intent open = new Intent()
                    .setComponent(new ComponentName(MODULE_PACKAGE,
                            SettingsActivity.class.getName()))
                    .putExtra(AppRuleActivity.EXTRA_PACKAGE, packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, packageName.hashCode(),
                    open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Volumelimiter")
                    .setContentText("已为" + label + "自动应用模板「" + templateName + "」")
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build();

            LogUtils.info(NOTIFICATION_LOG_PREFIX + "Sending notification");
            manager.notify(CHANNEL_ID, packageName.hashCode(), notification);
            LogUtils.info(NOTIFICATION_LOG_PREFIX + "Notification posted successfully");
            return true;
        } catch (Throwable error) {
            logProcessingFailure(packageName, "notification", error.toString(), error);
            return false;
        }
    }

    private static String resolveApplicationLabel(Context context, String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            return context.getPackageManager().getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }
}
