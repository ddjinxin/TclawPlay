package com.jingxin.jingxinmusic.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
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

import androidx.appcompat.app.AppCompatActivity;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.util.WebDavCacheManager;
import com.jingxin.jingxinmusic.util.WebDavConfig;
import com.jingxin.jingxinmusic.util.WebDavScanner;

/**
 * WebDAV 设置页面
 * 配置服务器地址、账号、缓存大小等
 * 配色跟随首页风格系统（4种风格+昼夜模式）
 */
public class WebDavSettingsActivity extends AppCompatActivity {

    private static final String TAG = "WebDavSettings";

    private WebDavConfig config;
    private boolean isNightMode;

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webdav_settings);

        config = new WebDavConfig(this);

        initViews();
        applyTheme();
        loadConfig();
        checkStoragePermission();
    }

    /**
     * 检查存储权限，没有则弹窗引导用户去系统设置授权
     */
    private void checkStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (!android.os.Environment.isExternalStorageManager()) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage("读取备份配置需要\"所有文件访问\"权限，请在设置中开启")
                    .setPositiveButton("去设置", (d, w) -> {
                        try {
                            startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + getPackageName())));
                        }
                    })
                    .setNegativeButton("取消", null)
                    .setCancelable(false)
                    .show();
            }
        }
    }

    private void initViews() {
        rootScroll = findViewById(R.id.root_scroll);
        contentLayout = findViewById(R.id.content_layout);
        etServerUrl = findViewById(R.id.et_server_url);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        etMusicPath = findViewById(R.id.et_music_path);
        spinnerCacheSize = findViewById(R.id.spinner_cache_size);
        tvCacheUsed = findViewById(R.id.tv_cache_used);
        btnTestConnection = findViewById(R.id.btn_test_connection);
        btnSave = findViewById(R.id.btn_save);
        btnImport = findViewById(R.id.btn_import);
        btnClearCache = findViewById(R.id.btn_clear_cache);

        tvTitle = findViewById(R.id.tv_title);
        tvLabelServer = findViewById(R.id.tv_label_server);
        tvLabelUsername = findViewById(R.id.tv_label_username);
        tvLabelPassword = findViewById(R.id.tv_label_password);
        tvLabelPath = findViewById(R.id.tv_label_path);
        tvLabelCache = findViewById(R.id.tv_label_cache);
        tvCacheLabel = findViewById(R.id.tv_cache_label);
        dividerTop = findViewById(R.id.divider_top);
        dividerServer = findViewById(R.id.divider_server);
        dividerUsername = findViewById(R.id.divider_username);
        dividerPassword = findViewById(R.id.divider_password);
        dividerPath = findViewById(R.id.divider_path);
        dividerCache = findViewById(R.id.divider_cache);

        // 返回按钮
        ImageView btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        // 缓存大小下拉
        int[] cacheOptions = WebDavConfig.getCacheSizeOptions();
        String[] cacheLabels = new String[cacheOptions.length];
        for (int i = 0; i < cacheOptions.length; i++) {
            cacheLabels[i] = WebDavConfig.getCacheSizeLabel(cacheOptions[i]);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                R.layout.spinner_item_theme, cacheLabels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                boolean night = getSharedPreferences("theme", MODE_PRIVATE).getBoolean("isNight", true);
                ((TextView) v).setTextColor(night ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary());
                return v;
            }
            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                boolean night = getSharedPreferences("theme", MODE_PRIVATE).getBoolean("isNight", true);
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
        SharedPreferences themePrefs = getSharedPreferences("theme", MODE_PRIVATE);
        isNightMode = themePrefs.getBoolean("isNight", true);
        ThemeColors.init(this);

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
        ImageView btnBack = findViewById(R.id.btn_back);
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
                    boolean night = getSharedPreferences("theme", MODE_PRIVATE).getBoolean("isNight", true);
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
        configIntent.setPackage(getPackageName());
        sendBroadcast(configIntent);
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
                    Toast.makeText(this, "连接成功", Toast.LENGTH_SHORT).show();
                    btnTestConnection.setBackgroundColor(0xFF4CAF50);
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    btnTestConnection.setBackgroundColor(0xFFFF5252);
                }
            });
        }).start();
    }

    private void saveAndBrowse() {
        saveConfig();
        if (!config.isConfigured()) {
            Toast.makeText(this, "请填写服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }
        // 跳转到首页云端Tab
        Intent intent = new Intent(this, com.jingxin.jingxinmusic.MainActivity.class);
        intent.putExtra("select_tab", 1); // 1=云端Tab
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void importFromBackup() {
        // 只回填UI，不写入SharedPreferences，点保存才生效
        String json = readBackupContent();
        if (json == null) {
            Toast.makeText(this, "未找到备份配置", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "配置已提取，请点保存生效", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "备份文件格式错误", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 读取Download目录下备份文件的内容（不写入SharedPreferences）
     */
    private String readBackupContent() {
        java.io.File backup = new java.io.File("/sdcard/Download/jingxin_webdav_config.json");
        java.io.File oldBackup = new java.io.File("/sdcard/Download/.jingxin_webdav_config");
        if (!backup.exists() && !oldBackup.exists()) return null;
        java.io.File file = backup.exists() ? backup : oldBackup;
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            fis.read(buffer);
            fis.close();
            return new String(buffer, "UTF-8");
        } catch (Exception e) {
            return null;
        }
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
        WebDavCacheManager cacheManager = WebDavCacheManager.getInstance(this);
        long sizeBefore = cacheManager.getCacheSize();
        if (sizeBefore == 0) {
            Toast.makeText(this, "无缓存文件", Toast.LENGTH_SHORT).show();
            return;
        }
        cacheManager.clearCache();
        updateCacheUsed();
        Toast.makeText(this, "已清除缓存 " + WebDavCacheManager.formatSize(sizeBefore), Toast.LENGTH_SHORT).show();
    }

    private void updateCacheUsed() {
        WebDavCacheManager cacheManager = WebDavCacheManager.getInstance(this);
        long size = cacheManager.getCacheSize();
        tvCacheUsed.setText("当前已用: " + WebDavCacheManager.formatSize(size));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从权限设置页返回后刷新提取按钮状态
        updateImportButton();
    }

}
