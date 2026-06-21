package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;

/**
 * WebDAV 配置管理
 * 持久化存储 WebDAV 服务器地址、账号、音乐根目录、缓存设置等
 * 同时备份配置到 /sdcard/Download/ 目录，卸载重装后可自动恢复
 */
public class WebDavConfig {

    private static final String TAG = "WebDavConfig";
    private static final String PREFS_NAME = "webdav_config";
    private static final String BACKUP_FILENAME = "jingxin_webdav_config.json";
    private static final String BACKUP_FILENAME_OLD = ".jingxin_webdav_config";

    // 配置项 key
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_MUSIC_PATH = "music_path";
    private static final String KEY_CACHE_SIZE_MB = "cache_size_mb";
    private static final String KEY_ENABLED = "enabled";

    // 默认值
    private static final String DEFAULT_SERVER_URL = "";
    private static final String DEFAULT_USERNAME = "";
    private static final String DEFAULT_PASSWORD = "";
    private static final String DEFAULT_MUSIC_PATH = "/music/";
    private static final int DEFAULT_CACHE_SIZE_MB = 500;
    private static final boolean DEFAULT_ENABLED = false;

    private final SharedPreferences prefs;
    private final Context context;

    public WebDavConfig(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 不再自动恢复，由用户在设置页手动点"提取"
    }

    // ===== Getters & Setters =====

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    public void setServerUrl(String url) {
        // 确保URL以/结尾
        if (url != null && !url.isEmpty() && !url.endsWith("/")) {
            url = url + "/";
        }
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, DEFAULT_USERNAME);
    }

    public void setUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD);
    }

    public void setPassword(String password) {
        prefs.edit().putString(KEY_PASSWORD, password).apply();
    }

    public String getMusicPath() {
        return prefs.getString(KEY_MUSIC_PATH, DEFAULT_MUSIC_PATH);
    }

    public void setMusicPath(String path) {
        // 确保路径以/开头和结尾
        if (path != null && !path.isEmpty()) {
            if (!path.startsWith("/")) path = "/" + path;
            if (!path.endsWith("/")) path = path + "/";
        }
        prefs.edit().putString(KEY_MUSIC_PATH, path).apply();
    }

    public int getCacheSizeMb() {
        return prefs.getInt(KEY_CACHE_SIZE_MB, DEFAULT_CACHE_SIZE_MB);
    }

    public void setCacheSizeMb(int sizeMb) {
        prefs.edit().putInt(KEY_CACHE_SIZE_MB, sizeMb).apply();
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    // ===== 工具方法 =====

    /**
     * 判断WebDAV是否已配置（地址和账号非空）
     */
    public boolean isConfigured() {
        String url = getServerUrl();
        return url != null && !url.isEmpty()
                && url.startsWith("http");
    }

    /**
     * 获取完整的WebDAV音乐目录URL
     */
    public String getMusicUrl() {
        String serverUrl = getServerUrl();
        String musicPath = getMusicPath();
        if (serverUrl == null || serverUrl.isEmpty()) return "";
        // 拼接：serverUrl + musicPath（去掉musicPath开头的/，避免重复）
        if (musicPath.startsWith("/")) {
            musicPath = musicPath.substring(1);
        }
        return serverUrl + musicPath;
    }

    /**
     * 获取Basic Auth编码字符串
     */
    public String getAuthHeader() {
        String username = getUsername();
        String password = getPassword();
        if (username == null || username.isEmpty()) return "";
        String credentials = username + ":" + (password != null ? password : "");
        return "Basic " + android.util.Base64.encodeToString(
                credentials.getBytes(), android.util.Base64.NO_WRAP);
    }

    /**
     * 获取缓存大小选项数组
     */
    public static int[] getCacheSizeOptions() {
        return new int[]{200, 500, 1000, 2000, -1}; // -1表示无限制
    }

    /**
     * 获取缓存大小显示文字
     */
    public static String getCacheSizeLabel(int sizeMb) {
        if (sizeMb <= 0) return "无限制";
        if (sizeMb >= 1000) return (sizeMb / 1000) + "GB";
        return sizeMb + "MB";
    }

    /**
     * 清除所有WebDAV配置
     */
    public void clearAll() {
        prefs.edit().clear().apply();
        File backup = new File(getBackupPath());
        if (backup != null && backup.exists()) backup.delete();
        File backupOld = new File(getOldBackupPath());
        if (backupOld != null && backupOld.exists()) backupOld.delete();
    }

    // ===== 备份与恢复（Download目录） =====

    private String getBackupPath() {
        return "/sdcard/Download/" + BACKUP_FILENAME;
    }

    private String getOldBackupPath() {
        return "/sdcard/Download/" + BACKUP_FILENAME_OLD;
    }

    public boolean hasBackup() {
        return ConfigBackupHelper.hasBackup(getBackupPath(), getOldBackupPath());
    }

    public boolean exportToDownload() {
        try {
            JSONObject json = new JSONObject();
            json.put(KEY_SERVER_URL, getServerUrl());
            json.put(KEY_USERNAME, getUsername());
            json.put(KEY_PASSWORD, getPassword());
            json.put(KEY_MUSIC_PATH, getMusicPath());
            json.put(KEY_CACHE_SIZE_MB, getCacheSizeMb());
            json.put(KEY_ENABLED, isEnabled());
            return ConfigBackupHelper.exportToDownload(getBackupPath(), json, "WebDAV");
        } catch (Exception e) {
            Log.e(TAG, "导出WebDAV配置失败: " + e.getMessage());
            return false;
        }
    }

    public boolean importFromDownload() {
        File backup = ConfigBackupHelper.findBackupFile(getBackupPath(), getOldBackupPath());
        return ConfigBackupHelper.importFromDownload(backup, prefs, (editor, json) -> {
            try {
                if (json.has(KEY_SERVER_URL)) editor.putString(KEY_SERVER_URL, json.getString(KEY_SERVER_URL));
                if (json.has(KEY_USERNAME)) editor.putString(KEY_USERNAME, json.getString(KEY_USERNAME));
                if (json.has(KEY_PASSWORD)) editor.putString(KEY_PASSWORD, json.getString(KEY_PASSWORD));
                if (json.has(KEY_MUSIC_PATH)) editor.putString(KEY_MUSIC_PATH, json.getString(KEY_MUSIC_PATH));
                if (json.has(KEY_CACHE_SIZE_MB)) editor.putInt(KEY_CACHE_SIZE_MB, json.getInt(KEY_CACHE_SIZE_MB));
                if (json.has(KEY_ENABLED)) editor.putBoolean(KEY_ENABLED, json.getBoolean(KEY_ENABLED));
            } catch (org.json.JSONException e) {
                throw new RuntimeException(e);
            }
        }, "WebDAV");
    }
}
