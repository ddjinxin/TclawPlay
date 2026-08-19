package com.jingxin.jingxinmusic.fragment;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jingxin.jingxinmusic.HostActivity;
import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.util.ConfigBackupHelper;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.util.WebDavCacheManager;
import com.jingxin.jingxinmusic.util.WebDavConfig;
import com.jingxin.jingxinmusic.util.WebDavScanner;

/**
 * WebDAV 设置页面 Fragment
 * 配置服务器地址、账号、缓存大小等
 * 配色跟随首页风格系统（4种风格+昼夜模式）
 */
public class WebDavSettingsFragment extends BaseFloatFragment {

    private static final String TAG = "WebDavSettings";

    private WebDavConfig config;
    private boolean isNightMode;
    private View rootView;

    private ScrollView rootScroll;
    private LinearLayout contentLayout;
    private EditText etServerUrl;
    private EditText etUsername;
    private EditText etPassword;
    private EditText etMusicPath;
    private Spinner spinnerCacheSize;
    private TextView tvCacheUsed;
    private Button btnTestConnection;
    private Button btnSave;
    private Button btnImport;
    private Button btnClearCache;

    // 需要动态配色的元素
    private TextView tvTitle;
    private TextView tvLabelServer;
    private TextView tvLabelUsername;
    private TextView tvLabelPassword;
    private TextView tvLabelPath;
    private TextView tvLabelCache;
    private TextView tvCacheLabel;
    private View dividerTop;
    private View dividerServer;
    private View dividerUsername;
    private View dividerPassword;
    private View dividerPath;
    private View dividerCache;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_webdav_settings, container, false);
        rootView = view;

        config = new WebDavConfig(requireContext());

        initViews(view);
        applyTheme();
        loadConfig();

        return view;
    }

    private void initViews(View view) {
        rootScroll = view.findViewById(R.id.root_scroll);
        contentLayout = view.findViewById(R.id.content_layout);
        etServerUrl = view.findViewById(R.id.et_server_url);
        etUsername = view.findViewById(R.id.et_username);
        etPassword = view.findViewById(R.id.et_password);
        etMusicPath = view.findViewById(R.id.et_music_path);
        spinnerCacheSize = view.findViewById(R.id.spinner_cache_size);
        tvCacheUsed = view.findViewById(R.id.tv_cache_used);
        btnTestConnection = view.findViewById(R.id.btn_test_connection);
        btnSave = view.findViewById(R.id.btn_save);
        btnImport = view.findViewById(R.id.btn_import);
        btnClearCache = view.findViewById(R.id.btn_clear_cache);

        tvTitle = view.findViewById(R.id.tv_title);
        tvLabelServer = view.findViewById(R.id.tv_label_server);
        tvLabelUsername = view.findViewById(R.id.tv_label_username);
        tvLabelPassword = view.findViewById(R.id.tv_label_password);
        tvLabelPath = view.findViewById(R.id.tv_label_path);
        tvLabelCache = view.findViewById(R.id.tv_label_cache);
        tvCacheLabel = view.findViewById(R.id.tv_cache_label);
        dividerTop = view.findViewById(R.id.divider_top);
        dividerServer = view.findViewById(R.id.divider_server);
        dividerUsername = view.findViewById(R.id.divider_username);
        dividerPassword = view.findViewById(R.id.divider_password);
        dividerPath = view.findViewById(R.id.divider_path);
        dividerCache = view.findViewById(R.id.divider_cache);

        // 返回按钮
        ImageView btnBack = view.findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());

        // 缓存大小下拉
        int[] cacheOptions = WebDavConfig.getCacheSizeOptions();
        String[] cacheLabels = new String[cacheOptions.length];
        for (int i = 0; i < cacheOptions.length; i++) {
            cacheLabels[i] = WebDavConfig.getCacheSizeLabel(cacheOptions[i]);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(),
                R.layout.spinner_item_theme, cacheLabels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                boolean night = requireContext().getSharedPreferences("theme", android.content.Context.MODE_PRIVATE).getBoolean("isNight", true);
                ((TextView) v).setTextColor(night ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary());
                return v;
            }
            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                boolean night = requireContext().getSharedPreferences("theme", android.content.Context.MODE_PRIVATE).getBoolean("isNight", true);
                ((TextView) v).setTextColor(night ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary());
                return v;
            }
        };
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_theme);
        spinnerCacheSize.setAdapter(adapter);

        // 测试连接
        btnTestConnection.setOnClickListener(v -> testConnection());

        // 保存
        btnSave.setOnClickListener(v -> saveAndBrowse());

        // 提取
        btnImport.setOnClickListener(v -> importFromBackup());

        // 清除缓存
        btnClearCache.setOnClickListener(v -> clearCache());
    }

    /**
     * 根据首页风格系统应用配色
     */
    private void applyTheme() {
        SharedPreferences themePrefs = requireContext().getSharedPreferences("theme", android.content.Context.MODE_PRIVATE);
        isNightMode = themePrefs.getBoolean("isNight", true);
        ThemeColors.init(requireContext());

        int textPrimary = isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();
        int textSecondary = isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary();
        int textTertiary = isNightMode ? ThemeColors.nightTextTertiary() : ThemeColors.dayTextTertiary();
        int dividerColor = isNightMode ? ThemeColors.nightDivider() : ThemeColors.dayDivider();
        int hintColor = isNightMode ? 0xFF555570 : 0xFF999999;

        // 背景渐变
        rootScroll.setBackground(ThemeColors.bgGradient(isNightMode));

        // 标题
        tvTitle.setTextColor(textPrimary);

        // 标签文字
        int labelColor = textSecondary;
        tvLabelServer.setTextColor(labelColor);
        tvLabelUsername.setTextColor(labelColor);
        tvLabelPassword.setTextColor(labelColor);
        tvLabelPath.setTextColor(labelColor);
        tvLabelCache.setTextColor(labelColor);

        // 输入框文字
        int editTextColor = textPrimary;
        etServerUrl.setTextColor(editTextColor);
        etUsername.setTextColor(editTextColor);
        etPassword.setTextColor(editTextColor);
        etMusicPath.setTextColor(editTextColor);

        // 输入框hint
        etServerUrl.setHintTextColor(hintColor);
        etUsername.setHintTextColor(hintColor);
        etPassword.setHintTextColor(hintColor);
        etMusicPath.setHintTextColor(hintColor);

        // 分割线
        int dvColor = dividerColor;
        dividerTop.setBackgroundColor(dvColor);
        dividerServer.setBackgroundColor(dvColor);
        dividerUsername.setBackgroundColor(dvColor);
        dividerPassword.setBackgroundColor(dvColor);
        dividerPath.setBackgroundColor(dvColor);
        dividerCache.setBackgroundColor(dvColor);

        // 缓存相关
        tvCacheLabel.setTextColor(textPrimary);
        tvCacheUsed.setTextColor(textTertiary);
        btnClearCache.setTextColor(0xFFFF5252);

        // 返回键图标颜色
        ImageView btnBack = rootView.findViewById(R.id.btn_back);
        btnBack.setImageTintList(android.content.res.ColorStateList.valueOf(textPrimary));

        // Spinner下拉文字颜色
        try {
            View selectedView = spinnerCacheSize.getSelectedView();
            if (selectedView instanceof TextView) {
                ((TextView) selectedView).setTextColor(textPrimary);
            }
        } catch (Exception ignored) {}

        // Spinner下拉弹窗背景色
        try {
            spinnerCacheSize.setPopupBackgroundDrawable(new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{isNightMode ? ThemeColors.nightItemBg() : ThemeColors.dayItemBg(),
                              isNightMode ? ThemeColors.nightBg() : ThemeColors.dayBg()}));
        } catch (Exception ignored) {}

        // Spinner箭头颜色
        spinnerCacheSize.setBackgroundTintList(android.content.res.ColorStateList.valueOf(textTertiary));
    }

    private void loadConfig() {
        etServerUrl.setText(config.getServerUrl());
        etUsername.setText(config.getUsername());
        etPassword.setText(config.getPassword());
        etMusicPath.setText(config.getMusicPath());

        // 缓存大小
        int[] cacheOptions = WebDavConfig.getCacheSizeOptions();
        int currentSize = config.getCacheSizeMb();
        for (int i = 0; i < cacheOptions.length; i++) {
            if (cacheOptions[i] == currentSize) {
                spinnerCacheSize.setSelection(i);
                break;
            }
        }

        // 缓存占用
        updateCacheUsed();

        // 提取按钮：无备份时灰色不可点
        updateImportButton();

        // spinner监听
        spinnerCacheSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int[] options = WebDavConfig.getCacheSizeOptions();
                config.setCacheSizeMb(options[position]);
                // 设置选中项文字颜色跟随主题
                if (view instanceof TextView) {
                    boolean night = requireContext().getSharedPreferences("theme", android.content.Context.MODE_PRIVATE).getBoolean("isNight", true);
                    ((TextView) view).setTextColor(night ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void saveConfig() {
        config.setServerUrl(etServerUrl.getText().toString().trim());
        config.setUsername(etUsername.getText().toString().trim());
        config.setPassword(etPassword.getText().toString().trim());
        config.setMusicPath(etMusicPath.getText().toString().trim());
        config.setEnabled(true);
        // 同时备份到Download目录，卸载重装后可恢复
        config.exportToDownload();
        // 通知Service重建DataSource（认证头需要更新）
        Intent configIntent = new Intent(com.jingxin.jingxinmusic.service.MusicPlayerService.ACTION_WEBDAV_CONFIG_CHANGED);
        configIntent.setPackage(requireContext().getPackageName());
        requireContext().sendBroadcast(configIntent);
        // 保存后备份文件可能新建，刷新提取按钮状态
        updateImportButton();
    }

    private void testConnection() {
        saveConfig();
        btnTestConnection.setEnabled(false);
        btnTestConnection.setText("测试中...");

        new Thread(() -> {
            WebDavScanner scanner = new WebDavScanner(config);
            String error = scanner.testConnection();
            uiHandler.post(() -> {
                btnTestConnection.setEnabled(true);
                btnTestConnection.setText("测试");
                if (error == null) {
                    Toast.makeText(requireContext(), "连接成功", Toast.LENGTH_SHORT).show();
                    btnTestConnection.setBackgroundColor(0xFF4CAF50);
                } else {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                    btnTestConnection.setBackgroundColor(0xFFFF5252);
                }
            });
        }).start();
    }

    private void saveAndBrowse() {
        saveConfig();
        if (!config.isConfigured()) {
            Toast.makeText(requireContext(), "请填写服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }
        // 跳回首页云端Tab
        if (getActivity() instanceof HostActivity) {
            ((HostActivity) getActivity()).pendingTab = 1;
            requireActivity().onBackPressed();
        }
    }

    private void importFromBackup() {
        // 只回填UI，不写入SharedPreferences，点保存才生效
        String json = readBackupContent();
        if (json == null) {
            Toast.makeText(requireContext(), "未找到备份配置", Toast.LENGTH_SHORT).show();
            updateImportButton();
            return;
        }
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            if (obj.has("server_url")) etServerUrl.setText(obj.getString("server_url"));
            if (obj.has("username")) etUsername.setText(obj.getString("username"));
            if (obj.has("password")) etPassword.setText(obj.getString("password"));
            if (obj.has("music_path")) etMusicPath.setText(obj.getString("music_path"));
            if (obj.has("cache_size_mb")) {
                int sizeMb = obj.getInt("cache_size_mb");
                int[] cacheOptions = WebDavConfig.getCacheSizeOptions();
                for (int i = 0; i < cacheOptions.length; i++) {
                    if (cacheOptions[i] == sizeMb) {
                        spinnerCacheSize.setSelection(i);
                        break;
                    }
                }
            }
            Toast.makeText(requireContext(), "配置已提取，请点保存生效", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "备份文件格式错误", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 读取备份文件内容（通过 MediaoStore/API 兼容方式，不需要特殊权限）
     */
    private String readBackupContent() {
        return ConfigBackupHelper.readBackupContent(requireContext(),
                WebDavConfig.getBackupFilename(), WebDavConfig.getBackupFilenameOld());
    }

    /**
     * 根据Download目录下是否有备份文件，更新提取按钮状态
     * 有备份：橙色可点；无备份：灰色不可点
     */
    private void updateImportButton() {
        boolean hasBackup = config.hasBackup();
        btnImport.setEnabled(hasBackup);
        if (hasBackup) {
            btnImport.setAlpha(1.0f);
            btnImport.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF9800));
        } else {
            btnImport.setAlpha(1.0f);
            btnImport.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF666666));
        }
    }

    private void clearCache() {
        WebDavCacheManager cacheManager = WebDavCacheManager.getInstance(requireContext());
        long sizeBefore = cacheManager.getCacheSize();
        if (sizeBefore == 0) {
            Toast.makeText(requireContext(), "无缓存文件", Toast.LENGTH_SHORT).show();
            return;
        }
        cacheManager.clearCache();
        updateCacheUsed();
        Toast.makeText(requireContext(), "已清除缓存 " + WebDavCacheManager.formatSize(sizeBefore), Toast.LENGTH_SHORT).show();
    }

    private void updateCacheUsed() {
        WebDavCacheManager cacheManager = WebDavCacheManager.getInstance(requireContext());
        long size = cacheManager.getCacheSize();
        tvCacheUsed.setText("当前已用: " + WebDavCacheManager.formatSize(size));
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从权限设置页返回后刷新提取按钮状态
        updateImportButton();
    }
}
