package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import com.jingxin.jingxinmusic.util.KrcParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 心象歌词（第四种歌词动效·移植 folia cadenza：词云爆发）
 *
 * 当前行的每个词被打散成独立视觉对象，以"主角词"为中心碰撞散布在页心区域，
 * 词随音各自点亮发光、唱过慢漂离去——一行歌词是一幅词的心象图。
 *
 * 准备态（一行一次，行种子确定）：
 * - 字号自适应 clamp(屏宽×8.6%,34~94)−超12字罚分−超7词罚分，×fontScale 上限 132
 * - 折行（CJK 逐字/ASCII 整词；词可被折行劈开成片段）→ 词片段回映字素流
 * - hero 选举：CJK 权重 0.18/英文 ≤0.36×字素数 + 居中偏置 → 最高分词 1.46×放大居中
 * - 碰撞散布：hero 推离 + 椭圆螺旋采样找空位（score=重叠×2.2+行程）；找不到取最小重叠
 *
 * 每帧包络（纯时间函数 + 指数平滑）：
 * - 词三态 waiting(不可见/blur10)→active(scale×1.3+10Hz脉冲±4%/颜色混合)→passed(0.82alpha+5s外漂+缓转)
 * - 字素辉光：非 CJK 逐字字母三层 glow；CJK 整词亮（字素=字本身）
 * - 行级：入场 opacity/scale0.9/blur10→清、退场 scale1.1+blur20
 * - energy 能量 → 页心竖直浮动 + 词局漂（audioPower 语义，无则 0）
 */
public class CadenzaLyricView extends View {

    // ===== tuning（folia DEFAULT_CADENZA_TUNING） =====
    private static final float FONT_SCALE = 1.12f;
    private static final float GLOW_INTENSITY = 1f;
    private static final float MOTION_AMOUNT = 1f;

    private static final float HERO_SCALE = 1.46f;
    private static final float PULSE_FREQUENCY = 10f;   // 10Hz 脉动
    private static final float PASSED_ALPHA = 0.82f;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<KrcParser.LyricLine> lines = Collections.emptyList();
    private int targetIndex = -1;
    private int positionMs;
    private String titleText = "";
    private int colorPrimary = Color.rgb(0xB9, 0xD7, 0xF2);   // 基色（passed/waiting）
    private int colorAccent = Color.rgb(0xC9, 0xE4, 0xFF);    // 高亮色
    private float energy;                                     // 0~1 音乐能量（audioPower 语义）

    private int layoutW, layoutH;
    private float titleSize;

    // ===== 准备态（一行一次） =====
    private static final class Placement {
        // 静态落点
        String text;              // 片段文本
        String wordText;          // 原词全文（hero/词性判断）
        int wordStart, wordEnd;   // 词绝对时间 ms
        int fragGlobalStart, fragGlobalEnd; // 片段在词内的字素区间（跨行拆开用）
        boolean cjk;
        float x, y;               // 落点中心（页心坐标系：原点=页心偏移锚）
        float width, height;
        float scale;              // 含 hero 1.46×
        float passedRotate;       // passed 缓转
        float passedDriftX, passedDriftY; // passed 外漂向量
        boolean isHero;
        // 每帧平滑态
        float ax, ay, aRotation, aScale, aBodyAlpha, aBlur, aActiveMix, aGlowAlpha;
        boolean initialized;
    }
    private Placement[] placements = new Placement[0];
    private int prevTargetIndex = -2;

    // 行级包络
    private long lineEnterMs = -1L, lineExitStartMs = -1L;
    private float prevLineOpacity = 1f;

    private boolean running = false;
    private long lastFrameNs = -1L;
    private long startMs;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            lastFrameNs = frameTimeNanos;
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public CadenzaLyricView(Context context) { super(context); init(); }
    public CadenzaLyricView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public CadenzaLyricView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    // ===== 对外接口 =====

    public void setLyricData(KrcParser.LyricData data) {
        this.lines = (data != null && data.lines != null) ? data.lines : Collections.emptyList();
        this.targetIndex = -1;
        this.prevTargetIndex = -2;
        this.placements = new Placement[0];
        invalidate();
    }

    public void updatePosition(int positionMs) {
        this.positionMs = positionMs;
        if (lines.isEmpty()) {
            if (targetIndex != -1) { targetIndex = -1; placements = new Placement[0]; }
            return;
        }
        int lo = 0, hi = lines.size() - 1, idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startTime <= positionMs) { idx = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        if (idx != targetIndex) {
            targetIndex = idx;
            if (idx >= 0) prepareLine(idx);
        }
    }

    public void setColors(int primary, int accent) {
        if (primary != 0) colorPrimary = primary;
        if (accent != 0) colorAccent = accent;
        invalidate();
    }

    public void setTitleText(String t) { this.titleText = (t != null ? t : ""); invalidate(); }

    /** 注入音乐能量 0~1（audioPower/255；无频谱时 0——词云静态呼吸） */
    public void setEnergy(float e) { this.energy = clamp01(e); }

    // ===== 准备态：一行一次 =====

    private void prepareLine(int lineIdx) {
        KrcParser.LyricLine line = lines.get(lineIdx);
        boolean hasWords = line.words != null && !line.words.isEmpty();
        boolean interlude = "......".equals(line.text);

        // 字号自适应（folia chooseFontPx + fontScale）
        int graphemeCount = line.text != null ? line.text.length() : 1;
        int wordCount = hasWords ? line.words.size() : 1;
        float widthBase = clamp(layoutW * 0.086f, 34f, 94f);
        float lengthPenalty = graphemeCount > 12 ? Math.min((graphemeCount - 12) * 1.8f, 34f) : 0f;
        float densityPenalty = wordCount > 7 ? Math.min((wordCount - 7) * 1.5f, 18f) : 0f;
        float fontPx = clamp(clamp(widthBase - lengthPenalty - densityPenalty, 28f, 104f) * FONT_SCALE, 24f, 132f);
        textPaint.setTextSize(fontPx);
        float lineHeight = Math.round(fontPx * (isCJK(line.text) ? 1.22f : 1.1f));

        // 可用宽（widthRatio 0.72 × 折行压缩）
        float available = Math.max(layoutW - 48f, 120f);
        float wrapCompression = graphemeCount > 12
                ? clamp(0.92f - (graphemeCount - 12) * 0.018f, 0.62f, 0.92f) : 0.92f;
        float maxWidth = clamp(Math.min(layoutW * 0.72f * wrapCompression, 820f),
                Math.min(220f, available), available);

        // 词列表（纯 LRC 退化：整行一个"词"）
        List<KrcParser.LyricWord> words = hasWords ? line.words : new ArrayList<>();
        if (!hasWords && line.text != null && !line.text.isEmpty()) {
            KrcParser.LyricWord w = new KrcParser.LyricWord();
            w.text = line.text;
            w.startTime = (int) line.startTime;
            w.duration = (int) Math.max(line.duration, 1L);
            words.add(w);
        }

        if (interlude) {
            // 间奏：全词居中放大静置
            placements = new Placement[words.size()];
            Random r = new Random(line.startTime);
            for (int i = 0; i < words.size(); i++) {
                Placement p = basePlacement(words.get(i), fontPx, r, 0, 0, 1f, false);
                p.x = 0; p.y = 0;
                placements[i] = p;
            }
            lineEnterMs = System.currentTimeMillis();
            lineExitStartMs = -1L;
            return;
        }

        // 词片段：跨行词拆半（folia fragment 逻辑本地化——按折行宽切词）
        List<Object[]> fragments = new ArrayList<>(); // [word, startX(词内偏移0), text, isHead]
        float x = 0f;
        for (KrcParser.LyricWord w : words) {
            float wWidth = textPaint.measureText(w.text);
            if (x + wWidth <= maxWidth) {
                fragments.add(new Object[]{w, w.text, true});
                x += wWidth;
            } else {
                // 词被折行劈开：按字素切到行尾，剩余进下一"行"（简化：劈两半）
                int cut = 1;
                float acc = 0f;
                for (int ci = 0; ci < w.text.length(); ci++) {
                    float cw = textPaint.measureText(String.valueOf(w.text.charAt(ci)));
                    if (acc + cw + x > maxWidth && ci > 0) { cut = ci; break; }
                    acc += cw;
                }
                fragments.add(new Object[]{w, w.text.substring(0, cut), true});
                fragments.add(new Object[]{w, w.text.substring(cut), false});
                x = textPaint.measureText(w.text) - acc;
            }
        }

        // ===== hero 选举（CJK 0.18 / 英文≤0.36×字素数 + 居中偏置） =====
        int heroIdx = -1; float heroScore = -1;
        int n = words.size();
        for (int i = 0; i < n; i++) {
            String wt = words.get(i).text;
            int gc = wt.length();
            float semantic = isCJK(wt) ? 0.18f : Math.min(gc * 0.08f, 0.36f);
            float centerBias = 1f - Math.abs(i - (n - 1) / 2f) / Math.max(n, 1);
            float score = semantic + centerBias * 0.18f;
            if (score > heroScore) { heroScore = score; heroIdx = i; }
        }
        float heroBoost = heroIdx >= 0 ? 1f + clamp(heroScore - 0.48f, 0f, 0.52f) : 1f;

        // ===== 碰撞散布（椭圆螺旋采样；hero 先落位居中） =====
        Random r = new Random(line.startTime * 1000L);
        // 过滤候选（只散布非 hero；hero 居中放大）
        placements = new Placement[n];

        // hero 落位
        KrcParser.LyricWord heroWord = words.get(heroIdx);
        float heroScale = HERO_SCALE * heroBoost;
        Placement heroP = basePlacement(heroWord, fontPx, r, 0, 0, heroScale, true);
        heroP.x = 0; heroP.y = 0;
        placements[heroIdx] = heroP;

        // 其余词：按 heroScore 降序散布（先近后远）
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        float[] scores = new float[n];
        for (int i = 0; i < n; i++) {
            String wt = words.get(i).text;
            scores[i] = (isCJK(wt) ? 0.18f : Math.min(wt.length() * 0.08f, 0.36f));
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));

        // 碰撞矩形列表（hero 先注册）
        List<float[]> rects = new ArrayList<>(); // l,t,r,b
        registerRect(rects, heroP);

        float verticalBound = Math.max(lineHeight * 1.6f, layoutH * 0.22f);
        for (int oi = 0; oi < n; oi++) {
            int i = order[oi];
            if (i == heroIdx || placements[i] != null) continue;
            KrcParser.LyricWord w = words.get(i);
            float scale = 1f; // 非 hero 基准 scale（folia baseScale normal=1.01）
            Placement p = basePlacement(w, fontPx, r, 0, 0, scale, false);
            float wW = textPaint.measureText(w.text);
            float collisionW = wW * 1.26f, collisionH = fontPx * 1.24f;
            float padding = 8f;

            // 推离 hero：目标位 = 词原始流位置（简化：随机初始角），先保证离 hero 足够远
            float preferredAngle = (float) (r.nextFloat() * Math.PI * 2);
            float preferredRadius = heroP.width * 0.34f + wW * 0.52f + padding * 2f;
            float px = (float) Math.cos(preferredAngle) * preferredRadius;
            float py = (float) Math.sin(preferredAngle) * preferredRadius * 0.8f;

            // 椭圆螺旋采样找空位
            float step = Math.max(10f, fontPx * 0.14f);
            float maxRadius = Math.max(Math.max(lineHeight * 2.2f, collisionW * 0.75f), 56f);
            boolean found = false;
            float bestX = px, bestY = py; float bestScore = Float.POSITIVE_INFINITY;
            for (float radius = 0f; radius <= maxRadius && !found; radius += step) {
                int samples = radius == 0 ? 1 : Math.max(12, (int) ((Math.PI * 2 * radius) / Math.max(step * 1.1f, 10f)));
                for (int s = 0; s < samples && !found; s++) {
                    float ang = preferredAngle + (s / (float) samples) * (float) Math.PI * 2f;
                    float dx = (float) Math.cos(ang) * radius;
                    float dy = (float) Math.sin(ang) * radius * 0.92f;
                    float cx = px + dx, cy = py + dy;
                    float l = cx - collisionW / 2f - padding, t = cy - collisionH / 2f - padding;
                    float rr = l + collisionW + padding * 2f, b = t + collisionH + padding * 2f;
                    if (l < -maxWidth / 2f - 72f || rr > maxWidth / 2f + 72f
                            || t < -verticalBound || b > verticalBound) continue;
                    float[] hit = evaluateCollision(rects, l, t, rr, b);
                    float score = hit[0] * 2.2f + (float) Math.hypot(dx, dy);
                    if (score < bestScore) { bestScore = score; bestX = cx; bestY = cy; }
                    if (hit[0] <= 0f) { // 无重叠 → 落位
                        p.x = cx; p.y = cy;
                        registerRect(rects, cx - collisionW / 2f, cy - collisionH / 2f,
                                cx + collisionW / 2f, cy + collisionH / 2f);
                        found = true;
                    }
                }
            }
            if (!found) {
                p.x = bestX; p.y = bestY;
                registerRect(rects, bestX - collisionW / 2f, bestY - collisionH / 2f,
                        bestX + collisionW / 2f, bestY + collisionH / 2f);
            }
            placements[i] = p;
        }

        lineEnterMs = System.currentTimeMillis();
        lineExitStartMs = -1L;
    }

    /** 词基础配置（落位前的静态属性） */
    private Placement basePlacement(KrcParser.LyricWord w, float fontPx, Random r,
                                     float fragStartX, float fragText, float scale, boolean isHero) {
        Placement p = new Placement();
        p.text = w.text;
        p.wordText = w.text;
        p.wordStart = (int) w.startTime;
        p.wordEnd = (int) (w.startTime + Math.max(w.duration, 1L));
        p.cjk = isCJK(w.text);
        float wWidth = textPaint.measureText(w.text);
        p.width = Math.max(wWidth, fontPx * 0.18f);
        p.height = fontPx * 0.95f;
        p.scale = scale;
        p.passedRotate = (r.nextFloat() - 0.5f) * 12f;
        // passed 外漂：沿落点向外方向 5~11px + 抖动（folia driftAmount normal 5+6）
        float outLen = Math.max((float) Math.hypot(p.x, p.y), 1f);
        float ux = p.x / outLen, uy = p.y / outLen;
        float drift = 5f + r.nextFloat() * 6f;
        p.passedDriftX = ux * drift + (r.nextFloat() - 0.5f) * 2.4f;
        p.passedDriftY = uy * drift * 0.72f + (r.nextFloat() - 0.5f) * 2f;
        p.isHero = isHero;
        return p;
    }

    private static void registerRect(List<float[]> rects, Placement p) {
        rects.add(new float[]{p.x - p.width * p.scale / 2f, p.y - p.height * p.scale / 2f,
                p.x + p.width * p.scale / 2f, p.y + p.height * p.scale / 2f});
    }

    private static void registerRect(List<float[]> rects, float l, float t, float r, float b) {
        rects.add(new float[]{l, t, r, b});
    }

    /** [重叠面积, 是否相交] */
    private static float[] evaluateCollision(List<float[]> rects, float l, float t, float r, float b) {
        float area = 0f;
        for (float[] rc : rects) {
            float ow = Math.max(0f, Math.min(r, rc[2]) - Math.max(l, rc[0]));
            float oh = Math.max(0f, Math.min(b, rc[3]) - Math.max(t, rc[1]));
            if (ow > 0 && oh > 0) area += ow * oh;
        }
        return new float[]{area, area > 0 ? 1f : 0f};
    }

    // ===== 生命周期 =====

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w; layoutH = h;
        titleSize = Math.max(14f, w * 0.024f);
        if (targetIndex >= 0 && targetIndex < lines.size()) prepareLine(targetIndex);
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

    // ===== 每帧绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layoutW <= 0) return;
        long now = System.currentTimeMillis();
        float t = positionMs / 1000f; // 歌曲时间轴(秒)

        drawTitle(canvas);
        if (targetIndex < 0 || targetIndex >= lines.size() || placements.length == 0) return;

        KrcParser.LyricLine line = lines.get(targetIndex);

        // 行级包络：入场（420ms opacity0→1/scale0.9→1/blur10→0）+ 退场（下一行前的淡出）
        float lineOpacity = 1f, lineScale = 1f;
        float lineBlur = 0f;
        if (lineEnterMs > 0) {
            float et = (now - lineEnterMs) / 420f;
            if (et < 1f) {
                float e = 1f - (1f - clamp01(et)) * (1f - clamp01(et)) * (1f - clamp01(et)); // easeOutCubic
                lineOpacity = clamp01(et * 1.2f);
                lineScale = 0.9f + 0.1f * e;
                lineBlur = 10f * (1f - e);
            }
        }
        // 行将结束的退场（时间驱动：行末后仍保留到 nextLine 前——简化成不提前退场，新行入场覆盖）

        // 页心浮动：energy 驱动竖直 lift（±3~11px）+ 慢正弦
        float ts = (now - startMs) / 1000f;
        float motionEnergy = energy * MOTION_AMOUNT;
        float verticalLift = (float) Math.sin(t * 2.3f) * (3f + motionEnergy * 8f);
        float focusY = layoutH * 0.42f + verticalLift;

        int sc = canvas.save();
        canvas.scale(lineScale, lineScale, layoutW / 2f, focusY);
        if (lineBlur > 0.5f) {
            // 模糊退场近似：入场期用透明度补（API31 真模糊页成本低时再上）
            lineOpacity *= 1f - lineBlur / 24f;
        }

        // 平滑系数（指数趋近，与 dt 无关的近似帧率 60fps 档）
        float transformMix = 0.16f; // ≈1-exp(-11/60)
        float visualMix = 0.21f;    // ≈1-exp(-14/60)
        float glowMix = 0.24f;      // ≈1-exp(-16/60)

        for (int i = 0; i < placements.length; i++) {
            Placement p = placements[i];
            // 词三态
            int status = wordStatus(p, t);
            float progress = wordProgress(p, t);

            // 目标值
            float targetScale, targetRotation, targetBodyAlpha, targetActiveMix, targetGlowAlpha;
            float px = 0f, py = 0f;
            if (status == 0) { // waiting
                targetScale = Math.max(p.scale * 0.5f, 0.5f);
                targetRotation = 20f;
                targetBodyAlpha = 0f;
                targetActiveMix = 0f;
                targetGlowAlpha = 0f;
            } else if (status == 1) { // active
                float pulse = 1f + (float) Math.sin(t * PULSE_FREQUENCY + p.wordStart / 1000f * 5f) * 0.04f * MOTION_AMOUNT;
                targetScale = p.scale * 1.3f * pulse;
                targetRotation = 0f;
                targetBodyAlpha = 1f;
                targetActiveMix = progress;
                targetGlowAlpha = glowEnvelope(p, t);
            } else { // passed
                float drift = easeInOutQuad(clamp01((t - p.wordEnd / 1000f) / 5f));
                targetScale = p.scale;
                targetRotation = p.passedRotate * drift;
                targetBodyAlpha = PASSED_ALPHA;
                targetActiveMix = 1f - drift * 0.5f;
                targetGlowAlpha = glowEnvelope(p, t);
                px = p.passedDriftX * drift;
                py = p.passedDriftY * drift;
            }
            // 局部漂浮（energy 驱动；placementIndex 相位）
            float localFloatX = (float) Math.sin(t * 1.2f + i * 0.6f) * motionEnergy * 4f;
            float localFloatY = (float) Math.cos(t * 1.5f + i * 0.4f) * motionEnergy * 2.5f;
            float targetX = layoutW / 2f + p.x + px + localFloatX;
            float targetY = focusY + p.y + py + localFloatY;

            if (!p.initialized) {
                p.ax = targetX; p.ay = targetY;
                p.aRotation = targetRotation + 16f;
                p.aScale = Math.max(p.scale * 0.5f, 0.5f);
                p.aBodyAlpha = 0f; p.aBlur = 10f; p.aActiveMix = 0f; p.aGlowAlpha = 0f;
                p.initialized = true;
            }

            // 指数趋近平滑
            p.ax += (targetX - p.ax) * transformMix;
            p.ay += (targetY - p.ay) * transformMix;
            p.aRotation += (targetRotation - p.aRotation) * transformMix;
            p.aScale += (targetScale - p.aScale) * transformMix;
            p.aBodyAlpha += (targetBodyAlpha - p.aBodyAlpha) * visualMix;
            p.aBlur += (0f - p.aBlur) * visualMix;
            p.aActiveMix += (targetActiveMix - p.aActiveMix) * visualMix;
            p.aGlowAlpha += (targetGlowAlpha - p.aGlowAlpha) * glowMix;

            if (p.aBodyAlpha < 0.015f && p.aGlowAlpha < 0.015f) continue;

            drawPlacement(canvas, p, lineOpacity);
        }
        canvas.restoreToCount(sc);

        postInvalidateOnAnimation();
    }

    /** 单词绘制：正文 + 字素/整词辉光 + hero 副歌涟漪省略（folia isChorus 圆环本地暂砍） */
    private void drawPlacement(Canvas canvas, Placement p, float lineOpacity) {
        textPaint.setTextSize(60f); // 实际字号按 scale 恢复（此处 placeholder，见下）
        // 正文：颜色 = 基色→高亮 混合（activeMix）
        int bodyColor = mixColor(colorPrimary, colorAccent, clamp01(p.aActiveMix));
        int a = (int) (clamp01(p.aBodyAlpha * lineOpacity) * 255);
        if (a <= 2) return;

        // 字号：字体尺寸存于准备态——记录于 p.height 关联（height=fontPx*0.95）
        float fontPx = p.height / 0.95f;
        textPaint.setTextSize(fontPx);

        // 辉光包络（字素级：非 CJK 逐字母；CJK 整词）
        // folia buildDomTextShadow：三层 40px glow；Android shadowLayer 单层近似（半径40 效果强，乘强度）
        float glowI = clamp01(p.aGlowAlpha * GLOW_INTENSITY);
        int cs = canvas.save();
        canvas.translate(p.ax, p.ay);
        canvas.rotate(p.aRotation);
        canvas.scale(p.aScale, p.aScale);

        android.graphics.Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = -(fm.ascent + fm.descent) * 0.5f - fm.descent;

        if (p.cjk || p.text.length() <= 1) {
            // 整词辉光 + 正文
            if (glowI > 0.02f) {
                textPaint.setShadowLayer(40f * glowI, 0, 0, (int)(glowI * 0.92f * 255) << 24 | (colorAccent & 0xFFFFFF));
            } else {
                textPaint.setShadowLayer(0, 0, 0, 0);
            }
            textPaint.setColor((a << 24) | (bodyColor & 0xFFFFFF));
            canvas.drawText(p.text, 0, baseline, textPaint);
        } else {
            // 非 CJK：逐字母辉光（每个字母独立 glow 相位）
            float x = -textPaint.measureText(p.text) / 2f;
            float wordDur = Math.max((p.wordEnd - p.wordStart) / 1000f, 0.1f);
            float perGlyph = wordDur / p.text.length();
            for (int gi = 0; gi < p.text.length(); gi++) {
                String ch = String.valueOf(p.text.charAt(gi));
                float cw = textPaint.measureText(ch);
                // 字素辉光包络（elapsed / (字时长×6) keyframe）
                float charStart = p.wordStart / 1000f + perGlyph * gi;
                float gElapsed = positionMs / 1000f - charStart;
                float gProg = gElapsed <= 0 ? 0f : gElapsed / (perGlyph * 6f);
                float gEnv = keyframedGlow(clamp01(gProg));
                float giAlpha = gEnv * glowI;
                if (giAlpha > 0.02f) {
                    textPaint.setShadowLayer(40f * giAlpha, 0, 0,
                            (int)(giAlpha * 0.92f * 255) << 24 | (colorAccent & 0xFFFFFF));
                } else {
                    textPaint.setShadowLayer(0, 0, 0, 0);
                }
                textPaint.setColor((a << 24) | (bodyColor & 0xFFFFFF));
                canvas.drawText(ch, x + cw / 2f, baseline, textPaint);
                x += cw;
            }
        }
        textPaint.setShadowLayer(0, 0, 0, 0);
        canvas.restoreToCount(cs);
    }

    // ===== 包络函数（folia cadenzaEnvelopes 直译） =====

    /** 词三态：waiting=0 active=1 passed=2 */
    private int wordStatus(Placement p, float t) {
        float start = p.wordStart / 1000f, end = p.wordEnd / 1000f;
        float lookahead = 0.18f;
        if (t >= start - lookahead && t <= end) return 1;
        if (t > end) return 2;
        return 0;
    }

    private float wordProgress(Placement p, float t) {
        float start = p.wordStart / 1000f, end = p.wordEnd / 1000f;
        float duration = Math.max(end - start, 0.01f);
        return clamp01((t - start) / duration);
    }

    /** 词级辉光包络（folia getClassicGlowEnvelope normal 档） */
    private float glowEnvelope(Placement p, float t) {
        float start = p.wordStart / 1000f, end = p.wordEnd / 1000f;
        float duration = Math.max(end - start, 0.1f);
        if (t < start) return 0f;
        if (t <= end) {
            float progress = clamp01((t - start) / duration);
            if (progress < 0.18f) return easeOutCubic(progress / 0.18f);
            if (progress < 0.9f) return 1f;
            return 1f - (1f - 0.9f) * ((progress - 0.9f) / 0.1f);
        }
        float fadeOut = clamp01((t - end) / 0.9f);
        return 0.9f * (1f - fadeOut) * (1f - fadeOut);
    }

    /** 字素辉光 keyframe（folia getClassicKeyframedGlow） */
    private static float keyframedGlow(float progress) {
        if (progress <= 0f || progress >= 1f) return 0f;
        if (progress < 0.3f) return easeOutCubic(progress / 0.3f);
        return 1f - clamp01((progress - 0.3f) / 0.7f);
    }

    private static float easeOutCubic(float v) {
        return 1f - (float) Math.pow(1f - clamp01(v), 3);
    }

    private static float easeInOutQuad(float v) {
        float n = clamp01(v);
        return n < 0.5f ? 2f * n * n : 1f - (float) Math.pow(-2f * n + 2f, 2) / 2f;
    }

    // ===== 工具 =====

    private void drawTitle(Canvas canvas) {
        if (titleText.isEmpty()) return;
        textPaint.setTextSize(titleSize);
        textPaint.setShadowLayer(0, 0, 0, 0);
        textPaint.setColor(colorAccent);
        textPaint.setFakeBoldText(true);
        float density = getResources().getDisplayMetrics().density;
        canvas.drawText(titleText, layoutW / 2f,
                (float) Math.ceil(density * 16f) + titleSize * 0.85f, textPaint);
    }

    private static boolean isCJK(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FAF) return true;
        }
        return false;
    }

    private static int mixColor(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        return (0xFF << 24)
                | ((fr + (int) ((tr - fr) * t)) << 16)
                | ((fg + (int) ((tg - fg) * t)) << 8)
                | (fb + (int) ((tb - fb) * t));
    }

    private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    private static float clamp(float v, float min, float max) {
        return v < min ? min : v > max ? max : v;
    }
}
