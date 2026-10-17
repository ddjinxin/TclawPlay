package com.jingxin.jingxinmusic.stage;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.view.AuroraGradientView;
import com.jingxin.jingxinmusic.view.LandscapeGradientOverlay;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.SpectrumView;

/**
 * 沉浸风格页面（组件化第6步收尾）
 *
 * 合并旧 PortraitImmersiveScene + LandscapeImmersiveScene：
 * - 竖屏：封面原图 CENTER_CROP 铺顶部 43%，旋转封面隐藏，歌名推到遮罩区（updateImmersiveLyricMargin）
 * - 横屏：封面矩形铺满右侧 35%（不旋转，边缘渐变+渐变过渡层），层级手术 moveCoverBelowOverlay
 * - 频谱：始终底部（圆环类不可用）
 * - 全屏歌词：沉浸不支持（switchScene 进风格时已降级为多行），本页 onLyricModeChanged
 *   仅处理双行/多行互切的兜底
 */
public class ImmersivePage extends StagePage {

    public ImmersivePage(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }

    @Override
    public void install(LayoutInflater inflater, ViewGroup host) {
        // 沉浸沿旧视图流（背景层z-order手术依赖rootLayout直接子View），无自建视图
    }

    @Override
    public void enter() {
        boolean landscape = h.isLandscapeMode;
        // 沉浸遮罩
        h.immersiveOverlay.setLandscapeMode(landscape);
        h.immersiveOverlay.setNightMode(h.isNightMode);
        h.immersiveOverlay.setVisibility(View.VISIBLE);
        // 隐藏非沉浸遮罩与占位
        h.overlayView.setVisibility(View.GONE);
        h.whiteOverlay.setVisibility(View.GONE);
        h.coverPlaceholder.setVisibility(View.GONE);
        // 夜间暗层
        h.immersiveDarkOverlay.setVisibility(h.isNightMode ? View.VISIBLE : View.GONE);

        if (landscape) {
            // 横屏：隐藏模糊背景，coverView 矩形铺右 35%
            h.blurBackground.setVisibility(View.GONE);
            applyLandscapeImmersiveCover();
            h.coverView.setVisibility(View.VISIBLE);
            h.coverView.setClipToOutline(false);
            h.coverView.setBackground(null);
            h.coverView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            h.coverView.stopAndResetRotation();
            // 创建或恢复渐变过渡层
            if (h.landscapeGradientOverlay == null) {
                h.landscapeGradientOverlay = new LandscapeGradientOverlay(
                        h.rootLayout.getContext());
                h.landscapeGradientOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
            if (h.landscapeGradientOverlay.getParent() == null) {
                h.rootLayout.addView(h.landscapeGradientOverlay);
            }
            h.landscapeGradientOverlay.setOverlayColor(h.immersiveOverlay.getOverlayColor());
            h.landscapeGradientOverlay.setSolidLeft(true);
            h.landscapeGradientOverlay.setVisibility(View.VISIBLE);
            // 封面边缘渐变
            CompatUtil.setForegroundCompat(h.coverView,
                    h.ensureCoverBorderGradient(h.immersiveOverlay.getOverlayColor()));
            // 排列层级（immersiveOverlay底层 → coverView → 渐变过渡层）
            h.moveCoverBelowOverlay();
        } else {
            // 竖屏：模糊背景原图铺顶部 43%，旋转封面隐藏
            h.blurBackground.setVisibility(View.VISIBLE);
            h.blurBackground.setAlpha(1.0f);
            h.blurBackground.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            applyPortraitImmersiveCoverHeight();
            h.coverView.setVisibility(View.GONE);
            h.applyCircleCoverStyle();
            // 恢复层级（immersiveOverlay 在 blurBackground 之上）
            h.restoreOverlayHierarchy();
        }

        // 歌名歌手
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        if (landscape) {
            h.callback.resetLyricMargin();
        } else {
            boolean isFull = h.lyricView != null
                    && h.lyricView.getDisplayMode() == LyricView.DisplayMode.FULL;
            if (isFull) {
                h.callback.resetLyricMargin();
            } else {
                h.callback.updateImmersiveLyricMargin(false);
            }
        }
        // 光晕模式下由 updateThemeUI 统一管理光晕层级与遮罩可见性
        h.callback.updateThemeUI();
    }

    @Override
    public void exit() {
        // 退出沉浸恢复经典基态
        h.immersiveOverlay.setVisibility(View.GONE);
        h.immersiveOverlay.setLandscapeMode(false);
        h.immersiveDarkOverlay.setVisibility(View.GONE);
        h.blurBackground.setVisibility(View.GONE);
        h.coverView.setVisibility(View.VISIBLE);
        // 移除渐变过渡层（仅GONE会有叠加残留）
        if (h.landscapeGradientOverlay != null && h.landscapeGradientOverlay.getParent() != null) {
            h.rootLayout.removeView(h.landscapeGradientOverlay);
        }
        // 恢复圆形裁剪与层级
        h.applyCircleCoverStyle();
        h.moveCoverAboveInfoPanel();
        // 恢复歌词 margin / 占位 / 遮罩 / 旋转
        h.callback.resetLyricMargin();
        h.callback.updateCoverPlaceholder();
        h.callback.updateThemeUI();
        if (h.isPlaying) {
            h.coverView.startRotation();
        }
    }

    @Override
    public void layout(int width, int height) {
        boolean landscape = h.isLandscapeMode;
        h.applyButtonMargins(height, width, landscape);
        h.immersiveOverlay.setLandscapeMode(landscape);
        if (landscape) {
            h.layoutLandscapeBase(width, height);
            applyLandscapeImmersiveCover();
        } else {
            h.setLayoutInfoPanelFullWidth();
            h.tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
            h.tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);
            float songNameSize = width * 0.048f;
            h.tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
            h.tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize * 0.7f);
            // 歌名推到遮罩区（43%高度）；全屏兜底 reset
            boolean isFull = h.lyricView != null
                    && h.lyricView.getDisplayMode() == LyricView.DisplayMode.FULL;
            if (isFull) {
                h.callback.resetLyricMargin();
            } else {
                h.callback.updateImmersiveLyricMargin(false);
            }
            applyPortraitImmersiveCoverHeight();
        }
        // 频谱：沉浸始终底部
        h.applySpectrumPosition(true, 0, 0, 0, height, getSpectrumHeightRatio());
        h.applyProgressScaling(height, width);
    }

    @Override
    public void setCover(Bitmap bitmap) {
        if (h.isLandscapeMode) {
            // 横屏：coverView 直接显示，不旋转
            h.coverView.setVisibility(View.VISIBLE);
            h.coverView.setImageBitmap(bitmap);
            h.coverView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            h.coverView.stopAndResetRotation();
            applyLandscapeImmersiveCover();
            // 横向渐变 foreground：光晕模式用光晕底色，非光晕用主色调
            int gradientColor = h.bgSkinMode == 1
                    ? (h.isNightMode ? AuroraGradientView.BG_NIGHT : AuroraGradientView.BG_DAY)
                    : h.immersiveOverlay.getOverlayColor();
            CompatUtil.setForegroundCompat(h.coverView, h.ensureCoverBorderGradient(gradientColor));
        } else {
            // 竖屏：原图铺模糊背景，CENTER_CROP，高度 43%
            h.blurBackground.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            h.blurBackground.setVisibility(View.VISIBLE);
            h.blurBackground.setAlpha(1.0f);
            h.blurBackground.setImageBitmap(bitmap);
            applyPortraitImmersiveCoverHeight();
            h.coverView.setVisibility(View.GONE);
        }
        // 提取主色调
        h.callback.extractAndApplyDominantColor(bitmap);
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        // 光晕模式下 immersiveOverlay 由 updateThemeUI 统一管理（保持 GONE）
        if (h.bgSkinMode != 1) {
            h.immersiveOverlay.setVisibility(View.VISIBLE);
        }
        h.overlayView.setVisibility(View.GONE);
        h.whiteOverlay.setVisibility(View.GONE);
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        h.coverPlaceholder.setVisibility(View.GONE);
        if (h.isLandscapeMode) {
            h.coverView.setVisibility(View.VISIBLE);
            h.coverView.setClipToOutline(false);
            h.coverView.setBackground(null);
            h.callback.resetLyricMargin();
        } else {
            h.coverView.setVisibility(View.GONE);
            // 沉浸不支持全屏：双行/多行互切时保持遮罩区模式
            h.immersiveOverlay.setFullScreenMode(false);
            h.callback.updateImmersiveLyricMargin(false);
        }
    }

    // ===== 沉浸专用布局 =====

    /** 竖屏：模糊背景高度 = 页面 43%，全宽，顶部对齐 */
    private void applyPortraitImmersiveCoverHeight() {
        int ph = h.rootLayout.getHeight();
        if (ph <= 0) {
            ph = h.getAvailableScreenHeight();
        }
        int coverHeight = (int) (ph * 0.43f);
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) h.blurBackground.getLayoutParams();
        params.width = FrameLayout.LayoutParams.MATCH_PARENT;
        params.height = coverHeight;
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        h.blurBackground.setLayoutParams(params);
    }

    /** 横屏：coverView 矩形铺满右侧 35% */
    private void applyLandscapeImmersiveCover() {
        int layoutWidth = h.getLayoutWidth();
        int parentHeight = h.getAvailableScreenHeight();
        int ph = h.rootLayout.getHeight();
        if (ph > 0) parentHeight = ph;
        int coverWidth = (int) (layoutWidth * 0.35f);
        FrameLayout.LayoutParams coverParams =
                new FrameLayout.LayoutParams(coverWidth, parentHeight);
        coverParams.gravity = Gravity.END | Gravity.TOP;
        coverParams.rightMargin = 0;
        coverParams.topMargin = 0;
        h.coverView.setLayoutParams(coverParams);
    }

    // ===== 旧 Scene 兼容 =====

    @Override
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
        // 沉浸模式下圆环/扩散圆环/波浪圆环不可用
        return !SpectrumView.isOverlayStyle(spectrumStyle);
    }

    @Override
    public boolean shouldRotateCover() {
        return false; // 沉浸封面不旋转
    }

    @Override
    public boolean needsReloadCover() {
        return true;
    }

    @Override
    public float getInfoPanelWidthRatio() {
        return h.isLandscapeMode ? 0.65f : 1.0f;
    }

    @Override
    public float getSpectrumHeightRatio() {
        return 0.10f;
    }
}
