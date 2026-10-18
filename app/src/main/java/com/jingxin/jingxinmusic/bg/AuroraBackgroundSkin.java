package com.jingxin.jingxinmusic.bg;


import com.jingxin.jingxinmusic.view.AuroraGradientView;

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

}
