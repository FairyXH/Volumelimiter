package io.github.fairyxh.volumelimiter.config;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class BackupCodec {
    public static final int VERSION = 1;

    private BackupCodec() {
    }

    public static String exportJson(SharedPreferences preferences) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", VERSION);
        JSONObject global = new JSONObject();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.equals(PreferenceStorage.KEY_TEMPLATES_JSON)
                    || key.equals(PreferenceStorage.KEY_APP_PACKAGES)
                    || key.startsWith("app_")
                    || key.equals(PreferenceStorage.KEY_AUTO_APPLY_NEW_APPS)
                    || key.equals(PreferenceStorage.KEY_DEFAULT_TEMPLATE_ID)
                    || key.equals(PreferenceStorage.KEY_NOTIFY_NEW_APPS)) {
                continue;
            }
            putValue(global, key, entry.getValue());
        }
        root.put("global", global);
        root.put("templates", new JSONArray(TemplateRepository.serialize(
                TemplateRepository.read(preferences))));
        root.put("applications", exportApplications(preferences));
        root.put("newAppBehavior", new JSONObject()
                .put("enabled", preferences.getBoolean(
                        PreferenceStorage.KEY_AUTO_APPLY_NEW_APPS, false))
                .put("defaultTemplateId", preferences.getString(
                        PreferenceStorage.KEY_DEFAULT_TEMPLATE_ID, ""))
                .put("notify", preferences.getBoolean(
                        PreferenceStorage.KEY_NOTIFY_NEW_APPS, false)));
        return root.toString(2);
    }

    public static void importJson(SharedPreferences preferences, String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        int version = root.getInt("version");
        if (version != VERSION) {
            throw new JSONException("不支持的配置版本：" + version);
        }
        JSONObject global = root.getJSONObject("global");
        JSONArray templates = root.getJSONArray("templates");
        JSONObject applications = root.getJSONObject("applications");
        JSONObject behavior = root.getJSONObject("newAppBehavior");
        TemplateRepository.parse(templates.toString());

        SharedPreferences.Editor editor = preferences.edit().clear();
        java.util.Iterator<String> globalKeys = global.keys();
        while (globalKeys.hasNext()) {
            String key = globalKeys.next();
            writeJsonValue(editor, key, global.get(key));
        }
        Set<String> packages = new HashSet<>();
        java.util.Iterator<String> applicationKeys = applications.keys();
        while (applicationKeys.hasNext()) {
            String packageName = applicationKeys.next();
            JSONObject rule = applications.getJSONObject(packageName);
            packages.add(packageName);
            editor.putBoolean(PreferenceStorage.appEnabledKey(packageName),
                    rule.optBoolean("enabled", true));
            editor.putInt(PreferenceStorage.appPercentKey(packageName),
                    checkedPercent(rule.getInt("master"), packageName + " master"));
            writeRuleMap(editor, packageName, rule.optJSONObject("streams"), true);
            writeRuleMap(editor, packageName, rule.optJSONObject("devices"), false);
        }
        editor.putStringSet(PreferenceStorage.KEY_APP_PACKAGES, packages)
                .putString(PreferenceStorage.KEY_TEMPLATES_JSON, templates.toString())
                .putBoolean(PreferenceStorage.KEY_AUTO_APPLY_NEW_APPS,
                        behavior.optBoolean("enabled", false))
                .putString(PreferenceStorage.KEY_DEFAULT_TEMPLATE_ID,
                        behavior.optString("defaultTemplateId", ""))
                .putBoolean(PreferenceStorage.KEY_NOTIFY_NEW_APPS,
                        behavior.optBoolean("notify", false));
        if (!editor.commit()) {
            throw new JSONException("写入 Remote Preferences 失败");
        }
    }

    private static JSONObject exportApplications(SharedPreferences preferences) throws JSONException {
        JSONObject applications = new JSONObject();
        for (String packageName : PreferenceStorage.readAppPackages(preferences)) {
            JSONObject rule = new JSONObject();
            rule.put("enabled", preferences.getBoolean(
                    PreferenceStorage.appEnabledKey(packageName), true));
            rule.put("master", PreferenceStorage.readPercent(preferences,
                    PreferenceStorage.appPercentKey(packageName), 100));
            JSONObject streams = new JSONObject();
            for (PreferenceStorage.StreamEntry stream : PreferenceStorage.STREAMS.values()) {
                if (preferences.getBoolean(PreferenceStorage.appStreamEnabledKey(
                        packageName, stream.key), false)) {
                    streams.put(stream.key, PreferenceStorage.readPercent(preferences,
                            PreferenceStorage.appStreamPercentKey(packageName, stream.key), 100));
                }
            }
            JSONObject devices = new JSONObject();
            for (PreferenceStorage.DeviceEntry device : PreferenceStorage.DEVICES.values()) {
                if (preferences.getBoolean(PreferenceStorage.appDeviceEnabledKey(
                        packageName, device.key), false)) {
                    devices.put(device.key, PreferenceStorage.readPercent(preferences,
                            PreferenceStorage.appDevicePercentKey(packageName, device.key), 100));
                }
            }
            rule.put("streams", streams);
            rule.put("devices", devices);
            applications.put(packageName, rule);
        }
        return applications;
    }

    private static void writeRuleMap(SharedPreferences.Editor editor, String packageName,
                                     JSONObject values, boolean stream) throws JSONException {
        if (values == null) {
            return;
        }
        Map<String, ?> allowed = stream ? streamEntriesByKey() : PreferenceStorage.DEVICES;
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.containsKey(key)) {
                continue;
            }
            int percent = checkedPercent(values.getInt(key), key);
            editor.putBoolean(stream ? PreferenceStorage.appStreamEnabledKey(packageName, key)
                    : PreferenceStorage.appDeviceEnabledKey(packageName, key), true);
            editor.putInt(stream ? PreferenceStorage.appStreamPercentKey(packageName, key)
                    : PreferenceStorage.appDevicePercentKey(packageName, key), percent);
        }
    }

    private static Map<String, PreferenceStorage.StreamEntry> streamEntriesByKey() {
        Map<String, PreferenceStorage.StreamEntry> result = new HashMap<>();
        for (PreferenceStorage.StreamEntry entry : PreferenceStorage.STREAMS.values()) {
            result.put(entry.key, entry);
        }
        return result;
    }

    private static int checkedPercent(int value, String field) throws JSONException {
        if (value < 0 || value > 100) {
            throw new JSONException(field + " 必须在 0 到 100 之间");
        }
        return value;
    }

    private static void putValue(JSONObject object, String key, Object value) throws JSONException {
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof String) {
            object.put(key, value);
        }
    }

    private static void writeJsonValue(SharedPreferences.Editor editor, String key, Object value)
            throws JSONException {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Number) {
            editor.putFloat(key, ((Number) value).floatValue());
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else {
            throw new JSONException("不支持的字段类型：" + key);
        }
    }
}
