package io.github.fairyxh.volumelimiter.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Compatibility shell for older installed manifests. New installation handling is performed in
 * system_server by {@code SystemServerNewAppMonitor}; this receiver must not duplicate it.
 */
public final class NewAppReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                || intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                || intent.getData() == null) {
            return;
        }
        // Intentionally no-op. The current manifest no longer registers this component.
    }
}
