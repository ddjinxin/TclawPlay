package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.jingxin.jingxinmusic.R;

/**
 * 首页动态背景 View（1:1 复刻 QQ Music 车机版背景架构）
 *
 * 三层结构（对应 QQ Music layout_main_v3）：
 *   ① 底色层：白天 #EDF0F2 / 夜间 #181A22（QQ Music bg1/bg1_light）
 *   ② 左下角染色层：global_bg_container_op —— 原版 alpha 蒙版图 fitXY
 *      铺满左下 78.1% 宽 × 51.8% 高区域，PorterDuffColorFilter(封面查表色, SRC_IN) 染色
 *   ③ 右上角渐变层：GradientView —— 两段 LinearGradient(1660.5,-145.1 → 1370,434.8)，
 *      封面查表色→透明，整屏绘制（渐变在左侧自然消隐）
 *
 * 日夜模式：
 *   白天：浅色版蒙版（alpha 左下≈118）+ 查表浅色列
 *   夜间：深色版蒙版（alpha 左下≈211 更浓）+ 查表深色列
 *
 * 颜色管线：封面 → MagicColor.extractDominant(8桶HSV取主色) → MagicColor.mapColor(色相查10档表, light/!isNight)
 */
public class DynamicBgView extends View {

    private final Paint bgPaint = new Paint();
    private final Paint maskPaint = new Paint();
    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 封面查表后的染色色（白天浅色 / 夜间深色，RGB） */
    private int tintColor = -1;
    /** 底色：夜间深色（QQ Music bg1） */
    private int nightBaseColor = 0xFF181A22;
    /** 底色：白天（QQ Music bg1_light） */
    private int dayBaseColor = 0xFFEDF0F2;
    /** 是否夜间模式 */
    private boolean isNight = false;
    /** 是否有染色色可用 */
    private boolean hasColor = false;
    /** 是否绘制右上角渐变层（首页=true，子页面=false） */
    private boolean gradientEnabled = true;

    /** 左下角蒙版位图（按日夜切换浅色/深色版，按横竖屏切换 land/port） */
    private Bitmap maskBitmap;
    /** 染色 Paint 的 ColorFilter */
    private ColorFilter tintFilter;

    public DynamicBgView(Context context) {
        super(context);
        init();
    }

    public DynamicBgView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DynamicBgView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        bgPaint.setStyle(Paint.Style.FILL);
        maskPaint.setStyle(Paint.Style.FILL);
    }

    /** 按日夜 + 横竖屏加载对应蒙版图 */
    private Bitmap loadMaskFor(boolean night, boolean portrait) {
        int id;
        if (portrait) {
            id = night ? R.drawable.bg_mask_port_dark : R.drawable.bg_mask_port_light;
        } else {
            id = night ? R.drawable.bg_mask_land_dark : R.drawable.bg_mask_land_light;
        }
        return BitmapFactory.decodeResource(getResources(), id);
    }

    /**
     * 设置封面查表色，-1 表示无色（回退纯底色）
     */
    public void setTintColor(int color) {
        this.tintColor = color;
        this.hasColor = (color != -1);
        this.tintFilter = hasColor
                ? new PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
                : null;
        invalidate();
    }

    /**
     * 设置夜间/白天模式（同时切换蒙版图）
     */
    public void setNightMode(boolean night) {
        if (this.isNight != night) {
            this.isNight = night;
            reloadMask();
        }
        invalidate();
    }

    private void reloadMask() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        boolean portrait = getHeight() > getWidth();
        if (maskBitmap != null) maskBitmap.recycle();
        maskBitmap = loadMaskFor(isNight, portrait);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        reloadMask();
    }

    /**
     * 设置底色
     */
    public void setBaseColors(int nightColor, int dayColor) {
        this.nightBaseColor = nightColor;
        this.dayBaseColor = dayColor;
        invalidate();
    }

    /** 是否绘制右上角渐变层（默认 true，子页面设 false 仅保留底色+左下蒙版） */
    public void setGradientEnabled(boolean enabled) {
        this.gradientEnabled = enabled;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int baseColor = isNight ? nightBaseColor : dayBaseColor;

        // ① 底色层
        bgPaint.setColor(baseColor);
        canvas.drawRect(0, 0, w, h, bgPaint);

        if (!hasColor || tintColor == -1) return;

        // ② 左下角染色层：蒙版图 fitXY 到 宽78.1% × 高51.8%，贴左下角
        // 夜间用深色版蒙版（alpha 更浓，QQ Music global_bg_color_drawable_land 左下≈211）
        // 白天用浅色版蒙版（alpha 较淡，左下≈118）
        if (maskBitmap == null) {
            reloadMask();
        }
        if (maskBitmap != null) {
            int mw = Math.round(w * 0.781f);
            int mh = Math.round(h * 0.518f);
            if (h > w) {
                mw = w;  // 竖屏：QQ Music 竖版是 match_parent 宽
            }
            int left = 0;
            int top = h - mh;

            maskPaint.setColorFilter(tintFilter);
            canvas.save();
            canvas.clipRect(left, top, left + mw, h);
            canvas.drawBitmap(maskBitmap, null,
                    new android.graphics.RectF(left, top, left + mw, h), maskPaint);
            canvas.restore();
        }

        // ③ 右上角渐变层（子页面可关闭）
        if (!gradientEnabled) return;
        // QQ Music 的 View 挂在屏幕 [580,1920] 区（左侧 0-580 是导航栏），本地坐标 +580 偏移换算成屏幕坐标：
        //   起点 (580+1080.5, -145.1) = (1660.5, -145.1) 右上角外部最浓
        //   终点 (580+790, 434.8)     = (1370, 434.8)
        // t 分布：右上角≈0.02 最浓，向左下连续衰减，中部/左下 t>1 完全透明 → 颜色集中在右上角一块
        float sx = w / 1920f;
        float sy = h / 1080f;
        float gx0 = 1660.5f * sx, gy0 = -145.1f * sy;
        float gx1 = 1370.0f * sx, gy1 = 434.8f * sy;

        // 渐变起止：染色色 → 透明黑（QQ Music 同款 #00000000，中间色逐渐变暗与底色融合）
        int startColor = 0xFF000000 | tintColor;
        int endColor = 0x00000000;

        LinearGradient gradient = new LinearGradient(
                gx0, gy0, gx1, gy1,
                new int[]{startColor, endColor},
                new float[]{0.0f, 1.0f},
                Shader.TileMode.CLAMP
        );
        gradientPaint.setShader(gradient);
        // 整屏绘制（渐变在左侧自然消隐，无需裁剪）
        canvas.drawRect(0, 0, w, h, gradientPaint);
    }
}
