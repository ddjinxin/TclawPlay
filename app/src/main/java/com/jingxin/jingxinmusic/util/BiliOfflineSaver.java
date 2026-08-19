package com.jingxin.jingxinmusic.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.jingxin.jingxinmusic.model.Song;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * B站音频离线保存工具
 * 将B站DASH音频流下载为m4a格式，同时保存封面jpg和歌词lrc到同一目录
 * 存储位置：Download/music/
 * Android 10+ 通过 MediaStore.Downloads 写入，Android 9- 用 File API
 */
public class BiliOfflineSaver {

    private static final String TAG = "BiliOfflineSaver";
    private static final String SAVE_DIR_NAME = "music";
    private static final int BUFFER_SIZE = 64 * 1024; // 64KB缓冲
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    /**
     * 保存回调接口
     */
    public interface SaveCallback {
        void onSuccess(File m4aFile);
        void onSkipped(String reason);
        void onFailed(String error);
        void onProgress(int percent); // 0-100
    }

    /**
     * 异步保存B站歌曲到本地
     */
    public static void saveAsync(Context context, Song song, SaveCallback callback) {
        new Thread(() -> saveSync(context, song, callback), "BiliOfflineSave").start();
    }

    /**
     * 同步保存（在调用线程执行）
     */
    private static void saveSync(Context context, Song song, SaveCallback callback) {
        try {
            // 1. 检查B站登录状态
            BiliConfig config = new BiliConfig(context);
            if (!config.isConfigured()) {
                if (callback != null) callback.onSkipped("未登录B站");
                return;
            }

            // 2. 清理文件名中的非法字符
            String baseName = sanitizeFileName(song.title);

            // 3. 检查是否已存在
            if (isSaved(context, song)) {
                if (callback != null) callback.onSkipped("已保存过");
                return;
            }

            // 4. 获取音频流URL
            if (callback != null) callback.onProgress(5);
            BiliApi.AudioPlayInfo playInfo;
            if (song.cid > 0) {
                playInfo = BiliApi.getAudioPlayInfo(song.bvid, song.cid, config);
            } else {
                playInfo = BiliApi.getAudioPlayInfo(song.bvid, config);
            }
            if (playInfo == null || playInfo.audioUrl == null || playInfo.audioUrl.isEmpty()) {
                if (callback != null) callback.onFailed("获取音频流失败");
                return;
            }

            // 5. 下载音频流到应用缓存临时文件
            if (callback != null) callback.onProgress(10);
            Map<String, String> audioHeaders = new HashMap<>();
            audioHeaders.put("Referer", "https://www.bilibili.com");
            audioHeaders.put("Cookie", config.getAuthCookie());

            File tempDir = context.getCacheDir();
            File tempM4a = new File(tempDir, baseName + ".m4a.tmp");
            boolean downloadOk = downloadFile(playInfo.audioUrl, audioHeaders, tempM4a,
                    percent -> {
                        if (callback != null) callback.onProgress(10 + percent * 70 / 100);
                    });
            if (!downloadOk) {
                tempM4a.delete();
                if (callback != null) callback.onFailed("下载音频失败");
                return;
            }

            // 6. 读取封面字节（从应用缓存，不另存jpg）
            if (callback != null) callback.onProgress(85);
            byte[] coverJpg = null;
            File coverDir = context.getExternalFilesDir("covers");
            if (coverDir != null && coverDir.isDirectory()) {
                String coverName = Song.toFileName(song.title, song.artist) + ".jpg";
                File cachedCover = new File(coverDir, coverName);
                if (cachedCover.exists() && cachedCover.length() > 0) {
                    try {
                        FileInputStream fis = new FileInputStream(cachedCover);
                        coverJpg = new byte[(int) cachedCover.length()];
                        fis.read(coverJpg);
                        fis.close();
                        Log.d(TAG, "读取缓存封面: " + coverName);
                    } catch (Exception e) {
                        Log.w(TAG, "读取封面字节失败: " + e.getMessage());
                    }
                }
            }

            // 7. 获取歌词内容（不另存lrc）
            if (callback != null) callback.onProgress(90);
            String lrcContent = buildLrcContent(context, song);

            // 8. 将封面和歌词内嵌到临时 m4a 文件
            File finalM4a = new File(tempDir, baseName + ".m4a");
            if (!tempM4a.renameTo(finalM4a)) {
                // rename 失败则复制
                copyFile(tempM4a, finalM4a);
                tempM4a.delete();
            }
            if (coverJpg != null || (lrcContent != null && !lrcContent.isEmpty())) {
                boolean embedded = M4aMetadataWriter.embedMetadata(finalM4a, coverJpg, lrcContent);
                if (embedded) {
                    Log.d(TAG, "封面和歌词已内嵌到m4a");
                    // 内嵌成功后删除外部封面/歌词文件（如果有）
                    coverJpg = null;
                    lrcContent = null;
                } else {
                    Log.w(TAG, "内嵌元数据失败，回退保存外部文件");
                }
            }

            // 9. 通过 MediaStore 写入公共 Download/music/ 目录
            Uri m4aUri = writeToPublicDir(context, finalM4a, baseName + ".m4a", "audio/mp4");
            if (m4aUri == null) {
                finalM4a.delete();
                if (callback != null) callback.onFailed("写入公共目录失败");
                return;
            }
            Log.d(TAG, "音频已保存到 Download/music/" + baseName + ".m4a");

            // 内嵌失败时保存外部封面和歌词
            if (coverJpg != null) {
                File tempJpg = new File(tempDir, baseName + ".jpg");
                try {
                    FileOutputStream fos = new FileOutputStream(tempJpg);
                    fos.write(coverJpg);
                    fos.close();
                    writeToPublicDir(context, tempJpg, baseName + ".jpg", "image/jpeg");
                    tempJpg.delete();
                } catch (Exception ignored) {}
            }
            if (lrcContent != null && !lrcContent.isEmpty()) {
                File tempLrc = new File(tempDir, baseName + ".lrc");
                try {
                    FileOutputStream fos = new FileOutputStream(tempLrc);
                    fos.write(lrcContent.getBytes("UTF-8"));
                    fos.close();
                    writeToPublicDir(context, tempLrc, baseName + ".lrc", "application/octet-stream");
                    tempLrc.delete();
                } catch (Exception ignored) {}
            }

            // 10. 清理临时文件 + 触发媒体扫描
            finalM4a.delete();
            if (callback != null) callback.onProgress(100);
            triggerMediaScan(context, m4aUri);

            // 回调返回一个非 null 的 File（用公共路径构造，主要用于日志）
            if (callback != null) callback.onSuccess(new File("Download/music/" + baseName + ".m4a"));

        } catch (Exception e) {
            Log.e(TAG, "离线保存失败: " + e.getMessage());
            if (callback != null) callback.onFailed(e.getMessage());
        }
    }

    /**
     * 通过 MediaStore.Downloads 写入公共 Download/music/ 目录（Android 10+）
     * Android 9- 用 File API
     */
    private static Uri writeToPublicDir(Context context, File srcFile, String displayName, String mimeType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_DIR_NAME;
            Uri downloadsUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

            // 查询是否已存在
            Uri existingUri = null;
            String[] projection = {MediaStore.Downloads._ID};
            String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
            String[] selectionArgs = {displayName};
            try (Cursor cursor = resolver.query(downloadsUri, projection, selection, selectionArgs, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(0);
                    existingUri = android.content.ContentUris.withAppendedId(downloadsUri, id);
                }
            } catch (Exception ignored) {}

            Uri targetUri = existingUri;
            if (targetUri == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
                targetUri = resolver.insert(downloadsUri, values);
            }
            if (targetUri == null) return null;

            try (InputStream is = new FileInputStream(srcFile);
                 OutputStream os = resolver.openOutputStream(targetUri, "w")) {
                if (os == null) return null;
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
                os.flush();
            } catch (Exception e) {
                Log.e(TAG, "MediaStore 写入失败: " + e.getMessage());
                if (existingUri == null) {
                    try { resolver.delete(targetUri, null, null); } catch (Exception ignored) {}
                }
                return null;
            }
            return targetUri;
        } else {
            // Android 9-：直接 File API
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dir = new File(downloadsDir, SAVE_DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) return null;
            File destFile = new File(dir, displayName);
            try {
                copyFile(srcFile, destFile);
                return Uri.fromFile(destFile);
            } catch (Exception e) {
                Log.e(TAG, "File API 写入失败: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * 检查歌曲是否已下载（Android 10+ 通过 MediaStore 查询，9- 用 File API）
     */
    public static boolean isSaved(Context context, Song song) {
        String baseName = sanitizeFileName(song.title);
        String displayName = baseName + ".m4a";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            Uri downloadsUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = {MediaStore.Downloads._ID};
            String selection = MediaStore.Downloads.DISPLAY_NAME + " = ? AND " +
                    MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
            String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_DIR_NAME + "/";
            String[] selectionArgs = {displayName, "%" + SAVE_DIR_NAME + "%"};
            try (Cursor cursor = resolver.query(downloadsUri, projection, selection, selectionArgs, null)) {
                return cursor != null && cursor.getCount() > 0;
            } catch (Exception e) {
                Log.w(TAG, "查询已下载失败: " + e.getMessage());
            }
            // 兼容旧版 File 路径
            File saveDir = getLegacySaveDir();
            return saveDir != null && new File(saveDir, displayName).exists();
        } else {
            File saveDir = getLegacySaveDir();
            return saveDir != null && new File(saveDir, displayName).exists();
        }
    }

    /**
     * 获取旧版保存目录（仅 Android 9- 使用）
     */
    private static File getLegacySaveDir() {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloadsDir, SAVE_DIR_NAME);
        if (!dir.exists()) {
            if (!dir.mkdirs()) return null;
        }
        return dir;
    }

    /**
     * 下载文件到本地
     */
    private static boolean downloadFile(String urlStr, Map<String, String> headers,
                                         File destFile, ProgressCallback progressCb) {
        FileOutputStream fos = null;
        InputStream is = null;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                Log.e(TAG, "下载失败 HTTP " + code + " URL: " + urlStr);
                return false;
            }

            int contentLength = conn.getContentLength();
            is = conn.getInputStream();
            fos = new FileOutputStream(destFile);

            byte[] buffer = new byte[BUFFER_SIZE];
            long totalRead = 0;
            int bytesRead;
            int lastPercent = 0;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                if (contentLength > 0 && progressCb != null) {
                    int percent = (int) (totalRead * 100 / contentLength);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progressCb.onProgress(percent);
                    }
                }
            }
            fos.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "下载文件失败: " + e.getMessage());
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 构建LRC歌词内容
     * 优先使用已缓存的KRC歌词转为标准LRC，否则生成纯标题头
     * 从应用专属缓存目录读取歌词（不依赖公共目录 File API）
     */
    private static String buildLrcContent(Context context, Song song) {
        try {
            // 从应用专属目录读取已缓存的歌词
            File lyricsDir = new File(context.getExternalFilesDir(null), "lyrics");
            if (lyricsDir.exists()) {
                String[] names = {song.title, song.artist + " - " + song.title};
                for (String name : names) {
                    for (String ext : new String[]{".krc", ".lrc"}) {
                        File lrcFile = new File(lyricsDir, FileUtil.sanitizeFileName(name) + ext);
                        if (lrcFile.exists()) {
                            if (ext.equals(".krc")) {
                                KrcParser.LyricData data = KrcParser.parseKrcFile(lrcFile);
                                if (data != null) {
                                    return data.toLrcText();
                                }
                            } else {
                                return FileUtil.readFileWithNewlines(lrcFile);
                            }
                        }
                    }
                }
            }

            // 没有找到歌词文件，生成只有标签头的LRC
            StringBuilder sb = new StringBuilder();
            if (song.title != null) sb.append("[ti:").append(song.title).append("]\n");
            if (song.artist != null) sb.append("[ar:").append(song.artist).append("]\n");
            if (song.album != null) sb.append("[al:").append(song.album).append("]\n");
            if (song.duration > 0) sb.append("[length:").append(song.duration / 1000).append("]\n");
            return sb.toString();

        } catch (Exception e) {
            Log.w(TAG, "构建歌词失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 清理文件名中的非法字符
     */
    private static String sanitizeFileName(String name) {
        return FileUtil.sanitizeFileName(name);
    }

    /**
     * 复制文件
     */
    private static void copyFile(File src, File dest) throws Exception {
        try (FileInputStream is = new FileInputStream(src);
             FileOutputStream os = new FileOutputStream(dest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
        }
    }

    /**
     * 触发媒体扫描
     */
    private static void triggerMediaScan(Context context, Uri uri) {
        try {
            // Android 10+ 使用 MediaStore 的 uri 触发扫描
            android.media.MediaScannerConnection.scanFile(context,
                    new String[]{uri.toString()}, null, null);
        } catch (Exception e) {
            Log.w(TAG, "媒体扫描失败: " + e.getMessage());
        }
    }

    private interface ProgressCallback {
        void onProgress(int percent);
    }
}
