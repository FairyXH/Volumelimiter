package io.github.fairyxh.volumelimiter.receiver;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.config.TemplateRepository;
import io.github.fairyxh.volumelimiter.config.VolumeTemplate;


public final class NewAppProcessor {
    private static final String LOCAL_PREFS = "new_app_queue";
    private static final String KEY_PENDING = "pending";


    private NewAppProcessor() {
    }

    public static void enqueue(Context context, String packageName) {
        if (packageName == null || packageName.equals(context.getPackageName())) {
            return;
        }
        SharedPreferences local = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
        Set<String> pending = new HashSet<>(local.getStringSet(KEY_PENDING, Collections.emptySet()));
        pending.add(packageName);
        local.edit().putStringSet(KEY_PENDING, pending).apply();
    }

    public static synchronized void process(Context context, SharedPreferences remote) {
        SharedPreferences local = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
        if (!remote.getBoolean(PreferenceStorage.KEY_AUTO_APPLY_NEW_APPS, false)) {
            local.edit().remove(KEY_PENDING).apply();
            return;
        }
        Set<String> pending = new HashSet<>(local.getStringSet(KEY_PENDING, Collections.emptySet()));
        if (pending.isEmpty()) {
            return;
        }
        VolumeTemplate template = TemplateRepository.find(remote,
                remote.getString(PreferenceStorage.KEY_DEFAULT_TEMPLATE_ID, ""));
        if (template == null || !TemplateRepository.applyToPackages(remote, template, pending)) {
            return;
        }
        local.edit().remove(KEY_PENDING).apply();
    }
}
