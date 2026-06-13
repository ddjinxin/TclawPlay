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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.jingxin.jingxinmusic.adapter.BrowseAdapter;
import com.jingxin.jingxinmusic.adapter.SongAdapter;
import com.jingxin.jingxinmusic.model.BrowseItem;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.service.MusicPlayerService.MusicPlayerBinder;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.util.BiliApi;
import com.jingxin.jingxinmusic.util.BiliConfig;
import com.jingxin.jingxinmusic.util.FavoriteManager;
import com.jingxin.jingxinmusic.util.LocalDirectoryScanner;
import com.jingxin.jingxinmusic.util.MusicScanner;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.util.WebDavConfig;
import com.jingxin.jingxinmusic.util.WebDavScanner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 歌曲列表页面
 * 三种模式：本地、云端、收藏
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // 收藏列表适配器
    private SongAdapter songAdapter;
    private TextView tvSongCount;
    private TextView tvLoading;
    private TextView tvCopyright;
    private ImageView ivAppIcon;
    private EditText etSearch;
    private ImageView btnTheme;
    private ImageView btnStyle;
    private ImageView btnClose;
    private View rootLayout;
    private View tabBar;
    private View titleBar;
    private View tabDivider1;
    private View tabDivider2;
    private View indicatorLocal;
    private View indicatorCloud;
    private View indicatorBili;
    private View indicatorFavorite;
    private View titleAccentLine;       // 标题栏霓虹绿渐变线
    private View miniShimmerLine;       // 迷你播放条流光线
    private View miniPlayerWrap;        // 迷你播放条外框
    private android.animation.ObjectAnimator tabBreathAnimator;  // Tab指示线呼吸动画
    private RecyclerView rvList;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Tab views
    private TextView tabLocal;
    private TextView tabCloud;
    private TextView tabBili;
    private TextView tabFavorite;

    // Browse area
    private View browseArea;
    private View favoriteArea;
    private View pathBar;
    private ImageView btnNavigateBack;
    private TextView tvBrowsePath;
    private ImageView btnWebDavSettings;
    private ImageView btnBiliSettings;
    private RecyclerView rvBrowse;
    private View webdavSetupArea;
    private View browseLoading;
    private TextView btnGoWebDavSettings;
    private TextView tvSetupMsg;


    // Browse adapter (shared for local & cloud)
    private BrowseAdapter browseAdapter;

    // Local browse state
    private List<Song> allSongs = new ArrayList<>();
    private Stack<String> localNavStack = new Stack<>();
    private String localCurrentDir = null;

    // Cloud browse state
    private WebDavConfig webDavConfig;
    private WebDavScanner webDavScanner;
    private Stack<String> cloudNavStack = new Stack<>();
    private Stack<String> biliNavStack = new Stack<>();
    private String biliCurrentUrl = null;  // B站当前路径，如 "bili://folder/12345"
    private String cloudCurrentUrl = null;

    // Current tab: 0=local, 1=cloud, 2=favorite
    private int currentTab = 0;

    // 从mini播放条进入PlayerActivity后返回时，需要恢复到歌曲对应的来源页面
    private boolean returningFromPlayer = false;

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
    private boolean isScanning = false;
    private final Handler scanDebounceHandler = new Handler();
    private static final int SCAN_DEBOUNCE_MS = 500;

    // Mini 播放条
    private View miniPlayer;
    private TextView miniSongTitle;
    private TextView miniSongArtist;
    private ImageView miniPlayPause;
    private ImageView miniCover;
    private android.animation.ObjectAnimator coverRotateAnimator;

    // 播放服务绑定
    private MusicPlayerBinder playerBinder;
    private boolean bound = false;

    // 监听播放状态变化的广播接收器
    private BroadcastReceiver playStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (MusicPlayerService.ACTION_SONG_CHANGED.equals(action)) {
                String title = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_TITLE);
                String artist = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_ARTIST);
                miniSongTitle.setText(title);
                miniSongArtist.setText(artist);
                miniPlayerWrap.setVisibility(View.VISIBLE);
                if (shimmerAnimator != null && !shimmerAnimator.isRunning()) shimmerAnimator.start();
                // 更新迷你封面
                loadMiniCover(title, artist);
                // 新歌开始，启动旋转
                startCoverRotation();
            } else if (MusicPlayerService.ACTION_PLAY_STATE_CHANGED.equals(action)) {
                boolean playing = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false);
                miniPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                // 播放/暂停控制旋转
                if (playing) {
                    startCoverRotation();
                } else {
                    pauseCoverRotation();
                }
                String title = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_TITLE);
                String artist = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_ARTIST);
                if (title != null) {
                    miniSongTitle.setText(title);
                    miniSongArtist.setText(artist);
                    miniPlayerWrap.setVisibility(View.VISIBLE);
                    if (shimmerAnimator != null && !shimmerAnimator.isRunning()) shimmerAnimator.start();
                }
            }
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerBinder = (MusicPlayerBinder) service;
            bound = true;
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
        ThemeColors.init(this);

        // 初始化视图
        tvSongCount = findViewById(R.id.tv_song_count);
        tvLoading = findViewById(R.id.tv_loading);
        ivAppIcon = findViewById(R.id.iv_app_icon);
        etSearch = findViewById(R.id.et_search);
        btnTheme = findViewById(R.id.theme_button);
        btnStyle = findViewById(R.id.style_button);
        rootLayout = findViewById(R.id.root_layout);
        tabBar = findViewById(R.id.tab_bar);
        titleBar = findViewById(R.id.title_bar);
        tabDivider1 = findViewById(R.id.tab_divider_1);
        tabDivider2 = findViewById(R.id.tab_divider_2);
        indicatorLocal = findViewById(R.id.indicator_local);
        indicatorCloud = findViewById(R.id.indicator_cloud);
        indicatorBili = findViewById(R.id.indicator_bili);
        indicatorFavorite = findViewById(R.id.indicator_favorite);
        titleAccentLine = findViewById(R.id.title_accent_line);
        miniShimmerLine = findViewById(R.id.mini_shimmer_line);
        miniPlayerWrap = findViewById(R.id.mini_player_wrap);
        tvCopyright = findViewById(R.id.tv_copyright);
        rvList = findViewById(R.id.rv_song_list);

        // Browse area
        browseArea = findViewById(R.id.browse_area);
        favoriteArea = findViewById(R.id.favorite_area);
        pathBar = findViewById(R.id.path_bar);
        btnNavigateBack = findViewById(R.id.btn_navigate_back);
        tvBrowsePath = findViewById(R.id.tv_browse_path);
        btnWebDavSettings = findViewById(R.id.btn_webdav_settings);
        btnBiliSettings = findViewById(R.id.btn_bili_settings);
        rvBrowse = findViewById(R.id.rv_browse);
        webdavSetupArea = findViewById(R.id.webdav_setup_area);
        browseLoading = findViewById(R.id.loading_layout);
        btnGoWebDavSettings = findViewById(R.id.btn_go_webdav_settings);
        tvSetupMsg = findViewById(R.id.tv_webdav_setup_msg);


        // Mini 播放条
        miniPlayer = findViewById(R.id.mini_player);
        miniSongTitle = findViewById(R.id.mini_song_title);
        miniSongArtist = findViewById(R.id.mini_song_artist);
        miniPlayPause = findViewById(R.id.mini_play_pause);
        miniCover = findViewById(R.id.mini_cover);

        // 封面圆形裁剪 + 旋转动画
        miniCover.setClipToOutline(true);
        miniCover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                int size = Math.min(view.getWidth(), view.getHeight());
                if (size <= 0) size = 42; // 默认大小
                outline.setRoundRect(0, 0, size, size, size / 2f);
            }
        });
        coverRotateAnimator = android.animation.ObjectAnimator.ofFloat(miniCover, View.ROTATION, 0f, 360f);
        coverRotateAnimator.setDuration(12000); // 12秒转一圈
        coverRotateAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        coverRotateAnimator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);

        // 关闭按钮
        btnClose = findViewById(R.id.close_button);
        btnClose.setOnClickListener(v -> {
            stopService(new Intent(MainActivity.this, com.jingxin.jingxinmusic.service.MusicPlayerService.class));
            finishAffinity();
            System.exit(0);
        });

        // Tab
        tabLocal = findViewById(R.id.tab_local);
        tabCloud = findViewById(R.id.tab_cloud);
        tabBili = findViewById(R.id.tab_bili);
        tabFavorite = findViewById(R.id.tab_favorite);

        // WebDAV config
        webDavConfig = new WebDavConfig(this);

        // 设置收藏列表 RecyclerView
        rvList.setLayoutManager(new LinearLayoutManager(this));
        songAdapter = new SongAdapter(this);
        songAdapter.setOnSongClickListener(this::onFavoriteSongClick);
        rvList.setAdapter(songAdapter);

        // 设置浏览 RecyclerView（本地/云端/收藏共用）
        browseAdapter = new BrowseAdapter();
        int spanCount = Math.max(3, getResources().getDisplayMetrics().widthPixels / 360);
        rvBrowse.setLayoutManager(new GridLayoutManager(this, spanCount));
        rvBrowse.setAdapter(browseAdapter);

        browseAdapter.setOnItemClickListener((item, position) -> {
            if (item.isDirectory) {
                if (currentTab == 0) {
                    navigateLocalTo(item.path);
                } else if (currentTab == 1) {
                    navigateCloudTo(item.url);
                } else if (currentTab == 2) {
                    // B站目录项：收藏夹文件夹 或 视频目录（统一用item.url导航）
                    navigateBiliTo(item.url);
                }
            } else {
                playFromBrowse(item);
            }
        });

        // 监听窗口宽度变化
        View rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.addOnLayoutChangeListener(
                    (View v, int left, int top, int right, int bottom,
                     int oldLeft, int oldTop, int oldRight, int oldBottom) -> {
                        if (isFinishing() || isDestroyed()) return;
                        int newWidth = right - left;
                        int oldWidth = oldRight - oldLeft;
                        if (oldWidth > 0 && newWidth != oldWidth) {
                            if (rvBrowse != null) {
                                rvBrowse.post(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    int newSpan = Math.max(3, getResources().getDisplayMetrics().widthPixels / 360);
                                    rvBrowse.setLayoutManager(new GridLayoutManager(MainActivity.this, newSpan));
                                    if (browseAdapter != null) {
                                        browseAdapter.notifyDataSetChanged();
                                    }
                                });
                            }
                        }
                    });
        }

        // 风格切换按钮
        btnStyle.setOnClickListener(v -> {
            int newIndex = ThemeColors.cycleStyle(this);
            updateThemeUI();
            android.widget.Toast.makeText(this, ThemeColors.getStyle().name, android.widget.Toast.LENGTH_SHORT).show();
        });

        // 主题按钮
        btnTheme.setOnClickListener(v -> {
            isNightMode = !isNightMode;
            themePrefs.edit().putBoolean("isNight", isNightMode)
                    .putBoolean("amapTriggered", false)
                    .apply();
            updateThemeUI();
            android.widget.Toast.makeText(this, isNightMode ? "夜间模式" : "白天模式", android.widget.Toast.LENGTH_SHORT).show();
        });

        // 应用主题
        updateThemeUI();

        // 根布局初始不可见
        rootLayout.setVisibility(View.INVISIBLE);

        // Tab 切换
        tabLocal.setOnClickListener(v -> switchTab(0));
        tabCloud.setOnClickListener(v -> switchTab(1));
        tabBili.setOnClickListener(v -> switchTab(2));
        tabFavorite.setOnClickListener(v -> switchTab(3));

        // 返回按钮（路径栏）
        btnNavigateBack.setOnClickListener(v -> {
            if (currentTab == 0) {
                navigateLocalBack();
            } else if (currentTab == 1) {
                navigateCloudBack();
            } else if (currentTab == 2) {
                navigateBiliBack();
            }
        });

        // 云端设置按钮
        btnWebDavSettings.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, com.jingxin.jingxinmusic.ui.WebDavSettingsActivity.class));
        });

        // B站设置按钮
        btnBiliSettings.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, com.jingxin.jingxinmusic.ui.BiliSettingsActivity.class));
        });

        // 去配置按钮（根据当前tab动态切换目标Activity）
        btnGoWebDavSettings.setOnClickListener(v -> {
            if (currentTab == 2) {
                startActivity(new Intent(MainActivity.this, com.jingxin.jingxinmusic.ui.BiliSettingsActivity.class));
            } else {
                startActivity(new Intent(MainActivity.this, com.jingxin.jingxinmusic.ui.WebDavSettingsActivity.class));
            }
        });

        // 搜索功能
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                songAdapter.filter(s.toString());
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
                        if (!isGranted) {
                            showNotificationPermissionDeniedDialog();
                        }
                    } else {
                        if (isGranted) {
                            scanMusic();
                        } else {
                            tvLoading.setVisibility(View.GONE);
                            tvSongCount.setText("需要存储权限才能扫描音乐");
                        }
                    }
                    currentPermissionRequest = null;
                });

        multiPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean readGranted = Boolean.TRUE.equals(result.get(Manifest.permission.READ_EXTERNAL_STORAGE));
                    boolean writeGranted = Boolean.TRUE.equals(result.get(Manifest.permission.WRITE_EXTERNAL_STORAGE));
                    if (readGranted) {
                        scanMusic();
                        if (!writeGranted) {
                            if (!shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                                showWriteStoragePermissionDeniedDialog();
                            }
                        }
                        requestNotificationPermissionIfNeeded();
                    } else {
                        tvLoading.setVisibility(View.GONE);
                        tvSongCount.setText("需要存储权限才能扫描音乐");
                    }
                });

        checkPermissionAndScan();

        // 初始化首页Tab（检查是否从WebDAV设置页跳来）
        int selectTab = getIntent().getIntExtra("select_tab", 0);
        switchTab(selectTab);

        // 点击 mini 播放条整体 → 跳转播放页
        miniPlayer.setOnClickListener(v -> openPlayerFromMini());

        // 点击播放/暂停
        miniPlayPause.setOnClickListener(v -> {
            if (bound && playerBinder != null) {
                playerBinder.togglePlayPause();
            }
        });

        // 绑定播放服务
        Intent serviceIntent = new Intent(this, MusicPlayerService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 注册广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction(MusicPlayerService.ACTION_SONG_CHANGED);
        filter.addAction(MusicPlayerService.ACTION_PLAY_STATE_CHANGED);
        CompatUtil.safeRegisterReceiver(this, playStateReceiver, filter);

        // 注册 MediaStore ContentObserver
        mediaStoreObserver = new android.database.ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
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
        View rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (rvBrowse != null) {
                    rvBrowse.requestLayout();
                }
                if (browseAdapter != null) {
                    browseAdapter.notifyDataSetChanged();
                }
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int selectTab = intent.getIntExtra("select_tab", -1);
        if (selectTab >= 0) {
            switchTab(selectTab);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        rootLayout.setVisibility(View.VISIBLE);
        boolean savedNight = themePrefs.getBoolean("isNight", true);
        if (savedNight != isNightMode) {
            isNightMode = savedNight;
            updateThemeUI();
        }

        // 从mini播放条进入PlayerActivity后返回：恢复到歌曲来源对应的tab和导航层级
        if (returningFromPlayer) {
            returningFromPlayer = false;
            android.content.SharedPreferences prefs = getSharedPreferences("last_played", MODE_PRIVATE);
            String savedPlaylistMode = prefs.getString("playlist_mode", "all");
            if ("bili".equals(savedPlaylistMode) && currentTab != 2) {
                currentTab = 2;
                // 恢复B站导航到歌曲所在的列表页
                String biliNavUrl = prefs.getString("bili_nav_url", "");
                if (!biliNavUrl.isEmpty()) {
                    biliCurrentUrl = biliNavUrl;
                }
            } else if ("webdav".equals(savedPlaylistMode) && currentTab != 1) {
                currentTab = 1;
            }
        }

        // 确保 tab UI 与 currentTab 一致（autoResume 可能改了 currentTab 但没走 switchTab）
        updateTabUI();
        // 刷新收藏
        refreshFavorites();
        // 刷新当前Tab的浏览内容
        webDavConfig = new WebDavConfig(this);
        loadCurrentTabContent();
        // 更新 mini 播放条
        updateMiniPlayerFromService();
    }

    // ========== Tab 切换 ==========

    /** 轻量级刷新 tab 样式（不触发内容加载），用于 onResume 同步 UI */
    private void updateTabUI() {
        int mode = currentTab;
        int activeColor = isNightMode ? ThemeColors.nightTabActive() : ThemeColors.dayTabActive();
        int inactiveColor = isNightMode ? ThemeColors.nightTabInactive() : ThemeColors.dayTabInactive();
        tabLocal.setTextColor(mode == 0 ? activeColor : inactiveColor);
        tabLocal.setTypeface(null, mode == 0 ? Typeface.BOLD : Typeface.NORMAL);
        tabCloud.setTextColor(mode == 1 ? activeColor : inactiveColor);
        tabCloud.setTypeface(null, mode == 1 ? Typeface.BOLD : Typeface.NORMAL);
        tabBili.setTextColor(mode == 2 ? activeColor : inactiveColor);
        tabBili.setTypeface(null, mode == 2 ? Typeface.BOLD : Typeface.NORMAL);
        tabFavorite.setTextColor(mode == 3 ? activeColor : inactiveColor);
        tabFavorite.setTypeface(null, mode == 3 ? Typeface.BOLD : Typeface.NORMAL);

        int indicatorActive = isNightMode ? ThemeColors.nightTabIndicator() : ThemeColors.dayTabIndicator();
        int indicatorInactive = 0x00000000;
        indicatorLocal.setBackgroundColor(mode == 0 ? indicatorActive : indicatorInactive);
        indicatorCloud.setBackgroundColor(mode == 1 ? indicatorActive : indicatorInactive);
        indicatorBili.setBackgroundColor(mode == 2 ? indicatorActive : indicatorInactive);
        indicatorFavorite.setBackgroundColor(mode == 3 ? indicatorActive : indicatorInactive);

        // 确保正确的区域可见
        if (mode == 3) {
            browseArea.setVisibility(View.GONE);
            favoriteArea.setVisibility(View.VISIBLE);
        } else {
            browseArea.setVisibility(View.VISIBLE);
            favoriteArea.setVisibility(View.GONE);
        }
    }

    private void switchTab(int mode) {
        currentTab = mode;

        updateTabUI();

        loadCurrentTabContent();

        etSearch.setText("");
        updateCountText();
    }

    /**
     * 加载当前tab的内容。本地tab在allSongs为空时跳过（等scanMusic回调统一处理）。
     */
    private void loadCurrentTabContent() {
        if (currentTab == 0) {
            if (!allSongs.isEmpty()) {
                loadLocalItems();
            }
            // allSongs为空时scanMusic回调会负责加载，避免用空数据显示空白页面
        } else if (currentTab == 1) {
            loadWebDavItems();
        } else if (currentTab == 2) {
            loadBiliItems();
        } else {
            int spanCount = Math.max(3, getResources().getDisplayMetrics().widthPixels / 360);
            rvList.setLayoutManager(new GridLayoutManager(this, spanCount));
            rvList.setAdapter(browseAdapter);
            refreshFavorites();
            loadFavoriteBrowseItems();
        }
    }

    // ========== 本地浏览 ==========

    /**
     * 将收藏歌曲加载为封面卡片网格
     */
    private void loadFavoriteBrowseItems() {
        List<BrowseItem> items = new ArrayList<>();
        for (Song song : songAdapter.getFavoriteSongs()) {
            items.add(BrowseItem.localSong(song));
        }
        browseAdapter.setItems(items);
    }

    private void loadLocalItems() {
        navigateLocalTo(localCurrentDir);
    }

    private void navigateLocalTo(String dirPath) {
        // 入子目录时压栈
        if (dirPath != null && localCurrentDir != null && !dirPath.equals(localCurrentDir)) {
            localNavStack.push(localCurrentDir);
        }
        localCurrentDir = dirPath;

        List<BrowseItem> items = LocalDirectoryScanner.buildLevel(allSongs, dirPath);
        browseAdapter.setItems(items);

        // 路径栏：根目录隐藏，子目录显示
        if (dirPath == null) {
            pathBar.setVisibility(View.GONE);
            btnWebDavSettings.setVisibility(View.GONE);
            btnBiliSettings.setVisibility(View.GONE);
        } else {
            pathBar.setVisibility(View.VISIBLE);
            btnWebDavSettings.setVisibility(View.GONE);
            btnBiliSettings.setVisibility(View.GONE);
            String dirName = new File(dirPath).getName();
            tvBrowsePath.setText(dirName);
        }
        webdavSetupArea.setVisibility(View.GONE);
        browseLoading.setVisibility(View.GONE);
        rvBrowse.setVisibility(View.VISIBLE);

        updateCountText();
    }

    private void navigateLocalBack() {
        if (localNavStack.isEmpty()) {
            localCurrentDir = null;
            navigateLocalTo(null);
            return;
        }
        String parentDir = localNavStack.pop();
        localCurrentDir = null; // 防止压栈
        navigateLocalTo(parentDir);
    }

    // ========== 云端浏览 ==========

    // ========== WebDAV 浏览 ==========

    private void loadWebDavItems() {
        webDavConfig = new WebDavConfig(this);

        if (!webDavConfig.isConfigured()) {
            rvBrowse.setVisibility(View.GONE);
            browseLoading.setVisibility(View.GONE);
            pathBar.setVisibility(View.GONE);
            btnGoWebDavSettings.setVisibility(View.VISIBLE);
            tvSetupMsg.setText("配置WebDAV以浏览云端音乐");
            webdavSetupArea.setVisibility(View.VISIBLE);
            return;
        }

        webdavSetupArea.setVisibility(View.GONE);
        webDavScanner = new WebDavScanner(webDavConfig);

        String url = cloudCurrentUrl != null ? cloudCurrentUrl : webDavConfig.getMusicUrl();
        navigateCloudTo(url);
    }

    private void navigateCloudTo(String url) {
        if (url != null && cloudCurrentUrl != null && !url.equals(cloudCurrentUrl)) {
            cloudNavStack.push(cloudCurrentUrl);
        }
        cloudCurrentUrl = url;

        pathBar.setVisibility(View.VISIBLE);
        btnWebDavSettings.setVisibility(View.VISIBLE);
        btnBiliSettings.setVisibility(View.GONE);
        String displayPath = extractCloudDisplayPath(url);
        tvBrowsePath.setText("/ " + displayPath);

        webdavSetupArea.setVisibility(View.GONE);
        rvBrowse.setVisibility(View.GONE);
        browseLoading.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            List<WebDavScanner.DavItem> davItems = webDavScanner.listDirectory(url);
            List<BrowseItem> browseItems = new ArrayList<>();
            for (WebDavScanner.DavItem di : davItems) {
                BrowseItem bi;
                if (di.isDirectory) {
                    bi = BrowseItem.directory(di.name, di.path, di.url, Song.SOURCE_WEBDAV);
                } else {
                    bi = BrowseItem.webdavSong(di.name, di.path, di.url, di.size, di.modified, di.contentType);
                }
                browseItems.add(bi);
            }
            runOnUiThread(() -> {
                browseLoading.setVisibility(View.GONE);
                rvBrowse.setVisibility(View.VISIBLE);
                browseAdapter.setItems(browseItems);
                updateCountText();
            });
        });
    }

    private void navigateCloudBack() {
        if (cloudNavStack.isEmpty()) {
            cloudCurrentUrl = null;
            loadWebDavItems();
            return;
        }
        String parentUrl = cloudNavStack.pop();
        cloudCurrentUrl = null;
        navigateCloudTo(parentUrl);
    }

    // ========== B站 浏览 ==========

    private void loadBiliItems() {
        BiliConfig biliConfig = new BiliConfig(this);
        if (!biliConfig.isConfigured()) {
            // 未登录，显示引导
            rvBrowse.setVisibility(View.GONE);
            browseLoading.setVisibility(View.GONE);
            pathBar.setVisibility(View.GONE);
            webdavSetupArea.setVisibility(View.VISIBLE);
            btnGoWebDavSettings.setVisibility(View.VISIBLE);
            tvSetupMsg.setText("配置B站以浏览收藏音乐");
            return;
        }

        webdavSetupArea.setVisibility(View.GONE);
        // 首次加载时恢复持久化的导航状态
        restoreBiliNavState();
        // 不再每次清空导航栈，保留当前浏览层级（从播放页返回时不会跳回根目录）
        navigateBiliTo(biliCurrentUrl);
    }

    private void navigateBiliTo(String url) {
        // 压栈
        if (url != null && biliCurrentUrl != null && !url.equals(biliCurrentUrl)) {
            biliNavStack.push(biliCurrentUrl);
        }
        biliCurrentUrl = url;
        saveBiliNavState();

        pathBar.setVisibility(View.VISIBLE);
        btnWebDavSettings.setVisibility(View.GONE);
        btnBiliSettings.setVisibility(View.VISIBLE);
        if (url == null) {
            tvBrowsePath.setText("/ B站收藏夹");
        } else {
            tvBrowsePath.setText("/ " + extractBiliDisplayPath(url));
        }

        webdavSetupArea.setVisibility(View.GONE);
        rvBrowse.setVisibility(View.GONE);
        browseLoading.setVisibility(View.VISIBLE);

        BiliConfig biliConfig = new BiliConfig(this);

        executor.execute(() -> {
            List<BrowseItem> browseItems = new ArrayList<>();

            if (url == null) {
                // 根目录：显示收藏夹列表
                List<BiliApi.FavoriteFolder> folders = BiliApi.getFavoriteFolders(biliConfig);
                for (BiliApi.FavoriteFolder ff : folders) {
                    browseItems.add(BrowseItem.biliFolder(ff.title, ff.id, ff.mediaCount));
                }
            } else if (url.startsWith("bili://folder/")) {
                // 收藏夹内的视频列表
                long folderId = 0;
                try {
                    folderId = Long.parseLong(url.substring("bili://folder/".length()));
                } catch (NumberFormatException ignored) {}

                if (folderId > 0) {
                    List<BiliApi.FavoriteItem> items = BiliApi.getFavoriteItems(folderId, biliConfig);
                    for (BiliApi.FavoriteItem fi : items) {
                        if (fi.pageCount > 1) {
                            // 多P视频：作为目录项，点击后展开分P列表
                            browseItems.add(BrowseItem.biliVideo(
                                    fi.bvid, fi.title, fi.upperName,
                                    fi.cover, fi.pageCount, fi.duration));
                        } else {
                            // 单P视频：直接作为歌曲，点击即播放
                            Song song = Song.fromBili(fi.bvid, fi.title, fi.upperName,
                                    fi.duration, fi.cover);
                            browseItems.add(BrowseItem.biliSong(song));
                        }
                    }
                }
            } else if (url.startsWith("bili://video/")) {
                // 视频分P列表：展开每个分P为独立歌曲
                String bvid = url.substring("bili://video/".length());
                List<BiliApi.VideoPage> pages = BiliApi.getVideoPages(bvid, biliConfig);

                // 找到对应的视频目录项获取封面和UP主信息
                String coverUrl = null;
                String upperName = "";
                String videoTitle = "";
                for (BrowseItem bi : browseAdapter.getItems()) {
                    if (bi.biliBvid != null && bi.biliBvid.equals(bvid)) {
                        coverUrl = bi.biliCover;
                        upperName = bi.biliUpperName;
                        videoTitle = bi.biliVideoTitle;
                        break;
                    }
                }

                for (BiliApi.VideoPage vp : pages) {
                    // 多P标题：如果分P标题不同于视频标题，直接用分P标题
                    // 如果分P标题为空或与视频标题相同，加序号区分
                    String partTitle;
                    if (vp.part != null && !vp.part.isEmpty()
                            && !vp.part.equals(videoTitle)) {
                        partTitle = vp.part;
                    } else if (pages.size() > 1) {
                        partTitle = videoTitle + " P" + vp.page;
                    } else {
                        partTitle = videoTitle;
                    }
                    Song song = Song.fromBili(bvid, partTitle, upperName,
                            vp.duration, coverUrl, vp.cid, vp.page, videoTitle);
                    browseItems.add(BrowseItem.biliSong(song));
                }
            }

            List<BrowseItem> finalItems = browseItems;
            runOnUiThread(() -> {
                browseLoading.setVisibility(View.GONE);
                rvBrowse.setVisibility(View.VISIBLE);
                browseAdapter.setItems(finalItems);
                updateCountText();
            });
        });
    }

    private void navigateBiliBack() {
        if (biliNavStack.isEmpty()) {
            biliCurrentUrl = null;
            navigateBiliTo(null);
            return;
        }
        String parentUrl = biliNavStack.pop();
        biliCurrentUrl = null;
        navigateBiliTo(parentUrl);
    }

    /** 持久化B站导航状态，重启后恢复 */
    private void saveBiliNavState() {
        try {
            org.json.JSONArray stackArr = new org.json.JSONArray();
            for (String s : biliNavStack) {
                stackArr.put(s);
            }
            getSharedPreferences("bili_nav", MODE_PRIVATE)
                    .edit()
                    .putString("current_url", biliCurrentUrl)
                    .putString("nav_stack", stackArr.toString())
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "保存B站导航状态失败: " + e.getMessage());
        }
    }

    /** 恢复B站导航状态（仅首次加载且内存状态为空时调用） */
    private void restoreBiliNavState() {
        if (biliCurrentUrl != null || !biliNavStack.isEmpty()) return; // 已有内存状态，不覆盖
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("bili_nav", MODE_PRIVATE);
            biliCurrentUrl = prefs.getString("current_url", null);
            String stackJson = prefs.getString("nav_stack", null);
            if (stackJson != null) {
                org.json.JSONArray arr = new org.json.JSONArray(stackJson);
                for (int i = 0; i < arr.length(); i++) {
                    biliNavStack.push(arr.getString(i));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "恢复B站导航状态失败: " + e.getMessage());
        }
    }

    private String extractBiliDisplayPath(String url) {
        if (url == null) return "B站收藏夹";
        if (url.startsWith("bili://folder/")) return "收藏夹";
        if (url.startsWith("bili://video/")) return "分P列表";
        return url;
    }

    private String extractCloudDisplayPath(String url) {
        String musicUrl = webDavConfig.getMusicUrl();
        if (url != null && musicUrl != null && url.startsWith(musicUrl)) {
            return url.substring(musicUrl.length());
        }
        try {
            int pathStart = url.indexOf("/", url.indexOf("//") + 2);
            if (pathStart > 0) {
                return url.substring(pathStart);
            }
        } catch (Exception e) {
            // ignore
        }
        return url;
    }

    // ========== 从浏览项播放 ==========

    private void playFromBrowse(BrowseItem clickedItem) {
        List<BrowseItem> items = browseAdapter.getItems();
        List<Song> playlist = new ArrayList<>();
        int playIndex = 0;

        if (clickedItem.source == Song.SOURCE_LOCAL) {
            // 本地歌曲
            for (int i = 0; i < items.size(); i++) {
                BrowseItem item = items.get(i);
                if (!item.isDirectory && item.song != null) {
                    playlist.add(item.song);
                    if (item == clickedItem) {
                        playIndex = playlist.size() - 1;
                    }
                }
            }

            if (playlist.isEmpty()) return;

            Intent intent = new Intent(this, PlayerActivity.class);
            Song clickedSong = playlist.get(playIndex);
            clickedSong.toIntent(intent);
            intent.putExtra("position", playIndex);

            if (currentTab == 3) {
                // 收藏模式
                intent.putExtra("playlist_mode", "favorites");
            } else {
                // 本地目录模式
                intent.putExtra("playlist_mode", "folder");
                intent.putExtra("folder_size", playlist.size());
                java.util.ArrayList<String> paths = new java.util.ArrayList<>();
                for (Song s : playlist) paths.add(s.filePath);
                intent.putStringArrayListExtra("folder_song_paths", paths);
            }
            startActivity(intent);

        } else {
            // WebDAV / B站歌曲
            long idBase = 1000000;
            for (int i = 0; i < items.size(); i++) {
                BrowseItem item = items.get(i);
                if (!item.isDirectory) {
                    Song song;
                    if (item.song != null) {
                        song = item.song;
                    } else if (item.source == Song.SOURCE_BILI) {
                        // 理论上不应该到这里，biliSong总是有song
                        continue;
                    } else {
                        WebDavScanner.DavItem davItem = new WebDavScanner.DavItem(
                                item.name, item.path, item.url, false,
                                item.size, item.modified, item.contentType);
                        song = WebDavScanner.davItemToSong(davItem, idBase++);
                    }
                    song.id = idBase++;
                    playlist.add(song);
                    if (item == clickedItem) {
                        playIndex = playlist.size() - 1;
                    }
                }
            }

            if (playlist.isEmpty()) return;

            // 保存播放列表
            saveWebDavPlaylist(playlist, playIndex);

            // 判断播放模式
            String playlistMode = "webdav";
            if (clickedItem.source == Song.SOURCE_BILI) {
                playlistMode = "bili";
            }

            Intent intent = new Intent(this, PlayerActivity.class);
            Song clickedSong = playlist.get(playIndex);
            clickedSong.toIntent(intent);
            intent.putExtra("position", playIndex);
            intent.putExtra("playlist_mode", playlistMode);
            intent.putExtra("from_webdav", true);
            intent.putExtra("webdav_playlist_size", playlist.size());
            // 保存B站导航上下文，用于从播放页返回时恢复
            if (clickedItem.source == Song.SOURCE_BILI) {
                intent.putExtra("bili_nav_url", biliCurrentUrl != null ? biliCurrentUrl : "");
            }
            startActivity(intent);
        }
    }

    private void saveWebDavPlaylist(List<Song> playlist, int playIndex) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Song song : playlist) {
                arr.put(song.toJson());
            }
            getSharedPreferences("webdav_playlist", MODE_PRIVATE)
                    .edit()
                    .putString("playlist", arr.toString())
                    .putInt("play_index", playIndex)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "保存播放列表失败: " + e.getMessage());
        }
    }

    // ========== 收藏歌曲点击（仅收藏tab） ==========

    private void onFavoriteSongClick(Song song) {
        Intent intent = new Intent(this, PlayerActivity.class);
        song.toIntent(intent);
        intent.putExtra("position", songAdapter.getSongPositionInFavorites(song));
        intent.putExtra("playlist_mode", "favorites");
        startActivity(intent);
    }

    // ========== 数量文本 ==========

    private void updateCountText() {
        if (currentTab == 0) {
            List<BrowseItem> items = browseAdapter.getItems();
            int dirCount = 0, songCount = 0;
            for (BrowseItem item : items) {
                if (item.isDirectory) dirCount++;
                else songCount++;
            }
            if (localCurrentDir == null) {
                tvSongCount.setText(dirCount + " 个目录");
            } else {
                tvSongCount.setText(songCount + " 首歌曲");
            }
        } else if (currentTab == 1 || currentTab == 2) {
            List<BrowseItem> items = browseAdapter.getItems();
            int songCount = 0;
            for (BrowseItem item : items) {
                if (!item.isDirectory) songCount++;
            }
            tvSongCount.setText(songCount + " 首歌曲");
        } else {
            tvSongCount.setText(songAdapter.getSongCount() + " 首收藏");
        }
    }

    // ========== 主题 ==========

    private void updateThemeUI() {
        if (isNightMode) {
            rootLayout.setBackground(ThemeColors.bgGradient(true));
            titleBar.setBackground(ThemeColors.barGradient(true));
            tabBar.setBackground(ThemeColors.barGradient(true));
            etSearch.setTextColor(ThemeColors.nightTextPrimary());
            etSearch.setHintTextColor(ThemeColors.nightTextTertiary());
            tvSongCount.setTextColor(ThemeColors.nightTextTertiary());
            tvLoading.setTextColor(ThemeColors.nightTextTertiary());
            tvCopyright.setTextColor(ThemeColors.nightTextCopyright());
            tabDivider1.setBackgroundColor(ThemeColors.nightDivider());
            tabDivider2.setBackgroundColor(ThemeColors.nightDivider());
            btnTheme.clearColorFilter();
            btnStyle.clearColorFilter();
            btnClose.clearColorFilter();
            // Browse area
            browseArea.setBackground(ThemeColors.bgGradient(true));
            pathBar.setBackground(ThemeColors.barGradient(true));
            tvBrowsePath.setTextColor(ThemeColors.nightTextSecondary());
            btnNavigateBack.setColorFilter(ThemeColors.nightTextSecondary());
            btnWebDavSettings.setColorFilter(ThemeColors.nightTextSecondary());
            btnBiliSettings.setColorFilter(ThemeColors.nightTextSecondary());
             btnGoWebDavSettings.setTextColor(ThemeColors.nightTabActive());
            tvSetupMsg.setTextColor(ThemeColors.nightTextSecondary());
            // mini 播放条
            miniPlayer.setBackground(ThemeColors.miniGradient(true));
            miniSongTitle.setTextColor(ThemeColors.nightTextPrimary());
            miniSongArtist.setTextColor(ThemeColors.nightTextSecondary());
            miniPlayPause.clearColorFilter();
        } else {
            rootLayout.setBackground(ThemeColors.bgGradient(false));
            titleBar.setBackground(ThemeColors.barGradient(false));
            tabBar.setBackground(ThemeColors.barGradient(false));
            etSearch.setTextColor(ThemeColors.dayTextPrimary());
            etSearch.setHintTextColor(ThemeColors.dayTextSecondary());
            tvSongCount.setTextColor(ThemeColors.dayTextSecondary());
            tvLoading.setTextColor(ThemeColors.dayTextSecondary());
            tvCopyright.setTextColor(ThemeColors.dayTextCopyright());
            tabDivider1.setBackgroundColor(ThemeColors.dayDivider());
            tabDivider2.setBackgroundColor(ThemeColors.dayDivider());
            btnStyle.setColorFilter(ThemeColors.dayTextPrimary(), PorterDuff.Mode.SRC_IN);
            btnTheme.setColorFilter(ThemeColors.dayTextPrimary(), PorterDuff.Mode.SRC_IN);
            btnClose.setColorFilter(ThemeColors.dayTextPrimary(), PorterDuff.Mode.SRC_IN);
            // Browse area
            browseArea.setBackground(ThemeColors.bgGradient(false));
            pathBar.setBackground(ThemeColors.barGradient(false));
            tvBrowsePath.setTextColor(ThemeColors.dayTextSecondary());
            btnNavigateBack.setColorFilter(ThemeColors.dayTextSecondary());
            btnWebDavSettings.setColorFilter(ThemeColors.dayTextSecondary());
            btnBiliSettings.setColorFilter(ThemeColors.dayTextSecondary());
            btnGoWebDavSettings.setTextColor(ThemeColors.dayTabActive());
            tvSetupMsg.setTextColor(ThemeColors.dayTextSecondary());
            // mini 播放条
            miniPlayer.setBackground(ThemeColors.miniGradient(false));
            miniSongTitle.setTextColor(ThemeColors.dayTextPrimary());
            miniSongArtist.setTextColor(ThemeColors.dayTextSecondary());
            miniPlayPause.setColorFilter(ThemeColors.dayTextPrimary(), PorterDuff.Mode.SRC_IN);
        }
        // 更新 Tab 文字颜色
        int activeColor = isNightMode ? ThemeColors.nightTabActive() : ThemeColors.dayTabActive();
        int inactiveColor = isNightMode ? ThemeColors.nightTabInactive() : ThemeColors.dayTabInactive();
        tabLocal.setTextColor(currentTab == 0 ? activeColor : inactiveColor);
        tabCloud.setTextColor(currentTab == 1 ? activeColor : inactiveColor);
        tabBili.setTextColor(currentTab == 2 ? activeColor : inactiveColor);
        tabFavorite.setTextColor(currentTab == 3 ? activeColor : inactiveColor);
        // Tab指示线已隐藏
        // 标题栏底部霓虹绿渐变线
        int brandGreen = isNightMode ? ThemeColors.nightTabIndicator() : ThemeColors.dayTabIndicator();
        android.graphics.drawable.GradientDrawable accentGradient = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ 0x00000000, brandGreen, brandGreen, 0x00000000 });
        titleAccentLine.setBackground(accentGradient);
        // 迷你播放条流光线
        int shimmerColor = isNightMode ? brandGreen : brandGreen;
        android.graphics.drawable.GradientDrawable shimmerGradient = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ 0x00000000, shimmerColor, 0x00000000 });
        miniShimmerLine.setBackground(shimmerGradient);
        // 迷你播放条流光动画
        startMiniShimmerAnimation();
        // 同步 adapter 主题
        songAdapter.setNightMode(isNightMode);
        browseAdapter.setNightMode(isNightMode);
        // 刷新浏览列表（文件夹卡片渐变需要重新绑定）
        browseAdapter.notifyDataSetChanged();
        // 刷新当前可见 item
        applyThemeToRecyclerViewItems();
    }

    private void applyThemeToRecyclerViewItems() {
        rvList.post(() -> {
            int childCount = rvList.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = rvList.getChildAt(i);
                if (child.findViewById(R.id.tv_song_title) != null) {
                    TextView tvTitle = child.findViewById(R.id.tv_song_title);
                    TextView tvArtist = child.findViewById(R.id.tv_song_artist);
                    TextView tvDuration = child.findViewById(R.id.tv_song_duration);
                    if (isNightMode) {
                        child.setBackgroundColor(ThemeColors.nightItemBg());
                        tvTitle.setTextColor(ThemeColors.nightTextPrimary());
                        tvArtist.setTextColor(ThemeColors.nightTextSecondary());
                        tvDuration.setTextColor(ThemeColors.nightTextTertiary());
                    } else {
                        child.setBackgroundColor(ThemeColors.dayItemBg());
                        tvTitle.setTextColor(ThemeColors.dayTextPrimary());
                        tvArtist.setTextColor(ThemeColors.dayTextSecondary());
                        tvDuration.setTextColor(ThemeColors.dayTextSecondary());
                    }
                }
            }
        });
    }

    // ========== 收藏刷新 ==========

    private void refreshFavorites() {
        List<Song> favSongs = FavoriteManager.loadFavorites(favDir);
        List<Song> merged = new ArrayList<>();
        for (Song fav : favSongs) {
            Song matched = findSongInList(allSongs, fav.filePath);
            merged.add(matched != null ? matched : fav);
        }
        songAdapter.setFavoriteSongs(merged);
        songAdapter.setAllSongs(allSongs);
        if (currentTab == 3) {
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                scanMusic();
                requestNotificationPermissionIfNeeded();
            } else {
                tvLoading.setVisibility(View.VISIBLE);
                currentPermissionRequest = Manifest.permission.READ_MEDIA_AUDIO;
                permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
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

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                currentPermissionRequest = Manifest.permission.POST_NOTIFICATIONS;
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        // 申请悬浮窗权限（Android 6+需要用户手动在设置中开启）
        requestOverlayPermissionIfNeeded();
    }

    private void requestOverlayPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("悬浮窗权限")
                        .setMessage("后台播放时需要悬浮窗权限来显示迷你播放窗口。是否前往设置开启？")
                        .setPositiveButton("去设置", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + getPackageName()));
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
        }
    }

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
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    }
                })
                .setNegativeButton("暂不", null)
                .show();
    }

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
        // 浏览区显示加载中
        rvBrowse.setVisibility(View.GONE);
        browseLoading.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            List<Song> songs = MusicScanner.scanMusic(this);
            runOnUiThread(() -> {
                isScanning = false;
                allSongs = songs;
                songAdapter.setAllSongs(songs);
                tvLoading.setVisibility(View.GONE);
                browseLoading.setVisibility(View.GONE);
                rootLayout.setVisibility(View.VISIBLE);
                refreshFavorites();
                // 加载当前tab内容
                loadCurrentTabContent();
                updateCountText();
                applyThemeToRecyclerViewItems();
                // autoResume 不再阻止内容加载，放最后执行
                if (tryAutoResume) {
                    autoResumeLastPlayed(songs);
                }
            });
        });
    }

    private final Runnable scanDebounceRunnable = () -> {
        Log.d(TAG, "MediaStore onChange，重新扫描音乐");
        scanMusic(false);
    };

    // ========== 自动恢复上次播放 ==========

    private boolean autoResumeLastPlayed(List<Song> songs) {
        if (hasAutoResumed) {
            Log.d(TAG, "autoResumeLastPlayed: already resumed, skip");
            return false;
        }

        android.content.SharedPreferences prefs = getSharedPreferences("last_played", MODE_PRIVATE);
        if (!prefs.getBoolean("has_last", false)) return false;

        long songId = prefs.getLong("song_id", 0);
        String title = prefs.getString("song_title", "");
        if (title == null || title.isEmpty()) return false;

        int foundPosition = -1;
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).id == songId) {
                foundPosition = i;
                break;
            }
        }
        if (foundPosition < 0) foundPosition = prefs.getInt("position", 0);

        String savedPlaylistMode = prefs.getString("playlist_mode", "all");

        // 根据播放模式切到对应tab，这样返回时不会落到错误的页面
        if ("bili".equals(savedPlaylistMode)) {
            currentTab = 2;
        } else if ("webdav".equals(savedPlaylistMode)) {
            currentTab = 1;
        }

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
        // B站专属字段
        resumeSong.sourceType = prefs.getInt("song_source_type", Song.SOURCE_LOCAL);
        resumeSong.bvid = prefs.getString("song_bvid", "");
        resumeSong.cid = prefs.getLong("song_cid", 0);
        resumeSong.audioUrl = prefs.getString("song_audio_url", "");
        resumeSong.audioUrlExpire = prefs.getLong("song_audio_url_expire", 0);
        resumeSong.coverUrl = prefs.getString("song_cover_url", "");
        resumeSong.toIntent(intent);
        intent.putExtra("position", foundPosition);
        intent.putExtra("playlist_mode", savedPlaylistMode);
        intent.putExtra("resume_play", true);

        if ("folder".equals(savedPlaylistMode)) {
            java.util.Set<String> pathSet = prefs.getStringSet("folder_song_paths", null);
            if (pathSet != null && !pathSet.isEmpty()) {
                intent.putStringArrayListExtra("folder_song_paths", new java.util.ArrayList<>(pathSet));
            }
        } else if ("webdav".equals(savedPlaylistMode)) {
            // WebDAV模式：恢复from_webdav标记和播放索引
            intent.putExtra("from_webdav", true);
            intent.putExtra("song_index", foundPosition);
        } else if ("bili".equals(savedPlaylistMode)) {
            // B站模式：恢复from_webdav标记（B站播放列表也存webdav_playlist SP中）和播放索引
            intent.putExtra("from_webdav", true);
            intent.putExtra("song_index", foundPosition);
        }

        hasAutoResumed = true;
        startActivity(intent);
        return true;
    }

    // ========== Mini 播放条 ==========

    private void updateMiniPlayerFromService() {
        if (bound && playerBinder != null) {
            Song currentSong = playerBinder.getCurrentSong();
            if (currentSong != null && currentSong.title != null) {
                miniSongTitle.setText(currentSong.title);
                miniSongArtist.setText(currentSong.artist != null ? currentSong.artist : "");
                miniPlayerWrap.setVisibility(View.VISIBLE);
                if (shimmerAnimator != null && !shimmerAnimator.isRunning()) shimmerAnimator.start();
                miniPlayPause.setImageResource(playerBinder.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
                // 更新迷你封面
                loadMiniCover(currentSong.title, currentSong.artist);
                // 根据播放状态控制旋转
                if (playerBinder.isPlaying()) {
                    startCoverRotation();
                } else {
                    pauseCoverRotation();
                }
            }
        }
    }

    private void openPlayerFromMini() {
        if (bound && playerBinder != null) {
            Song currentSong = playerBinder.getCurrentSong();
            if (currentSong != null && currentSong.title != null && !currentSong.title.isEmpty()) {
                // 从 last_played 读取完整的来源信息（playlist_mode、B站字段、导航上下文）
                android.content.SharedPreferences prefs = getSharedPreferences("last_played", MODE_PRIVATE);
                String savedPlaylistMode = prefs.getString("playlist_mode", "all");
                String biliNavUrl = prefs.getString("bili_nav_url", "");

                Intent intent = new Intent(this, PlayerActivity.class);
                currentSong.toIntent(intent);
                intent.putExtra("position", playerBinder.getCurrentIndex());
                intent.putExtra("playlist_mode", savedPlaylistMode);
                intent.putExtra("resume_play", true);
                intent.putExtra("bili_nav_url", biliNavUrl);

                if ("webdav".equals(savedPlaylistMode) || "bili".equals(savedPlaylistMode)) {
                    intent.putExtra("from_webdav", true);
                    intent.putExtra("song_index", playerBinder.getCurrentIndex());
                }

                returningFromPlayer = true;
                startActivity(intent);
            } else if (playerBinder.isPlaying() || playerBinder.getCurrentIndex() >= 0) {
                // Service 在播放但 getCurrentSong 返回 null（playlist/index 状态异常）
                // 用 last_played 恢复
                android.content.SharedPreferences prefs = getSharedPreferences("last_played", MODE_PRIVATE);
                if (prefs.getBoolean("has_last", false)) {
                    Intent intent = new Intent(this, PlayerActivity.class);
                    Song resumeSong = new Song();
                    resumeSong.id = prefs.getLong("song_id", 0);
                    resumeSong.title = prefs.getString("song_title", "");
                    resumeSong.artist = prefs.getString("song_artist", "");
                    resumeSong.album = prefs.getString("song_album", "");
                    resumeSong.duration = prefs.getLong("song_duration", 0);
                    resumeSong.filePath = prefs.getString("song_path", "");
                    resumeSong.contentUri = prefs.getString("song_uri", "");
                    resumeSong.albumArt = prefs.getString("album_art", "");
                    resumeSong.displayName = resumeSong.title;
                    resumeSong.sourceType = prefs.getInt("song_source_type", Song.SOURCE_LOCAL);
                    resumeSong.bvid = prefs.getString("song_bvid", "");
                    resumeSong.cid = prefs.getLong("song_cid", 0);
                    resumeSong.audioUrl = prefs.getString("song_audio_url", "");
                    resumeSong.audioUrlExpire = prefs.getLong("song_audio_url_expire", 0);
                    resumeSong.coverUrl = prefs.getString("song_cover_url", "");
                    resumeSong.toIntent(intent);
                    intent.putExtra("position", prefs.getInt("position", 0));
                    intent.putExtra("playlist_mode", prefs.getString("playlist_mode", "all"));
                    intent.putExtra("resume_play", true);
                    intent.putExtra("bili_nav_url", prefs.getString("bili_nav_url", ""));

                    String savedPlaylistMode = prefs.getString("playlist_mode", "all");
                    if ("webdav".equals(savedPlaylistMode) || "bili".equals(savedPlaylistMode)) {
                        intent.putExtra("from_webdav", true);
                        intent.putExtra("song_index", prefs.getInt("position", 0));
                    }

                    returningFromPlayer = true;
                    startActivity(intent);
                }
            }
        }
    }

    /**
     * Tab指示线呼吸动画 — 2.5秒周期，alpha从0.5到1.0缓慢呼吸
     */
    private void startTabBreathAnimation(int activeColor) {
        if (tabBreathAnimator != null) tabBreathAnimator.cancel();
        // 找到当前激活的指示线
        View activeIndicator = null;
        if (currentTab == 0) activeIndicator = indicatorLocal;
        else if (currentTab == 1) activeIndicator = indicatorCloud;
        else if (currentTab == 2) activeIndicator = indicatorBili;
        else activeIndicator = indicatorFavorite;
        if (activeIndicator == null) return;

        // 设置激活色
        activeIndicator.setBackgroundColor(activeColor);
        tabBreathAnimator = android.animation.ObjectAnimator.ofInt(activeIndicator, "backgroundColor",
                activeColor, adjustAlpha(activeColor, 0.4f), activeColor);
        tabBreathAnimator.setDuration(2500);
        tabBreathAnimator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        tabBreathAnimator.setEvaluator(new android.animation.ArgbEvaluator());
        tabBreathAnimator.start();
    }

    /**
     * 迷你播放条流光动画 — 绿色光带从左到右循环移动
     */
    private android.animation.ObjectAnimator shimmerAnimator;
    private void startMiniShimmerAnimation() {
        if (miniShimmerLine == null) return;
        if (shimmerAnimator != null) shimmerAnimator.cancel();
        // 使用 translationX 制造流光效果：view从-宽度移动到屏幕宽度
        miniShimmerLine.setTranslationX(-miniShimmerLine.getWidth());
        shimmerAnimator = android.animation.ObjectAnimator.ofFloat(miniShimmerLine, "translationX",
                -getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().widthPixels);
        shimmerAnimator.setDuration(3000);
        shimmerAnimator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        shimmerAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        // 只在mini播放条可见时启动
        if (miniPlayerWrap != null && miniPlayerWrap.getVisibility() == View.VISIBLE) {
            shimmerAnimator.start();
        }
    }

    /** 调整颜色透明度 */
    private static int adjustAlpha(int color, float factor) {
        int a = Math.round(((color >> 24) & 0xFF) * factor);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 启动封面旋转（首次start，暂停后resume）
     */
    private void startCoverRotation() {
        if (coverRotateAnimator == null) return;
        if (coverRotateAnimator.isPaused()) {
            coverRotateAnimator.resume();
        } else if (!coverRotateAnimator.isRunning()) {
            coverRotateAnimator.start();
        }
    }

    /**
     * 暂停封面旋转
     */
    private void pauseCoverRotation() {
        if (coverRotateAnimator != null && coverRotateAnimator.isRunning()) {
            coverRotateAnimator.pause();
        }
    }

    /**
     * 加载迷你播放条圆形封面
     * 先查本地缓存目录，没有则用默认图标
     */
    private void loadMiniCover(String title, String artist) {
        if (title == null) return;
        executor.execute(() -> {
            android.graphics.Bitmap coverBitmap = null;
            try {
                File coverDir = getExternalFilesDir("covers");
                if (coverDir != null) {
                    String coverName = Song.toFileName(title, artist != null ? artist : "") + ".jpg";
                    File coverFile = new File(coverDir, coverName);
                    if (coverFile.exists() && coverFile.length() > 0) {
                        coverBitmap = android.graphics.BitmapFactory.decodeFile(coverFile.getAbsolutePath());
                    }
                    if (coverBitmap == null) {
                        File[] coverFiles = coverDir.listFiles((dir, name) ->
                                name.startsWith(title) && name.endsWith(".jpg"));
                        if (coverFiles != null && coverFiles.length > 0) {
                            coverBitmap = android.graphics.BitmapFactory.decodeFile(coverFiles[0].getAbsolutePath());
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "迷你封面查找失败: " + e.getMessage());
            }
            if (coverBitmap != null) {
                // 圆形裁剪
                int size = Math.min(coverBitmap.getWidth(), coverBitmap.getHeight());
                android.graphics.Bitmap squared = android.graphics.Bitmap.createBitmap(
                        coverBitmap,
                        (coverBitmap.getWidth() - size) / 2,
                        (coverBitmap.getHeight() - size) / 2,
                        size, size);
                android.graphics.Bitmap rounded = getScaledRoundedBitmap(squared, 42);
                runOnUiThread(() -> miniCover.setImageBitmap(rounded));
            } else {
                // 无封面，保持默认图标（不再额外设置，布局里已有默认src）
            }
        });
    }

    /** 将Bitmap缩放并裁剪为圆形 */
    private android.graphics.Bitmap getScaledRoundedBitmap(android.graphics.Bitmap bitmap, int dpSize) {
        float density = getResources().getDisplayMetrics().density;
        int px = (int) (dpSize * density);
        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, px, px, true);
        android.graphics.Bitmap output = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(output);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setAntiAlias(true);
        canvas.drawCircle(px / 2f, px / 2f, px / 2f, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(scaled, 0, 0, paint);
        return output;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (coverRotateAnimator != null) { coverRotateAnimator.cancel(); coverRotateAnimator = null; }
        if (tabBreathAnimator != null) { tabBreathAnimator.cancel(); tabBreathAnimator = null; }
        if (shimmerAnimator != null) { shimmerAnimator.cancel(); shimmerAnimator = null; }
        try {
            unregisterReceiver(playStateReceiver);
        } catch (Exception ignored) {}
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
