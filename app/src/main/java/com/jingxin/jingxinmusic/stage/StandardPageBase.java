package com.jingxin.jingxinmusic.stage;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.util.BlurUtil;
import com.jingxin.jingxinmusic.util.CoverColorExtractor;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.view.AuroraGradientView;
import com.jingxin.jingxinmusic.view.ControlBar;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.PlayerTopBar;
import com.jingxin.jingxinmusic.view.ProgressPanel;
import com.jingxin.jingxinmusic.view.RotatingCoverView;
import com.jingxin.jingxinmusic.view.SongTitleBar;
import com.jingxin.jingxinmusic.view.SpectrumView;

/**
 * 标准页基座（无风格身份）
 *
 * 自 ClassicPageV2 拆基而来（原经典页身兼“风格+基座”二职，概念混叠）：
 * 本类只承载“一个标准风格页跑起来”的全部共享基建——双 XML inflate/组件绑定、
 * Hub 订阅挂卸、封面/频谱/歌词/控制条联动、背景四态、日夜染色、横竖屏布局公式；
 * 出厂默认形态=经典（不覆写 provideXxxLayout 即经典骨架），风格壳 ClassicPageV2
 * 仅是它的默认形态标签，唱机/轮播覆写布局与钩子后挂在本类之下。
 *
 * 骨架规格（像素级对齐旧经典页）：
 * - 竖屏：封面25%屏高+5.6%顶距、占位=顶距+封面+1.6%题间距、歌名字号0.048×屏宽
 * - 横屏：左65信息列/右35封面列、歌名top=顶栏高+8dp、封面=70%×右栏宽
 * - 顶栏全5键、7键控制条、圆环频谱覆盖封面、四态背景（默认/光晕×日/夜）
 * - 全部视图为页私有（两份 XML：竖/横独立布局，v2_ 前缀），零共享视图接触
 * - 横屏 65/35 分栏由 XML 固化，布局手术归零；位置变动只改本页 XML/公式，其他风格零感知
 */
public class StandardPageBase extends StagePage {

    protected Actions actions;

    // ===== 双 XML 根与视图 =====
    protected View portraitRoot;   // 竖屏布局根（默认=stage_page_classic_v2.xml，子类覆写 providePortraitLayout 换自己的）
    protected View landscapeRoot;  // 横屏布局根（默认=stage_page_classic_v2_land.xml）

    // 竖屏视图
    private AuroraGradientView aurora; private ImageView blurBg;
    private View lightMask, darkMask, hideTouchLayer;
    protected View coverPlaceholder; // 子类（轮播V2）按旧轮播占位公式重设高度
    protected FrameLayout coverArea;
    protected RotatingCoverView cover;
    protected SpectrumView spectrumRing;
    private LinearLayout infoFlow;
    protected SongTitleBar title; protected LyricView lyric;
    protected SpectrumView spectrum;
    private ProgressPanel progress; private ControlBar control;
    private PlayerTopBar topBar;

    // 横屏视图（_land 后缀 XML，独立实例）
    private AuroraGradientView auroraL; private ImageView blurBgL;
    private View lightMaskL, darkMaskL, hideTouchLayerL;
    protected FrameLayout coverCol;
    protected RotatingCoverView coverL;
    protected SpectrumView spectrumRingL;
    private LinearLayout infoFlowL;
    protected SongTitleBar titleL; protected LyricView lyricL;
    protected SpectrumView spectrumL; // 子类（轮播V2）横屏频谱高按旧公式 0.08H 重设
    private ProgressPanel progressL; private ControlBar controlL;
    private PlayerTopBar topBarL;

    // ===== 状态 =====
    private boolean installed = false;
    private PlayerStateHub hub;
    private PlayerStateHub.Observer stateObserver;
    private PlayerStateHub.ProgressObserver progressObserver;
    private PlayerStateHub.CoverObserver coverObserver;
    private PlayerStateHub.CoverColorObserver coverColorObserver;
    private PlayerStateHub.LyricObserver lyricObserver;

    private boolean night, auroraSkin;
    private boolean playing;
    private boolean controlsHidden = false; // 精简模式态（镜像旧页 controlsHidden）
    private boolean hasCoverImage = false;   // V2 blur 已有位图（默认封面也经 Hub 广播到此）
    protected boolean spectrumHidden = false;  // 频谱用户隐藏态（镜像旧实例）
    private int coverColorsTop, coverColorsBottom;

    // 频谱圆环圆心（layout 时算出，传给 ring 实例）
    private int ringCx, ringCy, ringSize;
    // 最近一次布局尺寸（applyThemeColors 里进度条缩放需要；子类歌词联动后重申几何）
    protected int lastWidth, lastHeight;

    public StandardPageBase(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }

    /** 桥类型为基接口 StagePage.Actions（沉降至 StagePage；同包继承链裸名自动解析） */
    public void setActions(Actions actions) { this.actions = actions; }

    // ===== install =====

    @Override
    public void install(LayoutInflater inflater, ViewGroup host) {
        if (installed) return;
        portraitRoot = inflater.inflate(providePortraitLayout(), host, false);
        landscapeRoot = inflater.inflate(provideLandscapeLayout(), host, false);
        host.addView(portraitRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(landscapeRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bindPortrait(inflater.getContext());
        bindLandscape(inflater.getContext());
        installed = true;
    }

    /** 竖屏布局资源：默认形态=经典骨架（风格壳 ClassicPageV2 即此出厂态；子类覆写指向自己的独立 XML，节点 id 须与经典版一致以复用父类绑定） */
    protected int providePortraitLayout() { return R.layout.stage_page_classic_v2; }

    /** 横屏布局资源：默认形态=经典骨架（子类覆写指向自己的独立 XML） */
    protected int provideLandscapeLayout() { return R.layout.stage_page_classic_v2_land; }

    private void bindPortrait(android.content.Context ctx) {
        aurora = portraitRoot.findViewById(R.id.v2_aurora);
        blurBg = portraitRoot.findViewById(R.id.v2_blur);
        lightMask = portraitRoot.findViewById(R.id.v2_light_mask);
        darkMask = portraitRoot.findViewById(R.id.v2_dark_mask);
        hideTouchLayer = portraitRoot.findViewById(R.id.v2_hide_touch_layer);
        coverArea = portraitRoot.findViewById(R.id.v2_cover_area);
        cover = portraitRoot.findViewById(R.id.v2_cover);
        spectrumRing = portraitRoot.findViewById(R.id.v2_spectrum_ring);
        coverPlaceholder = portraitRoot.findViewById(R.id.v2_cover_placeholder);
        infoFlow = portraitRoot.findViewById(R.id.v2_info_flow);
        title = portraitRoot.findViewById(R.id.v2_title);
        lyric = portraitRoot.findViewById(R.id.v2_lyric);
        spectrum = portraitRoot.findViewById(R.id.v2_spectrum);
        progress = portraitRoot.findViewById(R.id.v2_progress);
        control = portraitRoot.findViewById(R.id.v2_control);
        topBar = portraitRoot.findViewById(R.id.v2_top_bar);
        initCommon(ctx, false);
    }

    private void bindLandscape(android.content.Context ctx) {
        auroraL = landscapeRoot.findViewById(R.id.v2_aurora_land);
        blurBgL = landscapeRoot.findViewById(R.id.v2_blur_land);
        lightMaskL = landscapeRoot.findViewById(R.id.v2_light_mask_land);
        darkMaskL = landscapeRoot.findViewById(R.id.v2_dark_mask_land);
        hideTouchLayerL = landscapeRoot.findViewById(R.id.v2_hide_touch_layer_land);
        coverCol = landscapeRoot.findViewById(R.id.v2_cover_col_land);
        coverL = landscapeRoot.findViewById(R.id.v2_cover_land);
        spectrumRingL = landscapeRoot.findViewById(R.id.v2_spectrum_ring_land);
        infoFlowL = landscapeRoot.findViewById(R.id.v2_info_flow_land);
        titleL = landscapeRoot.findViewById(R.id.v2_title_land);
        lyricL = landscapeRoot.findViewById(R.id.v2_lyric_land);
        spectrumL = landscapeRoot.findViewById(R.id.v2_spectrum_land);
        progressL = landscapeRoot.findViewById(R.id.v2_progress_land);
        controlL = landscapeRoot.findViewById(R.id.v2_control_land);
        topBarL = landscapeRoot.findViewById(R.id.v2_top_bar_land);
        initCommon(ctx, true);
    }

    /** 竖/横各自实例的公共接线（染色/事件/模式监听） */
    private void initCommon(android.content.Context ctx, boolean land) {
        SongTitleBar t = land ? titleL : title;
        LyricView ly = land ? lyricL : lyric;
        SpectrumView sp = land ? spectrum : spectrumL;
        SpectrumView ring = land ? spectrumRingL : spectrumRing;
        ControlBar cb = land ? controlL : control;
        ProgressPanel pp = land ? progressL : progress;
        PlayerTopBar tb = land ? topBarL : topBar;
        View touchLayer = land ? hideTouchLayerL : hideTouchLayer;

        // 进度条拖动（三 V2 风格此前未接此监听——拖动无响应的根因）
        pp.setListener(new ProgressPanel.Listener() {
            @Override public void onSeekStart() { if (actions != null) actions.onSeekStart(); }
            @Override public void onSeekTo(int positionMs) { if (actions != null) actions.onSeekTo(positionMs); }
            @Override public void onSeekStop() { if (actions != null) actions.onSeekStop(); }
        });
        // 数据通道订阅：统一在 enter() 时 attach（exit 会 detach，横竖重入后必须重接）
        // 监听器（控制事件/点击）在 install 时一次性绑定，不随 exit 失效
        cb.setListener(new ControlBar.Listener() {
            @Override public void onPlayPause() { if (actions != null) actions.togglePlayPause(); notifyControlsActivity(); }
            @Override public void onPrevious() { if (actions != null) actions.playPrevious(); notifyControlsActivity(); }
            @Override public void onNext() { if (actions != null) actions.playNext(); notifyControlsActivity(); }
            @Override public void onPlayOrder() { if (actions != null) actions.togglePlayOrder(); notifyControlsActivity(); }
            @Override public void onHistory() { if (actions != null) actions.showHistoryDialog(); notifyControlsActivity(); }
            @Override public void onFavorite() {
                if (actions != null) {
                    actions.toggleFavorite();
                    syncControlExtras(); // 收藏态同步提交后刷新本页图标
                    notifyControlsActivity();
                }
            }
            @Override public void onDownload() { if (actions != null) actions.saveBiliOffline(); notifyControlsActivity(); }
        });

        // 顶栏事件（PlayerTopBar 统一回调）：返回/装扮/弹窗/动效键全桥到 Fragment
        tb.setListener(new PlayerTopBar.Listener() {
            @Override public void onBack() { if (actions != null) actions.exitPlayerPage(); }
            @Override public void onEffect(View anchor) { if (actions != null) actions.showEffectPickerPopup(anchor); }
            @Override public void onLyricEffect() { if (actions != null) actions.openLyricEffect(); }
            @Override public void onLyricSearch(View anchor) { if (actions != null) actions.showLyricSearchPopup(anchor); }
            @Override public void onSpectrum(View anchor, boolean longPress) {
                if (actions == null) return;
                if (longPress) {
                    // 长按=切换显示/隐藏（镜像旧顶栏行为）
                    if (!com.jingxin.jingxinmusic.fragment.SettingsFragment
                            .isSpectrumEnabled(anchor.getContext())) return;
                    spectrumHidden = !spectrumHidden;
                    applySpectrumVisibility();
                    return;
                }
                // 单击判定用本页用户隐藏标志 spectrumHidden（长按切换的就是它）。
                // 不能读 bottom 实例 isSpectrumVisible()：圆环样式时 applySpectrumVisibility
                // 会把 bottom 实例布局性 GONE，重写的 setVisibility 同步置 visible=false，
                // 误判成"用户隐藏"后走恢复分支空转，样式选择弹窗永不弹出（V2 圆环样式点击失效根因）
                if (!spectrumHidden) {
                    actions.showSpectrumPicker(anchor);
                } else {
                    spectrumHidden = false;
                    applySpectrumVisibility();
                }
            }
            @Override public void onOutfit() { if (actions != null) actions.switchOutfitStyle(); }
        });

        //歌词：数据经 Hub 广播灌入；点击切模式 → 本页联动（经典分支：toggleMode + 本页收敛）
        ly.setOnModeChangeListener(mode -> {
            applyLyricModeEffect(land);
            onLyricModeChanged(mode == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL);
        });
        ly.setOnClickListener(v -> {
            LyricView.DisplayMode m = ly.toggleMode();
            applyLyricModeEffect(land);
        });

        // 精简模式触摸层：点击恢复控制区（镜像旧 controlsHideTouchLayer 行为）
        touchLayer.setOnClickListener(v -> {
            if (controlsHidden) {
                applyControlsHidden(false);
                notifyControlsActivity();
            }
        });
    }

    private PlayerStateHub hubRef() {
        if (hub == null) hub = PlayerStateHub.get();
        return hub;
    }

    // ===== enter / exit =====

    /** 竖屏基态：进入时全部子视图归位（防止上方向形态残留） */
    private void bindPortraitState(boolean show) {
        coverArea.setVisibility(show ? View.VISIBLE : View.GONE);
        infoFlow.setVisibility(show ? View.VISIBLE : View.GONE);
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        // 歌词联动基态（FULL 时由 applyLyricModeEffect 重新收敛）
        if (show) applyLyricModeEffect(false);
    }

    /** 横屏基态 */
    private void bindLandscapeState(boolean show) {
        coverCol.setVisibility(show ? View.VISIBLE : View.GONE);
        infoFlowL.setVisibility(show ? View.VISIBLE : View.GONE);
        topBarL.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) applyLyricModeEffect(true);
    }

    @Override
    public void enter() {
        if (!installed) return;
        boolean landscape = h.isLandscapeMode;
        portraitRoot.setVisibility(landscape ? View.GONE : View.VISIBLE);
        landscapeRoot.setVisibility(landscape ? View.VISIBLE : View.GONE);

        subscribeHub();
        // 组件 Hub 重接（exit 时解绑；横竖重入 enter 后恢复订阅：进度/控制/标题）
        PlayerStateHub hb = hubRef();
        progress.attachHub(hb);
        progressL.attachHub(hb);
        control.attachHub(hb);
        controlL.attachHub(hb);
        title.attachHub(hb);
        titleL.attachHub(hb);
        syncControlExtras(); // 收藏态/下载键显隐随当前歌曲快照
        // 精简模式态镜像旧页（进 V2 前可能已隐藏；横竖重入保持当前态）
        if (actions != null) applyControlsHidden(actions.isControlsHidden());
        // 全量拉当前值灌入（切风格进入/横竖切换重入都走这里；hb 已声明于 attach 段）
        night = hb.isNightMode();
        auroraSkin = hb.getBgSkinMode() == 1;
        playing = hb.isPlaying();
        boolean hiddenOnEnter = h.spectrumView != null && !h.spectrumView.isSpectrumVisible();
        if (hiddenOnEnter != spectrumHidden) {
            spectrumHidden = hiddenOnEnter;
        }

        bindPortraitState(!landscape);
        bindLandscapeState(landscape);

        // 横竖切换重入：占位高度此刻可能按旧尺寸算（见 applyLyricModeEffect 守卫注释），
        // 布局收敛后下一帧以真实竖屏高度重申一次，保证标题落位封面下方
        if (!landscape) {
            portraitRoot.post(() -> {
                if (portraitRoot.getHeight() > 0
                        && portraitRoot.getVisibility() == View.VISIBLE) {
                    applyLyricModeEffect(false);
                }
            });
        }

        // 频谱样式/可见性镜像旧实例（切风格期间用户可能改过样式）
        mirrorSpectrumStyle();

        // 封面/歌词/双色快照补灌（无网络等待：切风格进 V2 立即显示当前已加载内容）
        if (hb.getCover() != null) {
            onCoverArrived(hb.getCover(), landscape);
        } else {
            cover.setImageResource(R.drawable.ic_music_icon);
            coverL.setImageResource(R.drawable.ic_music_icon);
        }
        if (hb.getLyricData() != null) {
            lyric.setLyricData(hb.getLyricData());
            lyricL.setLyricData(hb.getLyricData());
        }
        if (hb.hasCoverColors()) {
            onColorsArrived(hb.getCoverColorTop(), hb.getCoverColorBottom());
        }
        applyBackgroundState();
        applyPlayingState(playing);
        applyThemeColors(); // 进入即染色（背景四态之外的全部日夜色）
        night = hb.isNightMode(); // 兜底：防止后续新增字段遗漏同步
    }

    /** 精简模式（自动隐藏）应用：镜像旧 hideControls/showControls 的可见性效果（V2 实例） */
    public void applyControlsHidden(boolean hidden) {
        controlsHidden = hidden;
        int vis = hidden ? View.GONE : View.VISIBLE;
        for (ProgressPanel p : new ProgressPanel[]{progress, progressL}) p.setVisibility(vis);
        for (ControlBar c : new ControlBar[]{control, controlL}) c.setVisibility(vis);
        for (View l : new View[]{hideTouchLayer, hideTouchLayerL}) {
            l.setVisibility(hidden ? View.VISIBLE : View.GONE);
        }
        // 横屏：底部三件隐藏/恢复会改变上层分栏高度，封面居中基准（可视区）随之变化，
        // 请求重布局使 layoutLandscapeImpl 的 post 修正重跑；竖屏封面独立定位不受影响
        if (h.isLandscapeMode && landscapeRoot != null && landscapeRoot.getVisibility() == View.VISIBLE) {
            landscapeRoot.post(() -> {
                if (h.rootLayout.getWidth() > 0 && h.rootLayout.getHeight() > 0) {
                    layout(h.rootLayout.getWidth(), h.rootLayout.getHeight());
                }
            });
        }
    }

    /** 外部（Fragment 自动隐藏机制）驱动：隐藏/显示 V2 控制区 */
    public void setControlsHiddenFromHost(boolean hidden) {
        applyControlsHidden(hidden);
    }

    /** 控制条触摸活动上报（Fragment 重置自动隐藏计时） */
    private void notifyControlsActivity() {
        if (actions != null) actions.onV2ControlsTouched();
    }

    /** 控制条非订阅状态（收藏图标/下载键显隐）随快照同步 */
    private void syncControlExtras() {
        if (actions != null) {
            control.setFavorite(actions.isFavorite());
            controlL.setFavorite(actions.isFavorite());
            control.setDownloadVisible(actions.isBiliSong());
            controlL.setDownloadVisible(actions.isBiliSong());
            boolean dlEnabled = actions.isBiliDownloadEnabled();
            control.setDownloadEnabled(dlEnabled);
            controlL.setDownloadEnabled(dlEnabled);
        }
    }

    /** 外部（Fragment 收藏/下载态变化点）通知刷本页控制条附加态 */
    public void notifyControlExtrasChanged() {
        syncControlExtras();
    }

    @Override
    public void exit() {
        if (!installed) return;
        unsubscribeHub();
        // 解绑组件 Hub（防止后台仍刷新不可见实例）
        control.detachHub(); controlL.detachHub();
        progress.detachHub(); progressL.detachHub();
        title.detachHub(); titleL.detachHub();
        portraitRoot.setVisibility(View.GONE);
        landscapeRoot.setVisibility(View.GONE);
        cover.stopRotation(); coverL.stopRotation();
        spectrum.setPlaying(false); spectrumL.setPlaying(false);
        spectrumRing.setPlaying(false); spectrumRingL.setPlaying(false);
    }

    // ===== Hub 订阅 =====

    private void subscribeHub() {
        unsubscribeHub();
        PlayerStateHub hb = hubRef();
        stateObserver = state -> {
            boolean skinChanged = (state.bgSkinMode == 1) != auroraSkin;
            boolean nightChanged = state.nightMode != night;
            if (night != state.nightMode) { night = state.nightMode; }
            auroraSkin = state.bgSkinMode == 1;
            playing = state.playing;
            if (skinChanged || nightChanged) {
                applyBackgroundState();
                applyThemeColors(); // 皮肤切换也要重染：进度条圆球/已进度段/控制条/顶栏（光晕球色跟随文字色）
            }
            applyPlayingState(playing);
        };
        hb.addObserver(stateObserver);
        progressObserver = (pos, dur) -> {
            lyric.updatePosition(pos);
            lyricL.updatePosition(pos);
        };
        hb.addProgressObserver(progressObserver);
        coverObserver = bitmap -> onCoverArrived(bitmap, h.isLandscapeMode);
        hb.addCoverObserver(coverObserver);
        coverColorObserver = (top, bottom) -> onColorsArrived(top, bottom);
        hb.addCoverColorObserver(coverColorObserver);
        lyricObserver = data -> {
            lyric.setLyricData(data);
            lyricL.setLyricData(data);
        };
        hb.addLyricObserver(lyricObserver);
    }

    private void unsubscribeHub() {
        PlayerStateHub hb = hubRef();
        if (stateObserver != null) { hb.removeObserver(stateObserver); stateObserver = null; }
        if (progressObserver != null) { hb.removeProgressObserver(progressObserver); progressObserver = null; }
        if (coverObserver != null) { hb.removeCoverObserver(coverObserver); coverObserver = null; }
        if (coverColorObserver != null) { hb.removeCoverColorObserver(coverColorObserver); coverColorObserver = null; }
        if (lyricObserver != null) { hb.removeLyricObserver(lyricObserver); lyricObserver = null; }
    }

    // ===== 封面 / 双色 / 歌词到达 =====

    /** 封面到达（protected hook：唱片机子类覆盖以叠加黑胶形态） */
    protected void onCoverArrived(Bitmap bitmap, boolean landscape) {
        hasCoverImage = true;
        cover.setImageBitmap(bitmap);
        coverL.setImageBitmap(bitmap);
        RotatingCoverView.CoverStyle style = RotatingCoverView.CoverStyle.CIRCLE;
        cover.setStyle(style);
        coverL.setStyle(style);
        if (playing) { cover.startRotation(); coverL.startRotation(); }
        // 模糊背景：异步生成后由 applyBackgroundState 决定可见性/透明度（线程池借 Fragment executor）
        if (h.executor != null) {
            h.executor.execute(() -> {
                Bitmap blurred = BlurUtil.blur(portraitRoot.getContext(), bitmap, 10f);
                if (blurred != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        blurBg.setImageBitmap(blurred);
                        blurBgL.setImageBitmap(blurred);
                        applyBackgroundState();
                    });
                }
            });
        } else {
            applyBackgroundState();
        }
    }

    private void onColorsArrived(int top, int bottom) {
        coverColorsTop = top;
        coverColorsBottom = bottom;
        aurora.setColors(top, bottom);
        auroraL.setColors(top, bottom);
        if (auroraSkin) {
            int lyricColor = CoverColorExtractor.getLyricHighlightColor(bottom, false);
            lyric.setHighlightColor(lyricColor);
            lyricL.setHighlightColor(lyricColor);
            for (SpectrumView s : new SpectrumView[]{spectrum, spectrumL, spectrumRing, spectrumRingL}) {
                s.setTint(top, bottom);
            }
        } else {
            // 非光晕：频谱/歌词恢复默认配色（镜像旧退出光晕清理逻辑）
            for (SpectrumView s : new SpectrumView[]{spectrum, spectrumL, spectrumRing, spectrumRingL}) {
                s.clearTint();
            }
            lyric.restoreHighlightColor();
            lyricL.restoreHighlightColor();
        }
    }

    // ===== 四态背景（公式镜像 DefaultBackgroundSkin/AuroraBackgroundSkin 非沉浸分支）=====

    private void applyBackgroundState() {
        ImageView[] blurs = {blurBg, blurBgL};
        if (auroraSkin) {
            // 光晕非沉浸：光晕带底色全屏作为页面背景；模糊/遮罩全藏（AuroraBackgroundSkin:76-84）
            for (AuroraGradientView a : new AuroraGradientView[]{aurora, auroraL}) {
                a.setNightMode(night);
                a.setTransparentBackground(false);
                a.setVisibility(View.VISIBLE);
            }
            // 光晕双色已提取则注入（切歌间隙保持上一次色，无需等新取色）
            if (coverColorsTop != 0 || coverColorsBottom != 0) {
                aurora.setColors(coverColorsTop, coverColorsBottom);
                auroraL.setColors(coverColorsTop, coverColorsBottom);
            }
            for (ImageView b : blurs) b.setVisibility(View.GONE);
            setMasks(View.GONE, View.GONE);
        } else {
            // 默认皮肤非沉浸：白天=模糊0.5+白遮罩0.7；夜间=模糊0.6+nightOverlay 遮罩（DefaultBackgroundSkin:56-70）
            for (AuroraGradientView a : new AuroraGradientView[]{aurora, auroraL}) {
                a.setVisibility(View.GONE);
            }
            float blurAlpha = night ? 0.6f : 0.5f;
            int blurVis = hasCoverImage ? View.VISIBLE : View.GONE;
            for (ImageView b : blurs) {
                b.setAlpha(blurAlpha);
                b.setVisibility(blurVis);
            }
            if (night) {
                darkMask.setBackgroundColor(ThemeColors.nightOverlay());
                darkMaskL.setBackgroundColor(ThemeColors.nightOverlay());
                setMasks(View.GONE, View.VISIBLE);
            } else {
                setMasks(View.VISIBLE, View.GONE);
            }
        }
    }

    /** lightMask/darkMask 双方向可见性（轻遮罩为白底0.7，XML 固定；暗遮罩色由日夜决定） */
    private void setMasks(int lightVis, int darkVis) {
        lightMask.setVisibility(lightVis);
        lightMaskL.setVisibility(lightVis);
        darkMask.setVisibility(darkVis);
        darkMaskL.setVisibility(darkVis);
    }

    // ===== 主题染色（标题/歌词/频谱/控制条日夜色）=====

    private void applyPlayingState(boolean isPlaying) {
        spectrum.setPlaying(isPlaying);
        spectrumL.setPlaying(isPlaying);
        spectrumRing.setPlaying(isPlaying);
        spectrumRingL.setPlaying(isPlaying);
        if (isPlaying) {
            if (cover.getVisibility() == View.VISIBLE) cover.startRotation();
            if (coverL.getVisibility() == View.VISIBLE) coverL.startRotation();
        } else {
            cover.stopRotation();
            coverL.stopRotation();
        }
    }

    private void applyThemeColors() {
        int name = night ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();
        for (SongTitleBar t : new SongTitleBar[]{title, titleL}) {
            t.setTextColor(name);
        }
        LyricView.ThemeMode tm = night ? LyricView.ThemeMode.NIGHT : LyricView.ThemeMode.DAY;
        lyric.setThemeMode(tm);
        lyricL.setThemeMode(tm);
        spectrum.setNightMode(night);
        spectrumL.setNightMode(night);
        spectrumRing.setNightMode(night);
        spectrumRingL.setNightMode(night);
        // 以下镜像旧 applyButtonTheme/applyTextTheme（尾部分）——
        // 底部控制条 7 键（收藏红态优先）
        boolean fav = actions != null && actions.isFavorite();
        control.applyTheme(night, fav, ThemeColors.dayTextPrimary(), ThemeColors.FAVORITE_RED);
        controlL.applyTheme(night, fav, ThemeColors.dayTextPrimary(), ThemeColors.FAVORITE_RED);
        // 顶栏 6 键：夜间清滤镜，白天染深色（PlayerTopBar 统一实现）
        topBar.applyTheme(night, ThemeColors.dayTextPrimary());
        topBarL.applyTheme(night, ThemeColors.dayTextPrimary());
        // 进度时间文字 + 进度条圆球/轨道/已进度段（旧 applySeekBarTheme + applyProgressScaling 同源公式）
        progress.setTimeTextColor(name);
        progressL.setTimeTextColor(name);
        if (lastWidth > 0 && lastHeight > 0) {
            progress.applyTrackTheme(lastHeight, lastWidth, night, auroraSkin);
            progressL.applyTrackTheme(lastHeight, lastWidth, night, auroraSkin);
        }
        // 光晕高亮收敛（旧 updateThemeUI 尾部同款）：setThemeMode 会把高亮色重置为自定义色，
        // 光晕皮肤必须在其后重新覆盖为封面副色映射色，否则被洗掉
        if (auroraSkin && coverColorsBottom != 0) {
            int lyricColor = CoverColorExtractor.getLyricHighlightColor(coverColorsBottom, false);
            lyric.setHighlightColor(lyricColor);
            lyricL.setHighlightColor(lyricColor);
        }
    }

    // ===== 布局（公式与旧 ClassicPage 像素级同源）=====

    @Override
    public void layout(int width, int height) {
        if (!installed) return;
        lastWidth = width;
        lastHeight = height;
        boolean landscape = h.isLandscapeMode;
        if (portraitRoot.getVisibility() == View.VISIBLE && landscape) {
            portraitRoot.setVisibility(View.GONE);
            landscapeRoot.setVisibility(View.VISIBLE);
        } else if (landscapeRoot.getVisibility() == View.VISIBLE && !landscape) {
            landscapeRoot.setVisibility(View.GONE);
            portraitRoot.setVisibility(View.VISIBLE);
        }
        if (landscape) layoutLandscapeImpl(width, height);
        else layoutPortraitImpl(width, height);
        // 布局完成：尺寸就绪，补一次染色（首次 enter 时 lastHeight=0 跳过了进度条圆球/字号，导致首进黄色）
        applyThemeColors();
    }

    /** 竖屏：公式同旧 ClassicPage.layoutPortrait */
    private void layoutPortraitImpl(int width, int height) {
        // 顶栏（同 applyButtonMargins 顶栏段：topMargin=系统栏+1%屏高，按钮三档×aspect）
        topBar.applyGeometry(width, height, false, h.density, h.systemTopInset);
        control.applyGeometry(height, width, false);
        control.applyBottomMargin(Math.max(8, (int) (height * 0.01f)));

        // 标题：topMargin=1.6%屏高，字号 0.048×屏宽（歌手×0.7）
        title.applyScene(new SongTitleBar.TitleScene(
                width * 0.048f, 0.7f, (int) (height * 0.016f), true));

        // 封面：25%屏高，顶距 5.6%屏高+系统栏，居中
        int coverSize = (int) (height * 0.25f);
        int coverMarginTop = (int) (height * 0.056f) + h.systemTopInset;
        FrameLayout.LayoutParams coverParams = new FrameLayout.LayoutParams(coverSize, coverSize);
        coverParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        coverParams.topMargin = coverMarginTop;
        cover.setLayoutParams(coverParams);
        // 封面区容器：高度=顶距+封面（容器从页顶起，封面在容器内再下移顶距；
        // 此前漏加顶距导致封面底部被容器裁剪——竖屏圆形下缘截断的根因）
        FrameLayout.LayoutParams areaParams =
                (FrameLayout.LayoutParams) coverArea.getLayoutParams();
        areaParams.height = coverMarginTop + coverSize;
        coverArea.setLayoutParams(areaParams);
        ringCx = width / 2;
        ringCy = coverMarginTop + coverSize / 2;
        ringSize = coverSize;

        // 占位：顶距+封面+题间距（全屏歌词 GONE 由 applyLyricModeEffect 管）
        boolean isFull = lyric.getDisplayMode() == LyricView.DisplayMode.FULL;
        if (!isFull) {
            LinearLayout.LayoutParams ph = (LinearLayout.LayoutParams) coverPlaceholder.getLayoutParams();
            ph.height = coverMarginTop + coverSize + (int) (height * 0.016f);
            coverPlaceholder.setVisibility(View.VISIBLE);
            coverPlaceholder.setLayoutParams(ph);
        }

        // 频谱高度 10%屏高（底部态）
        android.view.ViewGroup.LayoutParams sp = spectrum.getLayoutParams();
        sp.height = (int) (height * 0.10f);
        spectrum.setLayoutParams(sp);

        applySpectrumRing();
    }

    /** 横屏：公式同旧 ClassicPage.layoutLandscape（分栏已固化 XML） */
    private void layoutLandscapeImpl(int width, int height) {
        topBarL.applyGeometry(width, height, true, h.density, h.systemTopInset);
        controlL.applyGeometry(height, width, true);
        controlL.applyBottomMargin(Math.max(8, (int) (height * 0.01f)));

        // 标题：topMargin = calcTopBarHeight + 8dp，字号 0.048×65%宽
        int infoWidth = (int) (width * 0.65f);
        int topBarH = h.calcTopBarHeight(height, width, true);
        titleL.applyScene(new SongTitleBar.TitleScene(
                infoWidth * 0.048f, 0.7f, topBarH + (int) (h.density * 8), true));

        // 封面：70%×右栏宽；居中基准=剔除顶栏后的可视区（顶栏悬浮在列上方，
        // 直接 gravity=center 会把顶栏占位也平均，导致封面视觉偏上约半个顶栏高）
        int rightPanelWidth = (int) (width * 0.35f);
        int coverSize = (int) (rightPanelWidth * 0.70f);
        int topBarBelow = topBarH + (int) (h.density * 8); // 顶栏下沿+呼吸间距
        FrameLayout.LayoutParams coverParams = new FrameLayout.LayoutParams(coverSize, coverSize);
        coverParams.gravity = Gravity.CENTER_HORIZONTAL;
        coverParams.topMargin = topBarBelow;
        coverL.setLayoutParams(coverParams);
        // 居中公式的 topMargin = 顶栏下沿 + (列高 - 顶栏下沿 - 封面高) / 2，但列高布局后才知，post 修正
        coverCol.post(() -> {
            int colH = coverCol.getHeight();
            if (colH > 0) {
                int margin = topBarBelow + Math.max(0, (colH - topBarBelow - coverSize) / 2);
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) coverL.getLayoutParams();
                if (p.topMargin != margin) {
                    p.topMargin = margin;
                    coverL.setLayoutParams(p);
                }
                // 圆环圆心同步封面真实中心
                int cx = coverCol.getWidth() / 2;
                int cy = margin + coverSize / 2;
                if (cx > 0 && cy > 0) {
                    ringCx = cx;
                    ringCy = cy;
                    applySpectrumRing();
                }
            }
        });

        // 频谱高度
        android.view.ViewGroup.LayoutParams sp = spectrumL.getLayoutParams();
        sp.height = (int) (height * 0.10f);
        spectrumL.setLayoutParams(sp);
        ringSize = coverSize;
        applySpectrumRing();

        // 全屏歌词时封面列仍显示（旧横屏行为：FULL 横屏留封面）
        coverL.setVisibility(View.VISIBLE);
        coverCol.setVisibility(View.VISIBLE);
    }

    /** 圆环/非圆环频谱定位：圆心传递 + 可见性统一收敛（applySpectrumVisibility） */
    private void applySpectrumRing() {
        spectrumRing.setCoverCenter(ringCx, ringCy, ringSize / 2f);
        spectrumRingL.setCoverCenter(ringCx, ringCy, ringSize / 2f);
        applySpectrumVisibility();
    }

    /**
     * 子类（封面位置与经典公式不同的风格，如唱机 armReserve 让位）重定位封面后，
     * 用真实封面中心同步圆环圆心；底部三件隐藏/恢复等布局变化时必须重申
     * （父类 layoutLandscapeImpl 的 post 只会按经典公式回写圆心，唱机封面被二次挪位后即脱节）。
     */
    protected void updateRingCenter(int cx, int cy, int size) {
        if (cx <= 0 || cy <= 0 || size <= 0) return;
        ringCx = cx;
        ringCy = cy;
        ringSize = size;
        applySpectrumRing();
    }

    // ===== 频谱数据管道 + 样式镜像（Fragment 桥接）=====

    /** 进入时镜像旧频谱实例的样式与隐藏态（防止切风格期间用户改过） */
    private void mirrorSpectrumStyle() {
        if (h.spectrumView == null) return;
        applySpectrumStyle(h.spectrumView.getCurrentStyle());
    }

    /** 样式广播：四实例同步 + 可见性重新收敛（Fragment 频谱选择器选择后桥调） */
    public void applySpectrumStyle(int style) {
        for (SpectrumView s : new SpectrumView[]{spectrum, spectrumL, spectrumRing, spectrumRingL}) {
            s.setStyleSilent(style);
        }
        applySpectrumVisibility();
    }

    /** 可见性分层：圆环样式→ring显/bottom藏；其他→bottom显/ring藏；均尊重用户隐藏态；竖屏全屏歌词时圆环随封面收起（旧 FULL 行为：底条保留） */
    private void applySpectrumVisibility() {
        boolean overlay = spectrum.isCoverOverlayMode();
        boolean fullPortrait = !h.isLandscapeMode && lyric != null
                && lyric.getDisplayMode() == LyricView.DisplayMode.FULL;
        boolean showBottom = !spectrumHidden;
        boolean showRing = overlay && !spectrumHidden && !fullPortrait;
        int bottomVis = (overlay || !showBottom) ? View.GONE : View.VISIBLE;
        int ringVis = showRing ? View.VISIBLE : View.GONE;
        spectrum.setVisibility(bottomVis);
        spectrumL.setVisibility(bottomVis);
        spectrumRing.setVisibility(ringVis);
        spectrumRingL.setVisibility(ringVis);
        if (showRing && playing) {
            SpectrumView active = h.isLandscapeMode ? spectrumRingL : spectrumRing;
            active.setPlaying(true);
        } else if (showBottom && playing) {
            SpectrumView active = h.isLandscapeMode ? spectrumL : spectrum;
            active.setPlaying(true);
        }
    }

    /** 频谱数据喂入：Visualizer/AudioRecord 回调桥（Fragment 数据点分流；仅喂当前方向活跃实例） */
    public void feedSpectrumData(float[] magnitudes, float maxMag) {
        boolean landscape = h.isLandscapeMode;
        SpectrumView bottom = landscape ? spectrumL : spectrum;
        SpectrumView ring = landscape ? spectrumRingL : spectrumRing;
        (bottom.isCoverOverlayMode() ? ring : bottom).updateDTFMagnitudes(magnitudes, maxMag);
    }

    // ===== 歌词模式联动（本页闭环，不动全局）=====

    /** 歌词点击切模式后的本页联动（复刻 ClassicPage.onLyricModeChanged 竖屏公式；子类可覆写） */
    protected void applyLyricModeEffect(boolean land) {
        // install 未完成守卫：Fragment 的 onLayoutChange 首帧可能早于页面 install 抢跑调用（NPE 防御）
        if (lyric == null || lyricL == null) return;
        LyricView ly = land ? lyricL : lyric;
        boolean isFull = ly.getDisplayMode() == LyricView.DisplayMode.FULL;
        if (isFull) {
            // 全屏：竖屏藏封面+占位+圆环；横屏留封面（旧横屏行为）
            if (!land) {
                cover.setVisibility(View.GONE);
                coverArea.setVisibility(View.GONE); // 整个封面区收起（含圆环槽，不再遮挡触摸）
                coverPlaceholder.setVisibility(View.GONE);
                spectrumRing.setVisibility(View.GONE);
            }
            title.setTitleVisible(false);
            titleL.setTitleVisible(false);
        } else {
            cover.setVisibility(View.VISIBLE);
            coverL.setVisibility(View.VISIBLE);
            if (!land) {
                coverArea.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams ph = (LinearLayout.LayoutParams) coverPlaceholder.getLayoutParams();
                // 尺寸取值守卫：横→竖切换瞬间 portraitRoot 刚转回 VISIBLE，getHeight() 还是
                // 横屏旧值（1080/0），占位据此算出后标题叠上封面——用布局流程写入的 lastHeight 兜底
                int height = portraitRoot.getHeight();
                if (height <= 0 || height > lastHeight) height = lastHeight;
                if (height > 0) {
                    int coverSize = (int) (height * 0.25f);
                    int coverMarginTop = (int) (height * 0.056f) + h.systemTopInset;
                    ph.height = coverMarginTop + coverSize + (int) (height * 0.016f);
                }
                coverPlaceholder.setVisibility(View.VISIBLE);
                coverPlaceholder.setLayoutParams(ph);
                applySpectrumVisibility(); // 圆环可见性按样式收敛（非圆环样式不得露出条形频谱）
            }
            title.setTitleVisible(true);
            titleL.setTitleVisible(true);
        }
    }

    // ===== 生命周期协议兼容（Fragment 调度用）=====

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        applyLyricModeEffect(h.isLandscapeMode);
    }

    @Override
    public void onPlayingStateChanged(boolean isPlaying) {
        playing = isPlaying;
        applyPlayingState(isPlaying);
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        onPlayingStateChanged(isPlaying);
    }

    @Override
    public void setCover(Bitmap bitmap) {
        if (bitmap != null) onCoverArrived(bitmap, h.isLandscapeMode);
    }

    @Override
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
        return !SpectrumView.isOverlayStyle(spectrumStyle)
                || SpectrumView.isOverlayStyle(spectrumStyle); // V2 支持全样式（圆环由 ring 实例承接）
    }

    @Override
    public boolean shouldRotateCover() { return true; }

    @Override
    public boolean needsReloadCover() { return true; }

    @Override
    public float getSpectrumHeightRatio() { return 0.10f; }

    @Override
    public void destroy() {
        if (!installed) return;
        unsubscribeHub();
        ViewGroup p = (ViewGroup) portraitRoot.getParent();
        if (p != null) p.removeView(portraitRoot);
        ViewGroup pl = (ViewGroup) landscapeRoot.getParent();
        if (pl != null) pl.removeView(landscapeRoot);
    }
}
