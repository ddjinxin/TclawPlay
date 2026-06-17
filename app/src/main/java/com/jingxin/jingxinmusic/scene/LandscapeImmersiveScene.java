package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;

/**
 * 横屏沉浸模式
 * - 布局：左65%信息区 + 右35%封面区
 * - 封面：矩形铺满右侧，CENTER_CROP，不旋转，边缘渐变
 * - 层级：immersiveOverlay(底层实色) → coverView(封面) → landscapeGradientOverlay(渐变过渡)
 * - 歌名歌手：左面板顶部（52dp），字号随歌词动态调整
 * - 频谱高度：8%
 */
public class LandscapeImmersiveScene implements CoverScene {

    private final CoverSceneHelper h;

    public LandscapeImmersiveScene(CoverSceneHelper helper) {
        this.h = helper;
    }

    @Override
    public void enter() {
        // 沉浸遮罩
        h.immersiveOverlay.setLandscapeMode(true);
        h.immersiveOverlay.setNightMode(h.isNightMode);
        h.immersiveOverlay.setVisibility(View.VISIBLE);
        // 隐藏非沉浸遮罩
        h.overlayView.setVisibility(View.GONE);
        h.whiteOverlay.setVisibility(View.GONE);
        // 隐藏封面占位
        h.coverPlaceholder.setVisibility(View.GONE);
        // 隐藏模糊背景
        h.blurBackground.setVisibility(View.GONE);
        // 夜间暗层
        h.immersiveDarkOverlay.setVisibility(h.isNightMode ? View.VISIBLE : View.GONE);
        // 定位封面
        applyLandscapeImmersiveCover();
        h.coverView.setVisibility(View.VISIBLE);
        h.coverView.setClipToOutline(false);
        h.coverView.setBackground(null);
        h.coverView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        h.coverView.stopAndResetRotation();
        // 创建渐变过渡层
        if (h.landscapeGradientOverlay == null) {
            h.landscapeGradientOverlay = new com.jingxin.jingxinmusic.view.LandscapeGradientOverlay(
                    h.rootLayout.getContext());
            h.landscapeGradientOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            h.rootLayout.addView(h.landscapeGradientOverlay);
        }
        h.landscapeGradientOverlay.setOverlayColor(h.immersiveOverlay.getOverlayColor());
        h.landscapeGradientOverlay.setVisibility(View.VISIBLE);
        // 封面边缘渐变
        int overlayColor = h.immersiveOverlay.getOverlayColor();
        h.coverView.setForeground(h.ensureCoverBorderGradient(overlayColor));
        // 排列层级
        h.moveCoverBelowOverlay();
        // 歌名歌手
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        h.callback.resetLyricMargin();
    }

    @Override
    public void layout(int width, int height) {
        // 横屏公共布局
        h.layoutLandscapeBase(width);

        // immersiveOverlay 同步
        h.immersiveOverlay.setLandscapeMode(true);

        // 重新定位封面
        applyLandscapeImmersiveCover();

        // 频谱高度
        h.spectrumView.getLayoutParams().height = (int) (height * getSpectrumHeightRatio());
        h.coverPlaceholder.setVisibility(View.GONE);
    }

    @Override
    public void exit() {
        // 退出沉浸模式
        h.immersiveOverlay.setVisibility(View.GONE);
        h.immersiveOverlay.setLandscapeMode(false);
        h.immersiveDarkOverlay.setVisibility(View.GONE);
        h.blurBackground.setVisibility(View.GONE);
        h.coverView.setVisibility(View.VISIBLE);
        // 隐藏渐变过渡层
        if (h.landscapeGradientOverlay != null) {
            h.landscapeGradientOverlay.setVisibility(View.GONE);
        }
        // 恢复封面为圆形裁剪
        h.coverView.setClipToOutline(true);
        h.coverView.setBackgroundResource(R.drawable.circle_cover_background);
        h.coverView.setForeground(null);
        // 恢复封面层级
        h.moveCoverAboveInfoPanel();
        // 恢复歌词 margin
        h.callback.resetLyricMargin();
        // 恢复非沉浸遮罩
        h.callback.updateThemeUI();
        // 恢复旋转
        if (h.isPlaying) {
            h.coverView.startRotation();
        }
    }

    @Override
    public void setCover(Bitmap bitmap) {
        // 横屏沉浸：封面直接用 coverView 显示，不旋转
        h.coverView.setVisibility(View.VISIBLE);
        h.coverView.setImageBitmap(bitmap);
        h.coverView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        h.coverView.stopAndResetRotation();
        // 确保封面尺寸正确
        applyLandscapeImmersiveCover();
        // 恢复横向渐变 foreground
        int overlayColor = h.immersiveOverlay.getOverlayColor();
        h.coverView.setForeground(h.ensureCoverBorderGradient(overlayColor));
        // 提取主色调
        h.callback.extractAndApplyDominantColor(bitmap);
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        h.immersiveOverlay.setVisibility(View.VISIBLE);
        h.overlayView.setVisibility(View.GONE);
        h.whiteOverlay.setVisibility(View.GONE);
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        h.coverPlaceholder.setVisibility(View.GONE);
        h.coverView.setVisibility(View.VISIBLE);
        h.coverView.setClipToOutline(false);
        h.coverView.setBackground(null);
        h.callback.resetLyricMargin();
    }

    @Override
    public float getInfoPanelWidthRatio() {
        return 0.65f;
    }

    @Override
    public int getSongNameTopMarginDp() {
        return 52;
    }

    @Override
    public float getSpectrumHeightRatio() {
        return 0.10f;
    }

    /**
     * 横屏沉浸模式下设置封面：矩形铺满右侧，不旋转，带边缘渐变
     */
    private void applyLandscapeImmersiveCover() {
        int layoutWidth = h.getLayoutWidth();
        int parentHeight = h.getAvailableScreenHeight();

        // 用 FrameLayout 的实际高度
        int ph = h.rootLayout.getHeight();
        if (ph > 0) parentHeight = ph;

        int coverWidth = (int) (layoutWidth * 0.35f);
        int coverHeight = parentHeight;

        FrameLayout.LayoutParams coverParams =
                new FrameLayout.LayoutParams(coverWidth, coverHeight);
        coverParams.gravity = Gravity.END | Gravity.TOP;
        coverParams.rightMargin = 0;
        coverParams.topMargin = 0;
        h.coverView.setLayoutParams(coverParams);
    }
}
