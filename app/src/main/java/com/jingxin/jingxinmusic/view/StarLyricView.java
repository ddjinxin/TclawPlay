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
    // 全部文字颜色统一跟随播放页高亮色（COLOR_CURRENT 为未注入时的兜底）
    private static final int COLOR_CURRENT = Color.rgb(0xC9, 0xE4, 0xFF); // 浅冰蓝（兜底）

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

        // 计算旧字位置（用于淡出；字号按旧文本自适应防淡出溢出）
        if (oldIndex >= 0 && animFromText != null) {
            fromSize = fitTextSize(animFromText, textSizeCurrent);
            fromX = layoutW / 2f;
            fromY = currentLineY();
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

    private boolean isPortrait() {
        return layoutH > layoutW;
    }

    /** 歌名顶部页边距：横屏 16dp；竖屏再下移一倍歌名自身字号（+1×字高） */
    private float titleTopOffset(float density) {
        return (float) Math.ceil(density * 16f) + (isPortrait() ? textSizeNext : 0f);
    }

    // 锚点：歌名底部（本视图局部坐标；页顶偏移 + 歌名行高；歌名字号=预唱行字号）
    private float titleBottomY(float density) {
        return titleTopOffset(density) + textSizeNext * 1.25f;
    }

    /** 当前行字号：横屏 5.2% 屏宽（参考图宽屏规格）；竖屏 8%（屏宽窄，视觉重量补偿） */
    private float currentTextSizePx() {
        float ratio = layoutW > layoutH ? 0.052f : 0.08f;
        return Math.max(18f, layoutW * ratio);
    }

    private float nextTextSizePx() {
        return currentTextSizePx() * 0.55f;
    }

    /** 当前行基线：文字底部落在页面垂直正中（基线 = 中线 + 0.15 字高 desender 下探） */
    private float currentLineY() {
        return layoutH * 0.50f + textSizeCurrent * 0.15f;
    }

    /** 预唱行基线：文字顶部 = 歌名底部 + 预唱行高 ×行距倍数（基线 = 顶部 + 0.82 字高 ascent）
     *  横屏：距歌名 1× 行高；竖屏：3× 行高（拉开） */
    private float nextLineY() {
        float density = getResources().getDisplayMetrics().density;
        float gapLines = isPortrait() ? 3f : 1f;
        return titleBottomY(density) + gapLines * textSizeNext + 0.82f * textSizeNext;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w; layoutH = h;
        textSizeCurrent = currentTextSizePx();
        textSizeNext = nextTextSizePx();
    }

    // 预唱行缩放揭示：仅在换行转场内一次性完成（0.55→1.0 随主动画 t），
    // 稳态不重跑（否则转场结束后文字先缩回再长一次，多余动作）

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

    // 颜色：与播放页高亮歌词色一致（效果页注入；未注入时退回浅冰蓝）
    private int colorHighlight = COLOR_CURRENT;

    /** 注入播放页高亮歌词色（跟随主题/自定义色/光晕动态色），全部文字统一使用 */
    public void setHighlightColor(int color) {
        if (color != 0) this.colorHighlight = color;
        invalidate();
    }

    /** 预唱行抖动：微幅正弦振动（呼吸感，幅度随字高缩放；px 偏移） */
    private float nextJitterOffsetPx(long nowMs) {
        float amplitude = textSizeNext * 0.045f; // 约 4.5% 字高
        float t = (nowMs % 1400L) / 1400f;
        return amplitude * (float) Math.sin(t * 2 * Math.PI);
    }

    /** 歌名文案（歌名 - 歌手）；与本视图同坐标系自绘 */
    public void setTitleText(String text) {
        this.titleText = (text != null ? text : "");
        invalidate();
    }

    /** 歌名常驻绘制（页面顶部居中）：颜色同当前行浅冰蓝、粗体、字号同预唱行 */
    private void drawTitle(Canvas canvas) {
        if (titleText.isEmpty()) return;
        float density = getResources().getDisplayMetrics().density;
        textPaint.setTextSize(textSizeNext);
        textPaint.setColor(colorHighlight);
        textPaint.setFakeBoldText(true);
        textPaint.setShadowLayer(0, 0, 0, 0);
        canvas.drawText(titleText, layoutW / 2f,
                titleTopOffset(density) + textSizeNext * 0.85f, textPaint);
        textPaint.setFakeBoldText(false);
    }

    /** 宽度自适应字号：按文本实测宽缩小至可用宽（留 6% 两侧边距）；不超原字号则原字号返回 */
    private float fitTextSize(String text, float targetSize) {
        if (text == null || text.isEmpty() || layoutW <= 0) return targetSize;
        float avail = layoutW * 0.94f;
        textPaint.setTextSize(targetSize);
        float measured = textPaint.measureText(text);
        if (measured <= avail) return targetSize;
        // 字号上限下保护（防超长串缩到不可读）：最低不小于预唱行字号的 1.1 倍
        float min = textSizeNext * 1.1f;
        float scaled = targetSize * (avail / measured);
        return Math.max(min, scaled);
    }

    private void drawSteady(Canvas canvas) {
        // 下一句（上方小字）：颜色同高亮、90% 透明度、微抖动（缩放揭示只在换行转场内一次完成）
        // 超宽同样自动缩字号适配页宽
        String nextText = textOf(currentIndex + 1);
        if (nextText != null) {
            textPaint.setTextSize(fitTextSize(nextText, textSizeNext));
            textPaint.setColor((230 << 24) | (colorHighlight & 0xFFFFFF)); // 90% alpha
            textPaint.setShadowLayer(0, 0, 0, 0);
            float jitter = nextJitterOffsetPx(System.currentTimeMillis());
            canvas.drawText(nextText, layoutW / 2f + jitter, nextLineY() + jitter * 0.4f, textPaint);
            postInvalidateOnAnimation(); // 抖动持续驱动
        }
        // 当前句（中心大字+柔光）：颜色与光晕均随高亮色
        String curText = textOf(currentIndex);
        if (curText != null) {
            textPaint.setTextSize(fitTextSize(curText, textSizeCurrent));
            textPaint.setColor(colorHighlight);
            textPaint.setShadowLayer(0, 0, 0, 0); // 光晕已取消：纯文字呈现
            canvas.drawText(curText, layoutW / 2f, currentLineY(), textPaint);
        }
    }

    private void drawTransition(Canvas canvas, long now) {
        float t = DecelerateInterpolatorHolder.INSTANCE.getInterpolation(
                (now - animStart) / (float) ANIM_MS);

        // 旧句（当前行让位）：边放大边下落，一直坠到页面底部（全程加速）
        if (animFromText != null) {
            float oldAlpha = (1f - t) * (1f - t);
            if (oldAlpha > 0.02f) {
                float burst = t * t; // 加速：前段即完成大半行程
                float oldSize = fromSize > 0 ? fromSize : fitTextSize(animFromText, textSizeCurrent);
                textPaint.setTextSize(oldSize * (1f + 0.55f * burst)); // 沿路持续放大
                int a = (int) (oldAlpha * 255);
                textPaint.setColor((a << 24) | (colorHighlight & 0xFFFFFF));
                textPaint.setShadowLayer(0, 0, 0, 0);
                float startY = fromY > 0 ? fromY : currentLineY();
                float endY = layoutH + fromSize * 0.3f; // 终点：页面底（再探出少许，整行出屏）
                float dropY = startY + (endY - startY) * burst;
                canvas.drawText(animFromText, fromX > 0 ? fromX : layoutW / 2f, dropY, textPaint);
            }
        }

        // 新句：从下一行位置（小字位）落入当前行位置，字号小→大（终点为自适应字号），透明→亮
        float startY = nextLineY(), endY = currentLineY();
        float endSize = animToText != null ? fitTextSize(animToText, textSizeCurrent) : textSizeCurrent;
        // 旧句淡出字号也走自适应（否则旧长句淡出时溢出屏）
        if (animFromText != null && fromSize <= 0) {
            fromSize = fitTextSize(animFromText, textSizeCurrent);
        }
        float size = textSizeNext + (endSize - textSizeNext) * t;
        textPaint.setTextSize(size);
        int a = (int) (t * 255);
        textPaint.setColor((a << 24) | (colorHighlight & 0xFFFFFF));
        textPaint.setShadowLayer(0, 0, 0, 0); // 光晕已取消
        if (animToText != null) {
            canvas.drawText(animToText, layoutW / 2f, startY + (endY - startY) * t, textPaint);
        }

        // 转场期间下一行位置显示"再下一句"：从小到大缩放 + 淡入（颜色同高亮 90%）
        String next2 = textOf(currentIndex + 1);
        if (next2 != null) {
            float scale2 = 0.55f + 0.45f * t;
            textPaint.setTextSize(textSizeNext * scale2);
            int na = (int) (t * 220);
            textPaint.setColor((na << 24) | (colorHighlight & 0xFFFFFF));
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
