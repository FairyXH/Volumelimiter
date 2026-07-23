package io.github.fairyxh.volumelimiter.config;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class TemplateRepository {
    private TemplateRepository() {
    }

    public static List<VolumeTemplate> read(SharedPreferences preferences) {
        try {
            return parse(preferences.getString(PreferenceStorage.KEY_TEMPLATES_JSON, "[]"));
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
    }

    public static List<VolumeTemplate> parse(String json) throws JSONException {
        JSONArray array = new JSONArray(json == null ? "[]" : json);
        List<VolumeTemplate> templates = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            templates.add(VolumeTemplate.fromJson(array.getJSONObject(i)));
        }
        return templates;
    }

    public static String serialize(List<VolumeTemplate> templates) throws JSONException {
        JSONArray array = new JSONArray();
        for (VolumeTemplate template : templates) {
            array.put(template.toJson());
        }
        return array.toString();
    }

    public static boolean save(SharedPreferences preferences, List<VolumeTemplate> templates) {
        try {
            return preferences.edit().putString(PreferenceStorage.KEY_TEMPLATES_JSON,
                    serialize(templates)).commit();
        } catch (JSONException error) {
            return false;
        }
    }

    public static VolumeTemplate find(SharedPreferences preferences, String id) {
        if (id == null) {
            return null;
        }
        for (VolumeTemplate template : read(preferences)) {
            if (id.equals(template.id)) {
                return template;
            }
        }
        return null;
    }

    public static boolean applyToPackages(SharedPreferences preferences, VolumeTemplate template,
                                          Set<String> packageNames) {
        if (template == null || packageNames.isEmpty()) {
            return false;
        }
        Set<String> configured = PreferenceStorage.readAppPackages(preferences);
        configured.addAll(packageNames);
        SharedPreferences.Editor editor = preferences.edit()
                .putStringSet(PreferenceStorage.KEY_APP_PACKAGES, configured)
                .putBoolean(PreferenceStorage.KEY_PER_APP_ENABLED, true);
        for (String packageName : packageNames) {
            SystemRuleStore.writeRule(editor, packageName,
                    SystemRuleStore.Rule.fromTemplate(template));
        }
        return editor.commit();
    }
}
