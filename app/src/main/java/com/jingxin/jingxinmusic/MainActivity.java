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
    private TextView tvTitle;
    private EditText etSearch;
    private ImageView btnTheme;
    private ImageView btnClose;
    private View rootLayout;
    private View tabBar;
    private View titleBar;
    private View tabDivider1;
    private View tabDivider2;
    private View indicatorLocal;
    private View indicatorCloud;
    private View indicatorFavorite;
    private RecyclerView rvList;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Tab views
    private TextView tabLocal;
    private TextView tabCloud;
    private TextView tabFavorite;

    // Browse area
    private View browseArea;
    private View favoriteArea;
    private View pathBar;
    private ImageView btnNavigateBack;
    private TextView tvBrowsePath;
    private ImageView btnWebDavSettings;
    private RecyclerView rvBrowse;
    private View webdavSetupArea;
    private View browseLoading;
    private TextView btnGoWebDavSettings;

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
    private String cloudCurrentUrl = null;

    // Current tab: 0=local, 1=cloud, 2=favorite
    private int currentTab = 0;

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
                miniPlayer.setVisibility(View.VISIBLE);
            } else if (MusicPlayerService.ACTION_PLAY_STATE_CHANGED.equals(action)) {
                boolean playing = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false);
                miniPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
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
        indicatorLocal = findViewById(R.id.indicator_local);
        indicatorCloud = findViewById(R.id.indicator_cloud);
        indicatorFavorite = findViewById(R.id.indicator_favorite);
        tvCopyright = findViewById(R.id.tv_copyright);
        rvList = findViewById(R.id.rv_song_list);

        // Browse area
        browseArea = findViewById(R.id.browse_area);
        favoriteArea = findViewById(R.id.favorite_area);
        pathBar = findViewById(R.id.path_bar);
        btnNavigateBack = findViewById(R.id.btn_navigate_back);
        tvBrowsePath = findViewById(R.id.tv_browse_path);
        btnWebDavSettings = findViewById(R.id.btn_webdav_settings);
        rvBrowse = findViewById(R.id.rv_browse);
        webdavSetupArea = findViewById(R.id.webdav_setup_area);
        browseLoading = findViewById(R.id.loading_layout);
        btnGoWebDavSettings = findViewById(R.id.btn_go_webdav_settings);

        // Mini 播放条
        miniPlayer = findViewById(R.id.mini_player);
        miniSongTitle = findViewById(R.id.mini_song_title);
        miniSongArtist = findViewById(R.id.mini_song_artist);
        miniPlayPause = findViewById(R.id.mini_play_pause);

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
        tabFavorite.setOnClickListener(v -> switchTab(2));

        // 返回按钮（路径栏）
        btnNavigateBack.setOnClickListener(v -> {
            if (currentTab == 0) {
                navigateLocalBack();
            } else if (currentTab == 1) {
                navigateCloudBack();
            }
        });

        // 云端设置按钮
        btnWebDavSettings.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, com.jingxin.jingxinmusic.ui.WebDavSettingsActivity.class));
        });

        // 去配置按钮
        btnGoWebDavSettings.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, com.jingxin.jingxinmusic.ui.WebDavSettingsActivity.class));
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

        // 初始化首页Tab为本地
        switchTab(0);

        // 点击 mini 播放条主体 → 跳转播放页
        findViewById(R.id.mini_player_info).setOnClickListener(v -> openPlayerFromMini());

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
    protected void onResume() {
        super.onResume();
        rootLayout.setVisibility(View.VISIBLE);
        boolean savedNight = themePrefs.getBoolean("isNight", true);
        if (savedNight != isNightMode) {
            isNightMode = savedNight;
            updateThemeUI();
        }
        // 刷新收藏
        refreshFavorites();
        // 刷新当前Tab的浏览内容
        webDavConfig = new WebDavConfig(this);
        if (currentTab == 0) {
            loadLocalItems();
        } else if (currentTab == 1) {
            loadCloudItems();
        }
        // 更新 mini 播放条
        updateMiniPlayerFromService();
    }

    // ========== Tab 切换 ==========

    private void switchTab(int mode) {
        currentTab = mode;

        int activeColor = isNightMode ? ThemeColors.NIGHT_TAB_ACTIVE : ThemeColors.DAY_TAB_ACTIVE;
        int inactiveColor = isNightMode ? ThemeColors.NIGHT_TAB_INACTIVE : ThemeColors.DAY_TAB_INACTIVE;
        int indicatorActive = isNightMode ? ThemeColors.NIGHT_TAB_INDICATOR : ThemeColors.DAY_TAB_INDICATOR;
        int indicatorInactive = 0x00000000; // 透明
        tabLocal.setTextColor(mode == 0 ? activeColor : inactiveColor);
        tabLocal.setTypeface(null, mode == 0 ? Typeface.BOLD : Typeface.NORMAL);
        tabCloud.setTextColor(mode == 1 ? activeColor : inactiveColor);
        tabCloud.setTypeface(null, mode == 1 ? Typeface.BOLD : Typeface.NORMAL);
        tabFavorite.setTextColor(mode == 2 ? activeColor : inactiveColor);
        tabFavorite.setTypeface(null, mode == 2 ? Typeface.BOLD : Typeface.NORMAL);
        // Tab指示线
        indicatorLocal.setBackgroundColor(mode == 0 ? indicatorActive : indicatorInactive);
        indicatorCloud.setBackgroundColor(mode == 1 ? indicatorActive : indicatorInactive);
        indicatorFavorite.setBackgroundColor(mode == 2 ? indicatorActive : indicatorInactive);

        if (mode == 0) {
            // 本地
            browseArea.setVisibility(View.VISIBLE);
            favoriteArea.setVisibility(View.GONE);
            loadLocalItems();
        } else if (mode == 1) {
            // 云端
            browseArea.setVisibility(View.VISIBLE);
            favoriteArea.setVisibility(View.GONE);
            loadCloudItems();
        } else {
            // 收藏
            browseArea.setVisibility(View.GONE);
            favoriteArea.setVisibility(View.VISIBLE);
            // 收藏歌曲用封面卡片网格显示
            int spanCount = Math.max(3, getResources().getDisplayMetrics().widthPixels / 360);
            rvList.setLayoutManager(new GridLayoutManager(this, spanCount));
            rvList.setAdapter(browseAdapter);
            refreshFavorites();
            loadFavoriteBrowseItems();
        }

        etSearch.setText("");
        updateCountText();
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
        } else {
            pathBar.setVisibility(View.VISIBLE);
            btnWebDavSettings.setVisibility(View.GONE);
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

    private void loadCloudItems() {
        webDavConfig = new WebDavConfig(this);
        if (!webDavConfig.isConfigured()) {
            // 未配置：显示引导
            rvBrowse.setVisibility(View.GONE);
            browseLoading.setVisibility(View.GONE);
            pathBar.setVisibility(View.GONE);
            webdavSetupArea.setVisibility(View.VISIBLE);
            return;
        }

        webdavSetupArea.setVisibility(View.GONE);

        // 重新初始化scanner（配置可能已更新）
        webDavScanner = new WebDavScanner(webDavConfig);

        // 如果有上次的位置则恢复，否则从根目录开始
        String url = cloudCurrentUrl != null ? cloudCurrentUrl : webDavConfig.getMusicUrl();
        navigateCloudTo(url);
    }

    private void navigateCloudTo(String url) {
        // 入子目录时压栈
        if (url != null && cloudCurrentUrl != null && !url.equals(cloudCurrentUrl)) {
            cloudNavStack.push(cloudCurrentUrl);
        }
        cloudCurrentUrl = url;

        // 显示路径栏
        pathBar.setVisibility(View.VISIBLE);
        btnWebDavSettings.setVisibility(View.VISIBLE);
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
                    bi = BrowseItem.directory(di.name, di.path, di.url, BrowseItem.SOURCE_WEBDAV);
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
            loadCloudItems();
            return;
        }
        String parentUrl = cloudNavStack.pop();
        cloudCurrentUrl = null; // 防止压栈
        navigateCloudTo(parentUrl);
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

        if (clickedItem.source == BrowseItem.SOURCE_LOCAL) {
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

            if (currentTab == 2) {
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
            // WebDAV歌曲
            long idBase = 1000000;
            for (int i = 0; i < items.size(); i++) {
                BrowseItem item = items.get(i);
                if (!item.isDirectory) {
                    Song song;
                    if (item.song != null) {
                        song = item.song;
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

            Intent intent = new Intent(this, PlayerActivity.class);
            Song clickedSong = playlist.get(playIndex);
            clickedSong.toIntent(intent);
            intent.putExtra("position", playIndex);
            intent.putExtra("playlist_mode", "webdav");
            intent.putExtra("from_webdav", true);
            intent.putExtra("webdav_playlist_size", playlist.size());
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
        } else if (currentTab == 1) {
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
            // Browse area
            browseArea.setBackgroundColor(ThemeColors.NIGHT_BG);
            pathBar.setBackgroundColor(ThemeColors.NIGHT_BAR_BG);
            tvBrowsePath.setTextColor(ThemeColors.NIGHT_TEXT_SECONDARY);
            btnNavigateBack.setColorFilter(ThemeColors.NIGHT_TEXT_SECONDARY);
            btnWebDavSettings.setColorFilter(ThemeColors.NIGHT_TEXT_SECONDARY);
            btnGoWebDavSettings.setTextColor(ThemeColors.NIGHT_TAB_ACTIVE);
            try {
                TextView setupMsg = webdavSetupArea.findViewById(R.id.tv_webdav_setup_msg);
                if (setupMsg != null) setupMsg.setTextColor(ThemeColors.NIGHT_TEXT_SECONDARY);
            } catch (Exception ignored) {}
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
            // Browse area
            browseArea.setBackgroundColor(ThemeColors.DAY_BG);
            pathBar.setBackgroundColor(ThemeColors.DAY_BAR_BG);
            tvBrowsePath.setTextColor(ThemeColors.DAY_TEXT_SECONDARY);
            btnNavigateBack.setColorFilter(ThemeColors.DAY_TEXT_SECONDARY);
            btnWebDavSettings.setColorFilter(ThemeColors.DAY_TEXT_SECONDARY);
            btnGoWebDavSettings.setTextColor(ThemeColors.DAY_TAB_ACTIVE);
            try {
                TextView setupMsg = webdavSetupArea.findViewById(R.id.tv_webdav_setup_msg);
                if (setupMsg != null) setupMsg.setTextColor(ThemeColors.DAY_TEXT_SECONDARY);
            } catch (Exception ignored) {}
            // mini 播放条
            miniPlayer.setBackgroundColor(ThemeColors.DAY_MINI_BG);
            miniSongTitle.setTextColor(ThemeColors.DAY_TEXT_PRIMARY);
            miniSongArtist.setTextColor(ThemeColors.DAY_TEXT_SECONDARY);
            miniPlayPause.setColorFilter(ThemeColors.DAY_TEXT_PRIMARY, PorterDuff.Mode.SRC_IN);
        }
        // 更新 Tab 文字颜色
        int activeColor = isNightMode ? ThemeColors.NIGHT_TAB_ACTIVE : ThemeColors.DAY_TAB_ACTIVE;
        int inactiveColor = isNightMode ? ThemeColors.NIGHT_TAB_INACTIVE : ThemeColors.DAY_TAB_INACTIVE;
        tabLocal.setTextColor(currentTab == 0 ? activeColor : inactiveColor);
        tabCloud.setTextColor(currentTab == 1 ? activeColor : inactiveColor);
        tabFavorite.setTextColor(currentTab == 2 ? activeColor : inactiveColor);
        // Tab指示线
        int indicatorActive = isNightMode ? ThemeColors.NIGHT_TAB_INDICATOR : ThemeColors.DAY_TAB_INDICATOR;
        int indicatorInactive = 0x00000000;
        indicatorLocal.setBackgroundColor(currentTab == 0 ? indicatorActive : indicatorInactive);
        indicatorCloud.setBackgroundColor(currentTab == 1 ? indicatorActive : indicatorInactive);
        indicatorFavorite.setBackgroundColor(currentTab == 2 ? indicatorActive : indicatorInactive);
        // 同步 adapter 主题
        songAdapter.setNightMode(isNightMode);
        browseAdapter.setNightMode(isNightMode);
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
        List<Song> merged = new ArrayList<>();
        for (Song fav : favSongs) {
            Song matched = findSongInList(allSongs, fav.filePath);
            merged.add(matched != null ? matched : fav);
        }
        songAdapter.setFavoriteSongs(merged);
        songAdapter.setAllSongs(allSongs);
        if (currentTab == 2) {
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
                browseAdapter.setAllSongs(songs);
                tvLoading.setVisibility(View.GONE);
                browseLoading.setVisibility(View.GONE);
                if (tryAutoResume && autoResumeLastPlayed(songs)) {
                    return;
                }
                rootLayout.setVisibility(View.VISIBLE);
                refreshFavorites();
                // 加载当前tab内容
                if (currentTab == 0) {
                    loadLocalItems();
                } else if (currentTab == 1) {
                    loadCloudItems();
                }
                updateCountText();
                applyThemeToRecyclerViewItems();
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

        if ("folder".equals(savedPlaylistMode)) {
            java.util.Set<String> pathSet = prefs.getStringSet("folder_song_paths", null);
            if (pathSet != null && !pathSet.isEmpty()) {
                intent.putStringArrayListExtra("folder_song_paths", new java.util.ArrayList<>(pathSet));
            }
        } else if ("webdav".equals(savedPlaylistMode)) {
            // WebDAV模式：恢复from_webdav标记和播放索引
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
                miniPlayer.setVisibility(View.VISIBLE);
                miniPlayPause.setImageResource(playerBinder.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            }
        }
    }

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
