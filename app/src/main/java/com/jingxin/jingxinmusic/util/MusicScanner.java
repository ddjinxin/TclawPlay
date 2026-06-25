package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.jingxin.jingxinmusic.model.Song;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地音乐扫描器
 * 通过 MediaStore 查询 + 文件系统遍历扫描手机上的所有音乐文件
 * MediaStore 覆盖内置存储，文件遍历补充 U 盘/SD 卡等可移动存储
 */
public class MusicScanner {

    private static final String TAG = "MusicScanner";

    /**
     * 扫描手机上的所有音乐文件
     * 1. MediaStore 查询（覆盖内置存储已索引的歌曲）
     * 2. 文件遍历扫描（补充 U 盘/SD 卡等 MediaStore 未索引的歌曲）
     * 3. 合并去重
     */
    public static List<Song> scanMusic(Context context) {
        // 第一步：MediaStore 查询
        List<Song> mediaStoreSongs = scanByMediaStore(context);
        Log.d(TAG, "MediaStore 扫描: " + mediaStoreSongs.size() + " 首");

        // 第二步：文件遍历扫描（探测 U 盘/SD 卡）
        List<Song> fileTraversalSongs = scanByFileTraversal();
        Log.d(TAG, "文件遍历扫描: " + fileTraversalSongs.size() + " 首");

        // 第三步：合并去重（以 filePath 为键，MediaStore 结果优先）
        Set<String> existingPaths = new HashSet<>();
        for (Song song : mediaStoreSongs) {
            if (song.filePath != null) {
                existingPaths.add(song.filePath);
            }
        }

        List<Song> merged = new ArrayList<>(mediaStoreSongs);
        for (Song song : fileTraversalSongs) {
            if (song.filePath != null && !existingPaths.contains(song.filePath)) {
                merged.add(song);
                existingPaths.add(song.filePath);
            }
        }

        Log.d(TAG, "合并去重后: " + merged.size() + " 首");
        return merged;
    }

    /**
     * MediaStore 查询扫描（原有逻辑）
     */
    private static List<Song> scanByMediaStore(Context context) {
        List<Song> songs = new ArrayList<>();

        Uri collection;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        } else {
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        }

        String[] projection;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.DISPLAY_NAME
            };
        } else {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID
            };
        }

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                MediaStore.Audio.Media.DURATION + " > 30000";

        String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, selection, null, sortOrder)) {

            if (cursor == null) {
                Log.e(TAG, "查询 MediaStore 返回 null，可能缺少存储权限");
                return songs;
            }

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            int albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int relPathCol = -1;
            int displayNameCol = -1;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                relPathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH);
                displayNameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
            }

            while (cursor.moveToNext()) {
                Song song = new Song();
                song.id = cursor.getLong(idCol);
                song.title = cursor.getString(titleCol);
                song.artist = cursor.getString(artistCol);
                song.album = cursor.getString(albumCol);
                song.duration = cursor.getLong(durationCol);
                song.filePath = cursor.getString(dataCol);
                song.albumArt = getAlbumArtUri(cursor.getLong(albumIdCol));
                song.contentUri = "content://media/external/audio/media/" + song.id;

                if (song.filePath == null && relPathCol >= 0 && displayNameCol >= 0) {
                    String relPath = cursor.getString(relPathCol);
                    String displayName = cursor.getString(displayNameCol);
                    if (relPath != null && displayName != null) {
                        song.filePath = "/storage/emulated/0/" + relPath + displayName;
                    }
                }

                if (song.artist != null && !song.artist.equals("<unknown>")) {
                    song.displayName = song.artist + " - " + song.title;
                } else {
                    song.displayName = song.title;
                }

                songs.add(song);
            }

            Log.d(TAG, "MediaStore 扫描完成，共 " + songs.size() + " 首歌曲");
        } catch (Exception e) {
            Log.e(TAG, "MediaStore 扫描失败: " + e.getMessage(), e);
        }

        return songs;
    }

    // ========== 文件系统遍历扫描（补充 U 盘/SD 卡） ==========

    /**
     * 探测可移动存储挂载点（U 盘、SD 卡）
     * 遍历常见挂载目录，过滤掉内置存储
     */
    private static List<File> detectRemovableStorage() {
        List<File> storages = new ArrayList<>();

        // 内置存储路径集合，用于排除
        Set<String> internalPaths = new HashSet<>();
        internalPaths.add("/storage/emulated");
        internalPaths.add("/storage/emulated/0");
        internalPaths.add("/storage/self");
        internalPaths.add(Environment.getExternalStorageDirectory().getAbsolutePath());

        // 常见可移动存储挂载探测路径
        String[] probePaths = {
                "/mnt/usb_storage",
                "/mnt/media_rw",
                "/mnt/usb",
                "/mnt/sdcard2",
                "/mnt/ext_sdcard",
                "/mnt/external_sd",
                "/storage"
        };

        for (String probe : probePaths) {
            File dir = new File(probe);
            if (!dir.isDirectory() || !dir.canRead()) continue;

            File[] children = dir.listFiles();
            if (children == null) continue;

            for (File child : children) {
                if (!child.isDirectory() || !child.canRead()) continue;
                // 跳过内置存储路径
                if (internalPaths.contains(child.getAbsolutePath())) continue;
                // 跳过符号链接到内置存储
                if (child.getName().equals("emulated") || child.getName().equals("self")) continue;
                // 跳过 Android 特殊目录
                if (child.getName().equals("sdcard0")) continue;

                storages.add(child);
                Log.d(TAG, "发现可移动存储: " + child.getAbsolutePath());
            }
        }

        if (storages.isEmpty()) {
            Log.d(TAG, "未发现可移动存储");
        }
        return storages;
    }

    /**
     * 文件系统遍历扫描
     * 探测 U 盘/SD 卡挂载点，递归扫描音乐文件
     */
    private static List<Song> scanByFileTraversal() {
        List<Song> songs = new ArrayList<>();
        List<File> storages = detectRemovableStorage();

        for (File storage : storages) {
            traverseDirectory(storage, songs);
        }

        return songs;
    }

    /**
     * 递归遍历目录，收集音乐文件
     */
    private static void traverseDirectory(File dir, List<Song> songs) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                traverseDirectory(file, songs);
            } else if (file.isFile() && WebDavScanner.isMusicFile(file.getName())) {
                Song song = buildSongFromFile(file);
                if (song != null) {
                    songs.add(song);
                }
            }
        }
    }

    /**
     * 从文件构建 Song 对象
     * 不依赖 MediaStore，仅用文件信息构造
     */
    private static Song buildSongFromFile(File file) {
        String fileName = file.getName();
        String nameWithoutExt = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf("."))
                : fileName;

        Song song = new Song();
        song.sourceType = Song.SOURCE_LOCAL;
        song.title = nameWithoutExt;
        song.artist = "<unknown>";
        song.album = file.getParentFile() != null ? file.getParentFile().getName() : "";
        song.duration = 0; // 播放时由 ExoPlayer 更新真实值
        song.filePath = file.getAbsolutePath();
        song.contentUri = null; // 非 MediaStore 歌曲无 content URI，用 filePath 播放
        song.displayName = nameWithoutExt;
        song.id = file.hashCode(); // 唯一标识

        return song;
    }

    /**
     * 获取专辑封面 URI
     */
    private static String getAlbumArtUri(long albumId) {
        if (albumId <= 0) return null;
        return "content://media/external/audio/albumart/" + albumId;
    }
}
