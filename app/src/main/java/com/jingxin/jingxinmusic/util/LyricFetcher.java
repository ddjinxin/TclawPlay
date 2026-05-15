package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

import java.net.URLEncoder;

/**
 * 歌词获取管理器
 *
 * 完整流程：
 * 1. 查本地 KRC → 有就用
 * 2. 查本地 LRC → 有就用
 * 3. 在线获取 KRC（酷狗 API）
 * 4. 酷狗失败 → 备用网易云获取 LRC
 * 5. 都失败 → 写 .tried 标记，下次跳过
 *
 * 保存路径：getExternalFilesDir("lyrics")
 * 文件命名：歌曲名 - 歌手名.krc / .lrc / .tried
 */
public class LyricFetcher {

    private static final String TAG = "LyricFetcher";

    // 酷狗 API
    private static final String KUGOU_SEARCH_API = "https://mobileservice.kugou.com/api/v3/search/song";
    private static final String KUGOU_LYRIC_SEARCH_API = "http://krcs.kugou.com/search";
    private static final String KUGOU_LYRIC_DOWNLOAD_API = "http://lyrics.kugou.com/download";

    // 网易云 API
    private static final String NETEASE_SEARCH_API = "https://music.163.com/api/search/get";
    private static final String NETEASE_LYRIC_API = "https://music.163.com/api/song/lyric";

    public interface LyricCallback {
        void onLyricFetched(KrcParser.LyricData lyricData);
        void onError(String errorMessage);
    }

    public static void loadLyric(String songTitle, String artistName, String filePath, File lyricsDir, LyricCallback callback) {
        loadLyric(songTitle, artistName, filePath, lyricsDir, callback, null);
    }

    /**
     * 加载歌词（主入口）：本地 → 酷狗KRC → 网易云LRC
     * @param context 上下文，用于复制歌词到公共目录（可为null）
     */
    public static void loadLyric(String songTitle, String artistName, String filePath, File lyricsDir, LyricCallback callback, Context context) {
        new Thread(() -> {
            try {
                String safeName = buildFileName(songTitle, artistName);

                // 1. 查本地 KRC
                File krcFile = new File(lyricsDir, safeName + ".krc");
                if (krcFile.exists()) {
                    Log.d(TAG, "找到本地 KRC: " + krcFile.getName());
                    KrcParser.LyricData data = KrcParser.parseKrcFile(krcFile);
                    if (data != null && data.lines != null && !data.lines.isEmpty()) {
                        callback.onLyricFetched(data);
                        return;
                    }
                }

                // 1.5 查音乐文件所在目录的同名 LRC
                if (filePath != null && !filePath.isEmpty()) {
                    File songFile = new File(filePath);
                    if (songFile.exists()) {
                        File songDir = songFile.getParentFile();
                        if (songDir != null && songDir.isDirectory()) {
                            String rawName = songFile.getName();
                            int dotIdx = rawName.lastIndexOf('.');
                            if (dotIdx > 0) rawName = rawName.substring(0, dotIdx);
                            File localLrc = new File(songDir, rawName + ".lrc");
                            if (localLrc.exists()) {
                                Log.d(TAG, "找到歌曲同目录 LRC: " + localLrc.getAbsolutePath());
                                String lrcText = FileUtil.readFileWithNewlines(localLrc);
                                if (lrcText != null) {
                                    KrcParser.LyricData data = LrcParser.parse(lrcText);
                                    if (data != null && data.lines != null && !data.lines.isEmpty()) {
                                        // 复制一份到歌词目录，方便统一管理
                                        File lrcCopy = new File(lyricsDir, safeName + ".lrc");
                                        if (!lrcCopy.exists()) {
                                            FileUtil.writeFile(lrcCopy, lrcText);
                                            Log.d(TAG, "LRC 已复制到歌词目录: " + lrcCopy.getName());
                                            // 额外复制到公共下载目录
                                            if (context != null) LyricPublicUtil.copyToPublicDir(context, lrcCopy);
                                        }
                                        callback.onLyricFetched(data);
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. 查本地 LRC
                File lrcFile = new File(lyricsDir, safeName + ".lrc");
                if (lrcFile.exists()) {
                    Log.d(TAG, "找到本地 LRC: " + lrcFile.getName());
                    String lrcText = FileUtil.readFileWithNewlines(lrcFile);
                    if (lrcText != null) {
                        KrcParser.LyricData data = LrcParser.parse(lrcText);
                        if (data != null && data.lines != null && !data.lines.isEmpty()) {
                            callback.onLyricFetched(data);
                            return;
                        }
                    }
                }

                // 3. 检查 .tried 标记（之前获取失败过，24小时内不再重试）
                File triedFile = new File(lyricsDir, safeName + ".tried");
                if (triedFile.exists()) {
                    long triedTime = 0;
                    String triedContent = FileUtil.readFileWithNewlines(triedFile);
                    if (triedContent != null) {
                        try { triedTime = Long.parseLong(triedContent.trim()); } catch (Exception ignored) {}
                    }
                    if (triedTime > 0 && (System.currentTimeMillis() - triedTime) < 24 * 60 * 60 * 1000L) {
                        Log.d(TAG, ".tried 标记未过期（剩余 " +
                                ((24 * 60 * 60 * 1000L - (System.currentTimeMillis() - triedTime)) / 1000 / 60) +
                                " 分钟），跳过在线获取: " + safeName);
                        callback.onError("已尝试过，24小时内跳过");
                        return;
                    } else {
                        // 过期，删除标记重新尝试
                        triedFile.delete();
                        Log.d(TAG, ".tried 标记已过期，重新尝试获取: " + safeName);
                    }
                }

                // 4. 在线获取 KRC（酷狗）
                Log.d(TAG, "开始在线获取 KRC: " + songTitle + " - " + artistName);
                String base64Content = fetchKugouKrc(songTitle, artistName);
                if (base64Content != null) {
                    // 保存 KRC 到本地
                    KrcParser.saveKrcFromBase64(base64Content, krcFile);
                    Log.d(TAG, "KRC 保存到本地: " + krcFile.getName());
                    // 额外复制到公共下载目录
                    if (context != null) LyricPublicUtil.copyToPublicDir(context, krcFile);

                    KrcParser.LyricData data = KrcParser.parseKrcFromBase64(base64Content);
                    if (data != null && data.lines != null && !data.lines.isEmpty()) {
                        callback.onLyricFetched(data);
                        return;
                    }
                }

                // 5. 酷狗失败，备用网易云获取 LRC
                Log.d(TAG, "酷狗 KRC 获取失败，尝试网易云 LRC: " + songTitle + " - " + artistName);
                String lrcText = fetchNeteaseLrc(songTitle, artistName);
                if (lrcText != null && !lrcText.isEmpty()) {
                    // 保存 LRC 到本地
                    FileUtil.writeFile(lrcFile, lrcText);
                    Log.d(TAG, "LRC 保存到本地: " + lrcFile.getName());
                    // 额外复制到公共下载目录
                    if (context != null) LyricPublicUtil.copyToPublicDir(context, lrcFile);

                    KrcParser.LyricData data = LrcParser.parse(lrcText);
                    if (data != null && data.lines != null && !data.lines.isEmpty()) {
                        callback.onLyricFetched(data);
                        return;
                    }
                }

                // 6. 全部失败，写 .tried 标记（时间戳，24小时后过期）
                FileUtil.writeFile(triedFile, String.valueOf(System.currentTimeMillis()));
                Log.d(TAG, "所有歌词源均失败，写入 .tried 标记: " + safeName);
                callback.onError("所有歌词源均失败");

            } catch (Exception e) {
                Log.e(TAG, "加载歌词异常: " + e.getMessage(), e);
                callback.onError("加载歌词异常: " + e.getMessage());
            }
        }, "LyricLoader").start();
    }

    // ========== 文件名工具 ==========

    private static String buildFileName(String title, String artist) {
        return sanitize(title);
    }

    private static String sanitize(String name) {
        // 去掉文件名不允许的字符
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                  .replaceAll("\\s+", " ")
                  .trim();
    }

    // ========== 文件读写（已迁移到 FileUtil） ==========

    // ========== 酷狗 KRC 获取 ==========

    private static String fetchKugouKrc(String songTitle, String artistName) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                String hash = searchKugouSong(songTitle, artistName);
                if (hash == null || hash.isEmpty()) {
                    Log.e(TAG, "酷狗搜索未找到歌曲（第" + (retry + 1) + "次）");
                    if (retry < 2) Thread.sleep(3000);
                    continue;
                }

                String[] idAndKey = searchKugouLyric(hash);
                if (idAndKey == null) {
                    Log.e(TAG, "酷狗未找到歌词信息（第" + (retry + 1) + "次）");
                    if (retry < 2) Thread.sleep(3000);
                    continue;
                }

                String content = downloadKugouLyric(idAndKey[0], idAndKey[1]);
                if (content != null && !content.isEmpty()) {
                    return content;
                }

                Log.e(TAG, "酷狗下载歌词失败（第" + (retry + 1) + "次）");
                if (retry < 2) Thread.sleep(3000);
            } catch (Exception e) {
                Log.e(TAG, "酷狗 KRC 获取异常（第" + (retry + 1) + "次）: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    private static String searchKugouSong(String songTitle, String artistName) {
        try {
            String keyword = songTitle;
            String apiUrl = KUGOU_SEARCH_API + "?format=json&keyword=" +
                    URLEncoder.encode(keyword, "UTF-8") + "&page=1&pagesize=10";

            String response = HttpUtil.get(apiUrl);
            if (response == null) return null;

            JSONObject json = new JSONObject(response);
            if (json.optInt("status") != 1) return null;
            JSONObject data = json.optJSONObject("data");
            if (data == null) return null;
            JSONArray info = data.optJSONArray("info");
            if (info == null || info.length() == 0) return null;

            // 精确匹配
            for (int i = 0; i < info.length(); i++) {
                JSONObject song = info.getJSONObject(i);
                String name = song.optString("songname", "");
                if (name.equalsIgnoreCase(songTitle) ||
                    name.toLowerCase().contains(songTitle.toLowerCase())) {
                    return song.optString("hash", "");
                }
            }
            return info.getJSONObject(0).optString("hash", "");
        } catch (Exception e) {
            Log.e(TAG, "酷狗歌曲搜索失败: " + e.getMessage());
            return null;
        }
    }

    private static String[] searchKugouLyric(String hash) {
        try {
            String apiUrl = KUGOU_LYRIC_SEARCH_API + "?ver=1&man=yes&client=mobi&hash=" + hash;
            String response = HttpUtil.get(apiUrl);
            if (response == null) return null;

            JSONObject json = new JSONObject(response);
            if (json.optInt("status") != 200) return null;

            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) return null;

            JSONObject first = candidates.getJSONObject(0);
            return new String[]{first.optString("id", ""), first.optString("accesskey", "")};
        } catch (Exception e) {
            Log.e(TAG, "酷狗歌词搜索失败: " + e.getMessage());
            return null;
        }
    }

    private static String downloadKugouLyric(String id, String accesskey) {
        try {
            String apiUrl = KUGOU_LYRIC_DOWNLOAD_API + "?ver=1&client=pc&id=" + id
                    + "&accesskey=" + accesskey + "&fmt=krc&charset=utf8";
            String response = HttpUtil.get(apiUrl);
            if (response == null) return null;

            JSONObject json = new JSONObject(response);
            if (json.optInt("status") != 200) return null;

            String content = json.optString("content", "");
            return content.isEmpty() ? null : content;
        } catch (Exception e) {
            Log.e(TAG, "酷狗歌词下载失败: " + e.getMessage());
            return null;
        }
    }

    // ========== 网易云 LRC 获取 ==========

    private static String fetchNeteaseLrc(String songTitle, String artistName) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                long songId = searchNeteaseSong(songTitle, artistName);
                if (songId <= 0) {
                    Log.e(TAG, "网易云搜索未找到歌曲（第" + (retry + 1) + "次）");
                    if (retry < 2) Thread.sleep(3000);
                    continue;
                }

                String apiUrl = NETEASE_LYRIC_API + "?id=" + songId + "&lv=1";
                String response = HttpUtil.get(apiUrl);
                if (response == null) {
                    Log.e(TAG, "网易云歌词请求失败（第" + (retry + 1) + "次）");
                    if (retry < 2) Thread.sleep(3000);
                    continue;
                }

                JSONObject json = new JSONObject(response);
                if (json.optInt("code") != 200) {
                    Log.e(TAG, "网易云歌词返回错误（第" + (retry + 1) + "次）: " + json.optString("message"));
                    if (retry < 2) Thread.sleep(3000);
                    continue;
                }

                JSONObject lrcObj = json.optJSONObject("lrc");
                if (lrcObj == null) {
                    if (retry < 2) Thread.sleep(3000);
                    continue;
                }

                String lrcText = lrcObj.optString("lyric", "");
                if (!lrcText.isEmpty()) {
                    return lrcText;
                }

                Log.e(TAG, "网易云歌词内容为空（第" + (retry + 1) + "次）");
                if (retry < 2) Thread.sleep(3000);
            } catch (Exception e) {
                Log.e(TAG, "网易云 LRC 获取异常（第" + (retry + 1) + "次）: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    private static long searchNeteaseSong(String songTitle, String artistName) {
        try {
            String keyword = songTitle;
            String apiUrl = NETEASE_SEARCH_API + "?s=" +
                    URLEncoder.encode(keyword, "UTF-8") + "&limit=5&type=1&offset=0";

            String response = HttpUtil.get(apiUrl);
            if (response == null) return 0;

            JSONObject json = new JSONObject(response);
            JSONObject result = json.optJSONObject("result");
            if (result == null) return 0;

            JSONArray songs = result.optJSONArray("songs");
            if (songs == null || songs.length() == 0) return 0;

            // 精确匹配
            for (int i = 0; i < songs.length(); i++) {
                JSONObject song = songs.getJSONObject(i);
                String name = song.optString("name", "");
                JSONArray artists = song.optJSONArray("artists");
                String artistStr = "";
                if (artists != null && artists.length() > 0) {
                    artistStr = artists.getJSONObject(0).optString("name", "");
                }

                if (name.equalsIgnoreCase(songTitle) ||
                    name.toLowerCase().contains(songTitle.toLowerCase())) {
                    return song.optLong("id", 0);
                }
            }
            return songs.getJSONObject(0).optLong("id", 0);
        } catch (Exception e) {
            Log.e(TAG, "网易云歌曲搜索失败: " + e.getMessage());
            return 0;
        }
    }

}
