package com.jingxin.jingxinmusic.util;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import com.jingxin.jingxinmusic.model.Song;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 播放历史管理器
 * 记录播放过的歌曲，自动去重，按时间倒序
 * 存储在 getExternalFilesDir("history")/history.json
 */
public class HistoryManager {

    private static final String TAG = "HistoryManager";
    private static final int MAX_HISTORY = 200; // 最多保存200条

    /**
     * 历史记录项
     */
    public static class HistoryItem {
        public String title;
        public String artist;
        public String album;
        public long duration;
        public String filePath;
        public String contentUri;
        public String albumArt;
        public long playedAt; // 播放时间戳

        public HistoryItem() {
            playedAt = System.currentTimeMillis();
        }

        public String getDisplayName() {
            if (artist != null && !artist.isEmpty() && !"<unknown>".equals(artist)) {
                return artist + " - " + title;
            }
            return title;
        }
    }

    /**
     * 添加播放记录（去重：相同歌名+歌手则更新时间）
     */
    public static void addHistory(File historyDir, Song song) {
        if (song == null || song.title == null) return;

        List<HistoryItem> list = loadHistory(historyDir);

        // 去重：找到相同歌名+歌手的记录，移除旧的
        for (int i = list.size() - 1; i >= 0; i--) {
            HistoryItem item = list.get(i);
            if (item.title != null && item.title.equals(song.title) &&
                (item.artist == null || item.artist.equals(song.artist))) {
                list.remove(i);
            }
        }

        // 添加新记录到开头
        HistoryItem item = new HistoryItem();
        item.title = song.title;
        item.artist = song.artist;
        item.album = song.album;
        item.duration = song.duration;
        item.filePath = song.filePath;
        item.contentUri = song.contentUri;
        item.albumArt = song.albumArt;
        item.playedAt = System.currentTimeMillis();
        list.add(0, item);

        // 限制数量
        while (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }

        saveHistory(historyDir, list);
        Log.d(TAG, "记录播放历史: " + item.getDisplayName());
    }

    /**
     * 加载历史记录（按时间倒序）
     */
    public static List<HistoryItem> loadHistory(File historyDir) {
        File file = new File(historyDir, "history.json");
        if (!file.exists()) return new ArrayList<>();

        try {
            String content = FileUtil.readFile(file);
            if (content == null || content.isEmpty()) return new ArrayList<>();

            JSONArray array = new JSONArray(content);
            List<HistoryItem> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                HistoryItem item = new HistoryItem();
                item.title = obj.optString("title", "");
                item.artist = obj.optString("artist", "");
                item.album = obj.optString("album", "");
                item.duration = obj.optLong("duration", 0);
                item.filePath = obj.optString("filePath", "");
                item.contentUri = obj.optString("contentUri", "");
                item.albumArt = obj.optString("albumArt", "");
                item.playedAt = obj.optLong("playedAt", 0);
                if (!item.title.isEmpty()) {
                    list.add(item);
                }
            }

            // 按时间倒序
            Collections.sort(list, (a, b) -> Long.compare(b.playedAt, a.playedAt));
            return list;
        } catch (Exception e) {
            Log.e(TAG, "加载历史失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 清空历史
     */
    public static void clearHistory(File historyDir) {
        File file = new File(historyDir, "history.json");
        if (file.exists()) {
            file.delete();
            Log.d(TAG, "播放历史已清空");
        }
    }

    /**
     * 保存到 JSON 文件
     */
    private static void saveHistory(File historyDir, List<HistoryItem> list) {
        try {
            historyDir.mkdirs();
            JSONArray array = new JSONArray();
            for (HistoryItem item : list) {
                JSONObject obj = new JSONObject();
                obj.put("title", item.title);
                obj.put("artist", item.artist);
                obj.put("album", item.album);
                obj.put("duration", item.duration);
                obj.put("filePath", item.filePath);
                obj.put("contentUri", item.contentUri);
                obj.put("albumArt", item.albumArt);
                obj.put("playedAt", item.playedAt);
                array.put(obj);
            }
            FileUtil.writeFile(new File(historyDir, "history.json"), array.toString());
        } catch (Exception e) {
            Log.e(TAG, "保存历史失败: " + e.getMessage());
        }
    }

}
