package com.jingxin.jingxinmusic.stage;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.util.EffectWallpaperStore;
import com.jingxin.jingxinmusic.view.EffectWallpaperView;
import com.jingxin.jingxinmusic.view.PlayerTopBar;
import com.jingxin.jingxinmusic.view.RadioUniverseView;
import com.jingxin.jingxinmusic.view.StarfieldView;

import java.io.File;
import java.util.List;

/**
 * 电台星空页 V2（电台专用播放场景：星空 + 漂浮收音机群）
 *
 * 结构：StarfieldView 星空底（壁纸模式时 GONE 由壁纸垫底）/ RadioUniverseView
 * 漂浮收音机层（点收音机切台、点当前台暂停、长按收藏）/ PlayerTopBar 呼出式顶栏
 *（空点/长按呼出，3.5s 自动隐藏；radioMode 禁动效/搜索/装扮三键）。
 * 电台入口专用（effectiveCoverStyle 动态分流），不作为用户可选风格。
 */
public class RadioPageV2 extends StagePage {

    private CarouselPageV2.Actions actions;

    private FrameLayout root;
    private StarfieldView starfield;
    private RadioUniverseView universe;
    private EffectWallpaperView wallpaper;
    private PlayerTopBar topBar;
    private ImageView favPulse; // 长按收藏心形脉冲

    // 顶栏呼出：空点/长按呼出 + 3.5s 自动隐藏
    private static final long TOPBAR_AUTOHIDE_MS = 3500L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable topBarHider = new Runnable() {
        @Override public void run() { hideTopBar(); }
    };

    // 队列异步装填重试（绑定/扫描竞态窗口；服务就绪与切歌广播到达自愈）
    private static final long QUEUE_RETRY_MS = 300;
    private static final int QUEUE_RETRY_MAX = 8;
    private int queueRetry;
    private final Runnable queueRetryTask = new Runnable() {
        @Override public void run() { syncQueueData(); }
    };

    // Hub 订阅（播态同步星海层；封面取色同步电波）
    private PlayerStateHub.Observer stateObserver;
    private PlayerStateHub.CoverColorObserver coverColorObserver;
    private int coverLoadToken = 0; // 电台封面加载竞态防护（换台/重进时旧回调作废）
    private boolean radioHasCover = false; // 当前电台是否有真实封面（无→电波白色，取色不覆盖）

    public RadioPageV2(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }

    public void setActions(CarouselPageV2.Actions actions) {
        this.actions = actions;
    }

    // ===== install / enter / exit =====

    @Override
    public void install(LayoutInflater inflater, ViewGroup host) {
        android.content.Context ctx = host.getContext();
        root = new FrameLayout(ctx);
        // 防透底：星空/壁纸异常时也不露出底层播放页
        root.setBackgroundColor(android.graphics.Color.BLACK);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 星空底
        starfield = new StarfieldView(ctx);
        starfield.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(starfield);

        // 漂浮收音机层
        universe = new RadioUniverseView(ctx);
        universe.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(universe);
        universe.setOnRadioAction(new RadioUniverseView.OnRadioAction() {
            @Override public void onPlaySongAt(int queueIndex) {
                if (actions != null) actions.playSongAtPosition(queueIndex);
            }
            @Override public void onTogglePlayPause() {
                if (actions != null) actions.togglePlayPause();
                if (universe != null) universe.setPlaying(!isPausedNow());
            }
            @Override public void onToggleFavorite() {
                if (actions == null) return;
                actions.toggleFavorite();
                showFavoritePulse(actions.isFavorite());
            }
        });
        // 空点=散开台名群（View 内部处理）；长按空白=呼出顶栏
        universe.setOnLongPressListener((x, y) -> revealTopBar());

        // 顶栏（呼出式）
        topBar = new PlayerTopBar(ctx);
        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topBar.setLayoutParams(tbLp);
        root.addView(topBar);
        topBar.setRadioMode(true); // 电台态：动效/搜索/装扮三键禁用
        topBar.setListener(new PlayerTopBar.Listener() {
            @Override public void onBack() { if (actions != null) actions.exitPlayerPage(); }
            // 音效弹窗可用；频谱/动效/搜索/装扮与直播流无关，保持空操作
        });
        topBar.setVisibility(View.GONE);

        host.addView(root);
    }

    @Override
    public void enter() {
        if (root == null) return;
        root.setVisibility(View.VISIBLE);
        // 壁纸模式：壁纸层垫底（成功则星空 GONE 停刷省性能；失败回退星空）
        injectWallpaper();
        // 队列灌入（绑定未完成为空，服务就绪后切歌广播重新拉满自愈）
        syncQueueData();
        subscribeHub();
        hideTopBar();
    }

    @Override
    public void exit() {
        if (root == null) return;
        unsubscribeHub();
        handler.removeCallbacks(topBarHider);
        handler.removeCallbacks(queueRetryTask);
        queueRetry = 0;
        hideTopBar();
        releaseWallpaper();
        root.setVisibility(View.GONE);
    }

    @Override
    public void layout(int width, int height) {
        if (root == null || width <= 0 || height <= 0) return;
        // 顶栏几何（同其他风格公式；星空/收音机层全屏自适应无需手术）
        topBar.applyGeometry(width, height, h.isLandscapeMode, h.density, h.systemTopInset);
    }

    @Override
    public void destroy() {
        if (root == null) return;
        unsubscribeHub();
        releaseWallpaper();
        ViewGroup p = (ViewGroup) root.getParent();
        if (p != null) p.removeView(root);
    }

    // ===== 队列同步（Fragment 切歌/服务就绪桥调 + 本页重试） =====

    /** 列表全量刷新（enter/切台广播/服务就绪/横竖切换均走此处） */
    public void syncQueueData() {
        if (actions == null || universe == null) return;
        List<Song> playlist = actions.getPlaylist();
        int idx = actions.getCurrentIndex();
        if (playlist == null || playlist.isEmpty()) {
            if (queueRetry < QUEUE_RETRY_MAX) {
                queueRetry++;
                handler.removeCallbacks(queueRetryTask);
                handler.postDelayed(queueRetryTask, QUEUE_RETRY_MS);
            }
            return;
        }
        queueRetry = 0;
        handler.removeCallbacks(queueRetryTask);
        universe.setQueueData(playlist, idx);
        universe.setPlaying(!isPausedNow());
        loadRadioCover(); // 换台刷新底部图标封面
    }

    private boolean isPausedNow() {
        PlayerStateHub hb = PlayerStateHub.get();
        return hb != null && !hb.isPlaying();
    }

// ===== Hub 播态 =====

    private void subscribeHub() {
        unsubscribeHub();
        PlayerStateHub hb = PlayerStateHub.get();
        stateObserver = state -> {
            if (universe != null) universe.setPlaying(state.playing);
        };
        hb.addObserver(stateObserver);
        stateObserver.onStateChanged(hb.getState());
        // 电波取色：封面副色亮化后注入（无封面电台→电波白色，取色不覆盖）
        coverColorObserver = (top, bottom) -> { if (radioHasCover) applyWaveColor(bottom != 0 ? bottom : top); };
        hb.addCoverColorObserver(coverColorObserver);
        if (radioHasCover && hb.hasCoverColors()) {
            applyWaveColor(hb.getCoverColorBottom() != 0 ? hb.getCoverColorBottom() : hb.getCoverColorTop());
        }
        // 底部图标封面：自行用 CoverLoader 加载电台台标/封面（有→圆封面旋转，无→默认图标）
        // 不走 Hub 封面——Hub 的 ic_launcher 默认兜底会让无封面电台显示成默认品牌图标
        loadRadioCover();
    }

    private void unsubscribeHub() {
        if (stateObserver != null) {
            PlayerStateHub.get().removeObserver(stateObserver);
            stateObserver = null;
        }
        if (coverColorObserver != null) {
            PlayerStateHub.get().removeCoverColorObserver(coverColorObserver);
            coverColorObserver = null;
        }
        coverLoadToken++; // 作废进行中的电台封面加载回调
    }

    /**
     * 底部电台图标封面加载（当前电台）：有台标/封面→圆封面旋转；无→默认图标。
     * 走 CoverLoader 电台分支（本地台标→URL 缓存→网络），与 Hub 兜底封面无关。
     */
    private void loadRadioCover() {
        if (universe == null || root == null) return;
        final int token = ++coverLoadToken;
        radioHasCover = false; // 默认无封面（电波白色），加载成功才置真
        List<Song> playlist = actions != null ? actions.getPlaylist() : null;
        int idx = actions != null ? actions.getCurrentIndex() : -1;
        if (playlist == null || idx < 0 || idx >= playlist.size()) {
            universe.setCover(null);
            return;
        }
        Song radio = playlist.get(idx);
        int size = (int) (universe.getResources().getDisplayMetrics().density * 240);
        com.jingxin.jingxinmusic.util.CoverLoader.load(root.getContext(), radio, size, size,
                true, h.executor, new com.jingxin.jingxinmusic.util.CoverLoader.CoverCallback() {
                    @Override
                    public void onCoverLoaded(android.graphics.Bitmap bitmap) {
                        if (token == coverLoadToken) {
                            radioHasCover = true;
                            universe.setCover(bitmap);
                            reapplyCoverColor(); // 封面就绪后按当前 Hub 取色上色电波
                        }
                    }

                    @Override
                    public void onCoverFailed() {
                        if (token == coverLoadToken) {
                            radioHasCover = false;
                            universe.setCover(null); // 电波随之回默认白色
                        }
                    }
                });
    }

    /** 封面就绪后按 Hub 当前封面双色刷新电波色（无取色则保持默认白） */
    private void reapplyCoverColor() {
        if (universe == null || !radioHasCover) return;
        PlayerStateHub hb = PlayerStateHub.get();
        if (hb.hasCoverColors()) {
            applyWaveColor(hb.getCoverColorBottom() != 0 ? hb.getCoverColorBottom() : hb.getCoverColorTop());
        }
    }

    /** 电波色：封面主/副色亮化 到可读亮度（深色封面在黑星空上看不清） */
    private void applyWaveColor(int argb) {
        if (universe == null || argb == 0) return;
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(argb, hsv);
        if (hsv[2] < 0.62f) hsv[2] = 0.62f;          // 提亮
        if (hsv[1] > 0.75f) hsv[1] = 0.75f;          // 降饱和避免刺眼
        universe.setWaveColor(android.graphics.Color.HSVToColor(hsv));
    }

    // ===== 顶栏呼出/隐藏 =====

    private void revealTopBar() {
        handler.removeCallbacks(topBarHider);
        if (topBar != null) {
            topBar.applyTheme(true, 0); // 星空恒深底：恒夜间态白图标
            topBar.setRadioMode(true);
            topBar.setVisibility(View.VISIBLE);
        }
        handler.postDelayed(topBarHider, TOPBAR_AUTOHIDE_MS);
    }

    private void hideTopBar() {
        handler.removeCallbacks(topBarHider);
        if (topBar != null) topBar.setVisibility(View.GONE);
    }

    // ===== 壁纸（与歌词动效页同一壁纸设置） =====

    private void injectWallpaper() {
        releaseWallpaper();
        android.content.Context ctx = root.getContext();
        if (!EffectWallpaperStore.isWallpaperMode(ctx)) return;
        File file = EffectWallpaperStore.findWallpaperFile(ctx);
        if (file == null) return;
        EffectWallpaperView wp = new EffectWallpaperView(ctx);
        // 异步失败兜底（视频 error 等）：移除壁纸层并恢复星空底
        wp.setOnWallpaperErrorListener(() -> {
            if (wallpaper != wp || root == null) return;
            root.removeView(wp);
            wallpaper = null;
            if (starfield != null) starfield.setVisibility(View.VISIBLE);
        });
        if (!wp.load(file)) {
            wp.release();
            return;
        }
        root.addView(wp, 0);
        root.bringChildToFront(universe);
        root.bringChildToFront(topBar);
        wallpaper = wp;
        starfield.setVisibility(View.GONE);
    }

    private void releaseWallpaper() {
        if (wallpaper != null) {
            EffectWallpaperView wp = wallpaper;
            wallpaper = null;
            if (wp.getParent() == root) root.removeView(wp);
            wp.release();
        }
        if (starfield != null) starfield.setVisibility(View.VISIBLE);
    }

    // ===== 长按当前台收藏脉冲（心形放大渐隐） =====

    private void showFavoritePulse(boolean filled) {
        if (root == null) return;
        if (favPulse == null) {
            favPulse = new ImageView(root.getContext());
            favPulse.setScaleType(ImageView.ScaleType.FIT_CENTER);
            favPulse.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
            root.addView(favPulse);
            favPulse.setZ(25f);
            favPulse.setVisibility(View.GONE);
        }
        android.content.Context ctx = root.getContext();
        int size = (int) (ctx.getResources().getDisplayMetrics().density * 72);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) favPulse.getLayoutParams();
        lp.width = size;
        lp.height = size;
        favPulse.setLayoutParams(lp);
        // 当前收音机中心（近端锚点；脉冲从它上方弹出）
        float cx = universe != null ? universe.getWidth() * 0.5f : root.getWidth() * 0.5f;
        float cy = universe != null && universe.getHeight() > 0
                ? universe.getHeight() * 0.72f : root.getHeight() * 0.7f;
        if (universe != null && universe.getCurrentStationCenter() != null) {
            float[] c = universe.getCurrentStationCenter();
            cx = c[0]; cy = c[1];
        }
        favPulse.setTranslationX(cx - size / 2f);
        favPulse.setTranslationY(cy - size / 2f);
        favPulse.setImageResource(filled ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);
        favPulse.setColorFilter(filled ? 0xFFFF3B30 : 0xFFFFFFFF,
                android.graphics.PorterDuff.Mode.SRC_IN);
        favPulse.animate().cancel();
        favPulse.setVisibility(View.GONE);
        favPulse.setAlpha(1f);
        favPulse.setScaleX(0.2f);
        favPulse.setScaleY(0.2f);
        favPulse.setVisibility(View.VISIBLE);
        favPulse.animate().scaleX(3f).scaleY(3f).alpha(0f)
                .setDuration(420)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> favPulse.setVisibility(View.GONE))
                .start();
    }
}
