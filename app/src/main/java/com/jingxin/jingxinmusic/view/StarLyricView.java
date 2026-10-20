package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.jingxin.jingxinmusic.util.KrcParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 星空歌词（星空漫游效果的三行歌词系统）
 *
 * 结构（对齐截图）：中上区域两行——上面一行=下一句（灰蓝小字），中间=当前句（大字浅冰蓝+柔光）。
 * 换行动画的灵魂：下一句"从星空出现"——小字淡出 → 大字在新句浮现（放大+星尘粒子伴随）。
 *
 * 数据：LyricData 行列表 + 进度回调；换行判定二分查找当前行。
 */
public class StarLyricView extends View {

    // 配色（截图规格）
    private static final int COLOR_CURRENT = Color.rgb(0xC9, 0xE4, 0xFF); // 浅冰蓝（当前行/歌名共用）
    private static final int COLOR_NEXT = Color.rgb(0x8A, 0x96, 0xA6);    // 灰蓝（低饱和）
    private static final int COLOR_GLOW = Color.argb(0xB4, 0x9A, 0xC4, 0xF0); // 柔光

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dustPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();

    private List<KrcParser.LyricLine> lines = Collections.emptyList();
    private int currentIndex = -1;
    private String titleText = ""; // 歌名+歌手（本视图自绘，与歌词同坐标系锚定零误差）

    // 换行动画（600ms）
    private static final long ANIM_MS = 600L;
    private long animStart = -1L;
    private String animFromText, animToText;
    private float fromX, fromY, fromSize;

    // 星尘粒子
    private static final class Dust {
        float x, y, vx, vy, radius, lifeRatio;
    }
    private final List<Dust> dusts = new ArrayList<>();

    // 布局缓存
    private int layoutW, layoutH;
    private float textSizeCurrent, textSizeNext;

    public StarLyricView(Context context) { super(context); init(); }
    public StarLyricView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public StarLyricView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setLyricData(KrcParser.LyricData data) {
        this.lines = (data != null && data.lines != null) ? data.lines : Collections.emptyList();
        this.currentIndex = -1;
        requestLayout();
        invalidate();
    }

    /** 进度回调：换行判定（二分找 currentTime >= startTime 的最后一行） */
    public void updatePosition(int positionMs) {
        if (lines.isEmpty()) return;
        int lo = 0, hi = lines.size() - 1, idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startTime <= positionMs) { idx = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        if (idx != currentIndex) {
            onLineChanged(idx);
        }
    }

    private void onLineChanged(int newIndex) {
        String oldText = currentIndex >= 0 && currentIndex < lines.size()
                ? lines.get(currentIndex).text : null;
        int oldIndex = currentIndex;
        currentIndex = newIndex;
        if (newIndex < 0 || newIndex >= lines.size()) { invalidate(); return; }

        // 换行：从星空中浮现（旧渐隐/星尘 + 新从上方小字位落入中心）
        animFromText = oldText;
        animToText = lines.get(newIndex).text;
        animStart = System.currentTimeMillis();

        // 星尘粒子：伴随新句浮现
        spawnDusts();

        // 计算旧字位置（用于淡出）
        if (oldIndex >= 0 && animFromText != null) {
            float oldSize = textSizeCurrent;
            textPaint.setTextSize(oldSize);
            fromX = layoutW / 2f;
            fromY = currentLineY();
            fromSize = oldSize;
        }
        invalidate();
        postInvalidateOnAnimation();
        postDelayed(this::invalidate, ANIM_MS);
    }

    private void spawnDusts() {
        dusts.clear();
        int n = 7 + random.nextInt(4);
        float cx = layoutW / 2f, cy = currentLineY();
        for (int i = 0; i < n; i++) {
            Dust d = new Dust();
            d.x = cx + (random.nextFloat() - 0.5f) * layoutW * 0.6f;
            d.y = cy + (random.nextFloat() - 0.5f) * currentTextSizePx() * 2f;
            d.vx = (random.nextFloat() - 0.5f) * 0.8f;
            d.vy = (random.nextFloat() - 0.5f) * 0.6f;
            d.radius = 2f + random.nextFloat() * 3.5f;
            d.lifeRatio = 1f;
            dusts.add(d);
        }
    }

    // 锚点：歌名底部（本视图局部坐标；内部自算：页顶 16dp + 标题行高）
    private float titleBottomY(float density) {
        // 歌名底部（页顶 16dp + 歌名行高；歌名字号=预唱行字号）
        return (float) Math.ceil(density * 16f) + textSizeNext * 1.25f;
    }

    private float currentTextSizePx() {
        return Math.max(18f, layoutW * 0.052f);
    }

    private float nextTextSizePx() {
        return currentTextSizePx() * 0.55f;
    }

    /** 当前行基线：文字底部落在页面垂直正中（基线 = 中线 + 0.15 字高 desender 下探） */
    private float currentLineY() {
        return layoutH * 0.50f + textSizeCurrent * 0.15f;
    }

    /** 预唱行基线：文字顶部 = 歌名底部 + 预唱行高（基线 = 顶部 + 0.82 字高 ascent）
     *  （规则：预唱行顶部与歌名底部之间的距离 = 预唱行自身高度，即一行的间隔） */
    private float nextLineY() {
        float density = getResources().getDisplayMetrics().density;
        return titleBottomY(density) + textSizeNext + 0.82f * textSizeNext;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w; layoutH = h;
        textSizeCurrent = currentTextSizePx();
        textSizeNext = nextTextSizePx();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layoutW <= 0) return;

        long now = System.currentTimeMillis();
        boolean animating = animStart > 0 && now - animStart < ANIM_MS;

        // 歌名常驻（换行动画不作用于歌名：独立绘制，两分支均画，杜绝动画期间歌名消失）
        drawTitle(canvas);
        if (animating) {
            drawTransition(canvas, now);
        } else {
            drawSteady(canvas);
        }
        drawDusts(canvas, now);
    }

    /** 歌名文案（歌名 · 歌手）；与本视图同坐标系自绘 */
    public void setTitleText(String text) {
        this.titleText = (text != null ? text : "");
        invalidate();
    }

    /** 歌名常驻绘制（页面顶部居中）：颜色同当前行浅冰蓝、粗体、字号同预唱行 */
    private void drawTitle(Canvas canvas) {
        if (titleText.isEmpty()) return;
        float density = getResources().getDisplayMetrics().density;
        textPaint.setTextSize(textSizeNext);
        textPaint.setColor(COLOR_CURRENT);
        textPaint.setFakeBoldText(true);
        textPaint.setShadowLayer(0, 0, 0, 0);
        canvas.drawText(titleText, layoutW / 2f,
                (float) Math.ceil(density * 16f) + textSizeNext * 0.85f, textPaint);
        textPaint.setFakeBoldText(false);
    }

    private void drawSteady(Canvas canvas) {
        // 下一句（上方小字）
        String nextText = textOf(currentIndex + 1);
        if (nextText != null) {
            textPaint.setTextSize(textSizeNext);
            textPaint.setColor(COLOR_NEXT);
            textPaint.setShadowLayer(0, 0, 0, 0);
            canvas.drawText(nextText, layoutW / 2f, nextLineY(), textPaint);
        }
        // 当前句（中心大字+柔光）
        String curText = textOf(currentIndex);
        if (curText != null) {
            textPaint.setTextSize(textSizeCurrent);
            textPaint.setColor(COLOR_CURRENT);
            textPaint.setShadowLayer(18f, 0f, 0f, COLOR_GLOW);
            canvas.drawText(curText, layoutW / 2f, currentLineY(), textPaint);
        }
    }

    private void drawTransition(Canvas canvas, long now) {
        float t = DecelerateInterpolatorHolder.INSTANCE.getInterpolation(
                (now - animStart) / (float) ANIM_MS);

        // 旧句：整体淡出
        if (animFromText != null) {
            float oldAlpha = 1f - t;
            if (oldAlpha > 0.02f) {
                textPaint.setTextSize(fromSize > 0 ? fromSize : textSizeCurrent);
                int a = (int) (oldAlpha * 255);
                textPaint.setColor((a << 24) | (COLOR_CURRENT & 0xFFFFFF));
                textPaint.setShadowLayer(0, 0, 0, 0);
                canvas.drawText(animFromText, fromX > 0 ? fromX : layoutW / 2f, fromY > 0 ? fromY : currentLineY(), textPaint);
            }
        }

        // 新句：从下一行位置（小字位）落入当前行位置，字号小→大，透明→亮
        float startY = nextLineY(), endY = currentLineY();
        float size = textSizeNext + (textSizeCurrent - textSizeNext) * t;
        textPaint.setTextSize(size);
        int a = (int) (t * 255);
        textPaint.setColor((a << 24) | (COLOR_CURRENT & 0xFFFFFF));
        textPaint.setShadowLayer(18f * t, 0f, 0f, COLOR_GLOW);
        if (animToText != null) {
            canvas.drawText(animToText, layoutW / 2f, startY + (endY - startY) * t, textPaint);
        }

        // 转场期间下一行位置显示"再下一句"淡入
        String next2 = textOf(currentIndex + 1);
        if (next2 != null) {
            textPaint.setTextSize(textSizeNext);
            int na = (int) (t * 220);
            textPaint.setColor((na << 24) | (COLOR_NEXT & 0xFFFFFF));
            textPaint.setShadowLayer(0, 0, 0, 0);
            canvas.drawText(next2, layoutW / 2f, nextLineY(), textPaint);
        }
    }

    private void drawDusts(Canvas canvas, long now) {
        if (dusts.isEmpty()) return;
        float t = Math.min(1f, (now - animStart) / (float) ANIM_MS);
        if (t >= 1f) { dusts.clear(); return; }
        float alpha = (1f - t);
        for (Dust d : dusts) {
            float x = d.x + d.vx * t * 40f;
            float y = d.y + d.vy * t * 40f;
            float r = d.radius * (0.6f + 0.8f * t); // 先放大再随 alpha 消散
            int a = (int) (alpha * 230);
            dustPaint.setColor(a << 24 | 0xE8F2FF);
            dustPaint.setShader(new RadialGradient(x, y, r * 2.6f,
                    (a << 24) | 0xE8F2FF, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r * 2.6f, dustPaint);
        }
        if (t < 1f) postInvalidateOnAnimation();
    }

    private String textOf(int idx) {
        if (idx >= 0 && idx < lines.size()) {
            String t = lines.get(idx).text;
            return (t != null && !t.isEmpty()) ? t : null;
        }
        return null;
    }

    /** 复用插值器 */
    private static final class DecelerateInterpolatorHolder {
        static final android.view.animation.DecelerateInterpolator INSTANCE =
                new android.view.animation.DecelerateInterpolator();
    }
}
