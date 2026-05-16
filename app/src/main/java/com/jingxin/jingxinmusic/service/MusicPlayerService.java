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
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;

import com.jingxin.jingxinmusic.MainActivity;
import com.jingxin.jingxinmusic.PlayerActivity;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.util.HistoryManager;

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
    // 播放顺序模式
    public static final String ACTION_PLAY_ORDER_CHANGED = "com.jingxin.jingxinmusic.PLAY_ORDER_CHANGED";
    public static final String ACTION_UPDATE_METADATA = "com.jingxin.jingxinmusic.UPDATE_METADATA";
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

    private NotificationManager notificationManager;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 高德日夜模式防抖
    private long lastAmapThemeTime = 0;

    // ========== PendingIntent 工厂方法 ==========

    private PendingIntent createPreviousAction() {
        Intent intent = new Intent(this, MusicPlayerService.class);
        intent.setAction("ACTION_PREVIOUS");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, 0, intent, flags);
    }

    private PendingIntent createNextAction() {
        Intent intent = new Intent(this, MusicPlayerService.class);
        intent.setAction("ACTION_NEXT");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, 1, intent, flags);
    }

    private PendingIntent createPlayPauseAction() {
        Intent intent = new Intent(this, MusicPlayerService.class);
        intent.setAction("ACTION_PLAY_PAUSE");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, 2, intent, flags);
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
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                updateNotification();
                sendPlayStateBroadcast();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updateNotification();
                sendPlayStateBroadcast();
                updateMediaSessionPlaybackState();
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                updateNotification();
                sendPlayStateBroadcast();
                updateMediaSessionMetadata();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放错误: " + error.getMessage());
                playNext();
            }
        });

        // 监听播放完成自动下一首
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    Log.d(TAG, "歌曲播放结束，自动下一首");
                    playNext();
                }
            }
        });

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

    private void playSong(Song song, int position) {
        if (song == null || song.filePath == null) {
            Log.e(TAG, "歌曲信息无效");
            return;
        }

        this.currentIndex = position;
        Log.d(TAG, "playSong: " + song.title + ", position=" + position + ", playlist.size=" + playlist.size());
        // 优先使用 Content URI（不受 Scoped Storage 限制），fallback 到文件路径
        String playUri = song.contentUri != null ? song.contentUri : song.filePath;
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(playUri));
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();

        // 记录播放历史（后台线程）
        new Thread(() -> {
            File historyDir = new File(getExternalFilesDir(null), "history");
            HistoryManager.addHistory(historyDir, song);
        }, "HistoryLogger").start();

        // 更新 MediaSession 元数据
        updateMediaSessionMetadata();

        Log.d(TAG, "开始播放: " + song.title + " - " + song.artist);
        updateNotification();

        // 通知 Activity 歌曲切换了
        sendSongChangedBroadcast(song, position);
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
        Song currentSong = getCurrentSong();
        if (currentSong == null || mediaSession == null) return;

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentSong.duration);

        String coverName = com.jingxin.jingxinmusic.model.Song.toFileName(currentSong.title, currentSong.artist) + ".jpg";
        File cacheCoverFile = new File(new File(getExternalFilesDir(null), "covers"), coverName);
        if (cacheCoverFile.exists() && cacheCoverFile.length() > 0) {
            // 优先用 MediaStore 公共 URI（其他应用可读）
            Uri publicUri = com.jingxin.jingxinmusic.model.Song.getCoverPublicUri(this, coverName);
            if (publicUri != null) {
                builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, publicUri.toString());
                builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, publicUri.toString());
                builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, publicUri.toString());
            }
            // 同时提供 Bitmap（兼容 getBitmap 读取方式）
            Bitmap coverBitmap = android.graphics.BitmapFactory.decodeFile(cacheCoverFile.getAbsolutePath());
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

    // ========== 通知 ==========

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "音乐播放",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示当前播放歌曲信息");
            notificationManager.createNotificationChannel(channel);
        }
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
        Intent intent = new Intent(ACTION_PLAY_STATE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_IS_PLAYING, isPlaying());
        if (currentSong != null) {
            intent.putExtra(EXTRA_SONG_TITLE, currentSong.title);
            intent.putExtra(EXTRA_SONG_ARTIST, currentSong.artist);
            intent.putExtra(EXTRA_CURRENT_POSITION, getCurrentPosition());
            intent.putExtra(EXTRA_DURATION, getDuration());
        }
        sendBroadcast(intent);
        Log.d(TAG, "播放状态: " + (isPlaying() ? "播放中" : "暂停") + " - " +
                (currentSong != null ? currentSong.title : "无歌曲"));
    }

    private void sendSongChangedBroadcast(Song song, int index) {
        Intent intent = new Intent(ACTION_SONG_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_SONG_ID, song.id);
        intent.putExtra(EXTRA_SONG_TITLE, song.title);
        intent.putExtra(EXTRA_SONG_ARTIST, song.artist);
        intent.putExtra(EXTRA_SONG_ALBUM, song.album);
        intent.putExtra(EXTRA_SONG_PATH, song.filePath);
        intent.putExtra(EXTRA_SONG_URI, song.contentUri);
        intent.putExtra(EXTRA_SONG_ALBUM_ART, song.albumArt);
        intent.putExtra(EXTRA_SONG_INDEX, index);
        intent.putExtra(EXTRA_DURATION, song.duration);
        if (exoPlayer != null) {
            intent.putExtra(EXTRA_AUDIO_SESSION_ID, exoPlayer.getAudioSessionId());
        }
        sendBroadcast(intent);
        Log.d(TAG, "歌曲切换: " + song.title + " - " + song.artist);
    }

    private Song getCurrentSong() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            return playlist.get(currentIndex);
        }
        return null;
    }
}
