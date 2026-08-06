package io.github.fairyxh.volumelimiter.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.config.SystemRuleStore;
import io.github.libxposed.service.XposedService;

public final class ConfiguredRulesActivity extends RemotePreferencesActivity {
    private static final class RuleEntry {
        final String packageName;
        final String label;
        final Drawable icon;

        RuleEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private final Set<String> selected = new HashSet<>();
    private final Set<String> migratedPackages = new HashSet<>();
    private final Set<String> hiddenPackages = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, String> labelCache = new HashMap<>();
    private volatile boolean deleting;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        worker.execute(() -> {
            List<SystemRuleStore.PackageEntry> entries = systemRuleStore.listPackages();
            mainHandler.post(() -> {
                labelCache.clear();
                for (SystemRuleStore.PackageEntry entry : entries) {
                    labelCache.put(entry.packageName, entry.label);
                }
                if (preferences != null && !labelCache.isEmpty()) {
                    pruneUninstalledRules(labelCache.keySet());
                }
                if (preferences != null) {
                    content.removeAllViews();
                    renderContent();
                }
            });
        });
    }

    @Override
    public void onServiceChanged(XposedService service) {
        super.onServiceChanged(service);
        runOnUiThread(() -> {
            if (preferences != null) {
                worker.execute(() -> {
                    migrateOverlayRules();
                    mainHandler.post(() -> {
                        if (preferences != null) {
                            content.removeAllViews();
                            renderContent();
                        }
                    });
                });
            }
        });
    }

    private void migrateOverlayRules() {
        Set<String> overlay = systemRuleStore.readPackages();
        for (String packageName : overlay) {
            if (migratedPackages.add(packageName)) {
                systemRuleStore.migrateRuleToPreferences(preferences, packageName);
            }
        }
    }

    private void pruneUninstalledRules(Set<String> installedPackages) {
        Set<String> packages = PreferenceStorage.readAppPackages(preferences);
        Set<String> uninstalled = new HashSet<>();
        for (String packageName : packages) {
            if (!installedPackages.contains(packageName)) {
                uninstalled.add(packageName);
            }
        }
        if (uninstalled.isEmpty()) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        for (String packageName : uninstalled) {
            packages.remove(packageName);
            PreferenceStorage.removeAppRule(editor, packageName);
        }
        editor.putStringSet(PreferenceStorage.KEY_APP_PACKAGES, packages);
        editor.commit();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void renderContent() {
        addTitle("已配置应用规则");
        List<RuleEntry> entries = loadEntries();
        addBody("共 " + entries.size() + " 条独立规则");
        if (entries.isEmpty()) {
            addBody("尚未配置应用规则");
            return;
        }

        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<RuleEntry>(this, android.R.layout.simple_list_item_1, entries) {
            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout row = new LinearLayout(ConfiguredRulesActivity.this);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                CheckBox check = new CheckBox(ConfiguredRulesActivity.this);
                RuleEntry entry = getItem(position);
                check.setText(entry.label + "\n" + entry.packageName);
                check.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) selected.add(entry.packageName);
                    else selected.remove(entry.packageName);
                });
                check.setChecked(selected.contains(entry.packageName));
                Drawable icon = entry.icon;
                int size = dp(40);
                icon.setBounds(0, 0, size, size);
                check.setCompoundDrawables(icon, null, null, null);
                check.setCompoundDrawablePadding(dp(10));
                row.addView(check, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                Button edit = new Button(ConfiguredRulesActivity.this);
                edit.setText("编辑");
                edit.setOnClickListener(view -> startActivity(new Intent(
                        ConfiguredRulesActivity.this, AppRuleActivity.class)
                        .putExtra(AppRuleActivity.EXTRA_PACKAGE, entry.packageName)));
                row.addView(edit);
                return row;
            }
        });
        content.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(440)));

        LinearLayout selectionActions = new LinearLayout(this);
        addAction(selectionActions, "全选", () -> {
            for (RuleEntry entry : entries) selected.add(entry.packageName);
            refresh();
        });
        addAction(selectionActions, "反选", () -> {
            for (RuleEntry entry : entries) {
                if (!selected.add(entry.packageName)) selected.remove(entry.packageName);
            }
            refresh();
        });
        addAction(selectionActions, "取消选择", () -> {
            selected.clear();
            refresh();
        });
        content.addView(selectionActions, matchWrap());

        Button delete = new Button(this);
        delete.setText("删除所选规则");
        delete.setOnClickListener(view -> confirmDelete());
        content.addView(delete, matchWrap());
    }

    private List<RuleEntry> loadEntries() {
        List<RuleEntry> result = new ArrayList<>();
        PackageManager manager = getPackageManager();
        Drawable fallback = getDrawable(android.R.drawable.sym_def_app_icon);
        Set<String> packages = PreferenceStorage.readAppPackages(preferences);
        Set<String> overlay = systemRuleStore.readPackages();
        overlay.removeAll(hiddenPackages);
        packages.addAll(overlay);
        for (String packageName : packages) {
            String label = labelCache.get(packageName);
            Drawable icon = fallback;
            try {
                ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
                icon = manager.getApplicationIcon(info);
                if (label == null) {
                    label = manager.getApplicationLabel(info).toString();
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                if (label == null) {
                    label = packageName;
                }
            }
            result.add(new RuleEntry(packageName, label, icon));
        }
        result.sort((left, right) -> left.label.compareToIgnoreCase(right.label));
        return result;
    }

    private void addAction(LinearLayout row, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    private void confirmDelete() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择规则", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("删除所选应用规则？")
                .setMessage("将删除 " + selected.size() + " 条独立规则，不影响模板和全局设置。")
                .setPositiveButton("删除", (dialog, which) -> deleteSelected())
                .setNegativeButton("取消", null).show();
    }

    private void deleteSelected() {
        if (deleting || selected.isEmpty()) {
            return;
        }
        Set<String> toDelete = new HashSet<>(selected);
        deleting = true;
        worker.execute(() -> {
            boolean success;
            String message;
            try {
                for (String packageName : toDelete) {
                    if (migratedPackages.add(packageName)) {
                        systemRuleStore.migrateRuleToPreferences(preferences, packageName);
                    }
                }
                boolean prefsSaved = removeFromPreferences(toDelete);
                systemRuleStore.removeRules(toDelete);
                success = prefsSaved;
                message = success ? "已删除 " + toDelete.size() + " 条规则" : "删除规则失败";
            } catch (Throwable error) {
                success = false;
                message = "删除规则失败：" + error;
            }
            final boolean deleted = success;
            final String text = message;
            mainHandler.post(() -> {
                deleting = false;
                if (deleted) {
                    selected.removeAll(toDelete);
                    hiddenPackages.addAll(toDelete);
                }
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
                refresh();
            });
        });
    }

    private boolean removeFromPreferences(Set<String> packageNames) {
        Set<String> packages = PreferenceStorage.readAppPackages(preferences);
        SharedPreferences.Editor editor = preferences.edit();
        for (String packageName : packageNames) {
            packages.remove(packageName);
            PreferenceStorage.removeAppRule(editor, packageName);
        }
        editor.putStringSet(PreferenceStorage.KEY_APP_PACKAGES, packages);
        return editor.commit();
    }

    private void refresh() {
        content.removeAllViews();
        renderContent();
    }
}
