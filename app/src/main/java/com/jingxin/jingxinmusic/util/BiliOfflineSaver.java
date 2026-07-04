package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.jingxin.jingxinmusic.model.Song;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * B站音频离线保存工具
 * 将B站DASH音频流下载为m4a格式，同时保存封面jpg和歌词lrc到同一目录
 * 存储位置：Download/music/
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

            // 2. 确保保存目录存在
            File saveDir = getSaveDir(context);
            if (saveDir == null) {
                if (callback != null) callback.onFailed("无法创建保存目录");
                return;
            }

            // 3. 清理文件名中的非法字符
            String baseName = sanitizeFileName(song.title);

            // 4. 检查是否已存在
            File m4aFile = new File(saveDir, baseName + ".m4a");
            if (m4aFile.exists()) {
                if (callback != null) callback.onSkipped("已保存过");
                return;
            }

            // 5. 获取音频流URL
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

            // 6. 下载音频流 → m4a
            if (callback != null) callback.onProgress(10);
            Map<String, String> audioHeaders = new HashMap<>();
            audioHeaders.put("Referer", "https://www.bilibili.com");
            audioHeaders.put("Cookie", config.getAuthCookie());

            File tempM4a = new File(saveDir, baseName + ".m4a.tmp");
            boolean downloadOk = downloadFile(playInfo.audioUrl, audioHeaders, tempM4a,
                    percent -> {
                        if (callback != null) callback.onProgress(10 + percent * 70 / 100);
                    });
            if (!downloadOk) {
                tempM4a.delete();
                if (callback != null) callback.onFailed("下载音频失败");
                return;
            }

            // 重命名为正式文件
            if (!tempM4a.renameTo(m4aFile)) {
                tempM4a.delete();
                if (callback != null) callback.onFailed("重命名文件失败");
                return;
            }
            Log.d(TAG, "音频已保存: " + m4aFile.getAbsolutePath());

            // 7. 读取封面字节（从应用缓存，不另存jpg）
            if (callback != null) callback.onProgress(85);
            byte[] coverJpg = null;
            File coverDir = context.getExternalFilesDir("covers");
            if (coverDir != null && coverDir.isDirectory()) {
                String coverName = com.jingxin.jingxinmusic.model.Song.toFileName(song.title, song.artist) + ".jpg";
                File cachedCover = new File(coverDir, coverName);
                if (cachedCover.exists() && cachedCover.length() > 0) {
                    try {
                        java.io.FileInputStream fis = new java.io.FileInputStream(cachedCover);
                        coverJpg = new byte[(int) cachedCover.length()];
                        fis.read(coverJpg);
                        fis.close();
                        Log.d(TAG, "读取缓存封面: " + coverName);
                    } catch (Exception e) {
                        Log.w(TAG, "读取封面字节失败: " + e.getMessage());
                    }
                }
            }

            // 8. 获取歌词内容（不另存lrc）
            if (callback != null) callback.onProgress(90);
            String lrcContent = buildLrcContent(context, song);

            // 9. 将封面和歌词内嵌到 m4a 文件
            if (coverJpg != null || (lrcContent != null && !lrcContent.isEmpty())) {
                boolean embedded = M4aMetadataWriter.embedMetadata(m4aFile, coverJpg, lrcContent);
                if (embedded) {
                    Log.d(TAG, "封面和歌词已内嵌到m4a");
                } else {
                    // 内嵌失败，保留外部文件作为兜底
                    Log.w(TAG, "内嵌元数据失败，回退保存外部文件");
                    if (coverJpg != null) {
                        try {
                            File jpgFile = new File(saveDir, baseName + ".jpg");
                            FileOutputStream fos = new FileOutputStream(jpgFile);
                            fos.write(coverJpg);
                            fos.close();
                        } catch (Exception ignored) {}
                    }
                    if (lrcContent != null && !lrcContent.isEmpty()) {
                        try {
                            File lrcFile = new File(saveDir, baseName + ".lrc");
                            FileOutputStream fos = new FileOutputStream(lrcFile);
                            fos.write(lrcContent.getBytes("UTF-8"));
                            fos.close();
                        } catch (Exception ignored) {}
                    }
                }
            }

            // 10. 触发媒体扫描
            if (callback != null) callback.onProgress(100);
            triggerMediaScan(context, m4aFile);

            if (callback != null) callback.onSuccess(m4aFile);

        } catch (Exception e) {
            Log.e(TAG, "离线保存失败: " + e.getMessage());
            if (callback != null) callback.onFailed(e.getMessage());
        }
    }

    /**
     * 获取保存目录：Download/music/
     */
    public static File getSaveDir(Context context) {
        File dir;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // API 29+：使用 Downloads/music 公共目录
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            dir = new File(downloadsDir, SAVE_DIR_NAME);
        } else {
            // API 21-28：同一路径
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            dir = new File(downloadsDir, SAVE_DIR_NAME);
        }
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.e(TAG, "创建目录失败: " + dir.getAbsolutePath());
                return null;
            }
        }
        return dir;
    }

    /**
     * 检查歌曲是否已下载
     */
    public static boolean isSaved(Context context, Song song) {
        File saveDir = getSaveDir(context);
        if (saveDir == null) return false;
        String baseName = sanitizeFileName(song.title);
        return new File(saveDir, baseName + ".m4a").exists();
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
     */
    private static String buildLrcContent(Context context, Song song) {
        try {
            // 尝试从歌词缓存目录查找已解析的KRC歌词
            File lyricsDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "lyrics");
            if (lyricsDir.exists()) {
                // 用标题搜索歌词文件
                String[] names = {song.title, song.artist + " - " + song.title};
                for (String name : names) {
                    for (String ext : new String[]{".krc", ".lrc"}) {
                        File lrcFile = new File(lyricsDir, name + ext);
                        if (lrcFile.exists()) {
                            if (ext.equals(".krc")) {
                                KrcParser.LyricData data = KrcParser.parseKrcFile(lrcFile);
                                if (data != null) {
                                    return data.toLrcText();
                                }
                            } else {
                                // 纯LRC文件，直接读取
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
     * 触发媒体扫描，让系统识别新文件
     */
    private static void triggerMediaScan(Context context, File file) {
        try {
            android.media.MediaScannerConnection.scanFile(context,
                    new String[]{file.getAbsolutePath()}, null, null);
        } catch (Exception e) {
            Log.w(TAG, "媒体扫描失败: " + e.getMessage());
        }
    }

    private interface ProgressCallback {
        void onProgress(int percent);
    }
}
