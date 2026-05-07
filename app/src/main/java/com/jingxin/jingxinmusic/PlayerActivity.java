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
import android.net.Uri;
import android.os.Build;
import android.media.audiofx.Visualizer;
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
import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.service.MusicPlayerService.MusicPlayerBinder;
import com.jingxin.jingxinmusic.util.BlurUtil;
import com.jingxin.jingxinmusic.util.CoverFetcher;
import com.jingxin.jingxinmusic.util.FavoriteManager;
import com.jingxin.jingxinmusic.util.HistoryManager;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.util.LrcParser;
import com.jingxin.jingxinmusic.util.LyricFetcher;
import com.jingxin.jingxinmusic.util.MusicScanner;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.RotatingCoverView;
import com.jingxin.jingxinmusic.view.SpectrumView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.AlertDialog;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Rect;

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

    // 主题
    private boolean isNightMode = true;  // 默认夜间模式
    private boolean isFavorite = false;  // 当前歌曲是否已收藏

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
                Song newSong = new Song();
                newSong.id = intent.getLongExtra(MusicPlayerService.EXTRA_SONG_ID, 0);
                newSong.title = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_TITLE);
                newSong.artist = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_ARTIST);
                newSong.album = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_ALBUM);
                newSong.duration = intent.getLongExtra(MusicPlayerService.EXTRA_DURATION, 0);
                newSong.filePath = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_PATH);
                newSong.contentUri = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_URI);
                newSong.albumArt = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_ALBUM_ART);
                newSong.displayName = newSong.title;
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
                    tvCurrentTime.setText(formatTime(currentPosition));
                    tvTotalTime.setText(formatTime(duration));
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
        song = new Song();
        song.id = getIntent().getLongExtra("song_id", 0);
        song.title = getIntent().getStringExtra("song_title");
        song.artist = getIntent().getStringExtra("song_artist");
        song.album = getIntent().getStringExtra("song_album");
        song.duration = getIntent().getLongExtra("song_duration", 0);
        song.filePath = getIntent().getStringExtra("song_path");
        song.contentUri = getIntent().getStringExtra("song_uri");
        song.albumArt = getIntent().getStringExtra("album_art");
        position = getIntent().getIntExtra("position", 0);
        playlistMode = getIntent().getStringExtra("playlist_mode");
        if (playlistMode == null) playlistMode = "all";
        resumePlay = getIntent().getBooleanExtra("resume_play", false);
        song.displayName = song.title;

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

        // 封面和频谱高度：相对屏幕高度（减去状态栏+导航栏）
        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        int navBarResourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        int navBarHeight = 0;
        if (navBarResourceId > 0) navBarHeight = getResources().getDimensionPixelSize(navBarResourceId);
        int availableHeight = getResources().getDisplayMetrics().heightPixels - statusBarHeight - navBarHeight;
        coverView.getLayoutParams().height = (int) (availableHeight * 0.25f);
        coverView.getLayoutParams().width = (int) (availableHeight * 0.25f);
        spectrumView.getLayoutParams().height = (int) (availableHeight * 0.10f);

        // 读取主题状态并同步所有 UI
        isNightMode = getSharedPreferences("theme", MODE_PRIVATE).getBoolean("isNight", true);
        updateThemeUI();
        lyricView.setThemeMode(isNightMode
                ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);

        // 显示歌曲信息
        tvSongName.setText(song.title);
        tvArtist.setText(song.artist);
        tvTotalTime.setText(Song.formatDuration(song.duration));

        // 进度条
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvCurrentTime.setText(formatTime(progress));
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
                com.jingxin.jingxinmusic.view.LyricView.DisplayMode newMode = lyricView.toggleMode();
                updateLayoutForMode(newMode);
            }
        });
        lyricView.setOnModeChangeListener(newMode -> updateLayoutForMode(newMode));
        btnTheme.setOnClickListener(v -> toggleTheme());
        btnBack.setOnClickListener(v -> finish());

        // 注册广播接收器（监听切歌和播放状态）
            IntentFilter filter = new IntentFilter();
            filter.addAction(MusicPlayerService.ACTION_SONG_CHANGED);
            filter.addAction(MusicPlayerService.ACTION_PLAY_STATE_CHANGED);
            filter.addAction(MusicPlayerService.ACTION_PLAY_ORDER_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(songChangedReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(songChangedReceiver, filter);
        }

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
        if (mode == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL) {
            // 多行模式：隐藏封面、歌名、歌手，歌词区域占满
            coverView.setVisibility(View.GONE);
            tvSongName.setVisibility(View.GONE);
            tvArtist.setVisibility(View.GONE);
        } else {
            // 其他模式：显示封面、歌名、歌手
            coverView.setVisibility(View.VISIBLE);
            tvSongName.setVisibility(View.VISIBLE);
            tvArtist.setVisibility(View.VISIBLE);
        }
    }

    private void updatePlayPauseButton(boolean isPlaying) {
        if (isPlaying) {
            btnPlayPause.setImageResource(R.drawable.ic_pause);
            coverView.startRotation();
            spectrumView.setPlaying(true);
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play);
            coverView.stopRotation();
            spectrumView.setPlaying(false);
        }
    }

    private void updateSongDisplay(Song s) {
        if (s == null) return;
        song = s;
        tvSongName.setText(s.title);
        tvArtist.setText(s.artist);
        tvCurrentTime.setText("00:00");
        tvTotalTime.setText(Song.formatDuration(s.duration));
        loadCover();
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
        });
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
        // 设置旋转封面
        coverView.setImageBitmap(bitmap);
        // 设置模糊背景 + 提取主色调
        executor.execute(() -> {
            Bitmap blurred = BlurUtil.blur(PlayerActivity.this, bitmap, 25f);
            // 提取封面主色调
            int dominantColor = extractDominantColor(bitmap);
            if (blurred != null && !isDestroyed()) {
                uiHandler.post(() -> {
                    blurBackground.setImageBitmap(blurred);
                    blurBackground.setAlpha(0.6f);
                    if (!isNightMode) {
                        // 白天模式：白色遮罩 + 封面主色调叠加
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
        
        if (count == 0) return Color.parseColor("#CCF5F5F5"); // 回退：淡白
        
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

    private void toggleTheme() {
        isNightMode = !isNightMode;
        getSharedPreferences("theme", MODE_PRIVATE).edit().putBoolean("isNight", isNightMode).apply();
        updateThemeUI();
        if (lyricView != null) {
            lyricView.setThemeMode(isNightMode
                    ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                    : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
        }
        android.widget.Toast.makeText(this, isNightMode ? "夜间模式" : "白天模式", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void updateThemeUI() {
        if (isNightMode) {
            // 夜间模式：黑色背景，白色文字
            blurBackground.setAlpha(0.6f);
            whiteOverlay.setVisibility(View.GONE);
            overlayView.setVisibility(View.VISIBLE);
            overlayView.setBackgroundColor(Color.parseColor("#55000000"));
            tvSongName.setTextColor(Color.parseColor("#FFFFFF"));
            tvArtist.setTextColor(Color.parseColor("#AAAAAA"));
            tvCurrentTime.setTextColor(Color.parseColor("#FFFFFF"));
            tvTotalTime.setTextColor(Color.parseColor("#FFFFFF"));
            btnPlayPause.clearColorFilter();
            btnPrevious.clearColorFilter();
            btnNext.clearColorFilter();
            btnHistory.clearColorFilter();
            btnFavorite.clearColorFilter();
            btnPlayOrder.clearColorFilter();
            btnTheme.clearColorFilter();
            btnBack.clearColorFilter();
            applySeekBarThemeColor(Color.parseColor("#FFFFFF"));
        } else {
            // 白天模式：白色背景，深色文字
            blurBackground.setAlpha(0.5f);
            whiteOverlay.setVisibility(View.VISIBLE);
            overlayView.setVisibility(View.VISIBLE);
            overlayView.setAlpha(0.35f);
            tvSongName.setTextColor(Color.parseColor("#333333"));
            tvArtist.setTextColor(Color.parseColor("#666666"));
            tvCurrentTime.setTextColor(Color.parseColor("#333333"));
            tvTotalTime.setTextColor(Color.parseColor("#333333"));
            int buttonColor = Color.parseColor("#333333");
            btnPlayPause.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            btnPrevious.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            btnNext.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            btnHistory.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            btnFavorite.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            btnPlayOrder.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            btnTheme.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            btnBack.setColorFilter(buttonColor, PorterDuff.Mode.SRC_IN);
            applySeekBarThemeColor(Color.parseColor("#333333"));
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

    public static String formatTime(long ms) {
        int totalSeconds = (int) (ms / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
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
     */
    private void saveLastPlayed() {
        if (song == null) return;
        getSharedPreferences("last_played", MODE_PRIVATE).edit()
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
                .commit();
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
        // 释放 Visualizer
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
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
