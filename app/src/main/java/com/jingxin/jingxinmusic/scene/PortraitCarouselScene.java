package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.view.CoverCarouselAdapter;
import com.jingxin.jingxinmusic.view.CoverCarouselView;

/**
 * 竖屏多封面轮播模式
 * - 封面：5张卡片叠加，中间最大，两侧递减，可滑动翻页
 * - 横竖屏结构统一：封面在上方，info_panel 全宽
 * - 歌词：仅双行/多行，无全屏模式
 * - 频谱：仅底部柱状/波浪，禁用圆形频谱
 */
public class PortraitCarouselScene implements CoverScene {

    private final CoverSceneHelper h;

    public PortraitCarouselScene(CoverSceneHelper helper) {
        this.h = helper;
    }

    @Override
    public void enter() {
        // 隐藏沉浸相关 View
        h.immersiveOverlay.setVisibility(View.GONE);
        h.immersiveDarkOverlay.setVisibility(View.GONE);
        if (h.landscapeGradientOverlay != null && h.landscapeGradientOverlay.getParent() != null) {
            h.rootLayout.removeView(h.landscapeGradientOverlay);
        }
        // 非沉浸遮罩
        h.overlayView.setVisibility(View.GONE);
        h.whiteOverlay.setVisibility(View.VISIBLE);
        h.whiteOverlay.setAlpha(0.4f);
        // 关键：rootLayout 不裁剪子View，叠加溢出可见
        h.rootLayout.setClipChildren(false);
        h.rootLayout.setClipToPadding(false);
        // 隐藏旋转封面，显示轮播封面
        h.coverView.setVisibility(View.GONE);
        ensureCarouselView();
        h.carouselView.setVisibility(View.VISIBLE);
        // 显示封面占位（轮播模式需要占位撑开歌名位置）
        h.coverPlaceholder.setVisibility(View.VISIBLE);
        // 歌名歌手
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        h.callback.resetLyricMargin();
        // 恢复封面层级（carousel 在 infoPanel 上方）
        moveCarouselAboveInfoPanel();
        // 恢复主题
        h.callback.updateThemeUI();
        // 竖屏轮播：恢复歌词显示上一行（三行模式），确保不为全屏
        if (h.lyricView != null) {
            h.lyricView.setShowPrevLine(true);
            com.jingxin.jingxinmusic.view.LyricView.DisplayMode mode = h.lyricView.getDisplayMode();
            if (mode == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL) {
                h.lyricView.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.MULTI_LINE);
                h.callback.resetLyricMargin();
            }
        }
    }

    @Override
    public void layout(int width, int height) {
        // 顶部/底部按钮间距
        h.applyButtonMargins(height, width, false);
        // info_panel 全宽（轮播模式横竖屏统一）
        FrameLayout.LayoutParams infoParams =
                (FrameLayout.LayoutParams) h.infoPanel.getLayoutParams();
        infoParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.gravity = Gravity.START;
        h.infoPanel.setLayoutParams(infoParams);
        if (h.infoPanel instanceof LinearLayout) {
            ((LinearLayout) h.infoPanel).setGravity(Gravity.CENTER_HORIZONTAL);
        }
        // 歌名 topMargin = 16dp
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) h.tvSongName.getLayoutParams();
        nameParams.topMargin = (int) (h.density * 26);
        h.tvSongName.setLayoutParams(nameParams);
        h.tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
        h.tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);
        // 歌名字号
        float songNameSize = Math.max(32f, Math.min(60f, width * 0.048f));
        h.tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        h.tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize * 0.7f);
        // 轮播封面区域：在顶部按钮栏下方
        int topBarHeight = (h.topButtonsBar != null && h.topButtonsBar.getHeight() > 0)
                ? h.topButtonsBar.getHeight() : (int) (h.density * 56);
        // 中间卡片尺寸：和经典模式封面一致，height * 25%
        int coverSize = (int) (height * 0.25f);
        // 轮播容器高度 = coverSize + 上下边距
        int carouselHeight = coverSize + (int) (h.density * 16);
        if (h.carouselView != null) {
            h.carouselView.setCoverSize(coverSize);
            h.carouselView.setOverlapRatio(0.50f);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.carouselView.getLayoutParams();
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = carouselHeight;
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            lp.topMargin = topBarHeight + (int) (h.density * 10);
            h.carouselView.setLayoutParams(lp);
            // 通知 carouselView 重新布局卡片
            h.carouselView.requestLayoutCards();
        }
        // 封面占位：紧贴轮播容器底部，不留额外间距
        h.coverPlaceholder.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams placeholderParams =
                (LinearLayout.LayoutParams) h.coverPlaceholder.getLayoutParams();
        placeholderParams.height = topBarHeight + (int) (h.density * 10) + carouselHeight;
        placeholderParams.width = 1;
        h.coverPlaceholder.setLayoutParams(placeholderParams);
        // 频谱位置：仅底部，不用圆形
        if (h.spectrumView != null && h.spectrumView.getParent() == h.rootLayout) {
            h.moveSpectrumToBottom();
        }
        if (h.spectrumView != null) {
            h.spectrumView.getLayoutParams().height = (int) (height * getSpectrumHeightRatio());
        }
    }

    @Override
    public void exit() {
        // 隐藏轮播封面
        if (h.carouselView != null) {
            h.carouselView.setVisibility(View.GONE);
        }
        // 恢复裁剪（其他模式不需要溢出）
        h.rootLayout.setClipChildren(true);
    }

    @Override
    public void setCover(Bitmap bitmap) {
        // 轮播模式下封面由 carousel adapter 自行管理加载
        // 此处仅更新模糊背景
        h.blurBackground.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        h.executor.execute(() -> {
            Bitmap blurred = com.jingxin.jingxinmusic.util.BlurUtil.blur(
                    h.rootLayout.getContext(), bitmap, 10f);
            if (blurred != null) {
                h.rootLayout.post(() -> {
                    h.blurBackground.setImageBitmap(blurred);
                    h.blurBackground.setAlpha(0.5f);
                    h.blurBackground.setVisibility(View.VISIBLE);
                    if (!h.isNightMode) {
                        h.whiteOverlay.setVisibility(View.VISIBLE);
                        h.whiteOverlay.setAlpha(0.4f);
                    }
                });
            }
        });
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        // 轮播竖屏：不支持全屏歌词，只在双行和多行之间切换
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        if (isFullScreen) {
            // 拒绝全屏，降级为多行
            if (h.lyricView != null) {
                h.lyricView.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.MULTI_LINE);
            }
            h.callback.resetLyricMargin();
        } else {
            h.callback.resetLyricMargin();
        }
    }

    @Override
    public float getInfoPanelWidthRatio() {
        return 1.0f;
    }

    @Override
    public int getSongNameTopMarginDp() {
        return 16;
    }

    @Override
    public float getSpectrumHeightRatio() {
        return 0.12f;
    }

    @Override
    public void onPlayingStateChanged(boolean isPlaying) {
        // 轮播模式：无封面旋转、无唱臂
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        // 轮播模式无特殊恢复逻辑
    }

    @Override
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
        // 禁用所有圆形频谱
        boolean isOverlay = (spectrumStyle == com.jingxin.jingxinmusic.view.SpectrumView.STYLE_RING
                || spectrumStyle == com.jingxin.jingxinmusic.view.SpectrumView.STYLE_DIFFUSION_RING
                || spectrumStyle == com.jingxin.jingxinmusic.view.SpectrumView.STYLE_WAVE_RING);
        return !isOverlay;
    }

    @Override
    public boolean shouldRotateCover() {
        return false;
    }

    @Override
    public boolean needsReloadCover() {
        return true;
    }

    @Override
    public void onStyleEnter() {
        // 确保 carouselView 已创建并添加到 rootLayout
        ensureCarouselView();
        h.carouselView.setVisibility(View.VISIBLE);
        // 如果当前是圆形频谱，切换到非圆形
        if (h.spectrumView != null && h.spectrumView.isCoverOverlayMode()) {
            while (h.spectrumView.isCoverOverlayMode()) {
                h.spectrumView.switchStyle();
            }
        }
    }

    @Override
    public void onStyleExit() {
        if (h.carouselView != null) {
            h.carouselView.setVisibility(View.GONE);
        }
    }

    // ========== 私有方法 ==========

    /**
     * 确保 carouselView 和 carouselAdapter 已创建并添加到 rootLayout
     */
    private void ensureCarouselView() {
        if (h.carouselView == null) {
            h.carouselView = new CoverCarouselView(h.rootLayout.getContext());
            h.carouselView.setId(View.generateViewId());
            h.carouselView.setExecutor(h.executor);
            // 创建 adapter
            h.carouselAdapter = new CoverCarouselAdapter(
                    h.rootLayout.getContext(), h.executor);
            // 滑动/点击切歌回调
            h.carouselView.setOnSongChangeListener(delta -> {
                // delta: 负=上一曲方向, 正=下一曲方向
                h.callback.playSongAt(delta);
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            h.rootLayout.addView(h.carouselView, lp);
        }
    }

    /**
     * 将 carouselView 移到 infoPanel 上方
     */
    private void moveCarouselAboveInfoPanel() {
        if (h.carouselView == null) return;
        int carouselIdx = h.rootLayout.indexOfChild(h.carouselView);
        int infoIdx = h.rootLayout.indexOfChild(h.infoPanel);
        if (infoIdx >= 0 && carouselIdx != infoIdx + 1) {
            h.rootLayout.removeView(h.carouselView);
            h.rootLayout.addView(h.carouselView, infoIdx + 1);
        }
    }
}
