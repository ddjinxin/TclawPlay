package com.jingxin.jingxinmusic.stage;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.view.SpectrumView;
import com.jingxin.jingxinmusic.view.lattice.LatticeWallView;

import java.util.List;

/**
 * 拼贴风格页 V2（第9风格，FOLIA Lattice 封面墙移植，全屏墙形态）
 */
public class LatticePageV2 extends StandardPageBase {

    private CarouselPageV2.Actions latticeActions;

    private LatticeWallView wall;
    private LatticeWallView wallL;

    // 父类私有视图的自持引用（infoFlow/topBar 为 private，此处自行绑定同 id）
    private View infoFlowV, infoFlowLV, topBarV, topBarLV;
    // 真 SpectrumView 实例（与其他风格同源同款渲染源）：盖到展开卡频谱带上
    private SpectrumView cardSpectrum;
    private SpectrumView cardSpectrumL;
    // 卡内歌名/歌手文字层（z=20，频谱 z=15 之上：频谱是独立 View，墙 canvas 后绘压不过它，
    // 文字必须是更高 z 的独立 View 才能真正在频谱上面）
    private android.widget.LinearLayout cardTextLayer;
    private android.widget.TextView cardTitleTv, cardArtistTv;
    private String lastTitleSet, lastArtistSet;
    /** 最近一次灌入的队列（文字层取展开卡歌名用） */
    private List<Song> lastPlaylist;
    // 封面双色（同步给墙做进度线色；与频谱实例同源）
    private int tintTop, tintBottom;

    // Hub 自有订阅（基类订阅喂歌词/进度条，本页歌词/进度条全隐，自建喂墙）
    private PlayerStateHub.ProgressObserver progressObserver;
    private PlayerStateHub.Observer stateObserver;
    private PlayerStateHub.CoverColorObserver coverColorObserver;

    // 临时顶栏：空点呼出 + 3.5s 自动隐藏
    private static final long TOPBAR_AUTOHIDE_MS = 3500L;
    private final Handler topBarHandler = new Handler(Looper.getMainLooper());
    private final Runnable topBarHider = new Runnable() {
        @Override public void run() { hideTopBars(); }
    };

    public LatticePageV2(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }

    public void setActions(CarouselPageV2.Actions actions) {
        super.setActions(actions);
        this.latticeActions = actions;
    }

    @Override
    public void install(LayoutInflater inflater, ViewGroup host) {
        super.install(inflater, host);
        wall = portraitRoot.findViewById(R.id.lt2_wall);
        wallL = landscapeRoot.findViewById(R.id.lt2_wall_land);
        infoFlowV = portraitRoot.findViewById(R.id.v2_info_flow);
        infoFlowLV = landscapeRoot.findViewById(R.id.v2_info_flow_land);
        topBarV = portraitRoot.findViewById(R.id.v2_top_bar);
        topBarLV = landscapeRoot.findViewById(R.id.v2_top_bar_land);
        cardSpectrum = portraitRoot.findViewById(R.id.v2_spectrum);
        cardSpectrumL = landscapeRoot.findViewById(R.id.v2_spectrum_land);
        LatticeWallView.OnTileAction tile = new LatticeWallView.OnTileAction() {
            @Override public void onPlaySongAt(int queueIndex) {
                // 绝对定位播放：queueIndex 是队列真实索引（墙环绕索引已换算），
                // 不走 delta（delta=墙索引-当前索引 在环绕/远副本下无意义）
                if (latticeActions != null) latticeActions.playSongAtPosition(queueIndex);
            }
            @Override public void onTogglePlayPause() {
                if (latticeActions != null) latticeActions.togglePlayPause();
            }
            @Override public void onPlayPrevious() {
                if (latticeActions != null) latticeActions.playPrevious();
            }
            @Override public void onPlayNext() {
                if (latticeActions != null) latticeActions.playNext();
            }
        };
        LatticeWallView.OnEmptyTapListener emptyTap = this::revealTopBar;
        LatticeWallView.OnLongPressListener longPress = (sx, sy) -> revealTopBar();
        LatticeWallView.OnExpandedCoverTapListener coverTap = () -> {
            if (latticeActions != null) latticeActions.openLyricEffectRemembered();
        };
        for (LatticeWallView w : new LatticeWallView[]{wall, wallL}) {
            if (w == null) continue;
            w.setExecutor(h.executor);
            w.setOnTileAction(tile);
            w.setOnEmptyTapListener(emptyTap);
            w.setOnLongPressListener(longPress);
            w.setOnExpandedCoverTapListener(coverTap);
            // 频谱带同步回调：带变化时把真 SpectrumView 实例盖上去
            w.setSpectrumBandUpdater(this::syncCardSpectrumPosition);
        }
        // 真 SpectrumView 从 info_flow（已 GONE）中拎出来抬到页根，避免被 info_flow 连带隐藏
        liftSpectrumFromInfoFlow();
        setUpCardTextLayer();
    }

    /** 拼贴独立骨架 XML（父类绑定 id 全保留复用） */
    @Override
    protected int providePortraitLayout() { return R.layout.stage_page_lattice_v2; }

    @Override
    protected int provideLandscapeLayout() { return R.layout.stage_page_lattice_v2_land; }

    @Override
    public void enter() {
        super.enter();
        boolean landscape = h.isLandscapeMode;
        // 全屏墙接管：父类可能在 enter 中回置的传统元素全部压回 GONE
        setGone(infoFlowV, infoFlowLV);
        cover.setVisibility(View.GONE);
        coverL.setVisibility(View.GONE);
        coverArea.setVisibility(View.GONE);
        if (coverCol != null) coverCol.setVisibility(View.GONE);
        hideTopBars();
        activeWall(landscape).setVisibility(View.VISIBLE);
        // 队列灌入（绑定未完成为空，服务就绪后切歌广播重新拉满自愈）
        syncQueueData();
        applyNightToWall();
        subscribeWallObservers();
        pushSpectrumStyle();
    }

    @Override
    public void exit() {
        super.exit();
        unsubscribeWallObservers();
        topBarHandler.removeCallbacks(topBarHider);
        hideTopBars();
        if (wall != null) wall.setVisibility(View.GONE);
        if (wallL != null) wallL.setVisibility(View.GONE);
        if (cardSpectrum != null) cardSpectrum.setVisibility(View.GONE);
        if (cardSpectrumL != null) cardSpectrumL.setVisibility(View.GONE);
        if (cardTextLayer != null) cardTextLayer.setVisibility(View.GONE);
    }

    @Override
    public void layout(int width, int height) {
        super.layout(width, height);
        // 横竖切换后保持压制 + 重灌数据
        // 封面族必压：父类横屏布局结尾硬编码 coverL/coverCol VISIBLE（旧横屏 FULL 歌词行为），
        // layout 晚于 enter 调用，会把圆形封面重新盖到墙上
        setGone(infoFlowV, infoFlowLV, cover, coverL, coverArea, coverCol);
        hideTopBars();
        syncQueueData();
        applyNightToWall();
        // 卡内频谱/文字层必须重灌位置：super.layout 的 layoutPortraitImpl/layoutLandscapeImpl
        // 无条件把频谱实例高度写成 10% 屏高（它以为是 info_flow 底部频谱，实为我们拎出的
        // 卡内实例）；而墙的带矩形未变不触发回调，循环回拼贴时破坏值会一直留着
        pushSpectrumStyle();
    }

    // ===== 旁路父类的传统元素机制 =====

    /** 精简模式旁路：进度/控制条全隐时代价太高，触摸拦截层绝不能拦墙 */
    @Override
    public void applyControlsHidden(boolean hidden) {
        // no-op：墙自己管理 chrome
    }

    @Override
    public void setControlsHiddenFromHost(boolean hidden) {
        // no-op：同上
    }

    /** 歌词模式变化不再动封面/占位（全隐）；仅保持锁多行 */@Override
    protected void applyLyricModeEffect(boolean land) {
        // no-op：无歌词元素
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        if (lyric != null && lyric.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL) {
            lyric.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.MULTI_LINE);
        }
        if (lyricL != null && lyricL.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL) {
            lyricL.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.MULTI_LINE);
        }
    }

    /** 封面链截断：墙自持双档缓存，父类封面/模糊链不做（墙全屏遮蔽，白省 CPU） */
    @Override
    protected void onCoverArrived(android.graphics.Bitmap bitmap, boolean landscape) {
        // no-op
    }

    // ===== 顶栏呼出/隐藏 =====

    private void revealTopBar() {
        topBarHandler.removeCallbacks(topBarHider);
        if (topBarV != null) topBarV.setVisibility(View.VISIBLE);
        if (topBarLV != null) topBarLV.setVisibility(View.VISIBLE);
        topBarHandler.postDelayed(topBarHider, TOPBAR_AUTOHIDE_MS);
    }

    private void hideTopBars() {
        topBarHandler.removeCallbacks(topBarHider);
        if (topBarV != null) topBarV.setVisibility(View.GONE);
        if (topBarLV != null) topBarLV.setVisibility(View.GONE);
    }

    private static void setGone(View... views) {
        for (View v : views) { if (v != null) v.setVisibility(View.GONE); }
    }

    // ===== 墙数据/观察者 =====

    /** 动效页占用/释放（Fragment 进/退动效页时调：动效页全屏沉浸，卡内频谱与文字隐藏） */
    public void setEffectPageActive(boolean active) {
        effectPageActive = active;
        if (active) {
            if (cardSpectrum != null) cardSpectrum.setVisibility(View.GONE);
            if (cardSpectrumL != null) cardSpectrumL.setVisibility(View.GONE);
            if (cardTextLayer != null) cardTextLayer.setVisibility(View.GONE);
        } else {
            syncCardSpectrumPosition(); // 回墙时按当前带位置恢复
        }
    }

    private boolean effectPageActive = false;

    /** 列表全量刷新（enter/切歌广播/服务就绪/横竖切换均走此处） */
    public void syncQueueData() {
        if (latticeActions == null) return;
        List<Song> playlist = latticeActions.getPlaylist();
        int idx = latticeActions.getCurrentIndex();
        if (playlist == null || playlist.isEmpty()) return;
        lastPlaylist = playlist;
        // 两实例都灌（横竖切换即得数据）
        if (wall != null) wall.setQueueData(playlist, idx);
        if (wallL != null) wallL.setQueueData(playlist, idx);
    }

    // ===== 频谱链：真 SpectrumView 实例盖到展开卡频谱带（与其他风格同源同款） =====

    /** 频谱样式同步到卡内实例（Fragment 切样式时调；环类由调用前拦截，无需特殊处理） */
    @Override
    public void applySpectrumStyle(int style) {
        super.applySpectrumStyle(style);
        if (cardSpectrum != null) cardSpectrum.setStyleSilent(style);
        if (cardSpectrumL != null) cardSpectrumL.setStyleSilent(style);
        syncCardSpectrumPosition();
    }

    /** 频谱数据分发到卡内实例（与其他风格同数据源同渲染），闲置那只不喂数 */
    @Override
    public void feedSpectrumData(float[] magnitudes, float maxMag) {
        SpectrumView sp = h.isLandscapeMode ? cardSpectrumL : cardSpectrum;
        if (sp != null && sp.getVisibility() == View.VISIBLE) {
            sp.updateDTFMagnitudes(magnitudes, maxMag);
        }
    }

    /** 订阅后初始化卡内频谱（样式/夜间/取色同步真实例） */
    private void pushSpectrumStyle() {
        if (h.spectrumView == null) return;
        int style = h.spectrumView.getCurrentStyle();
        PlayerStateHub hb = hubRef();
        for (SpectrumView sp : new SpectrumView[]{cardSpectrum, cardSpectrumL}) {
            if (sp == null) continue;
            sp.setStyleSilent(style);
            sp.setNightMode(h.isNightMode);
            sp.setPlaying(hb.isPlaying());
            if (hb.hasCoverColors()) {
                sp.setTint(hb.getCoverColorTop(), hb.getCoverColorBottom());
            }
        }
        syncCardSpectrumPosition();
    }

    private void subscribeWallObservers() {
        unsubscribeWallObservers();
        PlayerStateHub hb = hubRef();
        progressObserver = (positionMs, durationMs) -> {
            if (wall != null) wall.setProgress(positionMs, durationMs);
            if (wallL != null) wallL.setProgress(positionMs, durationMs);
        };
        hb.addProgressObserver(progressObserver);
        stateObserver = state -> {
            boolean playing = state.playing;
            boolean night = state.nightMode;
            if (wall != null) { wall.setPlaying(playing); wall.setNight(night); }
            if (wallL != null) { wallL.setPlaying(playing); wallL.setNight(night); }
            // 频谱实例同步播态（暂停态画静态形不跑动画，同其他风格）
            if (cardSpectrum != null) cardSpectrum.setPlaying(playing);
            if (cardSpectrumL != null) cardSpectrumL.setPlaying(playing);
        };
        hb.addObserver(stateObserver);
        // 频谱颜色：与 StandardPageBase.onColorsArrived 同逻辑——封面双色到达即注入 tint
        // （"频谱恒双色 tint 与开关无关"，不判光晕模式；切歌自动变色）
        coverColorObserver = (top, bottom) -> {
            if (cardSpectrum != null) cardSpectrum.setTint(top, bottom);
            if (cardSpectrumL != null) cardSpectrumL.setTint(top, bottom);
            tintTop = top; tintBottom = bottom;
        };
        hb.addCoverColorObserver(coverColorObserver);
        if (hb.hasCoverColors()) {
            coverColorObserver.onCoverColorsChanged(hb.getCoverColorTop(), hb.getCoverColorBottom());
        } else {
            // 无双色（无封面/提取失败）：清 tint 回落默认色，防残留上首歌颜色
            if (cardSpectrum != null) cardSpectrum.clearTint();
            if (cardSpectrumL != null) cardSpectrumL.clearTint();
        }
        pushSpectrumStyle();
        // 立即灌当前快照
        stateObserver.onStateChanged(hb.getState());
        progressObserver.onProgress(hb.getPositionMs(), hb.getDurationMs());
    }

    /** 双色光晕模式判定（背景皮肤模式 1；供 aurora 背景用，频谱 tint 不判此开关） */
    private static boolean aAuroraActive(PlayerStateHub hb) {
        PlayerStateHub.State s = hb.getState();
        return s.bgSkinMode == 1 && s.auroraActive;
    }

    private void unsubscribeWallObservers() {
        PlayerStateHub hb = hubRef();
        if (progressObserver != null) { hb.removeProgressObserver(progressObserver); progressObserver = null; }
        if (stateObserver != null) { hb.removeObserver(stateObserver); stateObserver = null; }
        if (coverColorObserver != null) { hb.removeCoverColorObserver(coverColorObserver); coverColorObserver = null; }
    }

    private LatticeWallView activeWall(boolean landscape) {
        LatticeWallView active = landscape ? wallL : wall;
        return active != null ? active : (wall != null ? wall : wallL);
    }

    /**
     * 把真 SpectrumView 实例从 info_flow（本页已 GONE）拎到宿主容器：
     * info_flow 隐藏会连带隐藏子实例，卡内频谱须独立挂 rootLayout（与墙同层）。
     */
    private void liftSpectrumFromInfoFlow() {
        for (SpectrumView sp : new SpectrumView[]{cardSpectrum, cardSpectrumL}) {
            if (sp == null) continue;
            ViewGroup parent = (ViewGroup) sp.getParent();
            if (parent != null && parent != h.rootLayout) {
                parent.removeView(sp);
                h.rootLayout.addView(sp, new android.widget.FrameLayout.LayoutParams(0, 0));
                sp.setZ(15f); // 墙之上（墙z=0），顶栏之下；抬顶栏时其自身置前
                sp.setVisibility(View.GONE);
            }
        }
    }

    /** 把活跃 SpectrumView 实例平移缩放到墙算出的频谱带上（带无效/环类/动效页占用=隐藏） */
    private void syncCardSpectrumPosition() {
        if (effectPageActive) return; // 动效页期间不恢复
        LatticeWallView active = activeWall(h.isLandscapeMode);
        // 文字层与频谱同步走（同一回调链：卡矩形/带矩形变化均触发）
        syncCardText(active);
        SpectrumView sp = h.isLandscapeMode ? cardSpectrumL : cardSpectrum;
        SpectrumView idle = h.isLandscapeMode ? cardSpectrum : cardSpectrumL;
        if (idle != null) idle.setVisibility(View.GONE);
        if (sp == null || active == null) return;
        float[] band = active.getSpectrumBand();
        if (band == null) {
            sp.setVisibility(View.GONE);
            return;
        }
        // 环类样式拦截（页面侧双保险，与墙侧判定一致）
        int style = h.spectrumView != null ? h.spectrumView.getCurrentStyle() : sp.getCurrentStyle();
        if (SpectrumView.isOverlayStyle(style)) {
            sp.setVisibility(View.GONE);
            return;
        }
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) sp.getLayoutParams();
        lp.leftMargin = (int) band[0];
        lp.topMargin = (int) band[1];
        lp.width = (int) (band[2] - band[0]);
        lp.height = (int) (band[3] - band[1]);
        sp.setLayoutParams(lp);
        sp.setVisibility(View.VISIBLE);
        // 样式/夜间与真值实例同步（切风格期间改过样式时到达即对齐）
        if (h.spectrumView != null) {
            if (sp.getCurrentStyle() != h.spectrumView.getCurrentStyle()) {
                sp.setStyleSilent(h.spectrumView.getCurrentStyle());
            }
            sp.setNightMode(h.isNightMode);
        }
    }

    /**
     * 卡内文字层（歌名+歌手，底部对齐贴进度线上方）。z=20 高于频谱 z=15——
     * 频谱是独立 View，墙 canvas 后绘无法覆盖它，文字必须同为 View 且更高 z。
     */
    private void setUpCardTextLayer() {
        if (cardTextLayer != null || h.rootLayout == null) return;
        android.content.Context ctx = h.rootLayout.getContext();
        cardTextLayer = new android.widget.LinearLayout(ctx);
        cardTextLayer.setOrientation(android.widget.LinearLayout.VERTICAL);
        cardTextLayer.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        cardTitleTv = new android.widget.TextView(ctx);
        cardArtistTv = new android.widget.TextView(ctx);
        for (android.widget.TextView tv : new android.widget.TextView[]{cardTitleTv, cardArtistTv}) {
            tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setClickable(false);
            tv.setFocusable(false);
        }
        cardTextLayer.addView(cardTitleTv);
        cardTextLayer.addView(cardArtistTv);
        cardTextLayer.setLayoutParams(new android.widget.FrameLayout.LayoutParams(0, 0));
        h.rootLayout.addView(cardTextLayer);
        cardTextLayer.setZ(20f); // 频谱(15)之上，墙 canvas 之上
        cardTextLayer.setVisibility(View.GONE);
    }

    /**
     * 文字层随展开卡同步：位置/字号/透明度/内容（卡矩形回调链每帧触发）。
     * 与频谱同域重叠——文字在上层（z 序），频谱铺在文字后方作为底纹。
     */
    private void syncCardText(LatticeWallView active) {
        if (cardTextLayer == null || active == null) return;
        float[] r = active.getExpandedCardRect();
        float t = active.getExpandProgress();
        if (r == null || t <= 0.55f) {
            cardTextLayer.setVisibility(View.GONE);
            return;
        }
        float cardX = r[0], cardY = r[1];
        float cardW = r[2] - r[0], cardH = r[3] - r[1];
        // 内容（去重，避免无关帧触发 TextView 重排版）
        int qi = active.getExpandedQueueIndex();
        // 进度线与频谱同色：tint 变化注入墙（无 tint 传 0 回落默认色）
        if (active == wall || active == wallL) {
            active.setSpectrumTint(tintTop, tintBottom);
        }
        if (lastPlaylist != null && qi >= 0 && qi < lastPlaylist.size()) {
            Song s = lastPlaylist.get(qi);
            String title = s.title != null ? s.title : "";
            String artist = s.artist != null ? s.artist : "";
            if (!title.equals(lastTitleSet)) { cardTitleTv.setText(title); lastTitleSet = title; }
            if (!artist.equals(lastArtistSet)) { cardArtistTv.setText(artist); lastArtistSet = artist; }
        }
        // 字号/颜色（像素级对齐旧 canvas 版公式；歌名缩小后=卡宽0.06）
        float titleSize = Math.max(14f, Math.min(30f, cardW * 0.06f));
        cardTitleTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize);
        cardTitleTv.setTextColor(0xFFFFFFFF);
        cardArtistTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize * 0.62f);
        cardArtistTv.setTextColor(0xB3FFFFFF);
        // 位置：与展开卡同矩形，底部内边距=进度线+间距（原 canvas 公式同源）
        float progLine = Math.max(3f, cardH * 0.006f);
        cardTextLayer.setPadding((int) (cardW * 0.05f), 0, (int) (cardW * 0.05f),
                (int) (progLine + cardH * 0.012f));
        android.widget.LinearLayout.LayoutParams alp =
                (android.widget.LinearLayout.LayoutParams) cardArtistTv.getLayoutParams();
        int artistGap = (int) (cardH * 0.012f);
        if (alp.topMargin != artistGap) {
            alp.topMargin = artistGap;
            cardArtistTv.setLayoutParams(alp);
        }
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) cardTextLayer.getLayoutParams();
        lp.leftMargin = (int) cardX;
        lp.topMargin = (int) cardY;
        lp.width = (int) cardW;
        lp.height = (int) cardH;
        cardTextLayer.setLayoutParams(lp);
        // 淡入随展开进度（原 canvas 公式同源）
        cardTextLayer.setAlpha(Math.min(1f, (t - 0.55f) / 0.35f));
        cardTextLayer.setVisibility(View.VISIBLE);
    }

    private void applyNightToWall() {
        boolean n = h.isNightMode;
        if (wall != null) wall.setNight(n);
        if (wallL != null) wallL.setNight(n);
    }

    // ===== 能力接口 =====

    @Override
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
        return !com.jingxin.jingxinmusic.view.SpectrumView.isOverlayStyle(spectrumStyle); // 环类不可用
    }

    @Override
    public boolean shouldRotateCover() { return false; }

    @Override
    public boolean needsReloadCover() { return true; }

    @Override
    public float getSpectrumHeightRatio() {
        return h.isLandscapeMode ? 0.08f : 0.10f;
    }
}
