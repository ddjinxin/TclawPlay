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
import com.jingxin.jingxinmusic.view.LyricColorBar;

import java.util.List;

public class SettingsFragment extends BaseFloatFragment {

    public static final String PREFS_NAME = "app_settings";
    public static final String KEY_FLOAT_WINDOW_ENABLED = "float_window_enabled";
    public static final String KEY_SPECTRUM_ENABLED = "spectrum_enabled";

    // 歌词高亮颜色 preferences 存在 "theme" SharedPreferences 中
    public static final String KEY_DAY_LYRIC_COLOR = "day_lyric_color";
    public static final String KEY_NIGHT_LYRIC_COLOR = "night_lyric_color";
    public static final int DEFAULT_DAY_LYRIC_COLOR = 0xFFE53935;   // 默认白天高亮红色
    public static final int DEFAULT_NIGHT_LYRIC_COLOR = 0xFFFFEB3B; // 默认夜间高亮黄色

    private boolean isNightMode;
    private ScrollView rootScroll;
    private TextView tvTitle;
    private View dividerTop, dividerMid, dividerMid2, dividerMid3, dividerMid4;
    private ImageView btnBack;
    private TextView[] styleTabs = new TextView[4];
    private int currentStyleIndex = 0;
    private SwitchCompat switchNight, switchFloat, switchSpectrum;
    // 各设置项标题
    private TextView tvNightTitle, tvStyleTitle, tvFloatTitle, tvSpectrumTitle, tvLyricTitle;
    // 各设置项描述
    private TextView tvNightDesc, tvStyleDesc, tvFloatDesc, tvSpectrumDesc, tvLyricDesc;
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
        setupStyleSelector(view);
        setupFloatWindowSwitch(view);
        setupSpectrumSwitch(view);
        setupLyricColorSelector(view);

        rootScroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                topInset = insets.getInsets(android.view.WindowInsets.Type.systemBars()).top;
            } else {
                topInset = insets.getSystemWindowInsetTop();
            }
            rootScroll.setPadding(rootScroll.getPaddingLeft(), topInset, rootScroll.getPaddingRight(), rootScroll.getPaddingBottom());
            return insets;
        });

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
        btnBack = view.findViewById(R.id.btn_back);
        styleTabs[0] = view.findViewById(R.id.style_tab_0);
        styleTabs[1] = view.findViewById(R.id.style_tab_1);
        styleTabs[2] = view.findViewById(R.id.style_tab_2);
        styleTabs[3] = view.findViewById(R.id.style_tab_3);
    }

    private void applyTheme() {
        rootScroll.setBackground(ThemeColors.bgGradient(isNightMode));
        int tc = titleColor();
        int ts = isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary();
        tvTitle.setTextColor(tc);
        // 刷新所有标题
        if (tvNightTitle != null) tvNightTitle.setTextColor(tc);
        if (tvStyleTitle != null) tvStyleTitle.setTextColor(tc);
        if (tvFloatTitle != null) tvFloatTitle.setTextColor(tc);
        if (tvSpectrumTitle != null) tvSpectrumTitle.setTextColor(tc);
        if (tvLyricTitle != null) tvLyricTitle.setTextColor(tc);
        if (tvScanTitle != null) tvScanTitle.setTextColor(tc);
        if (btnScan != null) btnScan.setTextColor(tc);
        if (tvStoragePermTitle != null) tvStoragePermTitle.setTextColor(tc);
        if (btnStoragePerm != null) btnStoragePerm.setTextColor(tc);
        // 刷新所有描述
        if (tvNightDesc != null) tvNightDesc.setTextColor(ts);
        if (tvStyleDesc != null) tvStyleDesc.setTextColor(ts);
        if (tvFloatDesc != null) tvFloatDesc.setTextColor(ts);
        if (tvSpectrumDesc != null) tvSpectrumDesc.setTextColor(ts);
        if (tvLyricDesc != null) tvLyricDesc.setTextColor(ts);
        if (tvScanDesc != null) tvScanDesc.setTextColor(ts);
        if (tvStoragePermDesc != null) tvStoragePermDesc.setTextColor(ts);
        if (btnDayReset != null) btnDayReset.setTextColor(ts);
        if (btnNightReset != null) btnNightReset.setTextColor(ts);
        // 风格 tab 颜色
        updateStyleTabColors();
        if (isNightMode) {
            dividerTop.setBackgroundColor(ThemeColors.nightDivider());
            dividerMid.setBackgroundColor(ThemeColors.nightDivider());
            dividerMid2.setBackgroundColor(ThemeColors.nightDivider());
            dividerMid3.setBackgroundColor(ThemeColors.nightDivider());
            dividerMid4.setBackgroundColor(ThemeColors.nightDivider());
            btnBack.clearColorFilter();
        } else {
            dividerTop.setBackgroundColor(ThemeColors.dayDivider());
            dividerMid.setBackgroundColor(ThemeColors.dayDivider());
            dividerMid2.setBackgroundColor(ThemeColors.dayDivider());
            dividerMid3.setBackgroundColor(ThemeColors.dayDivider());
            dividerMid4.setBackgroundColor(ThemeColors.dayDivider());
            btnBack.setColorFilter(ThemeColors.dayTextPrimary(), PorterDuff.Mode.SRC_IN);
        }
    }

    private void setupStoragePermission(View view) {
        btnStoragePerm = view.findViewById(R.id.btn_storage_perm);
        tvStoragePermTitle = view.findViewById(R.id.tv_storage_perm_title);
        tvStoragePermDesc = view.findViewById(R.id.tv_storage_perm_desc);
        tvStoragePermTitle.setTextColor(titleColor());
        tvStoragePermDesc.setTextColor(isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary());
        btnStoragePerm.setTextColor(titleColor());

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
        tvScanTitle.setTextColor(titleColor());
        tvScanDesc.setTextColor(isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary());
        btnScan.setTextColor(titleColor());

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

        tvNightTitle.setTextColor(titleColor());
        tvNightDesc.setTextColor(isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary());

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
            Toast.makeText(requireContext(), isChecked ? "夜间模式" : "白天模式", Toast.LENGTH_SHORT).show();
        });
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
        tvStyleTitle.setTextColor(titleColor());
        tvStyleDesc.setTextColor(isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary());
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

        tvFloatTitle.setTextColor(titleColor());
        tvFloatDesc.setTextColor(isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary());

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

        tvSpectrumTitle.setTextColor(titleColor());
        tvSpectrumDesc.setTextColor(isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary());

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_SPECTRUM_ENABLED, true);
        switchSpectrum.setChecked(enabled);
        applySwitchTint(switchSpectrum);

        switchSpectrum.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_SPECTRUM_ENABLED, isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "已开启频谱显示" : "已关闭频谱显示", Toast.LENGTH_SHORT).show();
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

        tvLyricTitle.setTextColor(titleColor());
        tvLyricDesc.setTextColor(isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary());
        btnDayReset.setTextColor(isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary());
        btnNightReset.setTextColor(isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary());

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
