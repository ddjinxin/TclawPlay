package com.jingxin.jingxinmusic.util;

import android.content.Context;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import com.jingxin.jingxinmusic.model.Song;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 收藏管理器
 * 收藏/取消收藏歌曲，存储在 getExternalFilesDir("favorites")/favorites.json
 */
public class FavoriteManager {

    private static final String TAG = "FavoriteManager";

    /**
     * 获取收藏目录
     */
    public static File getFavoriteDir(Context context) {
        File dir = context.getExternalFilesDir("favorites");
        if (dir != null && !dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 收藏歌曲（去重）
     */
    public static void addFavorite(File favDir, Song song) {
        if (song == null || song.title == null) return;

        List<Song> list = loadFavorites(favDir);

        // 去重
        for (int i = list.size() - 1; i >= 0; i--) {
            Song s = list.get(i);
            if (s.title != null && s.title.equals(song.title) &&
                (s.artist == null || s.artist.equals(song.artist))) {
                list.remove(i);
            }
        }

        list.add(0, song);
        saveFavorites(favDir, list);
        Log.d(TAG, "收藏: " + song.title + " - " + song.artist);
    }

    /**
     * 取消收藏
     */
    public static void removeFavorite(File favDir, Song song) {
        if (song == null || song.title == null) return;

        List<Song> list = loadFavorites(favDir);

        for (int i = list.size() - 1; i >= 0; i--) {
            Song s = list.get(i);
            if (s.title != null && s.title.equals(song.title) &&
                (s.artist == null || s.artist.equals(song.artist))) {
                list.remove(i);
            }
        }

        saveFavorites(favDir, list);
        Log.d(TAG, "取消收藏: " + song.title);
    }

    /**
     * 检查是否已收藏
     */
    public static boolean isFavorite(File favDir, String title, String artist) {
        List<Song> list = loadFavorites(favDir);
        for (Song s : list) {
            if (s.title != null && s.title.equals(title) &&
                (s.artist == null || s.artist.equals(artist))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 加载收藏列表
     */
    public static List<Song> loadFavorites(File favDir) {
        File file = new File(favDir, "favorites.json");
        if (!file.exists()) return new ArrayList<>();

        try {
            String content = FileUtil.readFile(file);
            if (content == null || content.isEmpty()) return new ArrayList<>();

            JSONArray array = new JSONArray(content);
            List<Song> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                Song song = Song.fromJson(array.getJSONObject(i));
                if (song != null && song.title != null && !song.title.isEmpty()) {
                    list.add(song);
                }
            }
            return list;
        } catch (Exception e) {
            Log.e(TAG, "加载收藏失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static void saveFavorites(File favDir, List<Song> list) {
        try {
            favDir.mkdirs();
            JSONArray array = new JSONArray();
            for (Song song : list) {
                array.put(song.toJson());
            }
            FileUtil.writeFile(new File(favDir, "favorites.json"), array.toString());
        } catch (Exception e) {
            Log.e(TAG, "保存收藏失败: " + e.getMessage());
        }
    }

}
