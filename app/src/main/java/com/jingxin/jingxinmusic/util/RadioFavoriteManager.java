package com.jingxin.jingxinmusic.util;

import android.content.Context;

import com.jingxin.jingxinmusic.model.Song;

import java.io.File;
import java.util.List;

/**
 * 电台收藏管理器
 * 复用 FavoriteManager 的 JSON 存取，但用独立目录，避免污染歌曲收藏
 */
public class RadioFavoriteManager {

    private RadioFavoriteManager() {}

    public static File getRadioFavDir(Context context) {
        return new File(context.getFilesDir(), "radio_favorites");
    }

    public static void add(Context ctx, Song radio) {
        FavoriteManager.addFavorite(getRadioFavDir(ctx), radio);
    }

    public static void remove(Context ctx, Song radio) {
        FavoriteManager.removeFavorite(getRadioFavDir(ctx), radio);
    }

    public static boolean isFavorite(Context ctx, String uuid) {
        List<Song> list = FavoriteManager.loadFavorites(getRadioFavDir(ctx));
        if (list == null) return false;
        String target = "radio://" + uuid;
        for (Song s : list) {
            if (target.equals(s.filePath)) return true;
        }
        return false;
    }

    public static List<Song> loadFavorites(Context ctx) {
        return FavoriteManager.loadFavorites(getRadioFavDir(ctx));
    }
}