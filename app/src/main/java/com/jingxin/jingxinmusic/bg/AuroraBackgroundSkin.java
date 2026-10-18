package com.jingxin.jingxinmusic.bg;

import android.view.View;
import android.widget.FrameLayout;

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
    /** coverStyle 参数保留签名兼容（旧沉浸=1 分支已随旧四风格页下线，恒走非沉浸路径） */
    public void apply(BackgroundSkinContext ctx, int coverStyle, boolean landscape, boolean night) {
        // 双色光晕模式（所有封面模式生效，沉浸模式透明叠加/区域显示）：显示光晕 View
        ctx.aurora.setNightMode(night);
        // 注：原分支内联的 applyTextTheme/applyButtonTheme 已等价外提，
        //     由 PlayerFragment.updateThemeUI 统一收尾执行。
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
