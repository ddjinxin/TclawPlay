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
    
    // 双击歌词恢复频谱
    private long lastLyricClickTime = 0;
    private static final int DOUBLE_CLICK_INTERVAL = 300;
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
    private View overlayView;
    private View whiteOverlay;
    private View immersiveDarkOverlay;

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

    // 播放服务
    private MusicPlayerBinder playerBinder;
    private boolean bound = false;
    private final Handler uiHandler = new Handler();
    private boolean userSeeking = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 频谱：Visualizer（主方案）或 AudioRecord（降级方案）
    private android.media.audiofx.Visualizer visualizer;
    private AudioRecord audioRecord;
    private boolean spectrumRunning = false;
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
                        if (resumePlay) {
                            // 从 mini 播放条跳转，音乐已在后台播放，只更新 UI
                            btnPlayPause.setImageResource(playerBinder.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
                            coverView.startRotation();
                            spectrumView.setPlaying(playerBinder.isPlaying());
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
                            File favDir = new File(getExternalFilesDir(null), "favorites");
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
        overlayView = findViewById(R.id.overlay_view);
        whiteOverlay = findViewById(R.id.white_overlay);
        immersiveDarkOverlay = findViewById(R.id.immersive_dark_overlay);
        immersiveOverlay = findViewById(R.id.immersive_overlay);
        infoPanel = findViewById(R.id.info_panel);
        coverPlaceholder = findViewById(R.id.cover_placeholder);

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
        // 首次必须应用竖屏布局（设置封面占位等）
        applyPortraitLayout();

        // 竖屏模式下封面和频谱高度：相对屏幕高度
        int availableHeight = getAvailableScreenHeight();
        coverView.getLayoutParams().height = (int) (availableHeight * 0.25f);
        coverView.getLayoutParams().width = (int) (availableHeight * 0.25f);
        spectrumView.getLayoutParams().height = (int) (availableHeight * 0.10f);

        // 读取主题状态并同步所有 UI
        isNightMode = getSharedPreferences("theme", MODE_PRIVATE).getBoolean("isNight", true);
        isImmersiveMode = getSharedPreferences("theme", MODE_PRIVATE).getBoolean("immersive", false);
        updateThemeUI();
        lyricView.setThemeMode(isNightMode
                ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
        immersiveOverlay.setNightMode(isNightMode);

        // 沉浸模式初始化：隐藏旋转封面，隐藏封面占位，同步歌词模式，调整歌词 margin
        if (isImmersiveMode) {
            coverView.setVisibility(View.GONE);
            coverPlaceholder.setVisibility(View.GONE);
            if (lyricView != null) {
                immersiveOverlay.setFullScreenMode(
                        lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL);
                updateImmersiveLyricMargin(
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

        // 歌词区域：单击切换模式，频谱隐藏时双击恢复
        lyricView.setOnClickListener(v -> {
            if (spectrumView.getVisibility() == View.GONE) {
                // 频谱隐藏状态下，双击恢复，单击也恢复（方便操作）
                long now = System.currentTimeMillis();
                if (now - lastLyricClickTime < DOUBLE_CLICK_INTERVAL) {
                    spectrumView.setVisibility(View.VISIBLE);
                    lastLyricClickTime = 0;
                } else {
                    lastLyricClickTime = now;
                }
            } else {
                // 频谱可见状态下，正常单击切换模式
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
            }
        });
        lyricView.setOnModeChangeListener(newMode -> updateLayoutForMode(newMode));
        // 长按歌词区域切换沉浸模式
        lyricView.setOnLongClickListener(v -> {
            // 非沉浸模式下的全屏歌词，长按不切换
            if (!isImmersiveMode && lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL) {
                return true;
            }
            toggleImmersiveMode();
            return true;
        });
        btnTheme.setOnClickListener(v -> toggleTheme());
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
        File favDir = new File(getExternalFilesDir(null), "favorites");

        if (isFavorite) {
            FavoriteManager.removeFavorite(favDir, song);
            isFavorite = false;
            btnFavorite.setImageResource(R.drawable.ic_favorite);
            android.widget.Toast.makeText(this, "取消收藏", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            FavoriteManager.addFavorite(favDir, song);
            isFavorite = true;
            btnFavorite.setImageResource(R.drawable.ic_favorite_filled);
            android.widget.Toast.makeText(this, "已收藏", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 检查当前歌曲是否已收藏
     */
    private void checkFavoriteStatus() {
        if (song == null) return;
        File favDir = new File(getExternalFilesDir(null), "favorites");
        isFavorite = FavoriteManager.isFavorite(favDir, song.title, song.artist);
        btnFavorite.setImageResource(isFavorite ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);
    }

    /**
     * 根据歌词模式更新布局
     */
    private void updateLayoutForMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode mode) {
        boolean isFull = mode == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;

        if (isImmersiveMode) {
            immersiveOverlay.setVisibility(View.VISIBLE);
            overlayView.setVisibility(View.GONE);
            whiteOverlay.setVisibility(View.GONE);
            tvSongName.setVisibility(View.VISIBLE);
            tvArtist.setVisibility(View.VISIBLE);
            coverPlaceholder.setVisibility(View.GONE);

            if (isLandscapeMode) {
                // 横屏沉浸：歌名位置和非沉浸横屏一致（52dp）
                resetLyricMargin();
                coverView.setVisibility(View.VISIBLE);
                coverView.setClipToOutline(false);
                coverView.setBackground(null);
            } else {
                // 竖屏沉浸：歌名推到遮罩区，封面隐藏
                immersiveOverlay.setFullScreenMode(false);
                blurBackground.setAlpha(1.0f);
                coverView.setVisibility(View.GONE);
                updateImmersiveLyricMargin(false);
            }
        } else {
            // 非沉浸模式
            immersiveOverlay.setVisibility(View.GONE);
            resetLyricMargin();
            if (!isLandscapeMode) {
                // 竖屏：全屏歌词时不需要封面占位，非全屏时恢复占位
                if (isFull) {
                    coverPlaceholder.setVisibility(View.GONE);
                } else {
                    updateCoverPlaceholder();
                }
            }
            if (isFull) {
                // 横屏全屏歌词：封面保持显示；竖屏：隐藏封面
                if (isLandscapeMode) {
                    coverView.setVisibility(View.VISIBLE);
                } else {
                    coverView.setVisibility(View.GONE);
                }
                tvSongName.setVisibility(View.GONE);
                tvArtist.setVisibility(View.GONE);
            } else {
                coverView.setVisibility(View.VISIBLE);
                tvSongName.setVisibility(View.VISIBLE);
                tvArtist.setVisibility(View.VISIBLE);
            }
        }
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
        android.view.ViewParent parent = coverView.getParent();
        if (parent instanceof android.widget.FrameLayout) {
            int ph = ((android.widget.FrameLayout) parent).getHeight();
            if (ph > 0) return ph;
        }
        // 回退：用屏幕高度减去系统栏
        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        int navBarResourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        int navBarHeight = 0;
        if (navBarResourceId > 0) navBarHeight = getResources().getDimensionPixelSize(navBarResourceId);
        int height = getResources().getDisplayMetrics().heightPixels - statusBarHeight - navBarHeight;
        if (height <= 0) height = getResources().getDisplayMetrics().heightPixels;
        return height;
    }

    /**
     * 设置竖屏封面占位高度，把歌名推到封面下方
     */
    private void updateCoverPlaceholder() {
        int screenHeight = getAvailableScreenHeight();
        float density = getResources().getDisplayMetrics().density;
        int coverSize = (int) (screenHeight * 0.25f);
        int coverMarginTop = (int) (density * 32);
        int coverNameGap = (int) (density * 16);
        coverPlaceholder.setVisibility(View.VISIBLE);
        android.widget.LinearLayout.LayoutParams placeholderParams =
                (android.widget.LinearLayout.LayoutParams) coverPlaceholder.getLayoutParams();
        placeholderParams.height = coverMarginTop + coverSize + coverNameGap;
        placeholderParams.width = 1;
        coverPlaceholder.setLayoutParams(placeholderParams);
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
     * 应用当前布局模式（横屏/竖屏）
     */
    private void applyLayoutMode() {
        if (isLandscapeMode) {
            applyLandscapeLayout();
        } else {
            applyPortraitLayout();
        }
        // 非沉浸模式下也同步 landscapeMode，防止横屏→竖屏后残留 true，导致下次进入沉浸时渐变丢失
        immersiveOverlay.setLandscapeMode(isLandscapeMode);

        // 沉浸模式下，横竖屏切换需要同步遮罩和封面
        if (isImmersiveMode) {
            immersiveOverlay.setLandscapeMode(isLandscapeMode);
            if (isLandscapeMode) {
                applyLandscapeImmersiveCover();
                coverView.setVisibility(View.VISIBLE);
                coverView.setClipToOutline(false);
                coverView.setBackground(null);
                coverView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                coverView.stopAndResetRotation();
                // 恢复横向渐变 foreground
                int overlayColor = immersiveOverlay.getOverlayColor();
                float borderWidthPx = getResources().getDisplayMetrics().density * 40f;
                if (coverBorderGradient == null) {
                    coverBorderGradient = new com.jingxin.jingxinmusic.view.CoverBorderGradientDrawable(
                            overlayColor, borderWidthPx);
                } else {
                    coverBorderGradient.setOverlayColor(overlayColor);
                }
                coverView.setForeground(coverBorderGradient);
                // 夜间模式显示半透明黑色遮罩
                immersiveDarkOverlay.setVisibility(isNightMode ? View.VISIBLE : View.GONE);
                blurBackground.setVisibility(View.GONE);
                // 恢复横屏沉浸层级
                moveCoverBelowOverlay();
                resetLyricMargin();
            } else {
                coverView.setVisibility(View.GONE);
                coverView.setClipToOutline(true);
                coverView.setBackgroundResource(R.drawable.circle_cover_background);
                coverView.setForeground(null);
                blurBackground.setVisibility(View.VISIBLE);
                blurBackground.setAlpha(1.0f);
                // 夜间模式显示半透明黑色遮罩
                immersiveDarkOverlay.setVisibility(isNightMode ? View.VISIBLE : View.GONE);
                // 恢复 immersiveOverlay 到 blurBackground 之上，防止被盖住
                restoreOverlayHierarchy();
                boolean isFull = lyricView != null &&
                        lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
                if (!isFull) {
                    updateImmersiveLyricMargin(false);
                }
            }
            loadCover();
        }
    }

    /**
     * 获取根 FrameLayout 的实际宽度，首次布局时可能为0则回退到 screenWidth
     */
    private int getLayoutWidth() {
        android.view.ViewParent parent = coverView.getParent();
        if (parent instanceof android.widget.FrameLayout) {
            int pw = ((android.widget.FrameLayout) parent).getWidth();
            if (pw > 0) return pw;
        }
        return getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * 应用横屏布局：左65%信息 + 右35%封面
     * 所有尺寸基于 FrameLayout 实际宽度计算，响应车机窗口宽度变化
     */
    private void applyLandscapeLayout() {
        float density = getResources().getDisplayMetrics().density;
        int layoutWidth = getLayoutWidth();
        int layoutHeight = getAvailableScreenHeight();
        Log.d(TAG, "applyLandscapeLayout: layoutWidth=" + layoutWidth + " immersive=" + isImmersiveMode);

        // info_panel 占左 65%
        int infoWidth = (int) (layoutWidth * 0.65f);
        android.widget.FrameLayout.LayoutParams infoParams =
                (android.widget.FrameLayout.LayoutParams) infoPanel.getLayoutParams();
        Log.d(TAG, "applyLandscapeLayout: old infoWidth=" + infoParams.width + " new infoWidth=" + infoWidth);
        infoParams.width = infoWidth;
        infoParams.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.gravity = android.view.Gravity.START;
        infoPanel.setLayoutParams(infoParams);
        // 左侧内容水平居中，从顶部向下排列（歌名紧跟返回/主题按钮下方）
        if (infoPanel instanceof android.widget.LinearLayout) {
            ((android.widget.LinearLayout) infoPanel).setGravity(
                    android.view.Gravity.CENTER_HORIZONTAL);
        }

        // 歌名 topMargin = 返回按钮底部位置（marginTop=16dp + height=36dp = 52dp）
        android.widget.LinearLayout.LayoutParams nameParams =
                (android.widget.LinearLayout.LayoutParams) tvSongName.getLayoutParams();
        nameParams.topMargin = (int) (density * 52);
        tvSongName.setLayoutParams(nameParams);
        tvSongName.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        tvArtist.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        // 横屏下歌名和歌手字号随歌词动态调整：歌名比当前行歌词略大，歌手为歌名70%，均设上限
        float lyricCurrentSize = lyricView != null ? lyricView.getTextSizeCurrent() : 48f;
        float songNameSize = Math.min(lyricCurrentSize * 1.1f, 36f);
        float artistSize = Math.min(songNameSize * 0.7f, 28f);
        tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, artistSize);

        // 封面布局：沉浸模式下由 applyLandscapeImmersiveCover() 处理，这里只设非沉浸
        if (!isImmersiveMode) {
            int rightPanelWidth = (int) (layoutWidth * 0.35f);
            int coverSize = (int) (rightPanelWidth * 0.70f);
            android.widget.FrameLayout.LayoutParams coverParams =
                    new android.widget.FrameLayout.LayoutParams(coverSize, coverSize);
            coverParams.gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
            int horizontalPadding = (rightPanelWidth - coverSize) / 2;
            coverParams.rightMargin = horizontalPadding;
            coverParams.leftMargin = 0;
            coverView.setLayoutParams(coverParams);
            Log.d(TAG, "Landscape cover: layoutWidth=" + layoutWidth + " rightPanel=" + rightPanelWidth
                    + " coverSize=" + coverSize + " rightMargin=" + horizontalPadding);
        }

        // 频谱高度
        spectrumView.getLayoutParams().height = (int) (layoutHeight * 0.08f);

        // 横屏下不需要封面占位
        coverPlaceholder.setVisibility(View.GONE);

        // 根据播放状态恢复旋转（非沉浸模式下）
        if (!isImmersiveMode) {
            if (playerBinder != null && playerBinder.isPlaying()) {
                coverView.startRotation();
            } else {
                coverView.stopRotation();
            }
        }
    }

    /**
     * 应用竖屏布局：封面顶部居中，下方歌名、歌手、歌词等
     */
     /**
      * 横屏沉浸模式下设置封面：矩形铺满右侧，不旋转，边缘渐变
      * 用 Gravity.END + rightMargin=0 贴右边缘，避免车机 FrameLayout 实际宽度与 screenWidth 不一致露出黑条
      * 所有尺寸基于 FrameLayout 实际宽度计算，响应车机窗口宽度变化
      */
    private void applyLandscapeImmersiveCover() {
        int layoutWidth = getLayoutWidth();
        int parentHeight = getAvailableScreenHeight();

        // 用 FrameLayout 的实际高度
        android.view.ViewParent parent = coverView.getParent();
        if (parent instanceof android.widget.FrameLayout) {
            int ph = ((android.widget.FrameLayout) parent).getHeight();
            if (ph > 0) parentHeight = ph;
        }

        // 封面宽度=右侧面板宽度，基于实际布局宽度计算
        int coverWidth = (int) (layoutWidth * 0.35f);
        int coverHeight = parentHeight;

        android.widget.FrameLayout.LayoutParams coverParams =
                new android.widget.FrameLayout.LayoutParams(coverWidth, coverHeight);
        coverParams.gravity = android.view.Gravity.END | android.view.Gravity.TOP;
        coverParams.rightMargin = 0;
        coverParams.topMargin = 0;
        coverView.setLayoutParams(coverParams);

        // 取消圆形裁剪，不旋转
        coverView.setClipToOutline(false);
        coverView.setBackground(null);
        coverView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        coverView.stopAndResetRotation();

        // 设置封面边缘渐变 foreground（遮罩色到透明）
        float borderWidthPx = getResources().getDisplayMetrics().density * 40f;
        int overlayColor = immersiveOverlay.getOverlayColor();
        if (coverBorderGradient == null) {
            coverBorderGradient = new com.jingxin.jingxinmusic.view.CoverBorderGradientDrawable(
                    overlayColor, borderWidthPx);
        } else {
            coverBorderGradient.setOverlayColor(overlayColor);
        }
        coverView.setForeground(coverBorderGradient);
    }

    /**
     * 横屏沉浸：排列三层层级
     * immersiveOverlay(0) → coverView(1) → landscapeGradientOverlay(2)
     */
    private void moveCoverBelowOverlay() {
        android.view.ViewParent p = coverView.getParent();
        if (!(p instanceof android.widget.FrameLayout)) return;
        android.widget.FrameLayout parent = (android.widget.FrameLayout) p;

        // immersiveOverlay 放到最底层（全屏实色基底）
        parent.removeView(immersiveOverlay);
        parent.addView(immersiveOverlay, 0);

        // coverView 放在 immersiveOverlay 上面
        parent.removeView(coverView);
        parent.addView(coverView, 1);

        // landscapeGradientOverlay 放在 coverView 上面（渐变过渡）
        if (landscapeGradientOverlay != null) {
            parent.removeView(landscapeGradientOverlay);
            parent.addView(landscapeGradientOverlay, 2);
        }
    }

    /**
     * 退出沉浸：将 coverView 恢复到 info_panel 上层
     * 恢复层级：blur_background → immersiveOverlay → info_panel → coverView → 按钮
     */
    private void moveCoverAboveInfoPanel() {
        android.view.ViewParent p = coverView.getParent();
        if (!(p instanceof android.widget.FrameLayout)) return;
        android.widget.FrameLayout parent = (android.widget.FrameLayout) p;
        int coverIndex = parent.indexOfChild(coverView);
        int infoIndex = parent.indexOfChild(infoPanel);
        // 目标：coverView 在 infoPanel 后面
        int targetIndex = infoIndex + 1;
        if (coverIndex != targetIndex) {
            parent.removeView(coverView);
            parent.addView(coverView, targetIndex);
        }
    }

    /**
     * 竖屏沉浸：恢复 immersiveOverlay 到 blurBackground 之上
     * 横屏沉浸时 moveCoverBelowOverlay 把 immersiveOverlay 移到了最底层，
     * 切竖屏时需要恢复，否则 blurBackground 会盖住遮罩层
     */
    private void restoreOverlayHierarchy() {
        android.view.ViewParent p = coverView.getParent();
        if (!(p instanceof android.widget.FrameLayout)) return;
        android.widget.FrameLayout parent = (android.widget.FrameLayout) p;

        // immersiveOverlay 移到 blurBackground 后面（紧挨着，在它上面）
        int blurIndex = parent.indexOfChild(blurBackground);
        int overlayIndex = parent.indexOfChild(immersiveOverlay);
        if (overlayIndex < blurIndex) {
            parent.removeView(immersiveOverlay);
            parent.addView(immersiveOverlay, blurIndex + 1);
        }

        // immersiveDarkOverlay 移到 immersiveOverlay 后面
        int newOverlayIndex = parent.indexOfChild(immersiveOverlay);
        int darkIndex = parent.indexOfChild(immersiveDarkOverlay);
        if (darkIndex >= 0 && darkIndex < newOverlayIndex) {
            parent.removeView(immersiveDarkOverlay);
            parent.addView(immersiveDarkOverlay, newOverlayIndex + 1);
        }

        // 隐藏横屏专属的渐变过渡层
        if (landscapeGradientOverlay != null) {
            landscapeGradientOverlay.setVisibility(View.GONE);
        }
    }

    private void applyPortraitLayout() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getAvailableScreenHeight();
        float density = getResources().getDisplayMetrics().density;

        // info_panel 恢复全宽
        android.widget.FrameLayout.LayoutParams infoParams =
                (android.widget.FrameLayout.LayoutParams) infoPanel.getLayoutParams();
        infoParams.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.gravity = android.view.Gravity.START;
        infoPanel.setLayoutParams(infoParams);
        // 竖屏：内容水平居中，不强制垂直居中（从顶部向下排列）
        if (infoPanel instanceof android.widget.LinearLayout) {
            ((android.widget.LinearLayout) infoPanel).setGravity(
                    android.view.Gravity.CENTER_HORIZONTAL);
        }

        // 歌名歌手竖屏居中；非沉浸模式恢复原始 topMargin，沉浸模式保持不变
        if (!isImmersiveMode) {
            android.widget.LinearLayout.LayoutParams nameParams =
                    (android.widget.LinearLayout.LayoutParams) tvSongName.getLayoutParams();
            nameParams.topMargin = (int) (density * 16);
            tvSongName.setLayoutParams(nameParams);
            Log.d(TAG, "applyPortraitLayout: non-immersive, set name topMargin=16dp");
        } else {
            // 沉浸模式下重新应用歌名位置，防止被其他调用覆盖
            updateImmersiveLyricMargin(false);
            Log.d(TAG, "applyPortraitLayout: immersive, re-apply name margin");
        }
        tvSongName.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        tvArtist.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        // 竖屏恢复原始字号（歌名24sp，歌手18sp）
        tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24);
        tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);

        // 封面回顶部居中
        int coverSize = (int) (screenHeight * 0.25f);
        int coverMarginTop = (int) (density * 32);
        int coverNameGap = (int) (density * 16); // 封面和歌名间距
        android.widget.FrameLayout.LayoutParams coverParams =
                new android.widget.FrameLayout.LayoutParams(coverSize, coverSize);
        coverParams.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP;
        coverParams.topMargin = coverMarginTop;
        coverView.setLayoutParams(coverParams);

        // 竖屏封面占位：非沉浸+非全屏歌词时推歌名到封面下方
        boolean isFullLyric = lyricView != null &&
                lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
        if (!isImmersiveMode && !isFullLyric) {
            updateCoverPlaceholder();
        } else {
            coverPlaceholder.setVisibility(View.GONE);
        }

        // 频谱高度
        spectrumView.getLayoutParams().height = (int) (screenHeight * 0.10f);
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

        String title = cleanSongTitle(song.title);
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
        coverView.setImageResource(R.drawable.default_cover);
        // 横屏沉浸下，切换间隙隐藏 foreground 渐变，避免默认封面+渐变的闪烁
        if (isImmersiveMode && isLandscapeMode) {
            coverView.setForeground(null);
        }

        executor.execute(() -> {
            // 1. 提取音频文件内嵌封面
            Bitmap embedded = CoverFetcher.extractEmbeddedCover(song.filePath);
            if (embedded != null) {
                // 保存到缓存 + 公共目录（MediaStore）
                String coverName = com.jingxin.jingxinmusic.model.Song.toFileName(song.title, song.artist) + ".jpg";
                File cacheDir = getExternalFilesDir("covers");
                if (cacheDir != null) {
                    File f = new File(cacheDir, coverName);
                    if (!f.exists()) {
                        com.jingxin.jingxinmusic.model.Song.saveCoverToPublic(PlayerActivity.this, coverName, embedded);
                    }
                }
                uiHandler.post(() -> setCoverBitmap(embedded));
                // 通知 Service 更新 MediaSession metadata（含封面）
                notifyMetadataUpdate();
                return;
            }

            // 2. 本地封面缓存
            File coverDir = getExternalFilesDir("covers");
            if (coverDir != null) {
                String coverName = com.jingxin.jingxinmusic.model.Song.toFileName(song.title, song.artist) + ".jpg";
                File coverFile = new File(coverDir, coverName);
                if (coverFile.exists() && coverFile.length() > 0) {
                    Bitmap bmp = BitmapFactory.decodeFile(coverFile.getAbsolutePath());
                    if (bmp != null) {
                        Bitmap finalBitmap = bmp;
                        uiHandler.post(() -> setCoverBitmap(finalBitmap));
                        // 通知 Service 更新 MediaSession metadata（含封面）
                        notifyMetadataUpdate();
                        return;
                    }
                }
            }

            // 3. 在线获取
            fetchOnlineCover();
        });
    }

    private void fetchOnlineCover() {
        String title = cleanSongTitle(song.title);
        String artist = "<unknown>".equals(song.artist) ? "" : song.artist;
        CoverFetcher.fetchCover(title, artist, new CoverFetcher.CoverCallback() {
            @Override
            public void onCoverFetched(Bitmap coverBitmap) {
                // 保存到缓存 + 公共目录（MediaStore）
                String coverName = com.jingxin.jingxinmusic.model.Song.toFileName(song.title, song.artist) + ".jpg";
                com.jingxin.jingxinmusic.model.Song.saveCoverToPublic(PlayerActivity.this, coverName, coverBitmap);
                uiHandler.post(() -> setCoverBitmap(coverBitmap));
                // 通知 Service 更新 MediaSession metadata（含封面）
                notifyMetadataUpdate();
            }

            @Override
            public void onError(String errorMessage) {
                Log.d(TAG, "在线封面获取失败: " + errorMessage);
            }
        });
    }

    private void setCoverBitmap(Bitmap bitmap) {
        if (bitmap == null || isDestroyed()) return;

        // 沉浸模式：显示封面，提取主色调传给 immersiveOverlay
        if (isImmersiveMode) {
            if (isLandscapeMode) {
                // 横屏沉浸：封面直接用 coverView 显示，不旋转
                coverView.setVisibility(View.VISIBLE);
                coverView.setImageBitmap(bitmap);
                coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                coverView.stopAndResetRotation();
                // 确保封面尺寸正确（从竖屏切过来时 LayoutParams 可能没更新）
                applyLandscapeImmersiveCover();
                // 恢复横向渐变 foreground
                int overlayColor = immersiveOverlay.getOverlayColor();
                float borderWidthPx = getResources().getDisplayMetrics().density * 40f;
                if (coverBorderGradient == null) {
                    coverBorderGradient = new com.jingxin.jingxinmusic.view.CoverBorderGradientDrawable(
                            overlayColor, borderWidthPx);
                } else {
                    coverBorderGradient.setOverlayColor(overlayColor);
                }
                coverView.setForeground(coverBorderGradient);
            } else {
                // 竖屏沉浸：原图铺背景
                blurBackground.setScaleType(ImageView.ScaleType.MATRIX);
                blurBackground.setVisibility(View.VISIBLE);
                blurBackground.setAlpha(1.0f);
                coverView.setVisibility(View.GONE);
                applyImmersiveCoverMatrix(bitmap);
            }
            // 提取主色调（异步）
            executor.execute(() -> {
                int dominantColor = extractDominantColor(bitmap);
                if (!isDestroyed()) {
                    uiHandler.post(() -> {
                        if (immersiveOverlay != null) {
                            immersiveOverlay.setDominantColor(dominantColor);
                        }
                        // 横屏沉浸：同步更新封面边缘渐变颜色和渐变过渡层颜色
                        if (isLandscapeMode && coverBorderGradient != null) {
                            coverBorderGradient.setOverlayColor(immersiveOverlay.getOverlayColor());
                        }
                        if (isLandscapeMode && landscapeGradientOverlay != null) {
                            landscapeGradientOverlay.setOverlayColor(immersiveOverlay.getOverlayColor());
                        }
                    });
                }
            });
            return;
        }

        // 非沉浸模式：设置旋转封面 + 模糊背景
        // 全屏歌词竖屏模式下封面保持隐藏
        boolean isFullLyric = lyricView != null &&
                lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
        if (!(isFullLyric && !isLandscapeMode)) {
            coverView.setVisibility(View.VISIBLE);
        }
        coverView.setImageBitmap(bitmap);
        blurBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
        executor.execute(() -> {
            Bitmap blurred = BlurUtil.blur(PlayerActivity.this, bitmap, 25f);
            // 提取封面主色调
            int dominantColor = extractDominantColor(bitmap);
            if (blurred != null && !isDestroyed()) {
                uiHandler.post(() -> {
                    blurBackground.setImageBitmap(blurred);
                    blurBackground.setAlpha(0.6f);
                    blurBackground.setVisibility(View.VISIBLE);
                    if (!isNightMode && !isImmersiveMode) {
                        // 白天模式非沉浸：白色遮罩 + 封面主色调叠加
                        whiteOverlay.setVisibility(View.VISIBLE);
                        overlayView.setBackgroundColor(dominantColor);
                        overlayView.setAlpha(0.35f);
                        overlayView.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    /**
     * 从封面提取主色调（简单采样法）
     * 将图片缩小后采样中心区域像素，计算加权平均色
     * @param bitmap 封面图片
     * @return 主色调（ARGB）
     */
    /**
     * 沉浸模式下按35%屏幕高度缩放封面图
     * 使封面在可见区域内显示更多内容，不被过度裁切
     */
    private void applyImmersiveCoverMatrix(Bitmap bitmap) {
        if (bitmap == null || blurBackground == null) return;

        int viewWidth = blurBackground.getWidth();
        int viewHeight = blurBackground.getHeight();
        if (viewWidth == 0 || viewHeight == 0) {
            // View 还没布局完成，用实际布局尺寸
            viewWidth = getLayoutWidth();
            viewHeight = getAvailableScreenHeight();
        }

        int bmpWidth = bitmap.getWidth();
        int bmpHeight = bitmap.getHeight();
        if (bmpWidth == 0 || bmpHeight == 0) return;

        // 封面区域高度 = 屏幕可用高度 * 35%
        int coverAreaHeight = (int) (getAvailableScreenHeight() * 0.35f);

        // 缩放：宽度覆盖屏幕，高度覆盖封面区域（取较大值）
        float scaleW = (float) viewWidth / bmpWidth;
        float scaleH = (float) coverAreaHeight / bmpHeight;
        float scale = Math.max(scaleW, scaleH);

        // 构建 Matrix：缩放 + 居中到顶部
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setScale(scale, scale);
        // 水平居中
        float dx = (viewWidth - bmpWidth * scale) / 2f;
        matrix.postTranslate(dx, 0);

        blurBackground.setImageMatrix(matrix);
        blurBackground.setImageBitmap(bitmap);
    }

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
        
        if (count == 0) return ThemeColors.DOMINANT_COLOR_FALLBACK;
        
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
                            position = -1; // 历史播放，不在列表中
                            tvSongName.setText(s.title);
                            tvArtist.setText(s.artist);
                            tvCurrentTime.setText("00:00");
                            tvTotalTime.setText(Song.formatDuration(s.duration));
                            loadCover();
                            fetchLyrics();

                            if (bound && playerBinder != null) {
                                playerBinder.setPlaylist(allSongs);
                                playerBinder.playSong(s, position);
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
     * 切换沉浸封面模式
     * 长按歌词区域触发
     */
    private void toggleImmersiveMode() {
        isImmersiveMode = !isImmersiveMode;
        getSharedPreferences("theme", MODE_PRIVATE).edit().putBoolean("immersive", isImmersiveMode).apply();

        if (isImmersiveMode) {
            // 进入沉浸模式
            // 先设状态再VISIBLE，防止系统在状态未就绪时触发绘制
            immersiveOverlay.setNightMode(isNightMode);
            immersiveOverlay.setLandscapeMode(isLandscapeMode);
            immersiveOverlay.setVisibility(View.VISIBLE);
            // 隐藏非沉浸遮罩
            overlayView.setVisibility(View.GONE);
            whiteOverlay.setVisibility(View.GONE);
            // 隐藏封面占位
            coverPlaceholder.setVisibility(View.GONE);
            // 隐藏模糊背景（横屏沉浸不用 blurBackground，封面用 coverView 直接显示）
            blurBackground.setVisibility(View.GONE);

            if (isLandscapeMode) {
                // 横屏沉浸：immersiveOverlay(底层实色) → coverView(封面) → 渐变过渡层
                // 夜间模式显示半透明黑色遮罩
                immersiveDarkOverlay.setVisibility(isNightMode ? View.VISIBLE : View.GONE);
                // 先定位封面
                applyLandscapeImmersiveCover();
                coverView.setVisibility(View.VISIBLE);
                // 创建并显示渐变过渡层
                if (landscapeGradientOverlay == null) {
                    landscapeGradientOverlay = new com.jingxin.jingxinmusic.view.LandscapeGradientOverlay(this);
                    landscapeGradientOverlay.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
                    ((android.widget.FrameLayout) findViewById(R.id.root_layout)).addView(landscapeGradientOverlay);
                }
                landscapeGradientOverlay.setOverlayColor(immersiveOverlay.getOverlayColor());
                landscapeGradientOverlay.setVisibility(View.VISIBLE);
                // 排列层级
                moveCoverBelowOverlay();
                // 歌词 margin 保持横屏非沉浸的值
                resetLyricMargin();
            } else {
                // 竖屏沉浸：原图铺背景，旋转封面隐藏
                blurBackground.setVisibility(View.VISIBLE);
                blurBackground.setAlpha(1.0f);
                // 确保 immersiveOverlay 在 blurBackground 上面，防止被盖住导致渐变丢失
                restoreOverlayHierarchy();
                // 夜间模式显示半透明黑色遮罩，压暗封面保留色调
                immersiveDarkOverlay.setVisibility(isNightMode ? View.VISIBLE : View.GONE);
                coverView.setVisibility(View.GONE);
                // 调整歌词 margin
                boolean isFull = lyricView != null &&
                        lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
                if (isFull) {
                    resetLyricMargin();
                } else {
                    updateImmersiveLyricMargin(false);
                }
            }
            // 重新加载封面
            loadCover();
        } else {
            // 退出沉浸模式
            immersiveOverlay.setVisibility(View.GONE);
            immersiveOverlay.setLandscapeMode(false);
            blurBackground.setVisibility(View.GONE);
            coverView.setVisibility(View.VISIBLE);
            // 隐藏渐变过渡层
            if (landscapeGradientOverlay != null) {
                landscapeGradientOverlay.setVisibility(View.GONE);
            }
            // 隐藏夜间暗层
            immersiveDarkOverlay.setVisibility(View.GONE);
            // 恢复封面为圆形裁剪，清除边缘渐变
            coverView.setClipToOutline(true);
            coverView.setBackgroundResource(R.drawable.circle_cover_background);
            coverView.setForeground(null);
            // 恢复封面层级到 info_panel 上方
            moveCoverAboveInfoPanel();
            // 恢复封面布局（横屏：圆形右侧居中；竖屏：顶部居中）
            if (isLandscapeMode) {
                applyLandscapeLayout();
                // 恢复旋转
                if (playerBinder != null && playerBinder.isPlaying()) {
                    coverView.startRotation();
                }
            }
            // 恢复歌词原始 margin
            resetLyricMargin();
            // 恢复封面占位（竖屏非沉浸下，把歌名推到封面下方）
            if (!isLandscapeMode) {
                updateCoverPlaceholder();
            }
            // 恢复非沉浸遮罩
            updateThemeUI();
            // 恢复旋转封面图片
            loadCover();
        }

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
                overlayView.setBackgroundColor(ThemeColors.NIGHT_OVERLAY);
                applyTextTheme(true);
                applyButtonTheme(true);
            } else {
                // 白天模式：白色背景，深色文字
                blurBackground.setAlpha(0.5f);
                blurBackground.setVisibility(View.VISIBLE);
                whiteOverlay.setVisibility(View.VISIBLE);
                overlayView.setVisibility(View.VISIBLE);
                overlayView.setAlpha(0.35f);
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
            tvSongName.setTextColor(ThemeColors.NIGHT_TEXT_PRIMARY);
            tvArtist.setTextColor(ThemeColors.NIGHT_TEXT_SECONDARY);
            tvCurrentTime.setTextColor(ThemeColors.NIGHT_TEXT_PRIMARY);
            tvTotalTime.setTextColor(ThemeColors.NIGHT_TEXT_PRIMARY);
        } else {
            tvSongName.setTextColor(ThemeColors.DAY_TEXT_PRIMARY);
            tvArtist.setTextColor(ThemeColors.DAY_TEXT_TERTIARY);
            tvCurrentTime.setTextColor(ThemeColors.DAY_TEXT_PRIMARY);
            tvTotalTime.setTextColor(ThemeColors.DAY_TEXT_PRIMARY);
        }
    }

    /**
     * 统一设置按钮主题颜色
     */
    private void applyButtonTheme(boolean isNight) {
        ImageView[] buttons = {btnPlayPause, btnPrevious, btnNext,
                btnHistory, btnFavorite, btnPlayOrder, btnTheme, btnBack};
        if (isNight) {
            for (ImageView btn : buttons) btn.clearColorFilter();
            applySeekBarThemeColor(ThemeColors.NIGHT_TEXT_PRIMARY);
        } else {
            int buttonColor = ThemeColors.DAY_TEXT_PRIMARY;
            for (ImageView btn : buttons) btn.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            applySeekBarThemeColor(ThemeColors.DAY_TEXT_PRIMARY);
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
    private String cleanSongTitle(String title) {
        if (title == null) return "";
        String s = title.trim();
        // 1. 去掉音质标记：[mqms]、[mqms2]、[320k]、[FLAC]、[HQ]、[SQ] 等
        s = s.replaceAll("\\s*\\[(?:mqm[s]?[s2-9]?|[0-9]+k|FLAC|HQ|SQ|CD|Hi-?Res)\\]\\s*", " ");
        // 2. 去掉所有中文/英文括号及内容：(xxx)、（xxx）
        s = s.replaceAll("[（(][^)）]*[)）]", "");
        // 3. 去掉书名号：《xxx》、「xxx」
        s = s.replaceAll("[《「」》]", "");
        // 4. 去掉开头的数字序号：01 歌名、01.歌名、01-歌名
        s = s.replaceAll("^\\d{1,2}[.\\s\\-]+", "");
        // 5. 去掉多余空格并 trim
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /**
     * 保存当前播放歌曲信息到 SharedPreferences，下次启动时自动恢复
     * 同时保存播放队列模式，恢复时能继续在原队列（全部/收藏/目录）中播放
     */
    private void saveLastPlayed() {
        if (song == null) return;
        android.content.SharedPreferences.Editor editor = getSharedPreferences("last_played", MODE_PRIVATE).edit()
                .putLong("song_id", song.id)
                .putString("song_title", song.title)
                .putString("song_artist", song.artist)
                .putString("song_album", song.album)
                .putLong("song_duration", song.duration)
                .putString("song_path", song.filePath != null ? song.filePath : "")
                .putString("song_uri", song.contentUri != null ? song.contentUri : "")
                .putString("album_art", song.albumArt != null ? song.albumArt : "")
                .putInt("position", position)
                .putBoolean("has_last", true)
                .putString("playlist_mode", playlistMode != null ? playlistMode : "all");
        // 目录模式：保存该目录下所有歌曲路径
        if ("folder".equals(playlistMode)) {
            List<String> folderPaths = getIntent().getStringArrayListExtra("folder_song_paths");
            if (folderPaths != null && !folderPaths.isEmpty()) {
                editor.putStringSet("folder_song_paths", new java.util.HashSet<>(folderPaths));
            }
        } else {
            // 非目录模式清除旧数据
            editor.remove("folder_song_paths");
        }
        editor.commit();
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

                            // Visualizer FFT 数据：fft[0]=实部, fft[1]=虚部, fft[2]=实部, fft[3]=虚部...
                            // 跳过 DC 和 Nyquist（前2个），取前128个频段
                            float[] magnitudes = new float[128];
                            float maxMag = 0;
                            for (int i = 0; i < 128; i++) {
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

                    float[] magnitudes = new float[128];
                    float maxMag = 0;
                    for (int bar = 0; bar < 128; bar++) {
                        int k = bar * 3 + 1;
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
}
