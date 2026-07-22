package io.github.fairyxh.volumelimiter.config;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class VolumeTemplate {
    public final String id;
    public final String name;
    public final int masterPercent;
    public final Map<String, Integer> streamPercents;
    public final Map<String, Integer> devicePercents;

    public VolumeTemplate(String id, String name, int masterPercent,
                          Map<String, Integer> streamPercents,
                          Map<String, Integer> devicePercents) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.name = name == null || name.isBlank() ? "未命名模板" : name.trim();
        this.masterPercent = LimitPolicy.clamp(masterPercent);
        this.streamPercents = immutableClamped(streamPercents);
        this.devicePercents = immutableClamped(devicePercents);
    }

    public VolumeTemplate copy(String newName) {
        return new VolumeTemplate(null, newName, masterPercent, streamPercents, devicePercents);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("name", name);
        object.put("master", masterPercent);
        object.put("streams", new JSONObject(streamPercents));
        object.put("devices", new JSONObject(devicePercents));
        return object;
    }

    public static VolumeTemplate fromJson(JSONObject object) throws JSONException {
        String name = object.getString("name").trim();
        if (name.isEmpty()) {
            throw new JSONException("模板名称不能为空");
        }
        int master = checkedPercent(object.getInt("master"), "模板总限制");
        return new VolumeTemplate(object.optString("id", null), name, master,
                readPercentMap(object.optJSONObject("streams"), streamKeys()),
                readPercentMap(object.optJSONObject("devices"), PreferenceStorage.DEVICES));
    }

    private static Map<String, PreferenceStorage.StreamEntry> streamKeys() {
        Map<String, PreferenceStorage.StreamEntry> result = new LinkedHashMap<>();
        for (PreferenceStorage.StreamEntry entry : PreferenceStorage.STREAMS.values()) {
            result.put(entry.key, entry);
        }
        return result;
    }

    private static Map<String, Integer> immutableClamped(Map<String, Integer> source) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            result.put(entry.getKey(), LimitPolicy.clamp(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Integer> readPercentMap(JSONObject object, Map<?, ?> allowed)
            throws JSONException {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (object == null) {
            return result;
        }
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (allowed.containsKey(key)) {
                result.put(key, checkedPercent(object.getInt(key), key));
            }
        }
        return result;
    }

    private static int checkedPercent(int value, String field) throws JSONException {
        if (value < 0 || value > 100) {
            throw new JSONException(field + " 必须在 0 到 100 之间");
        }
        return value;
    }
}
