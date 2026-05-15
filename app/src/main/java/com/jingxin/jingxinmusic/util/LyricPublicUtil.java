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
                // Android 10+：通过 MediaStore.Downloads 写入
                copyViaMediaStore(context, srcFile);
            } else {
                // Android 9 以下：直接 File API 写入
                copyViaFileApi(srcFile);
            }
        } catch (Exception e) {
            Log.e(TAG, "复制歌词到公共目录失败: " + e.getMessage());
        }
    }

    /**
     * Android 10+：通过 MediaStore.Downloads 写入
     * 检查是否已存在同名文件，存在则跳过
     */
    private static void copyViaMediaStore(Context context, File srcFile) {
        ContentResolver resolver = context.getContentResolver();
        String fileName = srcFile.getName();

        // 检查是否已存在
        Uri downloadsUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.DISPLAY_NAME + " = ? AND " +
                MediaStore.Downloads.RELATIVE_PATH + " = ?";
        String[] selectionArgs = {fileName, Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_DIR_NAME};

        try (android.database.Cursor cursor = resolver.query(downloadsUri, projection, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                Log.d(TAG, "公共目录已存在，跳过: " + fileName);
                return;
            }
        }

        // 写入新文件
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, getMimeType(fileName));
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_DIR_NAME);

        Uri uri = resolver.insert(downloadsUri, values);
        if (uri == null) {
            Log.e(TAG, "MediaStore insert 失败: " + fileName);
            return;
        }

        try (InputStream is = new FileInputStream(srcFile);
             OutputStream os = resolver.openOutputStream(uri)) {
            if (os == null) return;
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
            Log.d(TAG, "歌词已复制到公共目录: " + fileName);
        } catch (Exception e) {
            Log.e(TAG, "写入公共目录失败: " + e.getMessage());
            // 写入失败，删除残留条目
            resolver.delete(uri, null, null);
        }
    }

    /**
     * Android 9 以下：直接 File API 写入
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
        if (destFile.exists()) {
            Log.d(TAG, "公共目录已存在，跳过: " + srcFile.getName());
            return;
        }

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
