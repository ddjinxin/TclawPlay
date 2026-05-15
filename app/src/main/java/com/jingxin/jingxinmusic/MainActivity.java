package com.jingxin.jingxinmusic;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.jingxin.jingxinmusic.adapter.SongAdapter;
import com.jingxin.jingxinmusic.model.FolderInfo;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.service.MusicPlayerService.MusicPlayerBinder;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.util.FavoriteManager;
import com.jingxin.jingxinmusic.util.MusicScanner;
import com.jingxin.jingxinmusic.util.ThemeColors;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 歌曲列表页面
 * 三种模式：目录、全部、收藏
 */
public class MainActivity extends AppCompatActivity implements SongAdapter.OnSongClickListener, SongAdapter.OnFolderClickListener {

    private static final String TAG = "MainActivity";

    private SongAdapter adapter;
    private TextView tvSongCount;
    private TextView tvLoading;
    private TextView tvCopyright;
    private TextView tvTitle;
    private EditText etSearch;
    private ImageView btnTheme;
    private ImageView btnClose;
    private View rootLayout;
    private View tabBar;
    private View titleBar;
    private View tabDivider1;
    private View tabDivider2;
    private RecyclerView rvList;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tabFolder;
    private TextView tabAll;
    private TextView tabFavorite;

    // 主题
    private boolean isNightMode = true;
    private SharedPreferences themePrefs;

    private File favDir;

    // 是否已从列表页跳转过播放页（防止返回时重复跳转）
    private boolean hasAutoResumed = false;

    private ActivityResultLauncher<String> permissionLauncher;
    private ActivityResultLauncher<String[]> multiPermissionLauncher;

    // 记录当前正在请求的权限类型，用于区分回调逻辑
    private String currentPermissionRequest;

    // MediaStore ContentObserver：监听音乐文件变化（U盘索引完成、增删音乐等）
    private android.database.ContentObserver mediaStoreObserver;
    private boolean isScanning = false; // 防止重复扫描
    private final Handler scanDebounceHandler = new Handler();
    private static final int SCAN_DEBOUNCE_MS = 500; // 防抖间隔

    // Mini 播放条
    private View miniPlayer;
    private TextView miniSongTitle;
    private TextView miniSongArtist;
    private ImageView miniPlayPause;

    // 播放服务绑定
    private MusicPlayerBinder playerBinder;
    private boolean bound = false;

    // 监听播放状态变化的广播接收器
    private BroadcastReceiver playStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (MusicPlayerService.ACTION_SONG_CHANGED.equals(action)) {
                // 歌曲切换了，更新 mini 播放条
                String title = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_TITLE);
                String artist = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_ARTIST);
                miniSongTitle.setText(title);
                miniSongArtist.setText(artist);
                miniPlayer.setVisibility(View.VISIBLE);
            } else if (MusicPlayerService.ACTION_PLAY_STATE_CHANGED.equals(action)) {
                // 播放状态变化，更新按钮图标
                boolean playing = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false);
                miniPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                // 同时更新歌曲信息（可能第一次进来）
                String title = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_TITLE);
                String artist = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_ARTIST);
                if (title != null) {
                    miniSongTitle.setText(title);
                    miniSongArtist.setText(artist);
                    miniPlayer.setVisibility(View.VISIBLE);
                }
            }
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerBinder = (MusicPlayerBinder) service;
            bound = true;
            // 连接后立即更新 mini 播放条
            updateMiniPlayerFromService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playerBinder = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        favDir = getExternalFilesDir("favorites");

        // 读取主题
        themePrefs = getSharedPreferences("theme", MODE_PRIVATE);
        isNightMode = themePrefs.getBoolean("isNight", true);

        // 初始化视图
        tvSongCount = findViewById(R.id.tv_song_count);
        tvLoading = findViewById(R.id.tv_loading);
        tvTitle = findViewById(R.id.tv_title);
        etSearch = findViewById(R.id.et_search);
        btnTheme = findViewById(R.id.theme_button);
        rootLayout = findViewById(R.id.root_layout);
        tabBar = findViewById(R.id.tab_bar);
        titleBar = findViewById(R.id.title_bar);
        tabDivider1 = findViewById(R.id.tab_divider_1);
        tabDivider2 = findViewById(R.id.tab_divider_2);
        tvLoading = findViewById(R.id.tv_loading);
        tvCopyright = findViewById(R.id.tv_copyright);
        rvList = findViewById(R.id.rv_song_list);

        // Mini 播放条（必须在 updateThemeUI 之前初始化）
        miniPlayer = findViewById(R.id.mini_player);
        miniSongTitle = findViewById(R.id.mini_song_title);
        miniSongArtist = findViewById(R.id.mini_song_artist);
        miniPlayPause = findViewById(R.id.mini_play_pause);

        // 关闭按钮（左上角）
        btnClose = findViewById(R.id.close_button);
        btnClose.setOnClickListener(v -> {
            // 停止播放服务并关闭应用
            stopService(new Intent(MainActivity.this, com.jingxin.jingxinmusic.service.MusicPlayerService.class));
            finishAffinity();
            System.exit(0);
        });

        // Tab
        tabFolder = findViewById(R.id.tab_folder);
        tabAll = findViewById(R.id.tab_all);
        tabFavorite = findViewById(R.id.tab_favorite);

        // 设置 RecyclerView
        rvList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SongAdapter(this);
        adapter.setOnSongClickListener(this);
        adapter.setOnFolderClickListener(this);
        rvList.setAdapter(adapter);

        // 监听窗口宽度变化（分屏/多窗口拖动时重新布局）
        View rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.addOnLayoutChangeListener(
                    (View v, int left, int top, int right, int bottom,
                     int oldLeft, int oldTop, int oldRight, int oldBottom) -> {
                        if (isFinishing() || isDestroyed()) return;
                        int newWidth = right - left;
                        int oldWidth = oldRight - oldLeft;
                        if (oldWidth > 0 && newWidth != oldWidth) {
                            // 窗口宽度变化，重新设置 LayoutManager 强制所有 Item 重建
                            if (rvList != null) {
                                rvList.post(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    rvList.setLayoutManager(new LinearLayoutManager(MainActivity.this));
                                    if (adapter != null) {
                                        adapter.notifyDataSetChanged();
                                    }
                                });
                            }
                        }
                    });
        }

        // 主题按钮
        btnTheme.setOnClickListener(v -> {
            isNightMode = !isNightMode;
            themePrefs.edit().putBoolean("isNight", isNightMode).apply();
            updateThemeUI();
            android.widget.Toast.makeText(this, isNightMode ? "夜间模式" : "白天模式", android.widget.Toast.LENGTH_SHORT).show();
        });

        // 应用主题
        updateThemeUI();

        // 根布局初始不可见，等扫描完成后再决定显示列表还是跳转播放页
        rootLayout.setVisibility(View.INVISIBLE);

        // Mini 播放条（在 updateThemeUI 之后才能引用，但 updateThemeUI 需要它，所以提前初始化）
        // 已在上方 findViewById 区域初始化，此处仅绑定事件

        // Tab 切换
        tabFolder.setOnClickListener(v -> switchTab(0));
        tabAll.setOnClickListener(v -> switchTab(1));
        tabFavorite.setOnClickListener(v -> switchTab(2));

        // 搜索功能
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
                updateCountText();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 权限请求
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (Manifest.permission.POST_NOTIFICATIONS.equals(currentPermissionRequest)) {
                        // 通知权限回调
                        if (!isGranted) {
                            showNotificationPermissionDeniedDialog();
                        }
                    } else {
                        // 存储权限回调
                        if (isGranted) {
                            scanMusic();
                        } else {
                            tvLoading.setVisibility(View.GONE);
                            tvSongCount.setText("需要存储权限才能扫描音乐");
                        }
                    }
                    currentPermissionRequest = null;
                });

        // 多权限请求（Android 12 及以下：READ + WRITE_EXTERNAL_STORAGE）
        multiPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean readGranted = Boolean.TRUE.equals(result.get(Manifest.permission.READ_EXTERNAL_STORAGE));
                    boolean writeGranted = Boolean.TRUE.equals(result.get(Manifest.permission.WRITE_EXTERNAL_STORAGE));
                    if (readGranted) {
                        scanMusic();
                        if (!writeGranted) {
                            // 写入权限被拒，判断是否勾了"不再询问"
                            if (!shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                                // 用户勾了"不再询问"，引导去设置页手动开启
                                showWriteStoragePermissionDeniedDialog();
                            }
                            // 否则仅本次拒绝，下次启动会再次请求
                        }
                        requestNotificationPermissionIfNeeded();
                    } else {
                        tvLoading.setVisibility(View.GONE);
                        tvSongCount.setText("需要存储权限才能扫描音乐");
                    }
                });

        checkPermissionAndScan();

        // 点击 mini 播放条主体 → 跳转播放页
        findViewById(R.id.mini_player_info).setOnClickListener(v -> openPlayerFromMini());

        // 点击播放/暂停
        miniPlayPause.setOnClickListener(v -> {
            if (bound && playerBinder != null) {
                playerBinder.togglePlayPause();
            }
        });

        // 绑定播放服务（不 start，Service 由 PlayerActivity 管理）
        Intent serviceIntent = new Intent(this, MusicPlayerService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 注册广播接收器（监听歌曲切换和播放状态）
        IntentFilter filter = new IntentFilter();
        filter.addAction(MusicPlayerService.ACTION_SONG_CHANGED);
        filter.addAction(MusicPlayerService.ACTION_PLAY_STATE_CHANGED);
        CompatUtil.safeRegisterReceiver(this, playStateReceiver, filter);

        // 注册 MediaStore ContentObserver，监听音乐文件变化（车机U盘索引、增删音乐等）
        mediaStoreObserver = new android.database.ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                // 防抖：500ms内多次通知只扫描一次
                scanDebounceHandler.removeCallbacks(scanDebounceRunnable);
                scanDebounceHandler.postDelayed(scanDebounceRunnable, SCAN_DEBOUNCE_MS);
            }
        };
        getContentResolver().registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 分屏/多窗口模式下，窗口尺寸变化时重新布局
        View rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                // 通知 RecyclerView 重新测量布局
                if (rvList != null) {
                    rvList.requestLayout();
                }
                // 通知 adapter 数据刷新以适配新宽度
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 确保根布局可见（从播放页返回或首次恢复）
        rootLayout.setVisibility(View.VISIBLE);
        // 同步主题状态（播放页可能切换了）
        boolean savedNight = themePrefs.getBoolean("isNight", true);
        if (savedNight != isNightMode) {
            isNightMode = savedNight;
            updateThemeUI();
        }
        // 每次回到列表页刷新收藏（播放页可能刚加了收藏）
        refreshFavorites();
        // 更新 mini 播放条
        updateMiniPlayerFromService();
    }

    // ========== Tab 切换 ==========

    private void switchTab(int mode) {
        // 更新 Tab 样式
        int activeColor = isNightMode ? ThemeColors.NIGHT_TAB_ACTIVE : ThemeColors.DAY_TAB_ACTIVE;
        int inactiveColor = isNightMode ? ThemeColors.NIGHT_TAB_INACTIVE : ThemeColors.DAY_TAB_INACTIVE;
        tabFolder.setTextColor(mode == 0 ? activeColor : inactiveColor);
        tabFolder.setTypeface(null, mode == 0 ? Typeface.BOLD : Typeface.NORMAL);
        tabAll.setTextColor(mode == 1 ? activeColor : inactiveColor);
        tabAll.setTypeface(null, mode == 1 ? Typeface.BOLD : Typeface.NORMAL);
        tabFavorite.setTextColor(mode == 2 ? activeColor : inactiveColor);
        tabFavorite.setTypeface(null, mode == 2 ? Typeface.BOLD : Typeface.NORMAL);

        // 切换模式
        adapter.switchMode(mode);
        etSearch.setText("");

        // 收藏模式下刷新数据
        if (mode == 2) {
            refreshFavorites();
        }

        updateCountText();

        // 切换后应用当前主题到列表项
        applyThemeToRecyclerViewItems();
    }

    private void updateCountText() {
        switch (adapter.getCurrentMode()) {
            case 0:
                tvSongCount.setText(adapter.getFolderCount() + " 个目录");
                break;
            case 1:
                tvSongCount.setText(adapter.getSongCount() + " 首歌曲");
                break;
            case 2:
                tvSongCount.setText(adapter.getSongCount() + " 首收藏");
                break;
        }
    }

    // ========== 主题 ==========

    private void updateThemeUI() {
        if (isNightMode) {
            rootLayout.setBackgroundColor(ThemeColors.NIGHT_BG);
            titleBar.setBackgroundColor(ThemeColors.NIGHT_BAR_BG);
            tabBar.setBackgroundColor(ThemeColors.NIGHT_BAR_BG);
            tvTitle.setTextColor(ThemeColors.NIGHT_TEXT_PRIMARY);
            etSearch.setTextColor(ThemeColors.NIGHT_TEXT_PRIMARY);
            etSearch.setHintTextColor(ThemeColors.NIGHT_TEXT_TERTIARY);
            tvSongCount.setTextColor(ThemeColors.NIGHT_TEXT_TERTIARY);
            tvLoading.setTextColor(ThemeColors.NIGHT_TEXT_TERTIARY);
            tvCopyright.setTextColor(ThemeColors.NIGHT_TEXT_COPYRIGHT);
            tabDivider1.setBackgroundColor(ThemeColors.NIGHT_DIVIDER);
            tabDivider2.setBackgroundColor(ThemeColors.NIGHT_DIVIDER);
            btnTheme.clearColorFilter();
            btnClose.clearColorFilter();
            // mini 播放条
            miniPlayer.setBackgroundColor(ThemeColors.NIGHT_BAR_BG);
            miniSongTitle.setTextColor(ThemeColors.NIGHT_TEXT_PRIMARY);
            miniSongArtist.setTextColor(ThemeColors.NIGHT_TEXT_SECONDARY);
            miniPlayPause.clearColorFilter();
        } else {
            rootLayout.setBackgroundColor(ThemeColors.DAY_BG);
            titleBar.setBackgroundColor(ThemeColors.DAY_BAR_BG);
            tabBar.setBackgroundColor(ThemeColors.DAY_BAR_BG);
            tvTitle.setTextColor(ThemeColors.DAY_TEXT_PRIMARY);
            etSearch.setTextColor(ThemeColors.DAY_TEXT_PRIMARY);
            etSearch.setHintTextColor(ThemeColors.DAY_TEXT_SECONDARY);
            tvSongCount.setTextColor(ThemeColors.DAY_TEXT_SECONDARY);
            tvLoading.setTextColor(ThemeColors.DAY_TEXT_SECONDARY);
            tvCopyright.setTextColor(ThemeColors.DAY_TEXT_COPYRIGHT);
            tabDivider1.setBackgroundColor(ThemeColors.DAY_DIVIDER);
            tabDivider2.setBackgroundColor(ThemeColors.DAY_DIVIDER);
            btnTheme.setColorFilter(ThemeColors.DAY_TEXT_PRIMARY, PorterDuff.Mode.SRC_IN);
            btnClose.setColorFilter(ThemeColors.DAY_TEXT_PRIMARY, PorterDuff.Mode.SRC_IN);
            // mini 播放条
            miniPlayer.setBackgroundColor(ThemeColors.DAY_MINI_BG);
            miniSongTitle.setTextColor(ThemeColors.DAY_TEXT_PRIMARY);
            miniSongArtist.setTextColor(ThemeColors.DAY_TEXT_SECONDARY);
            miniPlayPause.setColorFilter(ThemeColors.DAY_TEXT_PRIMARY, PorterDuff.Mode.SRC_IN);
        }
        // 更新 Tab 文字颜色
        int mode = adapter.getCurrentMode();
        int activeColor = isNightMode ? ThemeColors.NIGHT_TAB_ACTIVE : ThemeColors.DAY_TAB_ACTIVE;
        int inactiveColor = isNightMode ? ThemeColors.NIGHT_TAB_INACTIVE : ThemeColors.DAY_TAB_INACTIVE;
        tabFolder.setTextColor(mode == 0 ? activeColor : inactiveColor);
        tabAll.setTextColor(mode == 1 ? activeColor : inactiveColor);
        tabFavorite.setTextColor(mode == 2 ? activeColor : inactiveColor);
        // 同步 adapter 主题字段（新滚出的 item 会用正确颜色）
        adapter.setNightMode(isNightMode);
        // 刷新当前可见 item
        applyThemeToRecyclerViewItems();
    }

    /**
     * 直接遍历 RecyclerView 子 View 修改颜色，不触发 adapter 刷新
     */
    private void applyThemeToRecyclerViewItems() {
        rvList.post(() -> {
            int childCount = rvList.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = rvList.getChildAt(i);
                // 根据 view id 区分目录和歌曲
                if (child.findViewById(R.id.tv_folder_name) != null) {
                    // 目录项
                    TextView tvName = child.findViewById(R.id.tv_folder_name);
                    TextView tvCount = child.findViewById(R.id.tv_folder_count);
                    ImageView ivArrow = child.findViewById(R.id.iv_folder_arrow);
                    if (isNightMode) {
                        child.setBackgroundColor(ThemeColors.NIGHT_ITEM_BG);
                        tvName.setTextColor(ThemeColors.NIGHT_TEXT_PRIMARY);
                        tvCount.setTextColor(ThemeColors.NIGHT_TEXT_SECONDARY);
                        ivArrow.setColorFilter(ThemeColors.NIGHT_TEXT_SECONDARY);
                    } else {
                        child.setBackgroundColor(ThemeColors.DAY_ITEM_BG);
                        tvName.setTextColor(ThemeColors.DAY_TEXT_PRIMARY);
                        tvCount.setTextColor(ThemeColors.DAY_TEXT_SECONDARY);
                        ivArrow.setColorFilter(ThemeColors.DAY_TEXT_SECONDARY);
                    }
                } else if (child.findViewById(R.id.tv_song_title) != null) {
                    // 歌曲项
                    TextView tvTitle = child.findViewById(R.id.tv_song_title);
                    TextView tvArtist = child.findViewById(R.id.tv_song_artist);
                    TextView tvDuration = child.findViewById(R.id.tv_song_duration);
                    if (isNightMode) {
                        child.setBackgroundColor(ThemeColors.NIGHT_ITEM_BG);
                        tvTitle.setTextColor(ThemeColors.NIGHT_TEXT_PRIMARY);
                        tvArtist.setTextColor(ThemeColors.NIGHT_TEXT_SECONDARY);
                        tvDuration.setTextColor(ThemeColors.NIGHT_TEXT_TERTIARY);
                    } else {
                        child.setBackgroundColor(ThemeColors.DAY_ITEM_BG);
                        tvTitle.setTextColor(ThemeColors.DAY_TEXT_PRIMARY);
                        tvArtist.setTextColor(ThemeColors.DAY_TEXT_SECONDARY);
                        tvDuration.setTextColor(ThemeColors.DAY_TEXT_SECONDARY);
                    }
                }
            }
        });
    }

    // ========== 收藏刷新 ==========

    private void refreshFavorites() {
        List<Song> favSongs = FavoriteManager.loadFavorites(favDir);
        List<Song> allSongs = adapter.getAllSongs();
        List<Song> merged = new ArrayList<>();
        for (Song fav : favSongs) {
            Song matched = findSongInList(allSongs, fav.filePath);
            merged.add(matched != null ? matched : fav);
        }
        adapter.setFavoriteSongs(merged);
        if (adapter.getCurrentMode() == 2) {
            updateCountText();
        }
    }

    private Song findSongInList(List<Song> list, String filePath) {
        if (filePath == null) return null;
        for (Song s : list) {
            if (s.filePath != null && s.filePath.equals(filePath)) return s;
        }
        return null;
    }

    // ========== 权限与扫描 ==========

    private void checkPermissionAndScan() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Android 13+：只需 READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                scanMusic();
                requestNotificationPermissionIfNeeded();
            } else {
                tvLoading.setVisibility(View.VISIBLE);
                currentPermissionRequest = Manifest.permission.READ_MEDIA_AUDIO;
                permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            // Android 12 及以下：需要 READ + WRITE_EXTERNAL_STORAGE
            List<String> needed = new java.util.ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (android.os.Build.VERSION.SDK_INT <= 28 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (needed.isEmpty()) {
                scanMusic();
                requestNotificationPermissionIfNeeded();
            } else {
                tvLoading.setVisibility(View.VISIBLE);
                multiPermissionLauncher.launch(needed.toArray(new String[0]));
            }
        }
    }

    /**
     * Android 13+ 动态请求 POST_NOTIFICATIONS 权限
     */
    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                currentPermissionRequest = Manifest.permission.POST_NOTIFICATIONS;
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    /**
     * 通知权限被拒绝后，引导用户前往设置页手动开启
     */
    private void showNotificationPermissionDeniedDialog() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("通知权限")
                .setMessage("播放控制通知需要通知权限才能正常显示。是否前往设置开启？")
                .setPositiveButton("去设置", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                        startActivity(intent);
                    } catch (Exception e) {
                        // 降级：打开应用详情页
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    }
                })
                .setNegativeButton("暂不", null)
                .show();
    }

    /**
     * 写入权限被拒绝（勾了"不再询问"）后，引导用户前往设置页手动开启
     */
    private void showWriteStoragePermissionDeniedDialog() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("存储写入权限")
                .setMessage("歌词导出到公共目录需要写入权限。是否前往设置开启？")
                .setPositiveButton("去设置", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "无法打开应用设置页", e);
                    }
                })
                .setNegativeButton("暂不", null)
                .show();
    }

    private void scanMusic() {
        scanMusic(true);
    }

    private void scanMusic(boolean tryAutoResume) {
        if (isScanning) {
            Log.d(TAG, "正在扫描中，跳过重复请求");
            return;
        }
        isScanning = true;
        tvLoading.setVisibility(View.VISIBLE);
        tvSongCount.setText("正在扫描音乐...");

        executor.execute(() -> {
            List<Song> songs = MusicScanner.scanMusic(this);
            runOnUiThread(() -> {
                isScanning = false;
                adapter.setAllSongs(songs);
                tvLoading.setVisibility(View.GONE);
                if (tryAutoResume && autoResumeLastPlayed(songs)) {
                    return;
                }
                // 没有记录或不需要自动恢复，正常显示列表
                rootLayout.setVisibility(View.VISIBLE);
                tvLoading.setVisibility(View.GONE);
                refreshFavorites();
                updateCountText();
                applyThemeToRecyclerViewItems();
            });
        });
    }

    // ContentObserver 防抖 Runnable
    private final Runnable scanDebounceRunnable = () -> {
        Log.d(TAG, "MediaStore onChange，重新扫描音乐");
        scanMusic(false); // ContentObserver 触发的扫描不自动跳转播放页
    };

    // ========== 自动恢复上次播放 ==========

    /**
     * 检查 SharedPreferences 中是否有上次播放记录，有则自动跳转播放页
     * 同时恢复播放队列模式（全部/收藏/目录），保证下一首在原队列中播放
     * @return true 已跳转，调用方不应继续显示列表
     */
    private boolean autoResumeLastPlayed(List<Song> songs) {
        // 已经自动恢复过，不再重复跳转（防止从播放页返回时又跳回去）
        if (hasAutoResumed) {
            Log.d(TAG, "autoResumeLastPlayed: already resumed, skip");
            return false;
        }

        android.content.SharedPreferences prefs = getSharedPreferences("last_played", MODE_PRIVATE);
        if (!prefs.getBoolean("has_last", false)) return false;

        long songId = prefs.getLong("song_id", 0);
        String title = prefs.getString("song_title", "");
        if (title == null || title.isEmpty()) return false;

        // 在歌曲列表中找到匹配的歌曲，获取最新 position
        int foundPosition = -1;
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).id == songId) {
                foundPosition = i;
                break;
            }
        }
        if (foundPosition < 0) foundPosition = prefs.getInt("position", 0);

        // 恢复播放队列模式
        String savedPlaylistMode = prefs.getString("playlist_mode", "all");

        Intent intent = new Intent(this, PlayerActivity.class);
        Song resumeSong = new Song();
        resumeSong.id = songId;
        resumeSong.title = title;
        resumeSong.artist = prefs.getString("song_artist", "");
        resumeSong.album = prefs.getString("song_album", "");
        resumeSong.duration = prefs.getLong("song_duration", 0);
        resumeSong.filePath = prefs.getString("song_path", "");
        resumeSong.contentUri = prefs.getString("song_uri", "");
        resumeSong.albumArt = prefs.getString("album_art", "");
        resumeSong.displayName = title;
        resumeSong.toIntent(intent);
        intent.putExtra("position", foundPosition);
        intent.putExtra("playlist_mode", savedPlaylistMode);

        // 目录模式：恢复该目录的歌曲路径列表
        if ("folder".equals(savedPlaylistMode)) {
            java.util.Set<String> pathSet = prefs.getStringSet("folder_song_paths", null);
            if (pathSet != null && !pathSet.isEmpty()) {
                intent.putStringArrayListExtra("folder_song_paths", new java.util.ArrayList<>(pathSet));
            }
        }

        hasAutoResumed = true;
        startActivity(intent);
        return true;
    }

    // ========== 歌曲点击 ==========

    @Override
    public void onSongClick(Song song) {
        Intent intent = new Intent(this, PlayerActivity.class);
        song.toIntent(intent);

        int mode = adapter.getCurrentMode();
        if (mode == 2) {
            intent.putExtra("position", adapter.getSongPositionInFavorites(song));
            intent.putExtra("playlist_mode", "favorites");
        } else if (mode == 0 && adapter.getCurrentMode() == 0) {
            // 目录模式：播放队列限制在该目录内
            java.util.List<Song> folderSongs = new java.util.ArrayList<>();
            int pos = adapter.getSongPositionInFolder(song, folderSongs);
            intent.putExtra("position", pos);
            intent.putExtra("playlist_mode", "folder");
            intent.putExtra("folder_size", folderSongs.size());
            intent.putStringArrayListExtra("folder_paths", new java.util.ArrayList<>());
            java.util.ArrayList<String> paths = new java.util.ArrayList<>();
            for (Song s : folderSongs) paths.add(s.filePath);
            intent.putStringArrayListExtra("folder_song_paths", paths);
        } else {
            intent.putExtra("position", adapter.getSongPositionInAll(song));
            intent.putExtra("playlist_mode", "all");
        }
        startActivity(intent);
    }

    // ========== 目录点击 ==========

    @Override
    public void onFolderClick(FolderInfo folder, boolean expanded) {
        updateCountText();
        applyThemeToRecyclerViewItems();
    }

    // ========== Mini 播放条 ==========

    /**
     * 从 Service 获取当前播放信息，更新 mini 播放条
     */
    private void updateMiniPlayerFromService() {
        if (bound && playerBinder != null) {
            Song currentSong = playerBinder.getCurrentSong();
            if (currentSong != null && currentSong.title != null) {
                miniSongTitle.setText(currentSong.title);
                miniSongArtist.setText(currentSong.artist != null ? currentSong.artist : "");
                miniPlayer.setVisibility(View.VISIBLE);
                miniPlayPause.setImageResource(playerBinder.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            }
        }
    }

    /**
     * 点击 mini 播放条 → 跳转播放页
     */
    private void openPlayerFromMini() {
        if (bound && playerBinder != null) {
            Song currentSong = playerBinder.getCurrentSong();
            if (currentSong == null || currentSong.title == null) return;

            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra("song_id", currentSong.id);
            intent.putExtra("song_title", currentSong.title);
            intent.putExtra("song_artist", currentSong.artist);
            intent.putExtra("song_album", currentSong.album);
            intent.putExtra("song_duration", currentSong.duration);
            intent.putExtra("song_path", currentSong.filePath);
            intent.putExtra("song_uri", currentSong.contentUri);
            intent.putExtra("album_art", currentSong.albumArt);
            intent.putExtra("position", playerBinder.getCurrentIndex());
            intent.putExtra("playlist_mode", "all");
            intent.putExtra("resume_play", true);
            startActivity(intent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(playStateReceiver);
        } catch (Exception ignored) {}
        // 注销 MediaStore ContentObserver
        if (mediaStoreObserver != null) {
            getContentResolver().unregisterContentObserver(mediaStoreObserver);
        }
        scanDebounceHandler.removeCallbacks(scanDebounceRunnable);
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        executor.shutdownNow();
    }
}
