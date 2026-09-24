package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 首页动态背景 View（复刻 QQ Music 车机版背景架构）
 *
 * 三层结构：
 *   ① 底色层：白天 #EDF0F2 / 夜间 #181A22
 *   ② 左下角染色层：RadialGradient，中心在左下角外下方 (0.15w, 1.75h)，
 *      封面查表色从中心向四周衰减（替代蒙版图，日夜峰值 alpha 独立可调）
 *   ③ 右上角渐变层：LinearGradient(1660.5,-145.1 → 1370,434.8)，
 *      封面查表色→透明，整屏绘制
 *
 * 颜色管线：封面 → MagicColor.extractDominant(8桶HSV取主色) → MagicColor.mapColor(色相查10档表, light/!isNight)
 */
public class DynamicBgView extends View {

    private final Paint bgPaint = new Paint();
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    /** 左下角染色层峰值 alpha：夜间（浓度） */
    private static final int NIGHT_MASK_ALPHA = 195;
    /** 左下角染色层峰值 alpha：白天（比原蒙版 128 提亮，浅底色上仍可见） */
    private static final int DAY_MASK_ALPHA = 155;

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
     * 设置夜间/白天模式
     */
    public void setNightMode(boolean night) {
        this.isNight = night;
        invalidate();
    }

    /**
     * 设置底色
     */
    public void setBaseColors(int nightColor, int dayColor) {
        this.nightBaseColor = nightColor;
        this.dayBaseColor = dayColor;
        invalidate();
    }

    /** 是否绘制右上角渐变层（默认 true，子页面设 false 仅保留底色+左下染色） */
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

        // ② 左下角染色层：RadialGradient（复刻原蒙版"左下浓、向右上衰减"形态）
        //    夜间浓度 195 / 白天 155，峰值 alpha 独立可调
        //    染色区域向左、向下收拢：中心紧贴左下角外、半径实质缩短（对角线×0.45），
        //    浓区只铺左下一小块，向右/向上快速衰减为透明
        int maskAlpha = isNight ? NIGHT_MASK_ALPHA : DAY_MASK_ALPHA;
        int maskColor = (maskAlpha << 24) | (0x00FFFFFF & tintColor);
        // 半径：对角线 × 0.45（上一版 0.7 因中心拉近被抵消，此版实质收缩 30%+）
        float radius = (float) Math.sqrt(w * w + h * h) * 0.45f;
        // 中心紧贴左下角外下方：(0.05w, 1.3h)
        float cx = 0.05f * w;
        float cy = 1.3f * h;

        maskPaint.setShader(new RadialGradient(cx, cy, radius,
                maskColor, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, maskPaint);

        // ③ 右上角渐变层（子页面可关闭）
        if (!gradientEnabled) return;
        // QQ Music 的 View 挂在屏幕 [580,1920] 区（左侧 0-580 是导航栏），本地坐标 +580 偏移换算成屏幕坐标：
        //   起点 (580+1080.5, -145.1) = (1660.5, -145.1) 右上角外部最浓
        //   终点由 (1370, 434.8) 延长至 (1250, 560)，过渡带加宽约 26%，收尾不再生硬
        float sx = w / 1920f;
        float sy = h / 1080f;
        float gx0 = 1660.5f * sx, gy0 = -145.1f * sy;
        float gx1 = 1250.0f * sx, gy1 = 560.0f * sy;

        // alpha 缓出曲线 (1-t)²：四档中间色，头部浓、尾部平缓趋近透明（线性衰减会收尾突兀）
        int tintRGB = 0x00FFFFFF & tintColor;
        int[] colors = {
                0xFF000000 | tintRGB,   // t=0.00  alpha 255
                0x8F000000 | tintRGB,   // t=0.25  alpha 143
                0x40000000 | tintRGB,   // t=0.50  alpha 64
                0x10000000 | tintRGB,   // t=0.75  alpha 16
                0x00000000              // t=1.00  alpha 0
        };

        LinearGradient gradient = new LinearGradient(
                gx0, gy0, gx1, gy1,
                colors, new float[]{0.0f, 0.25f, 0.5f, 0.75f, 1.0f},
                Shader.TileMode.CLAMP
        );
        gradientPaint.setShader(gradient);
        // 整屏绘制（渐变在左侧自然消隐，无需裁剪）
        canvas.drawRect(0, 0, w, h, gradientPaint);
    }
}
