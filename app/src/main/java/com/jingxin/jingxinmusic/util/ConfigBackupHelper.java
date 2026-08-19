package com.jingxin.jingxinmusic.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class ConfigBackupHelper {

    private static final String TAG = "ConfigBackup";

    /**
     * 获取备份文件路径（兼容 Android 10+ 分区存储）
     * Android 10+：Download 目录由系统管理，本方法仅用于 Android 9 及以下
     * Android 10+：使用 MediaStore API 读写，路径仅用于查询 RELATIVE_PATH
     */
    private static String getRelativePath() {
        return Environment.DIRECTORY_DOWNLOADS + "/";
    }

    /**
     * 检查备份是否存在
     * Android 10+：通过 MediaStore.Downloads 查询
     * Android 9-：直接 File 检查
     */
    public static boolean hasBackup(Context context, String... fileNames) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (String name : fileNames) {
                if (findBackupUri(context, name) != null) {
                    Log.d(TAG, "hasBackup: found readable backup: " + name);
                    return true;
                }
            }
            // Android 10+ 也检查旧版 File 路径（兼容旧版本写入的备份）
            for (String name : fileNames) {
                File legacy = getLegacyFile(name);
                if (legacy.exists() && legacy.canRead()) return true;
            }
        } else {
            for (String name : fileNames) {
                File f = getLegacyFile(name);
                if (f.exists() && f.canRead()) {
                    Log.d(TAG, "hasBackup: found readable backup: " + name);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 导出配置到公共下载目录
     * Android 10+：通过 MediaStore.Downloads 写入（不需要特殊权限）
     * Android 9-：直接 File API 写入
     */
    public static boolean exportToDownload(Context context, String fileName, JSONObject json, String logLabel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return exportViaMediaStore(context, fileName, json, logLabel);
        } else {
            return exportViaFileApi(fileName, json, logLabel);
        }
    }

    /**
     * 查找备份文件并返回输入流
     * Android 10+：通过 MediaStore 查询，找不到则尝试旧版 File 路径
     * Android 9-：直接 File 查找
     */
    public static InputStream openBackupInputStream(Context context, String... fileNames) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (String name : fileNames) {
                Uri uri = findBackupUri(context, name);
                if (uri != null) {
                    try {
                        return context.getContentResolver().openInputStream(uri);
                    } catch (Exception e) {
                        Log.w(TAG, "打开备份失败: " + name + " - " + e.getMessage());
                    }
                }
                // 兼容旧版 File 路径
                File legacy = getLegacyFile(name);
                if (legacy.exists() && legacy.canRead()) {
                    try { return new FileInputStream(legacy); } catch (Exception ignored) {}
                }
            }
        } else {
            for (String name : fileNames) {
                File f = getLegacyFile(name);
                if (f.exists()) {
                    try { return new FileInputStream(f); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    /**
     * 通过 MediaStore 读取备份内容
     */
    public static String readBackupContent(Context context, String... fileNames) {
        try (InputStream is = openBackupInputStream(context, fileNames)) {
            if (is == null) return null;
            byte[] buffer = new byte[4096];
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            int len;
            while ((len = is.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "读取备份内容失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 通过 MediaStore 导入配置
     */
    public static boolean importFromDownload(Context context, SharedPreferences prefs,
                                              ImportHandler handler, String logLabel, String... fileNames) {
        try (InputStream is = openBackupInputStream(context, fileNames)) {
            if (is == null) return false;
            byte[] buffer = new byte[4096];
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            int len;
            while ((len = is.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            JSONObject json = new JSONObject(new String(baos.toByteArray(), "UTF-8"));
            SharedPreferences.Editor editor = prefs.edit();
            handler.applyJson(editor, json);
            editor.apply();
            Log.i(TAG, "从备份恢复" + logLabel + "配置成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "导入" + logLabel + "配置失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 删除备份文件
     */
    public static void deleteBackup(Context context, String... fileNames) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (String name : fileNames) {
                Uri uri = findBackupUri(context, name);
                if (uri != null) {
                    try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
                }
                File legacy = getLegacyFile(name);
                if (legacy.exists()) legacy.delete();
            }
        } else {
            for (String name : fileNames) {
                File f = getLegacyFile(name);
                if (f.exists()) f.delete();
            }
        }
    }

    // ===== 内部方法 =====

    private static File getLegacyFile(String fileName) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(downloadsDir, fileName);
    }

    /**
     * Android 10+：通过 MediaStore.Downloads 查询备份文件 Uri
     */
    private static Uri findBackupUri(Context context, String fileName) {
        ContentResolver resolver = context.getContentResolver();
        Uri downloadsUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
        String[] selectionArgs = {fileName};
        try (Cursor cursor = resolver.query(downloadsUri, projection, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return android.content.ContentUris.withAppendedId(downloadsUri, id);
            }
        } catch (Exception e) {
            Log.w(TAG, "查询备份Uri失败: " + fileName + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * Android 10+：通过 MediaStore.Downloads 写入
     */
    private static boolean exportViaMediaStore(Context context, String fileName, JSONObject json, String logLabel) {
        ContentResolver resolver = context.getContentResolver();
        String relativePath = getRelativePath();

        // 查询是否已存在
        Uri existingUri = findBackupUri(context, fileName);
        Uri targetUri;
        if (existingUri != null) {
            targetUri = existingUri;
        } else {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
            targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (targetUri == null) {
                Log.e(TAG, "MediaStore insert 失败: " + fileName);
                return false;
            }
        }

        try (OutputStream os = resolver.openOutputStream(targetUri, "w")) {
            if (os == null) return false;
            os.write(json.toString().getBytes("UTF-8"));
            os.flush();
            Log.i(TAG, logLabel + "配置已导出到 Download目录");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "导出" + logLabel + "配置失败: " + e.getMessage());
            if (existingUri == null) {
                try { resolver.delete(targetUri, null, null); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    /**
     * Android 9-：直接 File API 写入
     */
    private static boolean exportViaFileApi(String fileName, JSONObject json, String logLabel) {
        File backup = getLegacyFile(fileName);
        try {
            File dir = backup.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
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

    public interface ImportHandler {
        void applyJson(SharedPreferences.Editor editor, JSONObject json);
    }
}
