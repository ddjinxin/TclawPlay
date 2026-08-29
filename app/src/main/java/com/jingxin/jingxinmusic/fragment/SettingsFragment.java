package com.jingxin.jingxinmusic.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.util.MusicScanner;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.util.ThemeHelper;
import com.jingxin.jingxinmusic.view.LyricColorBar;

import java.util.List;

public class SettingsFragment extends BaseFloatFragment {

    public static final String PREFS_NAME = "app_settings";
    public static final String KEY_FLOAT_WINDOW_ENABLED = "float_window_enabled";
    public static final String KEY_SPECTRUM_ENABLED = "spectrum_enabled";
    public static final String KEY_LOCAL_COVER_PRIORITY = "local_cover_priority";
    public static final String KEY_LOCAL_LYRIC_PRIORITY = "local_lyric_priority";
    public static final String KEY_AUTO_RESUME = "auto_resume";

    // 歌词高亮颜色 preferences 存在 "theme" SharedPreferences 中
    public static final String KEY_DAY_LYRIC_COLOR = "day_lyric_color";
    public static final String KEY_NIGHT_LYRIC_COLOR = "night_lyric_color";
    public static final int DEFAULT_DAY_LYRIC_COLOR = 0xFFE53935;   // 默认白天高亮红色
    public static final int DEFAULT_NIGHT_LYRIC_COLOR = 0xFFFFEB3B; // 默认夜间高亮黄色

    private boolean isNightMode;
    private ScrollView rootScroll;
    private TextView tvTitle;
    private View dividerTop, dividerMid, dividerMid2, dividerMid3, dividerMid4, dividerMid5, dividerMid6, dividerMid7;
    private ImageView btnBack;
    private TextView[] styleTabs = new TextView[4];
    private int currentStyleIndex = 0;
    private SwitchCompat switchNight, switchFloat, switchSpectrum, switchLocalCover, switchLocalLyric, switchAutoResume, switchAutoTheme;
    private TextView tvNightTitle, tvStyleTitle, tvFloatTitle, tvSpectrumTitle, tvLyricTitle, tvLocalCoverTitle, tvLocalLyricTitle, tvAutoResumeTitle, tvAutoThemeTitle;
    private TextView tvNightDesc, tvStyleDesc, tvFloatDesc, tvSpectrumDesc, tvLyricDesc, tvLocalCoverDesc, tvLocalLyricDesc, tvAutoResumeDesc, tvAutoThemeDesc;
    private TextView tvDayStartLabel, tvDayStartValue, tvNightStartLabel, tvNightStartValue, tvTimeSeparator;
    private View timeSelectorRow;
    private TextView btnDayReset, btnNightReset;
    private TextView btnScan;
    private TextView tvScanTitle, tvScanDesc;
    private ProgressBar scanProgress;
    private boolean isScanning = false;
    // 文件访问权限
    private TextView btnStoragePerm;
    private TextView tvStoragePermTitle, tvStoragePermDesc;

    /** 设置项标题颜色：白天黑色，夜间白色 */
    private int titleColor() {
        return isNightMode ? 0xFFFFFFFF : 0xFF000000;
    }

    /** 设置项描述/次要文字颜色 */
    private int secondaryColor() {
        return isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary();
    }

    /** 统一设置一个设置项的标题和描述颜色 */
    private void applyItemTheme(TextView title, TextView desc) {
        if (title != null) title.setTextColor(titleColor());
        if (desc != null) desc.setTextColor(secondaryColor());
    }

    /** 读取悬浮窗开关状态（默认开启） */
    public static boolean isFloatWindowEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FLOAT_WINDOW_ENABLED, true);
    }

    /** 读取频谱显示开关状态（默认开启） */
    public static boolean isSpectrumEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SPECTRUM_ENABLED, true);
    }

    /** 读取优先读取本地封面开关状态（默认开启） */
    public static boolean isLocalCoverPriority(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCAL_COVER_PRIORITY, true);
    }

    /** 读取优先读取本地歌词开关状态（默认开启） */
    public static boolean isLocalLyricPriority(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCAL_LYRIC_PRIORITY, true);
    }

    /** 读取启动直达播放开关状态（默认开启） */
    public static boolean isAutoResumeEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_RESUME, true);
    }

    /** 读取白天高亮歌词颜色 */
    public static int getDayLyricColor(Context context) {
        return context.getSharedPreferences("theme", Context.MODE_PRIVATE)
                .getInt(KEY_DAY_LYRIC_COLOR, DEFAULT_DAY_LYRIC_COLOR);
    }

    /** 读取夜间高亮歌词颜色 */
    public static int getNightLyricColor(Context context) {
        return context.getSharedPreferences("theme", Context.MODE_PRIVATE)
                .getInt(KEY_NIGHT_LYRIC_COLOR, DEFAULT_NIGHT_LYRIC_COLOR);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        isNightMode = requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE)
                .getBoolean("isNight", true);

        initViews(view);
        applyTheme();
        setupScanButton(view);
        setupStoragePermission(view);
        setupNightModeSwitch(view);
        setupAutoTheme(view);
        setupStyleSelector(view);
        setupFloatWindowSwitch(view);
        setupSpectrumSwitch(view);
        setupLocalCoverSwitch(view);
        setupLocalLyricSwitch(view);
        setupAutoResumeSwitch(view);
        setupLyricColorSelector(view);

        applyTopInset(rootScroll);

        return view;
    }

    private void initViews(View view) {
        rootScroll = view.findViewById(R.id.root_scroll);
        tvTitle = view.findViewById(R.id.tv_title);
        dividerTop = view.findViewById(R.id.divider_top);
        dividerMid = view.findViewById(R.id.divider_mid);
        dividerMid2 = view.findViewById(R.id.divider_mid2);
        dividerMid3 = view.findViewById(R.id.divider_mid3);
        dividerMid4 = view.findViewById(R.id.divider_mid4);
        dividerMid5 = view.findViewById(R.id.divider_mid5);
        dividerMid6 = view.findViewById(R.id.divider_mid6);
        dividerMid7 = view.findViewById(R.id.divider_mid7);
        btnBack = view.findViewById(R.id.btn_back);
        styleTabs[0] = view.findViewById(R.id.style_tab_0);
        styleTabs[1] = view.findViewById(R.id.style_tab_1);
        styleTabs[2] = view.findViewById(R.id.style_tab_2);
        styleTabs[3] = view.findViewById(R.id.style_tab_3);
        // 时间选择行的 TextView 提前到 initViews 初始化，确保 applyTheme 能覆盖颜色
        tvDayStartLabel = view.findViewById(R.id.tv_day_start_label);
        tvDayStartValue = view.findViewById(R.id.tv_day_start_value);
        tvTimeSeparator = view.findViewById(R.id.tv_time_separator);
        tvNightStartLabel = view.findViewById(R.id.tv_night_start_label);
        tvNightStartValue = view.findViewById(R.id.tv_night_start_value);
        timeSelectorRow = view.findViewById(R.id.time_selector_row);
    }

    private void applyTheme() {
        rootScroll.setBackground(ThemeColors.bgGradient(isNightMode));
        int tc = titleColor();
        int ts = secondaryColor();
        tvTitle.setTextColor(tc);
        // 刷新所有标题
        TextView[] titles = {tvNightTitle, tvAutoThemeTitle, tvStyleTitle, tvFloatTitle, tvSpectrumTitle,
                tvLyricTitle, tvScanTitle, btnScan, tvStoragePermTitle, btnStoragePerm, tvLocalCoverTitle, tvLocalLyricTitle, tvAutoResumeTitle};
        for (TextView t : titles) if (t != null) t.setTextColor(tc);
        // 刷新所有描述
        TextView[] descs = {tvNightDesc, tvAutoThemeDesc, tvStyleDesc, tvFloatDesc, tvSpectrumDesc,
                tvLyricDesc, tvScanDesc, tvStoragePermDesc, btnDayReset, btnNightReset, tvLocalCoverDesc, tvLocalLyricDesc, tvAutoResumeDesc};
        for (TextView d : descs) if (d != null) d.setTextColor(ts);
        // 时间选择行文字
        TextView[] timeTexts = {tvDayStartLabel, tvDayStartValue, tvTimeSeparator, tvNightStartLabel, tvNightStartValue};
        for (TextView t : timeTexts) if (t != null) t.setTextColor(ts);
        // 风格 tab 颜色
        updateStyleTabColors();
        int divColor = isNightMode ? ThemeColors.nightDivider() : ThemeColors.dayDivider();
        View[] dividers = {dividerTop, dividerMid, dividerMid2, dividerMid3, dividerMid4, dividerMid5, dividerMid6, dividerMid7};
        for (View d : dividers) d.setBackgroundColor(divColor);
        if (isNightMode) {
            btnBack.clearColorFilter();
        } else {
            btnBack.setColorFilter(ThemeColors.dayTextPrimary(), PorterDuff.Mode.SRC_IN);
        }
    }

    private void setupStoragePermission(View view) {
        btnStoragePerm = view.findViewById(R.id.btn_storage_perm);
        tvStoragePermTitle = view.findViewById(R.id.tv_storage_perm_title);
        tvStoragePermDesc = view.findViewById(R.id.tv_storage_perm_desc);
        applyItemTheme(tvStoragePermTitle, tvStoragePermDesc);
        if (btnStoragePerm != null) btnStoragePerm.setTextColor(titleColor());

        updateStoragePermissionUI();

        btnStoragePerm.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(requireContext(), "文件访问权限已开启", Toast.LENGTH_SHORT).show();
                } else {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        try {
                            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivity(intent);
                        } catch (Exception e2) {
                            Toast.makeText(requireContext(), "无法打开设置页，请手动前往系统设置", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            } else {
                Toast.makeText(requireContext(), "当前系统版本不需要此权限", Toast.LENGTH_SHORT).show();
                btnStoragePerm.setText("已开启");
                btnStoragePerm.setEnabled(false);
            }
        });
    }

    private void updateStoragePermissionUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                btnStoragePerm.setText("已开启");
                btnStoragePerm.setEnabled(false);
                tvStoragePermDesc.setText("U盘音乐扫描权限已就绪");
            } else {
                btnStoragePerm.setText("去授权");
                btnStoragePerm.setEnabled(true);
                tvStoragePermDesc.setText("U盘音乐扫描需要此权限");
            }
        } else {
            btnStoragePerm.setText("已开启");
            btnStoragePerm.setEnabled(false);
            tvStoragePermDesc.setText("当前系统版本不需要此权限");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从系统授权页返回后刷新按钮状态
        if (btnStoragePerm != null) {
            updateStoragePermissionUI();
        }
    }

    private void setupScanButton(View view) {
        btnScan = view.findViewById(R.id.btn_scan);
        scanProgress = view.findViewById(R.id.scan_progress);
        tvScanTitle = view.findViewById(R.id.tv_scan_title);
        tvScanDesc = view.findViewById(R.id.tv_scan_desc);
        applyItemTheme(tvScanTitle, tvScanDesc);
        if (btnScan != null) btnScan.setTextColor(titleColor());

        btnScan.setOnClickListener(v -> {
            if (isScanning) return;
            isScanning = true;
            btnScan.setText("扫描中…");
            btnScan.setEnabled(false);
            scanProgress.setVisibility(View.VISIBLE);

            MusicScanner.manualScan(requireContext(), songs -> {
                requireActivity().runOnUiThread(() -> {
                    isScanning = false;
                    btnScan.setText("扫描");
                    btnScan.setEnabled(true);
                    scanProgress.setVisibility(View.GONE);
                    Toast.makeText(requireContext(),
                            "扫描完成，共 " + (songs != null ? songs.size() : 0) + " 首",
                            Toast.LENGTH_SHORT).show();
                });
            });
        });
    }

    private void setupNightModeSwitch(View view) {
        switchNight = view.findViewById(R.id.switch_night_mode);
        tvNightTitle = view.findViewById(R.id.tv_night_mode_title);
        tvNightDesc = view.findViewById(R.id.tv_night_mode_desc);
        applyItemTheme(tvNightTitle, tvNightDesc);

        SharedPreferences themePrefs = requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE);
        boolean isNight = themePrefs.getBoolean("isNight", true);
        switchNight.setChecked(isNight);
        applySwitchTint(switchNight);

        switchNight.setOnCheckedChangeListener((buttonView, isChecked) -> {
            themePrefs.edit()
                    .putBoolean("isNight", isChecked)
                    .putBoolean("amapTriggered", false)
                    .apply();
            isNightMode = isChecked;
            applyTheme();
            applySwitchTint(switchNight);
            applySwitchTint(switchFloat);
            applySwitchTint(switchSpectrum);
            applySwitchTint(switchLocalCover);
            applySwitchTint(switchLocalLyric);
            applySwitchTint(switchAutoResume);
            applySwitchTint(switchAutoTheme);
            Toast.makeText(requireContext(), isChecked ? "夜间模式" : "白天模式", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupAutoTheme(View view) {
        switchAutoTheme = view.findViewById(R.id.switch_auto_theme);
        tvAutoThemeTitle = view.findViewById(R.id.tv_auto_theme_title);
        tvAutoThemeDesc = view.findViewById(R.id.tv_auto_theme_desc);
        // tvDayStartValue/tvNightStartValue/timeSelectorRow 已在 initViews() 中初始化
        applyItemTheme(tvAutoThemeTitle, tvAutoThemeDesc);

        SharedPreferences themePrefs = requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE);
        boolean autoEnabled = themePrefs.getBoolean(ThemeHelper.KEY_AUTO_THEME, false);
        switchAutoTheme.setChecked(autoEnabled);
        applySwitchTint(switchAutoTheme);

        int dayStart = themePrefs.getInt(ThemeHelper.KEY_DAY_START, 6);
        int nightStart = themePrefs.getInt(ThemeHelper.KEY_NIGHT_START, 18);
        tvDayStartValue.setText(ThemeHelper.formatHour(dayStart));
        tvNightStartValue.setText(ThemeHelper.formatHour(nightStart));
        timeSelectorRow.setVisibility(autoEnabled ? View.VISIBLE : View.GONE);

        // 自动切换开启时，禁用手动日夜开关
        updateNightSwitchEnabled(!autoEnabled);

        switchAutoTheme.setOnCheckedChangeListener((buttonView, isChecked) -> {
            themePrefs.edit().putBoolean(ThemeHelper.KEY_AUTO_THEME, isChecked).apply();
            timeSelectorRow.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            updateNightSwitchEnabled(!isChecked);
            applySwitchTint(switchAutoTheme);
            applySwitchTint(switchNight);
            if (isChecked) {
                // 立即应用一次时间判断
                ThemeHelper.applyAutoTheme(requireContext());
                boolean nowNight = themePrefs.getBoolean("isNight", true);
                switchNight.setOnCheckedChangeListener(null);
                switchNight.setChecked(nowNight);
                switchNight.setOnCheckedChangeListener((bw, checked) -> {
                    themePrefs.edit().putBoolean("isNight", checked).putBoolean("amapTriggered", false).apply();
                    isNightMode = checked;
                    applyTheme();
                    applySwitchTint(switchNight);
                });
                isNightMode = nowNight;
                applyTheme();
                Toast.makeText(requireContext(), "已开启，当前" + (nowNight ? "夜间" : "白天"), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "已关闭自动切换", Toast.LENGTH_SHORT).show();
            }
        });

        // 点击白天时间弹出 NumberPicker
        tvDayStartValue.setOnClickListener(v -> showHourPicker(dayStart, nightStart, true, themePrefs));
        tvNightStartValue.setOnClickListener(v -> showHourPicker(dayStart, nightStart, false, themePrefs));
    }

    private void updateNightSwitchEnabled(boolean enabled) {
        if (switchNight != null) {
            switchNight.setEnabled(enabled);
            switchNight.setAlpha(enabled ? 1.0f : 0.5f);
        }
        if (tvNightTitle != null) {
            tvNightTitle.setAlpha(enabled ? 1.0f : 0.5f);
        }
        if (tvNightDesc != null) {
            tvNightDesc.setAlpha(enabled ? 1.0f : 0.5f);
        }
    }

    private void showHourPicker(int currentDayStart, int currentNightStart, boolean isDayStart, SharedPreferences themePrefs) {
        int current = isDayStart ? currentDayStart : currentNightStart;
        // 用系统 TimePickerDialog 简单实现
        android.app.TimePickerDialog dialog = new android.app.TimePickerDialog(
                requireContext(),
                (view, hourOfDay, minute) -> {
                    if (isDayStart) {
                        themePrefs.edit().putInt(ThemeHelper.KEY_DAY_START, hourOfDay).apply();
                        tvDayStartValue.setText(ThemeHelper.formatHour(hourOfDay));
                        // 立即应用一次
                        ThemeHelper.applyAutoTheme(requireContext());
                        boolean nowNight = themePrefs.getBoolean("isNight", true);
                        isNightMode = nowNight;
                        switchNight.setChecked(nowNight);
                        applyTheme();
                    } else {
                        themePrefs.edit().putInt(ThemeHelper.KEY_NIGHT_START, hourOfDay).apply();
                        tvNightStartValue.setText(ThemeHelper.formatHour(hourOfDay));
                        ThemeHelper.applyAutoTheme(requireContext());
                        boolean nowNight = themePrefs.getBoolean("isNight", true);
                        isNightMode = nowNight;
                        switchNight.setChecked(nowNight);
                        applyTheme();
                    }
                    Toast.makeText(requireContext(),
                            (isDayStart ? "白天" : "夜间") + "开始时间: " + ThemeHelper.formatHour(hourOfDay),
                            Toast.LENGTH_SHORT).show();
                },
                current, 0, true
        );
        dialog.show();
    }

    /** 统一设置 SwitchCompat 开关外观 */
    private void applySwitchTint(SwitchCompat switchView) {
        int thumbColor = isNightMode ? 0xFFCCCCCC : 0xFFFFFFFF;
        int trackColor = isNightMode ? 0xFFFFFFFF : 0xFF000000;
        int[][] states = {{android.R.attr.state_checked}, {}};
        switchView.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{thumbColor, thumbColor}));
        switchView.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{trackColor, trackColor}));
    }

    private void setupStyleSelector(View view) {
        tvStyleTitle = view.findViewById(R.id.tv_style_title);
        tvStyleDesc = view.findViewById(R.id.tv_style_desc);

        currentStyleIndex = ThemeColors.getStyleIndex();
        applyItemTheme(tvStyleTitle, tvStyleDesc);
        updateStyleTabColors();

        for (int i = 0; i < 4; i++) {
            final int index = i;
            styleTabs[i].setOnClickListener(v -> {
                if (index == currentStyleIndex) return;
                ThemeColors.setStyle(requireContext(), index);
                currentStyleIndex = index;
                isNightMode = requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE)
                        .getBoolean("isNight", true);
                applyTheme();
                Toast.makeText(requireContext(), ThemeColors.getStyle().name, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void updateStyleTabColors() {
        if (tvStyleTitle == null) return;
        int activeColor = isNightMode ? ThemeColors.nightTabActive() : ThemeColors.dayTabActive();
        int inactiveColor = isNightMode ? ThemeColors.nightTabInactive() : ThemeColors.dayTabInactive();
        for (int i = 0; i < 4; i++) {
            boolean selected = (i == currentStyleIndex);
            styleTabs[i].setTextColor(selected ? activeColor : inactiveColor);
            styleTabs[i].setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void setupFloatWindowSwitch(View view) {
        switchFloat = view.findViewById(R.id.switch_float_window);
        tvFloatDesc = view.findViewById(R.id.tv_float_window_desc);
        tvFloatTitle = view.findViewById(R.id.tv_float_window_title);
        applyItemTheme(tvFloatTitle, tvFloatDesc);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_FLOAT_WINDOW_ENABLED, true);
        switchFloat.setChecked(enabled);
        applySwitchTint(switchFloat);

        switchFloat.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_FLOAT_WINDOW_ENABLED, isChecked).apply();
            if (!isChecked) {
                // 关闭开关时，如果独立悬浮窗正在显示，立即关闭
                try {
                    requireContext().stopService(new android.content.Intent(requireContext(),
                            com.jingxin.jingxinmusic.service.MiniFloatService.class));
                } catch (Exception ignored) {}
                Toast.makeText(requireContext(), "已关闭悬浮播放窗", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "已开启悬浮播放窗", Toast.LENGTH_SHORT).show();
            }
        });

        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
    }

    private void setupSpectrumSwitch(View view) {
        switchSpectrum = view.findViewById(R.id.switch_spectrum);
        tvSpectrumTitle = view.findViewById(R.id.tv_spectrum_title);
        tvSpectrumDesc = view.findViewById(R.id.tv_spectrum_desc);
        applyItemTheme(tvSpectrumTitle, tvSpectrumDesc);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_SPECTRUM_ENABLED, true);
        switchSpectrum.setChecked(enabled);
        applySwitchTint(switchSpectrum);

        switchSpectrum.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_SPECTRUM_ENABLED, isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "已开启频谱显示" : "已关闭频谱显示", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupLocalCoverSwitch(View view) {
        switchLocalCover = view.findViewById(R.id.switch_local_cover);
        tvLocalCoverTitle = view.findViewById(R.id.tv_local_cover_title);
        tvLocalCoverDesc = view.findViewById(R.id.tv_local_cover_desc);
        applyItemTheme(tvLocalCoverTitle, tvLocalCoverDesc);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_LOCAL_COVER_PRIORITY, true);
        switchLocalCover.setChecked(enabled);
        applySwitchTint(switchLocalCover);

        switchLocalCover.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_LOCAL_COVER_PRIORITY, isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "已开启本地封面优先" : "已关闭本地封面优先，将在线获取", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupLocalLyricSwitch(View view) {
        switchLocalLyric = view.findViewById(R.id.switch_local_lyric);
        tvLocalLyricTitle = view.findViewById(R.id.tv_local_lyric_title);
        tvLocalLyricDesc = view.findViewById(R.id.tv_local_lyric_desc);
        applyItemTheme(tvLocalLyricTitle, tvLocalLyricDesc);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_LOCAL_LYRIC_PRIORITY, true);
        switchLocalLyric.setChecked(enabled);
        applySwitchTint(switchLocalLyric);

        switchLocalLyric.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_LOCAL_LYRIC_PRIORITY, isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "已开启本地歌词优先" : "已关闭本地歌词优先", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupAutoResumeSwitch(View view) {
        switchAutoResume = view.findViewById(R.id.switch_auto_resume);
        tvAutoResumeTitle = view.findViewById(R.id.tv_auto_resume_title);
        tvAutoResumeDesc = view.findViewById(R.id.tv_auto_resume_desc);
        applyItemTheme(tvAutoResumeTitle, tvAutoResumeDesc);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_AUTO_RESUME, true);
        switchAutoResume.setChecked(enabled);
        applySwitchTint(switchAutoResume);

        switchAutoResume.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_AUTO_RESUME, isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "已开启启动直达播放" : "已关闭启动直达播放", Toast.LENGTH_SHORT).show();
        });
    }

    // ========== 歌词高亮颜色选择 ==========

    private void setupLyricColorSelector(View view) {
        tvLyricTitle = view.findViewById(R.id.tv_lyric_color_title);
        tvLyricDesc = view.findViewById(R.id.tv_lyric_color_desc);
        LyricColorBar dayBar = view.findViewById(R.id.day_color_bar);
        LyricColorBar nightBar = view.findViewById(R.id.night_color_bar);
        TextView tvDayLabel = view.findViewById(R.id.tv_day_label);
        TextView tvNightLabel = view.findViewById(R.id.tv_night_label);
        btnDayReset = view.findViewById(R.id.btn_day_color_reset);
        btnNightReset = view.findViewById(R.id.btn_night_color_reset);
        applyItemTheme(tvLyricTitle, tvLyricDesc);
        int ts = secondaryColor();
        if (btnDayReset != null) btnDayReset.setTextColor(ts);
        if (btnNightReset != null) btnNightReset.setTextColor(ts);

        SharedPreferences themePrefs = requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE);

        int dayColor = themePrefs.getInt(KEY_DAY_LYRIC_COLOR, DEFAULT_DAY_LYRIC_COLOR);
        int nightColor = themePrefs.getInt(KEY_NIGHT_LYRIC_COLOR, DEFAULT_NIGHT_LYRIC_COLOR);
        tvDayLabel.setTextColor(dayColor);
        tvNightLabel.setTextColor(nightColor);
        dayBar.setColor(dayColor);
        nightBar.setColor(nightColor);

        dayBar.setOnColorChangeListener(color -> {
            themePrefs.edit().putInt(KEY_DAY_LYRIC_COLOR, color).apply();
            tvDayLabel.setTextColor(color);
        });

        nightBar.setOnColorChangeListener(color -> {
            themePrefs.edit().putInt(KEY_NIGHT_LYRIC_COLOR, color).apply();
            tvNightLabel.setTextColor(color);
        });

        btnDayReset.setOnClickListener(v -> {
            themePrefs.edit().putInt(KEY_DAY_LYRIC_COLOR, DEFAULT_DAY_LYRIC_COLOR).apply();
            dayBar.setColor(DEFAULT_DAY_LYRIC_COLOR);
            tvDayLabel.setTextColor(DEFAULT_DAY_LYRIC_COLOR);
        });

        btnNightReset.setOnClickListener(v -> {
            themePrefs.edit().putInt(KEY_NIGHT_LYRIC_COLOR, DEFAULT_NIGHT_LYRIC_COLOR).apply();
            nightBar.setColor(DEFAULT_NIGHT_LYRIC_COLOR);
            tvNightLabel.setTextColor(DEFAULT_NIGHT_LYRIC_COLOR);
        });
    }
}
