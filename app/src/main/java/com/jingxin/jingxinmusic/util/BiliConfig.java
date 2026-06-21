package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;

/**
 * B站配置管理
 * 持久化存储 B站登录Cookie（SESSDATA等）
 * 仿照 WebDavConfig 的模式，支持备份恢复
 */
public class BiliConfig {

    private static final String TAG = "BiliConfig";
    private static final String PREFS_NAME = "bili_config";
    private static final String BACKUP_FILENAME = "jingxin_bili_config.json";

    // 配置项 key
    private static final String KEY_SESSDATA = "sessdata";
    private static final String KEY_BILI_JCT = "bili_jct";
    private static final String KEY_DEDE_USER_ID = "dede_user_id";
    private static final String KEY_NICKNAME = "nickname";
    private static final String KEY_AVATAR_URL = "avatar_url";
    private static final String KEY_ENABLED = "enabled";

    // 默认值
    private static final String DEFAULT_EMPTY = "";

    private final SharedPreferences prefs;
    private final Context context;

    public BiliConfig(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ===== Getters & Setters =====

    public String getSessData() {
        return prefs.getString(KEY_SESSDATA, DEFAULT_EMPTY);
    }

    public void setSessData(String sessdata) {
        prefs.edit().putString(KEY_SESSDATA, sessdata).apply();
    }

    public String getBiliJct() {
        return prefs.getString(KEY_BILI_JCT, DEFAULT_EMPTY);
    }

    public void setBiliJct(String jct) {
        prefs.edit().putString(KEY_BILI_JCT, jct).apply();
    }

    public String getDedeUserId() {
        return prefs.getString(KEY_DEDE_USER_ID, DEFAULT_EMPTY);
    }

    public void setDedeUserId(String uid) {
        prefs.edit().putString(KEY_DEDE_USER_ID, uid).apply();
    }

    public String getNickname() {
        return prefs.getString(KEY_NICKNAME, DEFAULT_EMPTY);
    }

    public void setNickname(String name) {
        prefs.edit().putString(KEY_NICKNAME, name).apply();
    }

    public String getAvatarUrl() {
        return prefs.getString(KEY_AVATAR_URL, DEFAULT_EMPTY);
    }

    public void setAvatarUrl(String url) {
        prefs.edit().putString(KEY_AVATAR_URL, url).apply();
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    // ===== 工具方法 =====

    /**
     * 判断B站是否已配置（SESSDATA非空）
     */
    public boolean isConfigured() {
        String sd = getSessData();
        return sd != null && !sd.isEmpty();
    }

    /**
     * 拼接完整Cookie字符串
     */
    public String getAuthCookie() {
        StringBuilder sb = new StringBuilder();
        String sd = getSessData();
        if (sd != null && !sd.isEmpty()) {
            sb.append("SESSDATA=").append(sd);
        }
        String jct = getBiliJct();
        if (jct != null && !jct.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("bili_jct=").append(jct);
        }
        String uid = getDedeUserId();
        if (uid != null && !uid.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("DedeUserID=").append(uid);
        }
        return sb.toString();
    }

    /**
     * 清除所有B站配置
     */
    public void clearAll() {
        prefs.edit().clear().apply();
        File backup = new File(getBackupPath());
        if (backup != null && backup.exists()) backup.delete();
    }

    // ===== 备份与恢复（Download目录） =====

    private String getBackupPath() {
        return "/sdcard/Download/" + BACKUP_FILENAME;
    }

    public boolean hasBackup() {
        return ConfigBackupHelper.hasBackup(getBackupPath());
    }

    public boolean exportToDownload() {
        try {
            JSONObject json = new JSONObject();
            json.put(KEY_SESSDATA, getSessData());
            json.put(KEY_BILI_JCT, getBiliJct());
            json.put(KEY_DEDE_USER_ID, getDedeUserId());
            json.put(KEY_NICKNAME, getNickname());
            json.put(KEY_AVATAR_URL, getAvatarUrl());
            json.put(KEY_ENABLED, isEnabled());
            return ConfigBackupHelper.exportToDownload(getBackupPath(), json, "B站");
        } catch (Exception e) {
            Log.e(TAG, "导出B站配置失败: " + e.getMessage());
            return false;
        }
    }

    public boolean importFromDownload() {
        File backup = ConfigBackupHelper.findBackupFile(getBackupPath());
        return ConfigBackupHelper.importFromDownload(backup, prefs, (editor, json) -> {
            try {
                if (json.has(KEY_SESSDATA)) editor.putString(KEY_SESSDATA, json.getString(KEY_SESSDATA));
                if (json.has(KEY_BILI_JCT)) editor.putString(KEY_BILI_JCT, json.getString(KEY_BILI_JCT));
                if (json.has(KEY_DEDE_USER_ID)) editor.putString(KEY_DEDE_USER_ID, json.getString(KEY_DEDE_USER_ID));
                if (json.has(KEY_NICKNAME)) editor.putString(KEY_NICKNAME, json.getString(KEY_NICKNAME));
                if (json.has(KEY_AVATAR_URL)) editor.putString(KEY_AVATAR_URL, json.getString(KEY_AVATAR_URL));
                if (json.has(KEY_ENABLED)) editor.putBoolean(KEY_ENABLED, json.getBoolean(KEY_ENABLED));
            } catch (org.json.JSONException e) {
                throw new RuntimeException(e);
            }
        }, "B站");
    }
}
