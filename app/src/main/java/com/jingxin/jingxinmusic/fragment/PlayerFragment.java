package com.jingxin.jingxinmusic.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.jingxin.jingxinmusic.HostActivity;
import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.scene.CoverScene;
import com.jingxin.jingxinmusic.scene.CoverSceneHelper;
import com.jingxin.jingxinmusic.scene.PortraitClassicScene;
import com.jingxin.jingxinmusic.scene.PortraitImmersiveScene;
import com.jingxin.jingxinmusic.scene.PortraitRecordScene;
import com.jingxin.jingxinmusic.scene.PortraitCarouselScene;
import com.jingxin.jingxinmusic.scene.LandscapeClassicScene;
import com.jingxin.jingxinmusic.scene.LandscapeImmersiveScene;
import com.jingxin.jingxinmusic.scene.LandscapeRecordScene;
import com.jingxin.jingxinmusic.scene.LandscapeCarouselScene;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.service.MusicPlayerService.MusicPlayerBinder;
import com.jingxin.jingxinmusic.util.FavoriteManager;
import com.jingxin.jingxinmusic.util.HistoryManager;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.util.LyricFetcher;
import com.jingxin.jingxinmusic.util.MusicScanner;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.RotatingCoverView;
import com.jingxin.jingxinmusic.view.SpectrumView;
import com.jingxin.jingxinmusic.view.TonearmView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.AlertDialog;

/**
 * 播放页面 Fragment
 * 旋转封面 + 歌词 + 频谱 + 进度条 + 控制按钮
 */
public class PlayerFragment extends BaseFloatFragment {

    private static final String TAG = "PlayerFragment";
    private static final int PROGRESS_UPDATE_INTERVAL = 200;

    private Song song;
    private int position;
    private List<Song> allSongs;
    private String playlistMode = "all"; // "all" 或 "favorites"
    private boolean resumePlay = false; // 从 mini 播放条跳转，不重新播放

    private View mRootView;

    // UI
    private ImageView blurBackground;
    private RotatingCoverView coverView;
    private TextView tvSongName;
    private TextView tvArtist;
    private LyricView lyricView;
    private SpectrumView spectrumView;
    
    private SeekBar seekBar;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private ImageView btnPlayPause;
    private ImageView btnPrevious;
    private ImageView btnNext;
    private ImageView btnHistory;
    private ImageView btnFavorite;
    private ImageView btnDownload;
    private ImageView btnPlayOrder;
    private ImageView btnBack;
    private ImageView btnSpectrum;
    private ImageView btnOutfit;
    private ImageView btnLyricSearch;
    private View overlayView;
    private View whiteOverlay;
    private View immersiveDarkOverlay;

    // 封面缓存：切歌时保持旧封面，避免「默认封面→真实封面」闪烁
    private boolean hasCoverLoaded = false; // 是否已有封面加载过

    // 白天模式渐变遮罩（浅绿→白）
    private android.graphics.drawable.GradientDrawable whiteGradientDrawable;

    // 主题
    private boolean isNightMode = true;  // 默认夜间模式
    private boolean isFavorite = false;  // 当前歌曲是否已收藏

    // 封面风格：0=经典, 1=沉浸, 2=唱片机
    private static final int COVER_STYLE_CLASSIC = 0;
    private static final int COVER_STYLE_IMMERSIVE = 1;
    private static final int COVER_STYLE_RECORD = 2;
    private static final int COVER_STYLE_CAROUSEL = 3;
    private int coverStyle = COVER_STYLE_CLASSIC;
    private TonearmView tonearmView;
    private com.jingxin.jingxinmusic.view.ImmersiveOverlayView immersiveOverlay;
    private android.widget.PopupWindow spectrumPickerPopup; // 频谱选择浮窗
    private android.widget.PopupWindow lyricSearchPopup; // 歌词搜索浮窗

    // 横屏模式
    private boolean isLandscapeMode = false; // 宽>高*1.2 时为横屏
    private View infoPanel;     // 左侧信息面板
    private View coverPlaceholder; // 竖屏时封面占位
    private com.jingxin.jingxinmusic.view.CoverBorderGradientDrawable coverBorderGradient; // 横屏沉浸封面边缘渐变
    private com.jingxin.jingxinmusic.view.LandscapeGradientOverlay landscapeGradientOverlay; // 横屏沉浸渐变过渡层

    // CoverScene 模式策略
    private CoverSceneHelper sceneHelper;
    private PortraitClassicScene portraitClassic;
    private PortraitImmersiveScene portraitImmersive;
    private PortraitRecordScene portraitRecord;
    private PortraitCarouselScene portraitCarousel;
    private LandscapeClassicScene landscapeClassic;
    private LandscapeImmersiveScene landscapeImmersive;
    private LandscapeRecordScene landscapeRecord;
    private LandscapeCarouselScene landscapeCarousel;
    private CoverScene currentScene;

    // 播放服务
    private MusicPlayerBinder playerBinder;
    private boolean bound = false;
    private final Handler uiHandler = new Handler();
    private boolean userSeeking = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 频谱：Visualizer（主方案）或 AudioRecord（降级方案）
    private android.media.audiofx.Visualizer visualizer;
    private AudioRecord audioRecord;
    private volatile boolean spectrumRunning = false;
    private boolean useVisualizer = false; // 当前是否用 Visualizer
    private volatile long lastSpectrumCallbackTime = 0; // 频谱回调时间戳（心跳检测）
    private final Runnable spectrumHeartbeat = new Runnable() {
        @Override
        public void run() {
            if (spectrumRunning && useVisualizer && bound && playerBinder != null && playerBinder.isPlaying()) {
                long elapsed = android.os.SystemClock.elapsedRealtime() - lastSpectrumCallbackTime;
                if (elapsed > 3000) {
                    Log.w(TAG, "频谱心跳超时(" + elapsed + "ms)，自动重建");
                    stopSpectrum();
                    startSpectrumWithPermission();
                }
            }
            uiHandler.postDelayed(this, 2000);
        }
    };
    private static final int SAMPLE_RATE = 8000;
    private static final int FFT_SIZE = 256;

    // 广播接收器：监听歌曲切换和播放状态
    private BroadcastReceiver songChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (MusicPlayerService.ACTION_SONG_CHANGED.equals(action)) {
                // 切歌了，更新所有 UI
                Song newSong = Song.fromIntent(intent);
                position = intent.getIntExtra(MusicPlayerService.EXTRA_SONG_INDEX, 0);

                song = newSong;
                tvSongName.setText(newSong.title);
                tvArtist.setText(newSong.artist);
                tvTotalTime.setText(Song.formatDuration(newSong.duration));
                tvCurrentTime.setText("00:00");
                loadCover();
                fetchLyrics();
                checkFavoriteStatus();
                updateDownloadButtonVisibility();
                saveLastPlayed();
                // 轮播模式切歌时滚动到新位置
                syncCarouselPosition();
                // 切歌时重建频谱（sessionId 可能变化，如 ExoPlayer 重建或 MediaPlayer 兜底）
                if (bound && playerBinder != null) {
                    stopSpectrum();
                    startSpectrumWithPermission();
                }
            } else if (MusicPlayerService.ACTION_PLAY_STATE_CHANGED.equals(action)) {
                boolean playing = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false);
                updatePlayPauseButton(playing);
            } else if (MusicPlayerService.ACTION_PLAY_ORDER_CHANGED.equals(action)) {
                int order = intent.getIntExtra(MusicPlayerService.EXTRA_PLAY_ORDER, 0);
                updatePlayOrderIcon(order);
            } else if (MusicPlayerService.ACTION_THEME_CHANGED.equals(action)) {
                // 高德导航日夜模式切换
                boolean amapNight = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_NIGHT, isNightMode);
                if (amapNight != isNightMode) {
                    // 检查是否用户手动切过主题（如果是，忽略高德信号）
                    boolean amapTriggered = requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE)
                            .getBoolean("amapTriggered", false);
                    if (amapTriggered) {
                        // 高德触发的，直接同步
                        isNightMode = amapNight;
                        if (lyricView != null) {
                            lyricView.setThemeMode(isNightMode ?
                                    com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT :
                                    com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
                        }
                        updateThemeUI();
                    }
                }
            } else if ("com.jingxin.jingxinmusic.SPECTRUM_RESTART".equals(action)) {
                // 乐酷悬浮进入/退出时，MiniFloatService 的 Visualizer 被释放/重建
                // 导致同一 audioSessionId 上 PlayerFragment 的 Visualizer 回调失效，需重建
                stopSpectrum();
                startSpectrumWithPermission();
            }
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerBinder = (MusicPlayerBinder) service;
            bound = true;

            // 读缓存或扫描歌曲列表（不触发 triggerMediaScan）
            executor.execute(() -> {
                allSongs = MusicScanner.loadCache(requireContext());
                if (allSongs == null || allSongs.isEmpty()) {
                    allSongs = MusicScanner.scanMusic(requireContext());
                }
                uiHandler.post(() -> {
                    if (bound && playerBinder != null && allSongs != null) {
                        if (resumePlay && playerBinder.isPlaying()) {
                            // 从 mini 播放条跳转，音乐已在后台播放，只更新 UI
                            btnPlayPause.setImageResource(R.drawable.ic_pause);
                            coverView.startRotation();
                            spectrumView.setPlaying(true);
                            startSpectrumWithPermission();
                            // 通过 Scene 同步播放状态（唱片机同步唱臂等）
                            currentScene.onServiceResumed(true);
                        } else if ("folder".equals(playlistMode)) {
                            // 目录模式：播放队列 = 该目录歌曲
                            List<String> folderPaths = getArguments() != null ? getArguments().getStringArrayList("folder_song_paths") : null;
                            if (folderPaths != null && !folderPaths.isEmpty()) {
                                List<Song> folderSongs = new ArrayList<>();
                                for (String path : folderPaths) {
                                    for (Song s : allSongs) {
                                        if (s.filePath != null && s.filePath.equals(path)) {
                                            folderSongs.add(s);
                                            break;
                                        }
                                    }
                                }
                                if (!folderSongs.isEmpty()) {
                                    playerBinder.setPlaylist(folderSongs);
                                    playSong();
                                } else {
                                    playerBinder.setPlaylist(allSongs);
                                    playSong();
                                }
                            } else {
                                playerBinder.setPlaylist(allSongs);
                                playSong();
                            }
                        } else if ("favorites".equals(playlistMode)) {
                            // 收藏模式：播放队列 = 收藏歌曲
                            File favDir = com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(requireContext());
                            List<Song> favSongs = FavoriteManager.loadFavorites(favDir);
                            if (!favSongs.isEmpty()) {
                                playerBinder.setPlaylist(favSongs);
                                // 从收藏列表中找到匹配歌曲的位置
                                int favPos = 0;
                                for (int i = 0; i < favSongs.size(); i++) {
                                    if (favSongs.get(i).filePath != null &&
                                            favSongs.get(i).filePath.equals(song.filePath)) {
                                        favPos = i;
                                        break;
                                    }
                                }
                                position = favPos;
                                playSong();
                            } else {
                                playerBinder.setPlaylist(allSongs);
                                playSong();
                            }
                        } else if ("webdav".equals(playlistMode) || "bili".equals(playlistMode) || (getArguments() != null && getArguments().getBoolean("from_webdav", false))) {
                            // WebDAV/B站模式：从SharedPreferences恢复播放列表
                            List<Song> savedSongs = loadWebDavPlaylist();
                            if (!savedSongs.isEmpty()) {
                                playerBinder.setPlaylist(savedSongs);
                                // position优先从"position"取（正常浏览点击），"song_index"是自动恢复时的备选
                                int savedIndex = getArguments() != null ? getArguments().getInt("song_index", -1) : -1;
                                if (position >= 0 && position < savedSongs.size()) {
                                    // 从"position"已取到有效值，直接用
                                } else if (savedIndex >= 0 && savedIndex < savedSongs.size()) {
                                    // 自动恢复场景
                                    position = savedIndex;
                                } else {
                                    position = 0;
                                }
                                // 用播放列表中完整字段的歌曲替代可能缺失字段的song
                                song = savedSongs.get(position);
                                playSong();
                            } else {
                                // 降级：单曲播放
                                playerBinder.setPlaylist(java.util.Collections.singletonList(song));
                                position = 0;
                                playSong();
                            }
                        } else {
                            playerBinder.setPlaylist(allSongs);
                            playSong();
                        }
                        // 轮播模式：歌曲列表已就绪，同步到 adapter
                        if (coverStyle == COVER_STYLE_CAROUSEL) {
                            syncCarouselSongs();
                        }
                    }
                });
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playerBinder = null;
            bound = false;
        }
    };

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (bound && playerBinder != null && !userSeeking) {
                int currentPosition = playerBinder.getCurrentPosition();
                int duration = playerBinder.getDuration();
                if (duration > 0) {
                    seekBar.setMax(duration);
                    seekBar.setProgress(currentPosition);
                    tvCurrentTime.setText(Song.formatDuration(currentPosition));
                    tvTotalTime.setText(Song.formatDuration(duration));
                    // 同步歌词位置
                    if (lyricView != null) {
                        lyricView.updatePosition(currentPosition);
                    }
                }
            }
            uiHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 获取传入的歌曲信息
        Bundle args = getArguments();
        if (args != null) {
            Intent temp = new Intent();
            temp.putExtras(args);
            song = Song.fromIntent(temp);
            position = args.getInt("position", 0);
            playlistMode = args.getString("playlist_mode");
            if (playlistMode == null) playlistMode = "all";
            resumePlay = args.getBoolean("resume_play", false);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_player, container, false);
        mRootView = view;

        // 初始化视图
        blurBackground = view.findViewById(R.id.blur_background);
         coverView = view.findViewById(R.id.cover_view);
         coverView.setBackgroundResource(R.drawable.circle_cover_background);
        tonearmView = view.findViewById(R.id.tonearm_view);
        tvSongName = view.findViewById(R.id.song_name_text);
        tvArtist = view.findViewById(R.id.artist_text);
        lyricView = view.findViewById(R.id.lyric_view);
        spectrumView = view.findViewById(R.id.spectrum_view);
        seekBar = view.findViewById(R.id.progress_seek_bar);
        tvCurrentTime = view.findViewById(R.id.current_time_text);
        tvTotalTime = view.findViewById(R.id.total_time_text);
        btnPlayPause = view.findViewById(R.id.play_pause_button);
        btnPrevious = view.findViewById(R.id.previous_button);
        btnNext = view.findViewById(R.id.next_button);
        btnHistory = view.findViewById(R.id.history_button);
        btnFavorite = view.findViewById(R.id.mode_button);  // 复用 mode_button 位置
        btnDownload = view.findViewById(R.id.download_button);
        btnPlayOrder = view.findViewById(R.id.play_order_button);
        btnBack = view.findViewById(R.id.back_button);
        btnSpectrum = view.findViewById(R.id.spectrum_button);
        btnOutfit = view.findViewById(R.id.outfit_button);
        btnLyricSearch = view.findViewById(R.id.lyric_search_button);
        overlayView = view.findViewById(R.id.overlay_view);
        whiteOverlay = view.findViewById(R.id.white_overlay);
        // 初始化白天模式渐变遮罩：浅蓝(0xFFADD8E6)→白(0xFFFFFFFF)，左上到右下
        whiteGradientDrawable = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFADD8E6, 0xFFFFFFFF}
        );
        whiteOverlay.setBackground(whiteGradientDrawable);
        whiteOverlay.setAlpha(0.7f);
        immersiveDarkOverlay = view.findViewById(R.id.immersive_dark_overlay);
        immersiveOverlay = view.findViewById(R.id.immersive_overlay);
        infoPanel = view.findViewById(R.id.info_panel);
        coverPlaceholder = view.findViewById(R.id.cover_placeholder);

        // 初始化 CoverScene 策略
        initCoverScene();

        // 延迟检测横屏模式（等视图布局完成）
        // 用 ViewTreeObserver 监听布局变化，自动切换横竖屏布局
        isLandscapeMode = false;
        View rootView = view.findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (isActivityGone()) return;
                boolean wasLandscape = isLandscapeMode;
                detectAndApplyLandscapeMode();
                if (wasLandscape != isLandscapeMode) {
                    applyLayoutMode();
                    updateThemeUI();
                    updateLayoutForMode(lyricView.getDisplayMode());
                }
            });
        }

        // 在根 FrameLayout 上监听尺寸变化（车机调整应用宽度时触发）
        android.widget.FrameLayout rootFrameLayout = view.findViewById(R.id.root_layout);
        if (rootFrameLayout != null) {
            rootFrameLayout.addOnLayoutChangeListener(
                    (View v, int left, int top, int right, int bottom,
                     int oldLeft, int oldTop, int oldRight, int oldBottom) -> {
                        if (isActivityGone()) return;
                        int newWidth = right - left;
                        int oldWidth = oldRight - oldLeft;
                        if (newWidth > 0 && newWidth != oldWidth) {
                            // 横竖屏切换时关闭频谱选择弹窗
                            if (spectrumPickerPopup != null && spectrumPickerPopup.isShowing()) {
                                spectrumPickerPopup.dismiss();
                            }
                            // 重新检测横竖屏并应用完整布局
                            boolean wasLandscape = isLandscapeMode;
                            detectAndApplyLandscapeMode();
                            applyLayoutMode();
                            // 始终重新layout，更新频谱位置（圆环模式需要跟随封面中心）
                            int w = getLayoutWidth();
                            int h = getAvailableScreenHeight();
                            currentScene.layout(w, h);
                            if (wasLandscape == isLandscapeMode && isLandscapeMode) {
                                // 横屏宽度变化，同步主题和歌词布局
                                updateThemeUI();
                                updateLayoutForMode(lyricView.getDisplayMode());
                            } else {
                                // 横竖切换
                                updateThemeUI();
                                updateLayoutForMode(lyricView.getDisplayMode());
                            }
                        }
                    });
        }
        // 首次必须应用竖屏布局（设置封面占位等）——通过 scene layout
        syncSceneState();
        currentScene.layout(getLayoutWidth(), getAvailableScreenHeight());

        // 竖屏模式下封面和频谱高度：相对屏幕高度
        int availableHeight = getAvailableScreenHeight();
        coverView.getLayoutParams().height = (int) (availableHeight * 0.25f);
        coverView.getLayoutParams().width = (int) (availableHeight * 0.25f);
        // 圆环模式频谱需铺满rootLayout（MATCH_PARENT），由applySpectrumPosition处理
        if (!spectrumView.isCoverOverlayMode()) {
            spectrumView.getLayoutParams().height = (int) (availableHeight * 0.10f);
        }

        // 读取主题状态并同步所有 UI
        Context ctx = requireContext();
        isNightMode = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE).getBoolean("isNight", true);
        coverStyle = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE).getInt("cover_style", COVER_STYLE_CLASSIC);
        // 在初始化场景前先检测横竖屏（OnGlobalLayoutListener是异步的，会太晚）
        detectAndApplyLandscapeMode();
        ThemeColors.init(ctx);
        updateThemeUI();
        lyricView.setThemeMode(isNightMode
                ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
        immersiveOverlay.setNightMode(isNightMode);

        // 封面风格初始化：切换到正确的 scene
        if (coverStyle == COVER_STYLE_IMMERSIVE) {
            syncSceneState();
            currentScene = isLandscapeMode ? landscapeImmersive : portraitImmersive;
            currentScene.enter();
            currentScene.onStyleEnter();
            if (lyricView != null) {
                immersiveOverlay.setFullScreenMode(
                        lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL);
            }
        } else if (coverStyle == COVER_STYLE_RECORD) {
            // 唱片机模式：通过 RecordScene 的 onStyleEnter 统一处理
            syncSceneState();
            currentScene = isLandscapeMode ? landscapeRecord : portraitRecord;
            currentScene.enter();
            currentScene.onStyleEnter();
        } else if (coverStyle == COVER_STYLE_CAROUSEL) {
            // 轮播模式：通过 CarouselScene 的 onStyleEnter 统一处理
            syncSceneState();
            currentScene = isLandscapeMode ? landscapeCarousel : portraitCarousel;
            currentScene.enter();
            currentScene.onStyleEnter();
        }

        // 显示歌曲信息
        tvSongName.setText(song.title);
        tvArtist.setText(song.artist);
        tvTotalTime.setText(Song.formatDuration(song.duration));
        updateDownloadButtonVisibility();

        // 进度条
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvCurrentTime.setText(Song.formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                if (bound && playerBinder != null) {
                    playerBinder.seekTo(seekBar.getProgress());
                }
            }
        });

        // 控制按钮
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnPrevious.setOnClickListener(v -> playPrevious());
        btnNext.setOnClickListener(v -> playNext());
        btnHistory.setOnClickListener(v -> showHistoryDialog());
        btnFavorite.setOnClickListener(v -> toggleFavorite());
        btnDownload.setOnClickListener(v -> saveBiliOffline());
        btnPlayOrder.setOnClickListener(v -> togglePlayOrder());

        // 歌词区域：单击切换模式
        lyricView.setOnClickListener(v -> {
            if (coverStyle == COVER_STYLE_IMMERSIVE) {
                // 沉浸模式：只在双行和多行之间切换，不进入全屏
                com.jingxin.jingxinmusic.view.LyricView.DisplayMode cur = lyricView.getDisplayMode();
                com.jingxin.jingxinmusic.view.LyricView.DisplayMode newMode;
                if (cur == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE) {
                    newMode = com.jingxin.jingxinmusic.view.LyricView.DisplayMode.MULTI_LINE;
                } else {
                    newMode = com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE;
                }
                lyricView.setDisplayMode(newMode);
                updateLayoutForMode(newMode);
            } else if (coverStyle == COVER_STYLE_CAROUSEL && isLandscapeMode) {
                // 横屏轮播：歌词锁定双行，禁止点击切换
                return;
            } else if (coverStyle == COVER_STYLE_CAROUSEL && !isLandscapeMode) {
                // 竖屏轮播：只在双行和多行之间切换，不进入全屏
                com.jingxin.jingxinmusic.view.LyricView.DisplayMode cur = lyricView.getDisplayMode();
                com.jingxin.jingxinmusic.view.LyricView.DisplayMode newMode;
                if (cur == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE) {
                    newMode = com.jingxin.jingxinmusic.view.LyricView.DisplayMode.MULTI_LINE;
                } else {
                    newMode = com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE;
                }
                lyricView.setDisplayMode(newMode);
                updateLayoutForMode(newMode);
            } else {
                com.jingxin.jingxinmusic.view.LyricView.DisplayMode newMode = lyricView.toggleMode();
                updateLayoutForMode(newMode);
            }
        });
        lyricView.setOnModeChangeListener(newMode -> updateLayoutForMode(newMode));
        btnOutfit.setOnClickListener(v -> toggleImmersiveMode());
        // 单击：弹出频谱选择面板（频谱可见时）或恢复显示频谱
        // 长按：切换频谱显示/隐藏
        btnSpectrum.setOnClickListener(v -> {
            if (!SettingsFragment.isSpectrumEnabled(requireContext())) return;
            if (spectrumView.isSpectrumVisible()) {
                showSpectrumPicker();
            } else {
                spectrumView.toggleVisibility();
            }
        });
        btnSpectrum.setOnLongClickListener(v -> {
            if (!SettingsFragment.isSpectrumEnabled(requireContext())) return true;
            spectrumView.toggleVisibility();
            return true;
        });
        btnBack.setOnClickListener(v -> {
            stopSpectrum();
            requireActivity().onBackPressed();
        });
        btnLyricSearch.setOnClickListener(v -> showLyricSearchPopup());

        // 注册广播接收器（监听切歌和播放状态）
            IntentFilter filter = new IntentFilter();
            filter.addAction(MusicPlayerService.ACTION_SONG_CHANGED);
            filter.addAction(MusicPlayerService.ACTION_PLAY_STATE_CHANGED);
            filter.addAction(MusicPlayerService.ACTION_PLAY_ORDER_CHANGED);
            filter.addAction(MusicPlayerService.ACTION_THEME_CHANGED);
            filter.addAction("com.jingxin.jingxinmusic.SPECTRUM_RESTART");
        CompatUtil.safeRegisterReceiver(requireContext(), songChangedReceiver, filter);

        // 绑定播放服务
        Intent serviceIntent = new Intent(requireContext(), MusicPlayerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(serviceIntent);
        } else {
            requireContext().startService(serviceIntent);
        }
        requireContext().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 加载封面
        loadCover();

        // 检查收藏状态
        checkFavoriteStatus();

        // 加载歌词
        fetchLyrics();

        // 开始进度更新
        uiHandler.post(progressRunnable);


        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从列表页返回时同步主题（列表页可能切换了日夜模式）
        boolean savedNight = requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE).getBoolean("isNight", true);
        if (savedNight != isNightMode) {
            isNightMode = savedNight;
            updateThemeUI();
            lyricView.setThemeMode(isNightMode
                    ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                    : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
        }
        // 频谱开关：关闭时隐藏频谱View并停止，开启时恢复
        boolean spectrumEnabled = SettingsFragment.isSpectrumEnabled(requireContext());
        if (!spectrumEnabled) {
            stopSpectrum();
            uiHandler.removeCallbacks(spectrumHeartbeat);
            spectrumView.setVisibility(View.GONE);
            btnSpectrum.setVisibility(View.GONE);
        } else {
            btnSpectrum.setVisibility(View.VISIBLE);
            if (bound && playerBinder != null && playerBinder.isPlaying()) {
                spectrumView.setPlaying(true);
                startSpectrumWithPermission();
            }
            // 启动频谱心跳检测
            lastSpectrumCallbackTime = android.os.SystemClock.elapsedRealtime();
            uiHandler.postDelayed(spectrumHeartbeat, 2000);
        }
        // 从列表页返回时，可能横竖屏已变化，需要重新检测并刷新唱臂
        if (tonearmView != null && tonearmView.getVisibility() == View.VISIBLE) {
            tonearmView.post(() -> {
                if (isActivityGone()) return;
                boolean wasLandscape = isLandscapeMode;
                detectAndApplyLandscapeMode();
                if (wasLandscape != isLandscapeMode) {
                    applyLayoutMode();
                    updateThemeUI();
                    updateLayoutForMode(lyricView.getDisplayMode());
                } else {
                    // 横竖没变但位置可能变了（窗口resize等），刷新唱臂位置
                    tonearmView.setLandscapeMode(isLandscapeMode);
                    tonearmView.refreshAngle();
                    updateTonearmPosition();
                }
            });
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 横竖屏切换时关闭频谱选择弹窗
        if (spectrumPickerPopup != null && spectrumPickerPopup.isShowing()) {
            spectrumPickerPopup.dismiss();
        }
        if (lyricSearchPopup != null && lyricSearchPopup.isShowing()) {
            lyricSearchPopup.dismiss();
        }
        // 延迟一帧再检测，确保 DisplayMetrics 已更新
        uiHandler.post(() -> {
            boolean wasLandscape = isLandscapeMode;
            detectAndApplyLandscapeMode();
            if (wasLandscape != isLandscapeMode) {
                applyLayoutMode();
                updateThemeUI();
                updateLayoutForMode(lyricView.getDisplayMode());
            }
        });
    }

    private boolean isActivityGone() {
        Activity a = getActivity();
        return a == null || a.isFinishing() || a.isDestroyed();
    }

    private void runOnUi(Runnable r) {
        Activity a = getActivity();
        if (a != null) a.runOnUiThread(r);
    }

    private void playSong() {
        if (bound && playerBinder != null && song != null) {
            // 确保 position 与实际 playlist 匹配（position 可能来自 allSongs 索引，
            // 但 setPlaylist 设置的播放列表可能更小，如 folder/收藏模式）
            List<Song> playlist = playerBinder.getPlaylist();
            if (playlist != null && position >= playlist.size()) {
                // 在新播放列表中查找当前歌曲的实际位置
                int realPos = -1;
                for (int i = 0; i < playlist.size(); i++) {
                    Song s = playlist.get(i);
                    if ((s.filePath != null && s.filePath.equals(song.filePath)) ||
                        (s.contentUri != null && s.contentUri.equals(song.contentUri))) {
                        realPos = i;
                        break;
                    }
                }
                position = realPos >= 0 ? realPos : 0;
            }
            playerBinder.playSong(song, position);
            btnPlayPause.setImageResource(R.drawable.ic_pause);
            coverView.startRotation();
            spectrumView.setPlaying(true);
            startSpectrumWithPermission();
            saveLastPlayed();
        }
    }

    /**
     * 轮播模式点击侧边封面切歌
     */
    /**
     * 从轮播模式切歌，参数为偏移量（delta）
     * delta=-1=上一曲, +1=下一曲, -2/+2跳两首
     */
    private void playSongAt(int delta) {
        if (bound && playerBinder != null) {
            int curIdx = playerBinder.getCurrentIndex();
            int newIdx = curIdx + delta;
            java.util.List<Song> playlist = playerBinder.getPlaylist();
            if (playlist != null && newIdx >= 0 && newIdx < playlist.size()) {
                Song newSong = playlist.get(newIdx);
                // 同步 allSongs 中的 position
                if (allSongs != null) {
                    int allPos = allSongs.indexOf(newSong);
                    if (allPos >= 0) position = allPos;
                }
                song = newSong;
                tvSongName.setText(song.title);
                tvArtist.setText(song.artist);
                tvTotalTime.setText(Song.formatDuration(song.duration));
                tvCurrentTime.setText("00:00");
                playerBinder.playSongAtPosition(newIdx);
                btnPlayPause.setImageResource(R.drawable.ic_pause);
                spectrumView.setPlaying(true);
                startSpectrumWithPermission();
                loadCover();
                fetchLyrics();
                checkFavoriteStatus();
                updateDownloadButtonVisibility();
                saveLastPlayed();
                if (currentScene.shouldRotateCover()) {
                    coverView.startRotation();
                }
            }
        }
    }

    /**
     * 同步轮播封面位置（切歌后调用）
     */
    private void syncCarouselPosition() {
        if (coverStyle != COVER_STYLE_CAROUSEL || sceneHelper == null || sceneHelper.carouselView == null) return;
        if (sceneHelper.carouselAdapter != null && bound && playerBinder != null) {
            java.util.List<com.jingxin.jingxinmusic.model.Song> playlist = playerBinder.getPlaylist();
            int idx = playerBinder.getCurrentIndex();
            sceneHelper.carouselAdapter.updatePosition(idx, sceneHelper.carouselView.getCards());
        }
    }

    /**
     * 同步轮播封面歌曲列表（进入轮播模式/播放列表变化时调用）
     */
    private void syncCarouselSongs() {
        if (sceneHelper == null || sceneHelper.carouselView == null || sceneHelper.carouselAdapter == null) return;
        if (bound && playerBinder != null) {
            java.util.List<com.jingxin.jingxinmusic.model.Song> playlist = playerBinder.getPlaylist();
            int idx = playerBinder.getCurrentIndex();
            sceneHelper.carouselAdapter.update(playlist, idx, sceneHelper.carouselView.getCards());
            sceneHelper.carouselView.requestLayoutCards();
        }
    }

    private void startSpectrumWithPermission() {
        if (!SettingsFragment.isSpectrumEnabled(requireContext())) return;
        // 使用 ContextCompat/ActivityCompat 避免直接调用 API 23+ 方法导致低版本 ART VerifyError
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSpectrum();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                androidx.core.app.ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.RECORD_AUDIO}, 100);
            }
            // API < 23 安装时即授权，不会走此分支
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSpectrum();
            }
        }
    }

    private void togglePlayPause() {
        if (bound && playerBinder != null) {
            playerBinder.togglePlayPause();
            updatePlayPauseButton(playerBinder.isPlaying());
        }
    }

    private void playPrevious() {
        if (bound && playerBinder != null) {
            playerBinder.playPrevious();
        }
    }

    private void playNext() {
        if (bound && playerBinder != null) {
            playerBinder.playNext();
        }
    }

    /**
     * 切换播放顺序：顺序 → 随机 → 单曲循环
     */
    private void togglePlayOrder() {
        if (bound && playerBinder != null) {
            int current = playerBinder.getPlayOrder();
            int next = (current + 1) % 3;
            playerBinder.setPlayOrder(next);
            updatePlayOrderIcon(next);
            String[] names = {"顺序播放", "随机播放", "单曲循环"};
            android.widget.Toast.makeText(requireContext(), names[next], android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void updatePlayOrderIcon(int order) {
        switch (order) {
            case MusicPlayerService.PLAY_ORDER_SEQUENTIAL:
                btnPlayOrder.setImageResource(R.drawable.ic_play_order_sequential);
                break;
            case MusicPlayerService.PLAY_ORDER_SHUFFLE:
                btnPlayOrder.setImageResource(R.drawable.ic_play_order_shuffle);
                break;
            case MusicPlayerService.PLAY_ORDER_REPEAT_ONE:
                btnPlayOrder.setImageResource(R.drawable.ic_play_order_repeat);
                break;
        }
    }

    /**
     * 切换收藏状态
     */
    private void toggleFavorite() {
        if (song == null) return;
        File favDir = com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(requireContext());

        if (isFavorite) {
            FavoriteManager.removeFavorite(favDir, song);
            isFavorite = false;
            btnFavorite.setImageResource(R.drawable.ic_favorite);
            applyButtonTheme(isNightMode);
            android.widget.Toast.makeText(requireContext(), "取消收藏", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            FavoriteManager.addFavorite(favDir, song);
            isFavorite = true;
            btnFavorite.setImageResource(R.drawable.ic_favorite_filled);
            applyButtonTheme(isNightMode);
            android.widget.Toast.makeText(requireContext(), "已收藏", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 检查当前歌曲是否已收藏
     */
    private void checkFavoriteStatus() {
        if (song == null) return;
        File favDir = com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(requireContext());
        isFavorite = FavoriteManager.isFavorite(favDir, song.title, song.artist);
        btnFavorite.setImageResource(isFavorite ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);
        applyButtonTheme(isNightMode);
    }

    /**
     * 保存B站歌曲到本地（离线保存）
     */
    private void saveBiliOffline() {
        if (song == null) return;

        // 只对B站音源显示下载按钮，点击时校验
        if (song.sourceType != Song.SOURCE_BILI &&
                !(song.filePath != null && song.filePath.startsWith("bili://"))) {
            android.widget.Toast.makeText(requireContext(), "仅支持B站音源离线保存",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查是否已保存
        if (com.jingxin.jingxinmusic.util.BiliOfflineSaver.isSaved(requireContext(), song)) {
            android.widget.Toast.makeText(requireContext(), "已保存过，无需重复保存",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // 禁用按钮防止重复点击
        btnDownload.setEnabled(false);
        btnDownload.setAlpha(0.5f);
        android.widget.Toast.makeText(requireContext(), "开始离线保存...",
                android.widget.Toast.LENGTH_SHORT).show();

        com.jingxin.jingxinmusic.util.BiliOfflineSaver.saveAsync(requireContext(), song,
                new com.jingxin.jingxinmusic.util.BiliOfflineSaver.SaveCallback() {
                    @Override
                    public void onSuccess(java.io.File m4aFile) {
                        runOnUi(() -> {
                            btnDownload.setEnabled(true);
                            btnDownload.setAlpha(1f);
                            android.widget.Toast.makeText(requireContext(),
                                    "已保存到 Download/music/",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onSkipped(String reason) {
                        runOnUi(() -> {
                            btnDownload.setEnabled(true);
                            btnDownload.setAlpha(1f);
                            android.widget.Toast.makeText(requireContext(),
                                    reason, android.widget.Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onFailed(String error) {
                        runOnUi(() -> {
                            btnDownload.setEnabled(true);
                            btnDownload.setAlpha(1f);
                            android.widget.Toast.makeText(requireContext(),
                                    "保存失败: " + error,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onProgress(int percent) {
                        // 暂不显示进度
                    }
                });
    }

    /**
     * 更新下载按钮可见性：仅B站音源显示
     */
    private void updateDownloadButtonVisibility() {
        if (btnDownload == null) return;
        if (song != null && song.sourceType == Song.SOURCE_BILI) {
            btnDownload.setVisibility(View.VISIBLE);
        } else {
            btnDownload.setVisibility(View.GONE);
        }
    }

    /**
     * 根据歌词模式更新布局——使用 CoverScene 策略
     */
    private void updateLayoutForMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode mode) {
        syncSceneState();
        // 横屏轮播：歌词锁定双行，强制修正
        if (coverStyle == COVER_STYLE_CAROUSEL && isLandscapeMode
                && mode != com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE) {
            lyricView.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE);
        }
        boolean isFull = mode == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
        currentScene.onLyricModeChanged(isFull);
    }

    /**
     * 沉浸模式下调整歌名和歌词区域的顶部 margin
     * 歌名歌手推到遮罩区域顶部，歌词紧跟其下
     */
    private void updateImmersiveLyricMargin(boolean isFullScreen) {
        if (lyricView == null) return;

        // 计算屏幕可用高度
        int screenHeight = getAvailableScreenHeight();

        // 歌名推到遮罩区起始位置（43%）
        int nameTopOffset = (int) (screenHeight * 0.43f);
        int density = (int) getResources().getDisplayMetrics().density;
        android.widget.LinearLayout.LayoutParams nameParams =
                (android.widget.LinearLayout.LayoutParams) tvSongName.getLayoutParams();
        nameParams.topMargin = nameTopOffset;
        tvSongName.setLayoutParams(nameParams);

        // 歌词紧跟歌名歌手下方，只需小 margin
        android.widget.LinearLayout.LayoutParams lyricParams =
                (android.widget.LinearLayout.LayoutParams) lyricView.getLayoutParams();
        lyricParams.topMargin = density * 8;
        lyricView.setLayoutParams(lyricParams);
    }

    /**
     * 恢复歌名和歌词区域原始 margin（非沉浸模式）
     * 轮播模式由 Scene 自行管理歌名 topMargin，此处不覆盖
     */
    private void resetLyricMargin() {
        if (lyricView == null) return;
        int density = (int) getResources().getDisplayMetrics().density;

        // 轮播模式：歌名 topMargin 由 Scene 管理，不覆盖
        if (coverStyle != COVER_STYLE_CAROUSEL) {
            // 恢复歌名原始 margin（竖屏16dp，横屏52dp：返回按钮底部位置）
            android.widget.LinearLayout.LayoutParams nameParams =
                    (android.widget.LinearLayout.LayoutParams) tvSongName.getLayoutParams();
            nameParams.topMargin = isLandscapeMode ? (density * 52) : (density * 16);
            tvSongName.setLayoutParams(nameParams);
        }

        // 恢复歌词原始 margin
        android.widget.LinearLayout.LayoutParams lyricParams =
                (android.widget.LinearLayout.LayoutParams) lyricView.getLayoutParams();
        lyricParams.topMargin = density * 10;
        lyricView.setLayoutParams(lyricParams);
    }

    /**
     * 获取窗口实际可用高度
     * 优先用根 FrameLayout 的实际测量高度，避免雷电/MuMu 模拟器 getDisplayMetrics() 返回全屏高度
     * 首次布局时 View 可能为0，回退到 getDisplayMetrics()
     */
    private int getAvailableScreenHeight() {
        return sceneHelper != null ? sceneHelper.getAvailableScreenHeight() : getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * 设置竖屏封面占位高度，把歌名推到封面下方
     */
    private void updateCoverPlaceholder() {
        int screenHeight = getAvailableScreenHeight();
        float density = getResources().getDisplayMetrics().density;
        int coverSize = (int) (screenHeight * 0.25f);
        int coverMarginTop = (int) (density * 56);
        int coverNameGap = (int) (density * 16);
        coverPlaceholder.setVisibility(View.VISIBLE);
        android.widget.LinearLayout.LayoutParams placeholderParams =
                (android.widget.LinearLayout.LayoutParams) coverPlaceholder.getLayoutParams();
        placeholderParams.height = coverMarginTop + coverSize + coverNameGap;
        placeholderParams.width = 1;
        coverPlaceholder.setLayoutParams(placeholderParams);
    }

    /**
     * 初始化 CoverScene 策略框架
     */
    private void initCoverScene() {
        android.widget.FrameLayout rootLayout = mRootView.findViewById(R.id.root_layout);
        sceneHelper = new CoverSceneHelper(
                rootLayout, blurBackground, coverView, tvSongName, tvArtist,
                lyricView, spectrumView, seekBar, tvCurrentTime, tvTotalTime,
                btnPlayPause, btnPrevious, btnNext, btnFavorite,
                infoPanel, coverPlaceholder, overlayView, whiteOverlay,
                immersiveDarkOverlay, immersiveOverlay,
                btnBack, btnSpectrum, btnOutfit,
                mRootView.findViewById(R.id.top_buttons_bar),
                mRootView.findViewById(R.id.control_buttons),
                mRootView.findViewById(R.id.progress_layout),
                mRootView.findViewById(R.id.right_buttons_group),
                tonearmView,
                getResources().getDisplayMetrics().density);
        sceneHelper.callback = new CoverSceneHelper.Callback() {
            @Override public void loadCover() { PlayerFragment.this.loadCover(); }
            @Override public void updateCoverPlaceholder() { PlayerFragment.this.updateCoverPlaceholder(); }
            @Override public void resetLyricMargin() { PlayerFragment.this.resetLyricMargin(); }
            @Override public void updateImmersiveLyricMargin(boolean isFullScreen) { PlayerFragment.this.updateImmersiveLyricMargin(isFullScreen); }
            @Override public void updateThemeUI() { PlayerFragment.this.updateThemeUI(); }
            @Override public void extractAndApplyDominantColor(Bitmap bitmap) {
                executor.execute(() -> {
                    int dominantColor = extractDominantColor(bitmap);
                    if (!isActivityGone()) {
                        uiHandler.post(() -> {
                            if (immersiveOverlay != null) {
                                immersiveOverlay.setDominantColor(dominantColor);
                            }
                            if (isLandscapeMode && coverBorderGradient != null) {
                                coverBorderGradient.setOverlayColor(immersiveOverlay.getOverlayColor());
                            }
                            if (isLandscapeMode && landscapeGradientOverlay != null) {
                                landscapeGradientOverlay.setOverlayColor(immersiveOverlay.getOverlayColor());
                            }
                        });
                    }
                });
            }
            @Override public void updateTonearmPosition() { PlayerFragment.this.updateTonearmPosition(); }
            @Override public void playSongAt(int pos) { PlayerFragment.this.playSongAt(pos); }
        };

        portraitClassic = new PortraitClassicScene(sceneHelper);
        portraitImmersive = new PortraitImmersiveScene(sceneHelper);
        portraitRecord = new PortraitRecordScene(sceneHelper);
        portraitCarousel = new PortraitCarouselScene(sceneHelper);
        landscapeClassic = new LandscapeClassicScene(sceneHelper);
        landscapeImmersive = new LandscapeImmersiveScene(sceneHelper);
        landscapeRecord = new LandscapeRecordScene(sceneHelper);
        landscapeCarousel = new LandscapeCarouselScene(sceneHelper);

        // 默认竖屏经典
        currentScene = portraitClassic;

        // 监听系统窗口 insets，获取状态栏实际高度（车机全屏时为0）
        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // API 30+：使用新 API，Android 16 edge-to-edge 模式下更可靠
                topInset = insets.getInsets(android.view.WindowInsets.Type.systemBars()).top;
            } else {
                // API 21-29：使用兼容 API
                topInset = insets.getSystemWindowInsetTop();
            }
            if (sceneHelper.systemTopInset != topInset) {
                sceneHelper.systemTopInset = topInset;
                // inset 变化时重新布局，让 applyButtonMargins 生效
                v.requestLayout();
            }
            return insets;
        });
    }

    /**
     * 根据当前 isLandscapeMode 和 coverStyle 切换到正确的 Scene
     * @return 是否发生了 Scene 切换
     */
    private boolean switchScene() {
        CoverScene target;
        if (coverStyle == COVER_STYLE_IMMERSIVE) {
            target = isLandscapeMode ? landscapeImmersive : portraitImmersive;
        } else if (coverStyle == COVER_STYLE_RECORD) {
            target = isLandscapeMode ? landscapeRecord : portraitRecord;
        } else if (coverStyle == COVER_STYLE_CAROUSEL) {
            target = isLandscapeMode ? landscapeCarousel : portraitCarousel;
        } else {
            target = isLandscapeMode ? landscapeClassic : portraitClassic;
        }
        if (target != currentScene) {
            currentScene.onStyleExit();
            currentScene.exit();
            currentScene = target;
            currentScene.enter();
            currentScene.onStyleEnter();
            return true;
        }
        return false;
    }

    /**
     * 同步 sceneHelper 状态（在调用 scene 方法前调用）
     */
    private void syncSceneState() {
        sceneHelper.isNightMode = isNightMode;
        sceneHelper.isPlaying = bound && playerBinder != null && playerBinder.isPlaying();
        sceneHelper.isLandscapeMode = isLandscapeMode;
        sceneHelper.playerBinder = playerBinder;
        sceneHelper.executor = executor;
        // 双向同步：helper可能由scene创建了实例，不能被activity的null覆盖
        if (coverBorderGradient != null) {
            sceneHelper.coverBorderGradient = coverBorderGradient;
        } else if (sceneHelper.coverBorderGradient != null) {
            coverBorderGradient = sceneHelper.coverBorderGradient;
        }
        if (landscapeGradientOverlay != null) {
            sceneHelper.landscapeGradientOverlay = landscapeGradientOverlay;
        } else if (sceneHelper.landscapeGradientOverlay != null) {
            landscapeGradientOverlay = sceneHelper.landscapeGradientOverlay;
        }
    }

     /**
       * 检测并应用横屏布局模式
       * 触发条件：实际宽度 > 高度 * 1.1
      * 横屏布局：左65%信息区 + 右35%封面区
      */
    private void detectAndApplyLandscapeMode() {
        int width = getLayoutWidth();
        int height = getAvailableScreenHeight();
        boolean newLandscape = (width > height * 1.1f);
        isLandscapeMode = newLandscape;
    }

    /**
     * 应用当前布局模式（横屏/竖屏）——使用 CoverScene 策略
     */
    private void applyLayoutMode() {
        syncSceneState();
        switchScene();
        int width = getLayoutWidth();
        int height = getAvailableScreenHeight();
        currentScene.layout(width, height);
        immersiveOverlay.setLandscapeMode(isLandscapeMode);
        // 切换后需要重新加载封面的模式
        if (currentScene.needsReloadCover()) {
            loadCover();
        }
        // 唱臂横屏模式和位置更新
        if (tonearmView != null) {
            tonearmView.setLandscapeMode(isLandscapeMode);
            updateTonearmPosition();
            tonearmView.refreshAngle();
        }
    }

    /**
     * 获取根 FrameLayout 的实际宽度，首次布局时可能为0则回退到 screenWidth
     */
    private int getLayoutWidth() {
        return sceneHelper != null ? sceneHelper.getLayoutWidth() : getResources().getDisplayMetrics().widthPixels;
    }

    private void updatePlayPauseButton(boolean isPlaying) {
        if (isPlaying) {
            btnPlayPause.setImageResource(R.drawable.ic_pause);
            if (currentScene.shouldRotateCover()) {
                coverView.startRotation();
            }
            spectrumView.setPlaying(true);
            currentScene.onPlayingStateChanged(true);
            // 恢复播放时重启频谱数据采集（暂停期间 Visualizer 可能已失效）
            if (SettingsFragment.isSpectrumEnabled(requireContext())) {
                startSpectrumWithPermission();
            }
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play);
            coverView.stopRotation();
            spectrumView.setPlaying(false);
            currentScene.onPlayingStateChanged(false);
        }
    }

    /**
     * 在线获取歌词
     */
    private void fetchLyrics() {
        if (song == null || lyricView == null) return;
        lyricView.clearLyric();

        String title = Song.cleanSongTitle(song.title, song.artist);
        String artist = song.artist;
        if ("<unknown>".equals(artist)) artist = "";

        File lyricsDir = new File(requireContext().getExternalFilesDir(null), "lyrics");

        LyricFetcher.loadLyric(title, artist, song.filePath, lyricsDir, new LyricFetcher.LyricCallback() {
            @Override
            public void onLyricFetched(KrcParser.LyricData lyricData) {
                if (lyricView != null && lyricData != null && lyricData.lines != null && !lyricData.lines.isEmpty()) {
                    uiHandler.post(() -> {
                        lyricView.setLyricData(lyricData);
                    });
                } else {
                }
            }

            @Override
            public void onError(String errorMessage) {
            }
        }, requireContext(), song.title);
    }

    /**
     * 加载封面：先本地文件，没有则在线获取并保存
     */
    private void notifyMetadataUpdate() {
        Intent intent = new Intent(requireContext(), com.jingxin.jingxinmusic.service.MusicPlayerService.class);
        intent.setAction(com.jingxin.jingxinmusic.service.MusicPlayerService.ACTION_UPDATE_METADATA);
        requireContext().startService(intent);
    }

    private void loadCover() {
        // 切歌时保持旧封面，避免闪烁——只有首次进入（无旧封面）才设默认封面
        if (!hasCoverLoaded) {
            coverView.setImageResource(R.drawable.ic_music_icon);
            applyDefaultCoverBlur();
        }
        // 横屏沉浸下，切换间隙隐藏 foreground 渐变，避免默认封面+渐变的闪烁
        if (coverStyle == COVER_STYLE_IMMERSIVE && isLandscapeMode) {
            coverView.setForeground(null);
        }

        com.jingxin.jingxinmusic.util.CoverLoader.load(requireContext(), song, 600, 600,
                true, executor, new com.jingxin.jingxinmusic.util.CoverLoader.CoverCallback() {
            @Override
            public void onCoverLoaded(Bitmap bitmap) {
                if (isActivityGone()) return;
                setCoverBitmap(bitmap);
            }

            @Override
            public void onCoverFailed() {
                if (isActivityGone()) return;
                // 所有封面来源都失败，用默认封面图标生成模糊背景
                applyDefaultCoverBlur();
            }
        });
    }

    /**
     * 将默认封面矢量图(ic_music_icon)转为Bitmap并设置模糊背景
     * 确保无在线封面时播放页面也有模糊背景效果
     */
    private void applyDefaultCoverBlur() {
        if (isActivityGone()) return;
        executor.execute(() -> {
            try {
                Drawable drawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_music_icon);
                if (drawable == null) return;
                // 矢量图需要指定尺寸转为Bitmap
                int size = 256;
                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(canvas);
                if (!isActivityGone()) {
                    uiHandler.post(() -> setCoverBitmap(bitmap));
                }
            } catch (Exception e) {
                Log.w(TAG, "默认封面模糊背景生成失败", e);
            }
        });
    }

    private void setCoverBitmap(Bitmap bitmap) {
        if (bitmap == null || isActivityGone()) return;
        hasCoverLoaded = true;
        syncSceneState();
        currentScene.setCover(bitmap);
        // 通知 Service 更新 MediaSession metadata（含封面）
        notifyMetadataUpdate();
    }

    /**
     * 从封面提取主色调（简单采样法）
     * 将图片缩小后采样中心区域像素，计算加权平均色
     * @param bitmap 封面图片
     * @return 主色调（ARGB）
     */
    private int extractDominantColor(Bitmap bitmap) {
        // 缩小到 50x50 采样
        int sampleSize = Math.max(1, Math.min(bitmap.getWidth(), bitmap.getHeight()) / 50);
        int w = bitmap.getWidth() / sampleSize;
        int h = bitmap.getHeight() / sampleSize;
        if (w < 1) w = 1;
        if (h < 1) h = 1;
        Bitmap sampled = Bitmap.createScaledBitmap(bitmap, w, h, true);
        
        int[] pixels = new int[w * h];
        sampled.getPixels(pixels, 0, w, 0, 0, w, h);
        sampled.recycle();
        
        // 只采样中心 60% 区域，避免边缘黑边影响
        int startX = w / 5;
        int endX = w * 4 / 5;
        int startY = h / 5;
        int endY = h * 4 / 5;
        
        long rSum = 0, gSum = 0, bSum = 0, count = 0;
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int pixel = pixels[y * w + x];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                // 忽略过暗和过亮的像素
                if (r + g + b > 60 && r + g + b < 720) {
                    rSum += r;
                    gSum += g;
                    bSum += b;
                    count++;
                }
            }
        }
        
        if (count == 0) return ThemeColors.dominantColorFallback();
        
        int r = (int) (rSum / count);
        int g = (int) (gSum / count);
        int b = (int) (bSum / count);
        return Color.rgb(r, g, b);
    }

    /**
     * 显示播放历史列表
     */
    private void showHistoryDialog() {
        executor.execute(() -> {
            File historyDir = new File(requireContext().getExternalFilesDir(null), "history");
            List<HistoryManager.HistoryItem> history = HistoryManager.loadHistory(historyDir);

            if (history.isEmpty()) {
                uiHandler.post(() -> {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("播放历史")
                            .setMessage("暂无播放记录")
                            .setPositiveButton("确定", null)
                            .show();
                });
                return;
            }

            uiHandler.post(() -> {
                String[] items = new String[history.size()];
                for (int i = 0; i < history.size(); i++) {
                    HistoryManager.HistoryItem item = history.get(i);
                    items[i] = item.getDisplayName();
                }

                new AlertDialog.Builder(requireContext())
                        .setTitle("播放历史")
                        .setItems(items, (dialog, which) -> {
                            HistoryManager.HistoryItem item = history.get(which);
                            Song s = item.song;

                            song = s;
                            tvSongName.setText(s.title);
                            tvArtist.setText(s.artist);
                            tvCurrentTime.setText("00:00");
                            tvTotalTime.setText(Song.formatDuration(s.duration));
                            loadCover();
                            fetchLyrics();

                            if (bound && playerBinder != null) {
                                List<Song> historySongs = new ArrayList<>();
                                for (HistoryManager.HistoryItem h : history) {
                                    historySongs.add(h.song);
                                }
                                playerBinder.setPlaylist(historySongs);
                                playerBinder.playSong(s, which);
                                btnPlayPause.setImageResource(R.drawable.ic_pause);
                                coverView.startRotation();
                                startSpectrumWithPermission();
                            }
                        })
                        .setNegativeButton("清空历史", (dialog, which) -> {
                            HistoryManager.clearHistory(historyDir);
                        })
                        .show();
            });
        });
    }

    /**
     * 循环切换封面风格：经典 → 沉浸 → 唱片机 → 轮播 → 经典
     */
    private void toggleImmersiveMode() {
        int prevStyle = coverStyle;
        coverStyle = (coverStyle + 1) % 4;
        requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE).edit().putInt("cover_style", coverStyle).apply();

        // 沉浸模式下圆环/扩散圆环/波浪圆环不可用，循环跳过
        if (!currentScene.shouldShowSpectrumButton(spectrumView.getCurrentStyle())) {
            while (!currentScene.shouldShowSpectrumButton(spectrumView.getCurrentStyle())) {
                spectrumView.switchStyle();
            }
        }

        // 退出上一个风格的特殊状态
        if (prevStyle == COVER_STYLE_IMMERSIVE) {
            // 退出沉浸：恢复歌词位置等
        }

        syncSceneState();
        switchScene();
        int width = getLayoutWidth();
        int height = getAvailableScreenHeight();
        currentScene.layout(width, height);

        // 重新加载封面
        loadCover();

        // 经典/唱片机模式下同步封面旋转状态（必须在loadCover之后）
        if (coverStyle == COVER_STYLE_CLASSIC || coverStyle == COVER_STYLE_RECORD) {
            boolean isCurrentlyPlaying = bound && playerBinder != null && playerBinder.isPlaying();
            coverView.stopAndResetRotation();
            if (isCurrentlyPlaying) {
                coverView.startRotation();
            }
        }

        // 轮播模式下同步歌曲列表和当前位置
        if (coverStyle == COVER_STYLE_CAROUSEL) {
            syncCarouselSongs();
        }

        // 同步歌词模式到沉浸遮罩
        if (coverStyle == COVER_STYLE_IMMERSIVE && lyricView != null) {
            immersiveOverlay.setFullScreenMode(
                    lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL);
        }

        String[] styleNames = {"经典模式", "沉浸模式", "唱片机模式", "轮播模式"};
        android.widget.Toast.makeText(requireContext(), styleNames[coverStyle],
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 动态调整唱臂位置和尺寸
     * 
     * 横屏：pivot在View底部居中，View底边对齐封面上边沿，View水平居中于封面
     *       View尺寸=封面尺寸×1.2（方形，足够容纳旋转45°）
     * 
     * 竖屏：pivot在View左上角，View左边对齐封面右边缘，View顶边对齐封面上边沿
     */
    private void updateTonearmPosition() {
        if (tonearmView == null || coverView == null) return;
        tonearmView.post(() -> {
            if (isActivityGone()) return;
            int coverW = coverView.getWidth();
            int coverH = coverView.getHeight();
            if (coverW <= 0 || coverH <= 0) return;

            // 用绝对坐标定位，避免gravity导致getLeft/getTop不准
            int[] coverLoc = new int[2];
            int[] rootLoc = new int[2];
            coverView.getLocationOnScreen(coverLoc);
            android.widget.FrameLayout rootView = mRootView.findViewById(R.id.root_layout);
            rootView.getLocationOnScreen(rootLoc);
            // 封面中心相对于根布局
            float coverCenterX = coverLoc[0] - rootLoc[0] + coverW / 2f;
            float coverCenterY = coverLoc[1] - rootLoc[1] + coverH / 2f;

            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) tonearmView.getLayoutParams();
            lp.gravity = 0;

            // ---- 唱臂几何常量（与TonearmView.onDraw一致） ----
            float unit = isLandscapeMode ? coverW / 21f : coverW / 18f;
            float armLength = 5.5f * unit;
            float bendLength = 1.5f * unit;
            float bendAngle = 35f;
            float headHeight = 1.3f * unit;
            float stylusLength = 0.6f * unit;
            float cwDistance = 1.8f * unit;
            float cwRadius = 0.7f * unit;

            // pivot在View内偏移（与TonearmView.onDraw一致）
            float pivotOffsetY = cwDistance + cwRadius + 0.3f * unit;
            float pivotRightSpace = 0.707f * cwDistance + cwRadius + 0.5f * unit;

            // ---- pivot到唱针尖端的偏移（未旋转时，向下为正Y，向右为正X） ----
            double bendRad = Math.toRadians(bendAngle);
            float totalBendDist = bendLength + headHeight + stylusLength;
            float needleDx = (float) Math.sin(bendRad) * totalBendDist;
            float needleDy = armLength + (float) Math.cos(bendRad) * totalBendDist;

            // ---- 播放时唱针目标位置 ----
            float vinylRadius = coverW / 2f;
            float coverRadius = coverW * 2f / 3f / 2f;
            // 横屏唱针在黑胶环偏内侧(0.3)，竖屏唱针在黑胶环中间(0.5)
            float needleTargetR = coverRadius + (vinylRadius - coverRadius) * (isLandscapeMode ? 0.3f : 0.5f);

            if (isLandscapeMode) {
                // 横屏：主臂杆对齐封面中心线，pivot X = coverCenterX
                float pivotScreenX = coverCenterX;
                float pivotScreenY = coverCenterY - needleTargetR - needleDy;
                // View尺寸
                int armW = (int) (coverW * 1.2f);
                int armH = (int) (pivotOffsetY + armLength + totalBendDist + cwDistance + cwRadius + unit);
                // pivot在View内: (armW/2, pivotOffsetY)
                lp.width = armW;
                lp.height = armH;
                lp.leftMargin = (int) (pivotScreenX - armW / 2f);
                lp.topMargin = (int) (pivotScreenY - pivotOffsetY);
            } else {
                // 竖屏：唱臂在封面右侧，暂停0°垂直，播放45°唱针落入黑胶4点钟方向
                float playAngle = 45f;
                double playRad = Math.toRadians(playAngle);
                float rotatedNeedleDy = (float)(-needleDx * Math.sin(playRad) + needleDy * Math.cos(playRad));
                // 4点方向Y
                float needleTargetY = coverCenterY + needleTargetR * 0.5f;
                // pivot X：封面右边缘 + 偏移让唱针落在黑胶中间
                float pivotScreenX = coverLoc[0] - rootLoc[0] + coverW + needleTargetR * 0.35f;
                float pivotScreenY = needleTargetY - rotatedNeedleDy - unit;
                // View尺寸
                int armW = (int) (coverW + pivotRightSpace + cwDistance + cwRadius + unit);
                int armH = (int) (pivotOffsetY + armLength + totalBendDist + cwDistance + cwRadius + unit);
                // pivot在View内: (armW - pivotRightSpace, pivotOffsetY)
                float pivotViewX = armW - pivotRightSpace;
                lp.width = armW;
                lp.height = armH;
                lp.leftMargin = (int) (pivotScreenX - pivotViewX);
                lp.topMargin = (int) (pivotScreenY - pivotOffsetY);
            }

            tonearmView.setCoverSize(coverW);
            tonearmView.setLayoutParams(lp);
            tonearmView.bringToFront();
        });
    }

    /**
     * 弹出频谱选择浮窗面板（竖屏2列5行，横屏5列2行）
     */
    private void showSpectrumPicker() {
        if (spectrumPickerPopup != null && spectrumPickerPopup.isShowing()) {
            spectrumPickerPopup.dismiss();
            return;
        }

        String[] names = com.jingxin.jingxinmusic.view.SpectrumView.STYLE_NAMES;
        int currentStyle = spectrumView.getCurrentStyle();
        float density = getResources().getDisplayMetrics().density;
        Context ctx = requireContext();

        // 外层容器
        android.widget.LinearLayout container = new android.widget.LinearLayout(ctx);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(
                (int)(10 * density), (int)(10 * density),
                (int)(10 * density), (int)(10 * density));
        // 左直角右圆角背景
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        float r = 16 * density;
        bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});  // 左上0, 右上r, 右下r, 左下0
        bg.setColor(Color.argb(51, 30, 30, 30));  // 透明度20%
        container.setBackground(bg);

        // 标题
        android.widget.TextView title = new android.widget.TextView(ctx);
        title.setText("频谱选择");
        title.setTextColor(ThemeColors.sparkColor(isNightMode));
        title.setTextSize(18);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, (int)(12 * density));
        container.addView(title);

        // 竖屏2列5行，横屏5列2行
        boolean isLandscape = getLayoutWidth() > getAvailableScreenHeight() * 1.2f;
        int columns = isLandscape ? 5 : 2;

        int itemWidth = isLandscape
                ? (int)(120 * density)
                : (int)(140 * density);

        android.widget.GridLayout grid = new android.widget.GridLayout(ctx);
        grid.setColumnCount(columns);

        for (int i = 0; i < names.length; i++) {
            final int style = i;
            android.widget.TextView item = new android.widget.TextView(ctx);
            item.setText(names[i]);
            item.setTextSize(16);
            item.setGravity(android.view.Gravity.CENTER);
            item.setPadding(
                    (int)(20 * density), (int)(14 * density),
                    (int)(20 * density), (int)(14 * density));

            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = itemWidth;
            lp.height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT;
            lp.setMargins((int)(6 * density), (int)(4 * density), (int)(6 * density), (int)(4 * density));
            item.setLayoutParams(lp);

            // 沉浸模式下圆环类灰色不可选
            boolean isOverlay = com.jingxin.jingxinmusic.view.SpectrumView.isOverlayStyle(style);
            boolean disabled = !currentScene.shouldShowSpectrumButton(style);

            if (disabled) {
                item.setTextColor(ThemeColors.SPECTRUM_POPUP_DISABLED_TEXT);
                item.setBackgroundColor(Color.argb(153, 42, 42, 42));    // 透明度60%
            } else if (i == currentStyle) {
                // 当前选中：青色发光
                item.setTextColor(ThemeColors.SPECTRUM_POPUP_SELECTED_TEXT);
                item.setBackgroundColor(Color.argb(153, 0, 230, 180));  // 透明度60%
            } else {
                item.setTextColor(ThemeColors.SPECTRUM_POPUP_NORMAL_TEXT);
                item.setBackgroundColor(Color.argb(153, 68, 68, 68));   // 透明度60%
            }

            item.setOnClickListener(v -> {
                if (disabled) return;
                boolean wasRing = spectrumView.isCoverOverlayMode();
                spectrumView.setStyle(style);
                boolean isRing = spectrumView.isCoverOverlayMode();
                if (wasRing != isRing) {
                    int w = getLayoutWidth();
                    int h = getAvailableScreenHeight();
                    currentScene.layout(w, h);
                }
                spectrumPickerPopup.dismiss();
            });

            grid.addView(item);
        }
        container.addView(grid);

        spectrumPickerPopup = new android.widget.PopupWindow(container,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                true);
        spectrumPickerPopup.setOutsideTouchable(true);
        spectrumPickerPopup.setElevation(8 * density);
        // 左边距0，垂直居中
        spectrumPickerPopup.showAtLocation(btnSpectrum, android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL,
                0, 0);
    }

    /**
     * 弹出歌词搜索浮窗：加载状态 → 候选列表 → 点击下载
     */
    private void showLyricSearchPopup() {
        if (lyricSearchPopup != null && lyricSearchPopup.isShowing()) {
            lyricSearchPopup.dismiss();
            return;
        }
        if (song == null) {
            android.widget.Toast.makeText(requireContext(), "无歌曲信息", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        Context ctx = requireContext();

        // 外层容器
        android.widget.LinearLayout container = new android.widget.LinearLayout(ctx);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(
                (int)(12 * density), (int)(12 * density),
                (int)(12 * density), (int)(12 * density));
        // 半透明深色背景，竖屏四角圆角，横屏左直角右圆角
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        float r = 16 * density;
        if (isLandscapeMode) {
            bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        } else {
            bg.setCornerRadius(r);
        }
        bg.setColor(Color.argb(102, 30, 30, 30));  // 透明度40%
        container.setBackground(bg);

        // 标题
        android.widget.TextView title = new android.widget.TextView(ctx);
        String cleanTitle = Song.cleanSongTitle(song.title, song.artist);
        title.setText("搜索「" + (cleanTitle.length() > 12 ? cleanTitle.substring(0, 12) + "…" : cleanTitle) + "」歌词");
        title.setTextColor(ThemeColors.sparkColor(isNightMode));
        title.setTextSize(16);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, (int)(10 * density));
        container.addView(title);

        // 加载提示
        android.widget.ProgressBar loading = new android.widget.ProgressBar(ctx);
        android.widget.LinearLayout loadingRow = new android.widget.LinearLayout(ctx);
        loadingRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        loadingRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView loadingText = new android.widget.TextView(ctx);
        loadingText.setText("搜索中…");
        loadingText.setTextColor(0xFFCCCCCC);
        loadingText.setTextSize(14);
        loadingText.setPadding((int)(8 * density), 0, 0, 0);
        loadingRow.addView(loading);
        loadingRow.addView(loadingText);
        android.widget.LinearLayout.LayoutParams loadingLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        loadingLp.gravity = android.view.Gravity.CENTER;
        loadingRow.setLayoutParams(loadingLp);
        container.addView(loadingRow);

        // 候选列表容器（ScrollView + LinearLayout）
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout listContainer = new android.widget.LinearLayout(ctx);
        listContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
        scroll.addView(listContainer);
        container.addView(scroll);

        // 空结果提示（默认隐藏）
        android.widget.TextView emptyText = new android.widget.TextView(ctx);
        emptyText.setText("未找到歌词候选");
        emptyText.setTextColor(0xFFCCCCCC);
        emptyText.setTextSize(14);
        emptyText.setGravity(android.view.Gravity.CENTER);
        emptyText.setPadding(0, (int)(16 * density), 0, (int)(16 * density));
        emptyText.setVisibility(android.view.View.GONE);
        container.addView(emptyText);

        // 创建弹窗：竖屏宽度80%水平居中，横屏宽度65%左对齐
        int[] songNameLoc = new int[2];
        tvSongName.getLocationOnScreen(songNameLoc);
        int[] spectrumLoc = new int[2];
        spectrumView.getLocationOnScreen(spectrumLoc);
        int popupTop = songNameLoc[1];
        int spectrumBottom = spectrumLoc[1] + spectrumView.getHeight();
        int popupHeight = spectrumBottom - popupTop;
        if (popupHeight < (int)(200 * density)) popupHeight = (int)(200 * density);
        int screenWidth = mRootView.getWidth();
        float widthRatio = isLandscapeMode ? 0.65f : 0.8f;
        int popupWidth = (int)(screenWidth * widthRatio);

        lyricSearchPopup = new android.widget.PopupWindow(container,
                popupWidth,
                popupHeight,
                true);
        lyricSearchPopup.setOutsideTouchable(true);
        lyricSearchPopup.setElevation(8 * density);
        if (isLandscapeMode) {
            lyricSearchPopup.showAtLocation(btnLyricSearch,
                    android.view.Gravity.TOP | android.view.Gravity.START, 0, popupTop);
        } else {
            lyricSearchPopup.showAtLocation(btnLyricSearch,
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL, 0, popupTop);
        }

        // 异步搜索（传入当前歌曲时长用于排序，优先用ExoPlayer真实时长）
        final String searchTitle = cleanTitle;
        long realDuration = 0;
        if (bound && playerBinder != null) {
            int dur = playerBinder.getDuration();
            if (dur > 0) realDuration = dur;
        }
        if (realDuration == 0 && song != null) realDuration = song.duration;
        final long songDuration = realDuration;
        new Thread(() -> {
            java.util.List<LyricFetcher.LyricCandidate> candidates =
                    LyricFetcher.searchLyricCandidates(searchTitle, songDuration);
            uiHandler.post(() -> {
                if (lyricSearchPopup == null || !lyricSearchPopup.isShowing()) return;

                // 移除加载提示
                container.removeView(loadingRow);

                if (candidates.isEmpty()) {
                    emptyText.setVisibility(android.view.View.VISIBLE);
                    scroll.setVisibility(android.view.View.GONE);
                    return;
                }

                scroll.setVisibility(android.view.View.VISIBLE);

                // 找到时长最接近的候选索引（已排序，第一项即最接近）
                long bestDiff = Long.MAX_VALUE;
                int bestIdx = -1;
                if (songDuration > 0) {
                    for (int i = 0; i < candidates.size(); i++) {
                        long dur = candidates.get(i).durationMs;
                        if (dur > 0) {
                            bestDiff = Math.abs(dur - songDuration);
                            bestIdx = i;
                            break;
                        }
                    }
                }

                for (int idx = 0; idx < candidates.size(); idx++) {
                    final LyricFetcher.LyricCandidate cand = candidates.get(idx);
                    android.widget.TextView item = new android.widget.TextView(ctx);
                    String displayName = cand.title;
                    if (cand.artist != null && !cand.artist.isEmpty()) {
                        displayName += " - " + cand.artist;
                    }
                    String sourceTag = "kugou".equals(cand.source) ? "酷狗" : "网易云";
                    // 拼接时长
                    String durationStr = "";
                    if (cand.durationMs > 0) {
                        int totalSec = (int)(cand.durationMs / 1000);
                        durationStr = String.format("%d:%02d", totalSec / 60, totalSec % 60);
                    }
                    // 第一项且时长差<5秒，标记"最佳"
                    boolean isBestMatch = (idx == bestIdx && bestDiff < 5000);
                    String suffix = "  [" + sourceTag + "]";
                    if (!durationStr.isEmpty()) suffix += "  " + durationStr;
                    if (isBestMatch) suffix += "  ★";
                    item.setText(displayName + suffix);
                    item.setTextSize(13);
                    item.setTextColor(isBestMatch ? ThemeColors.sparkColor(isNightMode) : 0xFFDDDDDD);
                    item.setSingleLine(true);
                    item.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    item.setPadding(
                            (int)(12 * density), (int)(10 * density),
                            (int)(12 * density), (int)(10 * density));

                    android.graphics.drawable.GradientDrawable itemBg = new android.graphics.drawable.GradientDrawable();
                    if (isBestMatch) {
                        itemBg.setColor(Color.argb(51, 0, 230, 180));  // 最佳匹配背景高亮
                    } else {
                        itemBg.setColor(Color.argb(30, 68, 68, 68));
                    }
                    itemBg.setCornerRadius(8 * density);
                    item.setBackground(itemBg);
                    android.widget.LinearLayout.LayoutParams itemLp = new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    itemLp.setMargins(0, (int)(3 * density), 0, (int)(3 * density));
                    item.setLayoutParams(itemLp);

                    item.setOnClickListener(v -> {
                        lyricSearchPopup.dismiss();
                        android.widget.Toast.makeText(requireContext(),
                                "下载歌词中…", android.widget.Toast.LENGTH_SHORT).show();

                        File lyricsDir = new File(requireContext().getExternalFilesDir(null), "lyrics");
                        LyricFetcher.downloadLyricByCandidate(cand, lyricsDir, song.title,
                            new LyricFetcher.LyricCallback() {
                                @Override
                                public void onLyricFetched(KrcParser.LyricData lyricData) {
                                    uiHandler.post(() -> {
                                        if (lyricView != null && lyricData != null
                                                && lyricData.lines != null && !lyricData.lines.isEmpty()) {
                                            lyricView.setLyricData(lyricData);
                                            android.widget.Toast.makeText(requireContext(),
                                                    "歌词已更新", android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                                @Override
                                public void onError(String errorMessage) {
                                    uiHandler.post(() ->
                                        android.widget.Toast.makeText(requireContext(),
                                                "歌词下载失败: " + errorMessage,
                                                android.widget.Toast.LENGTH_SHORT).show());
                                }
                            }, requireContext());
                    });

                    listContainer.addView(item);
                }
            });
        }, "LyricSearch").start();
    }

    private void toggleTheme() {
        isNightMode = !isNightMode;
        requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE).edit()
                .putBoolean("isNight", isNightMode)
                .putBoolean("amapTriggered", false)  // 手动切换，暂停高德同步
                .apply();
        updateThemeUI();
        if (lyricView != null) {
            lyricView.setThemeMode(isNightMode
                    ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                    : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
        }
        android.widget.Toast.makeText(requireContext(), isNightMode ? "夜间模式" : "白天模式", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void updateThemeUI() {
        // 频谱日夜模式
        if (spectrumView != null) {
            spectrumView.setNightMode(isNightMode);
        }
        // 唱臂日夜主题 + 横屏模式
        if (tonearmView != null) {
            tonearmView.setNightMode(isNightMode);
            tonearmView.setLandscapeMode(isLandscapeMode);
        }

        if (coverStyle == COVER_STYLE_IMMERSIVE) {
            // 沉浸模式：用沉浸遮罩替代普通遮罩
            // 先设状态再VISIBLE，防止状态未就绪时触发绘制
            immersiveOverlay.setLandscapeMode(isLandscapeMode);
            immersiveOverlay.setNightMode(isNightMode);
            immersiveOverlay.setVisibility(View.VISIBLE);
            // 横屏沉浸：同步更新封面边缘渐变颜色和渐变过渡层颜色
            if (isLandscapeMode && coverBorderGradient != null) {
                coverBorderGradient.setOverlayColor(immersiveOverlay.getOverlayColor());
            }
            if (isLandscapeMode && landscapeGradientOverlay != null) {
                landscapeGradientOverlay.setOverlayColor(immersiveOverlay.getOverlayColor());
            }
            overlayView.setVisibility(View.GONE);
            whiteOverlay.setVisibility(View.GONE);
            // 同步夜间暗层
            immersiveDarkOverlay.setVisibility(isNightMode ? View.VISIBLE : View.GONE);
            blurBackground.setAlpha(1.0f);

            if (isNightMode) {
                applyTextTheme(true);
                applyButtonTheme(true);
            } else {
                applyTextTheme(false);
                applyButtonTheme(false);
            }
        } else {
            // 非沉浸模式：原有逻辑
            immersiveOverlay.setVisibility(View.GONE);

            if (isNightMode) {
                // 夜间模式：黑色背景，白色文字
                blurBackground.setAlpha(0.6f);
                blurBackground.setVisibility(View.VISIBLE);
                whiteOverlay.setVisibility(View.GONE);
                overlayView.setVisibility(View.VISIBLE);
                overlayView.setBackgroundColor(ThemeColors.nightOverlay());
                applyTextTheme(true);
                applyButtonTheme(true);
            } else {
                // 白天模式：渐变遮罩背景，深色文字
                blurBackground.setAlpha(0.5f);
                blurBackground.setVisibility(View.VISIBLE);
                whiteOverlay.setVisibility(View.VISIBLE);
                whiteOverlay.setAlpha(0.7f);
                overlayView.setVisibility(View.GONE);
                applyTextTheme(false);
                applyButtonTheme(false);
            }
        }
    }

    /**
     * 统一设置文字主题颜色
     */
    private void applyTextTheme(boolean isNight) {
        if (isNight) {
            tvSongName.setTextColor(ThemeColors.nightTextPrimary());
            tvArtist.setTextColor(ThemeColors.nightTextPrimary());
            tvCurrentTime.setTextColor(ThemeColors.nightTextPrimary());
            tvTotalTime.setTextColor(ThemeColors.nightTextPrimary());
        } else {
            tvSongName.setTextColor(ThemeColors.dayTextPrimary());
            tvArtist.setTextColor(ThemeColors.dayTextPrimary());
            tvCurrentTime.setTextColor(ThemeColors.dayTextPrimary());
            tvTotalTime.setTextColor(ThemeColors.dayTextPrimary());
        }
    }

    /**
     * 统一设置按钮主题颜色
     */
    private void applyButtonTheme(boolean isNight) {
        ImageView[] buttons = {btnPlayPause, btnPrevious, btnNext,
                btnHistory, btnPlayOrder, btnBack, btnSpectrum, btnOutfit, btnDownload, btnLyricSearch};
        if (isNight) {
            for (ImageView btn : buttons) btn.clearColorFilter();
            // 收藏按钮：已收藏用红色，未收藏清除滤镜
            if (isFavorite) {
                btnFavorite.setColorFilter(ThemeColors.FAVORITE_RED, PorterDuff.Mode.SRC_IN);
            } else {
                btnFavorite.clearColorFilter();
            }
            applySeekBarThemeColor(ThemeColors.nightTextPrimary());
        } else {
            int buttonColor = ThemeColors.dayTextPrimary();
            for (ImageView btn : buttons) btn.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            // 收藏按钮：已收藏用红色，未收藏用通用色
            if (isFavorite) {
                btnFavorite.setColorFilter(ThemeColors.FAVORITE_RED, PorterDuff.Mode.SRC_IN);
            } else {
                btnFavorite.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            }
            applySeekBarThemeColor(ThemeColors.dayTextPrimary());
        }
    }

    private void applySeekBarThemeColor(int color) {
        try {
            Drawable progressDrawable = seekBar.getProgressDrawable().mutate();
            if (progressDrawable instanceof LayerDrawable) {
                LayerDrawable layerDrawable = (LayerDrawable) progressDrawable;
                Drawable bg = layerDrawable.findDrawableByLayerId(android.R.id.background);
                if (bg != null) {
                    bg.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 保存当前播放歌曲信息到 SharedPreferences，下次启动时自动恢复
     * 同时保存播放队列模式，恢复时能继续在原队列（全部/收藏/目录）中播放
     */
    private void saveLastPlayed() {
        if (song == null) return;
        android.content.SharedPreferences.Editor editor = requireContext().getSharedPreferences("last_played", Context.MODE_PRIVATE).edit();
        song.saveToPrefs(editor);
        Bundle args = getArguments();
        String biliNavUrl = args != null ? args.getString("bili_nav_url", "") : "";
        editor.putInt("position", position)
                .putBoolean(com.jingxin.jingxinmusic.model.Song.KEY_HAS_LAST, true)
                .putString("playlist_mode", playlistMode != null ? playlistMode : "all")
                // B站导航上下文：返回时恢复到歌曲所在的列表页面
                .putString("bili_nav_url", biliNavUrl != null ? biliNavUrl : "");
        // 目录模式：保存该目录下所有歌曲路径
        if ("folder".equals(playlistMode)) {
            List<String> folderPaths = args != null ? args.getStringArrayList("folder_song_paths") : null;
            if (folderPaths != null && !folderPaths.isEmpty()) {
                editor.putStringSet("folder_song_paths", new java.util.HashSet<>(folderPaths));
            }
        } else if ("webdav".equals(playlistMode) || "bili".equals(playlistMode)) {
            // WebDAV/B站模式：保存标记，播放列表已在webdav_playlist SharedPreferences中
            editor.putBoolean("from_webdav", true);
        } else {
            // 其他模式清除旧数据
            editor.remove("folder_song_paths");
            editor.remove("from_webdav");
        }
        editor.apply();
    }

    /**
     * 启动频谱：优先 Visualizer（直接读取音频输出），失败则降级到 AudioRecord（麦克风采集）
     */
    private void startSpectrum() {
        // 先停后启：确保每次调用都能得到全新的 Visualizer（避免旧实例回调静默失效后标志卡死）
        if (spectrumRunning) {
            stopSpectrum();
        }
        spectrumRunning = true;

        // 尝试 Visualizer 方案（直接读取音频输出，不依赖麦克风）
        if (bound && playerBinder != null) {
            try {
                int sessionId = playerBinder.getAudioSessionId();
                if (sessionId != -1 && sessionId != 0) {
                    visualizer = new Visualizer(sessionId);
                    visualizer.setEnabled(false);
                    int[] range = visualizer.getCaptureSizeRange();
                    visualizer.setCaptureSize(range[1]);
                    visualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);

                    // rate=Visualizer.getMaxCaptureRate(), 不捕获波形, 捕获FFT
                    visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                        @Override
                        public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
                        }

                        @Override
                        public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                            if (!spectrumRunning || spectrumView == null) return;
                            lastSpectrumCallbackTime = android.os.SystemClock.elapsedRealtime();

                            // BD 方式：FFT 1:1 取幅度，竖条模式只取半数频段（会镜像展开）
                            int count = spectrumView.getBarInputCount();
                            float[] magnitudes = new float[count];
                            float maxMag = 0;
                            for (int i = 0; i < count; i++) {
                                int idx = (i + 1) * 2; // 跳过 DC 分量
                                if (idx + 1 < fft.length) {
                                    byte real = fft[idx];
                                    byte imaginary = fft[idx + 1];
                                    float mag = (float) Math.sqrt(real * real + imaginary * imaginary);
                                    magnitudes[i] = mag;
                                    if (mag > maxMag) maxMag = mag;
                                }
                            }
                            float finalMax = maxMag;
                            spectrumView.post(() -> {
                                if (spectrumView != null) {
                                    spectrumView.updateDTFMagnitudes(magnitudes, finalMax);
                                }
                            });
                        }
                    }, Visualizer.getMaxCaptureRate(), false, true);

                    visualizer.setEnabled(true);
                    useVisualizer = true;
                    return; // 成功，不需要 AudioRecord
                }
            } catch (Exception e) {
                Log.w(TAG, "Visualizer 初始化失败，降级到 AudioRecord: " + e.getMessage());
                if (visualizer != null) {
                    try { visualizer.release(); } catch (Exception ignored) {}
                    visualizer = null;
                }
            }
        }

        // 降级：AudioRecord + DFT（和酷狗替身参数一致）
        useVisualizer = false;
        startSpectrumAudioRecord();
    }

    /**
     * AudioRecord 降级方案：通过麦克风采集音频 + FFT 计算频谱
     */
    private void startSpectrumAudioRecord() {
        new Thread(() -> {
            try {
                int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord getMinBufferSize 失败");
                    spectrumRunning = false;
                    return;
                }

                audioRecord = null;
                int[] sources = {
                        MediaRecorder.AudioSource.MIC,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.UNPROCESSED
                };
                boolean created = false;
                for (int source : sources) {
                    try {
                        audioRecord = new AudioRecord(
                                source,
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufferSize);
                        if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                            created = true;
                            break;
                        } else {
                            if (audioRecord != null) {
                                audioRecord.release();
                                audioRecord = null;
                            }
                        }
                    } catch (Exception e) {
                        if (audioRecord != null) {
                            audioRecord.release();
                            audioRecord = null;
                        }
                    }
                }

                if (!created) {
                    Log.e(TAG, "AudioRecord 所有音频源初始化失败");
                    spectrumRunning = false;
                    return;
                }

                audioRecord.startRecording();
                Thread.sleep(500);

                // FFT 补零到 1024（2^10），800 采样点零填充
                final int FFT_SIZE = 1024;
                short[] readBuffer = new short[800];
                float[] fftReal = new float[FFT_SIZE];
                float[] fftImag = new float[FFT_SIZE];
                // 预计算 bit-reverse 表
                int[] bitRevTable = new int[FFT_SIZE];
                int bits = 10; // log2(1024)
                for (int i = 0; i < FFT_SIZE; i++) {
                    int rev = 0;
                    int val = i;
                    for (int b = 0; b < bits; b++) {
                        rev = (rev << 1) | (val & 1);
                        val >>= 1;
                    }
                    bitRevTable[i] = rev;
                }

                while (spectrumRunning) {
                    int totalRead = audioRecord.read(readBuffer, 0, 800);
                    if (totalRead <= 0) continue;

                    // 零填充到 FFT_SIZE 并加窗（Hann 窗减少频谱泄漏）
                    for (int i = 0; i < FFT_SIZE; i++) {
                        if (i < totalRead) {
                            double window = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (totalRead - 1)));
                            fftReal[i] = readBuffer[i] * (float) window;
                        } else {
                            fftReal[i] = 0;
                        }
                        fftImag[i] = 0;
                    }

                    // Cooley-Tukey 原地 FFT
                    for (int i = 0; i < FFT_SIZE; i++) {
                        int j = bitRevTable[i];
                        if (j > i) {
                            float tmp = fftReal[i]; fftReal[i] = fftReal[j]; fftReal[j] = tmp;
                            tmp = fftImag[i]; fftImag[i] = fftImag[j]; fftImag[j] = tmp;
                        }
                    }
                    for (int len = 2; len <= FFT_SIZE; len <<= 1) {
                        int halfLen = len >> 1;
                        double angleStep = -2.0 * Math.PI / len;
                        for (int i = 0; i < FFT_SIZE; i += len) {
                            for (int j = 0; j < halfLen; j++) {
                                double angle = angleStep * j;
                                float wr = (float) Math.cos(angle);
                                float wi = (float) Math.sin(angle);
                                int idx1 = i + j;
                                int idx2 = i + j + halfLen;
                                float tr = wr * fftReal[idx2] - wi * fftImag[idx2];
                                float ti = wr * fftImag[idx2] + wi * fftReal[idx2];
                                fftReal[idx2] = fftReal[idx1] - tr;
                                fftImag[idx2] = fftImag[idx1] - ti;
                                fftReal[idx1] += tr;
                                fftImag[idx1] += ti;
                            }
                        }
                    }

                    // 从 FFT 结果提取频谱幅度
                    int count = spectrumView != null ? spectrumView.getBarInputCount() : 65;
                    float[] magnitudes = new float[count];
                    float maxMag = 0;
                    for (int bar = 0; bar < count; bar++) {
                        int k = bar + 1; // 跳过 DC 分量
                        if (k >= FFT_SIZE / 2) k = FFT_SIZE / 2 - 1;
                        float re = fftReal[k];
                        float im = fftImag[k];
                        float mag = (float) Math.sqrt(re * re + im * im) / FFT_SIZE;
                        magnitudes[bar] = mag;
                        if (mag > maxMag) maxMag = mag;
                    }

                    float finalMax = maxMag;
                    if (spectrumView != null) {
                        spectrumView.post(() -> {
                            if (spectrumView != null) {
                                spectrumView.updateDTFMagnitudes(magnitudes, finalMax);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "频谱采集异常: " + e.getMessage());
            } finally {
                stopSpectrum();
            }
        }, "SpectrumThread").start();
    }

    private void stopSpectrum() {
        spectrumRunning = false;
        // 释放 Visualizer：先取消回调，再释放，避免回调线程与 release 并发导致 CFI 崩溃
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.setDataCaptureListener(null, 0, false, false);
                visualizer.release();
            } catch (Exception ignored) {}
            visualizer = null;
        }
        // 释放 AudioRecord
        if (audioRecord != null) {
            try {
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopSpectrum();
        uiHandler.removeCallbacks(spectrumHeartbeat);
        uiHandler.removeCallbacks(progressRunnable);
        try {
            requireContext().unregisterReceiver(songChangedReceiver);
        } catch (Exception ignored) {}
        if (bound) {
            requireContext().unbindService(serviceConnection);
            bound = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    /**
     * 从SharedPreferences加载WebDAV播放列表
     */
    private List<Song> loadWebDavPlaylist() {
        List<Song> songs = new ArrayList<>();
        try {
            String json = requireContext().getSharedPreferences("webdav_playlist", Context.MODE_PRIVATE)
                    .getString("playlist", null);
            if (json != null) {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    Song s = Song.fromJson(arr.getJSONObject(i));
                    if (s != null) songs.add(s);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载WebDAV播放列表失败: " + e.getMessage());
        }
        return songs;
    }
}