package com.jingxin.jingxinmusic.util;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class ConfigBackupHelper {

    private static final String TAG = "ConfigBackup";

    public static boolean hasBackup(String... filePaths) {
        for (String path : filePaths) {
            File f = new File(path);
            if (f.exists() && f.canRead()) {
                Log.d(TAG, "hasBackup: found readable backup: " + path);
                return true;
            }
        }
        return false;
    }

    public static boolean exportToDownload(String backupPath, JSONObject json, String logLabel) {
        File backup = new File(backupPath);
        try {
            FileOutputStream fos = new FileOutputStream(backup);
            fos.write(json.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
            backup.setReadable(true, false);
            Log.i(TAG, logLabel + "配置已导出到: " + backup.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "导出" + logLabel + "配置失败: " + e.getMessage());
            return false;
        }
    }

    public static File findBackupFile(String... filePaths) {
        for (String path : filePaths) {
            File f = new File(path);
            if (f.exists()) return f;
        }
        return null;
    }

    public static boolean importFromDownload(File backup, SharedPreferences prefs,
                                              ImportHandler handler, String logLabel) {
        if (backup == null) return false;
        try {
            FileInputStream fis = new FileInputStream(backup);
            byte[] buffer = new byte[(int) backup.length()];
            fis.read(buffer);
            fis.close();

            JSONObject json = new JSONObject(new String(buffer, "UTF-8"));

            SharedPreferences.Editor editor = prefs.edit();
            handler.applyJson(editor, json);
            editor.apply();

            Log.i(TAG, "从备份恢复" + logLabel + "配置成功: " + backup.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "导入" + logLabel + "配置失败: " + e.getMessage());
            return false;
        }
    }

    public interface ImportHandler {
        void applyJson(SharedPreferences.Editor editor, JSONObject json);
    }
}
