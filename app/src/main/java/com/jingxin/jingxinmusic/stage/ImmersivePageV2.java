package com.jingxin.jingxinmusic.stage;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.util.CoverColorExtractor;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.view.AuroraGradientView;
import com.jingxin.jingxinmusic.view.ControlBar;
import com.jingxin.jingxinmusic.view.ImmersiveOverlayView;
import com.jingxin.jingxinmusic.view.LandscapeGradientOverlay;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.PlayerTopBar;
import com.jingxin.jingxinmusic.view.ProgressPanel;
import com.jingxin.jingxinmusic.view.RotatingCoverView;
import com.jingxin.jingxinmusic.view.SongTitleBar;
import com.jingxin.jingxinmusic.view.SpectrumView;

/**
 * 沉浸风格页面 V2（第6风格，完全自持实例）
 *
 * 与旧 ImmersivePage 的区别：全部视图本页私有（双 XML：竖/横），零共享视图接触。
 * 行为公式逐行对齐旧页：
 * - 竖屏：封面原图 CENTER_CROP 铺顶部 43%；ImmersiveOverlayView 主色渐变遮罩；
 *   歌名推至遮罩区分界；歌词/频谱(仅底部样式)/进度/控制条常显
 * - 横屏：右 35% 全高矩形封面（不旋转）+ 前景边缘渐变 + 左侧渐变过渡层
 * - 沉浸限制：不支持全屏歌词（双行/多行互切）、圆环类频谱不可用
 * - 皮肤：非光晕=主色遮罩体系；光晕=光晕底色体系（遮罩/渐变全部取光晕底色）
 */
public class ImmersivePageV2 extends StagePage {

    // 动作桥走基接口 StagePage.Actions（沉降至基接口后，本页零依赖具体风格页）
    private StagePage.Actions actions;

    // ===== 双 XML 根 =====
    private View portraitRoot, landscapeRoot;

    // 竖屏
    private ImageView coverTop;            // 顶部43%封面原图层
    private ImmersiveOverlayView mask;     // 主色渐变遮罩（43%以下实底）
    private View dark, hideTouch, infoFlow;
    private AuroraGradientView aurora;
    private SongTitleBar title;
    private LyricView lyric;
    private SpectrumView spectrum;
    private ProgressPanel progress;
    private ControlBar control;
    private PlayerTopBar topBarView;

    // 横屏
    private AuroraGradientView auroraL;
    private ImmersiveOverlayView maskL;     // 横屏遮罩：左侧65%主色实底承担背景
    private LandscapeGradientOverlay gradientL;
    private View darkL, hideTouchL, infoFlowL;
    private FrameLayout coverCol;
    private RotatingCoverView coverL;      // 右35%全高矩形封面
    private SongTitleBar titleL;
    private LyricView lyricL;
    private SpectrumView spectrumL;
    private ProgressPanel progressL;
    private ControlBar controlL;
    private PlayerTopBar topBarViewL;

    // ===== 状态 =====
    private boolean installed = false;
    private PlayerStateHub hub;
    private PlayerStateHub.Observer stateObserver;
    private PlayerStateHub.ProgressObserver progressObserver;
    private PlayerStateHub.CoverObserver coverObserver;
    private PlayerStateHub.CoverColorObserver coverColorObserver;
    private PlayerStateHub.LyricObserver lyricObserver;

    private boolean night, auroraSkin, playing, controlsHidden;
    private boolean hasCoverImage = false;
    private boolean spectrumHidden = false;
    private int coverColorsTop, coverColorsBottom;
    /** 沉浸遮罩主色：Fragment 同款提取器产出（50×50采样、中心60%区域、忽暗忽亮像素）——
     * 与 Hub 双色（10×10色相分桶）是两个算法，夜间遮罩直接显主色，必须同源才像素级一致 */
    private int dominantColor = 0;
    private volatile android.graphics.Bitmap pendingExtractBitmap = null;
    private int lastWidth, lastHeight;

    public ImmersivePageV2(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }

    public void setActions(StagePage.Actions actions) { this.actions = actions; }

    // ===== install =====

    @Override
    public void install(LayoutInflater inflater, ViewGroup host) {
        if (installed) return;
        portraitRoot = inflater.inflate(R.layout.stage_page_immersive_v2, host, false);
        landscapeRoot = inflater.inflate(R.layout.stage_page_immersive_v2_land, host, false);
        host.addView(portraitRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(landscapeRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bindPortrait(inflater.getContext());
        bindLandscape(inflater.getContext());
        installed = true;
    }

    private void bindPortrait(android.content.Context ctx) {
        coverTop = portraitRoot.findViewById(R.id.iv2_cover_top);
        mask = portraitRoot.findViewById(R.id.iv2_mask);
        dark = portraitRoot.findViewById(R.id.iv2_dark);
        aurora = portraitRoot.findViewById(R.id.iv2_aurora);
        hideTouch = portraitRoot.findViewById(R.id.iv2_hide_touch);
        infoFlow = portraitRoot.findViewById(R.id.iv2_info);
        title = portraitRoot.findViewById(R.id.iv2_title);
        lyric = portraitRoot.findViewById(R.id.iv2_lyric);
        spectrum = portraitRoot.findViewById(R.id.iv2_spectrum);
        progress = portraitRoot.findViewById(R.id.iv2_progress);
        control = portraitRoot.findViewById(R.id.iv2_control);
        topBarView = portraitRoot.findViewById(R.id.iv2_top_bar);
        initCommon(ctx, false);
    }

    private void bindLandscape(android.content.Context ctx) {
        auroraL = landscapeRoot.findViewById(R.id.iv2_aurora_land);
        maskL = landscapeRoot.findViewById(R.id.iv2_mask_land);
        gradientL = landscapeRoot.findViewById(R.id.iv2_gradient_land);
        darkL = landscapeRoot.findViewById(R.id.iv2_dark_land);
        hideTouchL = landscapeRoot.findViewById(R.id.iv2_hide_touch_land);
        infoFlowL = landscapeRoot.findViewById(R.id.iv2_info_land);
        coverCol = landscapeRoot.findViewById(R.id.iv2_cover_col_land);
        coverL = landscapeRoot.findViewById(R.id.iv2_cover_land);
        titleL = landscapeRoot.findViewById(R.id.iv2_title_land);
        lyricL = landscapeRoot.findViewById(R.id.iv2_lyric_land);
        spectrumL = landscapeRoot.findViewById(R.id.iv2_spectrum_land);
        progressL = landscapeRoot.findViewById(R.id.iv2_progress_land);
        controlL = landscapeRoot.findViewById(R.id.iv2_control_land);
        topBarViewL = landscapeRoot.findViewById(R.id.iv2_top_bar_land);
        initCommon(ctx, true);
    }

    /** 竖/横公共接线（监听器只设一次：数据订阅统一在 enter） */
    private void initCommon(android.content.Context ctx, boolean land) {
        SongTitleBar t = land ? titleL : title;
        LyricView ly = land ? lyricL : lyric;
        SpectrumView sp = land ? spectrumL : spectrum;
        ControlBar cb = land ? controlL : control;
        ProgressPanel pp = land ? progressL : progress;
        PlayerTopBar tb = land ? topBarViewL : topBarView;
        View touchLayer = land ? hideTouchL : hideTouch;

        cb.setListener(new ControlBar.Listener() {
            @Override public void onPlayPause() { if (actions != null) actions.togglePlayPause(); notifyActivity(); }
            @Override public void onPrevious() { if (actions != null) actions.playPrevious(); notifyActivity(); }
            @Override public void onNext() { if (actions != null) actions.playNext(); notifyActivity(); }
            @Override public void onPlayOrder() { if (actions != null) actions.togglePlayOrder(); notifyActivity(); }
            @Override public void onHistory() { if (actions != null) actions.showHistoryDialog(); notifyActivity(); }
            @Override public void onFavorite() {
                if (actions != null) { actions.toggleFavorite(); syncControlExtras(); notifyActivity(); }
            }
            @Override public void onDownload() { if (actions != null) actions.saveBiliOffline(); notifyActivity(); }
        });

        tb.setListener(new PlayerTopBar.Listener() {
            @Override public void onBack() { if (actions != null) actions.exitPlayerPage(); }
            @Override public void onEffect(View anchor) { if (actions != null) actions.showEffectPickerPopup(anchor); }
            @Override public void onLyricEffect() { if (actions != null) actions.openLyricEffect(); }
            @Override public void onLyricSearch(View anchor) { if (actions != null) actions.showLyricSearchPopup(anchor); }
            @Override public void onSpectrum(View anchor, boolean longPress) {
                if (actions == null) return;
                SpectrumView active = land ? spectrumL : spectrum;
                if (longPress) {
                    active.toggleVisibility();
                    spectrumHidden = !active.isSpectrumVisible();
                    return;
                }
                if (active.isSpectrumVisible()) {
                    actions.showSpectrumPicker(anchor);
                } else {
                    active.toggleVisibility();
                    spectrumHidden = !active.isSpectrumVisible();
                }
            }
            @Override public void onOutfit() { if (actions != null) actions.switchOutfitStyle(); }
        });

        // 歌词：沉浸不支持全屏（双行↔多行互切），模式变化后本页收敛
        ly.setOnModeChangeListener(mode -> applyLyricModeConverge(land));
        ly.setOnClickListener(v -> {
            // 沉浸语义：双行↔多行
            LyricView.DisplayMode cur = ly.getDisplayMode();
            ly.setDisplayMode(cur == LyricView.DisplayMode.DOUBLE_LINE
                    ? LyricView.DisplayMode.MULTI_LINE : LyricView.DisplayMode.DOUBLE_LINE);
            applyLyricModeConverge(land);
        });

        // 精简模式触摸层：点击恢复
        touchLayer.setOnClickListener(v -> {
            if (controlsHidden) {
                applyControlsHidden(false);
                notifyActivity();
            }
        });
    }

    private PlayerStateHub hubRef() {
        if (hub == null) hub = PlayerStateHub.get();
        return hub;
    }

    private void notifyActivity() {
        if (actions != null) actions.onV2ControlsTouched();
    }

    // ===== enter / exit =====

    @Override
    public void enter() {
        if (!installed) return;
        boolean landscape = h.isLandscapeMode;
        portraitRoot.setVisibility(landscape ? View.GONE : View.VISIBLE);
        landscapeRoot.setVisibility(landscape ? View.VISIBLE : View.GONE);

        subscribeHub();
        PlayerStateHub hb = hubRef();
        // 组件订阅（exit 解绑；横竖重入后恢复）
        progress.attachHub(hb); progressL.attachHub(hb);
        control.attachHub(hb); controlL.attachHub(hb);
        title.attachHub(hb); titleL.attachHub(hb);
        syncControlExtras();

        night = hb.isNightMode();
        auroraSkin = hb.getBgSkinMode() == 1;
        playing = hb.isPlaying();
        if (actions != null) applyControlsHidden(actions.isControlsHidden());

        if (hb.getCover() != null) {
            onCoverArrived(hb.getCover());
        } else {
            coverTop.setImageResource(R.drawable.ic_music_icon);
            coverL.setImageResource(R.drawable.ic_music_icon);
        }
        if (hb.getLyricData() != null) {
            lyric.setLyricData(hb.getLyricData());
            lyricL.setLyricData(hb.getLyricData());
        }
        boolean hasColors = hb.hasCoverColors();
        if (hasColors) {
            coverColorsTop = hb.getCoverColorTop();
            coverColorsBottom = hb.getCoverColorBottom();
        }
        applyBackgroundState();
        applySpectrumTint();
        applyPlayingState(playing);
        applyThemeColors();
        applyLyricModeConverge(landscape);
    }

    @Override
    public void exit() {
        if (!installed) return;
        unsubscribeHub();
        control.detachHub(); controlL.detachHub();
        progress.detachHub(); progressL.detachHub();
        title.detachHub(); titleL.detachHub();
        portraitRoot.setVisibility(View.GONE);
        landscapeRoot.setVisibility(View.GONE);
        spectrum.setPlaying(false); spectrumL.setPlaying(false);
    }

    // ===== Hub 订阅 =====

    private void subscribeHub() {
        unsubscribeHub();
        PlayerStateHub hb = hubRef();
        stateObserver = state -> {
            boolean skinChanged = (state.bgSkinMode == 1) != auroraSkin;
            boolean nightChanged = state.nightMode != night;
            night = state.nightMode;
            auroraSkin = state.bgSkinMode == 1;
            playing = state.playing;
            if (skinChanged || nightChanged) {
                applyBackgroundState();
                applyThemeColors();
            }
            applyPlayingState(playing);
        };
        hb.addObserver(stateObserver);
        progressObserver = (pos, dur) -> {
            lyric.updatePosition(pos);
            lyricL.updatePosition(pos);
        };
        hb.addProgressObserver(progressObserver);
        coverObserver = this::onCoverArrived;
        hb.addCoverObserver(coverObserver);
        coverColorObserver = (top, bottom) -> {
            coverColorsTop = top;
            coverColorsBottom = bottom;
            applyBackgroundState();
            applySpectrumTint();
            applyLyricHighlight();
        };
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

    // ===== 封面到达 =====

    private void onCoverArrived(Bitmap bitmap) {
        hasCoverImage = true;
        coverTop.setImageBitmap(bitmap);
        coverTop.setVisibility(View.VISIBLE);
        coverL.setImageBitmap(bitmap);
        coverL.setStyle(RotatingCoverView.CoverStyle.RECT_BORDER_GRADIENT);
        // 遮罩主色提取（Fragment 同款算法，后台线程；返回后驱动背景重渲染）
        if (h.executor != null) {
            final Bitmap ref = bitmap;
            h.executor.execute(() -> {
                int c = extractDominantColor(ref);
                portraitRoot.post(() -> {
                    if (c != dominantColor) {
                        dominantColor = c;
                        applyBackgroundState();
                    }
                });
            });
        }
        applyBackgroundState();
    }

    /** 遮罩主色提取（与 PlayerFragment.extractDominantColor 同算法：
     *  50×50 采样，中心 60% 区域，忽略过暗/过亮像素，均值） */
    private int extractDominantColor(Bitmap bitmap) {
        try {
            int sampleSize = Math.max(1, Math.min(bitmap.getWidth(), bitmap.getHeight()) / 50);
            int w = bitmap.getWidth() / sampleSize;
            int h = bitmap.getHeight() / sampleSize;
            if (w < 1) w = 1;
            if (h < 1) h = 1;
            android.graphics.Bitmap sampled = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true);
            int[] pixels = new int[w * h];
            sampled.getPixels(pixels, 0, w, 0, 0, w, h);
            if (sampled != bitmap) sampled.recycle();
            int startX = w / 5, endX = w * 4 / 5, startY = h / 5, endY = h * 4 / 5;
            long rSum = 0, gSum = 0, bSum = 0, count = 0;
            for (int y = startY; y < endY; y++) {
                for (int x = startX; x < endX; x++) {
                    int pixel = pixels[y * w + x];
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    if (r + g + b > 60 && r + g + b < 720) {
                        rSum += r; gSum += g; bSum += b;
                        count++;
                    }
                }
            }
            if (count == 0) return ThemeColors.dominantColorFallback();
            return android.graphics.Color.rgb(
                    (int) (rSum / count), (int) (gSum / count), (int) (bSum / count));
        } catch (Exception e) {
            return ThemeColors.dominantColorFallback();
        }
    }

    // ===== 遮罩/渐变体系（非光晕=主色；光晕=光晕底色）=====

    /** 横屏渐变过渡层/封面边缘前景的取色（与 ImmersiveOverlayView.getOverlayColor 同公式：夜间=主色，白天=主色偏浅蓝50%）；光晕=光晕底色 */
    private int effectiveMaskColor() {
        if (auroraSkin) {
            return night ? AuroraGradientView.BG_NIGHT : AuroraGradientView.BG_DAY;
        }
        int dominant = dominantColor != 0 ? dominantColor : coverColorsTop;
        if (dominant == 0) {
            return night ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();
        }
        if (night) return 0xFF000000 | (dominant & 0x00FFFFFF);
        // 白天：主色向浅蓝偏移50%（唯一一次映射，与遮罩组件一致）
        int shiftTarget = ThemeColors.OVERLAY_DAY_SHIFT;
        int blueR = (shiftTarget >> 16) & 0xFF;
        int blueG = (shiftTarget >> 8) & 0xFF;
        int blueB = shiftTarget & 0xFF;
        int r = (int) ((dominant >> 16 & 0xFF) * 0.5f + blueR * 0.5f);
        int g = (int) ((dominant >> 8 & 0xFF) * 0.5f + blueG * 0.5f);
        int b = (int) ((dominant & 0xFF) * 0.5f + blueB * 0.5f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void applyBackgroundState() {
        if (auroraSkin) {
            // 光晕沉浸：横竖两套（镜像 AuroraBackgroundSkin 沉浸分支）
            int auroraBg = night ? AuroraGradientView.BG_NIGHT : AuroraGradientView.BG_DAY;
            portraitRoot.setBackgroundColor(auroraBg);
            landscapeRoot.setBackgroundColor(auroraBg);
            boolean hasColors = coverColorsTop != 0 || coverColorsBottom != 0;
            // 光晕 View：透明底整屏叠加（横屏在封面之上；竖屏在遮罩之上）
            for (AuroraGradientView a : new AuroraGradientView[]{aurora, auroraL}) {
                a.setNightMode(night);
                a.setTransparentBackground(true);
                a.setVisibility(View.VISIBLE);
                if (hasColors) a.setColors(coverColorsTop, coverColorsBottom);
            }
            // 遮罩（镜像旧分支差异）：横屏 GONE（光晕作氛围）；竖屏 VISIBLE auroraMode
            // （画光晕底色的 0~43% 渐变 + 43% 以下实底——这是竖屏封面上下渐隐的来源）
            maskL.setVisibility(View.GONE);
            mask.setAuroraMode(true);
            mask.setNightMode(night);
            mask.setLandscapeMode(false);
            mask.setFullScreenMode(false);
            mask.setDominantColor(dominantColor != 0 ? dominantColor : coverColorsTop);
            mask.setVisibility(View.VISIBLE);
            coverTop.setVisibility(View.VISIBLE);
        } else {
            // 默认皮肤：根底还原黑（避免光晕底色残留）
            portraitRoot.setBackgroundColor(android.graphics.Color.BLACK);
            landscapeRoot.setBackgroundColor(android.graphics.Color.BLACK);
            // 遮罩组件自己完成昼夜映射 —— 只传原始主色，组件内 getOverlayColor()
            // 唯一一次映射（夜间主色/白天偏蓝）；此前外面先映射一遍导致双重映射
            aurora.setVisibility(View.GONE);
            auroraL.setVisibility(View.GONE);
            mask.setAuroraMode(false);
            mask.setNightMode(night);
            mask.setLandscapeMode(false);
            mask.setDominantColor(dominantColor != 0 ? dominantColor : coverColorsTop);
            mask.setVisibility(View.VISIBLE);
            maskL.setAuroraMode(false);
            maskL.setNightMode(night);
            maskL.setLandscapeMode(true);
            maskL.setDominantColor(dominantColor != 0 ? dominantColor : coverColorsTop);
            maskL.setVisibility(View.VISIBLE);
        }
        // 横屏渐变过渡层：与遮罩同款映射色（夜间光晕/非光晕用）
        int gradColor = effectiveMaskColor();
        if (gradientL != null) {
            gradientL.setOverlayColor(gradColor);
            gradientL.setSolidLeft(!auroraSkin);
            // 白天光晕：静态渐变带整体退役（GONE）——静态色追不上逐帧光晕动画是白线病根；
            // 封面左缘 4% alpha 淡出透出「页面底色+动态光晕」接管过渡（见下方 edgeFade）。
            // 夜间光晕/非光晕保持原带原路径零改动。
            gradientL.setVisibility(auroraSkin && !night ? View.GONE : View.VISIBLE);
        }
        // 封面左缘淡出：仅白天光晕横屏开（4%），其余模式恒关（竖屏封面 coverTop 不参与）
        float edgeFade = (auroraSkin && !night) ? 0.04f : 0f;
        if (coverL != null) coverL.setLeftFade(h.isLandscapeMode ? edgeFade : 0f);
        // 夜间暗层（两皮肤通用：夜间补压一层黑；白天隐藏）
        dark.setVisibility(night ? View.VISIBLE : View.GONE);
        darkL.setVisibility(night ? View.VISIBLE : View.GONE);
    }

    // ===== 频谱 =====

    private void applyPlayingState(boolean isPlaying) {
        spectrum.setPlaying(isPlaying);
        spectrumL.setPlaying(isPlaying);
    }

    private void applySpectrumTint() {
        if (auroraSkin && coverColorsBottom != 0) {
            spectrum.setTint(coverColorsTop, coverColorsBottom);
            spectrumL.setTint(coverColorsTop, coverColorsBottom);
        } else {
            spectrum.clearTint();
            spectrumL.clearTint();
        }
    }

    /** 歌词高亮色：光晕=副色映射（QQ 固定深色，同旧 getLyricHighlightColor(bottom, false)）；非光晕=恢复自定义色 */
    private void applyLyricHighlight() {
        if (auroraSkin && coverColorsBottom != 0) {
            int lyricColor = CoverColorExtractor.getLyricHighlightColor(coverColorsBottom, false);
            lyric.setHighlightColor(lyricColor);
            lyricL.setHighlightColor(lyricColor);
        } else {
            lyric.restoreHighlightColor();
            lyricL.restoreHighlightColor();
        }
    }

    /** 频谱可见性（沉浸仅底部样式；尊重用户隐藏态） */
    private void applySpectrumVisibility() {
        boolean show = !spectrumHidden;
        spectrum.setVisibility(show ? View.VISIBLE : View.GONE);
        spectrumL.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && playing) {
            (h.isLandscapeMode ? spectrumL : spectrum).setPlaying(true);
        }
    }

    /** 频谱样式广播（Fragment 桥调：样式选择器选择后同步；圆环类被 shouldShow 过滤） */
    public void applySpectrumStyle(int style) {
        // 圆环类样式在沉浸下不可用（Fragment 侧禁用项），到此处均为底部样式
        spectrum.setStyleSilent(style);
        spectrumL.setStyleSilent(style);
        applySpectrumVisibility();
    }

    /** 频谱数据喂入（Visualizer/AudioRecord 桥） */
    public void feedSpectrumData(float[] magnitudes, float maxMag) {
        (h.isLandscapeMode ? spectrumL : spectrum).updateDTFMagnitudes(magnitudes, maxMag);
    }

    // ===== 染色 =====

    private void applyThemeColors() {
        int name = night ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();
        title.setTextColor(name);
        titleL.setTextColor(name);
        lyric.setThemeMode(night ? LyricView.ThemeMode.NIGHT : LyricView.ThemeMode.DAY);
        lyricL.setThemeMode(night ? LyricView.ThemeMode.NIGHT : LyricView.ThemeMode.DAY);
        spectrum.setNightMode(night);
        spectrumL.setNightMode(night);
        boolean fav = actions != null && actions.isFavorite();
        control.applyTheme(night, fav, ThemeColors.dayTextPrimary(), ThemeColors.FAVORITE_RED);
        controlL.applyTheme(night, fav, ThemeColors.dayTextPrimary(), ThemeColors.FAVORITE_RED);
        // 顶栏 6 键：夜间清滤镜，白天染深色（PlayerTopBar 统一实现）
        topBarView.applyTheme(night, ThemeColors.dayTextPrimary());
        topBarViewL.applyTheme(night, ThemeColors.dayTextPrimary());
        progress.setTimeTextColor(name);
        progressL.setTimeTextColor(name);
        if (lastWidth > 0 && lastHeight > 0) {
            progress.applyTrackTheme(lastHeight, lastWidth, night, auroraSkin);
            progressL.applyTrackTheme(lastHeight, lastWidth, night, auroraSkin);
        }
        // 光晕高亮收敛（旧 updateThemeUI 尾部同款）：setThemeMode 会重置为自定义高亮色，
        // 光晕皮肤必须在其后覆盖为封面副色映射色（applyLyricHighlight 不受此影响的保底）
        applyLyricHighlight();
    }

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

    // ===== 精简模式 =====

    public void applyControlsHidden(boolean hidden) {
        controlsHidden = hidden;
        int vis = hidden ? View.GONE : View.VISIBLE;
        progress.setVisibility(vis); progressL.setVisibility(vis);
        control.setVisibility(vis); controlL.setVisibility(vis);
        hideTouch.setVisibility(hidden ? View.VISIBLE : View.GONE);
        hideTouchL.setVisibility(hidden ? View.VISIBLE : View.GONE);
        if (hidden) {
            // 隐藏后歌词区自动撑满（weight 因 GONE 自动让位）
            applyLyricModeConverge(h.isLandscapeMode);
        }
    }

    public void setControlsHiddenFromHost(boolean hidden) {
        applyControlsHidden(hidden);
    }

    // ===== 布局 =====

    @Override
    public void layout(int width, int height) {
        if (!installed) return;
        lastWidth = width; lastHeight = height;
        boolean landscape = h.isLandscapeMode;
        if (portraitRoot.getVisibility() == View.VISIBLE && landscape) {
            portraitRoot.setVisibility(View.GONE);
            landscapeRoot.setVisibility(View.VISIBLE);
        } else if (landscapeRoot.getVisibility() == View.VISIBLE && !landscape) {
            landscapeRoot.setVisibility(View.GONE);
            portraitRoot.setVisibility(View.VISIBLE);
        } else if (portraitRoot.getVisibility() != View.VISIBLE
                && landscapeRoot.getVisibility() != View.VISIBLE) {
            // 兑底：两 root 均不可见（分屏 resize 与场景重入交错时可现的中间态），
            // 按当前方向恢复一个——否则页面停在宿主黑底，等下次横竖翻转才自愈
            if (landscape) {
                landscapeRoot.setVisibility(View.VISIBLE);
            } else {
                portraitRoot.setVisibility(View.VISIBLE);
            }
        }
        if (landscape) layoutLandscapeImpl(width, height);
        else layoutPortraitImpl(width, height);
        applyThemeColors(); // 尺寸就绪补染色（首进轨道/字号）
    }

    /** 竖屏：43% 封面层 + 标题推至遮罩分界（公式对齐旧 updateImmersiveLyricMargin） */
    private void layoutPortraitImpl(int width, int height) {
        topBarView.applyGeometry(width, height, false, h.density, h.systemTopInset);
        control.applyGeometry(height, width, false);
        control.applyBottomMargin(Math.max(8, (int) (height * 0.01f)));

        // 顶部封面层：一律 43%（与旧版 applyPortraitImmersiveCoverHeight 逐字对齐，
        // 不分皮肤）。此前误加 auroraSkin 全屏铺底分支（曲解旧"光晕=blur全屏"语义——
        // 那是旧页共享 blurBackground 的行为；V2 光晕由 AuroraGradientView 自任背景，
        // 封面永远只在顶部 43% 区域 centerCrop，越界即被子层遮挡掩盖+无谓绘制）
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) coverTop.getLayoutParams();
        cp.height = (int) (height * 0.43f);
        coverTop.setLayoutParams(cp);

        // 标题推至遮罩实底分界：43%
        int maskDivider = (int) (height * 0.43f);
        title.applyScene(new SongTitleBar.TitleScene(
                width * 0.048f, 0.7f, maskDivider, true));
        mask.setFullScreenMode(false);
        mask.setLandscapeMode(false);

        // 频谱高度 10%
        android.view.ViewGroup.LayoutParams sp = spectrum.getLayoutParams();
        sp.height = (int) (height * 0.10f);
        spectrum.setLayoutParams(sp);
        applySpectrumVisibility();
    }

    /** 横屏：65/35 分栏（XML 固化）+ 标题公式 + 频谱 */
    private void layoutLandscapeImpl(int width, int height) {
        topBarViewL.applyGeometry(width, height, true, h.density, h.systemTopInset);
        controlL.applyGeometry(height, width, true);
        controlL.applyBottomMargin(Math.max(8, (int) (height * 0.01f)));

        int infoWidth = (int) (width * 0.55f);
        int topBarH = h.calcTopBarHeight(height, width, true);
        titleL.applyScene(new SongTitleBar.TitleScene(
                infoWidth * 0.048f, 0.7f, topBarH + (int) (h.density * 8), true));

        // 封面列：全高跨屏（页面根子级），宽 45% 屏宽右贴边（旧版 35%，加宽封面）；旧 applyLandscapeImmersiveCover 同源
        int coverWidth = (int) (width * 0.45f);
        FrameLayout.LayoutParams ccP = (FrameLayout.LayoutParams) coverCol.getLayoutParams();
        ccP.width = coverWidth;
        ccP.height = FrameLayout.LayoutParams.MATCH_PARENT;
        ccP.gravity = Gravity.END | Gravity.TOP;
        ccP.rightMargin = 0;
        ccP.topMargin = 0;
        coverCol.setLayoutParams(ccP);
        // 信息流列限宽 65%（竖直堆叠父容器中，避免歌词/底部三件横跨到封面下）
        LinearLayout.LayoutParams ip = (LinearLayout.LayoutParams) infoFlowL.getLayoutParams();
        ip.width = infoWidth;
        ip.gravity = Gravity.START;
        infoFlowL.setLayoutParams(ip);

        mask.setLandscapeMode(true);
        coverL.setVisibility(View.VISIBLE);

        android.view.ViewGroup.LayoutParams sp = spectrumL.getLayoutParams();
        sp.height = (int) (height * 0.10f);
        spectrumL.setLayoutParams(sp);
        applySpectrumVisibility();
        // 白天光晕：进横屏重申封面左缘淡出（皮肤态不变时 applyBackgroundState 不重跑）
        if (coverL != null) coverL.setLeftFade(auroraSkin && !night ? 0.04f : 0f);
    }

    // ===== 歌词模式收敛（沉浸：双行↔多行，不进全屏） =====

    private void applyLyricModeConverge(boolean land) {
        LyricView ly = land ? lyricL : lyric;
        boolean isMulti = ly.getDisplayMode() != LyricView.DisplayMode.DOUBLE_LINE;
        // 沉浸不进全屏；多行时遮罩仍保持遮罩区模式
        mask.setFullScreenMode(false);
        title.setTitleVisible(true);
        titleL.setTitleVisible(true);
        // 频谱可见性不变（沉浸底部常显）
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        // 沉浸不支持全屏（进入风格前 Fragment 已降级为多行；此处兜底收敛）
        applyLyricModeConverge(h.isLandscapeMode);
    }

    // ===== 生命周期兼容 =====

    @Override
    public void onPlayingStateChanged(boolean isPlaying) {
        playing = isPlaying;
        applyPlayingState(isPlaying);
        applySpectrumVisibility();
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        onPlayingStateChanged(isPlaying);
    }

    @Override
    public void setCover(Bitmap bitmap) {
        if (bitmap != null) onCoverArrived(bitmap);
    }

    @Override
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
        return !SpectrumView.isOverlayStyle(spectrumStyle); // 圆环类不可用
    }

    @Override
    public boolean shouldRotateCover() { return false; }

    @Override
    public boolean needsReloadCover() { return true; }

    @Override
    public float getSpectrumHeightRatio() { return 0.10f; }

    @Override
    public float getInfoPanelWidthRatio() {
        return h.isLandscapeMode ? 0.55f : 1.0f;
    }

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
