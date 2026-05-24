package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * WebDAV 配置管理
 * 持久化存储 WebDAV 服务器地址、账号、音乐根目录、缓存设置等
 */
public class WebDavConfig {

    private static final String PREFS_NAME = "webdav_config";

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

    public WebDavConfig(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
    }
}
