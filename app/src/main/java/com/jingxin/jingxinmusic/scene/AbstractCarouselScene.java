package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;

import com.jingxin.jingxinmusic.view.SpectrumView;

/**
 * 轮播模式公共基类
 * 竖屏和横屏轮播场景共用大部分逻辑（封面设置、频谱禁用、风格进入/退出等）
 * 子类只需实现 enter()、layout()、onLyricModeChanged() 和 getSpectrumHeightRatio()
 */
public abstract class AbstractCarouselScene implements CoverScene {

    protected final CoverSceneHelper h;

    protected AbstractCarouselScene(CoverSceneHelper helper) {
        this.h = helper;
    }

    // ========== 子类必须实现的差异化方法 ==========

    @Override
    public abstract void enter();

    @Override
    public abstract void layout(int width, int height);

    @Override
    public abstract void onLyricModeChanged(boolean isFullScreen);

    // ========== 轮播模式共用实现 ==========

    @Override
    public void exit() {
        if (h.carouselView != null) {
            h.carouselView.stopSwayAnimation();
            h.carouselView.setVisibility(android.view.View.GONE);
        }
        h.rootLayout.setClipChildren(true);
    }

    @Override
    public void setCover(Bitmap bitmap) {
        h.applyBlurBackground(bitmap);
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
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
        return !SpectrumView.isOverlayStyle(spectrumStyle);
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
        h.ensureCarouselView();
        h.carouselView.setVisibility(android.view.View.VISIBLE);
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
            h.carouselView.stopSwayAnimation();
            h.carouselView.setVisibility(android.view.View.GONE);
        }
    }
}
