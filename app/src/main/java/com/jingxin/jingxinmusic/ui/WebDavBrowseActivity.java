package com.jingxin.jingxinmusic.ui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.jingxin.jingxinmusic.PlayerActivity;
import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.util.WebDavConfig;
import com.jingxin.jingxinmusic.util.WebDavScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * WebDAV 逐级目录浏览页
 * 网格图标布局，点击目录进入下一级，点击音乐文件播放
 * 底部迷你播放条：显示正在播放歌曲，可快速返回播放页
 */
public class WebDavBrowseActivity extends AppCompatActivity {

    private static final String TAG = "WebDavBrowse";

    private WebDavConfig config;
    private WebDavScanner scanner;

    private RecyclerView rvItems;
    private LinearLayout loadingLayout;
    private LinearLayout emptyLayout;
    private TextView tvCurrentPath;
    private ImageView btnBack;
    private ImageView btnSettings;

    // Mini 播放条
    private View miniPlayer;
    private View miniPlayerDivider;
    private TextView miniSongTitle;
    private TextView miniSongArtist;
    private ImageView miniPlayPause;

    private WebDavItemAdapter adapter;

    // 导航栈：记录每一级的URL，支持返回
    private Stack<String> navigationStack = new Stack<>();
    private String currentUrl;

    // 播放服务绑定
    private MusicPlayerService.MusicPlayerBinder playerBinder;
    private boolean bound = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // 监听播放状态变化的广播接收器
    private BroadcastReceiver playStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (MusicPlayerService.ACTION_SONG_CHANGED.equals(action)) {
                String title = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_TITLE);
                String artist = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_ARTIST);
                miniSongTitle.setText(title);
                miniSongArtist.setText(artist != null ? artist : "");
                showMiniPlayer(true);
            } else if (MusicPlayerService.ACTION_PLAY_STATE_CHANGED.equals(action)) {
                boolean playing = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false);
                miniPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                String title = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_TITLE);
                if (title != null) {
                    miniSongTitle.setText(title);
                    String artist = intent.getStringExtra(MusicPlayerService.EXTRA_SONG_ARTIST);
                    miniSongArtist.setText(artist != null ? artist : "");
                    showMiniPlayer(true);
                }
            }
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerBinder = (MusicPlayerService.MusicPlayerBinder) service;
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
        setContentView(R.layout.activity_webdav_browse);

        config = new WebDavConfig(this);
        scanner = new WebDavScanner(config);

        initViews();
        setupRecyclerView();
        setupMiniPlayer();
        bindPlayService();

        // 从配置的音乐根目录开始浏览
        String rootUrl = config.getMusicUrl();
        if (rootUrl.isEmpty()) {
            Toast.makeText(this, "请先配置WebDAV", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        navigateTo(rootUrl);
    }

    private void initViews() {
        rvItems = findViewById(R.id.rv_items);
        loadingLayout = findViewById(R.id.loading_layout);
        emptyLayout = findViewById(R.id.empty_layout);
        tvCurrentPath = findViewById(R.id.tv_current_path);
        btnBack = findViewById(R.id.btn_back);
        btnSettings = findViewById(R.id.btn_settings);

        // 返回按钮：返回上一级目录
        btnBack.setOnClickListener(v -> navigateBack());

        // 设置按钮
        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(this, WebDavSettingsActivity.class));
        });
    }

    private void setupRecyclerView() {
        adapter = new WebDavItemAdapter();
        // 网格布局：根据屏幕宽度自适应列数，每项约120dp
        int spanCount = Math.max(3, getResources().getDisplayMetrics().widthPixels / 360);
        rvItems.setLayoutManager(new GridLayoutManager(this, spanCount));
        rvItems.setAdapter(adapter);

        adapter.setOnItemClickListener((item, position) -> {
            if (item.isDirectory) {
                // 点击目录：进入下一级
                navigateTo(item.url);
            } else {
                // 点击音乐文件：开始播放
                playWebDavSong(item);
            }
        });
    }

    /**
     * 初始化 Mini 播放条
     */
    private void setupMiniPlayer() {
        miniPlayer = findViewById(R.id.mini_player);
        miniPlayerDivider = findViewById(R.id.mini_player_divider);
        miniSongTitle = findViewById(R.id.mini_song_title);
        miniSongArtist = findViewById(R.id.mini_song_artist);
        miniPlayPause = findViewById(R.id.mini_play_pause);

        // 点击播放条主体 → 跳转播放页
        findViewById(R.id.mini_player_info).setOnClickListener(v -> openPlayerFromMini());

        // 点击播放/暂停
        miniPlayPause.setOnClickListener(v -> {
            if (bound && playerBinder != null) {
                playerBinder.togglePlayPause();
            }
        });
    }

    /**
     * 绑定播放服务 + 注册广播
     */
    private void bindPlayService() {
        Intent serviceIntent = new Intent(this, MusicPlayerService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(MusicPlayerService.ACTION_SONG_CHANGED);
        filter.addAction(MusicPlayerService.ACTION_PLAY_STATE_CHANGED);
        CompatUtil.safeRegisterReceiver(this, playStateReceiver, filter);
    }

    /**
     * 从 Service 获取当前播放信息，更新 mini 播放条
     */
    private void updateMiniPlayerFromService() {
        if (bound && playerBinder != null) {
            Song currentSong = playerBinder.getCurrentSong();
            if (currentSong != null && currentSong.title != null) {
                miniSongTitle.setText(currentSong.title);
                miniSongArtist.setText(currentSong.artist != null ? currentSong.artist : "");
                miniPlayPause.setImageResource(playerBinder.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
                showMiniPlayer(true);
            }
        }
    }

    /**
     * 显示/隐藏 Mini 播放条
     */
    private void showMiniPlayer(boolean show) {
        miniPlayer.setVisibility(show ? View.VISIBLE : View.GONE);
        miniPlayerDivider.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * 点击 mini 播放条 → 跳转播放页（resume模式）
     */
    private void openPlayerFromMini() {
        if (bound && playerBinder != null) {
            Song currentSong = playerBinder.getCurrentSong();
            if (currentSong == null || currentSong.title == null) return;

            Intent intent = new Intent(this, PlayerActivity.class);
            currentSong.toIntent(intent);
            intent.putExtra("position", playerBinder.getCurrentIndex());
            intent.putExtra("resume_play", true);
            startActivity(intent);
        }
    }

    // ========== 目录导航 ==========

    private void navigateTo(String url) {
        navigateTo(url, true);
    }

    private void navigateTo(String url, boolean pushStack) {
        if (pushStack && currentUrl != null) {
            navigationStack.push(currentUrl);
        }
        currentUrl = url;

        String displayPath = extractDisplayPath(url);
        tvCurrentPath.setText("/ " + displayPath);

        showLoading(true);

        new Thread(() -> {
            List<WebDavScanner.DavItem> items = scanner.listDirectory(url);
            uiHandler.post(() -> {
                showLoading(false);
                if (items.isEmpty()) {
                    showEmpty(true);
                } else {
                    showEmpty(false);
                    adapter.setItems(items);
                }
            });
        }).start();
    }

    private void navigateBack() {
        if (navigationStack.isEmpty()) {
            finish();
            return;
        }
        String parentUrl = navigationStack.pop();
        navigateTo(parentUrl, false);
    }

    // ========== 播放 ==========

    private void playWebDavSong(WebDavScanner.DavItem clickedItem) {
        List<WebDavScanner.DavItem> allItems = adapter.getItems();
        List<Song> playlist = new ArrayList<>();
        int playIndex = 0;
        long idBase = 1000000;

        for (int i = 0; i < allItems.size(); i++) {
            WebDavScanner.DavItem item = allItems.get(i);
            if (!item.isDirectory && WebDavScanner.isMusicFile(item.name)) {
                Song song = WebDavScanner.davItemToSong(item, idBase++);
                playlist.add(song);
                if (item == clickedItem) {
                    playIndex = playlist.size() - 1;
                }
            }
        }

        if (playlist.isEmpty()) {
            Toast.makeText(this, "无可播放的音乐文件", Toast.LENGTH_SHORT).show();
            return;
        }

        saveWebDavPlaylist(playlist, playIndex);

        Intent intent = new Intent(this, PlayerActivity.class);
        Song clickedSong = playlist.get(playIndex);
        clickedSong.toIntent(intent);
        intent.putExtra("song_index", playIndex);
        intent.putExtra("from_webdav", true);
        intent.putExtra("webdav_playlist_size", playlist.size());
        startActivity(intent);

        Log.d(TAG, "播放WebDAV歌曲: " + clickedSong.title + " 列表共" + playlist.size() + "首");
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

    // ========== 工具方法 ==========

    private String extractDisplayPath(String url) {
        String musicUrl = config.getMusicUrl();
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

    private void showLoading(boolean show) {
        rvItems.setVisibility(show ? View.GONE : View.VISIBLE);
        loadingLayout.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) emptyLayout.setVisibility(View.GONE);
    }

    private void showEmpty(boolean show) {
        rvItems.setVisibility(show ? View.GONE : View.VISIBLE);
        emptyLayout.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ========== 生命周期 ==========

    @Override
    protected void onResume() {
        super.onResume();
        // 回到页面时同步播放状态（可能在播放页操作过）
        updateMiniPlayerFromService();
    }

    @Override
    public void onBackPressed() {
        navigateBack();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 注销广播
        try {
            unregisterReceiver(playStateReceiver);
        } catch (Exception ignored) {}
        // 解绑服务
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
    }
}
