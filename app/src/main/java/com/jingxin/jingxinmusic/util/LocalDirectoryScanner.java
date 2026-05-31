package com.jingxin.jingxinmusic.util;

import android.util.Log;

import com.jingxin.jingxinmusic.model.BrowseItem;
import com.jingxin.jingxinmusic.model.Song;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地目录扫描器
 * 将本地歌曲按目录结构组织，支持逐级浏览
 */
public class LocalDirectoryScanner {

    private static final String TAG = "LocalDirScan";

    /**
     * 从歌曲列表构建顶层目录和歌曲的浏览项
     * 只显示一级子目录（按歌曲文件的直接父目录分组）
     * @param allSongs 全部本地歌曲
     * @return 顶层浏览项列表（目录卡片 + 顶层歌曲卡片）
     */
    /**
     * 列出指定目录路径下的直接子项
     * @param allSongs 全部本地歌曲
     * @param dirPath 目录路径，null表示根目录（顶层）
     * @return 该目录下的浏览项列表
     */
    public static List<BrowseItem> buildLevel(List<Song> allSongs, String dirPath) {
        List<BrowseItem> items = new ArrayList<>();

        // 收集该目录下的直接子项
        // 子目录：该目录下一级路径中包含歌曲的子目录
        // 歌曲：直接属于该目录的歌曲

        Map<String, BrowseItem> subDirs = new LinkedHashMap<>(); // 子目录名 -> BrowseItem
        List<BrowseItem> songsInDir = new ArrayList<>();

        for (Song song : allSongs) {
            if (song.filePath == null) continue;

            String songDir = getParentPath(song.filePath);

            if (dirPath == null) {
                // 顶层：看歌曲的直接父目录作为子目录
                String dirName = getLastName(songDir);
                if (!subDirs.containsKey(songDir)) {
                    subDirs.put(songDir, BrowseItem.directory(dirName, songDir, songDir, BrowseItem.SOURCE_LOCAL));
                }
            } else if (songDir.equals(dirPath)) {
                // 歌曲直接属于当前目录
                songsInDir.add(BrowseItem.localSong(song));
            } else if (songDir.startsWith(dirPath + "/")) {
                // 歌曲在当前目录的子目录下
                String relative = songDir.substring(dirPath.length() + 1);
                String firstSegment = relative.contains("/") ?
                        relative.substring(0, relative.indexOf("/")) : relative;
                String subDirPath = dirPath + "/" + firstSegment;

                if (!subDirs.containsKey(subDirPath)) {
                    subDirs.put(subDirPath, BrowseItem.directory(firstSegment, subDirPath, subDirPath, BrowseItem.SOURCE_LOCAL));
                }
            }
        }

        // 先加目录，后加歌曲
        items.addAll(subDirs.values());
        items.addAll(songsInDir);

        Log.d(TAG, "列出目录 " + (dirPath != null ? dirPath : "根") + " : "
                + subDirs.size() + "个子目录, " + songsInDir.size() + "首歌曲");

        return items;
    }

    /**
     * 获取路径的父目录
     */
    private static String getParentPath(String path) {
        if (path == null) return "";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) return path.substring(0, lastSlash);
        return "";
    }

    /**
     * 获取路径最后一段名称
     */
    private static String getLastName(String path) {
        if (path == null) return "";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }
}
