package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

/**
 * 时计机芯（PendoloClockwork，移植 folia pendolo 的 Canvas2D 线框路径）
 *
 * 部件（照搬 pendoloClockworkDraw2d）：主擒纵轮(36齿+辐条轮+机刻纹+铆钉)、太阳轮、
 * 行星轮×3、左上摆轮(20齿+游丝+宝石)、左下传动轮(24齿+日内瓦条纹)、秒轮(每秒一齿)、
 * 介轮、同心参考环+外圈60刻度+焦轴基准线与箭头。
 *
 * 运动：主动轮角 = 棘轮弹簧(tickIndex×角度步进)；摆轮慢速常转 + 低频能量摆游丝；
 * 秒轮按播放秒数走齿；介轮快转；其余按 folia 联动比反转/加速。
 * 齿轮 Path 静态缓存（齿形不变只有旋转），stroke 纯线框零位图。
 */
public class PendoloClockworkView extends View {

    /** 低频能量提供者（BassEnergy 注入；null 时游丝不振，机芯照走） */
    public interface BassProvider { float getBassEnergy(); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private BassProvider bassProvider;
    private int primaryColor = Color.rgb(0xC9, 0xE4, 0xFF);   // 主题主色（兜底）
    private int accentColor = Color.rgb(0x6E, 0xE8, 0xD8);    // 强调色（兜底）

    private int layoutW, layoutH;
    private float centerX, centerY, baseRadius;

    // 棘轮弹簧（欠阻尼，追目标行索引）：folia stiffness 180×snappiness2.0 / damping 20 / mass 0.8
    private float springPos, springVel;
    private float springTarget;
    private static final float SPRING_K = 360f, SPRING_D = 20f, SPRING_M = 0.8f;

    // 摆轮相位（慢速常转用）
    private long lastFrameNs = -1L;
    private float phase;
    private boolean running = false;

    // 进度（秒轮）
    private long positionMs = -1L;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            long deltaNs = lastFrameNs < 0 ? 0 : frameTimeNanos - lastFrameNs;
            lastFrameNs = frameTimeNanos;
            float dt = Math.min(0.064f, deltaNs / 1_000_000_000f);
            step(dt);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public PendoloClockworkView(Context context) { super(context); init(); }
    public PendoloClockworkView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public PendoloClockworkView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        // 软件绘制：纯线框 stroke 软绘无 tessellate，规避 8.1 车机 HWUI/驱动退化
        // （现象：硬绘下长跑后逐步丢弃绘制 op，机芯只剩最先画的两个齿轮）
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ===== 对外接口 =====

    public void setColors(int primary, int accent) {
        if (primary != 0) primaryColor = primary;
        if (accent != 0) accentColor = accent;
        invalidate();
    }

    public void setBassProvider(BassProvider p) { this.bassProvider = p; }

    /** 歌词行索引目标（换行棘轮的驱动源；LyricView 每换行调用） */
    public void setTickTarget(float target) {
        this.springTarget = target;
    }

    /** 播放进度（秒轮走齿） */
    public void setPositionMs(long positionMs) { this.positionMs = positionMs; }

    // ===== 弹簧步进 =====

    private void step(float dt) {
        // 半隐式欧拉：欠阻尼弹簧追目标（角步进由文本端换算，这里只出弹簧值）
        float f = -SPRING_K * (springPos - springTarget) - SPRING_D * springVel;
        springVel += (f / SPRING_M) * dt;
        springPos += springVel * dt;
        phase += dt;
    }

    /** 当前棘轮弹簧值（LyricView 布局用同一值保持轮与轨道咬合） */
    public float getSpringTick() { return springPos; }

    // ===== 布局 =====

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w; layoutH = h;
        // folia 默认：轮心 x=0% 屏宽（左边缘）、y=50%；主轮半径 42% min(w,h)
        centerX = 0f;
        centerY = h * 0.5f;
        baseRadius = Math.min(w, h) * 0.42f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrameNs = -1L;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (baseRadius <= 0) return;

        float m = 1f; // subtle 档装饰透明度乘子（folia subtle≈0.55，full=1；保持 subtle）
        int pA10 = withAlpha(primaryColor, 0.10f * m), pA15 = withAlpha(primaryColor, 0.15f * m);
        int pA18 = withAlpha(primaryColor, 0.18f * m), pA25 = withAlpha(primaryColor, 0.25f * m);
        int pA28 = withAlpha(primaryColor, 0.28f * m), pA32 = withAlpha(primaryColor, 0.32f * m);
        int pA42 = withAlpha(primaryColor, 0.42f * m);
        int aA35 = withAlpha(accentColor, 0.35f * m), aA58 = withAlpha(accentColor, 0.58f * m);
        int aA72 = withAlpha(accentColor, 0.72f * m);
        int mainFill = withAlpha(accentColor, 0.08f * m);

        float escapementAngle = springPos * ANGLE_STEP_RAD; // 棘轮角 = 弹簧×角步进

        // 1. 同心参考环 + 外圈 60 刻度（已移除：机芯精简）

        // 2. 主擒纵轮：36 齿外圈 + 辐条轮 + 双倒角环 + 机刻纹 + 铆钉
        paint.setStrokeWidth(2.2f);
        drawGear(canvas, centerX, centerY, baseRadius + 8f, 36, 10f, escapementAngle, aA58, mainFill);
        paint.setStrokeWidth(1.8f);
        drawSpokedWheel(canvas, centerX, centerY, baseRadius * 0.2f, baseRadius * 0.85f, 6, escapementAngle, pA42);
        paint.setStrokeWidth(0.8f);
        paint.setColor(pA18);
        canvas.drawCircle(centerX, centerY, baseRadius * 0.88f, paint);
        canvas.drawCircle(centerX, centerY, baseRadius * 0.92f, paint);
        // 机刻纹：48 根长短交替放射线
        float gIn = baseRadius * 0.62f, gOut = baseRadius * 0.83f;
        for (int i = 0; i < 48; i++) {
            float a = (i * (float) (Math.PI * 2)) / 48f;
            float inner = i % 2 == 0 ? gIn : gIn + (gOut - gIn) * 0.3f;
            paint.setColor(i % 2 == 0 ? pA10 : withAlpha(primaryColor, 0.07f * m));
            paint.setStrokeWidth(0.7f);
            canvas.drawLine(
                    centerX + inner * cos(a + escapementAngle), centerY + inner * sin(a + escapementAngle),
                    centerX + gOut * cos(a + escapementAngle), centerY + gOut * sin(a + escapementAngle), paint);
        }
        // 铆钉 12 颗
        paint.setColor(withAlpha(primaryColor, 0.25f * m));
        paint.setStrokeWidth(0.8f);
        float rivetR = baseRadius * 0.96f;
        for (int i = 0; i < 12; i++) {
            float a = (i * (float) (Math.PI * 2)) / 12f + escapementAngle;
            canvas.drawCircle(centerX + rivetR * cos(a), centerY + rivetR * sin(a), 2.2f, paint);
        }

        // 3. 太阳轮（中心 12 齿，反转 2.5×）+ 中心宝石
        paint.setStrokeWidth(2.1f);
        drawGear(canvas, centerX, centerY, baseRadius * 0.22f, 12, 6f,
                -escapementAngle * 2.5f, aA72, null);
        drawJewel(canvas, centerX, centerY, 4.5f, 2.5f);

        // 4. 行星轮 ×3（已移除：机芯精简）

        // 5. 摆轮 + 游丝（已移除：机芯精简）

        // 6. 传动轮 / 7. 秒轮 / 8. 介轮（已移除：机芯精简，只留主擒纵轮 + 中心太阳轮）

        // 9. 焦轴基准线 + 箭头（0° 水平向右）
        paint.setColor(aA72);
        paint.setStrokeWidth(2f);
        canvas.drawLine(centerX + baseRadius * 0.8f, centerY, centerX + baseRadius * 1.30f, centerY, paint);
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(centerX + baseRadius * 1.30f, centerY - 4f);
        path.lineTo(centerX + baseRadius * 1.30f + 8f, centerY);
        path.lineTo(centerX + baseRadius * 1.30f, centerY + 4f);
        path.close();
        paint.setColor(aA72);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    // ===== 齿轮/辐条/游丝/日内瓦 =====

    /** 角度步进：100° 弧 9 行可见窗（与 LyricView 共用同一常量来源） */
    public static final float ANGLE_STEP_RAD =
            (float) (Math.toRadians(100f) / 8f);

    /** 梯形齿齿轮（folia drawGearTeeth 直译；齿形 Path 复用） */
    private void drawGear(Canvas canvas, float cx, float cy, float radius, int teeth,
                          float toothDepth, float rotation, int strokeColor, Integer fillColor) {
        float innerR = radius - toothDepth, outerR = radius;
        float perTooth = (float) (Math.PI * 2) / teeth;
        path.reset();
        for (int i = 0; i < teeth; i++) {
            float base = i * perTooth;
            float a0 = base - perTooth * 0.22f, a1 = base - perTooth * 0.12f;
            float a2 = base + perTooth * 0.12f, a3 = base + perTooth * 0.22f;
            float x0 = cx + innerR * cos(a0 + rotation), y0 = cy + innerR * sin(a0 + rotation);
            float x1 = cx + outerR * cos(a1 + rotation), y1 = cy + outerR * sin(a1 + rotation);
            float x2 = cx + outerR * cos(a2 + rotation), y2 = cy + outerR * sin(a2 + rotation);
            float x3 = cx + innerR * cos(a3 + rotation), y3 = cy + innerR * sin(a3 + rotation);
            if (i == 0) path.moveTo(x0, y0); else path.lineTo(x0, y0);
            path.lineTo(x1, y1);
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
        }
        path.close();
        if (fillColor != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fillColor);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
        paint.setColor(strokeColor);
        canvas.drawPath(path, paint);
    }

    /** 辐条轮：轮毂+轮辋+辐条+减重孔（folia drawSpokedWheel 直译） */
    private void drawSpokedWheel(Canvas canvas, float cx, float cy, float hubR, float rimR,
                                 int spokes, float rotation, int color) {
        paint.setColor(color);
        canvas.drawCircle(cx, cy, hubR, paint);
        canvas.drawCircle(cx, cy, rimR, paint);
        float step = (float) (Math.PI * 2) / spokes;
        for (int i = 0; i < spokes; i++) {
            float a = i * step + rotation;
            canvas.drawLine(cx + hubR * cos(a), cy + hubR * sin(a),
                    cx + rimR * cos(a), cy + rimR * sin(a), paint);
        }
        // 减重孔（已移除：机芯精简）
    }

    /** 日内瓦条纹：9 条平行线裁剪在轮面内（save/clipRect 用 canvas.clipPath） */
    /** 宝石轴承：双环 */
    private void drawJewel(Canvas canvas, float cx, float cy, float outerR, float innerR) {
        paint.setColor(withAlpha(accentColor, 0.65f));
        paint.setStrokeWidth(outerR * 0.28f);
        canvas.drawCircle(cx, cy, outerR, paint);
        paint.setColor(withAlpha(accentColor, 0.28f));
        paint.setStrokeWidth(innerR * 0.35f);
        canvas.drawCircle(cx, cy, innerR, paint);
    }

    private static float cos(float a) { return (float) Math.cos(a); }
    private static float sin(float a) { return (float) Math.sin(a); }
    private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (Math.min(1f, Math.max(0f, alpha)) * 255);
        return (a << 24) | (color & 0xFFFFFF);
    }
}
