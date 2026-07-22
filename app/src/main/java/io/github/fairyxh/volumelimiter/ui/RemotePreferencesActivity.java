package io.github.fairyxh.volumelimiter.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import io.github.fairyxh.volumelimiter.VolumeLimiterApp;
import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.libxposed.service.XposedService;

abstract class RemotePreferencesActivity extends Activity implements VolumeLimiterApp.ServiceListener {
    protected LinearLayout content;
    protected SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(content, matchWrap());
        setContentView(scroll);
        showMessage("正在连接 LSPosed 服务…");
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
    public void onServiceChanged(XposedService service) {
        runOnUiThread(() -> {
            preferences = service == null ? null
                    : service.getRemotePreferences(PreferenceStorage.GROUP);
            content.removeAllViews();
            if (preferences == null) {
                showMessage("LSPosed 服务未连接，配置暂不可写入");
            } else {
                renderContent();
            }
        });
    }

    protected abstract void renderContent();

    protected void addTitle(String text) {
        TextView title = text(text, 25, 0xff202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title, matchWrap());
    }

    protected void addSection(String text) {
        TextView section = text(text, 18, 0xff3f51b5);
        section.setTypeface(null, android.graphics.Typeface.BOLD);
        section.setPadding(0, dp(22), 0, dp(6));
        content.addView(section, matchWrap());
    }

    protected void addBody(String value) {
        TextView body = text(value, 14, 0xff666666);
        body.setPadding(0, dp(4), 0, dp(10));
        content.addView(body, matchWrap());
    }

    protected void addLimiter(String title, String enabledKey, String percentKey, String summary) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(16);
        toggle.setChecked(preferences.getBoolean(enabledKey, false));
        content.addView(toggle, matchWrap());
        TextView detail = text(summary, 13, 0xff777777);
        content.addView(detail, matchWrap());
        int current = PreferenceStorage.readPercent(preferences, percentKey, 100);
        TextView value = text("当前限制：" + current + "%", 14, 0xff555555);
        content.addView(value, matchWrap());
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(current);
        bar.setEnabled(toggle.isChecked());
        content.addView(bar, matchWrap());
        toggle.setOnCheckedChangeListener((button, checked) -> {
            bar.setEnabled(checked);
            preferences.edit().putBoolean(enabledKey, checked).apply();
        });
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText("当前限制：" + progress + "%");
            }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) {
                preferences.edit().putInt(percentKey, seekBar.getProgress()).apply();
            }
        });
    }

    protected TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        return view;
    }

    protected void showMessage(String value) {
        content.removeAllViews();
        addTitle("Volumelimiter");
        addBody(value);
    }

    protected LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    protected int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
