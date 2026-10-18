package com.jingxin.jingxinmusic.bg;

import android.graphics.Color;
import android.view.View;

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

        // 注：原分支内联的 applyTextTheme/applyButtonTheme 已等价外提，
        //     由 PlayerFragment.updateThemeUI 统一收尾执行。
    }
}
