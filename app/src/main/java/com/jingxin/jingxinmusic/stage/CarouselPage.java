package com.jingxin.jingxinmusic.stage;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.view.CoverCarouselView;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.SpectrumView;

/**
 * 轮播风格页面（组件化第5步试点）
 *
 * 合并旧 PortraitCarouselScene + LandscapeCarouselScene 为单页面类：
 * - 自有三槽 XML（stage_page_carousel）：底槽（皮肤）+ 内容区（信息流）
 * - 共享组件借用：LyricView/SpectrumView/ProgressPanel/ControlBar 单实例
 *   re-parent 进入各自槽位（onBorrowComponents）
 * - 轮播卡片沿旧实现：ensureCarouselView 动态建 CoverCarouselView，
 *   叠加在内容区之上（FrameLayout 层序），尺寸/重叠率横竖屏自适应
 * - 布局公式从旧 Scene 原样迁移（横竖屏仅参数不同）
 */
public class CarouselPage extends StagePage {

    private FrameLayout pageRoot;
    private FrameLayout bottomSlot;
    private LinearLayout contentArea;
    private View coverPlaceholder;
    private FrameLayout lyricSlot;
    private FrameLayout spectrumSlot;
    private FrameLayout progressSlot;
    private FrameLayout controlSlot;

    private boolean installed = false;

    public CarouselPage(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }

    @Override
    public void install(LayoutInflater inflater, ViewGroup host) {
        if (installed) return;
        pageRoot = (FrameLayout) inflater.inflate(R.layout.stage_page_carousel, host, false);
        host.addView(pageRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bottomSlot = pageRoot.findViewById(R.id.carousel_bottom_slot);
        contentArea = pageRoot.findViewById(R.id.carousel_content_area);
        coverPlaceholder = pageRoot.findViewById(R.id.carousel_cover_placeholder);
        lyricSlot = pageRoot.findViewById(R.id.carousel_lyric_slot);
        spectrumSlot = pageRoot.findViewById(R.id.carousel_spectrum_slot);
        progressSlot = pageRoot.findViewById(R.id.carousel_progress_slot);
        controlSlot = pageRoot.findViewById(R.id.carousel_control_slot);
        installed = true;
    }

    @Override
    public void enter() {
        if (!installed) return;
        // 页面可见并接管内容区
        pageRoot.setVisibility(View.VISIBLE);
        // 旧 Scene 的关键行为迁移：
        // rootLayout 不裁剪子 View（轮播卡片两侧溢出可见）
        h.rootLayout.setClipChildren(false);
        h.rootLayout.setClipToPadding(false);
        h.hideImmersiveViews();
        h.overlayView.setVisibility(View.GONE);
        // 隐藏旋转封面（轮播不用）
        h.coverView.setVisibility(View.GONE);
        // 轮播卡片：动态创建（旧 ensureCarouselView），置于内容区之上
        h.ensureCarouselView();
        h.carouselView.setVisibility(View.VISIBLE);
        h.moveCarouselAboveInfoPanel();
        // 歌词：竖屏三行/横屏锁双行
        if (h.lyricView != null) {
            if (h.isLandscapeMode) {
                if (h.lyricView.getDisplayMode() != LyricView.DisplayMode.DOUBLE_LINE) {
                    h.lyricView.setDisplayMode(LyricView.DisplayMode.DOUBLE_LINE);
                }
                h.lyricView.setShowPrevLine(false);
            } else {
                h.lyricView.setShowPrevLine(true);
                if (h.lyricView.getDisplayMode() == LyricView.DisplayMode.FULL) {
                    h.lyricView.setDisplayMode(LyricView.DisplayMode.MULTI_LINE);
                }
            }
            h.callback.resetLyricMargin();
        }
        // 歌名歌手可见（共享 SongTitleBar 由宿主管理，仍走旧路径保持风格间一致）
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        // 圆形频谱降级为非圆形（轮播不支持封面圆形频谱）
        if (h.spectrumView != null && h.spectrumView.isCoverOverlayMode()) {
            while (h.spectrumView.isCoverOverlayMode()) {
                h.spectrumView.switchStyle();
            }
        }
        h.callback.updateThemeUI();
        h.carouselView.startSwayAnimation();
    }

    @Override
    public void onBorrowComponents() {
        if (!installed) return;
        // 共享单实例组件 re-parent 进页面槽位（状态无缝延续）
        reParent(h.lyricView, lyricSlot);
        reParent(h.progressLayout, progressSlot);
        reParent(h.controlButtons, controlSlot);
        // 频谱仅在底部样式时借用（圆形样式为 rootLayout 全屏层，不借）
        if (h.spectrumView != null && !h.spectrumView.isCoverOverlayMode()) {
            reParent(h.spectrumView, spectrumSlot);
        }
    }

    @Override
    public void exit() {
        if (!installed) return;
        // 与旧 exit() 对齐：停摆动画、藏卡片、恢复裁剪
        if (h.carouselView != null) {
            h.carouselView.stopSwayAnimation();
            h.carouselView.setVisibility(View.GONE);
        }
        h.rootLayout.setClipChildren(true);
        // 横屏残留的歌词约束清理（旧 LandscapeCarouselScene.exit 逻辑）
        if (h.lyricView != null && h.isLandscapeMode) {
            h.lyricView.setShowPrevLine(true);
            h.lyricView.setMaxTextSizeForHeight(Float.MAX_VALUE);
            h.lyricView.setMaxLyricAreaHeight(Float.MAX_VALUE);
            h.lyricView.clearSceneTextSizes();
        }
        pageRoot.setVisibility(View.GONE);
    }

    @Override
    public void layout(int width, int height) {
        if (!installed) return;
        boolean landscape = h.isLandscapeMode;
        // ===== 公共布局（公式自旧 Scene 原样迁移，横竖屏参数内聚） =====
        h.applyButtonMargins(height, width, landscape);
        h.setLayoutInfoPanelFullWidth();
        h.applyProgressScaling(height, width);

        // 顶部栏高度
        int topBarHeight = h.calcTopBarHeight(height, width, landscape);
        if (landscape) topBarHeight = Math.max(topBarHeight, (int) (height * 0.06f));

        // 歌名 topMargin（竖屏 26dp / 横屏 1.5%）
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) h.tvSongName.getLayoutParams();
        nameParams.topMargin = landscape
                ? Math.max(4, (int) (height * 0.015f))
                : (int) (h.density * 26);
        h.tvSongName.setLayoutParams(nameParams);
        h.tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
        h.tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);
        if (landscape && h.tvArtist != null) {
            LinearLayout.LayoutParams artistParams =
                    (LinearLayout.LayoutParams) h.tvArtist.getLayoutParams();
            artistParams.topMargin = Math.max(2, (int) (height * 0.01f));
            h.tvArtist.setLayoutParams(artistParams);
        }

        // 歌名字号（竖屏宽4.8% / 横屏受 65% 宽与 8% 高双约束）
        float songNameSize;
        if (landscape) {
            float infoWidth = width * 0.65f;
            songNameSize = Math.min(infoWidth * 0.048f, height * 0.08f * 0.55f);
        } else {
            songNameSize = width * 0.048f;
        }
        h.tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        h.tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize * 0.7f);

        // 歌词布局（横屏窄列+字号联动 / 竖屏全宽）
        if (h.lyricView != null) {
            LinearLayout.LayoutParams lyricParams =
                    (LinearLayout.LayoutParams) h.lyricView.getLayoutParams();
            if (landscape) {
                lyricParams.width = (int) (width * 0.65f);
                lyricParams.gravity = Gravity.CENTER_HORIZONTAL;
                int lyricTopMargin = Math.max(4, (int) (height * 0.015f));
                lyricParams.topMargin = lyricTopMargin;
                h.lyricView.setLayoutParams(lyricParams);
                float usedHeight = topBarHeight + height * 0.08f + lyricTopMargin
                        + height * 0.08f + height * 0.01f + height * 0.015f + height * 0.03f;
                float lyricAreaHeight = Math.max(40f, height - usedHeight);
                float lyricCurrentSize = songNameSize * 1.2f;
                float lyricNormalSize = songNameSize * 0.9f;
                float lyricLineSpacing = lyricNormalSize;
                float totalLyricH = lyricCurrentSize + lyricNormalSize + lyricLineSpacing;
                if (totalLyricH > lyricAreaHeight) {
                    lyricLineSpacing = Math.max(lyricNormalSize * 0.3f,
                            lyricAreaHeight - lyricCurrentSize - lyricNormalSize);
                    totalLyricH = lyricCurrentSize + lyricNormalSize + lyricLineSpacing;
                }
                if (totalLyricH > lyricAreaHeight) {
                    float scale = lyricAreaHeight / totalLyricH;
                    lyricCurrentSize *= scale;
                    lyricNormalSize *= scale;
                    lyricLineSpacing = lyricAreaHeight - lyricCurrentSize - lyricNormalSize;
                }
                h.lyricView.setSceneTextSizes(lyricCurrentSize, lyricNormalSize, lyricLineSpacing);
            } else {
                h.lyricView.setMaxTextSizeForHeight(Float.MAX_VALUE);
                h.lyricView.setMaxLyricAreaHeight(Float.MAX_VALUE);
                lyricParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
                lyricParams.gravity = Gravity.NO_GRAVITY;
                lyricParams.topMargin = (int) (h.density * 16);
                h.lyricView.setLayoutParams(lyricParams);
                h.lyricView.refreshTextSize();
            }
        }

        // 轮播卡片（竖屏卡片 H25%+16dp 容器 / 横屏 H28%）
        int coverSize = (int) (height * (landscape ? 0.28f : 0.25f));
        int carouselHeight = landscape ? coverSize : coverSize + (int) (h.density * 16);
        int carouselTopMargin = landscape ? topBarHeight
                : h.systemTopInset + topBarHeight + (int) (h.density * 10);
        if (h.carouselView != null) {
            h.carouselView.setCoverSize(coverSize);
            h.carouselView.setOverlapRatio(landscape ? 0.30f : 0.70f);
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) h.carouselView.getLayoutParams();
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = carouselHeight;
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            lp.topMargin = carouselTopMargin;
            h.carouselView.setLayoutParams(lp);
            h.carouselView.requestLayoutCards();
        }
        // 封面占位撑开歌名位置
        LinearLayout.LayoutParams placeholderParams =
                (LinearLayout.LayoutParams) coverPlaceholder.getLayoutParams();
        placeholderParams.height = carouselTopMargin + carouselHeight;
        placeholderParams.width = 1;
        coverPlaceholder.setLayoutParams(placeholderParams);

        // 频谱（底部条带；高度比例竖0.10/横0.08，横屏余量1%底距）
        if (h.spectrumView != null && !h.spectrumView.isCoverOverlayMode()) {
            float ratio = landscape ? 0.08f : 0.10f;
            LinearLayout.LayoutParams specParams =
                    (LinearLayout.LayoutParams) spectrumSlot.getLayoutParams();
            specParams.height = (int) (height * ratio);
            spectrumSlot.setLayoutParams(specParams);
            LinearLayout.LayoutParams viewParams =
                    (LinearLayout.LayoutParams) h.spectrumView.getLayoutParams();
            viewParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            viewParams.bottomMargin = landscape ? Math.max(2, (int) (height * 0.01f)) : 0;
            h.spectrumView.setLayoutParams(viewParams);
        }

        // 进度条横屏底距
        LinearLayout.LayoutParams progressParams =
                (LinearLayout.LayoutParams) progressSlot.getLayoutParams();
        progressParams.bottomMargin = landscape ? Math.max(2, (int) (height * 0.015f)) : 0;
        progressSlot.setLayoutParams(progressParams);
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        // 轮播不支持全屏歌词：竖屏降级多行，横屏锁双行
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        if (h.isLandscapeMode) {
            if (h.lyricView != null && h.lyricView.getDisplayMode() != LyricView.DisplayMode.DOUBLE_LINE) {
                h.lyricView.setDisplayMode(LyricView.DisplayMode.DOUBLE_LINE);
            }
        } else if (isFullScreen) {
            if (h.lyricView != null) {
                h.lyricView.setDisplayMode(LyricView.DisplayMode.MULTI_LINE);
            }
        }
        h.callback.resetLyricMargin();
    }

    // ===== 旧 Scene 兼容映射 =====

    @Override
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
        return !SpectrumView.isOverlayStyle(spectrumStyle);
    }

    @Override
    public boolean needsReloadCover() {
        return true; // 轮播需要专辑联动刷新
    }

    @Override
    public float getSpectrumHeightRatio() {
        return h.isLandscapeMode ? 0.08f : 0.10f;
    }

    // ===== 工具 =====

    /** 共享组件 re-parent 借入槽位（保留 LayoutParams 主体，宽高由槽位决定） */
    private static void reParent(View view, FrameLayout slot) {
        if (view == null || slot == null) return;
        if (view.getParent() == slot) return;
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        slot.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}
