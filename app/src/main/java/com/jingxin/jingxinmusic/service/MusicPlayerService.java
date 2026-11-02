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
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.util.AudioEffectManager;
import com.jingxin.jingxinmusic.util.BiliApi;
import com.jingxin.jingxinmusic.util.BiliConfig;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.util.FileUtil;
import com.jingxin.jingxinmusic.util.HistoryManager;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.util.LoudnessScanner;
import com.jingxin.jingxinmusic.util.LyricPublicUtil;
import com.jingxin.jingxinmusic.util.SoftwareEqProcessor;
import com.jingxin.jingxinmusic.util.WebDavCacheManager;
import com.jingxin.jingxinmusic.util.WebDavConfig;

import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    // 音效预设变更广播（设置页切换预设后通知 Service 立即生效，不重启播放）
    public static final String ACTION_EFFECT_PRESET_CHANGED = "com.jingxin.jingxinmusic.EFFECT_PRESET_CHANGED";
    public static final String EXTRA_EFFECT_PRESET = "effect_preset";
    // 音量均衡开关变更广播（设置页切换后通知 Service）
    public static final String ACTION_VOLUME_NORMALIZE_CHANGED = "com.jingxin.jingxinmusic.VOLUME_NORMALIZE_CHANGED";

    // 主题切换广播（由高德日夜模式触发，接收器已上移至 App.java 应用级）
    public static final String ACTION_THEME_CHANGED = "com.jingxin.jingxinmusic.THEME_CHANGED";
    public static final String EXTRA_IS_NIGHT = "is_night";

    private ExoPlayer exoPlayer;
    // ExoPlayer 构造移到子线程后，用该标志标记是否已就绪（音频服务异常设备构造可能失败）
    private volatile boolean exoPlayerReady = false;
    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder stateBuilder;

    // 系统音效（音量均衡增益）+ 软件 EQ（EQ 预设/低音增强/全景环绕）
    private AudioEffectManager audioEffectManager;
    // 软件 EQ 处理器：注入 ExoPlayer 渲染链，绕开车机厂商 EQ 在多声道上的静音缺陷
    private SoftwareEqProcessor softwareEqProcessor;

    // 音量均衡：响度扫描器（无 REPLAYGAIN 标签时 RMS 预扫描，gain_cache.json 缓存）
    private LoudnessScanner loudnessScanner;
    // 音量均衡开关（设置页持久化，播放本地歌曲时读取生效）
    private boolean volumeNormalizeEnabled;
    // 当前歌曲已应用的音量均衡增益（切歌时重置）
    private float currentGainDb = 0f;

    // MediaPlayer 兜底播放器：ExoPlayer 无法解码 ALAC 等格式时，使用系统 MediaPlayer
    private android.media.MediaPlayer fallbackPlayer;
    private boolean isFallbackMode = false;  // true = 当前由 fallbackPlayer 播放

    private List<Song> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private int playOrder = PLAY_ORDER_SEQUENTIAL;  // 默认顺序播放
    private final Random random = new Random();
    private boolean deferMediaSessionUpdate = false;  // 延迟MediaSession更新标志（等歌词下载完成后更新）
    private Runnable mediaSessionFallback;  // 5秒兜底，防止歌词搜索失败时MediaSession永不更新

    private NotificationManager notificationManager;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 播放错误防循环：连续失败计数，超过阈值停止自动切歌
    private int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    // 瞬态错误（IO/解码器初始化类）重试一次，避免偶发失败被误判为格式不支持
    private boolean transientRetryUsed = false;
    private static final long TRACK_RECHECK_DELAY = 500; // onTracksChanged 误判二次确认延时ms
    private static final long TRANSIENT_RETRY_DELAY_MS = 300; // 瞬态错误延时重试（等旧MediaCodec/AudioTrack实例异步释放）
    private static final long FALLBACK_RETRY_DELAY_MS = 500;  // MediaPlayer兜底初始化失败延时重试

    // B站异步获取音频流URL期间设为true，阻止自动切歌避免竞态
    private volatile boolean biliFetching = false;

    // MediaSession PlaybackState 定时更新（其他应用依赖此获取实时播放进度）
    private Runnable playbackStateUpdater;
    private static final long PLAYBACK_STATE_UPDATE_INTERVAL = 1000; // 1秒更新一次

    // 上次 setMetadata 的内容指纹：同一首歌播放中内容不变时不重复 set，
    // 避免每秒强刷触发 onMetadataChanged 打断乐酷桌面等外部应用的封面旋转动画
    private String lastMetadataKey = null;

    // Service 自己的封面加载线程池（PlayerActivity 不在前台时保证封面也能加载）
    private ExecutorService coverExecutor;

    private Player.Listener playerListener;

    // 恢复播放进度：收到 playSong 请求时暂存，等 ExoPlayer prepare 完成后 seekTo
    private long pendingSeekPosition = 0;

    // ========== PendingIntent 工厂方法 ==========

    private PendingIntent createActionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MusicPlayerService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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

    // 歌词就绪广播接收器（LyricFetcher 写入成功后触发，重新更新 MediaSession）
    private BroadcastReceiver lyricAvailableReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_LYRIC_AVAILABLE.equals(intent.getAction())) return;
            if (mediaSessionFallback != null) handler.removeCallbacks(mediaSessionFallback);
            deferMediaSessionUpdate = false;
            doUpdateMediaSessionMetadata();
        }
    };

    // ========== 生命周期 ==========

    @Override
    public void onCreate() {
        super.onCreate();

        // 音效管理器：恢复上次选择的预设（内部全部异步，绝不阻塞主线程）
        audioEffectManager = new AudioEffectManager();
        audioEffectManager.initFromPrefs(this);
        // 软件 EQ 同步上次预设（ExoPlayer 构造时即生效）
        if (softwareEqProcessor != null)
            softwareEqProcessor.setPreset(audioEffectManager.getSavedPreset(this));

        // 音量均衡：恢复开关状态，扫描器缓存存 history 同级目录
        volumeNormalizeEnabled = getSharedPreferences(
                com.jingxin.jingxinmusic.fragment.SettingsFragment.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(com.jingxin.jingxinmusic.fragment.SettingsFragment.KEY_VOLUME_NORMALIZE, false);
        loudnessScanner = new LoudnessScanner(getExternalFilesDir(null));

        // 恢复上次播放模式（顺序/随机/单曲循环）
        this.playOrder = getSharedPreferences("last_played", MODE_PRIVATE)
                .getInt("play_order", PLAY_ORDER_SEQUENTIAL);

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();

        // 初始化 ExoPlayer（优先硬件解码，兜底软件解码）
        // PREFER：硬件解码器优先处理ALAC等格式，软件解码器兜底处理FLAC等
        // 关键：ExoPlayer 构造会调用 AudioManager.generateAudioSessionId()（Binder 到 AudioFlinger），
        // 在音频服务异常设备（如 5.1 模拟器 AudioFlinger 卡死）上会无限阻塞主线程导致 ANR，
        // 因此构造移到子线程执行，完成后置 exoPlayerReady=true（所有使用点均已判空）。
        // 注意：playerListener 必须先于子线程就绪（构造在音频正常设备上可能瞬间完成，回调时机不确定）
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
                            currentSong.duration = realDuration;
                            // duration变了，必须刷新MediaSession metadata，其他应用才能拿到正确的时长
                            updateMediaSessionMetadata();
                        }
                    }
                }
                updateNotification();
                sendPlayStateBroadcast();
                if (playbackState == Player.STATE_ENDED) {
                    // B站异步获取URL期间不自动切歌，避免旧播放器结束后
                    // 还在等待fetch的竞态导致播放与显示不一致
                    if (biliFetching) {
                        return;
                    }
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
            public void onAudioSessionIdChanged(int sessionId) {
                // ExoPlayer 每次重建/首次播放都会分配 session；EQ/BB/VT 已改软件 DSP，
                // 此处仅挂载音量均衡（LoudnessEnhancer 为 AOSP 实现，车机正常）
                if (audioEffectManager != null) {
                    audioEffectManager.setFallbackMode(false);
                    audioEffectManager.attachLoudnessOnlyAsync(sessionId);
                }
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                // 关键修复：ExoPlayer 遇到不支持的音频编码（如ALAC），会静默跳过音频轨道，
                // 不会触发 onPlayerError，但 onTracksChanged 可以检测到没有选中任何音频轨道
                if (isFallbackMode) return;
                // isTypeSelected 检查指定类型的轨道是否被选中
                if (!tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
                        && exoPlayer.getPlaybackState() == Player.STATE_READY) {
                    // 轨道选择是异步的：刚 prepare/切歌时可能先收到空回调再选中轨道，
                    // 延迟二次确认仍未选中才判格式不支持，避免竞态误判
                    handler.postDelayed(() -> {
                        if (isFallbackMode || exoPlayer == null) return;
                        Tracks cur = exoPlayer.getCurrentTracks();
                        if (cur.isTypeSelected(C.TRACK_TYPE_AUDIO)) return; // 后续已选中，误判
                        if (exoPlayer.getPlaybackState() != Player.STATE_READY) return; // 状态已变，交给状态回调
                        Log.w(TAG, "ExoPlayer静默跳过音频轨道（可能不支持该编码），尝试MediaPlayer兜底");
                        Song currentSong = getCurrentSong();
                        if (currentSong != null) {
                            String playUri = getLocalPlayUri(currentSong);
                            if (playUri != null) {
                                startFallbackPlayback(currentSong, playUri, currentIndex);
                                return;
                            }
                        }
                        // 兜底也走不通，跳到下一首
                        Log.w(TAG, "无法播放此格式，自动切歌");
                        showToast("当前设备不支持播放此音频格式");
                        playNext();
                    }, TRACK_RECHECK_DELAY);
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
                Log.e(TAG, "播放错误: code=" + error.errorCode + " - " + error.getMessage());
                int code = error.errorCode;
                // 瞬态错误（IO类/解码器初始化失败）：重试一次再兜底
                // 车机常见场景：解码器被其他应用占用、上一次异步释放未完成、网络抖动
                if (isTransientError(code) && !transientRetryUsed) {
                    transientRetryUsed = true;
                    // 延时重试：车机快速切歌时旧MediaCodec/AudioTrack实例异步释放未完，
                    // 立即重试会再次初始化失败（曾致误报"格式不支持"）；延时期间切歌则放弃
                    final Song errSong = getCurrentSong();
                    final int errPos = currentIndex;
                    Log.w(TAG, "瞬态播放错误，延时" + TRANSIENT_RETRY_DELAY_MS + "ms重试");
                    handler.postDelayed(() -> {
                        Song cur = getCurrentSong();
                        if (currentIndex != errPos || cur == null || errSong == null
                                || (cur.filePath == null ? errSong.filePath != null
                                : !cur.filePath.equals(errSong.filePath))) {
                            return; // 已切歌，新播放自行接管
                        }
                        retryCurrentPlayback();
                    }, TRANSIENT_RETRY_DELAY_MS);
                    return;
                }
                transientRetryUsed = false;
                // ExoPlayer 播放失败：尝试用 MediaPlayer 兜底（可能是 ALAC 等不支持的格式）
                Song currentSong = getCurrentSong();
                if (currentSong != null && !isFallbackMode) {
                    String playUri = getLocalPlayUri(currentSong);
                    if (playUri != null) {
                        startFallbackPlayback(currentSong, playUri, currentIndex);
                        return;
                    }
                }
                // 网络类错误给出真实原因，不再统一报"格式不支持"
                if (isIoError(code)) {
                    showToast("网络异常，暂时无法播放");
                }
                handlePlaybackError();
            }
        };

        // ExoPlayer 构造移到子线程（构造内 generateAudioSessionId → Binder 到 AudioFlinger，
        // 音频服务异常设备上会阻塞主线程导致 ANR）；完成后在主线程挂载 listener 并置 exoPlayerReady。
        // 软件 EQ 处理器注入渲染链：EQ/低音/环绕预设由本应用 DSP 处理，
        // 彻底绕开车机厂商 EQ（NXP）在多声道输出上的静音缺陷。
        softwareEqProcessor = new SoftwareEqProcessor();
        // 软件 EQ 处理器注入渲染链：EQ/低音/环绕预设由本应用 DSP 处理，
        // 彻底绕开车机厂商 EQ（NXP）在多声道输出上的静音缺陷。
        final DefaultRenderersFactory renderersFactory =
                new EqRenderersFactory(this, softwareEqProcessor);
        new Thread(() -> {
            try {
                final ExoPlayer player = new ExoPlayer.Builder(this,
                        renderersFactory.setExtensionRendererMode(
                                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
                        .setMediaSourceFactory(new DefaultMediaSourceFactory(buildDataSourceFactory()))
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .setUsage(C.USAGE_MEDIA)
                                .build(), true)
                        .setHandleAudioBecomingNoisy(true)
                        .build();
                handler.post(() -> {
                    if (exoPlayer != null) return; // 已重建过，放弃本次
                    exoPlayer = player;
                    exoPlayerReady = true;
                    exoPlayer.addListener(playerListener);
                    // 关键修复（安卓8.1音效不生效）：media3 的 onAudioSessionIdChanged 回调
                    // 只在显式调用 setAudioSessionId() 时触发，构造自动分配的 session 不会回调。
                    // setAudioSessionId(0) 会让 ExoPlayer 重新生成 session 并下发给 AudioSink，
                    // 从而触发 onAudioSessionIdChanged → attachAsync 挂载音效。
                    // （verifyApplicationThread 要求主线程，此处正是主线程 handler）
                    try {
                        player.setAudioSessionId(0);
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable t) {
                Log.e(TAG, "ExoPlayer 构造失败: " + t.getMessage());
                handler.post(() -> exoPlayerReady = false);
            }
        }, "ExoInit").start();

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
                resumePlayback();
            }

            @Override
            public void onPause() {
                pausePlayback();
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

        // 预填充上一次播放的歌曲信息，避免其他应用（如乐酷桌面）首次进入时读到空 metadata
        Song lastSong = Song.fromPrefs(getSharedPreferences("last_played", MODE_PRIVATE));
        if (lastSong != null) {
            MediaMetadataCompat.Builder initBuilder = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, lastSong.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, lastSong.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lastSong.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, lastSong.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, lastSong.duration);
            mediaSession.setMetadata(initBuilder.build());
        }

        // 注册通知按钮动作
        IntentFilter filter = new IntentFilter();
        filter.addAction("ACTION_PLAY_PAUSE");
        filter.addAction("ACTION_PREVIOUS");
        filter.addAction("ACTION_NEXT");
        CompatUtil.safeRegisterReceiver(this, notificationActionReceiver, filter);

        // 注册歌词就绪广播（内部，LyricFetcher 歌词写入完成后触发）
        IntentFilter lyricFilter = new IntentFilter(ACTION_LYRIC_AVAILABLE);
        CompatUtil.safeRegisterReceiver(this, lyricAvailableReceiver, lyricFilter);

        // 启动为前台服务
        CompatUtil.safeStartForeground(this, NOTIFICATION_ID, buildNotification("静心音乐", "准备播放..."));

        // 初始化封面加载线程池
        coverExecutor = Executors.newSingleThreadExecutor();
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
            } else if (ACTION_VOLUME_NORMALIZE_CHANGED.equals(action)) {
                // 设置页开关音量均衡：同步开关并立即对当前歌曲生效/失效
                volumeNormalizeEnabled = getSharedPreferences(
                        com.jingxin.jingxinmusic.fragment.SettingsFragment.PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(com.jingxin.jingxinmusic.fragment.SettingsFragment.KEY_VOLUME_NORMALIZE, false);
                if (volumeNormalizeEnabled) {
                    Song cur = getCurrentSong();
                    if (cur != null) applyVolumeNormalize(cur);
                } else {
                    currentGainDb = 0f;
                    if (audioEffectManager != null) audioEffectManager.setLoudnessGainAsync(0f);
                    // 负增益（setVolume）也要回正
                    applyPlayerVolume(1f);
                }
            } else if (ACTION_EFFECT_PRESET_CHANGED.equals(action)) {
                // 切换音效预设：EQ/低音/环绕走软件 DSP（ExoPlayer 链内），仅音量均衡用系统效果器
                int preset = intent.getIntExtra(EXTRA_EFFECT_PRESET, AudioEffectManager.PRESET_OFF);
                if (audioEffectManager != null) {
                    audioEffectManager.setPresetAsync(preset);
                }
                if (softwareEqProcessor != null) {
                    softwareEqProcessor.setPreset(preset);
                }
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
        savePlaybackPosition();
        stopPlaybackStateUpdater();
        releaseFallbackPlayer();
        if (audioEffectManager != null) {
            audioEffectManager.releaseAsync();
            audioEffectManager = null;
        }
        if (loudnessScanner != null) {
            loudnessScanner.shutdown();
            loudnessScanner = null;
        }
        if (exoPlayer != null) {
            final ExoPlayer p = exoPlayer;
            exoPlayer = null;
            exoPlayerReady = false;
            // release 可能阻塞 Binder（音频服务异常设备），移到子线程
            new Thread(() -> {
                try { p.release(); } catch (Exception ignored) {}
            }, "ExoRelease").start();
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        handler.removeCallbacksAndMessages(null);
        if (coverExecutor != null) {
            coverExecutor.shutdownNow();
            coverExecutor = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        try {
            unregisterReceiver(notificationActionReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(lyricAvailableReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        savePlaybackPosition();
        super.onTaskRemoved(rootIntent);
    }

    /**
     * 保存当前播放进度到 SharedPreferences，下次启动时可恢复
     */
    private void savePlaybackPosition() {
        try {
            if (currentIndex >= 0 && playlist != null && !playlist.isEmpty()
                    && currentIndex < playlist.size()) {
                long pos = getCurrentPosition();
                getSharedPreferences("last_played", MODE_PRIVATE)
                        .edit()
                        .putLong("play_position", pos)
                        .apply();
            }
        } catch (Exception e) {
            Log.w(TAG, "保存播放进度失败: " + e.getMessage());
        }
    }

    // ========== Binder 供 Activity 调用 ==========

    public class MusicPlayerBinder extends Binder {
        public void playSong(Song song, int position) {
            MusicPlayerService.this.playSong(song, position);
        }

        /**
         * 设置恢复播放进度（毫秒），在下一次 playSong 的 prepare 完成后 seekTo
         */
        public void setPendingSeekPosition(long positionMs) {
            MusicPlayerService.this.pendingSeekPosition = positionMs;
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

        public List<Song> getPlaylist() {
            return MusicPlayerService.this.playlist;
        }

        public void playSongAtPosition(int position) {
            MusicPlayerService.this.playSongAtPosition(position);
        }

        public int getAudioSessionId() {
            if (isFallbackMode && fallbackPlayer != null) {
                return fallbackPlayer.getAudioSessionId();
            }
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

        public boolean isFallbackMode() {
            return MusicPlayerService.this.isFallbackMode;
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

        // 边界保护：position 可能来自 allSongs 索引，超出 playlist 范围
        if (position < 0 || position >= playlist.size()) {
            // 在播放列表中查找该歌曲的实际位置
            int realPos = -1;
            for (int i = 0; i < playlist.size(); i++) {
                Song s = playlist.get(i);
                if ((s.filePath != null && s.filePath.equals(song.filePath)) ||
                    (s.contentUri != null && s.contentUri.equals(song.contentUri))) {
                    realPos = i;
                    break;
                }
            }
            if (realPos >= 0) {
                position = realPos;
            } else {
                position = 0;
            }
        }
        this.currentIndex = position;

        // 音量均衡：切歌入口统一应用/归零增益（内部自带网络流归零逻辑）
        applyVolumeNormalize(song);

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

        // URL校验：WebDAV URL必须以http开头
        if (playUri != null && playUri.startsWith("http")) {
            // WebDAV歌曲：如果DataSource还没带认证头，立即重建
            if (!dataSourceHasAuth) {
                WebDavConfig cfg = new WebDavConfig(this);
                if (cfg.isConfigured()) {
                    rebuildDataSourceFactory();
                }
            }
            try {
                // 确保URL可以被正确解析
                android.net.Uri parsed = Uri.parse(playUri);
                if (parsed.getScheme() == null || parsed.getHost() == null) {
                    Log.e(TAG, "播放URL格式异常: " + playUri);
                    handlePlaybackError();
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "播放URL解析失败: " + playUri + " - " + e.getMessage());
                handlePlaybackError();
                return;
            }
        }

        startPlayback(song, playUri, position);
    }

    // ========== 音量均衡（方案 C） ==========

    /**
     * 对当前歌曲应用音量均衡（切歌时调用）：
     * - 开关关闭 → 全部归零（增益效果器禁用 + setVolume 恢复 1.0）
     * - 开关开启且为本地歌曲 → 查标签/扫描得增益：
     *   正增益走 LoudnessEnhancer（setVolume 上限 1.0 升不了音量），
     *   负增益直接 setVolume 缩小（比效果器少一层处理，音质无损）
     * - 网络流（B站/WebDAV）不做（扫描成本高且流地址会变）
     */
    private void applyVolumeNormalize(Song song) {
        currentGainDb = 0f;
        if (!volumeNormalizeEnabled) {
            if (audioEffectManager != null) audioEffectManager.setLoudnessGainAsync(0f);
            applyPlayerVolume(1f);
            return;
        }
        // 仅本地文件（content URI 无法直接读标签/扫描）
        String path = song != null ? song.filePath : null;
        if (path == null || path.startsWith("http") || path.startsWith("content://")
                || path.startsWith("bili://")) {
            if (audioEffectManager != null) audioEffectManager.setLoudnessGainAsync(0f);
            applyPlayerVolume(1f);
            return;
        }
        if (loudnessScanner == null) return;
        final String songId = path;
        loudnessScanner.getGainAsync(path, gainDb ->
                // 回调来自扫描线程，ExoPlayer 只能主线程访问，切回主线程再应用
                handler.post(() -> {
            // 回调时可能已切歌，只对仍在本歌曲的应用增益
            Song cur = getCurrentSong();
            if (cur == null || cur.filePath == null || !cur.filePath.equals(songId)) return;
            currentGainDb = gainDb;
            if (gainDb > 0f) {
                // 正增益：LoudnessEnhancer + setVolume 保持 1.0
                applyPlayerVolume(1f);
                if (audioEffectManager != null) audioEffectManager.setLoudnessGainAsync(gainDb);
            } else if (gainDb < 0f) {
                // 负增益：setVolume 线性缩小（10^gain/20），效果器增益归零
                if (audioEffectManager != null) audioEffectManager.setLoudnessGainAsync(0f);
                applyPlayerVolume((float) Math.pow(10, gainDb / 20f));
            } else {
                if (audioEffectManager != null) audioEffectManager.setLoudnessGainAsync(0f);
                applyPlayerVolume(1f);
            }
        }));
    }

    /**
     * 统一设置双播放器音量（ExoPlayer setVolume / MediaPlayer setVolume）。
     * 切歌/开关关闭时调用方负责恢复 1.0。
     */
    private void applyPlayerVolume(float volume) {
        try {
            if (exoPlayer != null && exoPlayerReady) exoPlayer.setVolume(volume);
        } catch (Exception ignored) {}
        try {
            if (fallbackPlayer != null) fallbackPlayer.setVolume(volume, volume);
        } catch (Exception ignored) {}
    }

    /**
     * 本地/WebDAV歌曲：开始播放
     */
    private void startPlayback(Song song, String playUri, int position) {
        // 检测 ALAC 等需要 MediaPlayer 兜底的格式
        if (needsFallback(song.filePath, playUri)) {
            startFallbackPlayback(song, playUri, position);
            return;
        }

        // ExoPlayer 尚未就绪（构造在子线程，音频服务异常设备上可能很慢或失败）→ MediaPlayer 兜底
        if (exoPlayer == null || !exoPlayerReady) {
            startFallbackPlayback(song, playUri, position);
            return;
        }

        // 非 ALAC：使用 ExoPlayer 正常播放
        releaseFallbackPlayer();
        isFallbackMode = false;
        if (audioEffectManager != null) audioEffectManager.setFallbackMode(false);

        // 先停掉当前播放，强制释放旧的 MediaCodec 解码器
        // 避免从 FLAC 切到 MP4/ALAC 时，ExoPlayer 复用旧 FLAC 解码器导致无声
        exoPlayer.stop();

        String mimeType = inferMimeType(song.filePath, playUri);
        MediaItem mediaItem;
        if (mimeType != null) {
            mediaItem = new MediaItem.Builder()
                    .setUri(Uri.parse(playUri))
                    .setMimeType(mimeType)
                    .build();
        } else {
            mediaItem = MediaItem.fromUri(Uri.parse(playUri));
        }
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();

        // 恢复播放进度
        if (pendingSeekPosition > 0) {
            long seekPos = pendingSeekPosition;
            pendingSeekPosition = 0;
            exoPlayer.seekTo(seekPos);
        }

        exoPlayer.play();

            // 双保险：直接读取 ExoPlayer 的 session 挂载音量均衡（EQ/BB/VT 已由软件 EQ 处理）
            try {
                if (audioEffectManager != null) {
                    audioEffectManager.setFallbackMode(false);
                    audioEffectManager.attachLoudnessOnlyAsync(exoPlayer.getAudioSessionId());
                }
            } catch (Throwable ignored) {}

        // 播放成功，重置连续错误计数和瞬态重试标记
        consecutiveErrors = 0;
        transientRetryUsed = false;

        logPlayHistory(song);

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

        // 检查缓存的URL是否有效
        if (song.audioUrl != null && !song.audioUrl.isEmpty()
                && song.audioUrlExpire > System.currentTimeMillis()) {
            startBiliPlayback(song, position);
            return;
        }

        // 异步获取音频流URL
        biliFetching = true;
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
                    biliFetching = false;
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

            handler.post(() -> {
                biliFetching = false;
                startBiliPlayback(song, position);
            });
        }, "BiliAudioFetcher").start();
    }

    /**
     * B站歌曲：用exoPlayer + 带认证头的MediaSource播放音频流
     */
    private void startBiliPlayback(Song song, int position) {
        try {
            // 释放MediaPlayer兜底播放器，避免两个播放器同时出声
            releaseFallbackPlayer();
            isFallbackMode = false;
            if (audioEffectManager != null) audioEffectManager.setFallbackMode(false);

            // 停掉ExoPlayer当前播放，确保干净切换
            exoPlayer.stop();

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

            // 恢复播放进度
            if (pendingSeekPosition > 0) {
                long seekPos = pendingSeekPosition;
                pendingSeekPosition = 0;
                exoPlayer.seekTo(seekPos);
            }

            exoPlayer.play();

            // 双保险：直接读取 ExoPlayer 的 session 挂载音量均衡（EQ/BB/VT 已由软件 EQ 处理）
            try {
                if (audioEffectManager != null) {
                    audioEffectManager.setFallbackMode(false);
                    audioEffectManager.attachLoudnessOnlyAsync(exoPlayer.getAudioSessionId());
                }
            } catch (Throwable ignored) {}

            // 播放成功，重置连续错误计数和瞬态重试标记
            consecutiveErrors = 0;
            transientRetryUsed = false;

            logPlayHistory(song);
            updateNotification();

            // 通知 Activity 歌曲切换了
            sendSongChangedBroadcast(song, position);
        } catch (Exception e) {
            Log.e(TAG, "B站播放启动失败: " + e.getMessage());
            handlePlaybackError();
        }
    }

    private void playSongAtPosition(int position) {
        if (position >= 0 && position < playlist.size()) {
            this.currentIndex = position;
            playSong(playlist.get(position), position);
        }
    }

    private void togglePlayPause() {
        if (isFallbackMode && fallbackPlayer != null) {
            // fallback 模式下状态由 fallbackPlayer 决定（isPlaying 内部会 Binder 到 AudioFlinger，
            // 音频服务异常设备上可能阻塞，移到子线程执行）
            final android.media.MediaPlayer mp = fallbackPlayer;
            new Thread(() -> {
                try {
                    if (mp.isPlaying()) {
                        mp.pause();
                    } else {
                        mp.start();
                    }
                } catch (Exception ignored) {}
            }, "FallbackToggle").start();
            sendPlayStateBroadcast();
            updateNotification();
            return;
        }
        if (exoPlayer == null) return;
        if (exoPlayer.getPlayWhenReady()) {
            pausePlayback();
        } else {
            resumePlayback();
        }
    }

    /**
     * 暂停播放：统一处理 MediaPlayer 兜底模式与 ExoPlayer 模式。
     * 供 MediaSession 回调（外部应用如乐酷桌面）与通知栏按钮共用，避免外部控制漏掉兜底播放器。
     * fallback 模式下 MediaPlayer 控制会 Binder 到 AudioFlinger，音频服务异常设备上可能阻塞，故移到子线程。
     */
    private void pausePlayback() {
        if (isFallbackMode && fallbackPlayer != null) {
            final android.media.MediaPlayer mp = fallbackPlayer;
            new Thread(() -> {
                try { mp.pause(); } catch (Exception ignored) {}
            }, "FallbackPause").start();
            sendPlayStateNotify();
            return;
        }
        if (exoPlayer != null) exoPlayer.pause();
    }

    /**
     * 恢复播放：统一处理 MediaPlayer 兜底模式与 ExoPlayer 模式。
     * 供 MediaSession 回调（外部应用如乐桌面）与通知栏按钮共用，避免外部播放漏掉兜底播放器。
     * fallback 模式下 MediaPlayer 操作可能阻塞，故移到子线程。
     */
    private void resumePlayback() {
        if (isFallbackMode && fallbackPlayer != null) {
            final android.media.MediaPlayer mp = fallbackPlayer;
            new Thread(() -> {
                try { mp.start(); } catch (Exception ignored) {}
            }, "FallbackResume").start();
            sendPlayStateNotify();
            return;
        }
        if (exoPlayer != null) exoPlayer.play();
    }

    private void sendPlayStateNotify() {
        sendPlayStateBroadcast();
        updateNotification();
    }

    private void playNext() {
        if (playlist.isEmpty()) return;
        playAdjacent(1);
    }

    private void playPrevious() {
        if (playlist.isEmpty()) return;
        playAdjacent(-1);
    }

    /**
     * 按当前播放顺序切换到相邻歌曲
     * @param direction +1 下一首, -1 上一首
     */
    private void playAdjacent(int direction) {
        if (playOrder == PLAY_ORDER_REPEAT_ONE) {
            playSongAtPosition(currentIndex);
        } else if (playOrder == PLAY_ORDER_SHUFFLE) {
            playRandomSong();
        } else {
            currentIndex += direction;
            if (currentIndex >= playlist.size()) currentIndex = 0;
            else if (currentIndex < 0) currentIndex = playlist.size() - 1;
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
        if (isFallbackMode && fallbackPlayer != null) {
            fallbackPlayer.seekTo(positionMs);
            return;
        }
        if (exoPlayer != null) {
            exoPlayer.seekTo(positionMs);
        }
    }

    private boolean isPlaying() {
        if (isFallbackMode && fallbackPlayer != null) {
            return fallbackPlayer.isPlaying();
        }
        return exoPlayer != null && exoPlayer.isPlaying();
    }

    private int getCurrentPosition() {
        if (isFallbackMode && fallbackPlayer != null) {
            return fallbackPlayer.getCurrentPosition();
        }
        if (exoPlayer != null) {
            return (int) exoPlayer.getCurrentPosition();
        }
        return 0;
    }

    private int getDuration() {
        if (isFallbackMode && fallbackPlayer != null) {
            return fallbackPlayer.getDuration();
        }
        if (exoPlayer != null && exoPlayer.getDuration() != C.TIME_UNSET) {
            return (int) exoPlayer.getDuration();
        }
        return 0;
    }

    private void setPlaylist(List<Song> songs) {
        this.playlist = new ArrayList<>(songs);
        this.currentIndex = -1;
    }

    private void setPlayOrder(int order) {
        this.playOrder = order;
        // 持久化，重启后恢复播放模式
        getSharedPreferences("last_played", MODE_PRIVATE).edit().putInt("play_order", order).apply();
        // 通知 UI
        Intent intent = new Intent(ACTION_PLAY_ORDER_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_PLAY_ORDER, order);
        sendBroadcast(intent);
    }

    // ========== MediaSession 更新 ==========

    private void updateMediaSessionMetadata() {
        if (deferMediaSessionUpdate) return;  // 延迟更新的歌曲，等歌词就绪后再更新
        doUpdateMediaSessionMetadata();
    }

    /**
     * 计算当前歌曲的 metadata 内容指纹
     * 指纹不变表示内容无变化，定时器秒刷时可用于去重跳过
     */
    private String computeMetadataKey() {
        Song currentSong = getCurrentSong();
        if (currentSong == null) return null;
        String artist = (currentSong.artist != null && !currentSong.artist.isEmpty()) ? currentSong.artist : "未知歌手";
        String coverName = com.jingxin.jingxinmusic.model.Song.toFileName(currentSong.title, currentSong.artist) + ".jpg";
        File cacheCoverFile = new File(com.jingxin.jingxinmusic.util.CoverLoader.getCoverDir(this), coverName);
        String coverFingerprint = (cacheCoverFile.exists() && cacheCoverFile.length() > 0)
                ? cacheCoverFile.length() + "@" + cacheCoverFile.lastModified() : "";
        return currentSong.id + "|" + currentSong.title + "|" + artist + "|" + currentSong.album
                + "|" + currentSong.duration + "|" + coverName + "|" + coverFingerprint
                + "|" + (currentSong.albumArt != null ? currentSong.albumArt : "");
    }

    /**
     * 去重版本：内容指纹未变化时跳过，仅用于每秒定时器路径，避免频繁 setMetadata 打断外部应用动画。
     * 事件驱动路径（切歌/封面就绪/歌词就绪/显式更新）调用 {@link #doUpdateMediaSessionMetadata()}，
     * 保证同曲重播、外部应用重连时也能收到更新。
     */
    private void doUpdateMediaSessionMetadataIfChanged() {
        String key = computeMetadataKey();
        if (key == null || key.equals(lastMetadataKey)) return;
        lastMetadataKey = key;
        doUpdateMediaSessionMetadata();
    }

    private void doUpdateMediaSessionMetadata() {
        Song currentSong = getCurrentSong();
        if (currentSong == null || mediaSession == null) return;

        // artist 为空时填充默认值，否则乐酷桌面 a51.i() 检查 TextUtils.isEmpty(artist) 会跳过不显示
        String artist = (currentSong.artist != null && !currentSong.artist.isEmpty()) ? currentSong.artist : "未知歌手";

        // 封面指纹：缓存文件长度+修改时间（文件不变则指纹不变）。
        // 不能用 bitmap.hashCode()：每次解码都是新对象，对象哈希天然不同会导致去重失效。
        String coverName = com.jingxin.jingxinmusic.model.Song.toFileName(currentSong.title, currentSong.artist) + ".jpg";
        File cacheCoverFile = new File(com.jingxin.jingxinmusic.util.CoverLoader.getCoverDir(this), coverName);
        String coverFingerprint = (cacheCoverFile.exists() && cacheCoverFile.length() > 0)
                ? cacheCoverFile.length() + "@" + cacheCoverFile.lastModified() : "";

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentSong.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.album != null ? currentSong.album : "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentSong.duration);

        if (cacheCoverFile.exists() && cacheCoverFile.length() > 0) {
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
        // 同步去重缓存：事件驱动推送后更新指纹，避免定时器随后重复推同一份内容
        lastMetadataKey = computeMetadataKey();
    }

    private void updateMediaSessionPlaybackState() {
        if (mediaSession == null || stateBuilder == null) return;
        int state = isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        // 统一走 fallback-aware 的 getCurrentPosition()：
        // fallback 模式下真正出声的是 MediaPlayer，直接读 exoPlayer 会恒为 0，
        // 导致乐酷桌面等外部应用拿不到进度、歌词不滚动
        long pos = getCurrentPosition();
        stateBuilder.setState(state, pos, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    /**
     * 启动定时器，每秒更新 MediaSession PlaybackState
     * 其他应用通过 MediaController.getPlaybackState() 获取实时播放进度
     * metadata 不再每秒强刷：内容指纹去重后同一首歌不会重复 setMetadata，
     * 避免每秒触发 onMetadataChanged 打断乐酷桌面等外部应用的封面旋转动画
     */
    private void startPlaybackStateUpdater() {
        stopPlaybackStateUpdater();
        playbackStateUpdater = new Runnable() {
            long lastSaveTime = 0;
            @Override
            public void run() {
                if (isPlaying()) {
                    updateMediaSessionPlaybackState();
                    // 定时器秒刷用去重版本：内容未变化跳过，避免每秒触发 onMetadataChanged 打断外部应用动画
                    if (!deferMediaSessionUpdate) {
                        doUpdateMediaSessionMetadataIfChanged();
                    }
                    // 每5秒周期性保存播放进度，确保退出时进度不丢失
                    long now = System.currentTimeMillis();
                    if (now - lastSaveTime > 5000) {
                        savePlaybackPosition();
                        lastSaveTime = now;
                    }
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
        // 保存当前状态
        boolean wasPlaying = exoPlayer.isPlaying();
        Song currentSong = getCurrentSong();
        int currentPosition = (int) exoPlayer.getCurrentPosition();
        List<Song> currentPlaylist = new ArrayList<>(playlist);
        int currentIdx = currentIndex;

        // 释放旧播放器（release 也可能阻塞 Binder，移到子线程）
        stopPlaybackStateUpdater();
        final ExoPlayer oldPlayer = exoPlayer;
        exoPlayer = null;
        exoPlayerReady = false;
        new Thread(() -> {
            try { oldPlayer.release(); } catch (Exception ignored) {}
        }, "ExoRelease").start();

        // 重建播放器（构造可能阻塞 Binder，移到子线程）；软件 EQ 处理器继续复用
        final DefaultRenderersFactory renderersFactory = new EqRenderersFactory(this, softwareEqProcessor);
        new Thread(() -> {
            try {
                final ExoPlayer player = new ExoPlayer.Builder(this,
                        renderersFactory.setExtensionRendererMode(
                                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
                        .setMediaSourceFactory(new DefaultMediaSourceFactory(buildDataSourceFactory()))
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .setUsage(C.USAGE_MEDIA)
                                .build(), true)
                        .setHandleAudioBecomingNoisy(true)
                        .build();
                handler.post(() -> {
                    if (exoPlayer != null) return;
                    exoPlayer = player;
                    exoPlayerReady = true;
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
                });
            } catch (Throwable t) {
                Log.e(TAG, "ExoPlayer 重建失败: " + t.getMessage());
                handler.post(() -> exoPlayerReady = false);
            }
        }, "ExoRebuild").start();
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
        for (String pkg : new String[]{getPackageName(), null}) {
            Intent intent = new Intent(ACTION_PLAY_STATE_CHANGED);
            if (pkg != null) intent.setPackage(pkg);
            intent.putExtra(EXTRA_IS_PLAYING, isPlaying());
            if (currentSong != null) {
                intent.putExtra(EXTRA_SONG_TITLE, currentSong.title);
                intent.putExtra(EXTRA_SONG_ARTIST, currentSong.artist);
                intent.putExtra(EXTRA_CURRENT_POSITION, getCurrentPosition());
                intent.putExtra(EXTRA_DURATION, getDuration());
            }
            sendBroadcast(intent);
        }
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
        intent.setPackage(getPackageName());
        sendBroadcast(intent);

        if (lyricPaths[0] != null || lyricPaths[1] != null) {
            // 本地有缓存，歌词已在公共目录，立即更新 MediaSession
            deferMediaSessionUpdate = false;
            doUpdateMediaSessionMetadata();
        } else {
            // 本地无缓存，延迟更新 MediaSession，等歌词下载完成（lyricAvailableReceiver 触发）
            // 5秒兜底：歌词搜索失败时也要更新 MediaSession
            deferMediaSessionUpdate = true;
            if (mediaSessionFallback != null) handler.removeCallbacks(mediaSessionFallback);
            mediaSessionFallback = () -> {
                if (deferMediaSessionUpdate) {
                    syncLyricToPublicDir(song);
                    deferMediaSessionUpdate = false;
                    doUpdateMediaSessionMetadata();
                }
            };
            handler.postDelayed(mediaSessionFallback, 5000);
        }

        // Service 自己也加载封面（PlayerActivity 不在前台时保证封面也能加载到缓存）
        ensureCoverCached(song);
    }

    /**
     * 确保封面缓存文件存在：如果不存在则异步加载并保存到缓存目录
     * 加载完成后刷新 MediaSession metadata，让乐酷桌面等外部应用能获取到封面
     */
    private void ensureCoverCached(Song song) {
        if (song == null || song.title == null || coverExecutor == null) return;

        String coverName = Song.toFileName(song.title, song.artist) + ".jpg";
        File cacheCoverFile = new File(com.jingxin.jingxinmusic.util.CoverLoader.getCoverDir(this), coverName);
        if (cacheCoverFile.exists() && cacheCoverFile.length() > 0) return; // 已有缓存无需加载

        // CoverLoader.load 内部通过 executor 异步加载，回调在主线程
        boolean preferLocal = com.jingxin.jingxinmusic.fragment.SettingsFragment.isLocalCoverPriority(this);
        com.jingxin.jingxinmusic.util.CoverLoader.load(this, song, 200, 200, true, preferLocal,
                coverExecutor,
                new com.jingxin.jingxinmusic.util.CoverLoader.CoverCallback() {
                    @Override
                    public void onCoverLoaded(Bitmap bitmap) {
                        // 封面已写入缓存文件，文件指纹已变化，指纹去重会正常触发 setMetadata
                        if (!deferMediaSessionUpdate) {
                            doUpdateMediaSessionMetadata();
                        }
                    }

                    @Override
                    public void onCoverFailed() {
                    }
                });
    }

    /**
     * 同步检查本地歌词缓存，有则复制到公共目录
     * @return [lrcPublicPath, krcPublicPath]，没有则为null
     */
    private String[] syncLyricToPublicDir(Song song) {
        String[] result = new String[]{null, null};
        // 文件命名用原始标题（sanitizeFileName），搜索用清洗后的标题
        String safeName = FileUtil.sanitizeFileName(song.title);
        File lyricsDir = new File(getExternalFilesDir(null), "lyrics");

        // 检查公共目录是否已有歌词文件（Android 10+ 通过 MediaStore，9- 用 File.exists）
        if (publicLyricExists(safeName + ".lrc")) {
            result[0] = getPublicLyricPath(safeName + ".lrc");
        }
        if (publicLyricExists(safeName + ".krc")) {
            result[1] = getPublicLyricPath(safeName + ".krc");
        }
        if (result[0] != null && result[1] != null) return result;

        // 检查本地缓存（兼容旧文件名格式），有则同步复制
        File krcCache = findLyricFile(lyricsDir, safeName, song.artist, ".krc");
        File lrcCache = findLyricFile(lyricsDir, safeName, song.artist, ".lrc");

        if (krcCache.exists() && result[1] == null) {
            LyricPublicUtil.copyToPublicDir(this, krcCache);
            if (publicLyricExists(safeName + ".krc")) result[1] = getPublicLyricPath(safeName + ".krc");
        }
        if (lrcCache.exists() && result[0] == null) {
            LyricPublicUtil.copyToPublicDir(this, lrcCache);
            if (publicLyricExists(safeName + ".lrc")) result[0] = getPublicLyricPath(safeName + ".lrc");
        }

        // 本地有KRC但没有LRC，从KRC生成LRC
        if (krcCache.exists() && !lrcCache.exists() && result[0] == null) {
            KrcParser.LyricData data = KrcParser.parseKrcFile(krcCache);
            if (data != null && data.lines != null && !data.lines.isEmpty()) {
                String lrcText = data.toLrcText();
                if (lrcText != null && !lrcText.isEmpty()) {
                    File newLrcCache = new File(lyricsDir, safeName + ".lrc");
                    FileUtil.writeFile(newLrcCache, lrcText);
                    LyricPublicUtil.copyToPublicDir(this, newLrcCache);
                    if (publicLyricExists(safeName + ".lrc")) result[0] = getPublicLyricPath(safeName + ".lrc");
                }
            }
        }

        return result;
    }

    /**
     * 检查公共 Download/lyrics/ 目录中是否存在指定歌词文件
     * Android 10+ 通过 MediaStore 查询，9- 用 File.exists()
     */
    private boolean publicLyricExists(String fileName) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                android.net.Uri downloadsUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] projection = {android.provider.MediaStore.Downloads._ID};
                String selection = android.provider.MediaStore.Downloads.DISPLAY_NAME + " = ? AND " +
                        android.provider.MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
                String[] selectionArgs = {fileName, "%/lyrics/%"};
                try (android.database.Cursor cursor = getContentResolver().query(
                        downloadsUri, projection, selection, selectionArgs, null)) {
                    return cursor != null && cursor.getCount() > 0;
                }
            } catch (Exception e) {
                Log.w(TAG, "查询公共歌词存在性失败: " + e.getMessage());
            }
            // 兼容旧版 File 路径
            File legacy = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "lyrics/" + fileName);
            return legacy.exists();
        } else {
            File file = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "lyrics/" + fileName);
            return file.exists();
        }
    }

    /**
     * 获取公共 Download/lyrics/ 目录中歌词文件路径
     * Android 10+：返回 MediaStore Uri 的字符串形式（content://）或 legacy File 路径
     * Android 9-：返回 File 路径
     */
    private String getPublicLyricPath(String fileName) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                android.net.Uri downloadsUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] projection = {android.provider.MediaStore.Downloads._ID};
                String selection = android.provider.MediaStore.Downloads.DISPLAY_NAME + " = ? AND " +
                        android.provider.MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
                String[] selectionArgs = {fileName, "%/lyrics/%"};
                try (android.database.Cursor cursor = getContentResolver().query(
                        downloadsUri, projection, selection, selectionArgs, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        long id = cursor.getLong(0);
                        return android.content.ContentUris.withAppendedId(downloadsUri, id).toString();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "获取公共歌词路径失败: " + e.getMessage());
            }
        }
        // Android 9- 或 MediaStore 查询失败时回退到 File 路径
        File file = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "lyrics/" + fileName);
        return file.getAbsolutePath();
    }

    /**
     * 查找本地歌词文件，兼容旧文件名格式
     */
    private File findLyricFile(File lyricsDir, String safeName, String artist, String ext) {
        // 1. 精确匹配
        File file = new File(lyricsDir, safeName + ext);
        if (file.exists()) return file;

        // 2. 尝试旧格式（清洗后的歌名 + 歌手）
        if (artist != null && !artist.isEmpty() && !"<unknown>".equals(artist)) {
            String safeArtist = artist.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
            String cleanName = Song.cleanSongTitle(safeName, artist);
            File legacy1 = new File(lyricsDir, cleanName + " - " + safeArtist + ext);
            if (legacy1.exists()) return legacy1;
            File legacy2 = new File(lyricsDir, safeArtist + " - " + cleanName + ext);
            if (legacy2.exists()) return legacy2;
            File legacy3 = new File(lyricsDir, safeArtist + "-" + cleanName + ext);
            if (legacy3.exists()) return legacy3;
        }

        // 3. 都没找到，返回标准路径
        return file;
    }

    private Song getCurrentSong() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            return playlist.get(currentIndex);
        }
        return null;
    }

    // ========== MediaPlayer 兜底播放（ALAC等ExoPlayer不支持的格式） ==========

    /**
     * 判断是否需要使用 MediaPlayer 兜底播放
     * ALAC 编码的 m4a：若设备无 ALAC 解码器，ExoPlayer 会静默跳过音频轨道（不报错），
     * 所以必须提前检测，直接走 MediaPlayer 兜底。
     * AIFF / WMA / APE / DTS / DSD / AC3 等罕见格式：同样直接兜底。
     * 车机可能带特殊解码器（杜比AC3/DTS/DSD），MediaPlayer 调系统解码器有机会播放。
     *
     * WebDAV 例外：playUri 以 http 开头时不走 MediaPlayer 兜底，
     * 因为 MediaPlayer.setDataSource(String) 无法携带 HTTP 认证头，
     * WebDAV 需要认证的资源会直接 401 失败。
     */
    private boolean needsFallback(String filePath, String playUri) {
        String path = stripQueryParams(filePath != null ? filePath : playUri);
        if (path == null) return false;
        String lower = path.toLowerCase();
        // m4a 可能是 ALAC 编码，先检查设备解码器
        // 有 ALAC 解码器的设备（如部分三星/小米真机），ExoPlayer 能正常播放
        // 无 ALAC 解码器的设备（如模拟器），ExoPlayer 会静默无声，必须走 MediaPlayer
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) {
            // WebDAV 歌曲不走 MediaPlayer 兜底（setDataSource 无法携带认证头，会 401 失败）
            if (playUri != null && playUri.startsWith("http")) {
                return false;
            }
            return !hasAlacDecoder();
        }
        // AIFF / WMA 等罕见格式，直接兜底
        if (lower.endsWith(".aiff") || lower.endsWith(".aif") || lower.endsWith(".wma")) {
            return true;
        }
        // APE / DTS / DSD / AC3：ExoPlayer 不支持，走 MediaPlayer 兜底
        // 车机可能带杜比AC3/DTS/DSD解码器，MediaPlayer 调系统解码器有机会播放
        // WebDAV 歌曲例外：MediaPlayer 无法携带认证头，不兜底
        if (lower.endsWith(".ape") || lower.endsWith(".dts") ||
            lower.endsWith(".dsf") || lower.endsWith(".dff") || lower.endsWith(".ac3")) {
            // WebDAV 歌曲不走 MediaPlayer 兜底（认证头问题）
            if (playUri != null && playUri.startsWith("http")) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * 检查设备是否有支持 ALAC 的 MediaCodec 解码器
     */
    private boolean hasAlacDecoder() {
        try {
            android.media.MediaCodecList codecList = new android.media.MediaCodecList(
                    android.media.MediaCodecList.ALL_CODECS);
            for (android.media.MediaCodecInfo info : codecList.getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if ("audio/alac".equals(type)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "检测ALAC解码器失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 使用系统 MediaPlayer 播放（兜底 ExoPlayer 不支持的格式）
     * 注意：MediaPlayer 构造/播放/seek 等操作内部会 Binder 到 AudioFlinger（acquireAudioSessionId），
     * 在音频服务异常设备（5.1 模拟器 AudioFlinger 卡死）上会阻塞调用线程，
     * 因此整个创建流程移到子线程执行，主线程立即返回，避免 ANR。
     */
    private void startFallbackPlayback(Song song, String playUri, int position) {
        releaseFallbackPlayer();
        isFallbackMode = true;
        if (audioEffectManager != null) audioEffectManager.setFallbackMode(true);

        // 暂停 ExoPlayer，避免两个播放器同时出声（仅判空调用，不阻塞）
        if (exoPlayer != null) {
            try { exoPlayer.pause(); } catch (Exception ignored) {}
        }

        new Thread(() -> {
            android.media.MediaPlayer mp = null;
            // 兜底初始化失败多为瞬态：车机快速切歌时旧 MediaCodec/AudioTrack 实例异步释放未完，
            // 新建即失败（曾误报"格式不支持"、切几次又好的病根）；延时重试一次
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    if (attempt > 1) {
                        if (isFallbackSuperseded(song, position)) { // 重试间隙被切歌
                            return;
                        }
                        Log.w(TAG, "MediaPlayer兜底初始化失败，延时" + FALLBACK_RETRY_DELAY_MS + "ms重试");
                        Thread.sleep(FALLBACK_RETRY_DELAY_MS);
                    }
                    mp = new android.media.MediaPlayer();
                    // 优先使用文件路径（MediaPlayer 通过路径可直接访问文件，比 content URI 更兼容）
                    // content URI 在某些设备/模拟器上可能无法正确传递格式信息
                    String dataSource = song.filePath != null ? song.filePath : playUri;
                    if (dataSource.startsWith("content://")) {
                        mp.setDataSource(this, Uri.parse(dataSource));
                    } else {
                        mp.setDataSource(dataSource);
                    }
                    mp.setOnCompletionListener(m -> handler.post(() -> playNext()));
                    mp.setOnErrorListener((m, what, extra) -> {
                        Log.e(TAG, "MediaPlayer兜底播放也失败: what=" + what + " extra=" + extra);
                        releaseFallbackPlayer();
                        isFallbackMode = false;
                        showToast("播放失败，请重试");
                        handler.post(() -> handlePlaybackError());
                        return true;
                    });
                    mp.prepare();
                    break; // 初始化成功
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (mp != null) { try { mp.release(); } catch (Exception ignored) {} }
                    return;
                } catch (Exception e) {
                    Log.e(TAG, "MediaPlayer兜底播放初始化失败(第" + attempt + "次): " + e.getMessage());
                    if (mp != null) { try { mp.release(); } catch (Exception ignored) {} mp = null; }
                }
            }
            if (mp == null) { // 两次都失败
                isFallbackMode = false;
                showToast("播放失败，请重试");
                handler.post(() -> handlePlaybackError());
                return;
            }
            // 重试间隙被切歌：新播放已接管（isFallbackMode 由新路径管理），放弃本次
            if (isFallbackSuperseded(song, position)) {
                try { mp.release(); } catch (Exception ignored) {}
                return;
            }

            // 恢复播放进度
            if (pendingSeekPosition > 0) {
                long seekPos = pendingSeekPosition;
                pendingSeekPosition = 0;
                mp.seekTo((int) seekPos);
            }

            // 创建成功后才赋给成员变量（失败路径由局部引用直接释放，杜绝泄漏）
            fallbackPlayer = mp;
            // MediaPlayer 兜底 session：预设走系统效果器（无软件 EQ，车机此场景本来就罕见）
            try {
                if (audioEffectManager != null) {
                    audioEffectManager.setFallbackMode(true);
                    audioEffectManager.attachAsync(mp.getAudioSessionId());
                }
            } catch (Exception ignored) {}
            mp.start();

            // 播放成功，重置连续错误计数和瞬态重试标记
            consecutiveErrors = 0;
            transientRetryUsed = false;

            logPlayHistory(song);

            // 同步播放状态到UI（fallback模式下没有ExoPlayer回调，需手动发送）
            handler.post(() -> {
                sendPlayStateBroadcast();
                updateNotification();
                startPlaybackStateUpdater();
                sendSongChangedBroadcast(song, position);
            });
        }, "FallbackInit").start();
    }

    /** 兜底异步流程是否已被切歌取代（快速切歌时放弃过期初始化，避免双声/错位） */
    private boolean isFallbackSuperseded(Song song, int position) {
        if (currentIndex != position) return true;
        Song cur = getCurrentSong();
        if (cur == song) return false;
        if (cur == null || song == null) return true;
        if (song.filePath != null && song.filePath.equals(cur.filePath)) return false;
        return song.contentUri == null || !song.contentUri.equals(cur.contentUri);
    }

    /**
     * 释放 MediaPlayer 兜底播放器
     * stop()/release() 在音频服务异常的设备（如 5.1 模拟器 AudioFlinger Binder 卡死）上
     * 会阻塞主线程导致 ANR，因此移到子线程释放，主线程立即返回。
     */
    private void releaseFallbackPlayer() {
        final android.media.MediaPlayer mp = fallbackPlayer;
        fallbackPlayer = null;
        if (mp != null) {
            new Thread(() -> {
                try {
                    mp.stop();
                } catch (Exception ignored) {}
                try {
                    mp.release();
                } catch (Exception ignored) {}
            }, "FallbackRelease").start();
        }
    }

    /**
     * 在主线程显示 Toast 提示
     */
    private void showToast(String message) {
        handler.post(() -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show());
    }

    /**
     * 去掉 URL 查询参数（?xxx=yyy），非 http(s) 路径原样返回
     */
    private String stripQueryParams(String path) {
        if (path != null && path.startsWith("http")) {
            int q = path.indexOf('?');
            if (q > 0) return path.substring(0, q);
        }
        return path;
    }

    /**
     * 后台线程记录播放历史
     */
    private void logPlayHistory(Song song) {
        new Thread(() -> {
            File historyDir = new File(getExternalFilesDir(null), "history");
            HistoryManager.addHistory(historyDir, song);
        }, "HistoryLogger").start();
    }

    /**
     * 获取当前歌曲的本地播放 URI（contentUri 优先），非本地源返回 null
     */
    private String getLocalPlayUri(Song song) {
        if (song == null) return null;
        String uri = song.contentUri != null ? song.contentUri : song.filePath;
        if (uri != null && !uri.startsWith("http") && !uri.startsWith("bili://")) {
            return uri;
        }
        return null;
    }

    /**
     * 统一处理播放错误：递增计数，未超限则跳下一首，超限则通知 UI 停止自动切歌
     */
    private void handlePlaybackError() {
        consecutiveErrors++;
        if (consecutiveErrors <= MAX_CONSECUTIVE_ERRORS) {
            playNext();
        } else {
            Log.w(TAG, "连续" + MAX_CONSECUTIVE_ERRORS + "首播放失败，停止自动切歌");
            Intent errorIntent = new Intent(ACTION_PLAY_STATE_CHANGED);
            errorIntent.setPackage(getPackageName());
            errorIntent.putExtra("play_error", true);
            errorIntent.putExtra("error_message", "连续播放失败，请检查网络或音乐文件");
            sendBroadcast(errorIntent);
        }
    }

    /**
     * 瞬态错误：IO类（网络/文件读取）和解码器初始化失败。
     * 这类错误重试通常可恢复，不应直接判定为格式不支持。
     */
    private boolean isTransientError(int errorCode) {
        // IO 类（网络抖动、连接失败、超时、HTTP错误）
        if (errorCode >= PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                && errorCode <= PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
            return true;
        }
        // 解码器初始化失败（资源被占用、上一次异步释放未完成）
        if (errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
            return true;
        }
        // 解码格式错误偶发也可能恢复，但保守起见只重试 IO 和 init 失败
        return false;
    }

    /**
     * IO 类错误（网络/文件读取），用于给出真实原因提示
     */
    private boolean isIoError(int errorCode) {
        return errorCode >= PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                && errorCode <= PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
    }

    /**
     * 重试当前歌曲：stop 后重新 prepare（重建 MediaCodec 实例/重连网络源）
     */
    private void retryCurrentPlayback() {
        Song currentSong = getCurrentSong();
        if (currentSong == null || exoPlayer == null) return;
        try {
            exoPlayer.pause();
            exoPlayer.prepare(); // 重新 prepare 重建数据源和渲染器
            exoPlayer.play();
        } catch (Exception e) {
            Log.e(TAG, "重试播放失败: " + e.getMessage());
            handlePlaybackError();
        }
    }

    /**
     * 根据文件扩展名推断音频MIME类型
     * content://URI 无法传递格式信息时，ExoPlayer 可能选错解码器，明确指定 MIME 可避免此问题
     * 注意：ALAC 编码的 m4a 已在 needsFallback() 中拦截走 MediaPlayer，此处只处理常规格式
     */
    private String inferMimeType(String filePath, String playUri) {
        String path = stripQueryParams(filePath);
        // 如果filePath不可用，尝试从playUri提取
        if (path == null || path.isEmpty()) path = playUri;
        if (path == null) return null;

        String lower = path.toLowerCase();
        // MP4容器格式（含AAC/ALAC编码）—— 这是修复ALAC m4a的关键
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".3gp")) {
            return "audio/mp4";
        }
        // 其他常见格式明确指定，避免ExoPlayer猜测错误
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg") || lower.endsWith(".oga")) return "audio/ogg";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        // 未知格式不设置，让ExoPlayer自行推断
        return null;
    }

    /**
     * 注入软件 EQ 的 RenderersFactory：buildAudioSink 时把 SoftwareEqProcessor
     * 插入 AudioSink 处理链（EQ/低音/环绕预设在本应用 DSP 内完成，绕开厂商效果器）。
     */
    private static final class EqRenderersFactory extends DefaultRenderersFactory {
        private final SoftwareEqProcessor eqProcessor;

        EqRenderersFactory(Context context, SoftwareEqProcessor eqProcessor) {
            super(context);
            this.eqProcessor = eqProcessor;
        }

        @Override
        protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
            return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[]{eqProcessor})
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build();
        }
    }
}
