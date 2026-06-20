package com.jingxin.jingxinmusic;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.scene.CoverScene;
import com.jingxin.jingxinmusic.scene.CoverSceneHelper;
import com.jingxin.jingxinmusic.scene.PortraitClassicScene;
import com.jingxin.jingxinmusic.scene.PortraitImmersiveScene;
import com.jingxin.jingxinmusic.scene.LandscapeClassicScene;
import com.jingxin.jingxinmusic.scene.LandscapeImmersiveScene;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.service.MusicPlayerService.MusicPlayerBinder;
import com.jingxin.jingxinmusic.util.BlurUtil;
import com.jingxin.jingxinmusic.util.CoverFetcher;
import com.jingxin.jingxinmusic.util.FavoriteManager;
import com.jingxin.jingxinmusic.util.HistoryManager;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.util.LyricFetcher;
import com.jingxin.jingxinmusic.util.MusicScanner;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.RotatingCoverView;
import com.jingxin.jingxinmusic.view.SpectrumView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.AlertDialog;
import android.content.res.Configuration;

/**
 * 播放页面
 * 旋转封面 + 歌词 + 频谱 + 进度条 + 控制按钮
 */
public class PlayerActivity extends AppCompatActivity {

    private static final String TAG = "PlayerActivity";
    private static final int PROGRESS_UPDATE_INTERVAL = 200;

    private Song song;
    private int position;
    private List<Song> allSongs;
    private String playlistMode = "all"; // "all" 或 "favorites"
    private boolean resumePlay = false; // 从 mini 播放条跳转，不重新播放

    // UI
    private ImageView blurBackground;
    private RotatingCoverView coverView;
    private TextView tvSongName;
    private TextView tvArtist;
    private LyricView lyricView;
    private SpectrumView spectrumView;
    
    private static final int DOUBLE_CLICK_INTERVAL = 300;
    // 频谱按钮双击检测
    private long lastSpectrumBtnClickTime = 0;
    private SeekBar seekBar;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private ImageView btnPlayPause;
    private ImageView btnPrevious;
    private ImageView btnNext;
    private ImageView btnHistory;
    private ImageView btnFavorite;
    private ImageView btnPlayOrder;
    private ImageView btnTheme;
    private ImageView btnBack;
    private ImageView btnSpectrum;
    private ImageView btnOutfit;
    private View overlayView;
    private View whiteOverlay;
    private View immersiveDarkOverlay;

    // 白天模式渐变遮罩（浅绿→白）
    private android.graphics.drawable.GradientDrawable whiteGradientDrawable;

    // 主题
    private boolean isNightMode = true;  // 默认夜间模式
    private boolean isFavorite = false;  // 当前歌曲是否已收藏

    // 沉浸模式
    private boolean isImmersiveMode = false; // 沉浸封面模式
    private com.jingxin.jingxinmusic.view.ImmersiveOverlayView immersiveOverlay;

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
    private LandscapeClassicScene landscapeClassic;
    private LandscapeImmersiveScene landscapeImmersive;
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
                saveLastPlayed();
                Log.d(TAG, "UI 更新: " + newSong.title + " - " + newSong.artist);
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
                    boolean amapTriggered = getSharedPreferences("theme", MODE_PRIVATE)
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
                        Log.d(TAG, "高德日夜模式同步: " + (isNightMode ? "夜间" : "白天"));
                    }
                }
            }
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerBinder = (MusicPlayerBinder) service;
            bound = true;
            Log.d(TAG, "播放服务已连接");

            // 先扫描并设置播放列表，再播放
            executor.execute(() -> {
                allSongs = MusicScanner.scanMusic(PlayerActivity.this);
                uiHandler.post(() -> {
                    if (bound && playerBinder != null && allSongs != null) {
                        if (resumePlay && playerBinder.isPlaying()) {
                            // 从 mini 播放条跳转，音乐已在后台播放，只更新 UI
                            btnPlayPause.setImageResource(R.drawable.ic_pause);
                            coverView.startRotation();
                            spectrumView.setPlaying(true);
                            startSpectrumWithPermission();
                        } else if ("folder".equals(playlistMode)) {
                            // 目录模式：播放队列 = 该目录歌曲
                            List<String> folderPaths = getIntent().getStringArrayListExtra("folder_song_paths");
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
                            File favDir = com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(PlayerActivity.this);
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
                        } else if ("webdav".equals(playlistMode) || "bili".equals(playlistMode) || getIntent().getBooleanExtra("from_webdav", false)) {
                            // WebDAV/B站模式：从SharedPreferences恢复播放列表
                            List<Song> savedSongs = loadWebDavPlaylist();
                            if (!savedSongs.isEmpty()) {
                                playerBinder.setPlaylist(savedSongs);
                                // position优先从"position"取（正常浏览点击），"song_index"是自动恢复时的备选
                                int savedIndex = getIntent().getIntExtra("song_index", -1);
                                if (position >= 0 && position < savedSongs.size()) {
                                    // 从"position"已取到有效值，直接用
                                } else if (savedIndex >= 0 && savedIndex < savedSongs.size()) {
                                    // 自动恢复场景
                                    position = savedIndex;
                                } else {
                                    position = 0;
                                }
                                // 用播放列表中完整字段的歌曲替代Intent中可能缺失字段的song
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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        // 获取传入的歌曲信息
        song = Song.fromIntent(getIntent());
        position = getIntent().getIntExtra("position", 0);
        playlistMode = getIntent().getStringExtra("playlist_mode");
        if (playlistMode == null) playlistMode = "all";
        resumePlay = getIntent().getBooleanExtra("resume_play", false);

        // 初始化视图
        blurBackground = findViewById(R.id.blur_background);
        coverView = findViewById(R.id.cover_view);
        tvSongName = findViewById(R.id.song_name_text);
        tvArtist = findViewById(R.id.artist_text);
        lyricView = findViewById(R.id.lyric_view);
        spectrumView = findViewById(R.id.spectrum_view);
        seekBar = findViewById(R.id.progress_seek_bar);
        tvCurrentTime = findViewById(R.id.current_time_text);
        tvTotalTime = findViewById(R.id.total_time_text);
        btnPlayPause = findViewById(R.id.play_pause_button);
        btnPrevious = findViewById(R.id.previous_button);
        btnNext = findViewById(R.id.next_button);
        btnHistory = findViewById(R.id.history_button);
        btnFavorite = findViewById(R.id.mode_button);  // 复用 mode_button 位置
        btnPlayOrder = findViewById(R.id.play_order_button);
        btnTheme = findViewById(R.id.theme_button);
        btnBack = findViewById(R.id.back_button);
        btnSpectrum = findViewById(R.id.spectrum_button);
        btnOutfit = findViewById(R.id.outfit_button);
        overlayView = findViewById(R.id.overlay_view);
        whiteOverlay = findViewById(R.id.white_overlay);
        // 初始化白天模式渐变遮罩：浅绿(#A5D6A4)→白(#FFFFFF)，从上到下
        whiteGradientDrawable = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFA5D6A4, 0xFFFFFFFF}
        );
        whiteOverlay.setBackground(whiteGradientDrawable);
        whiteOverlay.setAlpha(0.4f);
        immersiveDarkOverlay = findViewById(R.id.immersive_dark_overlay);
        immersiveOverlay = findViewById(R.id.immersive_overlay);
        infoPanel = findViewById(R.id.info_panel);
        coverPlaceholder = findViewById(R.id.cover_placeholder);

        // 初始化 CoverScene 策略
        initCoverScene();

        // 延迟检测横屏模式（等视图布局完成）
        // 用 ViewTreeObserver 监听布局变化，自动切换横竖屏布局
        isLandscapeMode = false;
        View rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (isFinishing() || isDestroyed()) return;
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
        android.widget.FrameLayout rootFrameLayout = findViewById(R.id.root_layout);
        if (rootFrameLayout != null) {
            rootFrameLayout.addOnLayoutChangeListener(
                    (View v, int left, int top, int right, int bottom,
                     int oldLeft, int oldTop, int oldRight, int oldBottom) -> {
                        if (isFinishing() || isDestroyed()) return;
                        int newWidth = right - left;
                        int oldWidth = oldRight - oldLeft;
                        if (oldWidth > 0 && newWidth != oldWidth) {
                            Log.d(TAG, "Root FrameLayout width changed: " + oldWidth + " -> " + newWidth + " landscape=" + isLandscapeMode + " immersive=" + isImmersiveMode);
                            // 重新检测横竖屏并应用完整布局
                            boolean wasLandscape = isLandscapeMode;
                            detectAndApplyLandscapeMode();
                            applyLayoutMode();
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
        spectrumView.getLayoutParams().height = (int) (availableHeight * 0.10f);

        // 读取主题状态并同步所有 UI
        isNightMode = getSharedPreferences("theme", MODE_PRIVATE).getBoolean("isNight", true);
        isImmersiveMode = getSharedPreferences("theme", MODE_PRIVATE).getBoolean("immersive", false);
        ThemeColors.init(this);
        updateThemeUI();
        lyricView.setThemeMode(isNightMode
                ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
        immersiveOverlay.setNightMode(isNightMode);

        // 沉浸模式初始化：切换到正确的 scene
        if (isImmersiveMode) {
            syncSceneState();
            currentScene = isLandscapeMode ? landscapeImmersive : portraitImmersive;
            currentScene.enter();
            if (lyricView != null) {
                immersiveOverlay.setFullScreenMode(
                        lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL);
            }
        }

        // 显示歌曲信息
        tvSongName.setText(song.title);
        tvArtist.setText(song.artist);
        tvTotalTime.setText(Song.formatDuration(song.duration));

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
        btnPlayOrder.setOnClickListener(v -> togglePlayOrder());

        // 歌词区域：单击切换模式
        lyricView.setOnClickListener(v -> {
            if (isImmersiveMode) {
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
            } else {
                com.jingxin.jingxinmusic.view.LyricView.DisplayMode newMode = lyricView.toggleMode();
                updateLayoutForMode(newMode);
            }
        });
        lyricView.setOnModeChangeListener(newMode -> updateLayoutForMode(newMode));
        btnTheme.setOnClickListener(v -> toggleTheme());
        btnOutfit.setOnClickListener(v -> toggleImmersiveMode());
        btnSpectrum.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastSpectrumBtnClickTime < DOUBLE_CLICK_INTERVAL) {
                // 双击：关闭/显示频谱
                spectrumView.toggleVisibility();
                lastSpectrumBtnClickTime = 0;
            } else {
                // 单击：切换频谱样式
                if (spectrumView.isSpectrumVisible()) {
                    boolean wasRing = spectrumView.isRingMode();
                    spectrumView.switchStyle();
                    // 沉浸模式下如果切到了圆环，再切一次跳过
                    if (isImmersiveMode && spectrumView.isRingMode()) {
                        spectrumView.switchStyle();
                    }
                    // 圆环↔非圆环切换，需要重新布局频谱位置
                    boolean isRing = spectrumView.isRingMode();
                    if (wasRing != isRing) {
                        int w = getLayoutWidth();
                        int h = getAvailableScreenHeight();
                        currentScene.layout(w, h);
                    }
                } else {
                    // 频谱关闭时单击恢复显示
                    spectrumView.toggleVisibility();
                }
                lastSpectrumBtnClickTime = now;
            }
        });
        btnBack.setOnClickListener(v -> {
            Log.d(TAG, "Back button clicked, isFinishing=" + isFinishing());
            stopSpectrum();
            finish();
        });

        // 注册广播接收器（监听切歌和播放状态）
            IntentFilter filter = new IntentFilter();
            filter.addAction(MusicPlayerService.ACTION_SONG_CHANGED);
            filter.addAction(MusicPlayerService.ACTION_PLAY_STATE_CHANGED);
            filter.addAction(MusicPlayerService.ACTION_PLAY_ORDER_CHANGED);
            filter.addAction(MusicPlayerService.ACTION_THEME_CHANGED);
        CompatUtil.safeRegisterReceiver(this, songChangedReceiver, filter);

        // 绑定播放服务
        Intent serviceIntent = new Intent(this, MusicPlayerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 加载封面
        loadCover();

        // 检查收藏状态
        checkFavoriteStatus();

        // 加载歌词
        fetchLyrics();

        // 开始进度更新
        uiHandler.post(progressRunnable);

        Log.d(TAG, "播放页面加载: " + song.title + " - " + song.artist);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从列表页返回时同步主题（列表页可能切换了日夜模式）
        boolean savedNight = getSharedPreferences("theme", MODE_PRIVATE).getBoolean("isNight", true);
        if (savedNight != isNightMode) {
            isNightMode = savedNight;
            updateThemeUI();
            lyricView.setThemeMode(isNightMode
                    ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                    : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged: newOrientation=" + newConfig.orientation);
        // 延迟一帧再检测，确保 DisplayMetrics 已更新
        uiHandler.post(() -> {
            boolean wasLandscape = isLandscapeMode;
            detectAndApplyLandscapeMode();
            Log.d(TAG, "onConfigChanged post: wasLandscape=" + wasLandscape + " isLandscape=" + isLandscapeMode);
            if (wasLandscape != isLandscapeMode) {
                Log.d(TAG, "Applying layout mode, landscape=" + isLandscapeMode);
                applyLayoutMode();
                updateThemeUI();
                updateLayoutForMode(lyricView.getDisplayMode());
            }
        });
    }

    private void playSong() {
        if (bound && playerBinder != null && song != null) {
            playerBinder.playSong(song, position);
            btnPlayPause.setImageResource(R.drawable.ic_pause);
            coverView.startRotation();
            spectrumView.setPlaying(true);
            startSpectrumWithPermission();
            saveLastPlayed();
        }
    }

    private void startSpectrumWithPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startSpectrum();
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 100);
            }
        } else {
            startSpectrum();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
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
            android.widget.Toast.makeText(this, names[next], android.widget.Toast.LENGTH_SHORT).show();
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
        File favDir = com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(this);

        if (isFavorite) {
            FavoriteManager.removeFavorite(favDir, song);
            isFavorite = false;
            btnFavorite.setImageResource(R.drawable.ic_favorite);
            applyButtonTheme(isNightMode);
            android.widget.Toast.makeText(this, "取消收藏", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            FavoriteManager.addFavorite(favDir, song);
            isFavorite = true;
            btnFavorite.setImageResource(R.drawable.ic_favorite_filled);
            applyButtonTheme(isNightMode);
            android.widget.Toast.makeText(this, "已收藏", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 检查当前歌曲是否已收藏
     */
    private void checkFavoriteStatus() {
        if (song == null) return;
        File favDir = com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(this);
        isFavorite = FavoriteManager.isFavorite(favDir, song.title, song.artist);
        btnFavorite.setImageResource(isFavorite ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);
        applyButtonTheme(isNightMode);
    }

    /**
     * 根据歌词模式更新布局——使用 CoverScene 策略
     */
    private void updateLayoutForMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode mode) {
        syncSceneState();
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
     */
    private void resetLyricMargin() {
        if (lyricView == null) return;
        int density = (int) getResources().getDisplayMetrics().density;

        // 恢复歌名原始 margin（竖屏16dp，横屏52dp：返回按钮底部位置）
        android.widget.LinearLayout.LayoutParams nameParams =
                (android.widget.LinearLayout.LayoutParams) tvSongName.getLayoutParams();
        nameParams.topMargin = isLandscapeMode ? (density * 52) : (density * 16);
        tvSongName.setLayoutParams(nameParams);

        // 恢复歌词原始 margin
        android.widget.LinearLayout.LayoutParams lyricParams =
                (android.widget.LinearLayout.LayoutParams) lyricView.getLayoutParams();
        lyricParams.topMargin = density * 16;
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
        android.widget.FrameLayout rootLayout = findViewById(R.id.root_layout);
        sceneHelper = new CoverSceneHelper(
                rootLayout, blurBackground, coverView, tvSongName, tvArtist,
                lyricView, spectrumView, seekBar, tvCurrentTime, tvTotalTime,
                btnPlayPause, btnPrevious, btnNext, btnFavorite,
                infoPanel, coverPlaceholder, overlayView, whiteOverlay,
                immersiveDarkOverlay, immersiveOverlay,
                btnBack, btnSpectrum, btnOutfit, btnTheme,
                findViewById(R.id.top_buttons_bar),
                findViewById(R.id.control_buttons),
                getResources().getDisplayMetrics().density);
        sceneHelper.callback = new CoverSceneHelper.Callback() {
            @Override public void loadCover() { PlayerActivity.this.loadCover(); }
            @Override public void updateCoverPlaceholder() { PlayerActivity.this.updateCoverPlaceholder(); }
            @Override public void resetLyricMargin() { PlayerActivity.this.resetLyricMargin(); }
            @Override public void updateImmersiveLyricMargin(boolean isFullScreen) { PlayerActivity.this.updateImmersiveLyricMargin(isFullScreen); }
            @Override public void updateThemeUI() { PlayerActivity.this.updateThemeUI(); }
            @Override public void extractAndApplyDominantColor(Bitmap bitmap) {
                executor.execute(() -> {
                    int dominantColor = extractDominantColor(bitmap);
                    if (!isDestroyed()) {
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
        };

        portraitClassic = new PortraitClassicScene(sceneHelper);
        portraitImmersive = new PortraitImmersiveScene(sceneHelper);
        landscapeClassic = new LandscapeClassicScene(sceneHelper);
        landscapeImmersive = new LandscapeImmersiveScene(sceneHelper);

        // 默认竖屏经典
        currentScene = portraitClassic;
    }

    /**
     * 根据当前 isLandscapeMode 和 isImmersiveMode 切换到正确的 Scene
     * @return 是否发生了 Scene 切换
     */
    private boolean switchScene() {
        CoverScene target;
        if (isLandscapeMode) {
            target = isImmersiveMode ? landscapeImmersive : landscapeClassic;
        } else {
            target = isImmersiveMode ? portraitImmersive : portraitClassic;
        }
        if (target != currentScene) {
            currentScene.exit();
            currentScene = target;
            currentScene.enter();
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
        sceneHelper.playerBinder = playerBinder;
        sceneHelper.coverBorderGradient = coverBorderGradient;
        sceneHelper.landscapeGradientOverlay = landscapeGradientOverlay;
        sceneHelper.executor = executor;
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
        Log.d(TAG, "detectLandscape: width=" + width + " height=" + height + " landscape=" + newLandscape);
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
        // 沉浸模式下切换后需要重新加载封面
        if (isImmersiveMode) {
            loadCover();
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
            // 横屏沉浸模式下封面不旋转
            if (!(isImmersiveMode && isLandscapeMode)) {
                coverView.startRotation();
            }
            spectrumView.setPlaying(true);
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play);
            coverView.stopRotation();
            spectrumView.setPlaying(false);
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

        File lyricsDir = new File(getExternalFilesDir(null), "lyrics");

        LyricFetcher.loadLyric(title, artist, song.filePath, lyricsDir, new LyricFetcher.LyricCallback() {
            @Override
            public void onLyricFetched(KrcParser.LyricData lyricData) {
                if (lyricView != null && lyricData != null && lyricData.lines != null && !lyricData.lines.isEmpty()) {
                    uiHandler.post(() -> {
                        lyricView.setLyricData(lyricData);
                        Log.d(TAG, "歌词加载成功，共 " + lyricData.lines.size() + " 行");
                    });
                } else {
                    Log.d(TAG, "歌词为空");
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.d(TAG, "歌词获取失败: " + errorMessage);
            }
        }, this);
    }

    /**
     * 加载封面：先本地文件，没有则在线获取并保存
     */
    private void notifyMetadataUpdate() {
        Intent intent = new Intent(this, com.jingxin.jingxinmusic.service.MusicPlayerService.class);
        intent.setAction(com.jingxin.jingxinmusic.service.MusicPlayerService.ACTION_UPDATE_METADATA);
        startService(intent);
    }

    private void loadCover() {
        // 设置默认封面
        coverView.setImageResource(R.drawable.ic_music_icon);
        // 先用默认封面图标生成模糊背景，后续找到真实封面会覆盖
        applyDefaultCoverBlur();
        // 横屏沉浸下，切换间隙隐藏 foreground 渐变，避免默认封面+渐变的闪烁
        if (isImmersiveMode && isLandscapeMode) {
            coverView.setForeground(null);
        }

        com.jingxin.jingxinmusic.util.CoverLoader.load(this, song, 600, 600,
                true, executor, new com.jingxin.jingxinmusic.util.CoverLoader.CoverCallback() {
            @Override
            public void onCoverLoaded(Bitmap bitmap) {
                if (isDestroyed()) return;
                setCoverBitmap(bitmap);
            }

            @Override
            public void onCoverFailed() {
                if (isDestroyed()) return;
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
        if (isDestroyed()) return;
        executor.execute(() -> {
            try {
                Drawable drawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_music_icon);
                if (drawable == null) return;
                // 矢量图需要指定尺寸转为Bitmap
                int size = 256;
                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(canvas);
                if (!isDestroyed()) {
                    uiHandler.post(() -> setCoverBitmap(bitmap));
                }
            } catch (Exception e) {
                Log.w(TAG, "默认封面模糊背景生成失败", e);
            }
        });
    }

    private void setCoverBitmap(Bitmap bitmap) {
        if (bitmap == null || isDestroyed()) return;
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
            File historyDir = new File(getExternalFilesDir(null), "history");
            List<HistoryManager.HistoryItem> history = HistoryManager.loadHistory(historyDir);

            if (history.isEmpty()) {
                uiHandler.post(() -> {
                    new AlertDialog.Builder(PlayerActivity.this)
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

                new AlertDialog.Builder(PlayerActivity.this)
                        .setTitle("播放历史")
                        .setItems(items, (dialog, which) -> {
                            HistoryManager.HistoryItem item = history.get(which);
                            // 把历史项转为 Song 并播放
                            Song s = new Song();
                            s.title = item.title;
                            s.artist = item.artist;
                            s.album = item.album;
                            s.duration = item.duration;
                            s.filePath = item.filePath;
                            s.contentUri = item.contentUri;
                            s.albumArt = item.albumArt;
                            s.displayName = s.title;

                            song = s;
                            tvSongName.setText(s.title);
                            tvArtist.setText(s.artist);
                            tvCurrentTime.setText("00:00");
                            tvTotalTime.setText(Song.formatDuration(s.duration));
                            loadCover();
                            fetchLyrics();

                            if (bound && playerBinder != null) {
                                // 将历史列表转为播放列表，下一首即为历史中的下一首
                                List<Song> historySongs = new ArrayList<>();
                                for (HistoryManager.HistoryItem h : history) {
                                    Song hs = new Song();
                                    hs.title = h.title;
                                    hs.artist = h.artist;
                                    hs.album = h.album;
                                    hs.duration = h.duration;
                                    hs.filePath = h.filePath;
                                    hs.contentUri = h.contentUri;
                                    hs.albumArt = h.albumArt;
                                    hs.displayName = hs.title;
                                    hs.sourceType = h.sourceType;
                                    hs.bvid = h.bvid;
                                    hs.cid = h.cid;
                                    hs.audioUrl = h.audioUrl;
                                    hs.audioUrlExpire = h.audioUrlExpire;
                                    hs.coverUrl = h.coverUrl;
                                    historySongs.add(hs);
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
     * 切换沉浸封面模式——使用 CoverScene 策略
     * 长按歌词区域触发
     */
    private void toggleImmersiveMode() {
        isImmersiveMode = !isImmersiveMode;
        getSharedPreferences("theme", MODE_PRIVATE).edit().putBoolean("immersive", isImmersiveMode).apply();

        // 沉浸模式下圆环不可用，如果当前是圆环则切换到竖条
        if (isImmersiveMode && spectrumView.isRingMode()) {
            spectrumView.switchStyle();
        }

        syncSceneState();
        switchScene();
        int width = getLayoutWidth();
        int height = getAvailableScreenHeight();
        currentScene.layout(width, height);

        // 重新加载封面
        loadCover();

        // 同步歌词模式到沉浸遮罩
        if (lyricView != null) {
            immersiveOverlay.setFullScreenMode(
                    lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL);
        }

        android.widget.Toast.makeText(this, isImmersiveMode ? "沉浸模式" : "经典模式",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void toggleTheme() {
        isNightMode = !isNightMode;
        getSharedPreferences("theme", MODE_PRIVATE).edit()
                .putBoolean("isNight", isNightMode)
                .putBoolean("amapTriggered", false)  // 手动切换，暂停高德同步
                .apply();
        updateThemeUI();
        if (lyricView != null) {
            lyricView.setThemeMode(isNightMode
                    ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                    : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
        }
        android.widget.Toast.makeText(this, isNightMode ? "夜间模式" : "白天模式", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void updateThemeUI() {
        if (isImmersiveMode) {
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
                whiteOverlay.setAlpha(0.4f);
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
            tvArtist.setTextColor(ThemeColors.nightTextSecondary());
            tvCurrentTime.setTextColor(ThemeColors.nightTextPrimary());
            tvTotalTime.setTextColor(ThemeColors.nightTextPrimary());
        } else {
            tvSongName.setTextColor(ThemeColors.dayTextPrimary());
            tvArtist.setTextColor(ThemeColors.dayTextTertiary());
            tvCurrentTime.setTextColor(ThemeColors.dayTextPrimary());
            tvTotalTime.setTextColor(ThemeColors.dayTextPrimary());
        }
    }

    /**
     * 统一设置按钮主题颜色
     */
    private void applyButtonTheme(boolean isNight) {
        ImageView[] buttons = {btnPlayPause, btnPrevious, btnNext,
                btnHistory, btnPlayOrder, btnTheme, btnBack, btnSpectrum, btnOutfit};
        if (isNight) {
            for (ImageView btn : buttons) btn.clearColorFilter();
            // 收藏按钮：已收藏用红色，未收藏清除滤镜
            if (isFavorite) {
                btnFavorite.setColorFilter(Color.parseColor("#FF5252"), PorterDuff.Mode.SRC_IN);
            } else {
                btnFavorite.clearColorFilter();
            }
            applySeekBarThemeColor(ThemeColors.nightTextPrimary());
        } else {
            int buttonColor = ThemeColors.dayTextPrimary();
            for (ImageView btn : buttons) btn.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            // 收藏按钮：已收藏用红色，未收藏用通用色
            if (isFavorite) {
                btnFavorite.setColorFilter(Color.parseColor("#FF5252"), PorterDuff.Mode.SRC_IN);
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
     * 清理歌曲标题中的品质后缀（如 [mqms]、[mqms2]、[mqms3] 等）
     * 提高在线搜索封面和歌词的匹配率
     */
    /**
     * 保存当前播放歌曲信息到 SharedPreferences，下次启动时自动恢复
     * 同时保存播放队列模式，恢复时能继续在原队列（全部/收藏/目录）中播放
     */
    private void saveLastPlayed() {
        if (song == null) return;
        android.content.SharedPreferences.Editor editor = getSharedPreferences("last_played", MODE_PRIVATE).edit();
        song.saveToPrefs(editor);
        editor.putInt("position", position)
                .putBoolean(com.jingxin.jingxinmusic.model.Song.KEY_HAS_LAST, true)
                .putString("playlist_mode", playlistMode != null ? playlistMode : "all")
                // B站导航上下文：返回时恢复到歌曲所在的列表页面
                .putString("bili_nav_url", getIntent().getStringExtra("bili_nav_url") != null ? getIntent().getStringExtra("bili_nav_url") : "");
        // 目录模式：保存该目录下所有歌曲路径
        if ("folder".equals(playlistMode)) {
            List<String> folderPaths = getIntent().getStringArrayListExtra("folder_song_paths");
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
        if (spectrumRunning) return;
        spectrumRunning = true;

        // 尝试 Visualizer 方案（直接读取音频输出，不依赖麦克风）
        if (bound && playerBinder != null) {
            try {
                int sessionId = playerBinder.getAudioSessionId();
                Log.d(TAG, "尝试 Visualizer, audioSessionId=" + sessionId);
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
                    Log.d(TAG, "Visualizer 启动成功");
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
     * AudioRecord 降级方案：通过麦克风采集音频 + DFT 计算频谱
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
                            Log.d(TAG, "AudioRecord 创建成功, source=" + source);
                            created = true;
                            break;
                        } else {
                            if (audioRecord != null) {
                                audioRecord.release();
                                audioRecord = null;
                            }
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "AudioSource " + source + " 失败: " + e.getMessage());
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
                Log.d(TAG, "AudioRecord 启动成功，开始频谱采集");

                short[] bigBuffer = new short[800];
                int logFrameCount = 0;
                while (spectrumRunning) {
                    int totalRead = audioRecord.read(bigBuffer, 0, 800);
                    if (totalRead <= 0) continue;

                    if (logFrameCount++ % 10 == 0) {
                        Log.d(TAG, "AudioRecord totalRead=" + totalRead);
                    }

                    int count = spectrumView != null ? spectrumView.getBarInputCount() : 65;
                    float[] magnitudes = new float[count];
                    float maxMag = 0;
                    // BD 方式：DFT 1:1 直传，不预合并
                    for (int bar = 0; bar < count; bar++) {
                        int k = bar + 1;
                        if (k > totalRead - 1) k = totalRead - 1;
                        float re = 0, im = 0;
                        double freq = 2.0 * Math.PI * k / totalRead;
                        for (int n = 0; n < totalRead; n++) {
                            float sample = bigBuffer[n];
                            re += sample * Math.cos(freq * n);
                            im -= sample * Math.sin(freq * n);
                        }
                        float mag = (float) Math.sqrt(re * re + im * im) / totalRead;
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
    protected void onDestroy() {
        super.onDestroy();
        stopSpectrum();
        uiHandler.removeCallbacks(progressRunnable);
        try {
            unregisterReceiver(songChangedReceiver);
        } catch (Exception ignored) {}
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        executor.shutdownNow();
    }

    /**
     * 从SharedPreferences加载WebDAV播放列表
     */
    private List<Song> loadWebDavPlaylist() {
        List<Song> songs = new ArrayList<>();
        try {
            String json = getSharedPreferences("webdav_playlist", MODE_PRIVATE)
                    .getString("playlist", null);
            if (json != null) {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    Song song = Song.fromJson(arr.getJSONObject(i));
                    if (song != null) songs.add(song);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载WebDAV播放列表失败: " + e.getMessage());
        }
        return songs;
    }
}
