package com.jingxin.jingxinmusic.stage;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.SpectrumView;

import java.util.ArrayList;
import java.util.List;

/**
 * 轮播风格页面（组件化第5步试点）
 *
 * 合并旧 PortraitCarouselScene + LandscapeCarouselScene 为单页面类。
 *
 * 借用边界（试点结论）：
 * - 借：底部三件（频谱/进度/控制条）——本就底钉布局，slot 承接 margin 语义，
 *   且借入后无任何代码对其做 LinearLayout 强转手术（margin 全部改在 slot 上）
 * - 不借：标题/歌词/旧 coverPlaceholder——与旧 info_panel 垂直流强耦合
 *  （占位高度、topMargin、字号联动的计算链），借用会破坏间距链，保持旧路径
 *
 * 归还协议：borrow 记账（原父容器+索引+原LayoutParams），exit() 逐一原位归还，
 * 保证切回旧 Scene 后全部布局手术的类型假设不变。
 */
public class CarouselPage extends StagePage {

    private FrameLayout pageRoot;
    private FrameLayout spectrumSlot;
    private FrameLayout progressSlot;
    private FrameLayout controlSlot;

    private boolean installed = false;

    /**
     * 歌词垫块：代替被借走的频谱/进度/控制条占据 info_panel 底部空间。
     * 原理：歌词 height=0dp+weight=1，旧结构里下方三个 wrap_content 组件压住剩余高度；
     * 借走后歌词成为唯一 weight 视图会弹满下半屏。垫块 weight 同为 1 均分剩余高度，
     * 歌词区回到歌手与底部簇之间的原位置（与旧版视觉等效）。
     */
    private View lyricSpacer;

    /** 借用记账：原父容器、原索引、原 LayoutParams（归还时原位恢复） */
    private static class BorrowRecord {
        final View view;
        final ViewGroup parent;
        final int index;
        final ViewGroup.LayoutParams params;
        BorrowRecord(View view, ViewGroup parent, int index, ViewGroup.LayoutParams params) {
            this.view = view; this.parent = parent; this.index = index; this.params = params;
        }
    }
    private final List<BorrowRecord> borrows = new ArrayList<>();

    public CarouselPage(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }

    @Override
    public void install(LayoutInflater inflater, ViewGroup host) {
        if (installed) return;
        pageRoot = (FrameLayout) inflater.inflate(R.layout.stage_page_carousel, host, false);
        host.addView(pageRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        spectrumSlot = pageRoot.findViewById(R.id.carousel_spectrum_slot);
        progressSlot = pageRoot.findViewById(R.id.carousel_progress_slot);
        controlSlot = pageRoot.findViewById(R.id.carousel_control_slot);
        installed = true;
    }

    @Override
    public void setCover(android.graphics.Bitmap bitmap) {
        // 轮播：封面沿旧模式仅做模糊背景（旧 AbstractCarouselScene.setCover 行为）
        h.applyBlurBackground(bitmap);
    }

    @Override
    public void enter() {
        if (!installed) return;
        pageRoot.setVisibility(View.VISIBLE);
        // 旧 Scene 关键行为迁移：
        // rootLayout 不裁剪子 View（轮播卡片两侧溢出可见）
        h.rootLayout.setClipChildren(false);
        h.rootLayout.setClipToPadding(false);
        h.hideImmersiveViews();
        h.overlayView.setVisibility(View.GONE);
        h.coverView.setVisibility(View.GONE);
        h.ensureCarouselView();
        h.carouselView.setVisibility(View.VISIBLE);
        h.moveCarouselAboveInfoPanel();
        // 歌名歌手可见（留在旧 info_panel 流，位置由 coverPlaceholder 撑开）
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        // 歌词模式：竖屏三行/横屏锁双行
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
        // 圆形频谱降级为非圆形
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
        // 阶段一：记录原始索引（全部基于同一布局快照）
        borrow(h.spectrumView, spectrumSlot,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        borrow(h.progressLayout, progressSlot,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        borrow(h.controlButtons, controlSlot,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 阶段二：频谱需单独入频谱槽，进度/控制各自入槽
        executeBorrowInto(spectrumSlot, h.spectrumView);
        executeBorrowInto(progressSlot, h.progressLayout);
        executeBorrowInto(controlSlot, h.controlButtons);
        installLyricSpacer();
    }

    /** 将指定 view 从记账集合对应的原父容器移除并放入指定槽位 */
    private void executeBorrowInto(FrameLayout slot, View view) {
        for (int i = borrows.size() - 1; i >= 0; i--) {
            BorrowRecord r = borrows.get(i);
            if (r.view == view) {
                r.parent.removeView(view);
                slot.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        view == h.spectrumView ? ViewGroup.LayoutParams.MATCH_PARENT
                                : ViewGroup.LayoutParams.WRAP_CONTENT));
                return;
            }
        }
    }

    /** 在旧 info_panel 歌词后插入垫块（原尺寸权重，透明不绘制） */
    private void installLyricSpacer() {
        if (lyricSpacer != null || h.lyricView == null) return;
        ViewGroup infoPanel = (ViewGroup) h.lyricView.getParent();
        if (infoPanel == null) return;
        lyricSpacer = new View(infoPanel.getContext());
        lyricSpacer.setVisibility(View.INVISIBLE); // 不绘制不拦截点击
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); // 与歌词同 weight 均分
        int idx = infoPanel.indexOfChild(h.lyricView) + 1;   // 紧贴歌词之后
        infoPanel.addView(lyricSpacer, idx, lp);
    }

    /** 移除垫块（归还同批执行） */
    private void removeLyricSpacer() {
        if (lyricSpacer == null) return;
        ViewGroup parent = (ViewGroup) lyricSpacer.getParent();
        if (parent != null) parent.removeView(lyricSpacer);
        lyricSpacer = null;
    }

    /**
     * 借入（两阶段）：先记录全部原始索引（同一布局快照），再统一移除。
     * 逐个移除时后续元素的 indexOfChild 会前移，导致归还索引错乱
     * （表现为频谱/进度/控制条顺序互换）。
     */
    private void borrow(View view, FrameLayout slot, int width, int height) {
        if (view == null || slot == null) return;
        if (view.getParent() == slot) return; // 已借入
        // 防重：同 view 旧账先移除（异常路径兜底）
        for (int i = borrows.size() - 1; i >= 0; i--) {
            if (borrows.get(i).view == view) borrows.remove(i);
        }
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent == null) return;
        borrows.add(new BorrowRecord(view, parent, parent.indexOfChild(view), view.getLayoutParams()));
    }

    /** 批量执行移除+入槽（onBorrowComponents 记录完后调用） */
    private void executeBorrow(FrameLayout slot, int width, int height) {
        for (int i = borrows.size() - 1; i >= 0; i--) {
            BorrowRecord r = borrows.get(i);
            r.parent.removeView(r.view);
            slot.addView(r.view, new FrameLayout.LayoutParams(width, height));
        }
    }

    /** 归还：按记账原父容器+索引+原 LayoutParams 恢复（升幂索引插入保持原顺序） */
    private void returnComponents() {
        // 先按原索引升序排序，保证多视图自同一父容器归还时顺序正确
        borrows.sort((a, b) -> Integer.compare(a.index, b.index));
        for (BorrowRecord r : borrows) {
            ViewGroup cur = (ViewGroup) r.view.getParent();
            if (cur != null) cur.removeView(r.view);
            if (r.index <= r.parent.getChildCount()) {
                r.parent.addView(r.view, r.index, r.params);
            } else {
                r.parent.addView(r.view, r.params);
            }
        }
        borrows.clear();
    }

    @Override
    public void exit() {
        if (!installed) return;
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
        returnComponents(); // 归还底部三件：旧 Scene 的布局手术类型假设恢复原样
        removeLyricSpacer(); // 同批移除歌词垫块，info_panel 恢复原结构
        pageRoot.setVisibility(View.GONE);
    }

    @Override
    public void layout(int width, int height) {
        if (!installed) return;
        boolean landscape = h.isLandscapeMode;
        // 歌词模式幂等适配（横竖屏切换不重走 enter，这里每次校正：
        // 竖屏三行且 FULL 降级 / 横屏锁双行）
        if (h.lyricView != null) {
            if (landscape) {
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
        }
        // ===== 顶部/标题/歌词/占位：完全走旧路径（公式原样迁移，视图留在 info_panel） =====
        h.applyButtonMargins(height, width, landscape); // 顶部栏+底部按钮尺寸（controlButtons 块已实例安全）
        h.setLayoutInfoPanelFullWidth();
        h.applyProgressScaling(height, width);          // thumb/时间字号（视图内部，父容器无关）

        int topBarHeight = h.calcTopBarHeight(height, width, landscape);
        if (landscape) topBarHeight = Math.max(topBarHeight, (int) (height * 0.06f));

        // 歌名 topMargin（竖屏 26dp / 横屏 1.5%）——tvSongName 父容器是 SongTitleBar（LinearLayout），未借用，安全
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

        float songNameSize;
        if (landscape) {
            songNameSize = Math.min(width * 0.65f * 0.048f, height * 0.08f * 0.55f);
        } else {
            songNameSize = width * 0.048f;
        }
        h.tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        h.tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize * 0.7f);

        // 歌词（未借用，LayoutParams 手术在旧 info_panel 流内，类型不变）——公式原样迁移
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

        // 轮播卡片（竖屏卡片 H25%+16dp 容器 / 横屏 H28%），卡片仍在 rootLayout
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
        // 旧 info_panel 的封面占位（撑开标题位置的机制保持旧版字节级一致）
        h.coverPlaceholder.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams placeholderParams =
                (LinearLayout.LayoutParams) h.coverPlaceholder.getLayoutParams();
        placeholderParams.height = (landscape ? topBarHeight : carouselTopMargin) + carouselHeight;
        placeholderParams.width = 1;
        h.coverPlaceholder.setLayoutParams(placeholderParams);

        // ===== 底部三件：margin 全部施加在槽位上（借入的组件不再动 LayoutParams） =====
        int controlBottomMargin = Math.max(8, (int) (height * 0.01f)); // 同 applyButtonMargins 底部按钮底距
        // 频谱槽：高度比例 竖0.10/横0.08，横屏额外 1% 底距（同旧 LandscapeCarouselScene）
        if (h.spectrumView != null && !h.spectrumView.isCoverOverlayMode()) {
            float ratio = landscape ? 0.08f : 0.10f;
            LinearLayout.LayoutParams specParams =
                    (LinearLayout.LayoutParams) spectrumSlot.getLayoutParams();
            specParams.height = (int) (height * ratio);
            specParams.bottomMargin = landscape ? Math.max(2, (int) (height * 0.01f)) : 0;
            spectrumSlot.setLayoutParams(specParams);
        }
        // 进度槽：横竖边距同旧 XML 值（start/end 16dp），底距 竖16dp/横 max(2,1.5%)
        LinearLayout.LayoutParams progressParams =
                (LinearLayout.LayoutParams) progressSlot.getLayoutParams();
        int progressSide = (int) (h.density * 16);
        progressParams.setMargins(progressSide, 0, progressSide,
                landscape ? Math.max(2, (int) (height * 0.015f)) : (int) (h.density * 16));
        progressSlot.setLayoutParams(progressParams);
        // 控制条槽：start/end 8dp，底距 max(8,1%)（同 applyButtonMargins 旧生效值）
        LinearLayout.LayoutParams controlParams =
                (LinearLayout.LayoutParams) controlSlot.getLayoutParams();
        int controlSide = (int) (h.density * 8);
        controlParams.setMargins(controlSide, 0, controlSide, controlBottomMargin);
        controlSlot.setLayoutParams(controlParams);
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
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
        return true;
    }

    @Override
    public float getSpectrumHeightRatio() {
        return h.isLandscapeMode ? 0.08f : 0.10f;
    }
}
