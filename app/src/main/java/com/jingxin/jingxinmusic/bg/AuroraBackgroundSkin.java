package com.jingxin.jingxinmusic.bg;

import android.view.View;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.scene.CoverSceneHelper;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.view.AuroraGradientView;
import com.jingxin.jingxinmusic.view.LandscapeGradientOverlay;

/**
 * 双色光晕背景皮肤（bgSkinMode=1）
 *
 * 渲染规则（自 PlayerFragment.updateThemeUI 光晕分支原样迁移，含层级手术）：
 * - 沉浸横屏：封面最底层 + 渐变过渡层 + 光晕叠加封面之上（透明底氛围），左侧透光晕底色
 * - 沉浸竖屏：封面全屏铺底 + 43% 光晕底色遮罩 + 光晕整屏置于遮罩之上
 * - 非沉浸：光晕带底色全屏作为页面背景
 * 由 BackgroundSkinController 保证 ctx.aurora 非空（原 if 条件 bgSkinMode==1 && aurora!=null）。
 */
public final class AuroraBackgroundSkin implements PlayerBackgroundSkin {

    @Override
    public void apply(BackgroundSkinContext ctx, int coverStyle, boolean landscape, boolean night) {
        // 双色光晕模式（所有封面模式生效，沉浸模式透明叠加/区域显示）：显示光晕 View
        ctx.aurora.setNightMode(night);
        if (coverStyle == CoverSceneHelper.COVER_STYLE_IMMERSIVE) {
            // 沉浸模式：光晕作为背景/氛围
            if (landscape) {
                // 横屏沉浸：封面置于最底层，光晕叠加在封面之上（透明底氛围）
                ctx.aurora.setTransparentBackground(true);
                ctx.aurora.setVisibility(View.VISIBLE);
                ensureAuroraAboveCover(ctx); // 光晕移到封面之上
                // 左侧无封面区域露出根布局背景，设为光晕底色（白天白/夜间深色）
                ctx.rootLayout.setBackgroundColor(night
                        ? AuroraGradientView.BG_NIGHT : AuroraGradientView.BG_DAY);
                // 渐变过渡层：光晕模式下左侧不画实色，透出光晕
                int auroraBgColor = night
                        ? AuroraGradientView.BG_NIGHT : AuroraGradientView.BG_DAY;
                if (ctx.sceneHelper.landscapeGradientOverlay != null) {
                    ctx.sceneHelper.landscapeGradientOverlay.setOverlayColor(auroraBgColor);
                    ctx.sceneHelper.landscapeGradientOverlay.setSolidLeft(false);
                    ctx.sceneHelper.landscapeGradientOverlay.setVisibility(View.VISIBLE);
                }
                // 封面前景渐变恢复：光晕底色→透明
                if (ctx.coverView != null) {
                    CompatUtil.setForegroundCompat(ctx.coverView,
                            ctx.sceneHelper.ensureCoverBorderGradient(auroraBgColor));
                }
                // 沉浸遮罩系列全部隐藏，光晕作为背景氛围
                ctx.immersiveOverlay.setVisibility(View.GONE);
                ctx.whiteOverlay.setVisibility(View.GONE);
                ctx.overlayView.setVisibility(View.GONE);
                ctx.immersiveDarkOverlay.setVisibility(night ? View.VISIBLE : View.GONE);
            } else {
                // 竖屏沉浸：封面全屏铺底，仅上面0~43%透出
                // 43%以下：光晕底色实底（白天白/夜间深）；0~43%：光晕底色渐变遮罩
                // 光晕叠加在最上层整屏可见
                int auroraBgColor = night
                        ? AuroraGradientView.BG_NIGHT : AuroraGradientView.BG_DAY;
                // 根布局底色同步光晕底色，防止封面未加载时露黑
                ctx.rootLayout.setBackgroundColor(auroraBgColor);
                ctx.immersiveOverlay.setNightMode(night); // 43%以下实底跟随日夜（auroraMode 下取光晕底色）
                ctx.immersiveOverlay.setAuroraMode(true);
                ctx.immersiveOverlay.setVisibility(View.VISIBLE); // 白底遮罩：43%以下实底
                // 封面全屏铺底（CENTER_CROP），光晕模式不隐藏
                ctx.blurBackground.setVisibility(View.VISIBLE);
                ctx.blurBackground.setAlpha(1.0f);
                // 光晕透明底叠加在最上层（封面之上、信息区之下）
                ctx.aurora.setTransparentBackground(true);
                ctx.aurora.setVisibility(View.VISIBLE);
                ensureAuroraAboveBlur(ctx);
                ctx.whiteOverlay.setVisibility(View.GONE);
                ctx.overlayView.setVisibility(View.GONE);
                ctx.immersiveDarkOverlay.setVisibility(night ? View.VISIBLE : View.GONE);
            }
        } else {
            // 非沉浸模式：光晕带底色全屏作为页面背景
            ctx.aurora.setTransparentBackground(false);
            ctx.aurora.setVisibility(View.VISIBLE);
            ctx.blurBackground.setVisibility(View.GONE);
            ctx.immersiveOverlay.setVisibility(View.GONE);
            ctx.whiteOverlay.setVisibility(View.GONE);
            ctx.overlayView.setVisibility(View.GONE);
            ctx.immersiveDarkOverlay.setVisibility(View.GONE);
        }
        // 注：原分支内联的 applyTextTheme/applyButtonTheme 已等价外提，
        //     由 PlayerFragment.updateThemeUI 统一收尾执行。
    }

    /**
     * 横屏沉浸光晕：层级从下到上 = 封面 → 渐变过渡层 → 光晕。
     * 渐变带紧贴封面做左边缘过渡，光晕叠在最上层整屏可见，不被渐变带盖住。
     * （自 PlayerFragment.ensureAuroraAboveCover 原样迁移）
     */
    private void ensureAuroraAboveCover(BackgroundSkinContext ctx) {
        FrameLayout root = ctx.rootLayout;
        if (root == null || ctx.aurora == null || ctx.coverView == null) return;
        int coverIndex = root.indexOfChild(ctx.coverView);
        if (coverIndex < 0) return;
        // 用 helper 的权威引用（渐变带由 Scene 层创建/移除）
        LandscapeGradientOverlay grad = ctx.sceneHelper.landscapeGradientOverlay;
        // 渐变过渡层紧贴封面之上
        if (grad != null && grad.getParent() != null) {
            int gradIndex = root.indexOfChild(grad);
            if (gradIndex != coverIndex + 1) {
                root.removeView(grad);
                root.addView(grad, root.indexOfChild(ctx.coverView) + 1);
            }
        }
        // 光晕移到渐变层之上、infoPanel 之下
        int anchorIndex = root.indexOfChild(ctx.coverView);
        if (grad != null && grad.getParent() != null) {
            anchorIndex = root.indexOfChild(grad);
        }
        int auroraIndex = root.indexOfChild(ctx.aurora);
        int infoIndex = root.indexOfChild(ctx.sceneHelper.infoPanel);
        int target = anchorIndex + 1;
        if (infoIndex >= 0 && target >= infoIndex) target = infoIndex;
        if (auroraIndex != target) {
            root.removeView(ctx.aurora);
            root.addView(ctx.aurora, target);
        }
    }

    /**
     * 竖屏沉浸光晕：把光晕 View 移到铺底封面及其全部遮罩层之上、infoPanel 之下，
     * 使光晕整屏可见（白底遮罩之上再叠光晕）。
     * （自 PlayerFragment.ensureAuroraAboveBlur 原样迁移）
     */
    private void ensureAuroraAboveBlur(BackgroundSkinContext ctx) {
        FrameLayout root = ctx.rootLayout;
        if (root == null || ctx.aurora == null || ctx.blurBackground == null) return;
        int blurIndex = root.indexOfChild(ctx.blurBackground);
        int auroraIndex = root.indexOfChild(ctx.aurora);
        if (blurIndex < 0) return;
        // 锚点取 blur 之上最高的遮罩层（immersiveDarkOverlay > immersiveOverlay），光晕叠在其上
        int anchor = blurIndex;
        int darkIndex = root.indexOfChild(ctx.immersiveDarkOverlay);
        if (darkIndex > anchor) anchor = darkIndex;
        int overlayIndex = root.indexOfChild(ctx.immersiveOverlay);
        if (overlayIndex > anchor) anchor = overlayIndex;
        int target = anchor + 1;
        int infoIndex = root.indexOfChild(ctx.sceneHelper.infoPanel);
        if (infoIndex >= 0 && target >= infoIndex) target = infoIndex;
        if (auroraIndex != target) {
            root.removeView(ctx.aurora);
            root.addView(ctx.aurora, target);
        }
    }
}
