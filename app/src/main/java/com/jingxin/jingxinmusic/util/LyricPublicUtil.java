package com.jingxin.jingxinmusic.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 歌词文件公共目录工具
 * 将歌词文件额外复制到 /sdcard/Download/lyrics/ 供其他应用访问
 * Android 10+ 通过 MediaStore.Downloads API 写入
 * Android 9 以下直接 File API 写入
 */
public class LyricPublicUtil {

    private static final String TAG = "LyricPublicUtil";
    private static final String PUBLIC_DIR_NAME = "lyrics";

    /**
     * 将歌词文件复制到公共下载目录
     * @param context 上下文
     * @param srcFile 源文件（应用专属目录中的歌词文件）
     */
    public static void copyToPublicDir(Context context, File srcFile) {
        if (srcFile == null || !srcFile.exists()) return;

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                copyViaMediaStore(context, srcFile);
            } else {
                copyViaFileApi(srcFile);
            }
        } catch (Exception e) {
            Log.e(TAG, "复制歌词到公共目录失败: " + e.getMessage());
        }
    }

    /**
     * Android 10+：通过 MediaStore.Downloads 写入
     * 用 DISPLAY_NAME 查询同名文件，Java 侧匹配 RELATIVE_PATH：
     * - 已存在 → 直接覆盖内容（openOutputStream，原子操作）
     * - 不存在 → insert 新条目
     */
    private static void copyViaMediaStore(Context context, File srcFile) {
        ContentResolver resolver = context.getContentResolver();
        String fileName = srcFile.getName();
        String targetRelativePath = Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_DIR_NAME;
        Uri downloadsUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

        // 查询是否已存在
        Uri existingUri = null;
        String[] projection = {MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH};
        String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
        String[] selectionArgs = {fileName};

        try (android.database.Cursor cursor = resolver.query(downloadsUri, projection, selection, selectionArgs, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String relativePath = cursor.getString(1);
                    if (pathMatch(relativePath, targetRelativePath)) {
                        long id = cursor.getLong(0);
                        existingUri = android.content.ContentUris.withAppendedId(downloadsUri, id);
                        break;
                    }
                }
            }
        }

        Uri targetUri;
        if (existingUri != null) {
            // 已存在，直接覆盖
            targetUri = existingUri;
        } else {
            // 不存在，insert 新条目
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, getMimeType(fileName));
            values.put(MediaStore.Downloads.RELATIVE_PATH, targetRelativePath);

            targetUri = resolver.insert(downloadsUri, values);
            if (targetUri == null) {
                Log.e(TAG, "MediaStore insert 失败: " + fileName);
                return;
            }
        }

        try (InputStream is = new FileInputStream(srcFile);
             OutputStream os = resolver.openOutputStream(targetUri, "w")) {
            if (os == null) return;
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
            Log.d(TAG, existingUri != null ? "歌词已覆盖: " + fileName : "歌词已复制到公共目录: " + fileName);
        } catch (Exception e) {
            Log.e(TAG, "写入公共目录失败: " + e.getMessage());
            // 新插入的条目写入失败则清理
            if (existingUri == null) {
                resolver.delete(targetUri, null, null);
            }
        }
    }

    /**
     * 路径匹配：忽略大小写和尾部斜杠差异
     * 例如 "Download/lyrics" 匹配 "Download/lyrics/" 和 "download/Lyrics"
     */
    private static boolean pathMatch(String actual, String expected) {
        if (actual == null || expected == null) return false;
        String a = actual.endsWith("/") ? actual.substring(0, actual.length() - 1) : actual;
        String e = expected.endsWith("/") ? expected.substring(0, expected.length() - 1) : expected;
        return a.equalsIgnoreCase(e);
    }

    /**
     * Android 9 以下：直接 File API 写入
     * 已存在则覆盖，不存在则新建
     * 需要 WRITE_EXTERNAL_STORAGE 权限，无权限则跳过
     */
    private static void copyViaFileApi(File srcFile) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File lyricDir = new File(downloadsDir, PUBLIC_DIR_NAME);

        // 确保目录存在
        if (!lyricDir.exists()) {
            if (!lyricDir.mkdirs()) {
                Log.e(TAG, "创建公共歌词目录失败: " + lyricDir.getAbsolutePath());
                return;
            }
        }

        File destFile = new File(lyricDir, srcFile.getName());

        try (FileInputStream is = new FileInputStream(srcFile);
             FileOutputStream os = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
            Log.d(TAG, "歌词已复制到公共目录: " + srcFile.getName());
        } catch (Exception e) {
            Log.e(TAG, "File API 复制失败: " + e.getMessage());
        }
    }

    private static String getMimeType(String fileName) {
        // 统一用 application/octet-stream，避免系统自动追加 .txt 后缀
        return "application/octet-stream";
    }
}
