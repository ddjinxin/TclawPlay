package com.jingxin.jingxinmusic.bg;

import com.jingxin.jingxinmusic.view.CoverBorderGradientDrawable;

/**
 * 背景皮肤控制器（组件化第3步）
 *
 * 持有皮肤上下文与皮肤注册表，替代原 updateThemeUI 中的
 * 「皮肤 × 风格 × 横竖屏 × 日夜」if-else 组合矩阵的皮肤分发部分。
 *
 * 皮肤选择规则与原逻辑一致：bgSkinMode==1 且光晕 View 可用时用光晕皮肤，
 * 否则回落默认皮肤。新增皮肤 = 在此注册 id → 实例。
 */
public final class BackgroundSkinController {

    /** 默认皮肤 id（0），与设置页 bg_skin 取值一致 */
    public static final int SKIN_DEFAULT = 0;
    /** 双色光晕皮肤 id（1） */
    public static final int SKIN_AURORA = 1;

    private final BackgroundSkinContext ctx;
    private final PlayerBackgroundSkin defaultSkin = new DefaultBackgroundSkin();
    private final PlayerBackgroundSkin auroraSkin = new AuroraBackgroundSkin();

    public BackgroundSkinController(BackgroundSkinContext ctx) {
        this.ctx = ctx;
    }

    /** 同步封面边缘渐变引用（与 Fragment 字段同源，每次状态同步时刷新） */
    public void syncCoverBorderGradient(CoverBorderGradientDrawable gradient) {
        ctx.coverBorderGradient = gradient;
    }

    /** 应用当前皮肤（原 updateThemeUI 背景段入口） */
    public void apply(int skinMode, int coverStyle, boolean landscape, boolean night) {
        PlayerBackgroundSkin skin = (skinMode == SKIN_AURORA && ctx.aurora != null)
                ? auroraSkin : defaultSkin;
        skin.apply(ctx, coverStyle, landscape, night);
    }
}
