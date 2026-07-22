package io.github.fairyxh.volumelimiter.ui;

import android.content.pm.PackageManager;
import android.widget.Button;
import android.widget.Toast;

import java.util.Collections;
import java.util.Set;

import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.config.TemplateRepository;
import io.github.fairyxh.volumelimiter.config.VolumeTemplate;

public final class AppRuleActivity extends RemotePreferencesActivity {
    public static final String EXTRA_PACKAGE = "package_name";

    @Override
    protected void renderContent() {
        String packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        if (packageName == null || packageName.isBlank()) {
            showMessage("应用包名无效或应用已卸载");
            return;
        }
        String label = packageName;
        try {
            label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            // Keep package name so an existing rule can still be removed.
        }
        addTitle(label);
        addBody(packageName);
        addLimiter("启用应用规则", PreferenceStorage.appEnabledKey(packageName),
                PreferenceStorage.appPercentKey(packageName), "应用规则的默认上限");
        addSection("音频流上限");
        for (PreferenceStorage.StreamEntry entry : PreferenceStorage.STREAMS.values()) {
            addLimiter(entry.label, PreferenceStorage.appStreamEnabledKey(packageName, entry.key),
                    PreferenceStorage.appStreamPercentKey(packageName, entry.key),
                    "仅对此应用的该音频流生效");
        }
        addSection("输出设备上限");
        for (PreferenceStorage.DeviceEntry entry : PreferenceStorage.DEVICES.values()) {
            addLimiter(entry.label, PreferenceStorage.appDeviceEnabledKey(packageName, entry.key),
                    PreferenceStorage.appDevicePercentKey(packageName, entry.key),
                    "仅对此应用的该输出设备生效");
        }
        addSection("模板");
        for (VolumeTemplate template : TemplateRepository.read(preferences)) {
            Button apply = new Button(this);
            apply.setText("应用模板：" + template.name);
            apply.setOnClickListener(view -> {
                boolean saved = TemplateRepository.applyToPackages(preferences, template,
                        Collections.singleton(packageName));
                Toast.makeText(this, saved ? "模板已应用" : "应用模板失败",
                        Toast.LENGTH_SHORT).show();
                if (saved) {
                    content.removeAllViews();
                    renderContent();
                }
            });
            content.addView(apply, matchWrap());
        }
        Button remove = new Button(this);
        remove.setText("删除应用规则");
        remove.setOnClickListener(view -> {
            Set<String> packages = PreferenceStorage.readAppPackages(preferences);
            packages.remove(packageName);
            android.content.SharedPreferences.Editor editor = preferences.edit()
                    .putStringSet(PreferenceStorage.KEY_APP_PACKAGES, packages);
            PreferenceStorage.removeAppRule(editor, packageName);
            if (editor.commit()) {
                finish();
            } else {
                Toast.makeText(this, "删除应用规则失败", Toast.LENGTH_SHORT).show();
            }
        });
        content.addView(remove, matchWrap());
    }
}
