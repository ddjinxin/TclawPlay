package com.jingxin.jingxinmusic.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import com.jingxin.jingxinmusic.view.DynamicBgView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.jingxin.jingxinmusic.HostActivity;
import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.adapter.BrowseAdapter;
import com.jingxin.jingxinmusic.adapter.SongAdapter;
import com.jingxin.jingxinmusic.model.BrowseItem;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.service.MusicPlayerService.MusicPlayerBinder;
import com.jingxin.jingxinmusic.util.BitmapUtil;
import com.jingxin.jingxinmusic.util.BiliApi;
import com.jingxin.jingxinmusic.util.BiliConfig;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.util.FavoriteManager;
import com.jingxin.jingxinmusic.util.LocalDirectoryScanner;
import com.jingxin.jingxinmusic.util.MusicScanner;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.util.UpdateHelper;
import com.jingxin.jingxinmusic.util.WebDavConfig;
import com.jingxin.jingxinmusic.util.WebDavScanner;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 歌曲列表页面 Fragment
 * 三种模式：本地、云端、收藏
 */
public class MainFragment extends BaseFloatFragment {

    private static final String TAG = "MainFragment";

    private View mRootView;

    // 收藏列表适配器
    private SongAdapter songAdapter;
    private TextView tvSongCount;
    private TextView tvLoading;
    private TextView tvCopyright;
    private ImageView ivAppIcon;
    private EditText etSearch;
    private ImageView btnClose;
    private ImageView btnHelp;
    private View rootLayout;
    private DynamicBgView dynamicBgView;
    private View tabBar;
    private View titleBar;
    private int systemTopInset = 0;
    private View tabIndicator;
    private View tabBottomLine;
    private View indicatorLocal;
    private View indicatorCloud;
    private View indicatorBili;
    private View indicatorFavorite;
    private View titleAccentLine;
    private View miniShimmerLine;
    private View miniPlayerWrap;
    private android.animation.ObjectAnimator tabBreathAnimator;
    private RecyclerView rvList;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Tab views（LinearLayout 容器）
    private View tabLocal;
    private View tabCloud;
    private View tabBili;
    private View tabFavorite;
    // Tab 子文字（小标题 + 大标题）
    private TextView tabLocalTop, tabLocalTitle;
    private TextView tabCloudTop, tabCloudTitle;
    private TextView tabBiliTop, tabBiliTitle;
    private TextView tabFavoriteTop, tabFavoriteTitle;

    // Browse area
    private View browseArea;
    private View favoriteArea;
    private View pathBar;
    private ImageView btnNavigateBack;
    private TextView tvBrowsePath;
    private ImageView btnWebDavSettings;
    private ImageView btnBiliSettings;
    private View searchBarContainer;
    private RecyclerView rvBrowse;
    private View webdavSetupArea;
    private View browseLoading;
    private TextView btnGoWebDavSettings;
    private TextView tvSetupMsg;

    // Browse adapter
    private BrowseAdapter browseAdapter;

    /** browseAdapter 当前已加载内容的 B站URL（null 表示非B站内容）；用于从播放页返回时复用，避免网格封面闪烁 */
    private String lastLoadedBiliUrl = null;

    // Local browse state
    private List<Song> allSongs = new ArrayList<>();
    private Stack<String> localNavStack = new Stack<>();
    private String localCurrentDir = null;

    // Cloud browse state
    private WebDavConfig webDavConfig;
    private WebDavScanner webDavScanner;
    private Stack<String> cloudNavStack = new Stack<>();
    private Stack<String> biliNavStack = new Stack<>();
    private String biliCurrentUrl = null;
    private String cloudCurrentUrl = null;

    // Current tab: 0=local, 1=cloud, 2=bili, 3=favorite
    private int currentTab = 0;

    private boolean returningFromPlayer = false;
    /** onCreateView 已执行过（popBackStack 返回时实例保留，应恢复离开时的 tab 而非读 last_played） */
    private boolean viewCreatedOnce = false;

    // 主题
    private boolean isNightMode = true;
    private SharedPreferences themePrefs;

    private File favDir;

    private boolean hasAutoResumed = false;

    /** 标记已自动恢复过，防止悬浮返回时 new MainFragment 又自动跳到播放页 */
    public void setAutoResumed(boolean value) {
        hasAutoResumed = value;
    }

    private ActivityResultLauncher<String> permissionLauncher;
    private ActivityResultLauncher<String[]> multiPermissionLauncher;
    private ActivityResultLauncher<Intent> manageStorageLauncher;
    private boolean pendingScanAfterStoragePermission = false;

    private String currentPermissionRequest;

    private android.database.ContentObserver mediaStoreObserver;
    private volatile boolean isScanning = false;
    private volatile boolean isManualScanning = false;
    private final Object scanLock = new Object();
    private static final int SELF_SCAN_IGNORE_MS = 3000;
    private final Handler scanDebounceHandler = new Handler();
    private static final int SCAN_DEBOUNCE_MS = 500;

    // 存储广播
    private BroadcastReceiver storageReceiver;
    private boolean firstMountReceived = false;

    // onResume 存储卷快照
    private Set<String> lastStorageSnapshot = null;
    // onResume 缓存新鲜度检查
    private long lastShownCacheTime = 0;

    // Mini 播放条
    private View miniPlayer;
    private TextView miniSongTitle;
    private TextView miniSongArtist;
    private ImageView miniPlayPause;
    private ImageView miniCover;
    private com.jingxin.jingxinmusic.util.CoverRotationHelper coverRotationHelper = new com.jingxin.jingxinmusic.util.CoverRotationHelper();

    // 播放服务绑定
    private MusicPlayerBinder playerBinder;
    private boolean bound = false;

    // 动态主色背景：是否已启用（仅春意盎然风格）
    private boolean dynamicBgEnabled = false;

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
                loadMiniCover(title, artist);
                startCoverRotation();
                // 春意盎然风格：提取封面主色更新首页背景
                if (dynamicBgEnabled) {
                    extractAndApplyMagicBgColor(title, artist);
                }
            } else if (MusicPlayerService.ACTION_PLAY_STATE_CHANGED.equals(action)) {
                boolean playing = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false);
                miniPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
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
            } else if (MusicPlayerService.ACTION_THEME_CHANGED.equals(action)) {
                // 高德日夜切换：悬浮态 Activity 已 stopped 不会走 onResume 重读，必须广播驱动刷新
                boolean amapNight = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_NIGHT, isNightMode);
                if (amapNight != isNightMode) {
                    isNightMode = amapNight;
                    updateThemeUI();
                }
            }
        }
    };

    // 手动扫描完成广播接收器
    private BroadcastReceiver scanCompleteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MusicScanner.ACTION_SCAN_COMPLETE.equals(intent.getAction())) {
                isManualScanning = false;
                Context ctx = context.getApplicationContext();
                executor.execute(() -> {
                    List<Song> songs = MusicScanner.loadCache(ctx);
                    if (songs == null) {
                        songs = MusicScanner.scanMusic(ctx);
                    }
                    List<Song> finalSongs = songs;
                    runOnUi(() -> {
                        if (isActivityGone()) return;
                        applyScanResult(finalSongs, ctx);
                    });
                });
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

    // ==================== 生命周期 ====================

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (Manifest.permission.POST_NOTIFICATIONS.equals(currentPermissionRequest)) {
                        if (!isGranted) {
                            showNotificationPermissionDeniedDialog();
                        }
                    } else {
                        if (isGranted) {
                            checkManageStorageAndScan();
                        } else {
                            if (tvLoading != null) tvLoading.setVisibility(View.GONE);
                            if (tvSongCount != null) tvSongCount.setText("需要存储权限才能扫描音乐");
                        }
                    }
                    currentPermissionRequest = null;
                });

        manageStorageLauncher = registerForActivityResult(
                new StartActivityForResult(),
                result -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pendingScanAfterStoragePermission) {
                        pendingScanAfterStoragePermission = false;
                        if (Environment.isExternalStorageManager()) {
                            clearCacheAndScan();
                        } else {
                            scanMusic();
                        }
                    }
                });

        multiPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean readGranted = Boolean.TRUE.equals(result.get(Manifest.permission.READ_EXTERNAL_STORAGE));
                    if (readGranted) {
                        checkManageStorageAndScan();
                        // WRITE_EXTERNAL_STORAGE 的 maxSdkVersion=28，Android 10+ 系统不会授予该权限。
                        // 仅在 API <= 28 时检查写入权限被拒情况，避免 Android 10+ 误弹对话框
                        if (Build.VERSION.SDK_INT <= 28) {
                            boolean writeGranted = Boolean.TRUE.equals(result.get(Manifest.permission.WRITE_EXTERNAL_STORAGE));
                            if (!writeGranted && !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                                showWriteStoragePermissionDeniedDialog();
                            }
                        }
                        requestNotificationPermissionIfNeeded();
                    } else {
                        if (tvLoading != null) tvLoading.setVisibility(View.GONE);
                        if (tvSongCount != null) tvSongCount.setText("需要存储权限才能扫描音乐");
                    }
                });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_main, container, false);
        mRootView = view;

        Context ctx = requireContext();

        favDir = FavoriteManager.getFavoriteDir(ctx);

        themePrefs = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE);
        isNightMode = themePrefs.getBoolean("isNight", true);
        ThemeColors.init(ctx);
        // 冷启动恢复持久化主色：重查表后 updateThemeUI 秒出封面渐变（无绑定竞态）
        ThemeColors.restoreDominant(ctx);

        // 初始化视图
        tvSongCount = view.findViewById(R.id.tv_song_count);
        tvLoading = view.findViewById(R.id.tv_loading);
        ivAppIcon = view.findViewById(R.id.iv_app_icon);
        etSearch = view.findViewById(R.id.et_search);
        rootLayout = view.findViewById(R.id.root_layout);
        dynamicBgView = view.findViewById(R.id.dynamic_bg_view);
        tabBar = view.findViewById(R.id.tab_bar);
        titleBar = view.findViewById(R.id.title_bar);

        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                topInset = insets.getInsets(android.view.WindowInsets.Type.systemBars()).top;
            } else {
                topInset = insets.getSystemWindowInsetTop();
            }
            if (systemTopInset != topInset) {
                systemTopInset = topInset;
                applyTitleBarTopMargin();
            }
            return insets;
        });
        tabIndicator = view.findViewById(R.id.tab_indicator);
        tabBottomLine = view.findViewById(R.id.tab_bottom_line);
        indicatorLocal = view.findViewById(R.id.indicator_local);
        indicatorCloud = view.findViewById(R.id.indicator_cloud);
        indicatorBili = view.findViewById(R.id.indicator_bili);
        indicatorFavorite = view.findViewById(R.id.indicator_favorite);
        titleAccentLine = view.findViewById(R.id.title_accent_line);
        miniShimmerLine = view.findViewById(R.id.mini_shimmer_line);
        miniPlayerWrap = view.findViewById(R.id.mini_player_wrap);
        tvCopyright = view.findViewById(R.id.tv_copyright);
        // 版权信息：版本号动态读取（随 App 升级自动更新），单行标语式
        try {
            String version = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
            tvCopyright.setText("心静，音自清 · v" + version);
        } catch (Exception ignored) {}
        rvList = view.findViewById(R.id.rv_song_list);

        browseArea = view.findViewById(R.id.browse_area);
        favoriteArea = view.findViewById(R.id.favorite_area);
        pathBar = view.findViewById(R.id.path_bar);
        btnNavigateBack = view.findViewById(R.id.btn_navigate_back);
        tvBrowsePath = view.findViewById(R.id.tv_browse_path);
        btnWebDavSettings = view.findViewById(R.id.btn_webdav_settings);
        btnBiliSettings = view.findViewById(R.id.btn_bili_settings);
        searchBarContainer = view.findViewById(R.id.search_bar_container);
        rvBrowse = view.findViewById(R.id.rv_browse);
        webdavSetupArea = view.findViewById(R.id.webdav_setup_area);
        browseLoading = view.findViewById(R.id.loading_layout);
        btnGoWebDavSettings = view.findViewById(R.id.btn_go_webdav_settings);
        tvSetupMsg = view.findViewById(R.id.tv_webdav_setup_msg);

        miniPlayer = view.findViewById(R.id.mini_player);
        miniSongTitle = view.findViewById(R.id.mini_song_title);
        miniSongArtist = view.findViewById(R.id.mini_song_artist);
        miniPlayPause = view.findViewById(R.id.mini_play_pause);
        miniCover = view.findViewById(R.id.mini_cover);

        miniCover.setClipToOutline(true);
        miniCover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                int size = Math.min(v.getWidth(), v.getHeight());
                if (size <= 0) size = 42;
                outline.setRoundRect(0, 0, size, size, size / 2f);
            }
        });
        coverRotationHelper.attach(miniCover);

        // 关闭按钮
        btnClose = view.findViewById(R.id.close_button);
        btnClose.setOnClickListener(v -> {
            ctx.stopService(new Intent(ctx, MusicPlayerService.class));
            requireActivity().finishAffinity();
            System.exit(0);
        });

        // 右上角按钮：进入设置页
        btnHelp = view.findViewById(R.id.help_button);
        btnHelp.setOnClickListener(v -> {
            ((HostActivity) requireActivity()).navigateTo(new SettingsFragment(), true);
        });

        // Tab
        tabLocal = view.findViewById(R.id.tab_local);
        tabCloud = view.findViewById(R.id.tab_cloud);
        tabBili = view.findViewById(R.id.tab_bili);
        tabFavorite = view.findViewById(R.id.tab_favorite);
        tabLocalTop = view.findViewById(R.id.tab_local_top);
        tabLocalTitle = view.findViewById(R.id.tab_local_title);
        tabCloudTop = view.findViewById(R.id.tab_cloud_top);
        tabCloudTitle = view.findViewById(R.id.tab_cloud_title);
        tabBiliTop = view.findViewById(R.id.tab_bili_top);
        tabBiliTitle = view.findViewById(R.id.tab_bili_title);
        tabFavoriteTop = view.findViewById(R.id.tab_favorite_top);
        tabFavoriteTitle = view.findViewById(R.id.tab_favorite_title);

        webDavConfig = new WebDavConfig(ctx);

        // 收藏列表 RecyclerView
        rvList.setLayoutManager(new LinearLayoutManager(ctx));
        songAdapter = new SongAdapter(ctx);
        songAdapter.setOnSongClickListener(this::onFavoriteSongClick);
        rvList.setAdapter(songAdapter);

        // 浏览 RecyclerView（复用实例：从播放页返回时保留已加载items与封面，避免重建闪烁）
        if (browseAdapter == null) {
            browseAdapter = new BrowseAdapter();
        }
        int spanCount = calcSpanCount();
        rvBrowse.setLayoutManager(new GridLayoutManager(ctx, spanCount));
        rvBrowse.setAdapter(browseAdapter);

        browseAdapter.setOnItemClickListener((item, position) -> {
            if (item.isDirectory) {
                if (currentTab == 0) {
                    navigateLocalTo(item.path);
                } else if (currentTab == 1) {
                    navigateCloudTo(item.url);
                } else if (currentTab == 2) {
                    navigateBiliTo(item.url);
                }
            } else {
                playFromBrowse(item);
            }
        });

        // 监听窗口尺寸变化
        view.addOnLayoutChangeListener(
                (View v, int left, int top, int right, int bottom,
                 int oldLeft, int oldTop, int oldRight, int oldBottom) -> {
                    if (isActivityGone()) return;
                    int newWidth = right - left;
                    int newHeight = bottom - top;
                    int oldWidth = oldRight - oldLeft;
                    int oldHeight = oldBottom - oldTop;
                    if (newWidth > 0 && (oldWidth <= 0 || newWidth != oldWidth || newHeight != oldHeight)) {
                        if (rvBrowse != null) {
                            rvBrowse.post(() -> {
                                if (isActivityGone()) return;
                                applyLandscapeProportionalLayout();
                                int newSpan = calcSpanCount();
                                GridLayoutManager lm = (GridLayoutManager) rvBrowse.getLayoutManager();
                                if (lm == null || lm.getSpanCount() != newSpan) {
                                    rvBrowse.setLayoutManager(new GridLayoutManager(ctx, newSpan));
                                    if (browseAdapter != null) {
                                        browseAdapter.notifyDataSetChanged();
                                    }
                                }
                            });
                        }
                    }
                });

        updateThemeUI();

        // 春意盎然风格：如果有当前播放歌曲，提取主色
        checkDynamicBgStyle();
        if (dynamicBgEnabled) {
            view.post(() -> {
                if (!isActivityGone() && bound && playerBinder != null) {
                    Song currentSong = playerBinder.getCurrentSong();
                    if (currentSong != null && currentSong.title != null) {
                        extractAndApplyMagicBgColor(currentSong.title, currentSong.artist);
                    }
                }
            });
        }

        // 首次进入时如果横屏，立即应用比例布局
        view.post(() -> {
            if (!isActivityGone()) {
                applyLandscapeProportionalLayout();
            }
        });

        rootLayout.setVisibility(View.INVISIBLE);

        // Tab 切换
        tabLocal.setOnClickListener(v -> switchTab(0));
        tabCloud.setOnClickListener(v -> switchTab(1));
        tabBili.setOnClickListener(v -> switchTab(2));
        tabFavorite.setOnClickListener(v -> switchTab(3));

        // 返回按钮
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
            ((HostActivity) requireActivity()).navigateTo(new WebDavSettingsFragment(), true);
        });

        // B站设置按钮
        btnBiliSettings.setOnClickListener(v -> {
            ((HostActivity) requireActivity()).navigateTo(new BiliSettingsFragment(), true);
        });

        // 去配置按钮
        btnGoWebDavSettings.setOnClickListener(v -> {
            if (currentTab == 2) {
                ((HostActivity) requireActivity()).navigateTo(new BiliSettingsFragment(), true);
            } else {
                ((HostActivity) requireActivity()).navigateTo(new WebDavSettingsFragment(), true);
            }
        });

        // 搜索功能
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (currentTab == 3) {
                    filterFavoriteBrowseItems(s.toString());
                } else {
                    songAdapter.filter(s.toString());
                }
                updateCountText();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        checkPermissionAndScan();

        // 初始 tab（从 HostActivity pendingTab 读取，或从 last_played 恢复）
        int initialTab = 0;
        if (getActivity() instanceof HostActivity) {
            HostActivity host = (HostActivity) getActivity();
            if (host.pendingTab >= 0) {
                initialTab = host.pendingTab;
                host.pendingTab = -1;
            } else if (viewCreatedOnce) {
                // popBackStack 返回（同实例重建视图）：恢复离开时的 tab，不读 last_played
                initialTab = currentTab;
            } else {
                // 悬浮模式返回时 new MainFragment()，从 last_played 恢复 tab
                String savedMode = ctx.getSharedPreferences("last_played", Context.MODE_PRIVATE)
                        .getString("playlist_mode", "all");
                if ("bili".equals(savedMode)) initialTab = 2;
                else if ("webdav".equals(savedMode)) initialTab = 1;
            }
        }
        viewCreatedOnce = true;
        switchTab(initialTab);

        // 点击 mini 播放条
        miniPlayer.setOnClickListener(v -> openPlayerFromMini());

        // 播放/暂停
        miniPlayPause.setOnClickListener(v -> {
            if (bound && playerBinder != null) {
                playerBinder.togglePlayPause();
            }
        });

        // 绑定播放服务
        Intent serviceIntent = new Intent(ctx, MusicPlayerService.class);
        ctx.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 注册播放状态广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction(MusicPlayerService.ACTION_SONG_CHANGED);
        filter.addAction(MusicPlayerService.ACTION_PLAY_STATE_CHANGED);
        filter.addAction(MusicPlayerService.ACTION_THEME_CHANGED);
        CompatUtil.safeRegisterReceiver(ctx, playStateReceiver, filter);

        // 注册手动扫描完成广播接收器
        IntentFilter scanFilter = new IntentFilter(MusicScanner.ACTION_SCAN_COMPLETE);
        CompatUtil.safeRegisterReceiver(ctx, scanCompleteReceiver, scanFilter);

        // 注册存储挂载/卸载广播接收器
        storageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
                    if (!firstMountReceived) {
                        firstMountReceived = true;
                        return;
                    }
                    isScanning = false;
                    scanDebounceHandler.removeCallbacks(scanDebounceRunnable);
                    scanDebounceHandler.postDelayed(() -> {
                        MusicScanner.clearCache(context);
                        scanMusic(false);
                    }, 2000);
                } else if (Intent.ACTION_MEDIA_EJECT.equals(action) ||
                           Intent.ACTION_MEDIA_REMOVED.equals(action) ||
                           Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)) {
                    scanDebounceHandler.removeCallbacks(scanDebounceRunnable);
                    scanDebounceHandler.postDelayed(() -> {
                        MusicScanner.clearCache(context);
                        scanMusic(false);
                    }, 1000);
                }
            }
        };
        IntentFilter storageFilter = new IntentFilter();
        storageFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        storageFilter.addAction(Intent.ACTION_MEDIA_EJECT);
        storageFilter.addAction(Intent.ACTION_MEDIA_REMOVED);
        storageFilter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        storageFilter.addDataScheme("file");
        CompatUtil.safeRegisterReceiver(ctx, storageReceiver, storageFilter);

        // 注册 MediaStore ContentObserver（部分车机ROM的MediaStore Provider不可用，try-catch防护）
        mediaStoreObserver = new android.database.ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                if (System.currentTimeMillis() - MusicScanner.lastMediaScanTime < SELF_SCAN_IGNORE_MS) {
                    return;
                }
                scanDebounceHandler.removeCallbacks(scanDebounceRunnable);
                scanDebounceHandler.postDelayed(scanDebounceRunnable, SCAN_DEBOUNCE_MS);
            }
        };
        try {
            ctx.getContentResolver().registerContentObserver(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver);
        } catch (SecurityException e) {
            Log.w(TAG, "MediaStore ContentObserver 注册失败，自动扫描不可用: " + e.getMessage());
            mediaStoreObserver = null;
        }

        // 检查更新
        UpdateHelper.getInstance(ctx).checkOnLaunch(requireActivity());

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        // 处理来自 HostActivity 的新 tab 请求
        if (getActivity() instanceof HostActivity) {
            HostActivity host = (HostActivity) getActivity();
            if (host.pendingTab >= 0) {
                switchTab(host.pendingTab);
                host.pendingTab = -1;
            }
        }

        rootLayout.setVisibility(View.VISIBLE);
        boolean savedNight = themePrefs.getBoolean("isNight", true);
        if (savedNight != isNightMode) {
            isNightMode = savedNight;
            updateThemeUI();
        }

        // 从播放页返回时，根据上次播放来源恢复 tab
        // 不依赖 returningFromPlayer 实例变量（replace 会销毁重建 MainFragment 导致丢失）
        // 但从设置页返回时（popBackStack，同一实例）不应恢复 tab，否则会把当前 tab 覆盖成上次播放来源
        boolean tabChanged = false;
        if (returningFromPlayer) {
            SharedPreferences playPrefs = requireContext().getSharedPreferences("last_played", Context.MODE_PRIVATE);
            String savedPlaylistMode = playPrefs.getString("playlist_mode", "all");
            if ("bili".equals(savedPlaylistMode) && currentTab != 2) {
                currentTab = 2;
                String biliNavUrl = playPrefs.getString("bili_nav_url", "");
                if (!biliNavUrl.isEmpty()) {
                    biliCurrentUrl = biliNavUrl;
                }
                tabChanged = true;
            } else if ("webdav".equals(savedPlaylistMode) && currentTab != 1) {
                currentTab = 1;
                tabChanged = true;
            }
        }
        returningFromPlayer = false;

        updateTabUI();
        refreshFavorites();
        webDavConfig = new WebDavConfig(requireContext());
        // 仅在 tab 实际变化时才重新加载内容，避免从播放页返回时 WebDAV 区域闪烁
        if (tabChanged) {
            loadCurrentTabContent();
        }
        updateMiniPlayerFromService();

        // 存储\u5377快照比对：检测U盘变化
        checkStorageChanged();

        // \u7f13\u5b58\u65b0\u9c9c\u5ea6\u68c0\u67e5：手动扫描后返回首页时补偿刷新
        checkCacheRefreshed();

        // Android 11+: 从系统授权页返回后，如果刚获得权限则重新扫描
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && pendingScanAfterStoragePermission
                && Environment.isExternalStorageManager()) {
            pendingScanAfterStoragePermission = false;
            clearCacheAndScan();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mRootView != null) {
            mRootView.post(() -> {
                if (isActivityGone()) return;
                applyLandscapeProportionalLayout();
                if (rvBrowse != null) rvBrowse.requestLayout();
                if (browseAdapter != null) {
                    browseAdapter.resetItemFontSize();
                    browseAdapter.notifyDataSetChanged();
                }
            });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (coverRotationHelper != null) coverRotationHelper.release();
        if (tabBreathAnimator != null) { tabBreathAnimator.cancel(); tabBreathAnimator = null; }
        if (shimmerAnimator != null) { shimmerAnimator.cancel(); shimmerAnimator = null; }
        try {
            if (getContext() != null) getContext().unregisterReceiver(playStateReceiver);
        } catch (Exception ignored) {}
        try {
            if (getContext() != null) getContext().unregisterReceiver(scanCompleteReceiver);
        } catch (Exception ignored) {}
        if (storageReceiver != null) {
            try {
                if (getContext() != null) getContext().unregisterReceiver(storageReceiver);
            } catch (Exception ignored) {}
            storageReceiver = null;
        }
        if (mediaStoreObserver != null && getContext() != null) {
            getContext().getContentResolver().unregisterContentObserver(mediaStoreObserver);
        }
        scanDebounceHandler.removeCallbacks(scanDebounceRunnable);
        if (bound && getContext() != null) {
            try {
                getContext().unbindService(serviceConnection);
            } catch (Exception ignored) {}
            bound = false;
        }
        mRootView = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ==================== 辅助方法 ====================

    private boolean isActivityGone() {
        Activity a = getActivity();
        return a == null || a.isFinishing() || a.isDestroyed();
    }

    private void runOnUi(Runnable r) {
        Activity a = getActivity();
        if (a != null) a.runOnUiThread(r);
    }

    // ==================== 导航到播放页 ====================

    private void navigateToPlayer(Bundle args) {
        if (getActivity() == null || !isAdded() || mRootView == null) return;
        // 任何主动进入播放页（点歌/mini条/浏览/自动恢复）后，返回列表页时都不应再自动跳回播放页
        hasAutoResumed = true;
        mRootView.post(() -> {
            // post 执行时 Fragment/Activity 可能已被销毁（乐酷分屏切换、崩溃重启竞态）
            if (getActivity() == null || !isAdded() || isActivityGone()) return;
            PlayerFragment fragment = new PlayerFragment();
            fragment.setArguments(args);
            ((HostActivity) requireActivity()).navigateTo(fragment, true);
        });
    }

    private Bundle buildPlayerArgs(Song song, int position, String playlistMode) {
        Bundle args = new Bundle();
        Intent temp = new Intent();
        song.toIntent(temp);
        if (temp.getExtras() != null) args.putAll(temp.getExtras());
        args.putInt("position", position);
        args.putString("playlist_mode", playlistMode);
        args.putBoolean("resume_play", true);
        if ("webdav".equals(playlistMode) || "bili".equals(playlistMode)) {
            args.putBoolean("from_webdav", true);
            args.putInt("song_index", position);
        }
        return args;
    }

    // ==================== Tab 切换 ====================

    private void updateTabUI() {
        int mode = currentTab;
        boolean useDynamic = dynamicBgEnabled;
        // Tab 颜色：白天用查表深色列（白底上要够深），夜间用浅色列（深底上要够亮）
        int activeColor, inactiveColor;
        if (useDynamic && ThemeColors.getRawDominantColor() != -1) {
            int tint = com.jingxin.jingxinmusic.util.MagicColor.mapColor(
                    ThemeColors.getRawDominantColor(), isNightMode);
            activeColor = tint;
            inactiveColor = com.jingxin.jingxinmusic.util.ColorUtil.adjustAlpha(tint, 0.5f);
        } else if (useDynamic) {
            activeColor = isNightMode ? 0xFFFFFFFF : 0xFF222222;
            inactiveColor = isNightMode ? 0x66FFFFFF : 0x88222222;
        } else {
            activeColor = isNightMode ? ThemeColors.nightTabActive() : ThemeColors.dayTabActive();
            inactiveColor = isNightMode ? ThemeColors.nightTabInactive() : ThemeColors.dayTabInactive();
        }

        // 双行 Tab 文字颜色
        setTabColor(tabLocalTop, tabLocalTitle, mode == 0, activeColor, inactiveColor);
        setTabColor(tabCloudTop, tabCloudTitle, mode == 1, activeColor, inactiveColor);
        setTabColor(tabBiliTop, tabBiliTitle, mode == 2, activeColor, inactiveColor);
        setTabColor(tabFavoriteTop, tabFavoriteTitle, mode == 3, activeColor, inactiveColor);

        // 底部指示线 + 滑动指示器（与 Tab 文字同色）
        int indicatorColor = activeColor;
        int bottomLineColor = useDynamic
                ? (isNightMode ? 0x22FFFFFF : 0x22222222)
                : (isNightMode ? ThemeColors.nightDivider() : ThemeColors.dayDivider());
        if (tabBottomLine != null) tabBottomLine.setBackgroundColor(bottomLineColor);
        if (tabIndicator != null) {
            tabIndicator.setBackgroundColor(indicatorColor);
            // 滑动到当前 Tab 位置
            tabIndicator.post(() -> {
                View tabView = getTabView(mode);
                if (tabView != null && tabIndicator.getWidth() > 0) {
                    float tabWidth = tabView.getWidth();
                    float indicatorWidth = tabIndicator.getWidth();
                    float targetX = tabView.getX() + (tabWidth - indicatorWidth) / 2f;
                    tabIndicator.animate().translationX(targetX).setDuration(200).start();
                }
            });
        }
        // 旧指示器（隐藏占位）
        indicatorLocal.setBackgroundColor(0x00000000);
        indicatorCloud.setBackgroundColor(0x00000000);
        indicatorBili.setBackgroundColor(0x00000000);
        indicatorFavorite.setBackgroundColor(0x00000000);

        if (mode == 3) {
            browseArea.setVisibility(View.GONE);
            favoriteArea.setVisibility(View.VISIBLE);
        } else {
            browseArea.setVisibility(View.VISIBLE);
            favoriteArea.setVisibility(View.GONE);
        }
    }

    private void setTabColor(TextView top, TextView title, boolean selected, int activeColor, int inactiveColor) {
        int color = selected ? activeColor : inactiveColor;
        if (top != null) top.setTextColor(color);
        if (title != null) {
            title.setTextColor(color);
            title.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private View getTabView(int index) {
        if (index == 0) return tabLocal;
        if (index == 1) return tabCloud;
        if (index == 2) return tabBili;
        return tabFavorite;
    }

    private void switchTab(int mode) {
        currentTab = mode;
        updateTabUI();
        loadCurrentTabContent();
        etSearch.setText("");
        updateCountText();
    }

    private void loadCurrentTabContent() {
        if (currentTab == 0) {
            if (!allSongs.isEmpty()) {
                loadLocalItems();
            }
        } else if (currentTab == 1) {
            loadWebDavItems();
        } else if (currentTab == 2) {
            loadBiliItems();
        } else {
            int spanCount = calcSpanCount();
            rvList.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));
            rvList.setAdapter(browseAdapter);
            refreshFavorites();
            loadFavoriteBrowseItems();
        }
    }

    // ==================== 本地浏览 ====================

    private void loadFavoriteBrowseItems() {
        List<BrowseItem> items = new ArrayList<>();
        for (Song song : songAdapter.getFavoriteSongs()) {
            items.add(BrowseItem.localSong(song));
        }
        browseAdapter.setItems(items);
        lastLoadedBiliUrl = null;
    }

    private void filterFavoriteBrowseItems(String query) {
        String q = query != null ? query.trim().toLowerCase() : "";
        List<BrowseItem> items = new ArrayList<>();
        for (Song song : songAdapter.getFavoriteSongs()) {
            if (q.isEmpty() ||
                (song.title != null && song.title.toLowerCase().contains(q)) ||
                (song.artist != null && song.artist.toLowerCase().contains(q))) {
                items.add(BrowseItem.localSong(song));
            }
        }
        browseAdapter.setItems(items);
        lastLoadedBiliUrl = null;
    }

    private void loadLocalItems() {
        navigateLocalTo(localCurrentDir);
    }

    private void navigateLocalTo(String dirPath) {
        if (dirPath != null && localCurrentDir != null && !dirPath.equals(localCurrentDir)) {
            localNavStack.push(localCurrentDir);
        }
        localCurrentDir = dirPath;

        List<BrowseItem> items = LocalDirectoryScanner.buildLevel(allSongs, dirPath);
        browseAdapter.setItems(items);
        lastLoadedBiliUrl = null;

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
        localCurrentDir = null;
        navigateLocalTo(parentDir);
    }

    // ==================== WebDAV 浏览 ====================

    private void loadWebDavItems() {
        webDavConfig = new WebDavConfig(requireContext());

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

        restoreWebDavNavState();
        String url = cloudCurrentUrl != null ? cloudCurrentUrl : webDavConfig.getMusicUrl();
        navigateCloudTo(url);
    }

    private void navigateCloudTo(String url) {
        if (url != null && cloudCurrentUrl != null && !url.equals(cloudCurrentUrl)) {
            cloudNavStack.push(cloudCurrentUrl);
        }
        cloudCurrentUrl = url;
        saveWebDavNavState();

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
            runOnUi(() -> {
                if (isActivityGone()) return;
                browseLoading.setVisibility(View.GONE);
                rvBrowse.setVisibility(View.VISIBLE);
                browseAdapter.setItems(browseItems);
                lastLoadedBiliUrl = null;
                updateCountText();
            });
        });
    }

    private void navigateCloudBack() {
        if (cloudNavStack.isEmpty()) {
            cloudCurrentUrl = null;
            saveWebDavNavState();
            loadWebDavItems();
            return;
        }
        String parentUrl = cloudNavStack.pop();
        cloudCurrentUrl = null;
        navigateCloudTo(parentUrl);
    }

    // ==================== WebDAV 导航状态持久化 ====================

    private void saveWebDavNavState() {
        saveNavState("webdav_nav", cloudCurrentUrl, cloudNavStack, "保存WebDAV导航状态失败");
    }

    private void restoreWebDavNavState() {
        if (cloudCurrentUrl != null || !cloudNavStack.isEmpty()) return;
        String[] result = restoreNavState("webdav_nav", cloudNavStack, "恢复WebDAV导航状态失败");
        if (result != null) cloudCurrentUrl = result[0];
    }

    // ==================== B站 浏览 ====================

    private void loadBiliItems() {
        BiliConfig biliConfig = new BiliConfig(requireContext());
        if (!biliConfig.isConfigured()) {
            rvBrowse.setVisibility(View.GONE);
            browseLoading.setVisibility(View.GONE);
            pathBar.setVisibility(View.GONE);
            webdavSetupArea.setVisibility(View.VISIBLE);
            btnGoWebDavSettings.setVisibility(View.VISIBLE);
            tvSetupMsg.setText("配置B站以浏览收藏音乐");
            return;
        }

        webdavSetupArea.setVisibility(View.GONE);
        restoreBiliNavState();
        navigateBiliTo(biliCurrentUrl);
    }

    private void navigateBiliTo(String url) {
        // 从播放页返回（popBackStack 同实例重建视图）或重复进入且内容未变时：
        // 直接复用已加载的 items 显示，避免整块列表 GONE→loading→重建造成的封面闪烁
        if (lastLoadedBiliUrl != null && lastLoadedBiliUrl.equals(url)
                && browseAdapter.getItems() != null && !browseAdapter.getItems().isEmpty()) {
            pathBar.setVisibility(View.VISIBLE);
            btnWebDavSettings.setVisibility(View.GONE);
            btnBiliSettings.setVisibility(View.VISIBLE);
            if (url == null) {
                tvBrowsePath.setText("/ B站收藏夹");
            } else {
                tvBrowsePath.setText("/ " + extractBiliDisplayPath(url));
            }
            webdavSetupArea.setVisibility(View.GONE);
            browseLoading.setVisibility(View.GONE);
            rvBrowse.setVisibility(View.VISIBLE);
            return;
        }
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

        BiliConfig biliConfig = new BiliConfig(requireContext());

        executor.execute(() -> {
            List<BrowseItem> browseItems = new ArrayList<>();

            if (url == null) {
                List<BiliApi.FavoriteFolder> folders = BiliApi.getFavoriteFolders(biliConfig);
                for (BiliApi.FavoriteFolder ff : folders) {
                    browseItems.add(BrowseItem.biliFolder(ff.title, ff.id, ff.mediaCount));
                }
            } else if (url.startsWith("bili://folder/")) {
                long folderId = 0;
                try {
                    folderId = Long.parseLong(url.substring("bili://folder/".length()));
                } catch (NumberFormatException ignored) {}

                if (folderId > 0) {
                    List<BiliApi.FavoriteItem> items = BiliApi.getFavoriteItems(folderId, biliConfig);
                    for (BiliApi.FavoriteItem fi : items) {
                        if (fi.pageCount > 1) {
                            browseItems.add(BrowseItem.biliVideo(
                                    fi.bvid, fi.title, fi.upperName,
                                    fi.cover, fi.pageCount, fi.duration));
                        } else {
                            Song song = Song.fromBili(fi.bvid, fi.title, fi.upperName,
                                    fi.duration, fi.cover);
                            browseItems.add(BrowseItem.biliSong(song));
                        }
                    }
                }
            } else if (url.startsWith("bili://video/")) {
                String bvid = url.substring("bili://video/".length());
                List<BiliApi.VideoPage> pages = BiliApi.getVideoPages(bvid, biliConfig);

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
            runOnUi(() -> {
                if (isActivityGone()) return;
                browseLoading.setVisibility(View.GONE);
                rvBrowse.setVisibility(View.VISIBLE);
                browseAdapter.setItems(finalItems);
                lastLoadedBiliUrl = url;
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

    private void saveBiliNavState() {
        saveNavState("bili_nav", biliCurrentUrl, biliNavStack, "保存B站导航状态失败");
    }

    private void restoreBiliNavState() {
        if (biliCurrentUrl != null || !biliNavStack.isEmpty()) return;
        String[] result = restoreNavState("bili_nav", biliNavStack, "恢复B站导航状态失败");
        if (result != null) biliCurrentUrl = result[0];
    }

    /**
     * 通用导航状态持久化（WebDAV/B站共用）
     */
    private void saveNavState(String prefsName, String currentUrl, Stack<String> stack, String errTag) {
        try {
            org.json.JSONArray stackArr = new org.json.JSONArray();
            for (String s : stack) {
                stackArr.put(s);
            }
            requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .edit()
                    .putString("current_url", currentUrl)
                    .putString("nav_stack", stackArr.toString())
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, errTag + ": " + e.getMessage());
        }
    }

    /**
     * 通用导航状态恢复（WebDAV/B站共用）
     * @return [0]=currentUrl, 或 null 表示无保存状态
     */
    private String[] restoreNavState(String prefsName, Stack<String> stack, String errTag) {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            String currentUrl = prefs.getString("current_url", null);
            String stackJson = prefs.getString("nav_stack", null);
            if (stackJson != null) {
                org.json.JSONArray arr = new org.json.JSONArray(stackJson);
                for (int i = 0; i < arr.length(); i++) {
                    stack.push(arr.getString(i));
                }
            }
            return new String[]{currentUrl};
        } catch (Exception e) {
            Log.e(TAG, errTag + ": " + e.getMessage());
            return null;
        }
    }

    private String extractBiliDisplayPath(String url) {
        if (url == null) return "B站收藏夹";
        if (url.startsWith("bili://folder/")) return "收藏夹";
        if (url.startsWith("bili://video/")) return "歌单";
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

    // ==================== 从浏览项播放 ====================

    private void playFromBrowse(BrowseItem clickedItem) {
        List<BrowseItem> items = browseAdapter.getItems();
        List<Song> playlist = new ArrayList<>();
        int playIndex = 0;

        if (clickedItem.source == Song.SOURCE_LOCAL) {
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

            Bundle args = new Bundle();
            Intent temp = new Intent();
            playlist.get(playIndex).toIntent(temp);
            if (temp.getExtras() != null) args.putAll(temp.getExtras());
            args.putInt("position", playIndex);

            if (currentTab == 3) {
                args.putString("playlist_mode", "favorites");
            } else {
                args.putString("playlist_mode", "folder");
                args.putInt("folder_size", playlist.size());
                ArrayList<String> paths = new ArrayList<>();
                for (Song s : playlist) paths.add(s.filePath);
                args.putStringArrayList("folder_song_paths", paths);
            }
            navigateToPlayer(args);

        } else {
            long idBase = 1000000;
            for (int i = 0; i < items.size(); i++) {
                BrowseItem item = items.get(i);
                if (!item.isDirectory) {
                    Song song;
                    if (item.song != null) {
                        song = item.song;
                    } else if (item.source == Song.SOURCE_BILI) {
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

            saveWebDavPlaylist(playlist, playIndex);

            String playlistMode = "webdav";
            if (clickedItem.source == Song.SOURCE_BILI) {
                playlistMode = "bili";
            }

            Bundle args = new Bundle();
            Intent temp = new Intent();
            playlist.get(playIndex).toIntent(temp);
            if (temp.getExtras() != null) args.putAll(temp.getExtras());
            args.putInt("position", playIndex);
            args.putString("playlist_mode", playlistMode);
            args.putBoolean("from_webdav", true);
            args.putInt("webdav_playlist_size", playlist.size());
            if (clickedItem.source == Song.SOURCE_BILI) {
                args.putString("bili_nav_url", biliCurrentUrl != null ? biliCurrentUrl : "");
            }
            navigateToPlayer(args);
        }
    }

    private void saveWebDavPlaylist(List<Song> playlist, int playIndex) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Song song : playlist) {
                arr.put(song.toJson());
            }
            requireContext().getSharedPreferences("webdav_playlist", Context.MODE_PRIVATE)
                    .edit()
                    .putString("playlist", arr.toString())
                    .putInt("play_index", playIndex)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "保存播放列表失败: " + e.getMessage());
        }
    }

    // ==================== 收藏歌曲点击 ====================

    private void onFavoriteSongClick(Song song) {
        Bundle args = new Bundle();
        Intent temp = new Intent();
        song.toIntent(temp);
        if (temp.getExtras() != null) args.putAll(temp.getExtras());
        args.putInt("position", songAdapter.getSongPositionInFavorites(song));
        args.putString("playlist_mode", "favorites");
        navigateToPlayer(args);
    }

    // ==================== 数量文本 ====================

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
            tvSongCount.setText(songAdapter.getFavoriteSongs().size() + " 首收藏");
        }
    }

    // ==================== 主题 ====================

    private void updateThemeUI() {
        checkDynamicBgStyle();
        // 日夜切换时用原始封面主色重新查表（白天浅色/夜间深色）
        int rawDominant = ThemeColors.getRawDominantColor();
        if (rawDominant != -1) {
            int retint = com.jingxin.jingxinmusic.util.MagicColor.mapColor(rawDominant, !isNightMode);
            ThemeColors.setDominantColor(retint);
        }
        // 春意盎然风格：始终启用 DynamicBgView（有主色=渐变，无主色=纯底色）
        boolean useDynamic = dynamicBgEnabled;

        // 动态背景模式：DynamicBgView 可见并渲染，所有上层容器透明
        View contentLayout = mRootView.findViewById(R.id.content_layout);
        if (useDynamic) {
            int color = ThemeColors.getDominantColor();
            dynamicBgView.setVisibility(View.VISIBLE);
            dynamicBgView.setNightMode(isNightMode);
            dynamicBgView.setTintColor(color);  // -1 时只画底色
            rootLayout.setBackgroundColor(0x00000000);
            if (contentLayout != null) contentLayout.setBackgroundColor(0x00000000);
        } else {
            dynamicBgView.setVisibility(View.GONE);
            dynamicBgView.setTintColor(-1);
            if (contentLayout != null) contentLayout.setBackgroundColor(0x00000000);
        }

        if (isNightMode) {
            // 非动态模式设背景，动态模式下全部透明
            titleBar.setBackground(useDynamic ? null : ThemeColors.barGradient(true));
            tabBar.setBackground(useDynamic ? null : ThemeColors.barGradient(true));
            // 动态模式：统一白色文字/图标；非动态模式：用春意盎然主题色
            etSearch.setTextColor(useDynamic ? 0xFFFFFFFF : ThemeColors.nightTextPrimary());
            etSearch.setHintTextColor(useDynamic ? 0x66FFFFFF : ThemeColors.nightTextTertiary());
            tvSongCount.setTextColor(useDynamic ? 0x88FFFFFF : ThemeColors.nightTextTertiary());
            tvLoading.setTextColor(useDynamic ? 0x88FFFFFF : ThemeColors.nightTextTertiary());
            tvCopyright.setTextColor(useDynamic ? 0x44FFFFFF : ThemeColors.nightTextCopyright());
            // 顶部图标颜色同 Tab 大字
            updateTopIconColors();
            browseArea.setBackground(useDynamic ? null : ThemeColors.bgGradient(true));
            pathBar.setBackground(useDynamic ? null : ThemeColors.barGradient(true));
            tvBrowsePath.setTextColor(useDynamic ? 0x99FFFFFF : ThemeColors.nightTextSecondary());
            btnNavigateBack.setColorFilter(useDynamic ? 0x99FFFFFF : ThemeColors.nightTextSecondary());
            btnWebDavSettings.setColorFilter(useDynamic ? 0x99FFFFFF : ThemeColors.nightTextSecondary());
            btnBiliSettings.setColorFilter(useDynamic ? 0x99FFFFFF : ThemeColors.nightTextSecondary());
            btnGoWebDavSettings.setTextColor(useDynamic ? 0xFFFFFFFF : ThemeColors.nightTabActive());
            tvSetupMsg.setTextColor(useDynamic ? 0x99FFFFFF : ThemeColors.nightTextSecondary());
            miniPlayer.setBackground(useDynamic ? null : ThemeColors.miniGradient(true));
            miniSongTitle.setTextColor(useDynamic ? 0xFFFFFFFF : ThemeColors.nightTextPrimary());
            miniSongArtist.setTextColor(useDynamic ? 0x99FFFFFF : ThemeColors.nightTextSecondary());
            miniPlayPause.clearColorFilter();
            // 非动态模式恢复 rootLayout 渐变
            if (!useDynamic) {
                rootLayout.setBackground(ThemeColors.bgGradient(true));
            }
        } else {
            titleBar.setBackground(useDynamic ? null : ThemeColors.barGradient(false));
            tabBar.setBackground(useDynamic ? null : ThemeColors.barGradient(false));
            // 动态模式：统一黑色文字/图标；非动态模式：用春意盎然主题色
            etSearch.setTextColor(useDynamic ? 0xFF222222 : ThemeColors.dayTextPrimary());
            etSearch.setHintTextColor(useDynamic ? 0x66222222 : ThemeColors.dayTextSecondary());
            tvSongCount.setTextColor(useDynamic ? 0x88222222 : ThemeColors.dayTextSecondary());
            tvLoading.setTextColor(useDynamic ? 0x88222222 : ThemeColors.dayTextSecondary());
            tvCopyright.setTextColor(useDynamic ? 0x44222222 : ThemeColors.dayTextCopyright());
            // 顶部图标颜色同 Tab 大字
            updateTopIconColors();
            browseArea.setBackground(useDynamic ? null : ThemeColors.bgGradient(false));
            pathBar.setBackground(useDynamic ? null : ThemeColors.barGradient(false));
            tvBrowsePath.setTextColor(useDynamic ? 0x99222222 : ThemeColors.dayTextSecondary());
            btnNavigateBack.setColorFilter(useDynamic ? 0x99222222 : ThemeColors.dayTextSecondary());
            btnWebDavSettings.setColorFilter(useDynamic ? 0x99222222 : ThemeColors.dayTextSecondary());
            btnBiliSettings.setColorFilter(useDynamic ? 0x99222222 : ThemeColors.dayTextSecondary());
            btnGoWebDavSettings.setTextColor(useDynamic ? 0xFF222222 : ThemeColors.dayTabActive());
            tvSetupMsg.setTextColor(useDynamic ? 0x99222222 : ThemeColors.dayTextSecondary());
            miniPlayer.setBackground(useDynamic ? null : ThemeColors.miniGradient(false));
            miniSongTitle.setTextColor(useDynamic ? 0xFF222222 : ThemeColors.dayTextPrimary());
            miniSongArtist.setTextColor(useDynamic ? 0x99222222 : ThemeColors.dayTextSecondary());
            miniPlayPause.setColorFilter(useDynamic ? 0xFF222222 : ThemeColors.dayTextPrimary(), PorterDuff.Mode.SRC_IN);
            if (!useDynamic) {
                rootLayout.setBackground(ThemeColors.bgGradient(false));
            }
        }
        // Tab 颜色：白天用查表深色列，夜间用浅色列（与背景相反，保证可读性）
        int activeColor, inactiveColor;
        if (useDynamic && ThemeColors.getRawDominantColor() != -1) {
            int tint = com.jingxin.jingxinmusic.util.MagicColor.mapColor(
                    ThemeColors.getRawDominantColor(), isNightMode);
            activeColor = tint;
            inactiveColor = com.jingxin.jingxinmusic.util.ColorUtil.adjustAlpha(tint, 0.5f);
        } else if (useDynamic) {
            activeColor = isNightMode ? 0xFFFFFFFF : 0xFF222222;
            inactiveColor = isNightMode ? 0x66FFFFFF : 0x88222222;
        } else {
            activeColor = isNightMode ? ThemeColors.nightTabActive() : ThemeColors.dayTabActive();
            inactiveColor = isNightMode ? ThemeColors.nightTabInactive() : ThemeColors.dayTabInactive();
        }
        setTabColor(tabLocalTop, tabLocalTitle, currentTab == 0, activeColor, inactiveColor);
        setTabColor(tabCloudTop, tabCloudTitle, currentTab == 1, activeColor, inactiveColor);
        setTabColor(tabBiliTop, tabBiliTitle, currentTab == 2, activeColor, inactiveColor);
        setTabColor(tabFavoriteTop, tabFavoriteTitle, currentTab == 3, activeColor, inactiveColor);
        // 底部指示线 + 滑动指示器（与 Tab 文字同色）
        int indicatorColor = activeColor;
        int bottomLineColor = useDynamic
                ? (isNightMode ? 0x22FFFFFF : 0x22222222)
                : (isNightMode ? ThemeColors.nightDivider() : ThemeColors.dayDivider());
        if (tabBottomLine != null) tabBottomLine.setBackgroundColor(bottomLineColor);
        if (tabIndicator != null) {
            tabIndicator.setBackgroundColor(indicatorColor);
            tabIndicator.post(() -> {
                View tabView = getTabView(currentTab);
                if (tabView != null && tabIndicator.getWidth() > 0) {
                    float tabWidth = tabView.getWidth();
                    float indicatorWidth = tabIndicator.getWidth();
                    float targetX = tabView.getX() + (tabWidth - indicatorWidth) / 2f;
                    tabIndicator.setTranslationX(targetX);
                }
            });
        }
        // 流光线颜色：动态模式有主色时取封面查表色（白天深/夜间浅，同 Tab 方向）；否则白/黑或主题色
        updateShimmerLineColors();
        songAdapter.setNightMode(isNightMode);
        songAdapter.setDynamicBgMode(useDynamic);
        browseAdapter.setNightMode(isNightMode);
        browseAdapter.setDynamicBgMode(useDynamic);
        browseAdapter.notifyDataSetChanged();
        applyThemeToRecyclerViewItems();
    }

    private void applyThemeToRecyclerViewItems() {
        boolean useDynamic = dynamicBgEnabled;
        rvList.post(() -> {
            int childCount = rvList.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = rvList.getChildAt(i);
                if (child.findViewById(R.id.tv_song_title) != null) {
                    TextView tvTitle = child.findViewById(R.id.tv_song_title);
                    TextView tvArtist = child.findViewById(R.id.tv_song_artist);
                    TextView tvDuration = child.findViewById(R.id.tv_song_duration);
                    // 动态模式：列表项透明（让 DynamicBgView 底色透出）；非动态：用主题色
                    child.setBackgroundColor(useDynamic ? 0x00000000 : ThemeColors.themedColor(isNightMode, ThemeColors.dayItemBg(), ThemeColors.nightItemBg()));
                    // 动态模式：白天黑夜间白
                    int titleColor = useDynamic ? (isNightMode ? 0xFFFFFFFF : 0xFF222222) : ThemeColors.themedColor(isNightMode, ThemeColors.dayTextPrimary(), ThemeColors.nightTextPrimary());
                    int subColor = useDynamic ? (isNightMode ? 0x99FFFFFF : 0x99222222) : ThemeColors.themedColor(isNightMode, ThemeColors.dayTextSecondary(), ThemeColors.nightTextSecondary());
                    int durColor = useDynamic ? (isNightMode ? 0x66FFFFFF : 0x66222222) : ThemeColors.themedColor(isNightMode, ThemeColors.dayTextSecondary(), ThemeColors.nightTextTertiary());
                    tvTitle.setTextColor(titleColor);
                    tvArtist.setTextColor(subColor);
                    tvDuration.setTextColor(durColor);
                }
            }
        });
    }

    // ==================== 收藏刷新 ====================

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

    // ==================== 权限与扫描 ====================

    private void checkPermissionAndScan() {
        Context ctx = requireContext();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                checkManageStorageAndScan();
                requestNotificationPermissionIfNeeded();
            } else {
                tvLoading.setVisibility(View.VISIBLE);
                currentPermissionRequest = Manifest.permission.READ_MEDIA_AUDIO;
                permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            List<String> needed = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (android.os.Build.VERSION.SDK_INT <= 28 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (needed.isEmpty()) {
                checkManageStorageAndScan();
                requestNotificationPermissionIfNeeded();
            } else {
                tvLoading.setVisibility(View.VISIBLE);
                multiPermissionLauncher.launch(needed.toArray(new String[0]));
            }
        }
    }

    /**
     * 清缓存 + 重置扫描状态 + 重新扫描
     */
    private void clearCacheAndScan() {
        MusicScanner.clearCache(requireContext());
        isScanning = false;
        scanMusic();
    }

    /**
     * 将扫描结果应用到 UI（4处重复逻辑统一）
     */
    private void applyScanResult(List<Song> songs, Context ctx) {
        allSongs = songs;
        songAdapter.setAllSongs(songs);
        tvLoading.setVisibility(View.GONE);
        browseLoading.setVisibility(View.GONE);
        rootLayout.setVisibility(View.VISIBLE);
        refreshFavorites();
        loadCurrentTabContent();
        updateCountText();
        applyThemeToRecyclerViewItems();
        File cacheFile = new File(ctx.getCacheDir(), "music_cache.json");
        lastShownCacheTime = cacheFile.lastModified();
    }

    /**
     * 跳转到 MANAGE_EXTERNAL_STORAGE 授权页（公共方法）
     */
    private void launchManageStoragePermission(Runnable onUnsupported) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            manageStorageLauncher.launch(intent);
        } catch (Exception e) {
            Log.w(TAG, "MANAGE_APP_ALL_FILES_ACCESS 不支持，尝试通用设置", e);
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                manageStorageLauncher.launch(intent);
            } catch (Exception e2) {
                Log.e(TAG, "无法打开文件访问权限设置页", e2);
                if (onUnsupported != null) onUnsupported.run();
            }
        }
    }

    /**
     * Android 11+: 检查 MANAGE_EXTERNAL_STORAGE（所有文件访问权限）
     */
    private void checkManageStorageAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                scanMusic();
            } else {
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle("需要文件访问权限")
                        .setMessage("扫描U盘音乐需要「所有文件访问权限」，否则U盘歌曲将无法显示。点击去授权跳转系统设置。")
                        .setPositiveButton("去授权", (dialog, which) -> {
                            pendingScanAfterStoragePermission = true;
                            launchManageStoragePermission(() -> {
                                pendingScanAfterStoragePermission = false;
                                scanMusic();
                            });
                        })
                        .setNegativeButton("跳过", (dialog, which) -> {
                            pendingScanAfterStoragePermission = false;
                            scanMusic();
                        })
                        .setCancelable(false)
                        .show();
            }
        } else {
            scanMusic();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                currentPermissionRequest = Manifest.permission.POST_NOTIFICATIONS;
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        requestOverlayPermissionIfNeeded();
    }

    private void showPermissionDeniedDialog(String title, String message, Runnable retryAction) {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("去设置", (dialog, which) -> {
                    if (retryAction != null) retryAction.run();
                })
                .setNegativeButton("暂不", null)
                .show();
    }

    private void requestOverlayPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(requireContext())) {
                SharedPreferences prefs = requireContext().getSharedPreferences("overlay_permission", Context.MODE_PRIVATE);
                if (prefs.getBoolean("never_ask", false)) return;

                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle("悬浮窗权限")
                        .setMessage("后台播放时需要悬浮窗权限来显示迷你播放窗口。是否前往设置开启？")
                        .setPositiveButton("去设置", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + requireContext().getPackageName()));
                                startActivity(intent);
                            } catch (Exception e) {
                                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
                                startActivity(intent);
                            }
                        })
                        .setNegativeButton("暂不", null)
                        .setNeutralButton("不再提示", (dialog, which) -> {
                            prefs.edit().putBoolean("never_ask", true).apply();
                        })
                        .show();
            }
        }
    }

    private void showNotificationPermissionDeniedDialog() {
        showPermissionDeniedDialog("通知权限",
                "播放控制通知需要通知权限才能正常显示。是否前往设置开启？",
                () -> {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
                        startActivity(intent);
                    }
                });
    }

    private void showWriteStoragePermissionDeniedDialog() {
        showPermissionDeniedDialog("存储写入权限",
                "歌词导出到公共目录需要写入权限。是否前往设置开启？",
                () -> {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "无法打开应用设置页", e);
                    }
                });
    }

    private void scanMusic() {
        scanMusic(true);
    }

    private void scanMusic(boolean tryAutoResume) {
        synchronized (scanLock) {
            if (isScanning) {
                return;
            }
            isScanning = true;
        }

        Context ctx = requireContext();
        List<Song> cached = MusicScanner.loadCache(ctx);

        if (cached != null && !cached.isEmpty()) {
            applyScanResult(cached, ctx);
            if (tryAutoResume) autoResumeLastPlayed(cached);
            // 缓存有效，无需后台全量扫描，避免返回列表页时重复刷新
            synchronized (scanLock) {
                isScanning = false;
            }
            return;
        }

        tvLoading.setVisibility(View.VISIBLE);
        tvSongCount.setText("正在扫描音乐...");
        rvBrowse.setVisibility(View.GONE);
        browseLoading.setVisibility(View.VISIBLE);

        final boolean finalTryAutoResume = tryAutoResume;
        executor.execute(() -> {
            if (!isActivityGone()) MusicScanner.triggerMediaScan(ctx);
            List<Song> songs = MusicScanner.scanMusic(ctx);
            runOnUi(() -> {
                if (isActivityGone()) return;
                synchronized (scanLock) {
                    isScanning = false;
                }
                applyScanResult(songs, ctx);
                if (finalTryAutoResume) autoResumeLastPlayed(songs);
            });
        });
    }

    private final Runnable scanDebounceRunnable = () -> {
        scanMusic(false);
    };

    // ==================== 存储卷快照比对 + 缓存新鲜度检查 ====================

    /**
     * 获取当前可移动存储路径集合快照
     */
    /**
     * 获取当前可移动存储路径集合快照（复用 MusicScanner.detectRemovableStorage）
     */
    private Set<String> getStorageSnapshot() {
        Set<String> snapshot = new HashSet<>();
        try {
            java.lang.reflect.Method m = MusicScanner.class.getDeclaredMethod("detectRemovableStorage", Context.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<File> storages = (List<File>) m.invoke(null, requireContext());
            if (storages != null) {
                for (File f : storages) {
                    snapshot.add(f.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "获取存储快照失败: " + e.getMessage());
        }
        return snapshot;
    }

    /**
     * onResume 时比对存储卷快照，检测U盘变化
     */
    private void checkStorageChanged() {
        Set<String> current = getStorageSnapshot();
        if (lastStorageSnapshot != null) {
            if (!current.equals(lastStorageSnapshot)) {
                MusicScanner.clearCache(requireContext());
                isScanning = false;
                scanMusic(false);
            }
        }
        lastStorageSnapshot = current;
    }

    /**
     * onResume 时检查缓存是否在手动扫描后被更新过
     * 若 lastShownCacheTime 之后缓存被更新 → 重新加载
     */
    private void checkCacheRefreshed() {
        File cacheFile = new File(requireContext().getCacheDir(), "music_cache.json");
        if (!cacheFile.exists()) return;
        long currentMod = cacheFile.lastModified();
        if (lastShownCacheTime > 0 && currentMod > lastShownCacheTime) {
            Context ctx = requireContext();
            executor.execute(() -> {
                List<Song> songs = MusicScanner.loadCache(ctx);
                if (songs == null) {
                    songs = MusicScanner.scanMusic(ctx);
                }
                List<Song> finalSongs = songs;
                runOnUi(() -> {
                    if (isActivityGone()) return;
                    applyScanResult(finalSongs, ctx);
                    lastShownCacheTime = currentMod;
                });
            });
        }
    }

    // ==================== 自动恢复上次播放 ====================

    private boolean autoResumeLastPlayed(List<Song> songs) {
        if (hasAutoResumed) {
            return false;
        }

        // 检查是否开启启动直达播放
        if (!SettingsFragment.isAutoResumeEnabled(requireContext())) {
            return false;
        }

        SharedPreferences prefs = requireContext().getSharedPreferences("last_played", Context.MODE_PRIVATE);
        Song resumeSong = Song.fromPrefs(prefs);
        if (resumeSong == null) return false;

        long songId = resumeSong.id;
        int foundPosition = -1;
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).id == songId) {
                foundPosition = i;
                break;
            }
        }
        if (foundPosition < 0) foundPosition = prefs.getInt("position", 0);

        String savedPlaylistMode = prefs.getString("playlist_mode", "all");

        if ("bili".equals(savedPlaylistMode)) {
            currentTab = 2;
        } else if ("webdav".equals(savedPlaylistMode)) {
            currentTab = 1;
        }

        Bundle args = buildPlayerArgs(resumeSong, foundPosition, savedPlaylistMode);

        // 传递上次播放进度，供 PlayerFragment 恢复 seekTo
        long savedPosition = prefs.getLong("play_position", 0L);
        if (savedPosition > 0) {
            args.putLong("resume_position", savedPosition);
        }

        if ("folder".equals(savedPlaylistMode)) {
            java.util.Set<String> pathSet = prefs.getStringSet("folder_song_paths", null);
            if (pathSet != null && !pathSet.isEmpty()) {
                args.putStringArrayList("folder_song_paths", new ArrayList<>(pathSet));
            }
        }

        hasAutoResumed = true;
        navigateToPlayer(args);
        return true;
    }

    // ==================== Mini 播放条 ====================

    private void updateMiniPlayerFromService() {
        if (bound && playerBinder != null) {
            Song currentSong = playerBinder.getCurrentSong();
            if (currentSong != null && currentSong.title != null) {
                miniSongTitle.setText(currentSong.title);
                miniSongArtist.setText(currentSong.artist != null ? currentSong.artist : "");
                miniPlayerWrap.setVisibility(View.VISIBLE);
                if (shimmerAnimator != null && !shimmerAnimator.isRunning()) shimmerAnimator.start();
                miniPlayPause.setImageResource(playerBinder.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
                loadMiniCover(currentSong.title, currentSong.artist);
                if (playerBinder.isPlaying()) {
                    startCoverRotation();
                } else {
                    pauseCoverRotation();
                }
                // 春意盎然：服务连接时提取主色
                // checkDynamicBgStyle 防时序：onServiceConnected 可能早于 onViewCreated 执行，
                // 此时 dynamicBgEnabled 尚未初始化（false），直接判断会漏掉首次取色
                checkDynamicBgStyle();
                if (dynamicBgEnabled) {
                    extractAndApplyMagicBgColor(currentSong.title, currentSong.artist);
                }
            }
        }
    }

    private void openPlayerFromMini() {
        if (bound && playerBinder != null) {
            Song currentSong = playerBinder.getCurrentSong();
            if (currentSong != null && currentSong.title != null && !currentSong.title.isEmpty()) {
                SharedPreferences prefs = requireContext().getSharedPreferences("last_played", Context.MODE_PRIVATE);
                String savedPlaylistMode = prefs.getString("playlist_mode", "all");
                String biliNavUrl = prefs.getString("bili_nav_url", "");

                Bundle args = buildPlayerArgs(currentSong, playerBinder.getCurrentIndex(), savedPlaylistMode);
                args.putString("bili_nav_url", biliNavUrl);

                returningFromPlayer = true;
                navigateToPlayer(args);
            } else if (playerBinder.isPlaying() || playerBinder.getCurrentIndex() >= 0) {
                SharedPreferences prefs = requireContext().getSharedPreferences("last_played", Context.MODE_PRIVATE);
                Song resumeSong = Song.fromPrefs(prefs);
                if (resumeSong != null) {
                    Bundle args = buildPlayerArgs(resumeSong, prefs.getInt("position", 0), prefs.getString("playlist_mode", "all"));
                    args.putString("bili_nav_url", prefs.getString("bili_nav_url", ""));

                    returningFromPlayer = true;
                    navigateToPlayer(args);
                }
            }
        }
    }

    private void startTabBreathAnimation(int activeColor) {
        if (tabBreathAnimator != null) tabBreathAnimator.cancel();
        View activeIndicator = tabIndicator;
        if (activeIndicator == null) return;

        activeIndicator.setBackgroundColor(activeColor);
        tabBreathAnimator = android.animation.ObjectAnimator.ofInt(activeIndicator, "backgroundColor",
                activeColor, com.jingxin.jingxinmusic.util.ColorUtil.adjustAlpha(activeColor, 0.4f), activeColor);
        tabBreathAnimator.setDuration(2500);
        tabBreathAnimator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        tabBreathAnimator.setEvaluator(new android.animation.ArgbEvaluator());
        tabBreathAnimator.start();
    }

    /** 更新顶部左右按钮图标颜色（同 Tab 大字查表色） */
    private void updateTopIconColors() {
        if (btnClose == null || btnHelp == null) return;
        boolean useDynamic = dynamicBgEnabled;
        int color;
        if (useDynamic && ThemeColors.getRawDominantColor() != -1) {
            color = com.jingxin.jingxinmusic.util.MagicColor.mapColor(
                    ThemeColors.getRawDominantColor(), isNightMode);
        } else if (useDynamic) {
            color = isNightMode ? 0xFFFFFFFF : 0xFF222222;
        } else {
            color = isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();
        }
        btnClose.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        btnHelp.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    private android.animation.ObjectAnimator shimmerAnimator;
    /** 更新两条流光线（标题栏+迷你播放栏）颜色：动态模式取封面查表色 */
    private void updateShimmerLineColors() {
        if (titleAccentLine == null || miniShimmerLine == null) return;
        boolean useDynamic = dynamicBgEnabled;
        int brandGreen;
        if (useDynamic && ThemeColors.getRawDominantColor() != -1) {
            brandGreen = com.jingxin.jingxinmusic.util.MagicColor.mapColor(
                    ThemeColors.getRawDominantColor(), isNightMode);
        } else if (useDynamic) {
            brandGreen = isNightMode ? 0xCCFFFFFF : 0xCC222222;
        } else {
            brandGreen = isNightMode ? ThemeColors.nightTabIndicator() : ThemeColors.dayTabIndicator();
        }
        // 标题栏流光线：透明→色→色→透明
        android.graphics.drawable.GradientDrawable accentGradient = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ 0x00000000, brandGreen, brandGreen, 0x00000000 });
        titleAccentLine.setBackground(accentGradient);
        // 迷你播放栏流光线：透明→色→透明
        android.graphics.drawable.GradientDrawable shimmerGradient = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ 0x00000000, brandGreen, 0x00000000 });
        miniShimmerLine.setBackground(shimmerGradient);
        startMiniShimmerAnimation();
    }

    private void startMiniShimmerAnimation() {
        if (miniShimmerLine == null) return;
        if (shimmerAnimator != null) shimmerAnimator.cancel();
        miniShimmerLine.setTranslationX(-miniShimmerLine.getWidth());
        shimmerAnimator = android.animation.ObjectAnimator.ofFloat(miniShimmerLine, "translationX",
                -getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().widthPixels);
        shimmerAnimator.setDuration(3000);
        shimmerAnimator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        shimmerAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        if (miniPlayerWrap != null && miniPlayerWrap.getVisibility() == View.VISIBLE) {
            shimmerAnimator.start();
        }
    }

    private void startCoverRotation() {
        coverRotationHelper.start();
    }

    private void pauseCoverRotation() {
        coverRotationHelper.pause();
    }

    private void loadMiniCover(String title, String artist) {
        if (title == null) return;
        executor.execute(() -> {
            android.graphics.Bitmap coverBitmap = null;
            try {
                File coverDir = com.jingxin.jingxinmusic.util.CoverLoader.getCoverDir(requireContext());
                if (coverDir != null) {
                    String coverName = Song.toFileName(title, artist != null ? artist : "") + ".jpg";
                    File coverFile = new File(coverDir, coverName);
                    if (coverFile.exists() && coverFile.length() > 0) {
                        coverBitmap = BitmapUtil.decodeSampledFromFile(coverFile.getAbsolutePath(), 200, 200);
                    }
                    if (coverBitmap == null) {
                        File[] coverFiles = coverDir.listFiles((dir, name) ->
                                name.startsWith(title) && name.endsWith(".jpg"));
                        if (coverFiles != null && coverFiles.length > 0) {
                            coverBitmap = BitmapUtil.decodeSampledFromFile(coverFiles[0].getAbsolutePath(), 200, 200);
                        }
                    }
                }
            } catch (Exception e) {
            }
            if (coverBitmap != null) {
                int size = Math.min(coverBitmap.getWidth(), coverBitmap.getHeight());
                android.graphics.Bitmap squared = android.graphics.Bitmap.createBitmap(
                        coverBitmap,
                        (coverBitmap.getWidth() - size) / 2,
                        (coverBitmap.getHeight() - size) / 2,
                        size, size);
                android.graphics.Bitmap rounded = getScaledRoundedBitmap(squared, 42);
                runOnUi(() -> {
                    if (!isActivityGone()) miniCover.setImageBitmap(rounded);
                });
            }
        });
    }

    private android.graphics.Bitmap getScaledRoundedBitmap(android.graphics.Bitmap bitmap, int dpSize) {
        float density = getResources().getDisplayMetrics().density;
        int px = (int) (dpSize * density);
        return BitmapUtil.createScaledCircularBitmap(bitmap, px);
    }

    // ==================== 动态主色背景（春意盎然） ====================

    /** 检查当前风格是否启用动态主色背景（仅春意盎然） */
    private void checkDynamicBgStyle() {
        dynamicBgEnabled = (ThemeColors.getStyleIndex() == 0);
    }

    /** 应用动态主色背景到首页 DynamicBgView */
    private void applyDynamicBg() {
        // 异步取色回调可能晚于 onDestroyView 到达，此时 mRootView 已为 null
        if (mRootView == null) return;
        int color = ThemeColors.getDominantColor();
        dynamicBgView.setVisibility(View.VISIBLE);
        dynamicBgView.setNightMode(isNightMode);
        dynamicBgView.setTintColor(color);  // -1 时只画底色
        rootLayout.setBackgroundColor(0x00000000);
        View contentLayout = mRootView.findViewById(R.id.content_layout);
        if (contentLayout != null) contentLayout.setBackgroundColor(0x00000000);
    }

    /**
     * 从封面提取主色并按 QQ Music 管线查表，应用到首页背景
     * （MagicColor.extractDominant 8桶取色 → MagicColor.mapColor 色相查10档表）
     */
    private void extractAndApplyMagicBgColor(String title, String artist) {
        if (title == null) return;
        // 后台线程回调时 Fragment 可能已销毁，先快照 Context 引用
        final android.content.Context ctx = getActivity();
        if (ctx == null) return;
        executor.execute(() -> {
            android.graphics.Bitmap coverBitmap = null;
            try {
                File coverDir = com.jingxin.jingxinmusic.util.CoverLoader.getCoverDir(ctx);
                if (coverDir != null) {
                    String coverName = Song.toFileName(title, artist != null ? artist : "") + ".jpg";
                    File coverFile = new File(coverDir, coverName);
                    if (coverFile.exists() && coverFile.length() > 0) {
                        coverBitmap = BitmapUtil.decodeSampledFromFile(coverFile.getAbsolutePath(), 70, 70);
                    }
                    if (coverBitmap == null) {
                        File[] coverFiles = coverDir.listFiles((dir, name) ->
                                name.startsWith(title) && name.endsWith(".jpg"));
                        if (coverFiles != null && coverFiles.length > 0) {
                            coverBitmap = BitmapUtil.decodeSampledFromFile(coverFiles[0].getAbsolutePath(), 70, 70);
                        }
                    }
                }
            } catch (Exception e) {
            }
            if (coverBitmap == null) {
                // 无封面：清除动态主色，回退纯底色
                ThemeColors.clearDominantColor();
                runOnUi(() -> {
                    if (!isActivityGone() && dynamicBgEnabled) {
                        applyDynamicBg();
                        updateTabUI();
                        updateShimmerLineColors();
                        updateTopIconColors();
                        // 文件夹卡片 6 色渐变依赖主色，重绑刷新
                        if (browseAdapter != null) browseAdapter.notifyDataSetChanged();
                    }
                });
                return;
            }
            // QQ Music 同款：70x70 缩图 → 8桶取主色 → 色相查表（白天浅色/夜间深色）
            int dominant = com.jingxin.jingxinmusic.util.MagicColor.extractDominant(coverBitmap);
            if (dominant != -1) {
                ThemeColors.setRawDominantColor(dominant);
                int tint = com.jingxin.jingxinmusic.util.MagicColor.mapColor(dominant, !isNightMode);
                ThemeColors.setDominantColor(tint);
            } else {
                ThemeColors.setRawDominantColor(-1);
                ThemeColors.setDominantColor(-1);
            }
            runOnUi(() -> {
                if (!isActivityGone() && dynamicBgEnabled) {
                    applyDynamicBg();
                    updateTabUI();
                    updateShimmerLineColors();
                    updateTopIconColors();
                    // 文件夹卡片 6 色渐变依赖主色，重绑刷新
                    if (browseAdapter != null) browseAdapter.notifyDataSetChanged();
                }
            });
        });
    }

    /**
     * 判断是否为横屏模式（基于实际宽高比，悬浮模式下优先使用悬浮区域尺寸）
     */
    private boolean isLandscapeMode() {
        int width = 0, height = 0;
        // 优先使用根视图实际测量尺寸（悬浮模式下 = 悬浮区域尺寸）
        if (mRootView != null && mRootView.getWidth() > 0 && mRootView.getHeight() > 0) {
            width = mRootView.getWidth();
            height = mRootView.getHeight();
        }
        // 回退到悬浮区域尺寸
        if (width <= 0 || height <= 0) {
            int floatW = com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().getFloatWidth();
            int floatH = com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().getFloatHeight();
            if (floatW > 0 && floatH > 0) {
                width = floatW;
                height = floatH;
            }
        }
        // 最后回退到屏幕尺寸
        if (width <= 0 || height <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
            height = getResources().getDisplayMetrics().heightPixels;
        }
        return width > height * 1.1f;
    }

    private int calcSpanCount() {
        int screenWidth, screenHeight;
        if (mRootView != null && mRootView.getWidth() > 0 && mRootView.getHeight() > 0) {
            screenWidth = mRootView.getWidth();
            screenHeight = mRootView.getHeight();
        } else {
            // 悬浮模式下优先取悬浮区域尺寸
            int floatW = com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().getFloatWidth();
            int floatH = com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().getFloatHeight();
            if (floatW > 0 && floatH > 0) {
                screenWidth = floatW;
                screenHeight = floatH;
            } else {
                screenWidth = getResources().getDisplayMetrics().widthPixels;
                screenHeight = getResources().getDisplayMetrics().heightPixels;
            }
        }
        boolean isLandscape = isLandscapeMode();
        int baseSpan = Math.max(3, screenWidth / 360);
        if (!isLandscape) return baseSpan;
        int availableH = (int) (screenHeight * 0.59f);
        int minSpanFor2Rows = (int) Math.ceil(2.0 * screenWidth / Math.max(1, availableH - 60));
        return Math.max(baseSpan, minSpanFor2Rows);
    }

    // ==================== 横屏比例布局 ====================

    private void applyTitleBarTopMargin() {
        if (titleBar != null) {
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) titleBar.getLayoutParams();
            lp.topMargin = systemTopInset;
            titleBar.setLayoutParams(lp);
        }
    }

    private void applyLandscapeProportionalLayout() {
        boolean isLandscape = isLandscapeMode();
        if (mRootView == null) return;
        int height = mRootView.getHeight();
        if (height == 0) {
            // 悬浮模式下根视图未布局时，取悬浮区域高度
            int floatH = com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().getFloatHeight();
            if (floatH > 0) {
                height = floatH;
            } else {
                return;
            }
        }
        float density = getResources().getDisplayMetrics().density;

        if (isLandscape) {
            int titleBarH = Math.max(36, (int) (height * 0.10f));
            if (titleBar != null) {
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) titleBar.getLayoutParams();
                lp.height = titleBarH;
                lp.topMargin = systemTopInset;
                titleBar.setLayoutParams(lp);
                // 顶部图标按标题栏高度等比缩放，下限20dp防极小屏（下限不得反超比例值）
                int iconSize = (int) Math.max(20 * density, titleBarH * 0.72f);
                if (ivAppIcon != null) {
                    android.widget.FrameLayout.LayoutParams lp2 = (android.widget.FrameLayout.LayoutParams) ivAppIcon.getLayoutParams();
                    lp2.width = iconSize; lp2.height = iconSize;
                    ivAppIcon.setLayoutParams(lp2);
                    int appIconPadding = (int)(iconSize * 0.10f);
                    ivAppIcon.setPadding(appIconPadding, appIconPadding, appIconPadding, appIconPadding);
                }
                if (btnClose != null) {
                    android.widget.FrameLayout.LayoutParams lp2 = (android.widget.FrameLayout.LayoutParams) btnClose.getLayoutParams();
                    lp2.width = iconSize; lp2.height = iconSize;
                    lp2.leftMargin = (int) (8 * density);
                    btnClose.setLayoutParams(lp2);
                    btnClose.setPadding((int)(iconSize * 0.17f), (int)(iconSize * 0.17f), (int)(iconSize * 0.17f), (int)(iconSize * 0.17f));
                }
                if (btnHelp != null) {
                    android.widget.FrameLayout.LayoutParams lp2 = (android.widget.FrameLayout.LayoutParams) btnHelp.getLayoutParams();
                    lp2.width = iconSize; lp2.height = iconSize;
                    lp2.rightMargin = (int) (8 * density);
                    btnHelp.setLayoutParams(lp2);
                    btnHelp.setPadding((int)(iconSize * 0.17f), (int)(iconSize * 0.17f), (int)(iconSize * 0.17f), (int)(iconSize * 0.17f));
                }
            }
            int tabBarH = Math.max(28, (int) (height * 0.10f));
            if (tabBar != null) {
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) tabBar.getLayoutParams();
                lp.height = tabBarH;
                tabBar.setLayoutParams(lp);
                float tabTitleSize = Math.max(10f, tabBarH * 0.38f);
                float tabTopSize = Math.max(4f, tabBarH * 0.16f);
                if (tabLocalTitle != null) tabLocalTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTitleSize);
                if (tabCloudTitle != null) tabCloudTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTitleSize);
                if (tabBiliTitle != null) tabBiliTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTitleSize);
                if (tabFavoriteTitle != null) tabFavoriteTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTitleSize);
                if (tabLocalTop != null) tabLocalTop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTopSize);
                if (tabCloudTop != null) tabCloudTop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTopSize);
                if (tabBiliTop != null) tabBiliTop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTopSize);
                if (tabFavoriteTop != null) tabFavoriteTop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTopSize);
            }
            if (tvCopyright != null) {
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) tvCopyright.getLayoutParams();
                lp.topMargin = Math.max(2, (int) (height * 0.005f));
                lp.bottomMargin = Math.max(2, (int) (height * 0.01f));
                tvCopyright.setLayoutParams(lp);
                tvCopyright.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(8f, height * 0.02f));
            }
            int miniH = Math.max(36, (int) (height * 0.12f));
            if (miniPlayerWrap != null) {
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) miniPlayerWrap.getLayoutParams();
                lp.height = miniH;
                miniPlayerWrap.setLayoutParams(lp);
            }
            if (miniPlayer != null) {
                android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) miniPlayer.getLayoutParams();
                lp.height = miniH - 2;
                miniPlayer.setLayoutParams(lp);
            }
            int miniCoverSize = Math.max(24, (int) (miniH * 0.72f));
            if (miniCover != null) {
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) miniCover.getLayoutParams();
                lp.width = miniCoverSize;
                lp.height = miniCoverSize;
                lp.leftMargin = Math.max(4, (int) (miniH * 0.15f));
                lp.rightMargin = Math.max(2, (int) (miniH * 0.1f));
                miniCover.setLayoutParams(lp);
            }
            if (miniPlayPause != null) {
                int btnSize = Math.max(28, (int) (miniH * 0.78f));
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) miniPlayPause.getLayoutParams();
                lp.width = btnSize;
                lp.height = btnSize;
                lp.rightMargin = Math.max(4, (int) (miniH * 0.15f));
                miniPlayPause.setLayoutParams(lp);
            }
            if (miniSongTitle != null) miniSongTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(10f, miniH * 0.24f));
            if (miniSongArtist != null) miniSongArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(8f, miniH * 0.2f));
            int pathBarH = Math.max(28, (int) (height * 0.10f));
            if (pathBar != null) {
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) pathBar.getLayoutParams();
                lp.height = pathBarH;
                pathBar.setLayoutParams(lp);
                int pathIconSize = Math.max(20, (int) (pathBarH * 0.8f));
                if (btnNavigateBack != null) {
                    android.widget.LinearLayout.LayoutParams lp2 = (android.widget.LinearLayout.LayoutParams) btnNavigateBack.getLayoutParams();
                    lp2.width = pathIconSize; lp2.height = pathIconSize;
                    btnNavigateBack.setLayoutParams(lp2);
                }
                if (btnWebDavSettings != null) {
                    android.widget.LinearLayout.LayoutParams lp2 = (android.widget.LinearLayout.LayoutParams) btnWebDavSettings.getLayoutParams();
                    lp2.width = pathIconSize; lp2.height = pathIconSize;
                    btnWebDavSettings.setLayoutParams(lp2);
                }
                if (btnBiliSettings != null) {
                    android.widget.LinearLayout.LayoutParams lp2 = (android.widget.LinearLayout.LayoutParams) btnBiliSettings.getLayoutParams();
                    lp2.width = pathIconSize; lp2.height = pathIconSize;
                    btnBiliSettings.setLayoutParams(lp2);
                }
                if (tvBrowsePath != null) tvBrowsePath.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(8f, pathBarH * 0.45f));
            }
            if (etSearch != null) {
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) etSearch.getLayoutParams();
                lp.height = Math.max(28, (int) (height * 0.05f));
                etSearch.setLayoutParams(lp);
            }
        } else {
            if (titleBar != null) {
                int titleBarH = Math.max((int)(56 * density), (int)(height * 0.08f));
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) titleBar.getLayoutParams();
                lp.height = titleBarH;
                lp.topMargin = systemTopInset;
                titleBar.setLayoutParams(lp);
                // 顶部图标按标题栏高度等比缩放，下限36dp
                int iconSize = Math.max((int)(36 * density), (int)(titleBarH * 0.72f));
                if (ivAppIcon != null) {
                    android.widget.FrameLayout.LayoutParams lp2 = (android.widget.FrameLayout.LayoutParams) ivAppIcon.getLayoutParams();
                    lp2.width = iconSize; lp2.height = iconSize;
                    ivAppIcon.setLayoutParams(lp2);
                }
                if (btnClose != null) {
                    android.widget.FrameLayout.LayoutParams lp2 = (android.widget.FrameLayout.LayoutParams) btnClose.getLayoutParams();
                    lp2.width = iconSize; lp2.height = iconSize;
                    lp2.leftMargin = (int) (8 * density);
                    btnClose.setLayoutParams(lp2);
                    btnClose.setPadding((int)(iconSize * 0.17f), (int)(iconSize * 0.17f), (int)(iconSize * 0.17f), (int)(iconSize * 0.17f));
                }
                if (btnHelp != null) {
                    android.widget.FrameLayout.LayoutParams lp2 = (android.widget.FrameLayout.LayoutParams) btnHelp.getLayoutParams();
                    lp2.width = iconSize; lp2.height = iconSize;
                    lp2.rightMargin = (int) (8 * density);
                    btnHelp.setLayoutParams(lp2);
                    btnHelp.setPadding((int)(iconSize * 0.17f), (int)(iconSize * 0.17f), (int)(iconSize * 0.17f), (int)(iconSize * 0.17f));
                }
            }
            if (tabBar != null) {
                int tabBarH = Math.max((int)(56 * density), (int)(height * 0.08f));
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) tabBar.getLayoutParams();
                lp.height = tabBarH;
                tabBar.setLayoutParams(lp);
                float tabTitleSize = Math.max(14f * density, tabBarH * 0.25f);
                float tabTopSize = Math.max(6f * density, tabBarH * 0.11f);
                if (tabLocalTitle != null) tabLocalTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTitleSize);
                if (tabCloudTitle != null) tabCloudTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTitleSize);
                if (tabBiliTitle != null) tabBiliTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTitleSize);
                if (tabFavoriteTitle != null) tabFavoriteTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTitleSize);
                if (tabLocalTop != null) tabLocalTop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTopSize);
                if (tabCloudTop != null) tabCloudTop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTopSize);
                if (tabBiliTop != null) tabBiliTop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTopSize);
                if (tabFavoriteTop != null) tabFavoriteTop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tabTopSize);
                // 指示器宽度 = Tab 宽度的 60%
                if (tabIndicator != null) {
                    android.widget.FrameLayout.LayoutParams ilp = (android.widget.FrameLayout.LayoutParams) tabIndicator.getLayoutParams();
                    ilp.width = Math.max((int)(39 * density), (int)(tabBarH * 0.7f));
                    tabIndicator.setLayoutParams(ilp);
                }
            }
            if (tvCopyright != null) {
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) tvCopyright.getLayoutParams();
                lp.topMargin = Math.max((int)(8 * density), (int)(height * 0.006f));
                lp.bottomMargin = Math.max((int)(12 * density), (int)(height * 0.008f));
                tvCopyright.setLayoutParams(lp);
                tvCopyright.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(11f * density, height * 0.016f));
            }
            int miniH = Math.max((int)(58 * density), (int)(height * 0.10f));
            if (miniPlayerWrap != null) {
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) miniPlayerWrap.getLayoutParams();
                lp.height = miniH;
                miniPlayerWrap.setLayoutParams(lp);
            }
            if (miniPlayer != null) {
                android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) miniPlayer.getLayoutParams();
                lp.height = miniH - 2;
                miniPlayer.setLayoutParams(lp);
            }
            int miniCoverSize = Math.max((int)(42 * density), (int)(miniH * 0.72f));
            if (miniCover != null) {
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) miniCover.getLayoutParams();
                lp.width = miniCoverSize; lp.height = miniCoverSize;
                lp.leftMargin = Math.max((int)(12 * density), (int)(miniH * 0.15f)); lp.rightMargin = Math.max((int)(8 * density), (int)(miniH * 0.1f));
                miniCover.setLayoutParams(lp);
            }
            int btnSize = Math.max((int)(44 * density), (int)(miniH * 0.76f));
            if (miniPlayPause != null) {
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) miniPlayPause.getLayoutParams();
                lp.width = btnSize; lp.height = btnSize;
                lp.rightMargin = Math.max((int)(12 * density), (int)(miniH * 0.15f));
                miniPlayPause.setLayoutParams(lp);
            }
            if (miniSongTitle != null) miniSongTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(14f * density, miniH * 0.24f));
            if (miniSongArtist != null) miniSongArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(12f * density, miniH * 0.2f));
            if (pathBar != null) {
                int pathBarH = Math.max((int)(56 * density), (int)(height * 0.08f));
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) pathBar.getLayoutParams();
                lp.height = pathBarH;
                pathBar.setLayoutParams(lp);
                int iconSize = Math.max((int)(20 * density), (int)(pathBarH * 0.7f));
                if (btnNavigateBack != null) {
                    android.widget.LinearLayout.LayoutParams lp2 = (android.widget.LinearLayout.LayoutParams) btnNavigateBack.getLayoutParams();
                    lp2.width = iconSize; lp2.height = iconSize;
                    btnNavigateBack.setLayoutParams(lp2);
                }
                if (btnWebDavSettings != null) {
                    android.widget.LinearLayout.LayoutParams lp2 = (android.widget.LinearLayout.LayoutParams) btnWebDavSettings.getLayoutParams();
                    lp2.width = iconSize; lp2.height = iconSize;
                    btnWebDavSettings.setLayoutParams(lp2);
                }
                if (btnBiliSettings != null) {
                    android.widget.LinearLayout.LayoutParams lp2 = (android.widget.LinearLayout.LayoutParams) btnBiliSettings.getLayoutParams();
                    lp2.width = iconSize; lp2.height = iconSize;
                    btnBiliSettings.setLayoutParams(lp2);
                }
                if (tvBrowsePath != null) tvBrowsePath.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(13f * density, pathBarH * 0.3f));
            }
            if (searchBarContainer != null) {
                int searchBarH = Math.max((int)(40 * density), (int)(height * 0.08f));
                android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) searchBarContainer.getLayoutParams();
                lp.height = searchBarH;
                searchBarContainer.setLayoutParams(lp);
                if (etSearch != null) {
                    etSearch.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(14f * density, searchBarH * 0.28f));
                }
                if (tvSongCount != null) {
                    tvSongCount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(12f * density, searchBarH * 0.24f));
                }
            }
        }
    }
}
