package com.jingxin.jingxinmusic.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.jingxin.jingxinmusic.MainActivity;
import com.jingxin.jingxinmusic.PlayerActivity;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.util.BiliApi;
import com.jingxin.jingxinmusic.util.BiliConfig;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.util.FileUtil;
import com.jingxin.jingxinmusic.util.HistoryManager;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.util.LyricPublicUtil;
import com.jingxin.jingxinmusic.util.WebDavCacheManager;
import com.jingxin.jingxinmusic.util.WebDavConfig;

import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 音乐播放服务
 * 管理 ExoPlayer 实例、播放队列、后台通知、锁屏控制
 */
public class MusicPlayerService extends Service {

    private static final String TAG = "MusicPlayerService";
    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int NOTIFICATION_ID = 1;

    // 播放状态广播
    public static final String ACTION_PLAY_STATE_CHANGED = "com.jingxin.jingxinmusic.PLAY_STATE_CHANGED";
    // 歌曲切换广播（切歌时单独发）
    public static final String ACTION_SONG_CHANGED = "com.jingxin.jingxinmusic.SONG_CHANGED";
    // 歌词就绪广播（歌词写入公共目录后发，补上LRC/KRC路径）
    public static final String ACTION_LYRIC_AVAILABLE = "com.jingxin.jingxinmusic.LYRIC_AVAILABLE";
    public static final String EXTRA_IS_PLAYING = "is_playing";
    public static final String EXTRA_SONG_TITLE = "song_title";
    public static final String EXTRA_SONG_ARTIST = "song_artist";
    public static final String EXTRA_CURRENT_POSITION = "current_position";
    public static final String EXTRA_DURATION = "duration";
    public static final String EXTRA_SONG_ID = "song_id";
    public static final String EXTRA_SONG_ALBUM = "song_album";
    public static final String EXTRA_SONG_PATH = "song_path";
    public static final String EXTRA_SONG_URI = "song_uri";
    public static final String EXTRA_SONG_ALBUM_ART = "album_art";
    public static final String EXTRA_SONG_INDEX = "song_index";
    public static final String EXTRA_AUDIO_SESSION_ID = "audio_session_id";
    public static final String EXTRA_LRC_FILE_PATH = "lrc_file_path";  // LRC歌词文件路径（供外部应用）
    public static final String EXTRA_KRC_FILE_PATH = "krc_file_path";  // KRC歌词文件路径（供外部应用）
    // 播放顺序模式
    public static final String ACTION_PLAY_ORDER_CHANGED = "com.jingxin.jingxinmusic.PLAY_ORDER_CHANGED";
    public static final String ACTION_UPDATE_METADATA = "com.jingxin.jingxinmusic.UPDATE_METADATA";
    public static final String ACTION_WEBDAV_CONFIG_CHANGED = "com.jingxin.jingxinmusic.WEBDAV_CONFIG_CHANGED";
    public static final String EXTRA_PLAY_ORDER = "play_order";
    public static final int PLAY_ORDER_SEQUENTIAL = 0;  // 顺序播放
    public static final int PLAY_ORDER_SHUFFLE = 1;      // 随机播放
    public static final int PLAY_ORDER_REPEAT_ONE = 2;   // 单曲循环

    // 主题切换广播（由高德日夜模式触发）
    public static final String ACTION_THEME_CHANGED = "com.jingxin.jingxinmusic.THEME_CHANGED";
    public static final String EXTRA_IS_NIGHT = "is_night";

    // 高德导航广播
    private static final String ACTION_AUTONAVI = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final int KEY_TYPE_DAY_NIGHT = 10019;
    private static final int EXTRA_STATE_DAY = 37;
    private static final int EXTRA_STATE_NIGHT = 38;

    private ExoPlayer exoPlayer;
    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder stateBuilder;

    private List<Song> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private int playOrder = PLAY_ORDER_SEQUENTIAL;  // 默认顺序播放
    private final Random random = new Random();
    private boolean deferMediaSessionUpdate = false;  // 延迟MediaSession更新标志（等歌词下载完成后更新）

    private NotificationManager notificationManager;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 高德日夜模式防抖
    private long lastAmapThemeTime = 0;

    // 播放错误防循环：连续失败计数，超过阈值停止自动切歌
    private int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    // MediaSession PlaybackState 定时更新（其他应用依赖此获取实时播放进度）
    private Runnable playbackStateUpdater;
    private static final long PLAYBACK_STATE_UPDATE_INTERVAL = 1000; // 1秒更新一次

    private Player.Listener playerListener;

    // ========== PendingIntent 工厂方法 ==========

    private PendingIntent createActionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MusicPlayerService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private PendingIntent createPreviousAction() {
        return createActionIntent("ACTION_PREVIOUS", 0);
    }

    private PendingIntent createNextAction() {
        return createActionIntent("ACTION_NEXT", 1);
    }

    private PendingIntent createPlayPauseAction() {
        return createActionIntent("ACTION_PLAY_PAUSE", 2);
    }

    // ========== 广播接收器（通知按钮） ==========

    private BroadcastReceiver notificationActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case "ACTION_PLAY_PAUSE":
                    togglePlayPause();
                    break;
                case "ACTION_PREVIOUS":
                    playPrevious();
                    break;
                case "ACTION_NEXT":
                    playNext();
                    break;
            }
        }
    };

    // 高德导航日夜模式广播接收器
    private BroadcastReceiver amapThemeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_AUTONAVI.equals(intent.getAction())) return;
            int keyType = intent.getIntExtra("KEY_TYPE", -1);
            if (keyType != KEY_TYPE_DAY_NIGHT) return;

            int extraState = intent.getIntExtra("EXTRA_STATE", -1);
            if (extraState != EXTRA_STATE_DAY && extraState != EXTRA_STATE_NIGHT) return;

            // 防抖：500ms 内不重复处理
            long now = System.currentTimeMillis();
            if (now - lastAmapThemeTime < 500) return;
            lastAmapThemeTime = now;

            boolean isNight = (extraState == EXTRA_STATE_NIGHT);
            Log.d(TAG, "高德日夜模式: " + (isNight ? "夜间" : "白天"));

            // 写入 SharedPreferences
            getSharedPreferences("theme", MODE_PRIVATE)
                    .edit().putBoolean("isNight", isNight).apply();
            // 标记此次由高德触发，非用户手动
            getSharedPreferences("theme", MODE_PRIVATE)
                    .edit().putBoolean("amapTriggered", true).apply();

            // 发送内部广播通知 PlayerActivity
            Intent themeIntent = new Intent(ACTION_THEME_CHANGED);
            themeIntent.setPackage(getPackageName());
            themeIntent.putExtra(EXTRA_IS_NIGHT, isNight);
            sendBroadcast(themeIntent);
        }
    };

    // ========== 生命周期 ==========

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MusicPlayerService 创建");

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();

        // 初始化 ExoPlayer（强制软件解码，兼容老车机硬件解码器不支持 FLAC 的问题）
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

        exoPlayer = new ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(buildDataSourceFactory()))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        playerListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                // 当播放器准备好时，ExoPlayer已获取到真实duration
                // WebDAV歌曲初始duration=0，需要在这里用ExoPlayer的真实值更新
                if (playbackState == Player.STATE_READY) {
                    Song currentSong = getCurrentSong();
                    if (currentSong != null && exoPlayer != null) {
                        long realDuration = exoPlayer.getDuration();
                        if (realDuration != C.TIME_UNSET && realDuration > 0 && currentSong.duration != realDuration) {
                            Log.d(TAG, "更新歌曲duration: " + currentSong.duration + " -> " + realDuration + " (" + currentSong.title + ")");
                            currentSong.duration = realDuration;
                            // duration变了，必须刷新MediaSession metadata，其他应用才能拿到正确的时长
                            updateMediaSessionMetadata();
                        }
                    }
                }
                updateNotification();
                sendPlayStateBroadcast();
                if (playbackState == Player.STATE_ENDED) {
                    Log.d(TAG, "歌曲播放结束，自动下一首");
                    playNext();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updateNotification();
                sendPlayStateBroadcast();
                updateMediaSessionPlaybackState();
                if (isPlaying) {
                    startPlaybackStateUpdater();
                } else {
                    stopPlaybackStateUpdater();
                }
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                updateNotification();
                sendPlayStateBroadcast();
                // MediaSession metadata 延迟到歌词就绪后更新（由 sendSongChangedBroadcast 触发）
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放错误: " + error.getMessage());
                consecutiveErrors++;
                if (consecutiveErrors <= MAX_CONSECUTIVE_ERRORS) {
                    Log.d(TAG, "播放错误(" + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS + ")，尝试下一首");
                    playNext();
                } else {
                    Log.w(TAG, "连续" + MAX_CONSECUTIVE_ERRORS + "首播放失败，停止自动切歌");
                    // 发送广播通知UI
                    Intent errorIntent = new Intent(ACTION_PLAY_STATE_CHANGED);
                    errorIntent.setPackage(getPackageName());
                    errorIntent.putExtra("play_error", true);
                    errorIntent.putExtra("error_message", "连续播放失败，请检查网络或音乐文件");
                    sendBroadcast(errorIntent);
                }
            }
        };
        exoPlayer.addListener(playerListener);

        // 初始化 MediaSessionCompat（支持锁屏控制）
        mediaSession = new MediaSessionCompat(this, "JingxinMusicSession");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SEEK_TO);
        mediaSession.setPlaybackState(stateBuilder.build());
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (exoPlayer != null) exoPlayer.play();
            }

            @Override
            public void onPause() {
                if (exoPlayer != null) exoPlayer.pause();
            }

            @Override
            public void onSkipToNext() {
                playNext();
            }

            @Override
            public void onSkipToPrevious() {
                playPrevious();
            }

            @Override
            public void onSeekTo(long pos) {
                if (exoPlayer != null) exoPlayer.seekTo(pos);
            }
        });
        mediaSession.setActive(true);

        // 注册通知按钮动作
        IntentFilter filter = new IntentFilter();
        filter.addAction("ACTION_PLAY_PAUSE");
        filter.addAction("ACTION_PREVIOUS");
        filter.addAction("ACTION_NEXT");
        CompatUtil.safeRegisterReceiver(this, notificationActionReceiver, filter);

        // 注册高德导航日夜模式广播（需 RECEIVER_EXPORTED，因为来自外部应用）
        IntentFilter amapFilter = new IntentFilter(ACTION_AUTONAVI);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(amapThemeReceiver, amapFilter, android.content.Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(amapThemeReceiver, amapFilter);
        }

        // 启动为前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 必须使用三参数形式，指定 foregroundServiceType
            startForeground(NOTIFICATION_ID, buildNotification("静心音乐", "准备播放..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("静心音乐", "准备播放..."));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("ACTION_PLAY_PAUSE".equals(action)) {
                togglePlayPause();
            } else if ("ACTION_PREVIOUS".equals(action)) {
                playPrevious();
            } else if ("ACTION_NEXT".equals(action)) {
                playNext();
            } else if (ACTION_UPDATE_METADATA.equals(action)) {
                updateMediaSessionMetadata();
            } else if (ACTION_WEBDAV_CONFIG_CHANGED.equals(action)) {
                rebuildDataSourceFactory();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MusicPlayerBinder();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPlaybackStateUpdater();
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        handler.removeCallbacksAndMessages(null);
        stopForeground(true);
        try {
            unregisterReceiver(notificationActionReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(amapThemeReceiver);
        } catch (Exception ignored) {}
        Log.d(TAG, "MusicPlayerService 销毁");
    }

    // ========== Binder 供 Activity 调用 ==========

    public class MusicPlayerBinder extends Binder {
        public void playSong(Song song, int position) {
            MusicPlayerService.this.playSong(song, position);
        }

        public void togglePlayPause() {
            MusicPlayerService.this.togglePlayPause();
        }

        public void playPrevious() {
            MusicPlayerService.this.playPrevious();
        }

        public void playNext() {
            MusicPlayerService.this.playNext();
        }

        public void seekTo(int positionMs) {
            MusicPlayerService.this.seekTo(positionMs);
        }

        public boolean isPlaying() {
            return MusicPlayerService.this.isPlaying();
        }

        public int getCurrentPosition() {
            return MusicPlayerService.this.getCurrentPosition();
        }

        public int getDuration() {
            return MusicPlayerService.this.getDuration();
        }

        public void setPlaylist(List<Song> songs) {
            MusicPlayerService.this.setPlaylist(songs);
        }

        public void playSongAtPosition(int position) {
            MusicPlayerService.this.playSongAtPosition(position);
        }

        public int getAudioSessionId() {
            if (exoPlayer != null) {
                return exoPlayer.getAudioSessionId();
            }
            return 0;
        }

        public Song getCurrentSong() {
            return MusicPlayerService.this.getCurrentSong();
        }

        public int getCurrentIndex() {
            return MusicPlayerService.this.currentIndex;
        }

        public void setPlayOrder(int order) {
            MusicPlayerService.this.setPlayOrder(order);
        }

        public int getPlayOrder() {
            return MusicPlayerService.this.playOrder;
        }
    }

    // ========== 播放控制 ==========

    // 标记当前DataSource是否带WebDAV认证
    private boolean dataSourceHasAuth = false;

    private void playSong(Song song, int position) {
        if (song == null || song.filePath == null) {
            Log.e(TAG, "歌曲信息无效");
            return;
        }

        this.currentIndex = position;
        Log.d(TAG, "playSong: " + song.title + ", position=" + position + ", playlist.size=" + playlist.size());

        // B站歌曲：异步获取音频流URL后再播放
        // 备用判断：filePath以"bili://"开头也视为B站歌曲（兼容旧版保存的播放列表）
        if (song.sourceType == Song.SOURCE_BILI ||
            (song.filePath != null && song.filePath.startsWith("bili://"))) {
            song.sourceType = Song.SOURCE_BILI; // 确保类型正确
            playBiliSong(song, position);
            return;
        }

        // 优先使用 Content URI（不受 Scoped Storage 限制），fallback 到文件路径
        String playUri = song.contentUri != null ? song.contentUri : song.filePath;
        Log.d(TAG, "playSong: playUri=" + playUri);

        // URL校验：WebDAV URL必须以http开头
        if (playUri != null && playUri.startsWith("http")) {
            // WebDAV歌曲：如果DataSource还没带认证头，立即重建
            if (!dataSourceHasAuth) {
                WebDavConfig cfg = new WebDavConfig(this);
                if (cfg.isConfigured()) {
                    Log.d(TAG, "检测到WebDAV配置，重建DataSource注入认证头");
                    rebuildDataSourceFactory();
                }
            }
            try {
                // 确保URL可以被正确解析
                android.net.Uri parsed = Uri.parse(playUri);
                if (parsed.getScheme() == null || parsed.getHost() == null) {
                    Log.e(TAG, "播放URL格式异常: " + playUri);
                    consecutiveErrors++;
                    if (consecutiveErrors <= MAX_CONSECUTIVE_ERRORS) {
                        playNext();
                    }
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "播放URL解析失败: " + playUri + " - " + e.getMessage());
                consecutiveErrors++;
                if (consecutiveErrors <= MAX_CONSECUTIVE_ERRORS) {
                    playNext();
                }
                return;
            }
        }

        startPlayback(song, playUri, position);
    }

    /**
     * 本地/WebDAV歌曲：开始播放
     */
    private void startPlayback(Song song, String playUri, int position) {
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(playUri));
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();

        // 播放成功，重置连续错误计数
        consecutiveErrors = 0;

        // 记录播放历史（后台线程）
        new Thread(() -> {
            File historyDir = new File(getExternalFilesDir(null), "history");
            HistoryManager.addHistory(historyDir, song);
        }, "HistoryLogger").start();

        // MediaSession metadata 在 sendSongChangedBroadcast 中根据歌词就绪时机更新

        Log.d(TAG, "开始播放: " + song.title + " - " + song.artist);
        updateNotification();

        // 通知 Activity 歌曲切换了
        sendSongChangedBroadcast(song, position);
    }

    /**
     * B站歌曲播放：异步获取音频流URL，然后用exoPlayer + requestHeaders播放
     */
    private void playBiliSong(Song song, int position) {
        // 兼容旧数据：如果bvid为空，从filePath中提取（filePath格式: "bili://BVxxxx"）
        if ((song.bvid == null || song.bvid.isEmpty()) && song.filePath != null && song.filePath.startsWith("bili://")) {
            song.bvid = song.filePath.substring(7);
        }
        Log.d(TAG, "playBiliSong: " + song.title);

        // 检查缓存的URL是否有效
        if (song.audioUrl != null && !song.audioUrl.isEmpty()
                && song.audioUrlExpire > System.currentTimeMillis()) {
            startBiliPlayback(song, position);
            return;
        }

        // 异步获取音频流URL
        new Thread(() -> {
            BiliConfig config = new BiliConfig(this);
            // 优先使用已知的cid（分P场景），否则自动获取第一P
            BiliApi.AudioPlayInfo playInfo;
            if (song.cid > 0) {
                playInfo = BiliApi.getAudioPlayInfo(song.bvid, song.cid, config);
            } else {
                playInfo = BiliApi.getAudioPlayInfo(song.bvid, config);
            }
            if (playInfo == null || playInfo.audioUrl == null || playInfo.audioUrl.isEmpty()) {
                Log.e(TAG, "获取B站音频流失败: " + song.bvid);
                handler.post(() -> {
                    consecutiveErrors++;
                    if (consecutiveErrors <= MAX_CONSECUTIVE_ERRORS) {
                        playNext();
                    }
                });
                return;
            }

            // 更新song中的缓存信息
            song.audioUrl = playInfo.audioUrl;
            song.audioUrlExpire = playInfo.expireTime;
            song.cid = playInfo.cid;

            Log.d(TAG, "B站音频流就绪");
            handler.post(() -> startBiliPlayback(song, position));
        }, "BiliAudioFetcher").start();
    }

    /**
     * B站歌曲：用exoPlayer + 带认证头的MediaSource播放音频流
     */
    private void startBiliPlayback(Song song, int position) {
        try {
            BiliConfig config = new BiliConfig(this);

            // 构建带B站认证头的OkHttpDataSource，创建MediaSource
            OkHttpClient biliClient = new OkHttpClient.Builder().build();
            OkHttpDataSource.Factory biliDataSourceFactory = new OkHttpDataSource.Factory(biliClient)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setDefaultRequestProperties(new HashMap<String, String>() {{
                        put("Referer", "https://www.bilibili.com");
                        put("Cookie", config.getAuthCookie());
                    }});

            DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, biliDataSourceFactory);
            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);

            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(song.audioUrl));
            exoPlayer.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem));
            exoPlayer.prepare();
            exoPlayer.play();

            consecutiveErrors = 0;

            new Thread(() -> {
                File historyDir = new File(getExternalFilesDir(null), "history");
                HistoryManager.addHistory(historyDir, song);
            }, "HistoryLogger").start();

            updateMediaSessionMetadata();
            updateNotification();
            sendSongChangedBroadcast(song, position);

        } catch (Exception e) {
            Log.e(TAG, "B站播放启动失败: " + e.getMessage());
            consecutiveErrors++;
            if (consecutiveErrors <= MAX_CONSECUTIVE_ERRORS) {
                playNext();
            }
        }
    }

    private void playSongAtPosition(int position) {
        if (position >= 0 && position < playlist.size()) {
            this.currentIndex = position;
            playSong(playlist.get(position), position);
        }
    }

    private void togglePlayPause() {
        if (exoPlayer == null) return;
        if (exoPlayer.isPlaying()) {
            exoPlayer.pause();
        } else {
            exoPlayer.play();
        }
        updateNotification();
    }

    private void playNext() {
        if (playlist.isEmpty()) return;
        Log.d(TAG, "playNext: playOrder=" + playOrder + ", currentIndex=" + currentIndex + ", playlist.size=" + playlist.size());

        if (playOrder == PLAY_ORDER_REPEAT_ONE) {
            playSongAtPosition(currentIndex);
        } else if (playOrder == PLAY_ORDER_SHUFFLE) {
            playRandomSong();
        } else {
            currentIndex++;
            if (currentIndex >= playlist.size()) currentIndex = 0;
            playSongAtPosition(currentIndex);
        }
    }

    private void playPrevious() {
        if (playlist.isEmpty()) return;

        if (playOrder == PLAY_ORDER_REPEAT_ONE) {
            playSongAtPosition(currentIndex);
        } else if (playOrder == PLAY_ORDER_SHUFFLE) {
            playRandomSong();
        } else {
            currentIndex--;
            if (currentIndex < 0) currentIndex = playlist.size() - 1;
            playSongAtPosition(currentIndex);
        }
    }

    /**
     * 随机选一首不同于当前的歌曲播放
     */
    private void playRandomSong() {
        if (playlist.size() > 1) {
            int newPos;
            do {
                newPos = random.nextInt(playlist.size());
            } while (newPos == currentIndex);
            currentIndex = newPos;
            playSong(playlist.get(currentIndex), currentIndex);
        } else {
            playSongAtPosition(currentIndex);
        }
    }

    private void seekTo(int positionMs) {
        if (exoPlayer != null) {
            exoPlayer.seekTo(positionMs);
        }
    }

    private boolean isPlaying() {
        return exoPlayer != null && exoPlayer.isPlaying();
    }

    private int getCurrentPosition() {
        if (exoPlayer != null) {
            return (int) exoPlayer.getCurrentPosition();
        }
        return 0;
    }

    private int getDuration() {
        if (exoPlayer != null && exoPlayer.getDuration() != C.TIME_UNSET) {
            return (int) exoPlayer.getDuration();
        }
        return 0;
    }

    private void setPlaylist(List<Song> songs) {
        this.playlist = new ArrayList<>(songs);
        this.currentIndex = -1;
        Log.d(TAG, "setPlaylist: size=" + songs.size());
    }

    private void setPlayOrder(int order) {
        this.playOrder = order;
        // 通知 UI
        Intent intent = new Intent(ACTION_PLAY_ORDER_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_PLAY_ORDER, order);
        sendBroadcast(intent);
        String[] names = {"顺序播放", "随机播放", "单曲循环"};
        Log.d(TAG, "播放顺序: " + names[order]);
    }

    // ========== MediaSession 更新 ==========

    private void updateMediaSessionMetadata() {
        if (deferMediaSessionUpdate) return;  // 延迟更新的歌曲，等歌词就绪后再更新
        doUpdateMediaSessionMetadata();
    }

    private void doUpdateMediaSessionMetadata() {
        Song currentSong = getCurrentSong();
        if (currentSong == null || mediaSession == null) return;

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentSong.duration);

        String coverName = com.jingxin.jingxinmusic.model.Song.toFileName(currentSong.title, currentSong.artist) + ".jpg";
        File cacheCoverFile = new File(com.jingxin.jingxinmusic.util.CoverLoader.getCoverDir(this), coverName);
        if (cacheCoverFile.exists() && cacheCoverFile.length() > 0) {
            // 优先用 MediaStore 公共 URI（其他应用可读）
            Uri publicUri = com.jingxin.jingxinmusic.model.Song.getCoverPublicUri(this, coverName);
            if (publicUri != null) {
                builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, publicUri.toString());
                builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, publicUri.toString());
                builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, publicUri.toString());
            }
            // 同时提供 Bitmap（兼容 getBitmap 读取方式）
            Bitmap coverBitmap = com.jingxin.jingxinmusic.util.BitmapUtil.decodeSampledFromFile(cacheCoverFile.getAbsolutePath(), 200, 200);
            if (coverBitmap != null) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBitmap);
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, coverBitmap);
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, coverBitmap);
            }
        } else if (currentSong.albumArt != null && !currentSong.albumArt.isEmpty()) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, currentSong.albumArt);
            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, currentSong.albumArt);
        }

        mediaSession.setMetadata(builder.build());
    }

    private void updateMediaSessionPlaybackState() {
        if (mediaSession == null || stateBuilder == null) return;
        int state = isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        long pos = exoPlayer != null ? exoPlayer.getCurrentPosition() : 0;
        stateBuilder.setState(state, pos, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    /**
     * 启动定时器，每秒更新 MediaSession PlaybackState
     * 其他应用通过 MediaController.getPlaybackState() 获取实时播放进度
     */
    private void startPlaybackStateUpdater() {
        stopPlaybackStateUpdater();
        playbackStateUpdater = new Runnable() {
            @Override
            public void run() {
                if (isPlaying()) {
                    updateMediaSessionPlaybackState();
                    handler.postDelayed(this, PLAYBACK_STATE_UPDATE_INTERVAL);
                }
            }
        };
        handler.post(playbackStateUpdater);
    }

    private void stopPlaybackStateUpdater() {
        if (playbackStateUpdater != null) {
            handler.removeCallbacks(playbackStateUpdater);
            playbackStateUpdater = null;
        }
    }

    /**
     * 构建 DataSource.Factory：WebDAV URL 自动注入认证头 + 播放缓存
     */
    private DataSource.Factory buildDataSourceFactory() {
        WebDavConfig webDavConfig = new WebDavConfig(this);
        DataSource.Factory httpDataSourceFactory;
        if (webDavConfig.isConfigured()) {
            dataSourceHasAuth = true;
            WebDavCacheManager cacheManager = WebDavCacheManager.getInstance(this);
            httpDataSourceFactory = cacheManager.createCachedHttpDataSourceFactory(
                    new OkHttpClient.Builder().build(), webDavConfig);
        } else {
            dataSourceHasAuth = false;
            httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent("JingXinMusic");
        }
        return new DefaultDataSource.Factory(this, httpDataSourceFactory);
    }

    /**
     * WebDAV配置变更后重建ExoPlayer，使认证头生效
     */
    private void rebuildDataSourceFactory() {
        if (exoPlayer == null) return;
        Log.d(TAG, "重建ExoPlayer（WebDAV配置已变更）");
        // 保存当前状态
        boolean wasPlaying = exoPlayer.isPlaying();
        Song currentSong = getCurrentSong();
        int currentPosition = (int) exoPlayer.getCurrentPosition();
        List<Song> currentPlaylist = new ArrayList<>(playlist);
        int currentIdx = currentIndex;

        // 释放旧播放器
        stopPlaybackStateUpdater();
        exoPlayer.release();

        // 重建播放器
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        exoPlayer = new ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(buildDataSourceFactory()))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        // 重新挂载监听器
        exoPlayer.addListener(playerListener);

        // 恢复播放列表和当前歌曲
        playlist = currentPlaylist;
        currentIndex = currentIdx;
        if (currentSong != null) {
            playSong(currentSong, currentIdx);
            // 恢复到之前的播放位置
            if (currentPosition > 0) {
                exoPlayer.seekTo(currentPosition);
            }
            if (!wasPlaying) {
                exoPlayer.pause();
            }
        }
    }

    // ========== 通知 ==========

    private void createNotificationChannel() {
        com.jingxin.jingxinmusic.util.NotificationHelper.createChannel(
                this, CHANNEL_ID, "音乐播放", "显示当前播放歌曲信息");
    }

    private Notification buildNotification(String title, String text) {
        Song currentSong = getCurrentSong();
        if (currentSong != null) {
            title = currentSong.title;
            String artist = currentSong.artist != null ? currentSong.artist : "";
            text = artist + " - " + title;
        }

        boolean playing = isPlaying();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(android.R.drawable.ic_media_previous, "上一首", createPreviousAction())
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "暂停" : "播放", createPlayPauseAction())
                .addAction(android.R.drawable.ic_media_next, "下一首", createNextAction())
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));

        return builder.build();
    }

    private void updateNotification() {
        if (notificationManager == null) return;
        notificationManager.notify(NOTIFICATION_ID, buildNotification("静心音乐", "准备播放..."));
    }

    private void sendPlayStateBroadcast() {
        Song currentSong = getCurrentSong();
        // 应用内广播（带包名限制，确保稳定送达）
        Intent internalIntent = new Intent(ACTION_PLAY_STATE_CHANGED);
        internalIntent.setPackage(getPackageName());
        internalIntent.putExtra(EXTRA_IS_PLAYING, isPlaying());
        if (currentSong != null) {
            internalIntent.putExtra(EXTRA_SONG_TITLE, currentSong.title);
            internalIntent.putExtra(EXTRA_SONG_ARTIST, currentSong.artist);
            internalIntent.putExtra(EXTRA_CURRENT_POSITION, getCurrentPosition());
            internalIntent.putExtra(EXTRA_DURATION, getDuration());
        }
        sendBroadcast(internalIntent);

        // 外部广播（无包名限制，供其他应用读取播放状态）
        Intent externalIntent = new Intent(ACTION_PLAY_STATE_CHANGED);
        externalIntent.putExtra(EXTRA_IS_PLAYING, isPlaying());
        if (currentSong != null) {
            externalIntent.putExtra(EXTRA_SONG_TITLE, currentSong.title);
            externalIntent.putExtra(EXTRA_SONG_ARTIST, currentSong.artist);
            externalIntent.putExtra(EXTRA_CURRENT_POSITION, getCurrentPosition());
            externalIntent.putExtra(EXTRA_DURATION, getDuration());
        }
        sendBroadcast(externalIntent);

        Log.d(TAG, "播放状态: " + (isPlaying() ? "播放中" : "暂停") + " - " +
                (currentSong != null ? currentSong.title : "无歌曲"));
    }

    private void sendSongChangedBroadcast(Song song, int index) {
        // 先同步本地缓存歌词到公共目录
        String[] lyricPaths = syncLyricToPublicDir(song);

        Intent intent = new Intent(ACTION_SONG_CHANGED);
        intent.putExtra(EXTRA_SONG_ID, song.id);
        intent.putExtra(EXTRA_SONG_TITLE, song.title);
        intent.putExtra(EXTRA_SONG_ARTIST, song.artist);
        intent.putExtra(EXTRA_SONG_ALBUM, song.album);
        intent.putExtra(EXTRA_SONG_PATH, song.filePath);
        intent.putExtra(EXTRA_SONG_URI, song.contentUri);
        intent.putExtra(EXTRA_SONG_ALBUM_ART, song.albumArt);
        intent.putExtra(EXTRA_SONG_INDEX, index);
        intent.putExtra(EXTRA_DURATION, song.duration);
        // B站专属字段
        intent.putExtra(Song.KEY_SOURCE_TYPE, song.sourceType);
        intent.putExtra(Song.KEY_BVID, song.bvid != null ? song.bvid : "");
        intent.putExtra(Song.KEY_CID, song.cid);
        intent.putExtra(Song.KEY_AUDIO_URL, song.audioUrl != null ? song.audioUrl : "");
        intent.putExtra(Song.KEY_AUDIO_URL_EXP, song.audioUrlExpire);
        intent.putExtra(Song.KEY_COVER_URL, song.coverUrl != null ? song.coverUrl : "");
        if (exoPlayer != null) {
            intent.putExtra(EXTRA_AUDIO_SESSION_ID, exoPlayer.getAudioSessionId());
        }
        // 歌词文件路径
        if (lyricPaths[0] != null) intent.putExtra(EXTRA_LRC_FILE_PATH, lyricPaths[0]);
        if (lyricPaths[1] != null) intent.putExtra(EXTRA_KRC_FILE_PATH, lyricPaths[1]);

        // 始终立即发广播（PlayerActivity UI更新不延迟）
        sendBroadcast(intent);

        if (lyricPaths[0] != null || lyricPaths[1] != null) {
            // 本地有缓存，歌词已在公共目录，立即更新 MediaSession
            deferMediaSessionUpdate = false;
            doUpdateMediaSessionMetadata();
        } else {
            // 本地无缓存，设置延迟标志，等歌词下载后再更新 MediaSession
            // 歌词下载由 PlayerActivity.fetchLyrics() 触发，无需在此重复调用
            deferMediaSessionUpdate = true;
            Song delayedSong = song;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                syncLyricToPublicDir(delayedSong);
                deferMediaSessionUpdate = false;
                doUpdateMediaSessionMetadata();
            }, 1000);
        }

        Log.d(TAG, "歌曲切换: " + song.title + " - " + song.artist);
    }

    /**
     * 同步检查本地歌词缓存，有则复制到公共目录
     * @return [lrcPublicPath, krcPublicPath]，没有则为null
     */
    private String[] syncLyricToPublicDir(Song song) {
        String[] result = new String[]{null, null};
        String cleanTitle = Song.cleanSongTitle(song.title, song.artist);
        String safeName = cleanTitle.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        File lyricsDir = new File(getExternalFilesDir(null), "lyrics");

        File lrcPublicFile = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "lyrics/" + safeName + ".lrc");
        File krcPublicFile = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "lyrics/" + safeName + ".krc");

        // 如果公共目录已经有，直接返回路径
        if (lrcPublicFile.exists()) result[0] = lrcPublicFile.getAbsolutePath();
        if (krcPublicFile.exists()) result[1] = krcPublicFile.getAbsolutePath();
        if (result[0] != null && result[1] != null) return result;

        // 检查本地缓存（兼容新旧文件名格式），有则同步复制
        File krcCache = findLyricFile(lyricsDir, safeName, song.artist, ".krc");
        File lrcCache = findLyricFile(lyricsDir, safeName, song.artist, ".lrc");

        if (krcCache.exists() && !krcPublicFile.exists()) {
            LyricPublicUtil.copyToPublicDir(this, krcCache);
            if (krcPublicFile.exists()) result[1] = krcPublicFile.getAbsolutePath();
        }
        if (lrcCache.exists() && !lrcPublicFile.exists()) {
            LyricPublicUtil.copyToPublicDir(this, lrcCache);
            if (lrcPublicFile.exists()) result[0] = lrcPublicFile.getAbsolutePath();
        }

        // 本地有KRC但没有LRC，从KRC生成LRC（用新格式文件名）
        if (krcCache.exists() && !lrcCache.exists() && !lrcPublicFile.exists()) {
            KrcParser.LyricData data = KrcParser.parseKrcFile(krcCache);
            if (data != null && data.lines != null && !data.lines.isEmpty()) {
                String lrcText = data.toLrcText();
                if (lrcText != null && !lrcText.isEmpty()) {
                    File newLrcCache = new File(lyricsDir, safeName + ".lrc");
                    FileUtil.writeFile(newLrcCache, lrcText);
                    LyricPublicUtil.copyToPublicDir(this, newLrcCache);
                    if (lrcPublicFile.exists()) result[0] = lrcPublicFile.getAbsolutePath();
                }
            }
        }

        return result;
    }

    /**
     * 查找本地歌词文件，兼容新旧文件名格式
     * 优先匹配新格式（不含歌手），回退旧格式（含歌手）
     */
    private File findLyricFile(File lyricsDir, String safeName, String artist, String ext) {
        // 1. 精确匹配新格式
        File file = new File(lyricsDir, safeName + ext);
        if (file.exists()) return file;

        // 2. 尝试旧格式
        if (artist != null && !artist.isEmpty() && !"<unknown>".equals(artist)) {
            String safeArtist = artist.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
            File legacy1 = new File(lyricsDir, safeName + " - " + safeArtist + ext);
            if (legacy1.exists()) return legacy1;
            File legacy2 = new File(lyricsDir, safeArtist + " - " + safeName + ext);
            if (legacy2.exists()) return legacy2;
            File legacy3 = new File(lyricsDir, safeArtist + "-" + safeName + ext);
            if (legacy3.exists()) return legacy3;
        }

        // 3. 都没找到，返回新格式路径
        return file;
    }

    private Song getCurrentSong() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            return playlist.get(currentIndex);
        }
        return null;
    }

}
