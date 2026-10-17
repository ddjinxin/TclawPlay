package com.jingxin.jingxinmusic.bg;

import android.graphics.Color;
import android.view.View;

import com.jingxin.jingxinmusic.scene.CoverSceneHelper;
import com.jingxin.jingxinmusic.util.ThemeColors;

/**
 * 默认背景皮肤（bgSkinMode=0）
 *
 * 渲染规则（自 PlayerFragment.updateThemeUI 非光晕分支原样迁移）：
 * - 沉浸模式：43% 渐变遮罩（封面主色）+ 夜间压暗层，无普通遮罩
 * - 非沉浸：白天=模糊封面0.5+白色渐变遮罩，夜间=模糊封面0.6+黑色半透明遮罩
 * - 光晕 View 隐藏、根布局还原黑色、频谱恢复默认配色（退出光晕的清理）
 */
public final class DefaultBackgroundSkin implements PlayerBackgroundSkin {

    @Override
    public void apply(BackgroundSkinContext ctx, int coverStyle, boolean landscape, boolean night) {
        // 非光晕或沉浸模式：隐藏光晕 View
        if (ctx.aurora != null) {
            ctx.aurora.setVisibility(View.GONE);
        }
        // 恢复根布局默认黑色背景（光晕沉浸模式设了白色/深色底，退出时需还原）
        ctx.rootLayout.setBackgroundColor(Color.BLACK);
        // 退出双色光晕模式：频谱恢复默认配色
        if (ctx.spectrumView != null) {
            ctx.spectrumView.clearTint();
        }

        if (coverStyle == CoverSceneHelper.COVER_STYLE_IMMERSIVE) {
            // 沉浸模式：用沉浸遮罩替代普通遮罩
            // 先设状态再VISIBLE，防止状态未就绪时触发绘制
            ctx.immersiveOverlay.setLandscapeMode(landscape);
            ctx.immersiveOverlay.setNightMode(night);
            ctx.immersiveOverlay.setAuroraMode(false); // 非光晕：遮罩恢复封面主色
            ctx.immersiveOverlay.setVisibility(View.VISIBLE);
            // 横屏沉浸：同步更新封面边缘渐变颜色和渐变过渡层颜色
            if (landscape && ctx.coverBorderGradient != null) {
                ctx.coverBorderGradient.setOverlayColor(ctx.immersiveOverlay.getOverlayColor());
            }
            if (landscape && ctx.sceneHelper.landscapeGradientOverlay != null) {
                ctx.sceneHelper.landscapeGradientOverlay.setOverlayColor(ctx.immersiveOverlay.getOverlayColor());
                ctx.sceneHelper.landscapeGradientOverlay.setSolidLeft(true);
            }
            ctx.overlayView.setVisibility(View.GONE);
            ctx.whiteOverlay.setVisibility(View.GONE);
            // 同步夜间暗层
            ctx.immersiveDarkOverlay.setVisibility(night ? View.VISIBLE : View.GONE);
            ctx.blurBackground.setAlpha(1.0f);
        } else {
            // 非沉浸模式
            ctx.immersiveOverlay.setVisibility(View.GONE);

            if (night) {
                // 夜间模式：黑色背景，白色文字
                ctx.blurBackground.setAlpha(0.6f);
                ctx.blurBackground.setVisibility(View.VISIBLE);
                ctx.whiteOverlay.setVisibility(View.GONE);
                ctx.overlayView.setVisibility(View.VISIBLE);
                ctx.overlayView.setBackgroundColor(ThemeColors.nightOverlay());
            } else {
                // 白天模式：渐变遮罩背景，深色文字
                ctx.blurBackground.setAlpha(0.5f);
                ctx.blurBackground.setVisibility(View.VISIBLE);
                ctx.whiteOverlay.setVisibility(View.VISIBLE);
                ctx.whiteOverlay.setAlpha(0.7f);
                ctx.overlayView.setVisibility(View.GONE);
            }
        }
        // 注：原分支内联的 applyTextTheme/applyButtonTheme 已等价外提，
        //     由 PlayerFragment.updateThemeUI 统一收尾执行。
    }
}
