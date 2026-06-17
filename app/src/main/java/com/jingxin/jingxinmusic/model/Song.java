package com.jingxin.jingxinmusic.model;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;

/**
 * 歌曲数据模型
 */
public class Song {
    private static final String TAG = "Song";
    private static final String COVER_FOLDER = "静心音乐";

    // Intent / SharedPreferences 序列化 key（公开供跨类引用）
    public static final String KEY_ID             = "song_id";
    public static final String KEY_TITLE          = "song_title";
    public static final String KEY_ARTIST         = "song_artist";
    public static final String KEY_ALBUM          = "song_album";
    public static final String KEY_DURATION       = "song_duration";
    public static final String KEY_PATH           = "song_path";
    public static final String KEY_URI            = "song_uri";
    public static final String KEY_ALBUM_ART      = "album_art";
    public static final String KEY_SOURCE_TYPE    = "song_source_type";
    public static final String KEY_BVID           = "song_bvid";
    public static final String KEY_CID            = "song_cid";
    public static final String KEY_AUDIO_URL      = "song_audio_url";
    public static final String KEY_AUDIO_URL_EXP  = "song_audio_url_expire";
    public static final String KEY_COVER_URL      = "song_cover_url";
    public static final String KEY_HAS_LAST       = "has_last";
    public long id;           // MediaStore 中的 ID
    public String title;      // 歌曲名
    public String artist;     // 歌手
    public String album;      // 专辑
    public long duration;    // 时长（毫秒）
    public String filePath;   // 文件路径
    public String contentUri; // MediaStore Content URI（ExoPlayer 用这个播放）
    public String albumArt;   // 专辑封面 URI
    public String displayName;// 显示名（歌手 - 歌曲名 或歌曲名）

    // 音源类型
    public static final int SOURCE_LOCAL = 0;
    public static final int SOURCE_WEBDAV = 1;
    public static final int SOURCE_BILI = 2;
    public int sourceType = SOURCE_LOCAL;

    // B站音源字段
    public String bvid;          // BV号
    public long cid;             // 视频cid
    public String audioUrl;      // 音频流URL（从playurl API获取）
    public long audioUrlExpire;  // 音频流URL过期时间（时间戳，秒）
    public String coverUrl;      // B站封面URL

    @Override
    public String toString() {
        return displayName + " (" + formatDuration(duration) + ")";
    }

    public static String formatDuration(long ms) {
        int totalSeconds = (int) (ms / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    /**
     * 生成统一的缓存文件名（歌名 - 歌手名）
     * 和歌词文件命名规则一致，去掉文件系统不允许的字符
     */
    public static String toFileName(String title, String artist) {
        String name = title;
        if (artist != null && !artist.isEmpty() && !"<unknown>".equals(artist)) {
            name = title + " - " + artist;
        }
        // 去掉文件名不允许的字符: / \ : * ? " < > |
        return name.replaceAll("[/\\\\:*?\"<>|]", "_");
    }

    /**
     * 从B站收藏夹项创建Song对象
     * @param bvid BV号
     * @param title 标题（可能含HTML标签，会自动清理）
     * @param upperName UP主名称
     * @param duration 时长（秒）
     * @param coverUrl 封面URL
     */
    public static Song fromBili(String bvid, String title, String upperName,
                                long duration, String coverUrl) {
        return fromBili(bvid, title, upperName, duration, coverUrl, 0, 0, null);
    }

    /**
     * 从B站视频分P创建Song对象
     * @param bvid BV号
     * @param title 分P标题（可能含HTML标签，会自动清理）
     * @param upperName UP主名称
     * @param duration 时长（秒）
     * @param coverUrl 封面URL
     * @param cid 分P的cid（0表示播放时自动获取）
     * @param page 分P序号（0表示不指定）
     * @param videoTitle 视频总标题（用于单P时显示，多P时可为null）
     */
    public static Song fromBili(String bvid, String title, String upperName,
                                long duration, String coverUrl,
                                long cid, int page, String videoTitle) {
        Song song = new Song();
        song.sourceType = SOURCE_BILI;
        song.bvid = bvid;
        song.cid = cid;
        // 去除标题中的HTML标签
        String cleanTitle = title.replaceAll("<[^>]+>", "").trim();
        song.artist = upperName;
        song.album = "B站";
        song.duration = duration * 1000; // 秒→毫秒
        song.coverUrl = coverUrl;
        song.albumArt = coverUrl;
        song.filePath = "bili://" + bvid; // 占位路径，播放时获取真实URL

        song.title = cleanTitle;
        song.displayName = song.title;
        return song;
    }

    /**
     * 保存封面到公共 Pictures/静心音乐/ 目录（通过 MediaStore API）
     * 其他应用可通过返回的 URI 读取封面
     * @return 公共 content URI，失败返回 null
     */
    public static Uri saveCoverToPublic(android.content.Context ctx, String fileName, Bitmap bitmap) {
        // 先保存到应用缓存目录
        File cacheDir = com.jingxin.jingxinmusic.util.CoverLoader.getCoverDir(ctx);
        File cacheFile = new File(cacheDir, fileName);
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "保存缓存封面失败: " + e.getMessage());
        }

        // 写入公共目录（MediaStore API）
        try {
            ContentResolver resolver = ctx.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + COVER_FOLDER);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Log.e(TAG, "MediaStore insert 失败: " + fileName);
                return null;
            }

            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os);
                    os.flush();
                }
            }

            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);

            Log.d(TAG, "封面已保存到公共目录: " + uri);
            return uri;
        } catch (Exception e) {
            Log.e(TAG, "保存公共封面失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从 Intent 中读取 Song 对象（用于 PlayerActivity 接收、广播接收等）
     * key 定义与 MusicPlayerService.EXTRA_* 常量对应
     */
    public static Song fromIntent(Intent intent) {
        if (intent == null) return null;
        Song song = new Song();
        song.id = intent.getLongExtra(KEY_ID, 0);
        song.title = intent.getStringExtra(KEY_TITLE);
        song.artist = intent.getStringExtra(KEY_ARTIST);
        song.album = intent.getStringExtra(KEY_ALBUM);
        song.duration = intent.getLongExtra(KEY_DURATION, 0);
        song.filePath = intent.getStringExtra(KEY_PATH);
        song.contentUri = intent.getStringExtra(KEY_URI);
        song.albumArt = intent.getStringExtra(KEY_ALBUM_ART);
        song.displayName = song.title;
        song.sourceType = intent.getIntExtra(KEY_SOURCE_TYPE, SOURCE_LOCAL);
        song.bvid = intent.getStringExtra(KEY_BVID);
        song.cid = intent.getLongExtra(KEY_CID, 0);
        song.audioUrl = intent.getStringExtra(KEY_AUDIO_URL);
        song.audioUrlExpire = intent.getLongExtra(KEY_AUDIO_URL_EXP, 0);
        song.coverUrl = intent.getStringExtra(KEY_COVER_URL);
        return song;
    }

    /**
     * 将 Song 对象写入 SharedPreferences（用于保存上次播放状态）
     * 只写 Song 自身字段，不包含播放列表上下文（position/playlist_mode等由调用方处理）
     */
    public void saveToPrefs(SharedPreferences.Editor editor) {
        if (editor == null) return;
        editor.putLong(KEY_ID, id)
                .putString(KEY_TITLE, title != null ? title : "")
                .putString(KEY_ARTIST, artist != null ? artist : "")
                .putString(KEY_ALBUM, album != null ? album : "")
                .putLong(KEY_DURATION, duration)
                .putString(KEY_PATH, filePath != null ? filePath : "")
                .putString(KEY_URI, contentUri != null ? contentUri : "")
                .putString(KEY_ALBUM_ART, albumArt != null ? albumArt : "")
                .putInt(KEY_SOURCE_TYPE, sourceType)
                .putString(KEY_BVID, bvid != null ? bvid : "")
                .putLong(KEY_CID, cid)
                .putString(KEY_AUDIO_URL, audioUrl != null ? audioUrl : "")
                .putLong(KEY_AUDIO_URL_EXP, audioUrlExpire)
                .putString(KEY_COVER_URL, coverUrl != null ? coverUrl : "");
    }

    /**
     * 从 SharedPreferences 读取 Song 对象
     * @return Song 对象，如果没有数据则返回 null
     */
    public static Song fromPrefs(SharedPreferences prefs) {
        if (prefs == null || !prefs.getBoolean(KEY_HAS_LAST, false)) return null;
        String title = prefs.getString(KEY_TITLE, "");
        if (title == null || title.isEmpty()) return null;
        Song song = new Song();
        song.id = prefs.getLong(KEY_ID, 0);
        song.title = title;
        song.artist = prefs.getString(KEY_ARTIST, "");
        song.album = prefs.getString(KEY_ALBUM, "");
        song.duration = prefs.getLong(KEY_DURATION, 0);
        song.filePath = prefs.getString(KEY_PATH, "");
        song.contentUri = prefs.getString(KEY_URI, "");
        song.albumArt = prefs.getString(KEY_ALBUM_ART, "");
        song.displayName = title;
        song.sourceType = prefs.getInt(KEY_SOURCE_TYPE, SOURCE_LOCAL);
        song.bvid = prefs.getString(KEY_BVID, "");
        song.cid = prefs.getLong(KEY_CID, 0);
        song.audioUrl = prefs.getString(KEY_AUDIO_URL, "");
        song.audioUrlExpire = prefs.getLong(KEY_AUDIO_URL_EXP, 0);
        song.coverUrl = prefs.getString(KEY_COVER_URL, "");
        return song;
    }

    /**
     * 将 Song 对象写入 Intent（用于 MainActivity 发起播放、恢复上次播放等）
     */
    public void toIntent(Intent intent) {
        if (intent == null) return;
        intent.putExtra(KEY_ID, id);
        intent.putExtra(KEY_TITLE, title);
        intent.putExtra(KEY_ARTIST, artist);
        intent.putExtra(KEY_ALBUM, album);
        intent.putExtra(KEY_DURATION, duration);
        intent.putExtra(KEY_PATH, filePath != null ? filePath : "");
        intent.putExtra(KEY_URI, contentUri != null ? contentUri : "");
        intent.putExtra(KEY_ALBUM_ART, albumArt != null ? albumArt : "");
        intent.putExtra(KEY_SOURCE_TYPE, sourceType);
        intent.putExtra(KEY_BVID, bvid != null ? bvid : "");
        intent.putExtra(KEY_CID, cid);
        intent.putExtra(KEY_AUDIO_URL, audioUrl != null ? audioUrl : "");
        intent.putExtra(KEY_AUDIO_URL_EXP, audioUrlExpire);
        intent.putExtra(KEY_COVER_URL, coverUrl != null ? coverUrl : "");
    }

    /**
     * 从 JSONObject 中读取 Song 对象（用于 FavoriteManager 加载收藏等）
     * 字段名：id, title, artist, album, duration, filePath, contentUri, albumArt
     */
    public static Song fromJson(JSONObject obj) {
        if (obj == null) return null;
        Song song = new Song();
        song.id = obj.optLong("id", 0);
        song.title = obj.optString("title", "");
        song.artist = obj.optString("artist", "");
        song.album = obj.optString("album", "");
        song.duration = obj.optLong("duration", 0);
        song.filePath = obj.optString("filePath", "");
        song.contentUri = obj.optString("contentUri", "");
        song.albumArt = obj.optString("albumArt", "");
        song.displayName = song.title;
        song.sourceType = obj.optInt("sourceType", SOURCE_LOCAL);
        song.bvid = obj.optString("bvid", "");
        song.cid = obj.optLong("cid", 0);
        song.audioUrl = obj.optString("audioUrl", "");
        song.audioUrlExpire = obj.optLong("audioUrlExpire", 0);
        song.coverUrl = obj.optString("coverUrl", "");
        return song;
    }

    /**
     * 将 Song 对象转为 JSONObject（用于 FavoriteManager 保存收藏等）
     */
    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("title", title);
            obj.put("artist", artist);
            obj.put("album", album);
            obj.put("duration", duration);
            obj.put("filePath", filePath);
            obj.put("contentUri", contentUri);
            obj.put("albumArt", albumArt);
            obj.put("sourceType", sourceType);
            obj.put("bvid", bvid);
            obj.put("cid", cid);
            obj.put("audioUrl", audioUrl);
            obj.put("audioUrlExpire", audioUrlExpire);
            obj.put("coverUrl", coverUrl);
        } catch (Exception e) {
            Log.e(TAG, "Song toJson 失败: " + e.getMessage());
        }
        return obj;
    }

    /**
     * 查询公共目录中已有的封面 URI（如果存在）
     * @return 公共 content URI，不存在返回 null
     */
    public static Uri getCoverPublicUri(android.content.Context ctx, String fileName) {
        try {
            ContentResolver resolver = ctx.getContentResolver();
            String selection = MediaStore.Images.Media.DISPLAY_NAME + " = ? AND " +
                    MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? ";
            String[] selectionArgs = new String[]{fileName,
                    "%" + Environment.DIRECTORY_PICTURES + "/" + COVER_FOLDER + "%"};
            Uri queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            java.io.File[] result = ctx.getExternalFilesDirs(null);
            android.database.Cursor cursor = resolver.query(queryUri,
                    new String[]{MediaStore.Images.Media._ID},
                    selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                cursor.close();
                return Uri.withAppendedPath(queryUri, String.valueOf(id));
            }
            if (cursor != null) cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "查询公共封面失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 清洗歌曲标题（去掉序号前缀、音质标记、歌手名前缀等），用于歌词/封面搜索
     */
    public static String cleanSongTitle(String title, String artist) {
        if (title == null) return "";
        String s = title.trim();
        // 1. 去掉音质标记：[mqms]、[mqms2]、[320k]、[FLAC]、[HQ]、[SQ] 等
        s = s.replaceAll("\\s*\\[(?:mqm[s]?[s2-9]?|[0-9]+k|FLAC|HQ|SQ|CD|Hi-?Res)\\]\\s*", " ");
        // 2. 去掉所有中文/英文括号及内容：(xxx)、（xxx）
        s = s.replaceAll("[（(][^)）]*[)）]", "");
        // 3. 去掉书名号：《xxx》、「xxx」
        s = s.replaceAll("[《「」》]", "");
        // 4. 去掉开头的数字序号：01 歌名、01.歌名、01-歌名、001.歌名（B站分P编号可达3位）
        s = s.replaceAll("^\\d{1,3}[.\\s\\-]+", "");
        // 5. 去掉多余空格并 trim
        s = s.replaceAll("\\s+", " ").trim();
        // 6. 去掉标题中的歌手名前缀：歌手 - 歌名、歌手-歌名、歌手·歌名、歌手_歌名
        s = removeArtistPrefix(s, artist);
        return s;
    }

    /**
     * 清洗歌曲标题（无歌手信息版本，不会去掉歌手名前缀）
     */
    public static String cleanSongTitle(String title) {
        return cleanSongTitle(title, null);
    }

    /**
     * 去掉标题中的歌手名（开头或末尾均可）
     * 开头匹配格式：歌手 - 歌名、歌手-歌名、歌手·歌名、歌手_歌名
     * 末尾匹配格式：歌名 - 歌手、歌名-歌手、歌名·歌手、歌名_歌手
     */
    private static String removeArtistPrefix(String title, String artist) {
        if (artist == null || artist.isEmpty() || "<unknown>".equals(artist)) {
            return title;
        }
        String escaped = java.util.regex.Pattern.quote(artist);

        // 1. 去掉开头的歌手名
        String result = title.replaceFirst("^" + escaped + "\\s*[-·_]\\s*", "");
        if (!result.equals(title)) return result;
        result = title.replaceFirst("^" + escaped + "\\s+", "");
        if (!result.equals(title)) return result;

        // 2. 去掉末尾的歌手名
        result = title.replaceFirst("\\s*[-·_]\\s*" + escaped + "$", "");
        if (!result.equals(title)) return result;
        result = title.replaceFirst("\\s+" + escaped + "$", "");
        if (!result.equals(title)) return result;

        return title;
    }
}
