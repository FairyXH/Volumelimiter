package io.github.fairyxh.volumelimiter.ui;


import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.fairyxh.volumelimiter.config.BackupCodec;
import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.config.SystemRuleStore;
import io.github.fairyxh.volumelimiter.config.TemplateRepository;
import io.github.fairyxh.volumelimiter.config.VolumeTemplate;
import io.github.fairyxh.volumelimiter.utils.AndroidVersionUtils;

public final class SettingsActivity extends RemotePreferencesActivity {
    private static final int REQUEST_EXPORT = 100;
    private static final int REQUEST_IMPORT = 101;


    private static final class AppEntry {
        final String packageName;
        final String label;
        final String searchText;

        AppEntry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
            this.searchText = (label + "\n" + packageName).toLowerCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return label + "\n" + packageName;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile List<AppEntry> cachedApps;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Volumelimiter");
        worker.execute(() -> cachedApps = loadLaunchableApps());
        openRuleFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openRuleFromIntent(intent);
    }

    private void openRuleFromIntent(Intent intent) {
        String packageName = intent.getStringExtra(AppRuleActivity.EXTRA_PACKAGE);
        if (packageName != null && !packageName.isBlank()) {
            intent.removeExtra(AppRuleActivity.EXTRA_PACKAGE);
            startActivity(new Intent(this, AppRuleActivity.class)
                    .putExtra(AppRuleActivity.EXTRA_PACKAGE, packageName));
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preferences != null) {
            content.removeAllViews();
            renderContent();
        }
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void renderContent() {
        addTitle("音量限制");
        addBody("限制系统最大音量");

        addSection("当前音量");
        AudioManager audioManager = getSystemService(AudioManager.class);
        if (audioManager != null) {
            for (PreferenceStorage.StreamEntry entry : PreferenceStorage.STREAMS.values()) {
                int current = audioManager.getStreamVolume(entry.streamType);
                int max = audioManager.getStreamMaxVolume(entry.streamType);
                int percent = max > 0 ? Math.round(current * 100f / max) : 0;
                addBody(entry.label + "：" + current + " / " + max + "（" + percent + "%）");
            }
        } else {
            addBody("AudioManager 不可用");
        }
        Button refreshVolume = new Button(this);
        refreshVolume.setText("刷新当前音量");
        refreshVolume.setOnClickListener(view -> {
            content.removeAllViews();
            renderContent();
        });
        content.addView(refreshVolume, matchWrap());

        addSection("总配置");
        addLimiter("启用音量限制", PreferenceStorage.KEY_ENABLED,
                PreferenceStorage.KEY_MASTER_PERCENT, "所有设备和应用的默认上限");

        addSection("调节精度");
        addBody("设置从 0 到满音量需要按多少次音量键，例如 100 表示按约 100 次到满（每次约 最大值÷100 档）。只影响音量键步进，不改变显示最大值；留空或输入 0 使用系统默认（每次 1 档）。");
        EditText stepsInput = new EditText(this);
        stepsInput.setHint("留空使用系统默认");
        stepsInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        stepsInput.setSingleLine(true);
        int precision = PreferenceStorage.readAdjustPrecision(preferences);
        stepsInput.setText(precision > 0 ? String.valueOf(precision) : "");
        content.addView(stepsInput, matchWrap());
        LinearLayout stepsRow = new LinearLayout(this);
        Button saveSteps = new Button(this);
        saveSteps.setText("保存调节精度");
        saveSteps.setOnClickListener(view -> {
            int parsed;
            String text = stepsInput.getText().toString().trim();
            if (text.isEmpty()) {
                parsed = 0;
            } else {
                try {
                    parsed = Integer.parseInt(text);
                } catch (NumberFormatException error) {
                    toast("请输入 0-" + PreferenceStorage.MAX_ADJUST_PRECISION
                            + " 之间的整数");
                    return;
                }
            }
            if (parsed < 0 || parsed > PreferenceStorage.MAX_ADJUST_PRECISION) {
                toast("请输入 0-" + PreferenceStorage.MAX_ADJUST_PRECISION
                        + " 之间的整数");
                return;
            }
            SharedPreferences.Editor editor = preferences.edit();
            if (parsed == 0) {
                editor.remove(PreferenceStorage.KEY_ADJUST_PRECISION);
            } else {
                editor.putInt(PreferenceStorage.KEY_ADJUST_PRECISION, parsed);
            }
            if (editor.commit()) {
                toast(parsed == 0 ? "已恢复系统默认精度"
                        : "调节精度已保存为 " + parsed);
            } else {
                toast("保存失败");
            }
        });
        stepsRow.addView(saveSteps, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button restoreSteps = new Button(this);
        restoreSteps.setText("恢复默认精度");
        restoreSteps.setOnClickListener(view -> {
            if (preferences.edit().remove(PreferenceStorage.KEY_ADJUST_PRECISION).commit()) {
                stepsInput.setText("");
                toast("已恢复系统默认精度");
            } else {
                toast("操作失败");
            }
        });
        stepsRow.addView(restoreSteps, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        content.addView(stepsRow, matchWrap());

        addSection("输出设备覆盖");
        for (PreferenceStorage.DeviceEntry entry : PreferenceStorage.DEVICES.values()) {
            addLimiter(entry.label, PreferenceStorage.deviceEnabledKey(entry.key),
                    PreferenceStorage.devicePercentKey(entry.key), "覆盖总配置");
        }

        addSection("应用规则");
        addSwitch("启用分应用限制", PreferenceStorage.KEY_PER_APP_ENABLED, false, null);
        addBody("识别优先级：前台 Activity > 音量调用者 > AudioFocus 播放应用。");
        Set<String> configured = readAllRulePackages();
        addBody("已配置 " + configured.size() + " 个应用");
        Button manageApps = new Button(this);
        manageApps.setText("搜索、选择与批量应用模板");
        manageApps.setOnClickListener(view -> showAppManager());
        content.addView(manageApps, matchWrap());
        Button configuredRules = new Button(this);
        configuredRules.setText("管理已配置应用规则");
        configuredRules.setOnClickListener(view -> startActivity(
                new Intent(this, ConfiguredRulesActivity.class)));
        content.addView(configuredRules, matchWrap());
//        List<String> configuredPreview = new ArrayList<>(configured);
//        configuredPreview.sort(String::compareToIgnoreCase);
//        if (configuredPreview.size() > 20) {
//            configuredPreview = new ArrayList<>(configuredPreview.subList(0, 20));
//        }
//        for (String packageName : configuredPreview) {
//            Button edit = new Button(this);
//            edit.setText(appLabel(packageName) + "\n" + packageName);
//            edit.setOnClickListener(view -> startActivity(new Intent(this, AppRuleActivity.class)
//                    .putExtra(AppRuleActivity.EXTRA_PACKAGE, packageName)));
//            content.addView(edit, matchWrap());
//        }
//        if (configured.size() > configuredPreview.size()) {
//            addBody("其余 " + (configured.size() - configuredPreview.size())
//                    + " 条规则请在应用管理中搜索。");
//        }

        addSection("音量类别安全上限");
        for (PreferenceStorage.StreamEntry entry : PreferenceStorage.STREAMS.values()) {
            addLimiter(entry.label, PreferenceStorage.streamEnabledKey(entry.key),
                    PreferenceStorage.streamPercentKey(entry.key), "所有规则之上的额外安全上限");
        }

        renderTemplates();
        renderNewAppBehavior();
        renderBackup();

        addSection("高级设置");
        addSwitch("Debug 日志", PreferenceStorage.KEY_DEBUG, false, null);
        addBody("仅排查问题时开启。日志包含应用候选、AudioFocus、前台应用、命中规则、流、设备与最终上限。");
        addBody("Android：" + AndroidVersionUtils.describe());
        addBody("ROM：" + AndroidVersionUtils.romDescription());

        addSection("About");
        addBody("Volumelimiter\n作者：FairyXH");
        Button github = new Button(this);
        github.setText("github.com/FairyXH/Volumelimiter");
        github.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/FairyXH/Volumelimiter"))));
        content.addView(github, matchWrap());

        Button reset = new Button(this);
        reset.setText("重置全部设置");
        reset.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("重置全部设置？")
                .setMessage("将清除全局设置、应用规则、模板和新应用行为配置。")
                .setPositiveButton("重置", (dialog, which) -> {
                    if (preferences.edit().clear().commit()) {
                        systemRuleStore.clear();
                        content.removeAllViews();
                        renderContent();
                    } else {
                        toast("重置失败");
                    }
                }).setNegativeButton("取消", null).show());
        content.addView(reset, matchWrap());
    }

    private void renderTemplates() {
        addSection("模板");
        List<VolumeTemplate> templates = TemplateRepository.read(preferences);
        addBody("模板包含应用默认上限、音频流上限和输出设备上限。");
        Button create = new Button(this);
        create.setText("创建模板");
        create.setOnClickListener(view -> startActivity(new Intent(this, TemplateEditActivity.class)));
        content.addView(create, matchWrap());
        for (VolumeTemplate template : templates) {
            LinearLayout row = new LinearLayout(this);
            Button edit = new Button(this);
            edit.setText(template.name + " · " + template.masterPercent + "%");
            edit.setOnClickListener(view -> startActivity(new Intent(this, TemplateEditActivity.class)
                    .putExtra(TemplateEditActivity.EXTRA_TEMPLATE_ID, template.id)));
            row.addView(edit, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button copy = new Button(this);
            copy.setText("复制");
            copy.setOnClickListener(view -> startActivity(new Intent(this, TemplateEditActivity.class)
                    .putExtra(TemplateEditActivity.EXTRA_TEMPLATE_ID, template.id)
                    .putExtra(TemplateEditActivity.EXTRA_COPY, true)));
            row.addView(copy);
            Button delete = new Button(this);
            delete.setText("删除");
            delete.setOnClickListener(view -> confirmDeleteTemplate(template));
            row.addView(delete);
            content.addView(row, matchWrap());
        }
    }

    private void renderNewAppBehavior() {
        addSection("新安装应用行为");
        addSwitch("新应用自动套用模板", PreferenceStorage.KEY_AUTO_APPLY_NEW_APPS,
                false, null);
        List<VolumeTemplate> templates = TemplateRepository.read(preferences);
        Spinner spinner = new Spinner(this);
        List<String> names = new ArrayList<>();
        names.add("未选择默认模板");
        int selected = 0;
        String selectedId = preferences.getString(PreferenceStorage.KEY_DEFAULT_TEMPLATE_ID, "");
        for (int i = 0; i < templates.size(); i++) {
            names.add(templates.get(i).name);
            if (templates.get(i).id.equals(selectedId)) {
                selected = i + 1;
            }
        }
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                preferences.edit().putString(PreferenceStorage.KEY_DEFAULT_TEMPLATE_ID,
                        position == 0 ? "" : templates.get(position - 1).id).apply();
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        content.addView(spinner, matchWrap());
        addSwitch("自动配置后发送系统通知", PreferenceStorage.KEY_NOTIFY_NEW_APPS,
                false, null);
    }

    private void renderBackup() {
        addSection("配置导入导出");
        Button export = new Button(this);
        export.setText("导出 JSON 备份");
        export.setOnClickListener(view -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE,
                        "volumelimiter_backup.json"), REQUEST_EXPORT));
        content.addView(export, matchWrap());
        Button importButton = new Button(this);
        importButton.setText("导入 JSON 备份");
        importButton.setOnClickListener(view -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), REQUEST_IMPORT));
        content.addView(importButton, matchWrap());
    }

    private void showAppManager() {
        List<AppEntry> all = cachedApps;
        if (all == null) {
            toast("应用列表仍在后台加载，请稍后重试");
            worker.execute(() -> cachedApps = loadLaunchableApps());
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        panel.setPadding(padding, padding, padding, padding);
        EditText query = new EditText(this);
        query.setHint("搜索应用名称或包名");
        query.setSingleLine(true);
        panel.addView(query, matchWrap());
        ListView list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        ArrayAdapter<AppEntry> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, new ArrayList<>(all));
        list.setAdapter(adapter);
        Set<String> selectedPackages = new HashSet<>();
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry entry = adapter.getItem(position);
            if (list.isItemChecked(position)) {
                selectedPackages.add(entry.packageName);
            } else {
                selectedPackages.remove(entry.packageName);
            }
        });
        panel.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(400)));
        LinearLayout actions = new LinearLayout(this);
        Button selectAll = new Button(this);
        selectAll.setText("全选结果");
        selectAll.setOnClickListener(view -> {
            for (int i = 0; i < adapter.getCount(); i++) {
                AppEntry entry = adapter.getItem(i);
                selectedPackages.add(entry.packageName);
                list.setItemChecked(i, true);
            }
        });
        actions.addView(selectAll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button clear = new Button(this);
        clear.setText("取消全选");
        clear.setOnClickListener(view -> {
            selectedPackages.clear();
            list.clearChoices();
        });
        actions.addView(clear, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        panel.addView(actions, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("应用管理")
                .setView(panel).setPositiveButton("批量应用模板", null)
                .setNeutralButton("添加规则", null).setNegativeButton("关闭", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                Set<String> selected = new HashSet<>(selectedPackages);
                if (selected.isEmpty()) {
                    toast("请先选择应用");
                    return;
                }
                Set<String> configured = PreferenceStorage.readAppPackages(preferences);
                configured.addAll(selected);
                SharedPreferences.Editor editor = preferences.edit()
                        .putStringSet(PreferenceStorage.KEY_APP_PACKAGES, configured)
                        .putBoolean(PreferenceStorage.KEY_PER_APP_ENABLED, true);
                for (String packageName : selected) {
                    editor.putBoolean(PreferenceStorage.appEnabledKey(packageName), true)
                            .putInt(PreferenceStorage.appPercentKey(packageName), 100);
                }
                if (editor.commit()) {
                    dialog.dismiss();
                    content.removeAllViews();
                    renderContent();
                } else toast("保存应用规则失败");
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                Set<String> selected = new HashSet<>(selectedPackages);
                if (selected.isEmpty()) {
                    toast("请先选择应用");
                } else {
                    showTemplatePicker(selected, dialog);
                }
            });
        });
        query.addTextChangedListener(new TextWatcher() {
            Runnable pending;
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pending != null) mainHandler.removeCallbacks(pending);
                String term = s.toString().trim().toLowerCase(Locale.ROOT);
                pending = () -> worker.execute(() -> {
                    List<AppEntry> filtered = new ArrayList<>();
                    for (AppEntry entry : all) {
                        if (term.isEmpty() || entry.searchText.contains(term)) filtered.add(entry);
                    }
                    mainHandler.post(() -> {
                        adapter.clear();
                        adapter.addAll(filtered);
                        adapter.notifyDataSetChanged();
                        list.clearChoices();
                        for (int i = 0; i < adapter.getCount(); i++) {
                            list.setItemChecked(i, selectedPackages.contains(
                                    adapter.getItem(i).packageName));
                        }
                    });
                });
                mainHandler.postDelayed(pending, 150);
            }
            public void afterTextChanged(Editable s) { }
        });
        dialog.show();
    }

    private void showTemplatePicker(Set<String> packages, AlertDialog parent) {
        List<VolumeTemplate> templates = TemplateRepository.read(preferences);
        if (templates.isEmpty()) {
            toast("请先创建模板");
            return;
        }
        String[] names = templates.stream().map(template -> template.name).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("选择模板").setItems(names, (dialog, which) -> {
            if (TemplateRepository.applyToPackages(preferences, templates.get(which), packages)) {
                systemRuleStore.removeRules(packages);
                toast("已为 " + packages.size() + " 个应用应用模板");
                parent.dismiss();
                content.removeAllViews();
                renderContent();
            } else toast("批量应用模板失败");
        }).setNegativeButton("取消", null).show();
    }


    private List<AppEntry> loadLaunchableApps() {
        List<SystemRuleStore.PackageEntry> bridgePackages = systemRuleStore.listPackages();
        if (!bridgePackages.isEmpty()) {
            List<AppEntry> result = new ArrayList<>();
            for (SystemRuleStore.PackageEntry entry : bridgePackages) {
                result.add(new AppEntry(entry.packageName, entry.label));
            }
            Collator collator = Collator.getInstance(Locale.getDefault());
            result.sort(Comparator.comparing(entry -> entry.label, collator));
            return List.copyOf(result);
        }
        // Fallback: the app-process PackageManager may be restricted by HideMyAppList.
        List<ApplicationInfo> installed = getPackageManager().getInstalledApplications(0);
        List<AppEntry> result = new ArrayList<>();
        for (ApplicationInfo info : installed) {
            if (!info.packageName.equals(getPackageName())) {
                result.add(new AppEntry(info.packageName,
                        info.loadLabel(getPackageManager()).toString()));
            }
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        result.sort(Comparator.comparing(entry -> entry.label, collator));
        return List.copyOf(result);
    }

    private void confirmDeleteTemplate(VolumeTemplate template) {
        new AlertDialog.Builder(this).setTitle("删除模板？").setMessage(template.name)
                .setPositiveButton("删除", (dialog, which) -> {
                    List<VolumeTemplate> templates = new ArrayList<>(TemplateRepository.read(preferences));
                    templates.removeIf(item -> item.id.equals(template.id));
                    SharedPreferences.Editor editor = preferences.edit();
                    try {
                        editor.putString(PreferenceStorage.KEY_TEMPLATES_JSON,
                                TemplateRepository.serialize(templates));
                    } catch (JSONException error) {
                        toast("模板数据无效");
                        return;
                    }
                    if (template.id.equals(preferences.getString(
                            PreferenceStorage.KEY_DEFAULT_TEMPLATE_ID, ""))) {
                        editor.remove(PreferenceStorage.KEY_DEFAULT_TEMPLATE_ID);
                    }
                    if (editor.commit()) {
                        content.removeAllViews();
                        renderContent();
                    } else toast("删除模板失败");
                }).setNegativeButton("取消", null).show();
    }

    private void addSwitch(String title, String key, boolean fallback,
                           java.util.function.Consumer<Boolean> callback) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(16);
        toggle.setChecked(preferences.getBoolean(key, fallback));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            if (callback != null) callback.accept(checked);
        });
        content.addView(toggle, matchWrap());
    }

    private String appLabel(String packageName) {
        List<AppEntry> apps = cachedApps;
        if (apps != null) {
            for (AppEntry entry : apps) {
                if (entry.packageName.equals(packageName)) {
                    return entry.label;
                }
            }
            return packageName;
        }
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            worker.execute(() -> exportTo(uri));
        } else if (requestCode == REQUEST_IMPORT) {
            worker.execute(() -> importFrom(uri));
        }
    }

    private void exportTo(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("无法打开目标文件");
            output.write(BackupCodec.exportJson(preferences, systemRuleStore)
                    .getBytes(StandardCharsets.UTF_8));
            mainHandler.post(() -> toast("配置已导出"));
        } catch (Exception error) {
            mainHandler.post(() -> toast("导出失败：" + error.getMessage()));
        }
    }

    private void importFrom(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("无法打开备份文件");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 4 * 1024 * 1024) {
                    throw new IllegalArgumentException("备份文件超过 4 MB");
                }
                output.write(buffer, 0, read);
            }
            String json = new String(output.toByteArray(), StandardCharsets.UTF_8);
            new org.json.JSONObject(json);
            mainHandler.post(() -> new AlertDialog.Builder(this).setTitle("覆盖现有配置？")
                    .setMessage("导入将完整覆盖当前全局设置、应用规则和模板。")
                    .setPositiveButton("覆盖导入", (dialog, which) -> worker.execute(() -> {
                        try {
                            BackupCodec.importJson(preferences, json, systemRuleStore);
                            mainHandler.post(() -> {
                                toast("配置已导入");
                                content.removeAllViews();
                                renderContent();
                            });
                        } catch (Exception error) {
                            mainHandler.post(() -> toast("导入失败：" + error.getMessage()));
                        }
                    })).setNegativeButton("取消", null).show());
        } catch (Exception error) {
            mainHandler.post(() -> toast("读取失败：" + error.getMessage()));
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private Set<String> readAllRulePackages() {
        Set<String> packages = PreferenceStorage.readAppPackages(preferences);
        packages.addAll(systemRuleStore.readPackages());
        return packages;
    }
}
