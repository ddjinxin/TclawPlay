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
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.util.MusicScanner;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.util.ThemeHelper;
import com.jingxin.jingxinmusic.view.DynamicBgView;
import com.jingxin.jingxinmusic.view.LyricColorBar;

import java.util.List;

public class SettingsFragment extends BaseFloatFragment {

    public static final String PREFS_NAME = "app_settings";
    public static final String KEY_FLOAT_WINDOW_ENABLED = "float_window_enabled";
    public static final String KEY_SPECTRUM_ENABLED = "spectrum_enabled";
    public static final String KEY_LOCAL_COVER_PRIORITY = "local_cover_priority";
    public static final String KEY_LOCAL_LYRIC_PRIORITY = "local_lyric_priority";
    public static final String KEY_AUTO_RESUME = "auto_resume";
    public static final String KEY_BG_SKIN = "bg_skin";
    public static final String KEY_AUDIO_EFFECT_PRESET = "audio_effect_preset";
    public static final String KEY_VOLUME_NORMALIZE = "volume_normalize";
    public static final String KEY_BOOT_AUTO_START = "boot_auto_start";
    public static final String KEY_AUTO_HIDE_CONTROLS_SECONDS = "auto_hide_controls_seconds";
    /** 自动隐藏播放栏：不隐藏 */
    public static final int AUTO_HIDE_CONTROLS_OFF = -1;
    /** 自动隐藏播放栏：默认 5 秒 */
    public static final int AUTO_HIDE_CONTROLS_DEFAULT_SECONDS = 5;
    /** 自动隐藏播放栏：最小秒数 */
    public static final int AUTO_HIDE_CONTROLS_MIN = 5;
    /** 自动隐藏播放栏：最大秒数 */
    public static final int AUTO_HIDE_CONTROLS_MAX = 30;
    public static final int BG_SKIN_DEFAULT = 0;
    public static final int BG_SKIN_AURORA = 1;

    // 歌词高亮颜色 preferences 存在 "theme" SharedPreferences 中
    public static final String KEY_DAY_LYRIC_COLOR = "day_lyric_color";
    public static final String KEY_NIGHT_LYRIC_COLOR = "night_lyric_color";
    public static final int DEFAULT_DAY_LYRIC_COLOR = 0xFFE53935;   // 默认白天高亮红色
    public static final int DEFAULT_NIGHT_LYRIC_COLOR = 0xFFFFEB3B; // 默认夜间高亮黄色

    private boolean isNightMode;
    private boolean dynamicBgEnabled = false;
    private ScrollView rootScroll;
    private DynamicBgView dynamicBgView;
    private View rootLayout;
    private TextView tvTitle;
    private LinearLayout topBar;
    private View dividerTop, dividerMid, dividerMid2, dividerMid3, dividerMid4, dividerMid5, dividerMid6, dividerMid7, dividerMid8;
    private ImageView btnBack;
    private ImageView btnHelp;
    private TextView[] styleTabs = new TextView[4];
    private int currentStyleIndex = 0;
    private SwitchCompat switchNight, switchFloat, switchSpectrum, switchLocalCover, switchLocalLyric, switchAutoResume, switchAutoTheme, switchVolumeNormalize, switchBootAutoStart;
    private TextView tvNightTitle, tvStyleTitle, tvBgSkinTitle, tvFloatTitle, tvSpectrumTitle, tvLyricTitle, tvLocalCoverTitle, tvLocalLyricTitle, tvAutoResumeTitle, tvAutoThemeTitle, tvVolumeNormalizeTitle, tvBootAutoStartTitle;
    private TextView tvNightDesc, tvStyleDesc, tvBgSkinDesc, tvFloatDesc, tvSpectrumDesc, tvLyricDesc, tvLocalCoverDesc, tvLocalLyricDesc, tvAutoResumeDesc, tvAutoThemeDesc, tvVolumeNormalizeDesc, tvBootAutoStartDesc;
    private android.widget.Spinner spinnerAutoHideControls;
    private TextView tvAutoHideControlsTitle, tvAutoHideControlsDesc;
    private TextView[] bgSkinTabs = new TextView[2];
    private int currentBgSkinIndex = 0;

    // 歌词动效壁纸
    private TextView tvEffectWallpaperTitle, tvEffectWallpaperDesc, rowPickWallpaper;
    private TextView[] wallpaperModeTabs = new TextView[2];
    private boolean currentWallpaperMode = false;
    private com.jingxin.jingxinmusic.view.FilePickerView activePicker; // 壁纸文件选择叠层（乐酷同款 View 方案）
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

    private View mRootView;
    private float sf = 1f; // 比例缩放系数：1=保持原样

    /** 设置项标题颜色 */
    private int titleColor() {
        if (dynamicBgEnabled) return isNightMode ? 0xFFFFFFFF : 0xFF222222;
        return isNightMode ? 0xFFFFFFFF : 0xFF000000;
    }

    /** 设置项描述/次要文字颜色 */
    private int secondaryColor() {
        if (dynamicBgEnabled) return isNightMode ? 0xFFAAAAAA : 0xFF666666;
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

    /** 读取背景皮肤模式（未设置过默认1=双色光晕，1075及以前用户升级后默认体验新皮肤） */
    public static int getBgSkin(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_BG_SKIN, BG_SKIN_AURORA);
    }

    /** 读取启动直达播放开关状态（默认开启） */
    public static boolean isAutoResumeEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_RESUME, true);
    }

    /** 读取开机自启开关状态（默认关闭） */
    public static boolean isBootAutoStartEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BOOT_AUTO_START, false);
    }

    /** 读取自动隐藏播放栏延时（秒），-1=不隐藏，默认5秒 */
    public static int getAutoHideControlsSeconds(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_AUTO_HIDE_CONTROLS_SECONDS, AUTO_HIDE_CONTROLS_DEFAULT_SECONDS);
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
        ThemeColors.init(requireContext());
        dynamicBgEnabled = (ThemeColors.getStyleIndex() == 0);

        initViews(view);
        applyTheme();
        setupScanButton(view);
        setupStoragePermission(view);
        setupNightModeSwitch(view);
        setupAutoTheme(view);
        setupStyleSelector(view);
        setupBgSkinSelector(view);
        setupEffectWallpaperSelector(view);
        setupFloatWindowSwitch(view);
        setupSpectrumSwitch(view);
        setupLocalCoverSwitch(view);
        setupLocalLyricSwitch(view);
        setupAutoResumeSwitch(view);
        setupVolumeNormalizeSwitch(view);
        setupBootAutoStartSwitch(view);
        setupAutoHideControls(view);
        setupLyricColorSelector(view);

        applyTopInset(rootScroll);

        // 比例缩放：布局完成后应用，尺寸变化（悬浮窗/分屏）时重应用
        view.post(() -> { if (isAdded()) applyPortraitScaling(); });
        view.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int newW = right - left, newH = bottom - top, oldW = oldRight - oldLeft, oldH = oldBottom - oldTop;
            if (newW > 0 && (oldW <= 0 || newW != oldW || newH != oldH)) {
                v.post(() -> { if (isAdded()) applyPortraitScaling(); });
            }
        });

        registerThemeReceiver(); // 高德日夜切换时同步本页主题

        return view;
    }

    @Override
    protected void onThemeNightChanged(boolean isNight) {
        if (isNightMode == isNight) return;
        isNightMode = isNight;
        // 同步日夜开关状态（临时摘掉监听器，防止 setChecked 回写 amapTriggered=false 拦截高德）
        if (switchNight != null) {
            switchNight.setOnCheckedChangeListener(null);
            switchNight.setChecked(isNight);
            switchNight.setOnCheckedChangeListener((bw, checked) -> {
                requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE)
                        .edit().putBoolean("isNight", checked).putBoolean("amapTriggered", false).apply();
                isNightMode = checked;
                applyTheme();
                Toast.makeText(requireContext(), checked ? "夜间模式" : "白天模式", Toast.LENGTH_SHORT).show();
            });
        }
        applyTheme();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unregisterThemeReceiver();
        // 壁纸选择叠层随视图销毁释放（停缩略图线程池）
        if (activePicker != null) {
            activePicker.close();
            activePicker = null;
        }
    }

    private void initViews(View view) {
        mRootView = view;
        rootScroll = view.findViewById(R.id.root_scroll);
        rootLayout = view.findViewById(R.id.root_layout);
        dynamicBgView = view.findViewById(R.id.dynamic_bg_view);
        tvTitle = view.findViewById(R.id.tv_title);
        topBar = view.findViewById(R.id.top_bar);
        dividerTop = view.findViewById(R.id.divider_top);
        dividerMid = view.findViewById(R.id.divider_mid);
        dividerMid2 = view.findViewById(R.id.divider_mid2);
        dividerMid3 = view.findViewById(R.id.divider_mid3);
        dividerMid4 = view.findViewById(R.id.divider_mid4);
        dividerMid5 = view.findViewById(R.id.divider_mid5);
        dividerMid6 = view.findViewById(R.id.divider_mid6);
        dividerMid7 = view.findViewById(R.id.divider_mid7);
        dividerMid8 = view.findViewById(R.id.divider_mid8);
        btnBack = view.findViewById(R.id.btn_back);
        btnHelp = view.findViewById(R.id.btn_help);
        btnHelp.setOnClickListener(v -> {
            if (getActivity() instanceof com.jingxin.jingxinmusic.HostActivity) {
                ((com.jingxin.jingxinmusic.HostActivity) getActivity())
                        .navigateTo(new HelpFragment(), true);
            }
        });
        styleTabs[0] = view.findViewById(R.id.style_tab_0);
        styleTabs[1] = view.findViewById(R.id.style_tab_1);
        styleTabs[2] = view.findViewById(R.id.style_tab_2);
        styleTabs[3] = view.findViewById(R.id.style_tab_3);
        bgSkinTabs[0] = view.findViewById(R.id.bg_skin_tab_0);
        bgSkinTabs[1] = view.findViewById(R.id.bg_skin_tab_1);
        // 歌词动效壁纸控件
        wallpaperModeTabs[0] = view.findViewById(R.id.effect_wallpaper_tab_default);
        wallpaperModeTabs[1] = view.findViewById(R.id.effect_wallpaper_tab_wallpaper);
        rowPickWallpaper = view.findViewById(R.id.row_pick_wallpaper);
        // 时间选择行的 TextView 提前到 initViews 初始化，确保 applyTheme 能覆盖颜色
        tvDayStartLabel = view.findViewById(R.id.tv_day_start_label);
        tvDayStartValue = view.findViewById(R.id.tv_day_start_value);
        tvTimeSeparator = view.findViewById(R.id.tv_time_separator);
        tvNightStartLabel = view.findViewById(R.id.tv_night_start_label);
        tvNightStartValue = view.findViewById(R.id.tv_night_start_value);
        timeSelectorRow = view.findViewById(R.id.time_selector_row);
    }

    private void applyTheme() {
        if (dynamicBgEnabled) {
            // 动态模式：DynamicBgView 底色+左下蒙版（无右上渐变）
            dynamicBgView.setVisibility(View.VISIBLE);
            dynamicBgView.setNightMode(isNightMode);
            dynamicBgView.setGradientEnabled(false);
            dynamicBgView.setTintColor(ThemeColors.getDominantColor());
            rootScroll.setBackgroundColor(0x00000000);
        } else {
            dynamicBgView.setVisibility(View.GONE);
            rootScroll.setBackground(ThemeColors.bgGradient(isNightMode));
        }
        int tc = titleColor();
        int ts = secondaryColor();
        int divColor = dynamicBgEnabled
                ? (isNightMode ? 0xFF2A2A2E : 0xFFE0E0E0)
                : (isNightMode ? ThemeColors.nightDivider() : ThemeColors.dayDivider());
        tvTitle.setTextColor(tc);
        // 刷新所有标题（含壁纸选择行：可操作项，与扫描按钮同用标题色）
        TextView[] titles = {tvNightTitle, tvAutoThemeTitle, tvStyleTitle, tvBgSkinTitle, tvEffectWallpaperTitle, rowPickWallpaper, tvFloatTitle, tvSpectrumTitle,
                tvLyricTitle, tvScanTitle, tvStoragePermTitle, btnStoragePerm, tvLocalCoverTitle, tvLocalLyricTitle, tvAutoResumeTitle, tvVolumeNormalizeTitle, tvBootAutoStartTitle, tvAutoHideControlsTitle};
        for (TextView t : titles) if (t != null) t.setTextColor(tc);
        // 刷新所有描述
        TextView[] descs = {tvNightDesc, tvAutoThemeDesc, tvStyleDesc, tvBgSkinDesc, tvEffectWallpaperDesc, tvFloatDesc, tvSpectrumDesc,
                tvLyricDesc, tvScanDesc, tvStoragePermDesc, btnDayReset, btnNightReset, tvLocalCoverDesc, tvLocalLyricDesc, tvAutoResumeDesc, tvVolumeNormalizeDesc, tvBootAutoStartDesc, tvAutoHideControlsDesc};
        for (TextView d : descs) if (d != null) d.setTextColor(ts);
        // 时间选择行文字
        TextView[] timeTexts = {tvDayStartLabel, tvDayStartValue, tvTimeSeparator, tvNightStartLabel, tvNightStartValue};
        for (TextView t : timeTexts) if (t != null) t.setTextColor(ts);
        // 风格 tab 颜色
        updateStyleTabColors();
        // 背景皮肤 tab 颜色
        updateBgSkinTabColors();
        // 歌词动效壁纸 tab 颜色与状态文案
        updateWallpaperTabColors();
        updateWallpaperStatusText();
        View[] dividers = {dividerTop, dividerMid, dividerMid2, dividerMid3, dividerMid4, dividerMid5, dividerMid6, dividerMid7, dividerMid8};
        for (View d : dividers) d.setBackgroundColor(divColor);
        if (dynamicBgEnabled) {
            // 动态模式：图标用黑白
            if (isNightMode) {
                btnBack.clearColorFilter();
                btnHelp.clearColorFilter();
            } else {
                btnBack.setColorFilter(0xFF222222, PorterDuff.Mode.SRC_IN);
                btnHelp.setColorFilter(0xFF222222, PorterDuff.Mode.SRC_IN);
            }
        } else {
            if (isNightMode) {
                btnBack.clearColorFilter();
                btnHelp.clearColorFilter();
            } else {
                btnBack.setColorFilter(ThemeColors.dayTextPrimary(), PorterDuff.Mode.SRC_IN);
                btnHelp.setColorFilter(ThemeColors.dayTextPrimary(), PorterDuff.Mode.SRC_IN);
            }
        }
        // 日夜变化时统一刷新全部开关颜色（初始化阶段未创建的开关自动跳过）
        applyAllSwitchTints();
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
        // 壁纸选择已改为 View 叠层 + 回调即时刷新，无需 onResume 逻辑
        if (activePicker != null && activePicker.getParent() == null) {
            activePicker = null; // 已被关闭/移除，清理引用
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

    /** 全部设置开关集合（初始化前为 null，调用方需判空） */
    private SwitchCompat[] allSwitches() {
        return new SwitchCompat[]{switchNight, switchAutoTheme, switchFloat, switchSpectrum,
                switchLocalCover, switchLocalLyric, switchAutoResume, switchVolumeNormalize, switchBootAutoStart};
    }

    /** 按当前日夜模式刷新全部开关颜色 */
    private void applyAllSwitchTints() {
        for (SwitchCompat sw : allSwitches()) applySwitchTint(sw);
    }

    /** 统一设置 SwitchCompat 开关外观 */
    private void applySwitchTint(SwitchCompat switchView) {
        if (switchView == null) return;
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

    private void setupBgSkinSelector(View view) {
        tvBgSkinTitle = view.findViewById(R.id.tv_bg_skin_title);
        tvBgSkinDesc = view.findViewById(R.id.tv_bg_skin_desc);
        applyItemTheme(tvBgSkinTitle, tvBgSkinDesc);

        currentBgSkinIndex = getBgSkin(requireContext());
        updateBgSkinTabColors();

        for (int i = 0; i < 2; i++) {
            final int index = i;
            bgSkinTabs[i].setOnClickListener(v -> {
                if (index == currentBgSkinIndex) return;
                SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putInt(KEY_BG_SKIN, index).apply();
                currentBgSkinIndex = index;
                updateBgSkinTabColors();
                String[] names = {"默认模式", "双色光晕"};
                Toast.makeText(requireContext(), names[index], Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void updateBgSkinTabColors() {
        if (tvBgSkinTitle == null) return;
        int activeColor = isNightMode ? ThemeColors.nightTabActive() : ThemeColors.dayTabActive();
        int inactiveColor = isNightMode ? ThemeColors.nightTabInactive() : ThemeColors.dayTabInactive();

        for (int i = 0; i < 2; i++) {
            boolean selected = (i == currentBgSkinIndex);
            bgSkinTabs[i].setTextColor(selected ? activeColor : inactiveColor);
            bgSkinTabs[i].setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    // ==================== 歌词动效壁纸 ====================

    private void setupEffectWallpaperSelector(View view) {
        tvEffectWallpaperTitle = view.findViewById(R.id.tv_effect_wallpaper_title);
        tvEffectWallpaperDesc = view.findViewById(R.id.tv_effect_wallpaper_desc);
        rowPickWallpaper = view.findViewById(R.id.row_pick_wallpaper);
        applyItemTheme(tvEffectWallpaperTitle, tvEffectWallpaperDesc);
        rowPickWallpaper.setTextColor(titleColor()); // 日夜主题初始化（后续 applyTheme 统一刷）

        // tab 展示与实际生效模式一致：壁纸模式但未设壁纸时效果页回退默认背景，tab 按默认显示
        currentWallpaperMode = com.jingxin.jingxinmusic.util.EffectWallpaperStore.isWallpaperMode(requireContext())
                && com.jingxin.jingxinmusic.util.EffectWallpaperStore.hasWallpaper(requireContext());

        for (int i = 0; i < 2; i++) {
            final boolean wallpaper = (i == 1);
            wallpaperModeTabs[i].setOnClickListener(v -> selectWallpaperMode(wallpaper));
        }
        rowPickWallpaper.setOnClickListener(v -> {
            // View 叠层方案（同乐酷驾驶 FilePickerView）：乐酷悬浮模式下应用内容在
            // APPLICATION_OVERLAY 窗口显示，Activity 窗口隐藏——Activity/Dialog 均不可见，
            // 只有叠进当前可见视图树的普通 View 能显示
            if (activePicker != null) return; // 已打开
            activePicker = new com.jingxin.jingxinmusic.view.FilePickerView(requireContext(), fileName -> {
                // 选中即已复制+启用壁纸模式，同步 tab 与文案（回调即时刷新）
                currentWallpaperMode = com.jingxin.jingxinmusic.util.EffectWallpaperStore.isWallpaperMode(requireContext())
                        && com.jingxin.jingxinmusic.util.EffectWallpaperStore.hasWallpaper(requireContext());
                updateWallpaperTabColors();
                updateWallpaperStatusText();
            });
            activePicker.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            // 叠在设置页根布局最上层（同乐酷 gv.addOverlay）
            ((android.view.ViewGroup) mRootView).addView(activePicker);
            activePicker.bringToFront();
        });

        updateWallpaperTabColors();
        updateWallpaperStatusText();
    }

    private void selectWallpaperMode(boolean wallpaper) {
        if (wallpaper == currentWallpaperMode) return;
        if (wallpaper && !com.jingxin.jingxinmusic.util.EffectWallpaperStore.hasWallpaper(requireContext())) {
            Toast.makeText(requireContext(), "请先选择壁纸文件", Toast.LENGTH_SHORT).show();
            return;
        }
        com.jingxin.jingxinmusic.util.EffectWallpaperStore.setMode(requireContext(), wallpaper);
        currentWallpaperMode = wallpaper;
        updateWallpaperTabColors();
        updateWallpaperStatusText();
        Toast.makeText(requireContext(), wallpaper ? "已切换为壁纸模式" : "已切换为默认背景", Toast.LENGTH_SHORT).show();
    }

    private void updateWallpaperTabColors() {
        if (tvEffectWallpaperTitle == null) return;
        int activeColor = isNightMode ? ThemeColors.nightTabActive() : ThemeColors.dayTabActive();
        int inactiveColor = isNightMode ? ThemeColors.nightTabInactive() : ThemeColors.dayTabInactive();
        for (int i = 0; i < 2; i++) {
            if (wallpaperModeTabs[i] == null) continue;
            boolean selected = ((i == 1) == currentWallpaperMode);
            wallpaperModeTabs[i].setTextColor(selected ? activeColor : inactiveColor);
            wallpaperModeTabs[i].setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    /** 壁纸状态：FilePicker 返回后 onResume 重刷（选中即复制完成） */
    private void updateWallpaperStatusText() {
        if (tvEffectWallpaperDesc == null) return;
        java.io.File f = com.jingxin.jingxinmusic.util.EffectWallpaperStore.findWallpaperFile(requireContext());
        boolean mode = com.jingxin.jingxinmusic.util.EffectWallpaperStore.isWallpaperMode(requireContext());
        if (f == null) {
            tvEffectWallpaperDesc.setText("尚未设置壁纸，当前使用默认背景");
            if (rowPickWallpaper != null) rowPickWallpaper.setText("选择壁纸文件");
        } else {
            // 显示源文件名（用户所选原始名）；老数据无记录时兜底显示存储名
            String name = com.jingxin.jingxinmusic.util.EffectWallpaperStore.getSourceName(requireContext());
            if (name == null) name = f.getName();
            tvEffectWallpaperDesc.setText(mode ? "壁纸模式 · " + name : "当前默认背景 · 已设壁纸 " + name);
            if (rowPickWallpaper != null) rowPickWallpaper.setText("重新选择壁纸（当前 " + name + "）");
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

    // ========== 音效预设选择 ==========

    /** 读取音效预设索引（默认关闭） */
    public static int getAudioEffectPreset(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_AUDIO_EFFECT_PRESET, com.jingxin.jingxinmusic.util.AudioEffectManager.PRESET_OFF);
    }

    /** 读取音量均衡开关状态（默认关闭） */
    public static boolean isVolumeNormalizeEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VOLUME_NORMALIZE, false);
    }

    // ========== 音量均衡开关 ==========

    private void setupVolumeNormalizeSwitch(View view) {
        switchVolumeNormalize = view.findViewById(R.id.switch_volume_normalize);
        tvVolumeNormalizeTitle = view.findViewById(R.id.tv_volume_normalize_title);
        tvVolumeNormalizeDesc = view.findViewById(R.id.tv_volume_normalize_desc);
        applyItemTheme(tvVolumeNormalizeTitle, tvVolumeNormalizeDesc);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_VOLUME_NORMALIZE, false);
        switchVolumeNormalize.setChecked(enabled);
        applySwitchTint(switchVolumeNormalize);

        switchVolumeNormalize.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_VOLUME_NORMALIZE, isChecked).apply();
            // startService 通知 Service 开/关音量均衡（方案 C 接入后生效）
            android.content.Intent intent = new android.content.Intent(
                    com.jingxin.jingxinmusic.service.MusicPlayerService.ACTION_VOLUME_NORMALIZE_CHANGED);
            intent.setClass(requireContext(), com.jingxin.jingxinmusic.service.MusicPlayerService.class);
            try {
                requireContext().startService(intent);
            } catch (Exception ignored) {}
            Toast.makeText(requireContext(), isChecked ? "已开启音量均衡" : "已关闭音量均衡", Toast.LENGTH_SHORT).show();
        });
    }

    // ========== 开机自启开关 ==========

    private void setupBootAutoStartSwitch(View view) {
        switchBootAutoStart = view.findViewById(R.id.switch_boot_auto_start);
        tvBootAutoStartTitle = view.findViewById(R.id.tv_boot_auto_start_title);
        tvBootAutoStartDesc = view.findViewById(R.id.tv_boot_auto_start_desc);
        applyItemTheme(tvBootAutoStartTitle, tvBootAutoStartDesc);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_BOOT_AUTO_START, false);
        switchBootAutoStart.setChecked(enabled);
        applySwitchTint(switchBootAutoStart);

        switchBootAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_BOOT_AUTO_START, isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "已开启开机自启" : "已关闭开机自启", Toast.LENGTH_SHORT).show();
        });
    }

    // ========== 自动隐藏播放栏延时选择 ==========

    private void setupAutoHideControls(View view) {
        spinnerAutoHideControls = view.findViewById(R.id.spinner_auto_hide_controls);
        tvAutoHideControlsTitle = view.findViewById(R.id.tv_auto_hide_controls_title);
        tvAutoHideControlsDesc = view.findViewById(R.id.tv_auto_hide_controls_desc);
        applyItemTheme(tvAutoHideControlsTitle, tvAutoHideControlsDesc);

        // 选项："关" + 5~30 秒
        final int[] values = new int[AUTO_HIDE_CONTROLS_MAX - AUTO_HIDE_CONTROLS_MIN + 2];
        values[0] = AUTO_HIDE_CONTROLS_OFF;
        int vi = 1;
        for (int i = AUTO_HIDE_CONTROLS_MIN; i <= AUTO_HIDE_CONTROLS_MAX; i++) values[vi++] = i;
        final String[] labels = new String[values.length];
        labels[0] = "关";
        for (int i = 1; i < labels.length; i++) labels[i] = values[i] + "秒";

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(),
                R.layout.spinner_item_theme, labels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v).setTextColor(isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary());
                return v;
            }
            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                ((TextView) v).setTextColor(isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary());
                return v;
            }
        };
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_theme);
        spinnerAutoHideControls.setAdapter(adapter);

        // 回显当前值
        int current = getAutoHideControlsSeconds(requireContext());
        int selectedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { selectedIndex = i; break; }
        }
        spinnerAutoHideControls.setSelection(selectedIndex);

        // 下拉弹窗背景随主题
        spinnerAutoHideControls.setPopupBackgroundDrawable(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{ isNight() ? 0xFF2A2A2E : 0xFFFFFFFF, isNight() ? 0xFF2A2A2E : 0xFFFFFFFF }));
        spinnerAutoHideControls.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                isNight() ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary()));

        spinnerAutoHideControls.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View v, int pos, long id) {
                int sec = values[pos];
                requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_AUTO_HIDE_CONTROLS_SECONDS, sec).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private boolean isNight() {
        return requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE).getBoolean("isNight", true);
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

    // ==================== 比例缩放（高分辨率车机适配） ====================

    /**
     * 计算缩放系数：竖屏以 16sp 条目标题 ≈ 1.88% 屏高、横屏以 16sp ≈ 3% 屏高为锚点；
     * 小屏手机 sf=1 保持原样。
     */
    private void computeScaleFactor() {
        sf = 1f;
        int width = 0, height = 0;
        if (mRootView != null && mRootView.getWidth() > 0 && mRootView.getHeight() > 0) {
            width = mRootView.getWidth();
            height = mRootView.getHeight();
        }
        if (width <= 0 || height <= 0) {
            width = com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().getFloatWidth();
            height = com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().getFloatHeight();
        }
        if (width <= 0 || height <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
            height = getResources().getDisplayMetrics().heightPixels;
        }
        if (width <= 0 || height <= 0) return;
        float basePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics());
        // 横竖屏锚点不同：保证同一车机横竖屏切换后字号视觉连续，避免横屏用竖屏锚点导致字过大
        float ratio = (width > height * 1.1f) ? 0.03f : 0.0188f;
        sf = Math.max(1f, height * ratio / basePx);
    }

    /** sp 原始值 × 缩放系数（px），sf=1 时与原 XML 尺寸一致 */
    private float spScaled(float spValue) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, spValue, getResources().getDisplayMetrics()) * sf;
    }

    /** dp 原始值 × 缩放系数（px） */
    private int dpScaled(float dpValue) {
        return Math.round(dpValue * getResources().getDisplayMetrics().density * sf);
    }

    /** 应用比例缩放：小屏手机保持原样，高分辨率车机按屏高等比放大（横竖屏自适应） */
    private void applyPortraitScaling() {
        if (mRootView == null || topBar == null) return;
        computeScaleFactor();

        // 顶部栏 48dp、返回键 36dp（padding 6dp）、标题 20sp
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) topBar.getLayoutParams();
        lp.height = dpScaled(48);
        topBar.setLayoutParams(lp);
        LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) btnBack.getLayoutParams();
        blp.width = blp.height = dpScaled(36);
        btnBack.setLayoutParams(blp);
        LinearLayout.LayoutParams hlp = (LinearLayout.LayoutParams) btnHelp.getLayoutParams();
        hlp.width = hlp.height = dpScaled(36);
        btnHelp.setLayoutParams(hlp);
        int pad6 = dpScaled(6);
        btnBack.setPadding(pad6, pad6, pad6, pad6);
        btnHelp.setPadding(pad6, pad6, pad6, pad6);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, spScaled(20));

        // 各条目行 paddingVertical 12dp
        int itemPad = dpScaled(12);
        int[] itemIds = {R.id.item_scan_music, R.id.item_storage_permission, R.id.item_night_mode,
                R.id.item_auto_theme, R.id.item_style, R.id.item_bg_skin, R.id.item_float_window, R.id.item_spectrum,
                R.id.item_auto_resume, R.id.item_local_cover, R.id.item_local_lyric, R.id.item_lyric_color,
                R.id.item_volume_normalize, R.id.item_boot_auto_start, R.id.item_auto_hide_controls};
        for (int id : itemIds) {
            View item = mRootView.findViewById(id);
            if (item != null) item.setPadding(item.getPaddingLeft(), itemPad, item.getPaddingRight(), itemPad);
        }

        // 条目标题 16sp、描述 12sp（含描述上边距 2dp）
        TextView[] titles = {tvScanTitle, tvStoragePermTitle, tvNightTitle, tvAutoThemeTitle, tvStyleTitle,
                tvBgSkinTitle, tvFloatTitle, tvSpectrumTitle, tvAutoResumeTitle, tvLocalCoverTitle, tvLocalLyricTitle, tvLyricTitle,
                tvVolumeNormalizeTitle, tvBootAutoStartTitle, tvAutoHideControlsTitle};
        TextView[] descs = {tvScanDesc, tvStoragePermDesc, tvNightDesc, tvAutoThemeDesc, tvStyleDesc,
                tvBgSkinDesc, tvFloatDesc, tvSpectrumDesc, tvAutoResumeDesc, tvLocalCoverDesc, tvLocalLyricDesc, tvLyricDesc,
                tvVolumeNormalizeDesc, tvBootAutoStartDesc, tvAutoHideControlsDesc};
        float titleSize = spScaled(16);
        float descSize = spScaled(12);
        for (TextView t : titles) if (t != null) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize);
        for (TextView d : descs) {
            if (d == null) continue;
            d.setTextSize(TypedValue.COMPLEX_UNIT_PX, descSize);
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) d.getLayoutParams();
            p.topMargin = dpScaled(2);
            d.setLayoutParams(p);
        }
        // 风格/背景皮肤/歌词颜色描述下边距 8dp
        for (TextView d : new TextView[]{tvStyleDesc, tvBgSkinDesc, tvLyricDesc}) {
            if (d == null) continue;
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) d.getLayoutParams();
            p.bottomMargin = dpScaled(8);
            d.setLayoutParams(p);
        }

        // 操作文字 14sp：扫描/去授权、时间选择行、白天/夜间标签
        float actionSize = spScaled(14);
        TextView[] actions = {btnScan, btnStoragePerm, tvDayStartLabel, tvDayStartValue, tvTimeSeparator,
                tvNightStartLabel, tvNightStartValue};
        for (TextView a : actions) if (a != null) a.setTextSize(TypedValue.COMPLEX_UNIT_PX, actionSize);
        TextView tvDayLabel = mRootView.findViewById(R.id.tv_day_label);
        TextView tvNightLabel = mRootView.findViewById(R.id.tv_night_label);
        if (tvDayLabel != null) tvDayLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, actionSize);
        if (tvNightLabel != null) tvNightLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, actionSize);

        // 时间选择行上边距 10dp、时间值内边距 4dp
        LinearLayout.LayoutParams trp = (LinearLayout.LayoutParams) timeSelectorRow.getLayoutParams();
        trp.topMargin = dpScaled(10);
        timeSelectorRow.setLayoutParams(trp);
        int pad4 = dpScaled(4);
        tvDayStartValue.setPadding(pad4, pad4, pad4, pad4);
        tvNightStartValue.setPadding(pad4, pad4, pad4, pad4);

        // 扫描进度圈 20dp
        LinearLayout.LayoutParams pbp = (LinearLayout.LayoutParams) scanProgress.getLayoutParams();
        pbp.width = pbp.height = dpScaled(20);
        scanProgress.setLayoutParams(pbp);

        // 风格选择条 36dp、tab 12sp；歌词颜色条 36dp、“默认”按钮 12sp
        LinearLayout styleSelector = mRootView.findViewById(R.id.style_selector);
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) styleSelector.getLayoutParams();
        slp.height = dpScaled(36);
        styleSelector.setLayoutParams(slp);
        float tabSize = spScaled(12);
        for (TextView tab : styleTabs) if (tab != null) tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, tabSize);
        // 背景皮肤选择条
        LinearLayout bgSkinSelector = mRootView.findViewById(R.id.bg_skin_selector);
        if (bgSkinSelector != null) {
            LinearLayout.LayoutParams bslp = (LinearLayout.LayoutParams) bgSkinSelector.getLayoutParams();
            bslp.height = dpScaled(36);
            bgSkinSelector.setLayoutParams(bslp);
        }
        for (TextView tab : bgSkinTabs) if (tab != null) tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, tabSize);
        // 歌词动效壁纸选择条
        LinearLayout wpSelector = mRootView.findViewById(R.id.effect_wallpaper_mode_selector);
        if (wpSelector != null) {
            LinearLayout.LayoutParams wslp = (LinearLayout.LayoutParams) wpSelector.getLayoutParams();
            wslp.height = dpScaled(36);
            wpSelector.setLayoutParams(wslp);
        }
        for (TextView tab : wallpaperModeTabs) if (tab != null) tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, tabSize);
        if (rowPickWallpaper != null) rowPickWallpaper.setTextSize(TypedValue.COMPLEX_UNIT_PX, tabSize);
        for (int id : new int[]{R.id.day_color_bar, R.id.night_color_bar}) {
            View bar = mRootView.findViewById(id);
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) bar.getLayoutParams();
            p.height = dpScaled(36);
            bar.setLayoutParams(p);
        }
        if (btnDayReset != null) btnDayReset.setTextSize(TypedValue.COMPLEX_UNIT_PX, descSize);
        if (btnNightReset != null) btnNightReset.setTextSize(TypedValue.COMPLEX_UNIT_PX, descSize);

        // 开关整体按 sf 放大（SwitchCompat 尺寸由内部 thumb/track 决定，scale 不改布局占位）
        // 缩放轴心设为右边缘：右缘保持原位置不被父容器裁剪，向左扩展；并加等量左边距避免遮挡文字
        for (SwitchCompat sw : allSwitches()) {
            if (sw == null) continue;
            sw.setPivotX(sw.getWidth());
            sw.setPivotY(sw.getHeight() / 2f);
            sw.setScaleX(sf);
            sw.setScaleY(sf);
            if (sw.getWidth() > 0 && sf > 1f) {
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) sw.getLayoutParams();
                p.leftMargin = (int) (sw.getWidth() * (sf - 1f));
                sw.setLayoutParams(p);
            } else if (sw.getWidth() > 0) {
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) sw.getLayoutParams();
                p.leftMargin = 0;
                sw.setLayoutParams(p);
            }
        }
    }
}
