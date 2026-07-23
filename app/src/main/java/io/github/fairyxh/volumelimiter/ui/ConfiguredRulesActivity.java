package io.github.fairyxh.volumelimiter.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.fairyxh.volumelimiter.config.PreferenceStorage;

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
                check.setChecked(selected.contains(entry.packageName));
                Drawable icon = entry.icon;
                int size = dp(40);
                icon.setBounds(0, 0, size, size);
                check.setCompoundDrawables(icon, null, null, null);
                check.setCompoundDrawablePadding(dp(10));
                check.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) selected.add(entry.packageName);
                    else selected.remove(entry.packageName);
                });
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
        packages.addAll(systemRuleStore.readPackages());
        for (String packageName : packages) {
            String label = packageName;
            Drawable icon = fallback;
            try {
                ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
                label = manager.getApplicationLabel(info).toString();
                icon = manager.getApplicationIcon(info);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Keep package name and default icon for uninstalled applications.
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
        Set<String> packages = PreferenceStorage.readAppPackages(preferences);
        SharedPreferences.Editor editor = preferences.edit();
        for (String packageName : selected) {
            packages.remove(packageName);
            PreferenceStorage.removeAppRule(editor, packageName);
        }
        editor.putStringSet(PreferenceStorage.KEY_APP_PACKAGES, packages);
        boolean remoteSaved = editor.commit();
        boolean overlaySaved = remoteSaved && systemRuleStore.removeRules(selected);
        if (remoteSaved && overlaySaved) {
            selected.clear();
            refresh();
        } else {
            Toast.makeText(this, "删除规则失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void refresh() {
        content.removeAllViews();
        renderContent();
    }
}
