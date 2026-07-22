package io.github.fairyxh.volumelimiter.ui;

import android.content.Intent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.fairyxh.volumelimiter.config.PreferenceStorage;
import io.github.fairyxh.volumelimiter.config.TemplateRepository;
import io.github.fairyxh.volumelimiter.config.VolumeTemplate;

public final class TemplateEditActivity extends RemotePreferencesActivity {
    public static final String EXTRA_TEMPLATE_ID = "template_id";
    public static final String EXTRA_COPY = "copy";

    private static final class OptionalLimit {
        final String key;
        final CheckBox enabled;
        final SeekBar percent;

        OptionalLimit(String key, CheckBox enabled, SeekBar percent) {
            this.key = key;
            this.enabled = enabled;
            this.percent = percent;
        }
    }

    @Override
    protected void renderContent() {
        String id = getIntent().getStringExtra(EXTRA_TEMPLATE_ID);
        boolean copy = getIntent().getBooleanExtra(EXTRA_COPY, false);
        VolumeTemplate original = TemplateRepository.find(preferences, id);
        VolumeTemplate template = original == null
                ? new VolumeTemplate(null, "", 100, Map.of(), Map.of()) : original;

        addTitle(original == null ? "创建模板" : copy ? "复制模板" : "编辑模板");
        EditText name = new EditText(this);
        name.setHint("模板名称");
        name.setSingleLine(true);
        name.setText(copy ? template.name + " 副本" : original == null ? "" : template.name);
        content.addView(name, matchWrap());

        addSection("默认上限");
        TextView masterValue = text(template.masterPercent + "%", 14, 0xff555555);
        content.addView(masterValue, matchWrap());
        SeekBar master = new SeekBar(this);
        master.setMax(100);
        master.setProgress(template.masterPercent);
        master.setOnSeekBarChangeListener(labelListener(masterValue));
        content.addView(master, matchWrap());

        List<OptionalLimit> streams = new ArrayList<>();
        addSection("音频流上限");
        for (PreferenceStorage.StreamEntry entry : PreferenceStorage.STREAMS.values()) {
            streams.add(addOptionalLimit(entry.key, entry.label, template.streamPercents.get(entry.key)));
        }
        List<OptionalLimit> devices = new ArrayList<>();
        addSection("输出设备上限");
        for (PreferenceStorage.DeviceEntry entry : PreferenceStorage.DEVICES.values()) {
            devices.add(addOptionalLimit(entry.key, entry.label, template.devicePercents.get(entry.key)));
        }

        Button save = new Button(this);
        save.setText("保存模板");
        save.setOnClickListener(view -> {
            String templateName = name.getText().toString().trim();
            if (templateName.isEmpty()) {
                name.setError("请输入模板名称");
                return;
            }
            Map<String, Integer> streamValues = collect(streams);
            Map<String, Integer> deviceValues = collect(devices);
            String savedId = original != null && !copy ? original.id : null;
            VolumeTemplate changed = new VolumeTemplate(savedId, templateName,
                    master.getProgress(), streamValues, deviceValues);
            List<VolumeTemplate> all = new ArrayList<>(TemplateRepository.read(preferences));
            if (original != null && !copy) {
                all.removeIf(item -> item.id.equals(original.id));
            }
            all.add(changed);
            if (TemplateRepository.save(preferences, all)) {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_TEMPLATE_ID, changed.id));
                finish();
            } else {
                Toast.makeText(this, "保存模板失败", Toast.LENGTH_SHORT).show();
            }
        });
        content.addView(save, matchWrap());
    }

    private OptionalLimit addOptionalLimit(String key, String label, Integer value) {
        CheckBox enabled = new CheckBox(this);
        enabled.setText(label);
        enabled.setChecked(value != null);
        content.addView(enabled, matchWrap());
        TextView percentLabel = text((value == null ? 100 : value) + "%", 14, 0xff555555);
        content.addView(percentLabel, matchWrap());
        SeekBar percent = new SeekBar(this);
        percent.setMax(100);
        percent.setProgress(value == null ? 100 : value);
        percent.setEnabled(enabled.isChecked());
        percent.setOnSeekBarChangeListener(labelListener(percentLabel));
        enabled.setOnCheckedChangeListener((button, checked) -> percent.setEnabled(checked));
        content.addView(percent, matchWrap());
        return new OptionalLimit(key, enabled, percent);
    }

    private static Map<String, Integer> collect(List<OptionalLimit> limits) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (OptionalLimit limit : limits) {
            if (limit.enabled.isChecked()) {
                values.put(limit.key, limit.percent.getProgress());
            }
        }
        return values;
    }

    private SeekBar.OnSeekBarChangeListener labelListener(TextView label) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(progress + "%");
            }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) { }
        };
    }
}
