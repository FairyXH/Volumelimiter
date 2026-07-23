package io.github.fairyxh.volumelimiter.config;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.fairyxh.volumelimiter.utils.LogUtils;

public final class SystemRuleStore {
    private static final File SYSTEM_CONFIG_DIR = new File("/data/system/volumelimiter");
    private static final String CONFIG_FILE = "system_rules.json";
    private static final long REMOTE_TIMEOUT_SECONDS = 5;

    private static final class RemoteHandlerHolder {
        private static final Handler INSTANCE = createRemoteResponseHandler();
    }

    public static final class Rule {
        public final boolean enabled;
        public final int masterPercent;
        public final Map<String, Integer> streamPercents;
        public final Map<String, Integer> devicePercents;

        Rule(boolean enabled, int masterPercent, Map<String, Integer> streamPercents,
                Map<String, Integer> devicePercents) {
            this.enabled = enabled;
            this.masterPercent = LimitPolicy.clamp(masterPercent);
            this.streamPercents = immutableClamped(streamPercents);
            this.devicePercents = immutableClamped(devicePercents);
        }

        static Rule fromTemplate(VolumeTemplate template) {
            return new Rule(true, template.masterPercent,
                    template.streamPercents, template.devicePercents);
        }

        static Rule fromJson(JSONObject object) throws JSONException {
            return new Rule(object.optBoolean("enabled", true),
                    checkedPercent(object.getInt("master"), "master"),
                    readPercentMap(object.optJSONObject("streams"), streamEntriesByKey()),
                    readPercentMap(object.optJSONObject("devices"), PreferenceStorage.DEVICES));
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("enabled", enabled)
                    .put("master", masterPercent)
                    .put("streams", new JSONObject(streamPercents))
                    .put("devices", new JSONObject(devicePercents));
        }
    }

    public static final class SaveResult {
        public final boolean saved;
        public final String step;
        public final String reason;
        public final Throwable error;

        private SaveResult(boolean saved, String step, String reason, Throwable error) {
            this.saved = saved;
            this.step = step;
            this.reason = reason;
            this.error = error;
        }

        static SaveResult saved() {
            return new SaveResult(true, "configuration save", null, null);
        }

        static SaveResult failed(String step, String reason, Throwable error) {
            return new SaveResult(false, step, reason, error);
        }
    }

    private final AtomicFile file;
    private final Context remoteContext;

    public SystemRuleStore(File file) {
        this.file = new AtomicFile(file);
        this.remoteContext = null;
    }

    private SystemRuleStore(Context remoteContext) {
        this.file = null;
        this.remoteContext = remoteContext.getApplicationContext();
    }

    public static SystemRuleStore forModuleRemote(Context context) {
        return context == null ? null : new SystemRuleStore(context);
    }

    public FileObserver observe(Runnable changed) {
        if (remoteContext != null) {
            return null;
        }
        File baseFile = file.getBaseFile();
        File parent = baseFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            return null;
        }
        FileObserver observer = new FileObserver(parent,
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.DELETE) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && path.equals(baseFile.getName())) {
                    changed.run();
                }
            }
        };
        observer.startWatching();
        return observer;
    }

    public static SystemRuleStore forSystemServer() {
        File configFile = new File(SYSTEM_CONFIG_DIR, CONFIG_FILE);
        LogUtils.info("[VLM][Config] SystemRuleStore path=" + configFile.getAbsolutePath());
        return new SystemRuleStore(configFile);
    }

    public SaveResult applyTemplateIfAbsent(SharedPreferences preferences, String packageName,
            VolumeTemplate template) {
        if (template == null) {
            return SaveResult.failed("creating rule", "template is null", null);
        }
        try {
            Map<String, Rule> rules = readRules();
            if (PreferenceStorage.readAppPackages(preferences).contains(packageName)
                    || rules.containsKey(packageName)) {
                return SaveResult.failed("creating rule", "existing rule", null);
            }
            rules.put(packageName, Rule.fromTemplate(template));
            writeRules(rules);
            return SaveResult.saved();
        } catch (Throwable error) {
            return SaveResult.failed("configuration save", error.toString(), error);
        }
    }

    public Map<String, Rule> readRules() throws IOException, JSONException {
        if (remoteContext != null) {
            return parseRules(readRemoteDocument());
        }
        return parseRules(readLocalDocument());
    }

    public String readDocument() throws IOException, JSONException {
        if (remoteContext != null) {
            return readRemoteDocument();
        }
        return readLocalDocument();
    }

    public void replaceDocument(String document) throws IOException, JSONException {
        if (document == null || document.length() > 4 * 1024 * 1024) {
            throw new IOException("configuration document is invalid");
        }
        Map<String, Rule> rules = parseRules(document);
        writeRules(rules);
    }

    public Set<String> readPackages() {
        try {
            return new HashSet<>(readRules().keySet());
        } catch (Throwable ignored) {
            return new HashSet<>();
        }
    }

    public Rule readRule(String packageName) {
        try {
            return readRules().get(packageName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public boolean migrateRuleToPreferences(SharedPreferences preferences, String packageName) {
        try {
            Map<String, Rule> rules = readRules();
            Rule rule = rules.get(packageName);
            if (rule == null || PreferenceStorage.readAppPackages(preferences).contains(packageName)) {
                return true;
            }
            Set<String> packages = PreferenceStorage.readAppPackages(preferences);
            packages.add(packageName);
            SharedPreferences.Editor editor = preferences.edit()
                    .putStringSet(PreferenceStorage.KEY_APP_PACKAGES, packages)
                    .putBoolean(PreferenceStorage.KEY_PER_APP_ENABLED, true);
            writeRule(editor, packageName, rule);
            if (!editor.commit()) {
                return false;
            }
            rules.remove(packageName);
            writeRules(rules);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean removeRules(Set<String> packageNames) {
        if (packageNames == null || packageNames.isEmpty()) {
            return true;
        }
        try {
            Map<String, Rule> rules = readRules();
            boolean changed = false;
            for (String packageName : packageNames) {
                changed |= rules.remove(packageName) != null;
            }
            if (changed) {
                writeRules(rules);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean clear() {
        try {
            writeRules(Collections.emptyMap());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public JSONObject exportApplications(SharedPreferences preferences) throws JSONException {
        JSONObject applications = new JSONObject();
        try {
            for (Map.Entry<String, Rule> entry : readRules().entrySet()) {
                if (!PreferenceStorage.readAppPackages(preferences).contains(entry.getKey())) {
                    applications.put(entry.getKey(), entry.getValue().toJson());
                }
            }
        } catch (IOException error) {
            throw new JSONException("读取 system_server 规则失败: " + error);
        }
        return applications;
    }

    public static void writeRule(SharedPreferences.Editor editor, String packageName, Rule rule) {
        editor.putBoolean(PreferenceStorage.appEnabledKey(packageName), rule.enabled)
                .putInt(PreferenceStorage.appPercentKey(packageName), rule.masterPercent);
        for (PreferenceStorage.StreamEntry entry : PreferenceStorage.STREAMS.values()) {
            Integer percent = rule.streamPercents.get(entry.key);
            editor.putBoolean(PreferenceStorage.appStreamEnabledKey(packageName, entry.key),
                    percent != null);
            if (percent == null) {
                editor.remove(PreferenceStorage.appStreamPercentKey(packageName, entry.key));
            } else {
                editor.putInt(PreferenceStorage.appStreamPercentKey(packageName, entry.key), percent);
            }
        }
        for (PreferenceStorage.DeviceEntry entry : PreferenceStorage.DEVICES.values()) {
            Integer percent = rule.devicePercents.get(entry.key);
            editor.putBoolean(PreferenceStorage.appDeviceEnabledKey(packageName, entry.key),
                    percent != null);
            if (percent == null) {
                editor.remove(PreferenceStorage.appDevicePercentKey(packageName, entry.key));
            } else {
                editor.putInt(PreferenceStorage.appDevicePercentKey(packageName, entry.key), percent);
            }
        }
    }

    private String readRemoteDocument() throws IOException {
        String document = sendRemoteRequest(SystemConfigService.OPERATION_READ, null);
        return document == null ? emptyDocument() : document;
    }

    private String readLocalDocument() throws IOException {
        if (!file.getBaseFile().isFile()) {
            return emptyDocument();
        }
        try (FileInputStream input = file.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void writeRules(Map<String, Rule> rules) throws IOException, JSONException {
        JSONObject applications = new JSONObject();
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            applications.put(entry.getKey(), entry.getValue().toJson());
        }
        writeDocument(new JSONObject().put("version", 1)
                .put("applications", applications).toString(2));
    }

    private void writeDocument(String document) throws IOException {
        if (remoteContext != null) {
            sendRemoteRequest(SystemConfigService.OPERATION_REPLACE, document);
            return;
        }
        File baseFile = file.getBaseFile();
        File parent = baseFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Unable to create " + parent);
        }
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(document.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException error) {
            if (output != null) {
                file.failWrite(output);
            }
            throw error;
        }
    }

    private String sendRemoteRequest(String operation, String document) throws IOException {
        CountDownLatch completed = new CountDownLatch(1);
        int[] resultCode = {SystemConfigService.RESULT_FAILURE};
        String[] resultData = {"system_server config bridge unavailable"};
        BroadcastReceiver resultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                resultCode[0] = getResultCode();
                resultData[0] = getResultData();
                completed.countDown();
            }
        };
        Intent request = new Intent(SystemConfigService.ACTION_REQUEST)
                .setPackage("android")
                .putExtra(SystemConfigService.EXTRA_OPERATION, operation);
        if (document != null) {
            request.putExtra(SystemConfigService.EXTRA_DOCUMENT, document);
        }
        remoteContext.sendOrderedBroadcast(request, null, resultReceiver,
                RemoteHandlerHolder.INSTANCE, SystemConfigService.RESULT_FAILURE,
                "system_server config bridge unavailable", null);
        try {
            if (!completed.await(REMOTE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("system_server config bridge timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("system_server config bridge interrupted", error);
        }
        if (resultCode[0] != SystemConfigService.RESULT_SUCCESS) {
            throw new IOException(resultData[0] == null
                    ? "system_server config request failed" : resultData[0]);
        }
        return resultData[0];
    }

    private static Handler createRemoteResponseHandler() {
        HandlerThread thread = new HandlerThread("Volumelimiter-config-response");
        thread.start();
        return new Handler(thread.getLooper());
    }

    private static Map<String, Rule> parseRules(String document) throws JSONException {
        JSONObject root = new JSONObject(document == null ? emptyDocument() : document);
        JSONObject applications = root.optJSONObject("applications");
        Map<String, Rule> result = new LinkedHashMap<>();
        if (applications == null) {
            return result;
        }
        java.util.Iterator<String> keys = applications.keys();
        while (keys.hasNext()) {
            String packageName = keys.next();
            result.put(packageName, Rule.fromJson(applications.getJSONObject(packageName)));
        }
        return result;
    }

    private static String emptyDocument() {
        return "{\"version\":1,\"applications\":{}}";
    }

    private static Map<String, Integer> immutableClamped(Map<String, Integer> source) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                result.put(entry.getKey(), LimitPolicy.clamp(entry.getValue()));
            }
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

    private static Map<String, PreferenceStorage.StreamEntry> streamEntriesByKey() {
        Map<String, PreferenceStorage.StreamEntry> result = new HashMap<>();
        for (PreferenceStorage.StreamEntry entry : PreferenceStorage.STREAMS.values()) {
            result.put(entry.key, entry);
        }
        return result;
    }

    private static int checkedPercent(int value, String field) throws JSONException {
        if (value < 0 || value > 100) {
            throw new JSONException(field + " must be between 0 and 100");
        }
        return value;
    }
}
