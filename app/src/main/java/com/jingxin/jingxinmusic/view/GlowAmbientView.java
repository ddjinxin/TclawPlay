package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 流光氛围底（GlowEffectPage 专用）：封面主色调的"由远及近"光尘
 *
 * 运动模型（景深隧道）：
 * - 光尘在远处视点（屏中偏上 40%）附近诞生：小而暗
 * - 深度 z 从 0→1：粒子沿"视点→初始方向"的射线向外扩散，先慢后快（easeIn z^1.6），
 *   半径随之放大、亮度先亮后隐——近大远小 + 近快远慢 = 尘埃迎面而来
 * - 临镜淡出（z 86%~100%），越过即回收回视点重生，循环
 * - 方向：全屏均匀散布（能飘出屏的也能从视野外回来，上下都不留空区）
 * - 密度按画布面积（1/22000px²），固定种子 20261022L：每次进入同一片尘
 * - 颜色：封面主色 lighten 浅亮版（黑底上发亮）；词的光晕仍是主角
 * - 屏底另有一层超暗主色光雾（4%~6%，12s 呼吸），位置不动
 */
public class GlowAmbientView extends View {

    private static final long SEED = 20261022L;

    private static final class Mote {
        float ox, oy;      // 相对视点的初始方向偏移（决定散射角）
        float z;           // 深度 0=最远 1=最近（掠过镜头前）
        float zSpeed;      // 接近速度（每秒推进的深度比例）
        float radiusBase;  // 基础半径（随 z 放大）
        float alphaMax;    // 峰值亮度
        float wobA, wobW, wobPhase;  // 轨迹微扰（避免完美直线，像气流里的尘）
        float twinkleW, twinklePhase;
    }
    private final List<Mote> motes = new ArrayList<>();
    private final Paint motePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hazePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random recycleRandom = new Random();

    /** 光尘颜色（默认浅蓝白，注入封面主色后 lighten） */
    private int tintColor = Color.rgb(0x9E, 0xC4, 0xF0);
    private int tintRaw;

    private int layoutW, layoutH;
    private long lastFrameNs = -1L;
    private boolean running = false;
    private long startMs;

    // Choreographer 驱动（同 StarfieldView：持有回调字段，attach/detach 启停）
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            long deltaNs = lastFrameNs < 0 ? 0 : frameTimeNanos - lastFrameNs;
            lastFrameNs = frameTimeNanos;
            float dt = Math.min(0.064f, deltaNs / 1_000_000_000f); // 钳制防跳帧瞬移
            for (int i = 0; i < motes.size(); i++) {
                Mote m = motes.get(i);
                m.z += m.zSpeed * dt;
                if (m.z >= 1f) recycle(m); // 掠过镜头：回视点重生
            }
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public GlowAmbientView(Context context) { super(context); }
    public GlowAmbientView(Context context, AttributeSet attrs) { super(context, attrs); }
    public GlowAmbientView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** 注入封面主色（上/下色，微尘走浅亮版） */
    public void setTintColors(int top, int bottom) {
        if (top != 0) tintRaw = top;
        else if (bottom != 0) tintRaw = bottom;
        tintColor = lighten(tintRaw != 0 ? tintRaw : tintColor);
        invalidate();
    }

    /** 提亮到浅色：黑底上光尘应发亮——亮度拉到 72%+、降饱和（保色相发柔） */
    private static int lighten(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = hsv[1] * 0.55f;
        hsv[2] = Math.max(hsv[2], 0.72f);
        return Color.HSVToColor(hsv);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w; layoutH = h;
        spawn();
    }

    /** 固定种子生成初始光尘群（深度散布 0~0.8，画面一进来就有层次） */
    private void spawn() {
        motes.clear();
        if (layoutW <= 0 || layoutH <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        Random r = new Random(SEED);
        int count = Math.max(12, (int) (layoutW * layoutH / 22000f));
        for (int i = 0; i < count; i++) {
            Mote m = new Mote();
            randomizeDirection(m, r);
            m.z = r.nextFloat() * 0.8f;
            m.zSpeed = 1f / (7f + r.nextFloat() * 11f); // 单程 7~18s
            // 大小两档：75% 小粒(1.2~2.6dp) + 25% 大粒(3.0~4.2dp)——大粒减半自原均布
            boolean big = r.nextFloat() < 0.25f;
            m.radiusBase = (big
                    ? 3.0f + r.nextFloat() * 1.2f
                    : 1.2f + r.nextFloat() * 1.4f) * density;
            m.alphaMax = 0.28f + r.nextFloat() * 0.24f; // 峰值 28%~52%
            m.wobA = density * (2f + r.nextFloat() * 6f);
            m.wobW = 0.15f + r.nextFloat() * 0.3f;
            m.wobPhase = r.nextFloat() * (float) (Math.PI * 2);
            m.twinkleW = 0.4f + r.nextFloat() * 0.8f;
            m.twinklePhase = r.nextFloat() * (float) (Math.PI * 2);
            motes.add(m);
        }
    }

    /** 散射方向：全屏均匀（上下左右都能到）；范围按对角线留足，顶部不留空区 */
    private void randomizeDirection(Mote m, Random r) {
        m.ox = (r.nextFloat() - 0.5f) * 2f * layoutW * 0.70f;
        m.oy = (r.nextFloat() - 0.5f) * 2f * layoutH * 1.05f;
    }

    /** 掠过镜头后回收：回视点重掷方向/呼吸（z 归零，视觉上从深处再次出发） */
    private void recycle(Mote m) {
        randomizeDirection(m, recycleRandom);
        m.z = 0f;
        m.zSpeed = 1f / (7f + recycleRandom.nextFloat() * 11f);
        m.wobA = 2f + recycleRandom.nextFloat() * 6f;
        m.wobPhase = recycleRandom.nextFloat() * (float) Math.PI * 2;
        m.twinklePhase = recycleRandom.nextFloat() * (float) Math.PI * 2;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        startMs = System.currentTimeMillis();
        lastFrameNs = -1L;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layoutW <= 0 || layoutH <= 0) return;
        float ts = (System.currentTimeMillis() - startMs) / 1000f;

        // 屏底超暗主色光雾（4%~6% 呼吸，12s 周期；用原始主色，不提亮）
        float haze = 0.05f + 0.01f * (float) Math.sin(ts * (float) (Math.PI * 2 / 12.0));
        int hazeColor = tintRaw != 0 ? tintRaw : tintColor;
        hazePaint.setShader(new RadialGradient(
                layoutW / 2f, layoutH * 1.15f, layoutH * 0.85f,
                ((int) (haze * 255) << 24) | (hazeColor & 0xFFFFFF),
                0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, layoutH * 0.35f, layoutW, layoutH, hazePaint);

        // 光尘隧道：视点在屏中偏上（40%），z 越大离镜头越近
        float vpX = layoutW * 0.5f, vpY = layoutH * 0.40f;
        for (int i = 0; i < motes.size(); i++) {
            Mote m = motes.get(i);
            float z = m.z;
            // 位移扩展：easeIn z^1.6 —— 远处慢（景深缓行），临近加速外扩（冲镜感）；
            // 系数上限 1.75：视野最远能覆盖布局对角，顶部无空区
            float g = 0.22f + 1.75f * (float) Math.pow(z, 1.6f);
            float wob = (float) Math.sin(m.wobW * ts + m.wobPhase) * m.wobA * g;
            float x = vpX + m.ox * g + wob;
            float y = vpY + m.oy * g + wob * 0.6f;
            float radius = m.radiusBase * (0.35f + 1.15f * z);
            // 生命周期亮度：深处淡入（0~16%）、临镜淡出（86%~100%），中途峰值呼吸
            float fadeIn = Math.min(1f, z / 0.16f);
            float fadeOut = Math.min(1f, (1f - z) / 0.14f);
            float tw = 0.65f + 0.35f * (float) Math.sin(m.twinkleW * ts + m.twinklePhase);
            int a = (int) (m.alphaMax * fadeIn * fadeOut * tw * 255);
            if (a <= 3) continue;
            motePaint.setShader(new RadialGradient(x, y, radius * 2.2f,
                    (a << 24) | (tintColor & 0xFFFFFF), 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, radius * 2.2f, motePaint);
        }
    }
}
