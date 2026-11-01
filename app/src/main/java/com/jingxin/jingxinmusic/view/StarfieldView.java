package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Bitmap;
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
 * 星空背景（星空歌词效果的底幕）
 *
 * 视觉规格（自拟：深空径向渐变+星云+双色星点）：
 * - 深空渐变：中心纯黑 → 左右边缘深蓝黑（径向），缓存位图不逐帧重画
 * - 两团半透明灰蓝星云（左右边缘淡雾）
 * - 星点两层：多数细小白点 + 少量亮星；每颗独立闪烁（随机相位/周期 2~4s 正弦呼吸）
 * - 密度疏朗：约 1星/9000px²
 */
public class StarfieldView extends View {

    private static final class Star {
        float x, y;            // 相对比例 0~1
        float radius;          // 像素半径
        float baseAlpha;       // 基础透明度
        long twinklePeriodMs;  // 闪烁周期
        float phase;           // 相位
        boolean bright;        // 亮星（带十字光）
    }

    private final List<Star> stars = new ArrayList<>();
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private Paint bgCachePaint;
    private Bitmap bgCache;
    private int cachedW, cachedH;
    private boolean running = false;
    private long animStartMs;
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (running) {
                // 全量 invalidate：onDraw 先画缓存背景位图（快）再画星点层
                invalidate();
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public StarfieldView(Context context) { super(context); init(); }
    public StarfieldView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public StarfieldView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() { setLayerType(LAYER_TYPE_NONE, null); }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            spawnStars(w, h);
            buildBgCache(w, h);
        }
    }

    /** 星点生成：疏朗两层（97% 微小白点 + 3% 亮星） */
    private void spawnStars(int w, int h) {
        stars.clear();
        random.setSeed(20261020L); // 固定种子：星空形态稳定（每次进入同款星空）
        int count = Math.max(40, w * h / 9000);
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < count; i++) {
            Star s = new Star();
            s.x = random.nextFloat();
            s.y = random.nextFloat();
            s.bright = random.nextInt(100) < 3;
            s.radius = s.bright
                    ? (1.8f + random.nextFloat() * 1.4f) * density
                    : (0.6f + random.nextFloat() * 0.9f) * density;
            s.baseAlpha = s.bright ? 0.9f : 0.35f + random.nextFloat() * 0.4f;
            s.twinklePeriodMs = 2000L + (long) (random.nextFloat() * 2000L);
            s.phase = random.nextFloat() * (float) (2 * Math.PI);
            stars.add(s);
        }
    }

    /** 背景缓存：深空渐变 + 两团星云（静态，尺寸变时重建） */
    private void buildBgCache(int w, int h) {
        if (bgCache != null) { bgCache.recycle(); bgCache = null; }
        bgCache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bgCache);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 深空基底：中心深黑 → 边缘深蓝黑（径向）
        p.setShader(new android.graphics.RadialGradient(
                w / 2f, h * 0.42f, Math.max(w, h) * 0.75f,
                Color.rgb(2, 4, 10), Color.rgb(14, 26, 48),
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // 左右两团半透明灰蓝星云（径向淡雾）
        drawNebula(c, p, w * 0.10f, h * 0.30f, Math.max(w, h) * 0.38f,
                Color.argb(46, 96, 128, 168));
        drawNebula(c, p, w * 0.90f, h * 0.62f, Math.max(w, h) * 0.36f,
                Color.argb(40, 88, 110, 150));
    }

    private void drawNebula(Canvas c, Paint p, float cx, float cy, float r, int color) {
        p.setShader(new RadialGradient(cx, cy, r, color, Color.argb(0, 0, 0, 0),
                Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (bgCache != null && !bgCache.isRecycled()) {
            canvas.drawBitmap(bgCache, 0, 0, null);
        }

        long t = (System.currentTimeMillis() - animStartMs);
        for (Star s : stars) {
            float twinkle = 0.55f + 0.45f
                    * (float) Math.sin(2 * Math.PI * (t % s.twinklePeriodMs) / s.twinklePeriodMs + s.phase);
            int alpha = (int) (s.baseAlpha * twinkle * 255);
            if (alpha <= 3) continue;
            starPaint.setColor(alpha << 24 | 0xFFFFFF); // 白星

            float px = s.x * w, py = s.y * h;
            if (s.bright) {
                // 亮星带十字微光
                canvas.drawCircle(px, py, s.radius, starPaint);
                int cross = (int) (alpha * 0.5f);
                starPaint.setColor(cross << 24 | 0xFFFFFF);
                canvas.drawLine(px - s.radius * 3.2f, py, px + s.radius * 3.2f, py, starPaint);
                canvas.drawLine(px, py - s.radius * 3.2f, px, py + s.radius * 3.2f, starPaint);
            } else {
                starPaint.setAntiAlias(true);
                canvas.drawCircle(px, py, s.radius, starPaint);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    private void start() {
        if (running) return;
        running = true;
        animStartMs = System.currentTimeMillis();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }
}
