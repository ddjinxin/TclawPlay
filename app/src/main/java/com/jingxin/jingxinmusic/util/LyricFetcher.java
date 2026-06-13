package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

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

    // 酷狗歌词 API
    private static final String KUGOU_LYRIC_SEARCH_API = "http://krcs.kugou.com/search";
    private static final String KUGOU_LYRIC_DOWNLOAD_API = "http://lyrics.kugou.com/download";

    // 网易云歌词 API
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
     * @param callback 歌词回调
     * @param context 上下文，用于复制歌词到公共目录（可为null）
     */
    public static void loadLyric(String songTitle, String artistName, String filePath, File lyricsDir, LyricCallback callback, Context context) {
        new Thread(() -> {
            try {
                String safeName = buildFileName(songTitle, artistName);

                // 1. 查本地 KRC
                File krcFile = findLyricFile(lyricsDir, safeName, artistName, ".krc");
                if (krcFile.exists()) {
                    Log.d(TAG, "找到本地 KRC: " + krcFile.getName());
                    KrcParser.LyricData data = KrcParser.parseKrcFile(krcFile);
                    if (data != null && data.lines != null && !data.lines.isEmpty()) {
                        if (context != null) {
                            LyricPublicUtil.copyToPublicDir(context, krcFile);
                            // 确保 LRC 也复制到公共目录
                            File lrcFile = findLyricFile(lyricsDir, safeName, artistName, ".lrc");
                            if (lrcFile.exists()) {
                                LyricPublicUtil.copyToPublicDir(context, lrcFile);
                            } else {
                                // 本地没有 LRC，从 KRC 解析后生成（用新格式文件名）
                                File newLrcFile = new File(lyricsDir, safeName + ".lrc");
                                String lrcText = data.toLrcText();
                                if (lrcText != null && !lrcText.isEmpty()) {
                                    FileUtil.writeFile(newLrcFile, lrcText);
                                    LyricPublicUtil.copyToPublicDir(context, newLrcFile);
                                    Log.d(TAG, "从 KRC 生成 LRC: " + newLrcFile.getName());
                                }
                            }
                        }
                        notifyLyricAvailable(context, safeName, songTitle, artistName);
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
                                    // 统一CRLF→LF
                                    lrcText = lrcText.replace("\r\n", "\n");
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
                                        notifyLyricAvailable(context, safeName, songTitle, artistName);
                                        callback.onLyricFetched(data);
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. 查本地 LRC
                File lrcFile = findLyricFile(lyricsDir, safeName, artistName, ".lrc");
                if (lrcFile.exists()) {
                    Log.d(TAG, "找到本地 LRC: " + lrcFile.getName());
                    String lrcText = FileUtil.readFileWithNewlines(lrcFile);
                    if (lrcText != null) {
                        KrcParser.LyricData data = LrcParser.parse(lrcText);
                        if (data != null && data.lines != null && !data.lines.isEmpty()) {
                            if (context != null) LyricPublicUtil.copyToPublicDir(context, lrcFile);
                            notifyLyricAvailable(context, safeName, songTitle, artistName);
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
                String[] kugouResult = fetchKugouLyricInfo(songTitle);
                if (kugouResult != null) {
                    String id = kugouResult[0];
                    String accesskey = kugouResult[1];

                    // 4a. 下载KRC歌词
                    String base64Content = downloadKugouLyric(id, accesskey);
                    if (base64Content != null) {
                        // 保存 KRC 到本地
                        KrcParser.saveKrcFromBase64(base64Content, krcFile);
                        Log.d(TAG, "KRC 保存到本地: " + krcFile.getName());
                        // 额外复制KRC到公共下载目录
                        if (context != null) LyricPublicUtil.copyToPublicDir(context, krcFile);

                        // 4b. 同时下载LRC歌词（同一个id+accesskey，fmt=lrc）
                        String lrcTextFromKugou = downloadKugouLrc(id, accesskey);
                        if (lrcTextFromKugou != null && !lrcTextFromKugou.isEmpty()) {
                            // 保存 LRC 到本地，方便步骤2缓存命中
                            File lrcLocalFile = new File(lyricsDir, safeName + ".lrc");
                            if (!lrcLocalFile.exists()) {
                                FileUtil.writeFile(lrcLocalFile, lrcTextFromKugou);
                            }
                            // 额外复制到公共下载目录
                            if (context != null) {
                                LyricPublicUtil.copyToPublicDir(context, lrcLocalFile);
                            }
                            Log.d(TAG, "酷狗LRC已保存: " + safeName + ".lrc");
                        }

                        KrcParser.LyricData data = KrcParser.parseKrcFromBase64(base64Content);
                        if (data != null && data.lines != null && !data.lines.isEmpty()) {
                            notifyLyricAvailable(context, safeName, songTitle, artistName);
                            callback.onLyricFetched(data);
                            return;
                        }
                    }
                }

                // 5. 酷狗失败，备用网易云获取 LRC
                Log.d(TAG, "酷狗 KRC 获取失败，尝试网易云 LRC: " + songTitle + " - " + artistName);
                String lrcText = fetchNeteaseLrc(songTitle, artistName);
                if (lrcText != null && !lrcText.isEmpty()) {
                    // 统一CRLF→LF，保证其他应用兼容
                    lrcText = lrcText.replace("\r\n", "\n");
                    // 保存 LRC 到本地
                    FileUtil.writeFile(lrcFile, lrcText);
                    Log.d(TAG, "LRC 保存到本地: " + lrcFile.getName());
                    // 额外复制到公共下载目录
                    if (context != null) LyricPublicUtil.copyToPublicDir(context, lrcFile);

                    KrcParser.LyricData data = LrcParser.parse(lrcText);
                    if (data != null && data.lines != null && !data.lines.isEmpty()) {
                        notifyLyricAvailable(context, safeName, songTitle, artistName);
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

    /**
     * 查找本地歌词文件，兼容新旧文件名格式
     * 优先匹配新格式（cleanSongTitle 后不含歌手），回退旧格式（含歌手）
     * @param lyricsDir 歌词目录
     * @param safeName 新格式文件名（不含扩展名）
     * @param artist 歌手名（可能为空）
     * @param ext 扩展名（.krc / .lrc）
     * @return 找到的文件，或新格式路径（即使不存在）
     */
    private static File findLyricFile(File lyricsDir, String safeName, String artist, String ext) {
        // 1. 精确匹配新格式
        File file = new File(lyricsDir, safeName + ext);
        if (file.exists()) return file;

        // 2. 尝试旧格式：歌名 - 歌手 / 歌手 - 歌名
        if (artist != null && !artist.isEmpty() && !"<unknown>".equals(artist)) {
            String safeArtist = sanitize(artist);
            // "歌名 - 歌手" 格式
            File legacy1 = new File(lyricsDir, safeName + " - " + safeArtist + ext);
            if (legacy1.exists()) return legacy1;
            // "歌手 - 歌名" 格式
            File legacy2 = new File(lyricsDir, safeArtist + " - " + safeName + ext);
            if (legacy2.exists()) return legacy2;
            // "歌手-歌名" 无空格格式
            File legacy3 = new File(lyricsDir, safeArtist + "-" + safeName + ext);
            if (legacy3.exists()) return legacy3;
        }

        // 3. 都没找到，返回新格式路径（用于后续创建）
        return file;
    }

    // ========== 文件读写（已迁移到 FileUtil） ==========

    // ========== 酷狗歌词获取 ==========

    /**
     * 搜索酷狗歌词信息（id + accesskey），最多3次重试
     * @return [id, accesskey] 或 null
     */
    private static String[] fetchKugouLyricInfo(String songTitle) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                String hash = searchKugouSong(songTitle, "");
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

                return idAndKey;
            } catch (Exception e) {
                Log.e(TAG, "酷狗搜索异常（第" + (retry + 1) + "次）: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    private static String searchKugouSong(String songTitle, String artistName) {
        JSONArray info = MusicApiUtil.searchKugou(songTitle, 10);
        if (info == null) return null;
        JSONObject match = MusicApiUtil.findKugouMatch(info, songTitle);
        return match != null ? match.optString("hash", "") : null;
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
            Log.e(TAG, "酷狗KRC下载失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 下载酷狗LRC格式歌词（用同一个id+accesskey，fmt=lrc）
     * @return LRC纯文本，或null
     */
    private static String downloadKugouLrc(String id, String accesskey) {
        try {
            String apiUrl = KUGOU_LYRIC_DOWNLOAD_API + "?ver=1&client=pc&id=" + id
                    + "&accesskey=" + accesskey + "&fmt=lrc&charset=utf8";
            String response = HttpUtil.get(apiUrl);
            if (response == null) return null;

            JSONObject json = new JSONObject(response);
            if (json.optInt("status") != 200) return null;

            String content = json.optString("content", "");
            if (content.isEmpty()) return null;

            // LRC歌词是base64编码的，需要解码
            byte[] decoded = android.util.Base64.decode(content, android.util.Base64.DEFAULT);
            String lrcText = new String(decoded, "UTF-8");
            // 酷狗LRC使用CRLF换行，替换为LF以保证其他应用兼容
            lrcText = lrcText.replace("\r\n", "\n");
            return lrcText;
        } catch (Exception e) {
            Log.d(TAG, "酷狗LRC下载失败: " + e.getMessage());
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
        JSONArray songs = MusicApiUtil.searchNetease(songTitle);
        if (songs == null) return 0;
        JSONObject match = MusicApiUtil.findNeteaseMatch(songs, songTitle);
        return match != null ? match.optLong("id", 0) : 0;
    }

    /**
     * 歌词写入公共目录后，发送歌词就绪广播
     * 补充切歌广播中无法附带的 LRC/KRC 文件路径
     */
    private static void notifyLyricAvailable(Context context, String safeName, String songTitle, String songArtist) {
        if (context == null) return;

        File lyricsPublicDir = new File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "lyrics");

        String lrcPath = null;
        String krcPath = null;

        File lrcFile = new File(lyricsPublicDir, safeName + ".lrc");
        if (lrcFile.exists()) lrcPath = lrcFile.getAbsolutePath();
        File krcFile = new File(lyricsPublicDir, safeName + ".krc");
        if (krcFile.exists()) krcPath = krcFile.getAbsolutePath();

        if (lrcPath == null && krcPath == null) return;

        android.content.Intent intent = new android.content.Intent(
                com.jingxin.jingxinmusic.service.MusicPlayerService.ACTION_LYRIC_AVAILABLE);
        intent.putExtra(com.jingxin.jingxinmusic.service.MusicPlayerService.EXTRA_SONG_TITLE, songTitle != null ? songTitle : "");
        intent.putExtra(com.jingxin.jingxinmusic.service.MusicPlayerService.EXTRA_SONG_ARTIST, songArtist != null ? songArtist : "");
        if (lrcPath != null) intent.putExtra(com.jingxin.jingxinmusic.service.MusicPlayerService.EXTRA_LRC_FILE_PATH, lrcPath);
        if (krcPath != null) intent.putExtra(com.jingxin.jingxinmusic.service.MusicPlayerService.EXTRA_KRC_FILE_PATH, krcPath);
        context.sendBroadcast(intent);

        Log.d(TAG, "歌词就绪广播: " + safeName + " lrc=" + (lrcPath != null) + " krc=" + (krcPath != null));
    }

}
