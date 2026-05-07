package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import com.jingxin.jingxinmusic.model.Song;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地音乐扫描器
 * 通过 MediaStore 查询手机上的所有音乐文件
 */
public class MusicScanner {

    private static final String TAG = "MusicScanner";

    /**
     * 扫描手机上的所有音乐文件
     */
    public static List<Song> scanMusic(Context context) {
        List<Song> songs = new ArrayList<>();

        // Android 10+ 使用 Audio.Media，10 以下使用 Audio
        Uri collection;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        } else {
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        }

        // Android 10+ 额外查询 RELATIVE_PATH 和 DISPLAY_NAME，用于 DATA 列为 null 时兜底
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
                MediaStore.Audio.Media.DURATION + " > 30000"; // 过滤掉小于30秒的

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

                // 生成 Content URI（ExoPlayer 播放用，不受 Scoped Storage 限制）
                song.contentUri = "content://media/external/audio/media/" + song.id;

                // Android 11+ DATA 列可能返回 null，用 RELATIVE_PATH + DISPLAY_NAME 兜底
                if (song.filePath == null && relPathCol >= 0 && displayNameCol >= 0) {
                    String relPath = cursor.getString(relPathCol);
                    String displayName = cursor.getString(displayNameCol);
                    if (relPath != null && displayName != null) {
                        song.filePath = "/storage/emulated/0/" + relPath + displayName;
                    }
                }

                // 生成显示名
                if (song.artist != null && !song.artist.equals("<unknown>")) {
                    song.displayName = song.artist + " - " + song.title;
                } else {
                    song.displayName = song.title;
                }

                songs.add(song);
            }

            Log.d(TAG, "扫描完成，共 " + songs.size() + " 首歌曲");
        } catch (Exception e) {
            Log.e(TAG, "扫描音乐失败: " + e.getMessage(), e);
        }

        return songs;
    }

    /**
     * 获取专辑封面 URI
     */
    private static String getAlbumArtUri(long albumId) {
        if (albumId <= 0) return null;
        return "content://media/external/audio/albumart/" + albumId;
    }
}
