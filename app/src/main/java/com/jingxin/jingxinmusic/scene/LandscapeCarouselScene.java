package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.view.CoverCarouselAdapter;
import com.jingxin.jingxinmusic.view.CoverCarouselView;

/**
 * 横屏多封面轮播模式
 * - 与竖屏结构完全一致：封面在上方，info_panel 全宽
 * - 仅尺寸参数不同（卡片更大）
 * - 歌词：仅双行/多行，无全屏模式
 * - 频谱：仅底部柱状/波浪，禁用圆形频谱
 */
public class LandscapeCarouselScene implements CoverScene {

    private final CoverSceneHelper h;

    public LandscapeCarouselScene(CoverSceneHelper helper) {
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
        h.callback.updateThemeUI();
        // 关键：rootLayout 不裁剪子View
        h.rootLayout.setClipChildren(false);
        h.rootLayout.setClipToPadding(false);
        // 隐藏旋转封面，显示轮播封面
        h.coverView.setVisibility(View.GONE);
        ensureCarouselView();
        h.carouselView.setVisibility(View.VISIBLE);
        // 隐藏封面占位
        h.coverPlaceholder.setVisibility(View.GONE);
        // 歌名歌手
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        h.callback.resetLyricMargin();
        // 恢复封面层级
        moveCarouselAboveInfoPanel();
        // 确保歌词为双行模式
        if (h.lyricView != null) {
            if (h.lyricView.getDisplayMode() != com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE) {
                h.lyricView.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE);
            }
            h.callback.resetLyricMargin();
        }
    }

    @Override
    public void layout(int width, int height) {
        // 顶部/底部按钮间距
        h.applyButtonMargins(height, width, true);
        // info_panel 全宽（轮播模式横竖屏统一！）
        FrameLayout.LayoutParams infoParams =
                (FrameLayout.LayoutParams) h.infoPanel.getLayoutParams();
        infoParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.gravity = Gravity.START;
        h.infoPanel.setLayoutParams(infoParams);
        if (h.infoPanel instanceof LinearLayout) {
            ((LinearLayout) h.infoPanel).setGravity(Gravity.CENTER_HORIZONTAL);
        }
        // 歌名 topMargin = 0dp（紧贴轮播封面）
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) h.tvSongName.getLayoutParams();
        nameParams.topMargin = 0;
        h.tvSongName.setLayoutParams(nameParams);
        h.tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
        h.tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);
        // 歌名字号：和经典横屏一致，基于65%宽度计算
        float infoWidth = width * 0.65f;
        float songNameSize = Math.max(32f, Math.min(60f, infoWidth * 0.048f));
        h.tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        h.tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize * 0.7f);
        // 歌词宽度限制为65%屏幕宽，确保字号和经典横屏一致
        if (h.lyricView != null) {
            LinearLayout.LayoutParams lyricParams = (LinearLayout.LayoutParams) h.lyricView.getLayoutParams();
            lyricParams.width = (int) infoWidth;
            lyricParams.gravity = Gravity.CENTER_HORIZONTAL;
            h.lyricView.setLayoutParams(lyricParams);
        }
        // 轮播封面区域：在顶部按钮栏下方
        int topBarHeight = (h.topButtonsBar != null && h.topButtonsBar.getHeight() > 0)
                ? h.topButtonsBar.getHeight() : (int) (h.density * 56);
        // 中间卡片尺寸：height * 25%
        int coverSize = (int) (height * 0.25f);
        // 轮播容器高度 = coverSize（无多余边距）
        int carouselHeight = coverSize;
        if (h.carouselView != null) {
            h.carouselView.setCoverSize(coverSize);
            h.carouselView.setOverlapRatio(0.30f);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.carouselView.getLayoutParams();
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = carouselHeight;
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            lp.topMargin = topBarHeight;
            h.carouselView.setLayoutParams(lp);
            // 通知 carouselView 重新布局卡片
            h.carouselView.requestLayoutCards();
        }
        // 封面占位：和轮播容器对齐
        h.coverPlaceholder.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams placeholderParams =
                (LinearLayout.LayoutParams) h.coverPlaceholder.getLayoutParams();
        placeholderParams.height = carouselHeight;
        placeholderParams.width = 1;
        h.coverPlaceholder.setLayoutParams(placeholderParams);
        // 频谱位置：仅底部
        if (h.spectrumView != null && h.spectrumView.getParent() == h.rootLayout) {
            h.moveSpectrumToBottom();
        }
        if (h.spectrumView != null) {
            h.spectrumView.getLayoutParams().height = (int) (height * getSpectrumHeightRatio());
        }
    }

    @Override
    public void exit() {
        if (h.carouselView != null) {
            h.carouselView.setVisibility(View.GONE);
        }
        h.rootLayout.setClipChildren(true);
    }

    @Override
    public void setCover(Bitmap bitmap) {
        // 仅更新模糊背景
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
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        if (isFullScreen) {
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
        // 轮播无旋转动画
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        // 轮播无特殊恢复逻辑
    }

    @Override
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
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
        ensureCarouselView();
        h.carouselView.setVisibility(View.VISIBLE);
        // 禁用圆形频谱
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

    private void ensureCarouselView() {
        if (h.carouselView == null) {
            h.carouselView = new CoverCarouselView(h.rootLayout.getContext());
            h.carouselView.setId(View.generateViewId());
            h.carouselView.setExecutor(h.executor);
            h.carouselAdapter = new CoverCarouselAdapter(
                    h.rootLayout.getContext(), h.executor);
            h.carouselView.setOnSongChangeListener(delta -> {
                h.callback.playSongAt(delta);
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            h.rootLayout.addView(h.carouselView, lp);
        }
    }

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
