package com.jingxin.jingxinmusic.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.jingxin.jingxinmusic.MainActivity;
import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.util.BiliApi;
import com.jingxin.jingxinmusic.util.BiliConfig;
import com.jingxin.jingxinmusic.util.HttpUtil;
import com.jingxin.jingxinmusic.util.ThemeColors;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * B站音源设置页面
 * 扫码登录 + 用户信息 + 去播放
 */
public class BiliSettingsActivity extends AppCompatActivity {

    private static final String TAG = "BiliSettings";
    /** 二维码轮询间隔（毫秒） */
    private static final long POLL_INTERVAL = 2000;
    /** 二维码有效期（3分钟） */
    private static final long QRCODE_VALID_MS = 180_000;

    private BiliConfig config;
    private boolean isNightMode;

    // 根布局
    private ScrollView rootScroll;

    // 未登录区域
    private LinearLayout layoutLogin;
    private WebView webViewQrcode;
    private LinearLayout layoutQrcodeLoading;
    private FrameLayout layoutQrcodeContainer;
    private TextView tvScanHint;
    private TextView tvScanStatus;
    private Button btnRefreshQrcode;

    // 已登录区域
    private LinearLayout layoutLoggedIn;
    private ImageView ivAvatar;
    private TextView tvNickname;
    private TextView tvUid;
    private Button btnLogout;
    private Button btnGoPlay;

    // 配色元素
    private TextView tvTitle;
    private View dividerTop;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 扫码轮询相关
    private String currentQrcodeKey;
    private long qrcodeCreateTime;
    private Runnable pollRunnable;
    private boolean isPolling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bili_settings);

        config = new BiliConfig(this);
        isNightMode = getSharedPreferences("theme", MODE_PRIVATE)
                .getBoolean("isNight", true);

        initViews();
        applyTheme();
        setupListeners();

        // 根据登录状态显示对应区域
        if (config.isConfigured()) {
            showLoggedIn();
        } else {
            showLoginArea();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        if (webViewQrcode != null) {
            webViewQrcode.destroy();
        }
        executor.shutdownNow();
    }

    private void initViews() {
        rootScroll = findViewById(R.id.root_scroll);

        // 未登录
        layoutLogin = findViewById(R.id.layout_login);
        webViewQrcode = findViewById(R.id.webview_qrcode);
        layoutQrcodeLoading = findViewById(R.id.layout_qrcode_loading);
        layoutQrcodeContainer = findViewById(R.id.layout_qrcode_container);
        tvScanHint = findViewById(R.id.tv_scan_hint);
        tvScanStatus = findViewById(R.id.tv_scan_status);
        btnRefreshQrcode = findViewById(R.id.btn_refresh_qrcode);

        // 初始化WebView
        initWebView();

        // 已登录
        layoutLoggedIn = findViewById(R.id.layout_logged_in);
        ivAvatar = findViewById(R.id.iv_avatar);
        tvNickname = findViewById(R.id.tv_nickname);
        tvUid = findViewById(R.id.tv_uid);
        btnLogout = findViewById(R.id.btn_logout);
        btnGoPlay = findViewById(R.id.btn_go_play);

        // 配色
        tvTitle = findViewById(R.id.tv_title);
        dividerTop = findViewById(R.id.divider_top);
    }

    private void initWebView() {
        WebSettings settings = webViewQrcode.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        // 禁止WebView跳转外部链接
        webViewQrcode.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return true; // 阻止所有导航
            }
        });
        webViewQrcode.setWebChromeClient(new WebChromeClient());
    }

    private void applyTheme() {
        int bgColor = isNightMode ? ThemeColors.nightBg() : ThemeColors.dayBg();
        int textColor = isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();
        int subTextColor = isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary();
        int dividerColor = isNightMode ? ThemeColors.nightDivider() : ThemeColors.dayDivider();

        rootScroll.setBackgroundColor(bgColor);
        tvTitle.setTextColor(textColor);
        dividerTop.setBackgroundColor(dividerColor);

        // 未登录区域
        ((TextView) findViewById(R.id.tv_label_qrcode)).setTextColor(textColor);
        tvScanHint.setTextColor(subTextColor);
        tvScanStatus.setTextColor(subTextColor);

        // 已登录区域
        tvNickname.setTextColor(textColor);
        tvUid.setTextColor(subTextColor);
    }

    private void setupListeners() {
        // 返回
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // 刷新二维码
        btnRefreshQrcode.setOnClickListener(v -> requestQrCode());

        // 退出登录
        btnLogout.setOnClickListener(v -> {
            config.clearAll();
            stopPolling();
            showLoginArea();
            Toast.makeText(this, "已退出B站登录", Toast.LENGTH_SHORT).show();
        });

        // 去播放：跳到MainActivity的B站tab，恢复当前导航层级
        btnGoPlay.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("select_tab", 2); // B站tab
            startActivity(intent);
        });
    }

    // ========== 登录/已登录区域切换 ==========

    private void showLoginArea() {
        layoutLogin.setVisibility(View.VISIBLE);
        layoutLoggedIn.setVisibility(View.GONE);
        tvScanStatus.setText("");
        btnRefreshQrcode.setVisibility(View.GONE);

        // 自动请求二维码
        requestQrCode();
    }

    // ========== 扫码登录核心流程 ==========

    /**
     * 请求B站二维码并显示
     */
    private void requestQrCode() {
        stopPolling();

        layoutQrcodeLoading.setVisibility(View.VISIBLE);
        webViewQrcode.setVisibility(View.GONE);
        btnRefreshQrcode.setVisibility(View.GONE);
        tvScanStatus.setText("正在生成二维码...");

        executor.execute(() -> {
            BiliApi.QrCodeResult result = BiliApi.getQrCode();
            uiHandler.post(() -> {
                if (result != null && result.url != null && result.qrcodeKey != null) {
                    currentQrcodeKey = result.qrcodeKey;
                    qrcodeCreateTime = System.currentTimeMillis();
                    renderQrCode(result.url);
                    startPolling();
                    tvScanStatus.setText("请使用B站APP扫描二维码");
                } else {
                    layoutQrcodeLoading.setVisibility(View.GONE);
                    tvScanStatus.setText("二维码生成失败，请点击刷新重试");
                    btnRefreshQrcode.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    /**
     * 在WebView中渲染二维码
     */
    private void renderQrCode(String url) {
        layoutQrcodeLoading.setVisibility(View.GONE);
        webViewQrcode.setVisibility(View.VISIBLE);

        // 加载本地HTML并调用JS生成二维码
        webViewQrcode.loadUrl("file:///android_asset/bili_qrcode.html");
        // 等HTML加载完成后调用generateQRCode
        webViewQrcode.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String pageUrl) {
                // 页面加载完成后，调用JS生成二维码
                String js = "generateQRCode('" + url.replace("'", "\\'") + "', 224, '#000000', '#ffffff')";
                view.evaluateJavascript(js, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return true;
            }
        });
    }

    /**
     * 开始轮询扫码状态
     */
    private void startPolling() {
        isPolling = true;
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPolling || currentQrcodeKey == null) return;

                // 检查二维码是否超时
                if (System.currentTimeMillis() - qrcodeCreateTime > QRCODE_VALID_MS) {
                    tvScanStatus.setText("二维码已过期，请点击刷新");
                    btnRefreshQrcode.setVisibility(View.VISIBLE);
                    stopPolling();
                    return;
                }

                executor.execute(() -> {
                    if (!isPolling) return; // 再检查一次，防止期间被stop
                    BiliApi.QrPollResult result = BiliApi.pollQrCode(currentQrcodeKey, config);
                    if (result == null || !isPolling) return;

                    uiHandler.post(() -> {
                        if (!isPolling) return; // UI线程也检查
                        switch (result.status) {
                            case BiliApi.QRCODE_NOT_SCANNED:
                                // 未扫码，继续轮询
                                scheduleNextPoll();
                                break;
                            case BiliApi.QRCODE_SCANNED:
                                tvScanStatus.setText("已扫码，请在手机上确认登录");
                                scheduleNextPoll();
                                break;
                            case BiliApi.QRCODE_CONFIRMED:
                                tvScanStatus.setText("登录确认中...");
                                stopPolling();
                                handleConfirmed(result);
                                break;
                            case BiliApi.QRCODE_EXPIRED:
                                tvScanStatus.setText("二维码已过期，请点击刷新");
                                btnRefreshQrcode.setVisibility(View.VISIBLE);
                                stopPolling();
                                break;
                            default:
                                tvScanStatus.setText("扫码异常(" + result.status + "): " + (result.message != null ? result.message : "未知"));
                                btnRefreshQrcode.setVisibility(View.VISIBLE);
                                stopPolling();
                                break;
                        }
                    });
                });
            }
        };
        uiHandler.postDelayed(pollRunnable, POLL_INTERVAL);
    }

    /**
     * 安排下一轮轮询（仅在isPolling为true时）
     */
    private void scheduleNextPoll() {
        if (isPolling && pollRunnable != null) {
            uiHandler.postDelayed(pollRunnable, POLL_INTERVAL);
        }
    }

    private void stopPolling() {
        isPolling = false;
        if (pollRunnable != null) {
            uiHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    /**
     * 扫码确认成功，处理从同一次HTTP响应中拿到的Cookie
     */
    private void handleConfirmed(BiliApi.QrPollResult result) {
        // 从轮询结果中直接保存Cookie
        if (result.sessdata != null && !result.sessdata.isEmpty()) {
            config.setSessData(result.sessdata);
        }
        if (result.biliJct != null && !result.biliJct.isEmpty()) {
            config.setBiliJct(result.biliJct);
        }
        if (result.dedeUserId != null && !result.dedeUserId.isEmpty()) {
            config.setDedeUserId(result.dedeUserId);
        }

        // 用nav接口验证cookie有效性并获取用户信息
        if (config.isConfigured()) {
            verifyAndShowLoggedIn();
        } else {
            String reason = "SESSDATA=" + (result.sessdata != null ? result.sessdata.length() + "字符" : "空");
            reason += "; cookieStr=" + (result.cookieStr != null ? result.cookieStr.length() + "字符" : "空");
            tvScanStatus.setText("Cookie获取失败(" + reason + ")，请重试");
            btnRefreshQrcode.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 验证cookie有效性并跳转到已登录页面
     */
    private void verifyAndShowLoggedIn() {
        executor.execute(() -> {
            BiliApi.UserInfo info = BiliApi.getUserInfo(config);
            uiHandler.post(() -> {
                if (info != null && info.isLogin) {
                    config.setNickname(info.nickname);
                    config.setAvatarUrl(info.avatarUrl);
                    config.setDedeUserId(String.valueOf(info.mid));
                    config.setEnabled(true);
                    showLoggedIn();
                } else {
                    tvScanStatus.setText("登录验证失败，请重新扫码");
                    btnRefreshQrcode.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    // ========== 已登录区域 ==========

    private void showLoggedIn() {
        layoutLogin.setVisibility(View.GONE);
        layoutLoggedIn.setVisibility(View.VISIBLE);
        stopPolling();

        // 加载用户信息
        executor.execute(() -> {
            BiliApi.UserInfo info = BiliApi.getUserInfo(config);
            if (info != null && info.isLogin) {
                uiHandler.post(() -> {
                    tvNickname.setText(info.nickname);
                    tvUid.setText("UID: " + info.mid);
                    // 保存用户信息到配置
                    config.setNickname(info.nickname);
                    config.setAvatarUrl(info.avatarUrl);
                    config.setDedeUserId(String.valueOf(info.mid));
                    config.setEnabled(true);

                    // 加载头像
                    loadAvatar(info.avatarUrl);
                });
            } else {
                uiHandler.post(() -> {
                    // Cookie已失效，退回登录
                    Toast.makeText(this, "登录已过期，请重新扫码", Toast.LENGTH_SHORT).show();
                    config.clearAll();
                    showLoginArea();
                });
            }
        });
    }

    private void loadAvatar(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isEmpty()) return;
        executor.execute(() -> {
            Bitmap bitmap = HttpUtil.getBitmap(avatarUrl);
            if (bitmap != null) {
                uiHandler.post(() -> ivAvatar.setImageBitmap(bitmap));
            }
        });
    }
}
