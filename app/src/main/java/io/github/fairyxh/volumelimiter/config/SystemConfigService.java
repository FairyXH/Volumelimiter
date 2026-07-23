package io.github.fairyxh.volumelimiter.config;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import java.lang.reflect.Method;

import io.github.fairyxh.volumelimiter.utils.LogUtils;

/**
 * UID-checked request bridge registered by system_server after systemReady.
 * It avoids adding a custom ServiceManager name, which requires platform SELinux policy changes.
 */
public final class SystemConfigService {
    public static final String ACTION_REQUEST =
            "io.github.fairyxh.volumelimiter.action.SYSTEM_CONFIG_REQUEST";
    public static final String REQUEST_PERMISSION =
            "io.github.fairyxh.volumelimiter.permission.SYSTEM_CONFIG";
    public static final String EXTRA_OPERATION = "operation";
    public static final String EXTRA_DOCUMENT = "document";
    public static final String EXTRA_ERROR = "error";
    public static final String OPERATION_READ = "read";
    public static final String OPERATION_REPLACE = "replace";
    public static final int RESULT_SUCCESS = 1;
    public static final int RESULT_FAILURE = 2;

    private static final String MODULE_PACKAGE = "io.github.fairyxh.volumelimiter";

    private SystemConfigService() {
    }

    /** Must be called after ActivityManagerService.systemReady(). */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static boolean install(Context context, SystemRuleStore store, Handler handler) {
        try {
            int uid = resolveModuleUid(context);
            if (uid <= 0) {
                LogUtils.error("[VLM][Config] Config bridge not registered: module uid unavailable", null);
                return false;
            }
            IntentFilter filter = new IntentFilter(ACTION_REQUEST);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    int sendingUid = resolveSendingUid(this);
                    if (sendingUid > 0 && sendingUid != uid) {
                        LogUtils.error("[VLM][Config] Rejected config request from uid="
                                + sendingUid, null);
                        setResultCode(RESULT_FAILURE);
                        setResultData("caller rejected");
                        return;
                    }
                    handleRequest(store, intent, this);
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, REQUEST_PERMISSION, handler,
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, REQUEST_PERMISSION, handler);
            }
            LogUtils.info("[VLM][Config] System config bridge registered for uid=" + uid);
            return true;
        } catch (Throwable error) {
            LogUtils.error("[VLM][Config] System config bridge registration failed", error);
            return false;
        }
    }

    private static void handleRequest(SystemRuleStore store, Intent intent,
            BroadcastReceiver receiver) {
        try {
            String operation = intent == null ? null : intent.getStringExtra(EXTRA_OPERATION);
            if (OPERATION_READ.equals(operation)) {
                receiver.setResultCode(RESULT_SUCCESS);
                receiver.setResultData(store.readDocument());
                return;
            }
            if (OPERATION_REPLACE.equals(operation)) {
                store.replaceDocument(intent.getStringExtra(EXTRA_DOCUMENT));
                receiver.setResultCode(RESULT_SUCCESS);
                receiver.setResultData(null);
                return;
            }
            throw new IllegalArgumentException("unsupported operation");
        } catch (Throwable error) {
            receiver.setResultCode(RESULT_FAILURE);
            receiver.setResultData(error.getClass().getSimpleName() + ": " + error.getMessage());
            LogUtils.error("[VLM][Config] Config request failed", error);
        }
    }

    private static int resolveSendingUid(BroadcastReceiver receiver) {
        try {
            Method method = BroadcastReceiver.class.getMethod("getSentFromUid");
            Object value = method.invoke(receiver);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable error) {
            LogUtils.debug("[VLM][Config] Sender UID API unavailable; signature permission enforced");
            return -1;
        }
    }

    private static int resolveModuleUid(Context context) {
        try {
            PackageManager packageManager = context == null ? null : context.getPackageManager();
            if (packageManager == null) {
                return -1;
            }
            return packageManager.getPackageUid(MODULE_PACKAGE, 0);
        } catch (Throwable error) {
            LogUtils.error("[VLM][Config] Module uid lookup failed", error);
            return -1;
        }
    }
}
