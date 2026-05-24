package com.jingxin.jingxinmusic.ui;

import android.content.Intent;
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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.util.WebDavCacheManager;
import com.jingxin.jingxinmusic.util.WebDavConfig;
import com.jingxin.jingxinmusic.util.WebDavScanner;

/**
 * WebDAV 设置页面
 * 配置服务器地址、账号、缓存大小等
 */
public class WebDavSettingsActivity extends AppCompatActivity {

    private static final String TAG = "WebDavSettings";

    private WebDavConfig config;

    private EditText etServerUrl;
    private EditText etUsername;
    private EditText etPassword;
    private EditText etMusicPath;
    private Spinner spinnerCacheSize;
    private TextView tvCacheUsed;
    private Button btnTestConnection;
    private Button btnSave;
    private Button btnClearCache;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webdav_settings);

        config = new WebDavConfig(this);

        initViews();
        loadConfig();
    }

    private void initViews() {
        etServerUrl = findViewById(R.id.et_server_url);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        etMusicPath = findViewById(R.id.et_music_path);
        spinnerCacheSize = findViewById(R.id.spinner_cache_size);
        tvCacheUsed = findViewById(R.id.tv_cache_used);
        btnTestConnection = findViewById(R.id.btn_test_connection);
        btnSave = findViewById(R.id.btn_save);
        btnClearCache = findViewById(R.id.btn_clear_cache);

        // 返回按钮
        ImageView btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        // 缓存大小下拉
        int[] cacheOptions = WebDavConfig.getCacheSizeOptions();
        String[] cacheLabels = new String[cacheOptions.length];
        for (int i = 0; i < cacheOptions.length; i++) {
            cacheLabels[i] = WebDavConfig.getCacheSizeLabel(cacheOptions[i]);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, cacheLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCacheSize.setAdapter(adapter);

        // 测试连接
        btnTestConnection.setOnClickListener(v -> testConnection());

        // 保存并浏览
        btnSave.setOnClickListener(v -> saveAndBrowse());

        // 清除缓存
        btnClearCache.setOnClickListener(v -> clearCache());
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

        // spinner监听
        spinnerCacheSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int[] options = WebDavConfig.getCacheSizeOptions();
                config.setCacheSizeMb(options[position]);
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
                btnTestConnection.setText("测试连接");
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
        // 跳转到WebDAV浏览页
        startActivity(new Intent(this, WebDavBrowseActivity.class));
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

}
