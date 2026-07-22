package io.github.fairyxh.volumelimiter.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.fairyxh.volumelimiter.VolumeLimiterApp;
import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.utils.AndroidVersionUtils;
import io.github.libxposed.service.XposedService;

public final class SettingsActivity extends Activity implements VolumeLimiterApp.ServiceListener {
    private static final class AppEntry {
        final String packageName;
        final String label;

        AppEntry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private LinearLayout content;
    private XposedService service;
    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Volumelimiter");
        ScrollView scrollView = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(32));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);
        renderDisconnected();
    }

    @Override
    protected void onStart() {
        super.onStart();
        VolumeLimiterApp.addListener(this);
    }

    @Override
    protected void onStop() {
        VolumeLimiterApp.removeListener(this);
        super.onStop();
    }

    @Override
    public void onServiceChanged(XposedService boundService) {
        runOnUiThread(() -> {
            service = boundService;
            preferences = service == null ? null
                    : service.getRemotePreferences(PreferenceStorage.GROUP);
            render();
        });
    }

    private void render() {
        content.removeAllViews();
        addTitle("音量限制");
        addBody(service == null
                ? "LSPosed 服务未连接，配置暂不可写入"
                : "LSPosed API " + service.getApiVersion() + " · 已连接");
        if (preferences == null) {
            return;
        }

        addSection("总配置");
        addBody("默认作用于所有输出设备。设备或前台应用有自己的配置时，按应用 > 设备 > 总配置的顺序覆盖。");
        addLimiter("启用音量限制", PreferenceStorage.KEY_ENABLED,
                PreferenceStorage.KEY_MASTER_PERCENT, "所有设备的默认上限");

        addSection("输出设备覆盖");
        addBody("仅开启需要单独覆盖总配置的设备。未开启的设备继续使用总配置。");
        for (PreferenceStorage.DeviceEntry entry : PreferenceStorage.DEVICES.values()) {
            addLimiter(entry.label, PreferenceStorage.deviceEnabledKey(entry.key),
                    PreferenceStorage.devicePercentKey(entry.key), "覆盖总配置");
        }

        addSection("分应用限制");
        addSwitch("启用分应用限制", "仅当前台应用有已启用规则时覆盖设备/总配置",
                PreferenceStorage.KEY_PER_APP_ENABLED, false);
        for (String packageName : sortedConfiguredPackages()) {
            addAppLimiter(packageName);
        }
        Button addApp = new Button(this);
        addApp.setText("添加应用规则");
        addApp.setOnClickListener(view -> showAppPicker());
        content.addView(addApp, matchWrap());

        addSection("音量类别安全上限（可选）");
        addBody("若启用，作为额外安全上限与上述结果取较小值。例如媒体设为 60%，即使某设备覆盖为 80%，媒体仍最多 60%。");
        for (PreferenceStorage.StreamEntry entry : PreferenceStorage.STREAMS.values()) {
            addLimiter(entry.label, PreferenceStorage.streamEnabledKey(entry.key),
                    PreferenceStorage.streamPercentKey(entry.key), "额外安全上限");
        }

        addSection("高级设置");
        addSwitch("Debug 日志", "仅排查兼容问题时开启", PreferenceStorage.KEY_DEBUG, false);
        addInfoRow("Hook 状态", "模块是否成功加载请查看 LSPosed 日志");
        addInfoRow("Android", AndroidVersionUtils.describe());
        addInfoRow("ROM", AndroidVersionUtils.romDescription());
        addInfoRow("作用域", "system / system_server");

        Button reset = new Button(this);
        reset.setText("重置全部设置");
        reset.setOnClickListener(view -> confirmReset());
        content.addView(reset, matchWrap());
    }

    private void renderDisconnected() {
        content.removeAllViews();
        addTitle("音量限制");
        addBody("正在连接 LSPosed 服务…");
    }

    private void addLimiter(String title, String enabledKey, String percentKey, String summary) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(8), 0, dp(12));

        Switch enabled = new Switch(this);
        enabled.setText(title);
        enabled.setTextSize(16);
        enabled.setChecked(preferences.getBoolean(enabledKey, false));
        block.addView(enabled, matchWrap());

        TextView detail = addText(summary, 13, 0xff777777);
        block.addView(detail, matchWrap());

        TextView value = new TextView(this);
        value.setTextSize(14);
        int current = PreferenceStorage.readPercent(preferences, percentKey, 100);
        value.setText("当前限制：" + current + "%");
        block.addView(value, matchWrap());

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress(current);
        seekBar.setEnabled(enabled.isChecked());
        block.addView(seekBar, matchWrap());

        enabled.setOnCheckedChangeListener((button, checked) -> {
            seekBar.setEnabled(checked);
            edit().putBoolean(enabledKey, checked).apply();
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText("当前限制：" + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                edit().putInt(percentKey, bar.getProgress()).apply();
            }
        });
        content.addView(block, matchWrap());
    }

    private void addAppLimiter(String packageName) {
        String label;
        try {
            PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            label = packageName;
        }
        addLimiter(label + "\n" + packageName,
                PreferenceStorage.appEnabledKey(packageName),
                PreferenceStorage.appPercentKey(packageName), "前台时覆盖设备/总配置");
        Button remove = new Button(this);
        remove.setText("删除 " + label + " 规则");
        remove.setOnClickListener(view -> removeAppRule(packageName));
        content.addView(remove, matchWrap());
    }

    private List<String> sortedConfiguredPackages() {
        List<String> packages = new ArrayList<>(PreferenceStorage.readAppPackages(preferences));
        packages.sort(String::compareToIgnoreCase);
        return packages;
    }

    private void showAppPicker() {
        List<AppEntry> apps = loadLaunchableApps();
        if (apps.isEmpty()) {
            Toast.makeText(this, "未找到可选择的应用", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppEntry app = apps.get(i);
            labels[i] = app.label + "\n" + app.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择应用")
                .setItems(labels, (dialog, which) -> addAppRule(apps.get(which).packageName))
                .setNegativeButton("取消", null)
                .show();
    }

    private List<AppEntry> loadLaunchableApps() {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(intent, 0);
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : resolved) {
            String packageName = info.activityInfo.packageName;
            if (getPackageName().equals(packageName) || !seen.add(packageName)) {
                continue;
            }
            apps.add(new AppEntry(packageName, info.loadLabel(getPackageManager()).toString()));
        }
        apps.sort(Comparator.comparing(entry -> entry.label, String.CASE_INSENSITIVE_ORDER));
        return apps;
    }

    private void addAppRule(String packageName) {
        Set<String> packages = PreferenceStorage.readAppPackages(preferences);
        packages.add(packageName);
        boolean saved = edit().putStringSet(PreferenceStorage.KEY_APP_PACKAGES, packages)
                .putBoolean(PreferenceStorage.appEnabledKey(packageName), true)
                .putInt(PreferenceStorage.appPercentKey(packageName), 100)
                .commit();
        if (saved) {
            render();
        } else {
            Toast.makeText(this, "保存应用规则失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void removeAppRule(String packageName) {
        Set<String> packages = PreferenceStorage.readAppPackages(preferences);
        packages.remove(packageName);
        boolean saved = edit().putStringSet(PreferenceStorage.KEY_APP_PACKAGES, packages)
                .remove(PreferenceStorage.appEnabledKey(packageName))
                .remove(PreferenceStorage.appPercentKey(packageName))
                .commit();
        if (saved) {
            render();
        } else {
            Toast.makeText(this, "删除应用规则失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重置全部设置？")
                .setMessage("将关闭所有限制并清除设备、应用和音量类别配置。")
                .setPositiveButton("重置", (dialog, which) -> {
                    if (edit().clear().commit()) {
                        render();
                        Toast.makeText(this, "设置已重置", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "重置失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addSwitch(String title, String summary, String key, boolean fallback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(12));
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(16);
        toggle.setChecked(preferences.getBoolean(key, fallback));
        toggle.setOnCheckedChangeListener((CompoundButton button, boolean checked) ->
                edit().putBoolean(key, checked).apply());
        row.addView(toggle, matchWrap());
        row.addView(addText(summary, 14, 0xff777777), matchWrap());
        content.addView(row, matchWrap());
    }

    private void addSection(String text) {
        Space space = new Space(this);
        content.addView(space, new LinearLayout.LayoutParams(1, dp(14)));
        TextView view = addText(text, 18, 0xff3f51b5);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(view, matchWrap());
    }

    private void addInfoRow(String name, String value) {
        TextView view = addText(name + "\n" + value, 14, 0xff666666);
        view.setPadding(0, dp(7), 0, dp(7));
        content.addView(view, matchWrap());
    }

    private void addTitle(String text) {
        TextView title = addText(text, 26, 0xff202124);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title, matchWrap());
    }

    private TextView addBody(String text) {
        TextView body = addText(text, 14, 0xff666666);
        body.setPadding(0, dp(4), 0, dp(12));
        content.addView(body, matchWrap());
        return body;
    }

    private TextView addText(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        return view;
    }

    private SharedPreferences.Editor edit() {
        SharedPreferences.Editor editor = preferences.edit();
        if (editor == null) {
            throw new IllegalStateException("LSPosed remote preferences are read-only");
        }
        return editor;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
