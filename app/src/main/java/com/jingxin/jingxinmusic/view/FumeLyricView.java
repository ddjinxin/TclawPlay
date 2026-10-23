package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import com.jingxin.jingxinmusic.util.KrcParser;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 浮名歌词页（Fume，移植 folia fume：整曲排成文章 + 相机运镜）
 *
 * 流水线（folia 全链直译）：
 * 1. 文章布局（fumeArticleLayout + fumeTextMeasure）：纸宽 clamp(1.95vw,920,2400)，
 *    多列尝试（maxCols 依纸宽 4/3/2/1）×密度二分（8 轮 0.82~1.42），选总高最接近
 *    2.45×视口高的组合；行序按 seed 洗牌（凑版面感）；
 *    hero 块（副歌短行/每 6 行/5% 随机）占 1~2 列宽放最平静大位，
 *    body 块放当前最矮列；字号 chooseSingleLineFontPx：二分搜「单行能放下的
 *    最大字号」（hero 基准 18~58 / body 10~30 ×密度乘子），放不下才折行；
 *    hero 行首 8% 缩进；字素切分 BreakIterator 字素簇（含 emoji 连写）
 * 2. 相机（fumeCameraStep）：跟踪焦点块（smooth=已打印字素小数偏移的行内实时点
 *    +跨行 0.7 字过渡混合 / stepped=整数字素宽度步进）；换块 retarget 时长按
 *    行过渡三档动态（renderHints：micro→none 0.04~0.075 / short→fast 0.055~0.095 /
 *    normal 0.075~0.13，仅行间切换 sourceLineIndex>=0 时启用远距 overview 飞桥，
 *    歌尾 overview 走纯弹簧）；平时弹簧追踪+scale 弹簧+无所事事正弦漂浮
 *    （18px/7s，overview 时衰减 0.36）
 * 3. 结尾全景：最后行中点后飞到整篇俯瞰（fit 0.22~0.72）
 * 4. 渲染（fumeCanvasText 分段合并）：同状态字素连续段合批 drawText（waiting 段/
 *    唱完段合一笔，激活/尾迹逐字），逐段状态——waiting 极淡隐字 → 唱到满亮+
 *    词色混入(0.22+0.78p easeOutCubic)+辉光(4+0.22f)·p → 唱过色尾（cutoff 快进
 *    0.45~1.45s, p^1.35）褪到 passed；每个字素独立印章方块（打印窗口前导
 *    0.055~0.2s 落下脉冲，cutoff 冻结，hero 0.72f×0.82α）；行激活时全块底层行辉光
 *    （hero 0.16+0.26包络/半径12+0.7f）
 * 5. 背景：由页面级星空底层提供（StarfieldView 深空渐变+星云+星点），
 *    本视图为纯内容层不再自绘底色，保证不透出播放页；
 *    fume 原版 disableGeometricBackground=true（几何背景默认关）
 *
 * Android 化：pretext 测量 → measureText（字号级缓存前缀宽）；字素 splitGraphemes
 * → BreakIterator 字素簇；fumeLiveRaster 离屏位图缓存 ≈ 软件层全量重绘+块级视口剔除
 * （+180px overscan）；渲染合批对齐 fumeCanvasText segments。
 */
public class FumeLyricView extends View {

    // ===== folia 常量 =====
    private static final float CAMERA_SCALE_MIN = 0.22f, CAMERA_SCALE_MAX = 2.24f;
    private static final int OVERVIEW_SOURCE = -2;
    private static final float TELEPORT_SCREENS = 2.75f;
    private static final float BG_PARALLAX_X = 0.9f, BG_PARALLAX_Y = 0.74f;
    private static final float BG_SCALE_FACTOR = 0.94f;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<KrcParser.LyricLine> lines = Collections.emptyList();
    private boolean[] chorusFlags = new boolean[0];
    private int targetIndex = -2;
    private float positionMs;

    private int colorPrimary = Color.rgb(0xE8, 0xEE, 0xF6);
    private int colorAccent = Color.rgb(0x6E, 0xE8, 0xD8);

    // ===== 文章 =====
    private Article article;
    private boolean layoutDirty = true;
    private final HashMap<String, LineMeasures> measuresCache = new HashMap<>();

    /** 文章（世界坐标：所有块铺在 poster 上，相机在其中运镜） */
    private static final class Article {
        float width, height;
        final List<Block> blocks = new ArrayList<>();
        final List<Block> chronological = new ArrayList<>();
        Block blockByLine(int lineIndex) {
            for (Block b : blocks) if (b.sourceLineIndex == lineIndex) return b;
            return null;
        }
        Block lastChronological() { return chronological.isEmpty() ? null : chronological.get(chronological.size() - 1); }
    }

    /** 行测量缓存（fumeTextMeasure.lineMeasuresFor 直译：同文本行共享，
     *  字号级前缀宽缓存 preparedByFontPx） */
    private static final class LineMeasures {
        final String text;
        final String[] clusters;       // 字素簇（BreakIterator）
        final int[] cuStart;           // 簇 i 的 code unit 起点（长度 N+1）
        final int[][] wordRanges;      // 词 → 簇区间 [s,e)，与 wordSourceIdx 同序（无词 null）
        final int[] wordSourceIdx;     // 词区间 → line.words 非空词下标（跳过空词）
        final int renderableCount;     // 非空白簇数
        final HashMap<Float, float[]> widthsByFontPx = new HashMap<>();

        LineMeasures(String text, String[] clusters, int[] cuStart, int[][] wordRanges,
                     int[] wordSourceIdx, int renderableCount) {
            this.text = text;
            this.clusters = clusters;
            this.cuStart = cuStart;
            this.wordRanges = wordRanges;
            this.wordSourceIdx = wordSourceIdx;
            this.renderableCount = renderableCount;
        }

        /** 簇前缀宽（含字号级缓存） */
        float[] widths(float fontPx, Paint paint) {
            float[] cached = widthsByFontPx.get(fontPx);
            if (cached != null) return cached;
            float[] w = new float[clusters.length + 1];
            paint.setTextSize(fontPx);
            for (int i = 0; i < clusters.length; i++) {
                w[i + 1] = w[i] + paint.measureText(clusters[i]);
            }
            widthsByFontPx.put(fontPx, w);
            return w;
        }
    }

    /** 一行歌词排成的块 */
    private static final class Block {
        KrcParser.LyricLine line;
        int sourceLineIndex;
        boolean hero;
        float x, y, width, height;
        float fontPx, lineHeight;
        LineMeasures measures;
        int[] renderLineOfCluster;        // 簇 → 渲染行索引（合批/相机用）
        List<RenderLine> renderLines = new ArrayList<>();
    }

    private static final class RenderLine {
        String text;                       // 对应原文片段（段合批 drawText 直接用）
        int startGlyph, endGlyph;          // 全局簇区间 [start, end)
        float left, top, width;
    }

    // ===== 相机状态（fumeCameraStep 直译）=====
    private static final class Camera {
        float x, y, velX, velY, focusX, focusY, scale = 1f, velScale, focusScale = 1f;
    }
    private static final class Retarget {
        int sourceLineIndex = -1;
        float startedAt, duration = 0.18f;
        float fromX, fromY, fromScale = 1f;
        int bridgeMode;          // 0=none 1=direct 2=overview
        float waypointX, waypointY, waypointScale = 1f, waypointPhase = 0.36f;
    }
    private final Camera cam = new Camera();
    private final Retarget ret = new Retarget();
    private boolean camInitialized = false;

    private boolean running = false;
    private long lastFrameNs = -1L;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            long deltaNs = lastFrameNs < 0 ? 0 : frameTimeNanos - lastFrameNs;
            lastFrameNs = frameTimeNanos;
            float dt = Math.min(0.05f, deltaNs / 1_000_000_000f);
            step(dt, frameTimeNanos);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public FumeLyricView(Context context) { super(context); init(); }
    public FumeLyricView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public FumeLyricView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ===== 对外接口 =====

    public void setLyricData(KrcParser.LyricData data) {
        this.lines = (data != null && data.lines != null) ? data.lines : Collections.emptyList();
        this.targetIndex = -2;
        detectChorus();
        measuresCache.clear();
        layoutDirty = true;
        camInitialized = false;
        invalidate();
    }

    public void updatePosition(int positionMs) { this.positionMs = positionMs; }

    public void setColors(int primary, int accent) {
        if (primary != 0) colorPrimary = primary;
        if (accent != 0) colorAccent = accent;
        invalidate();
    }

    /** 播放页高亮色注入（词色混合/辉光/印章用 accent；primary 保持浅色基调） */
    public void setAccentColor(int accent) {
        if (accent != 0) { colorAccent = accent; invalidate(); }
    }

    /** 相机跟踪模式（folia cameraTrackingMode 直译：0=smooth 默认 / 1=stepped 整数步进） */
    public void setCameraTrackingMode(int mode) {
        this.cameraTrackingMode = mode;
    }
    private int cameraTrackingMode = 0;

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutDirty = true;
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

    private void detectChorus() {
        chorusFlags = new boolean[lines.size()];
        if (lines.size() < 3) return;
        HashMap<String, Integer> counts = new HashMap<>();
        for (KrcParser.LyricLine line : lines) {
            String t = line != null && line.text != null ? line.text.trim() : "";
            if (t.isEmpty()) continue;
            Integer c = counts.get(t);
            counts.put(t, c != null ? c + 1 : 1);
        }
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).text != null ? lines.get(i).text.trim() : "";
            Integer c = counts.get(t);
            chorusFlags[i] = c != null && c >= 2;
        }
    }

    // ===== 文章布局（buildArticleLayout 直译）=====

    private void buildArticle() {
        int vw = getWidth(), vh = getHeight();
        Article a = new Article();
        if (vw <= 0 || vh <= 0 || lines.isEmpty()) {
            article = a;
            layoutDirty = false;
            return;
        }

        float paperWidth = clamp(Math.max(vw * 1.95f, vw + 520f), 920f, 2400f);
        float viewportHeight = Math.max(vh, 240f);
        int maxColumns = paperWidth >= 1120f ? 4 : paperWidth >= 760f ? 3 : paperWidth >= 500f ? 2 : 1;
        float targetHeight = viewportHeight * 2.45f;

        // 可排行（非空行）条目：时间序
        List<KrcParser.LyricLine> renderable = new ArrayList<>();
        List<Integer> renderableIdx = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).text;
            if (t != null && !t.trim().isEmpty()) { renderable.add(lines.get(i)); renderableIdx.add(i); }
        }
        if (renderable.isEmpty()) { article = a; layoutDirty = false; return; }

        // 尝试各列数×密度二分，选总高最接近 2.45×视口高的组合
        // 洗牌（fumeArticleLayout：seeded(`${seedKey}:${index}:${fullText}`) 升序放置，
        // seedKey=主题:columns:纸宽 → 每列数各自编排，measure 与 render 同种子；
        // “paper feels composed rather than strictly chronological”
        float bestScore = Float.POSITIVE_INFINITY;
        int bestColumns = maxColumns;
        float bestDensity = 1f;
        List<KrcParser.LyricLine> bestShuffled = null;
        List<Integer> bestShuffledIdx = null;
        int bestForcedHero = -1;
        for (int columns = maxColumns; columns >= 1; columns--) {
            // 洗牌（本列数专用种子）
            List<Integer> order = shuffledOrder(renderable, renderableIdx, columns, paperWidth);
            List<KrcParser.LyricLine> shuffled = new ArrayList<>(order.size());
            List<Integer> shuffledIdx = new ArrayList<>(order.size());
            for (Integer o : order) { shuffled.add(renderable.get(o)); shuffledIdx.add(renderableIdx.get(o)); }
            // 兼底 hero 在洗牌序上挑（原版 chooseFallbackHeroBlockIndex(filteredLines)）
            int forcedHero = chooseFallbackHero(shuffled);
            float lo = 0.82f, hi = 1.42f;
            for (int iter = 0; iter < 8; iter++) {
                float density = (lo + hi) / 2f;
                float height = measureAttemptHeight(shuffled, paperWidth, viewportHeight, columns, gapOf(paperWidth, columns), density, forcedHero);
                float coverage = Math.abs(height - targetHeight);
                float overflow = height < targetHeight ? 0f : (height - targetHeight) * 0.14f;
                float score = coverage + overflow;
                if (score < bestScore) {
                    bestScore = score; bestColumns = columns; bestDensity = density;
                    bestShuffled = shuffled; bestShuffledIdx = shuffledIdx; bestForcedHero = forcedHero;
                }
                if (height < targetHeight) lo = density; else hi = density;
            }
        }

        // 最终 render 布局（bestColumns/bestDensity + 同种子洗牌序）
        layOutArticle(a, bestShuffled != null ? bestShuffled : renderable,
                bestShuffledIdx != null ? bestShuffledIdx : renderableIdx,
                vw, paperWidth, viewportHeight, bestColumns,
                gapOf(paperWidth, bestColumns),
                bestDensity, bestForcedHero);

        article = a;
        layoutDirty = false;
        camInitialized = false;
    }

    private static float gapOf(float paperWidth, int columns) {
        return clamp(Math.round(paperWidth * (columns >= 4 ? 0.0065f : columns == 3 ? 0.0085f : 0.0115f)), 6f, 14f);
    }

    /** 洗牌（fumeArticleLayout 直译）：seeded(`${seedKey}:${columns}:${paperWidth}:${行号}:${文本}`) 升序，
     *  返回指向 renderable 的下标序列（每个列数各自编排，measure 与 render 同种子） */
    private static List<Integer> shuffledOrder(List<KrcParser.LyricLine> renderable,
                                               List<Integer> renderableIdx,
                                               int columns, float paperWidth) {
        int n = renderable.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (l, r) -> Float.compare(
                shuffleSeed(columns, paperWidth, renderableIdx.get(l), renderable.get(l).text),
                shuffleSeed(columns, paperWidth, renderableIdx.get(r), renderable.get(r).text)));
        List<Integer> out = new ArrayList<>(n);
        for (Integer o : order) out.add(o);
        return out;
    }

    private static float shuffleSeed(int columns, float paperWidth, int index, String text) {
        return seeded("fume:" + columns + ":" + Math.round(paperWidth) + ":" + index + ":" + text);
    }

    /** 测量尝试的总高（measure 模式：只算高度不做渲染细节） */
    private float measureAttemptHeight(List<KrcParser.LyricLine> renderable, float paperWidth,
                                       float viewportHeight, int columns, float gap,
                                       float density, int forcedHero) {
        float horizontalMargin = Math.max(getWidth() * 0.86f, 280f);
        float verticalMargin = Math.max(viewportHeight * 0.82f, 220f);
        float columnWidth = (paperWidth - gap * (columns - 1)) / columns;
        float[] columnHeights = new float[columns];
        java.util.Arrays.fill(columnHeights, verticalMargin);

        for (int blockIndex = 0; blockIndex < renderable.size(); blockIndex++) {
            KrcParser.LyricLine line = renderable.get(blockIndex);
            boolean hero = blockIndex == forcedHero || isNaturalHero(line, blockIndex, renderable.size());
            int heroSpan = hero ? Math.min(columns, columns <= 1 ? 1 : 2) : 1;
            float blockWidth = hero && columns >= 2
                    ? (columns == 2 ? columnWidth * 1.5f + gap * 0.5f : columnWidth * 2f + gap)
                    : (hero ? paperWidth : columnWidth);

            // chooseSingleLineFontPx 直译：二分搜单行能放下的最大字号（×密度乘子）
            LineMeasures m = measuresFor(line);
            FontChoice fc = chooseFontAndLines(m, Math.max(blockWidth, 120f), hero, density);
            float fontPx = fc.fontPx;
            float lineHeight = Math.round(fontPx * (hero ? 1.02f : 1.06f));
            int lineCount = fc.lineCount;
            float blockGap = hero ? Math.max(Math.round(lineHeight * 0.2f), 6) : Math.max(Math.round(lineHeight * 0.08f), 2);
            float blockHeight = lineCount * lineHeight;

            if (hero && heroSpan == 1) {
                float y = 0;
                for (float h : columnHeights) y = Math.max(y, h);
                for (int c = 0; c < columns; c++) columnHeights[c] = y + blockHeight + blockGap;
            } else if (hero) {
                // 跨列 hero：找覆盖列最高的起点
                float best = Float.POSITIVE_INFINITY;
                int start = 0;
                for (int sc = 0; sc <= columns - heroSpan; sc++) {
                    float covered = 0;
                    for (int c = sc; c < sc + heroSpan; c++) covered = Math.max(covered, columnHeights[c]);
                    if (covered < best) { best = covered; start = sc; }
                }
                for (int c = start; c < start + heroSpan && c < columns; c++) columnHeights[c] = best + blockHeight + blockGap;
            } else {
                int target = 0;
                float min = columnHeights[0];
                for (int c = 1; c < columns; c++) {
                    if (columnHeights[c] < min) { min = columnHeights[c]; target = c; }
                }
                columnHeights[target] = min + blockHeight + blockGap;
            }
        }
        float maxH = 0;
        for (float h : columnHeights) maxH = Math.max(maxH, h);
        return maxH + verticalMargin;
    }

    /** render 布局：世界坐标块 + 字素渲染行 */
    private void layOutArticle(Article a, List<KrcParser.LyricLine> renderable, List<Integer> renderableIdx,
                               int vw, float paperWidth, float viewportHeight, int columns, float gap,
                               float density, int forcedHero) {
        float horizontalMargin = Math.max(vw * 0.86f, 280f);
        float verticalMargin = Math.max(viewportHeight * 0.82f, 220f);
        float columnWidth = (paperWidth - gap * (columns - 1)) / columns;
        float[] columnHeights = new float[columns];
        java.util.Arrays.fill(columnHeights, verticalMargin);

        for (int blockIndex = 0; blockIndex < renderable.size(); blockIndex++) {
            KrcParser.LyricLine line = renderable.get(blockIndex);
            String text = line.text;
            boolean hero = blockIndex == forcedHero || isNaturalHero(line, blockIndex, renderable.size());
            int heroSpan = hero ? Math.min(columns, columns <= 1 ? 1 : 2) : 1;
            float blockWidth = hero && columns >= 2
                    ? (columns == 2 ? columnWidth * 1.5f + gap * 0.5f : columnWidth * 2f + gap)
                    : (hero ? paperWidth : columnWidth);

            // 字号（chooseSingleLineFontPx 直译，与 measure 模式同源）
            LineMeasures m = measuresFor(line);
            FontChoice fc = chooseFontAndLines(m, Math.max(blockWidth, 120f), hero, density);
            float fontPx = fc.fontPx;
            float lineHeight = Math.round(fontPx * (hero ? 1.02f : 1.06f));

            Block block = new Block();
            block.line = line;
            block.sourceLineIndex = renderableIdx.get(blockIndex);
            block.hero = hero;
            block.fontPx = fontPx;
            block.lineHeight = lineHeight;
            block.measures = m;

            // 折行（词原子：KRC 词不拆；簇为最小单位）+ hero 行首 8% 缩进
            float[] w = m.widths(fontPx, textPaint);
            List<int[]> rows = wrapClusters(m, fontPx, Math.max(blockWidth, 120f));
            block.renderLines.clear();
            int n = m.clusters.length;
            block.renderLineOfCluster = new int[Math.max(n, 1)];
            for (int ri = 0; ri < rows.size(); ri++) {
                int[] row = rows.get(ri);
                RenderLine rl = new RenderLine();
                rl.startGlyph = row[0];
                rl.endGlyph = row[1];
                rl.text = text.substring(m.cuStart[row[0]], m.cuStart[row[1]]);
                rl.width = w[row[1]] - w[row[0]];
                rl.left = hero ? Math.max((blockWidth - rl.width) * 0.08f, 0f) : 0f;
                block.renderLines.add(rl);
                for (int g = row[0]; g < row[1] && g < block.renderLineOfCluster.length; g++) {
                    block.renderLineOfCluster[g] = ri;
                }
            }
            fixRenderLineTops(block);
            int lineCount = Math.max(1, block.renderLines.size());
            float blockGap = hero ? Math.max(Math.round(lineHeight * 0.2f), 6) : Math.max(Math.round(lineHeight * 0.08f), 2);
            block.height = lineCount * lineHeight;
            block.width = blockWidth;

            // 放置（measure 逻辑同款）
            if (hero && heroSpan == 1) {
                float y = 0;
                for (float h : columnHeights) y = Math.max(y, h);
                block.x = horizontalMargin;
                block.y = y;
                java.util.Arrays.fill(columnHeights, y + block.height + blockGap);
            } else if (hero) {
                float best = Float.POSITIVE_INFINITY;
                int start = 0;
                for (int sc = 0; sc <= columns - heroSpan; sc++) {
                    float covered = 0;
                    for (int c = sc; c < sc + heroSpan; c++) covered = Math.max(covered, columnHeights[c]);
                    if (covered < best) { best = covered; start = sc; }
                }
                block.y = best;
                // 原版：hero 在跨列区间内水平居中（columns==2 时 heroSpanWidth 与 blockWidth 差 0.5 列）
                float heroSpanWidth = columnWidth * heroSpan + gap * (heroSpan - 1);
                block.x = horizontalMargin + start * (columnWidth + gap)
                        + Math.max((heroSpanWidth - blockWidth) * 0.5f, 0f);
                for (int c = start; c < start + heroSpan && c < columns; c++) columnHeights[c] = best + block.height + blockGap;
            } else {
                int target = 0;
                float min = columnHeights[0];
                for (int c = 1; c < columns; c++) if (columnHeights[c] < min) { min = columnHeights[c]; target = c; }
                block.x = horizontalMargin + target * (columnWidth + gap);
                block.y = columnHeights[target];
                columnHeights[target] = min + block.height + blockGap;
            }
            a.blocks.add(block);
        }

        // 世界尺寸
        float maxH = verticalMargin;
        for (float h : columnHeights) maxH = Math.max(maxH, h);
        a.width = paperWidth + horizontalMargin * 2f;
        a.height = maxH + verticalMargin;

        // 时间序
        a.chronological.addAll(a.blocks);
        a.chronological.sort(Comparator.comparingInt(b -> b.sourceLineIndex));
    }

    private boolean isNaturalHero(KrcParser.LyricLine line, int index, int total) {
        int glyphCount = measuresFor(line).renderableCount;
        if (glyphCount == 0) return false;
        int li = lineIndexOf(line);
        boolean isChorus = li >= 0 && li < chorusFlags.length && chorusFlags[li];
        if (isChorus && glyphCount <= 22) return true;
        boolean shortEnough = glyphCount >= 4 && glyphCount <= 28;
        float centered = Math.abs(index - total / 2f) / Math.max(total, 1);
        float random = seeded(line.text + ":" + index);
        return shortEnough && centered < 0.72f && ((index + 1) % 6 == 0 || random > 0.965f);
    }

    private int chooseFallbackHero(List<KrcParser.LyricLine> renderable) {
        boolean hasNatural = false;
        for (int i = 0; i < renderable.size(); i++) {
            if (isNaturalHero(renderable.get(i), i, renderable.size())) { hasNatural = true; break; }
        }
        if (hasNatural) return -1;
        int best = -1;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < renderable.size(); i++) {
            int gc = measuresFor(renderable.get(i)).renderableCount;
            if (gc == 0 || gc > 36) continue;
            if (gc < 4) continue;
            float centered = Math.abs(i - renderable.size() / 2f) / Math.max(renderable.size(), 1);
            float centerScore = 1f - centered;
            float lengthScore = gc >= 6 && gc <= 22 ? 1f : gc <= 28 ? 0.72f : 0.36f;
            int li = lineIndexOf(renderable.get(i));
            float chorusScore = li >= 0 && li < chorusFlags.length && chorusFlags[li] ? 0.28f : 0f;
            float score = centerScore * 0.62f + lengthScore * 0.34f + chorusScore;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        if (best >= 0) return best;
        int shortest = -1, shortestCount = Integer.MAX_VALUE;
        for (int i = 0; i < renderable.size(); i++) {
            int gc = measuresFor(renderable.get(i)).clusters.length;
            if (gc > 0 && gc < shortestCount) { shortestCount = gc; shortest = i; }
        }
        return shortest;
    }

    private int lineIndexOf(KrcParser.LyricLine line) { return lines.indexOf(line); }

    // ===== 行测量（fumeTextMeasure.lineMeasuresFor 直译：同文本+同分词行共享 + 字号级前缀宽缓存）=====

    private LineMeasures measuresFor(KrcParser.LyricLine line) {
        String text = line.text != null ? line.text : "";
        String key = text + '\u0001' + wordSignature(line.words);
        LineMeasures cached = measuresCache.get(key);
        if (cached != null) return cached;
        LineMeasures m = buildLineMeasures(text, line.words);
        measuresCache.put(key, m);
        return m;
    }

    /** 分词签名：同文本不同分词的行不共享测量（副歌重复行 KRC 分词可能不一致，
     *  共享 wordSourceIdx 会越界对不上下标） */
    private static String wordSignature(List<KrcParser.LyricWord> words) {
        if (words == null || words.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (KrcParser.LyricWord w : words) sb.append(w.text != null ? w.text : "").append('\u0002');
        return sb.toString();
    }

    /** 簇切分（splitGraphemes → BreakIterator 字素簇）+ 词→簇区间（尾随空白并入，fumeReveal 语义） */
    private static LineMeasures buildLineMeasures(String text, List<KrcParser.LyricWord> words) {
        List<String> cls = new ArrayList<>();
        List<Integer> csList = new ArrayList<>();
        if (!text.isEmpty()) {
            BreakIterator it = BreakIterator.getCharacterInstance();
            it.setText(text);
            int start = it.first();
            for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
                if (end > start) {
                    csList.add(start);
                    cls.add(text.substring(start, end));
                }
            }
        }
        int n = cls.size();
        String[] clusters = cls.toArray(new String[0]);
        int[] cuStart = new int[n + 1];
        for (int i = 0; i < n; i++) cuStart[i] = csList.get(i);
        cuStart[n] = text.length();

        int renderableCount = 0;
        for (String c : clusters) if (!c.trim().isEmpty()) renderableCount++;

        // KRC 词 → 簇区间 [lowerBound(cuS), lowerBound(cuE))，同序一一对应（跳过空词）
        int[][] wordRanges = null;
        int[] wordSourceIdx = null;
        if (words != null && !words.isEmpty()) {
            List<int[]> ranges = new ArrayList<>();
            List<Integer> sourceIdx = new ArrayList<>();
            int cuCursor = 0;
            for (int wi = 0; wi < words.size(); wi++) {
                KrcParser.LyricWord word = words.get(wi);
                String wt = word.text != null ? word.text : "";
                if (wt.isEmpty()) continue;
                int cuS = Math.min(cuCursor, text.length());
                int cuE = Math.min(cuS + wt.length(), text.length());
                while (cuE < text.length() && Character.isWhitespace(text.charAt(cuE))) cuE++;
                ranges.add(new int[]{lowerBound(cuStart, cuS), lowerBound(cuStart, cuE)});
                sourceIdx.add(wi);
                cuCursor = cuE;
            }
            wordRanges = ranges.toArray(new int[0][]);
            wordSourceIdx = new int[sourceIdx.size()];
            for (int i = 0; i < sourceIdx.size(); i++) wordSourceIdx[i] = sourceIdx.get(i);
        }
        return new LineMeasures(text, clusters, cuStart, wordRanges, wordSourceIdx, renderableCount);
    }

    /** 首个 >= key 的簇索引（cuStart 有序） */
    private static int lowerBound(int[] arr, int key) {
        int lo = 0, hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] < key) lo = mid + 1;
            else hi = mid;
        }
        return Math.min(lo, arr.length - 1);
    }

    /** 字号+行数选择（chooseSingleLineFontPx 直译：二分搜单行能放下的最大字号；0.5px 量化提缓存命中） */
    private static final class FontChoice { float fontPx; int lineCount; }

    private FontChoice chooseFontAndLines(LineMeasures m, float width, boolean hero, float densityScale) {
        float low = hero ? 18f : 10f, high = hero ? 58f : 30f;
        Float best = null;
        for (int iter = 0; iter < 8; iter++) {
            float mid = (low + high) / 2f;
            float candidate = Math.round(mid * densityScale * 2f) / 2f;
            if (wrapClusters(m, candidate, width).size() <= 1) {
                best = candidate;
                low = mid;
            } else {
                high = mid;
            }
        }
        if (best != null) {
            FontChoice fc = new FontChoice();
            fc.fontPx = best;
            fc.lineCount = Math.max(1, wrapClusters(m, best, width).size());
            return fc;
        }
        float fallback = Math.round((hero ? 18f : 10f) * densityScale * 2f) / 2f;
        FontChoice fc = new FontChoice();
        fc.fontPx = fallback;
        fc.lineCount = Math.max(1, wrapClusters(m, fallback, width).size());
        return fc;
    }

    /** 词原子折行 → 簇区间行（词为原子块；无词 LRC 按簇宽折叠） */
    private List<int[]> wrapClusters(LineMeasures m, float fontPx, float maxW) {
        int n = m.clusters.length;
        List<int[]> rows = new ArrayList<>();
        if (n == 0) { rows.add(new int[]{0, 0}); return rows; }
        float[] w = m.widths(fontPx, textPaint);
        if (m.wordRanges != null && m.wordRanges.length > 0) {
            int rowStart = 0;
            float acc = 0f;
            for (int[] range : m.wordRanges) {
                int s = range[0], e = range[1];
                float rw = w[Math.min(e, n)] - w[Math.min(s, n)];
                if (acc > 0f && acc + rw > maxW) {
                    rows.add(new int[]{rowStart, s});
                    rowStart = s;
                    acc = 0f;
                }
                acc += rw;
            }
            rows.add(new int[]{rowStart, m.wordRanges[m.wordRanges.length - 1][1]});
        } else {
            int start = 0;
            for (int i = 0; i < n; i++) {
                if (w[i + 1] - w[start] > maxW && i > start) {
                    rows.add(new int[]{start, i});
                    start = i;
                }
            }
            rows.add(new int[]{start, n});
        }
        return rows;
    }

    // ===== 相机（fumeCameraStep 直译）=====

    private void step(float dt, long frameNs) {
        if (layoutDirty) buildArticle();
        if (article == null || article.blocks.isEmpty()) return;

        float time = positionMs / 1000f;
        float now = frameNs / 1_000_000f;

        // 当前行
        int lineIdx = -1;
        float tSec = time;
        int lo = 0, hi = lines.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startTime / 1000f <= tSec) { lineIdx = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        targetIndex = lineIdx;

        // init / clamp
        if (!camInitialized) {
            cam.x = cam.focusX = article.width * 0.5f;
            cam.y = cam.focusY = article.height * 0.5f;
            cam.scale = cam.focusScale = 1.18f;
            cam.velX = cam.velY = cam.velScale = 0f;
            camInitialized = true;
            ret.sourceLineIndex = -1;
        }
        cam.x = clamp(cam.x, 0, article.width);
        cam.y = clamp(cam.y, 0, article.height);
        cam.scale = clamp(cam.scale, CAMERA_SCALE_MIN, CAMERA_SCALE_MAX);
        cam.focusScale = clamp(cam.focusScale, CAMERA_SCALE_MIN, CAMERA_SCALE_MAX);

        // 结尾全景
        float overviewStart = resolveOverviewStartTime();
        boolean showOverview = overviewStart < Float.POSITIVE_INFINITY && time >= overviewStart;

        float targetX = article.width * 0.5f;
        float targetY = article.height * 0.5f;
        float targetScale = 1.18f;
        float entryX = Float.NaN, entryY = Float.NaN;
        boolean didRetarget = false;
        float cameraSpeed = 1f;

        if (showOverview) {
            float[] ov = resolveOverviewCamera();
            targetX = ov[0]; targetY = ov[1]; targetScale = ov[2];
            if (ret.sourceLineIndex != OVERVIEW_SOURCE) {
                ret.sourceLineIndex = OVERVIEW_SOURCE;
                ret.startedAt = time;
                ret.duration = clamp(clamp(Math.min(getWidth(), getHeight()) / 1500f, 0.38f, 0.58f) / cameraSpeed, 0.12f, 1.2f);
                ret.fromX = cam.x; ret.fromY = cam.y; ret.fromScale = cam.scale;
                ret.bridgeMode = 0;
                didRetarget = true;
            }
        } else {
            Block focus = resolveFocusBlock(lineIdx, time);
            if (focus != null) {
                // 焦点：smooth=打印进度小数偏移行内点（跨行混合）/ stepped=整数字素宽度步进
                float[] fp = cameraTrackingMode == 1
                        ? resolveSteppedFocusPoint(focus, printedGraphemeCount(focus, time))
                        : resolveSmoothFocusPoint(focus, printedGraphemeProgress(focus, time));
                targetX = fp[0]; targetY = fp[1];
                targetScale = resolveCameraScaleForBlock(focus);
                // 入场锚点（resolveBlockEntryFocusPoint：首渲染行左侧 + 行中心）
                if (!focus.renderLines.isEmpty()) {
                    RenderLine fl = focus.renderLines.get(0);
                    entryX = focus.x + fl.left;
                    entryY = focus.y + fl.top + focus.lineHeight * 0.5f;
                }
                if (ret.sourceLineIndex != focus.sourceLineIndex) {
                    ret.sourceLineIndex = focus.sourceLineIndex;
                    ret.startedAt = time;
                    ret.duration = clamp(resolveCameraRetargetDuration(focus.line) / cameraSpeed, 0.03f, 0.3f);
                    ret.fromX = cam.x; ret.fromY = cam.y; ret.fromScale = cam.scale;
                    ret.bridgeMode = 0;
                    didRetarget = true;
                }
            } else if (ret.sourceLineIndex != -1) {
                ret.sourceLineIndex = -1;
                ret.startedAt = time;
                ret.duration = clamp(0.18f / cameraSpeed, 0.05f, 0.4f);
                ret.fromX = cam.x; ret.fromY = cam.y; ret.fromScale = cam.scale;
                ret.bridgeMode = 0;
                didRetarget = true;
            }
        }

        float retargetElapsed = Math.max(time - ret.startedAt, 0f);
        float retargetPhase = clamp(retargetElapsed / Math.max(ret.duration, 0.001f), 0f, 1f);
        float retargetBoost = 1f - easeOutCubic(retargetPhase);
        float entryFocusBias = (float) Math.pow(retargetBoost, 0.58);

        if (!Float.isNaN(entryX)) {
            targetX = mix(targetX, entryX, entryFocusBias);
            targetY = mix(targetY, entryY, entryFocusBias);
        }

        // 漂浮（normal 档 18px/7s）
        float floatPhase = (now / 1000f / 7f) * (float) Math.PI * 2f;
        float overviewAttenuation = showOverview ? 0.36f : 1f;
        float screenFloatX = (float) Math.sin(floatPhase * 0.74f + 0.8f) * 18f * 0.34f;
        float screenFloatY = ((float) Math.sin(floatPhase) * 18f
                + (float) Math.sin(floatPhase * 0.5f + 1.1f) * 18f * 0.22f) * overviewAttenuation;
        float worldDiv = Math.max(targetScale, 0.001f);
        targetX -= screenFloatX / worldDiv;
        targetY -= screenFloatY / worldDiv;
        targetScale = clamp(targetScale * (1f + (float) Math.sin(floatPhase + 0.9f) * 0.011f * overviewAttenuation),
                CAMERA_SCALE_MIN, CAMERA_SCALE_MAX);

        if (didRetarget) {
            float bridgeScale = Math.max(Math.max(cam.scale, targetScale), 0.001f);
            float screenDistance = (float) Math.hypot(
                    (targetX - ret.fromX) * bridgeScale, (targetY - ret.fromY) * bridgeScale);
            ret.bridgeMode = screenDistance >= Math.min(getWidth(), getHeight()) * 0.42f ? 1 : 0;
            ret.waypointX = targetX; ret.waypointY = targetY; ret.waypointScale = targetScale;
            ret.waypointPhase = 0.5f;
            // overview 飞桥（远距 > 2.75 屏，仅行间切换启用；歌尾 overview 走纯弹簧）
            if (ret.sourceLineIndex >= 0) {
                float[] bridge = resolveOverviewFlightBridge(ret.fromX, ret.fromY, ret.fromScale,
                        targetX, targetY, targetScale, showOverview);
                if (bridge != null) {
                    ret.bridgeMode = 2;
                    ret.waypointX = bridge[0]; ret.waypointY = bridge[1]; ret.waypointScale = bridge[2];
                    ret.waypointPhase = bridge[3];
                    ret.duration = Math.max(ret.duration, clamp(bridge[4] / cameraSpeed, 0.16f, 0.9f));
                    retargetPhase = clamp(retargetElapsed / Math.max(ret.duration, 0.001f), 0f, 1f);
                }
            }
        }

        boolean useBridge = ret.bridgeMode != 0 && retargetPhase < 1f;
        if (useBridge) {
            float bp = ret.bridgeMode == 2 ? easeOutCubic(retargetPhase) : easeInOutCubic(retargetPhase);
            float bx = ret.bridgeMode == 2
                    ? quadBezier(ret.fromX, ret.waypointX, targetX, bp)
                    : mix(ret.fromX, targetX, bp);
            float by = ret.bridgeMode == 2
                    ? quadBezier(ret.fromY, ret.waypointY, targetY, bp)
                    : mix(ret.fromY, targetY, bp);
            float bs = ret.bridgeMode == 2
                    ? quadBezier(ret.fromScale, ret.waypointScale, targetScale, bp)
                    : mix(ret.fromScale, targetScale, bp);
            float catchUp = 1f - (float) Math.exp(-dt * (ret.bridgeMode == 2
                    ? mix(12.5f, 22f, 1f - retargetPhase) : mix(10.5f, 17.5f, 1f - retargetPhase)));
            cam.focusX = bx; cam.focusY = by; cam.focusScale = bs;
            cam.x += (bx - cam.x) * catchUp;
            cam.y += (by - cam.y) * catchUp;
            cam.scale += (bs - cam.scale) * catchUp;
            cam.scale = clamp(cam.scale, CAMERA_SCALE_MIN, CAMERA_SCALE_MAX);
            cam.velX *= 0.72f; cam.velY *= 0.72f; cam.velScale *= 0.68f;
        } else {
            float boosted = clamp(4.8f / Math.max(ret.duration, 0.05f), 20f, 54f);
            float targetCatchUp = 1f - (float) Math.exp(-dt * mix(11.2f, boosted, retargetBoost));
            cam.focusX += (targetX - cam.focusX) * targetCatchUp;
            cam.focusY += (targetY - cam.focusY) * targetCatchUp;
            cam.focusScale += (targetScale - cam.focusScale) * (1f - (float) Math.exp(-dt * mix(5.4f, 12.8f, retargetBoost)));

            float cameraDistance = (float) Math.hypot(targetX - cam.x, targetY - cam.y);
            float springStrength = mix(208f,
                    clamp(15.8f / Math.max(ret.duration * ret.duration, 0.0064f), 260f, 780f), retargetBoost);
            float damping = mix(24f, clamp((float) Math.sqrt(springStrength) * 1.36f, 24f, 40f), retargetBoost);
            float accelX = (cam.focusX - cam.x) * springStrength - cam.velX * damping;
            float accelY = (cam.focusY - cam.y) * springStrength - cam.velY * damping;
            cam.velX += accelX * dt;
            cam.velY += accelY * dt;
            float maxVelocity = mix(1320f,
                    clamp(cameraDistance / Math.max(ret.duration * 0.28f, 0.028f), 2600f, 8800f), retargetBoost);
            cam.velX = clamp(cam.velX, -maxVelocity, maxVelocity);
            cam.velY = clamp(cam.velY, -maxVelocity, maxVelocity);
            cam.x += cam.velX * dt;
            cam.y += cam.velY * dt;

            float scaleStrength = mix(54f, 108f, retargetBoost);
            float scaleDamping = mix(13.5f, 21f, retargetBoost);
            float accelScale = (cam.focusScale - cam.scale) * scaleStrength - cam.velScale * scaleDamping;
            cam.velScale += accelScale * dt;
            cam.velScale = clamp(cam.velScale, -1.6f, 1.6f);
            cam.scale += cam.velScale * dt;
            cam.scale = clamp(cam.scale, CAMERA_SCALE_MIN, CAMERA_SCALE_MAX);
        }
    }

    /** 焦点块（resolveFocusBlock 直译） */
    private Block resolveFocusBlock(int lineIdx, float time) {
        if (lineIdx >= 0) {
            Block b = article.blockByLine(lineIdx);
            if (b != null) return b;
        }
        Block last = article.lastChronological();
        if (last != null && !article.chronological.isEmpty() && time >= lastRenderEnd(article.chronological.get(article.chronological.size() - 1))) return last;
        for (int i = article.chronological.size() - 1; i >= 0; i--) {
            Block b = article.chronological.get(i);
            if (printedGraphemeCount(b, time) > 0) return b;
        }
        return article.chronological.isEmpty() ? null : article.chronological.get(0);
    }

    /** smooth 焦点：打印进度 → 行内坐标（resolveSmoothBlockFocusPoint 直译，跨行 0.7 混合） */
    private float[] resolveSmoothFocusPoint(Block block, float printed) {
        float[] out = new float[2];
        float[] w = block.measures.widths(block.fontPx, textPaint);
        int n = block.measures.clusters.length;
        float effective = clamp(printed, 0f, n);
        // 找渲染行
        RenderLine tl = null;
        int tlIdx = -1;
        for (int i = 0; i < block.renderLines.size(); i++) {
            if (effective <= block.renderLines.get(i).endGlyph) { tl = block.renderLines.get(i); tlIdx = i; break; }
        }
        if (tl == null) { tl = block.renderLines.get(block.renderLines.size() - 1); tlIdx = block.renderLines.size() - 1; }
        float localOffset = clamp(effective, tl.startGlyph, tl.endGlyph);
        float baseOffset = (float) Math.floor(localOffset);
        float frac = localOffset - baseOffset;
        float baseWidth = w[(int) baseOffset] - w[tl.startGlyph];
        int li = (int) baseOffset - tl.startGlyph;
        float glyphAdvance = li >= 0 && li < tl.endGlyph - tl.startGlyph
                ? w[(int) baseOffset + 1] - w[(int) baseOffset] : 0f;
        float minX = block.x + tl.left;
        float maxX = minX + tl.width;
        float px = clamp(minX + baseWidth + glyphAdvance * frac, minX, maxX);
        float py = block.y + tl.top + block.lineHeight * 0.5f;
        out[0] = px; out[1] = py;
        // 跨行混合窗口 0.7（resolvePointOnRenderLine(prev.end) / (next.start)）
        float window = 0.7f;
        if (tlIdx > 0 && effective < tl.startGlyph + window) {
            RenderLine prev = block.renderLines.get(tlIdx - 1);
            float blend = easeInOutCubic(clamp(1f - (effective - prev.endGlyph) / window, 0f, 1f));
            float ppx = clamp(block.x + prev.left + prev.width, block.x + prev.left, block.x + prev.left + prev.width);
            float ppy = block.y + prev.top + block.lineHeight * 0.5f;
            out[0] = mix(px, ppx, blend);
            out[1] = mix(py, ppy, blend);
        } else if (tlIdx < block.renderLines.size() - 1 && effective > tl.endGlyph - window) {
            RenderLine next = block.renderLines.get(tlIdx + 1);
            float blend = easeInOutCubic(clamp((effective - (tl.endGlyph - window)) / window, 0f, 1f));
            float npx = block.x + next.left;
            float npy = block.y + next.top + block.lineHeight * 0.5f;
            out[0] = mix(px, npx, blend);
            out[1] = mix(py, npy, blend);
        }
        return out;
    }

    /** stepped 焦点（resolveSteppedBlockFocusPoint 直译：整数字素打印宽度步进，无小数插值无跨行混合） */
    private float[] resolveSteppedFocusPoint(Block block, int printedCount) {
        float[] out = new float[2];
        if (block.renderLines.isEmpty()) {
            out[0] = block.x + block.width * 0.5f;
            out[1] = block.y + block.height * 0.5f;
            return out;
        }
        float[] w = block.measures.widths(block.fontPx, textPaint);
        int n = block.measures.clusters.length;
        float effective = clamp(printedCount, 0, n);
        RenderLine tl = null;
        for (int i = 0; i < block.renderLines.size(); i++) {
            if (effective <= block.renderLines.get(i).endGlyph) { tl = block.renderLines.get(i); break; }
        }
        if (tl == null) tl = block.renderLines.get(block.renderLines.size() - 1);
        float localOffset = clamp(effective, tl.startGlyph, tl.endGlyph);
        float progressWidth = w[(int) localOffset] - w[tl.startGlyph];
        float minX = block.x + tl.left;
        float maxX = minX + tl.width;
        out[0] = clamp(minX + progressWidth, minX, maxX);
        out[1] = block.y + tl.top + block.lineHeight * 0.5f;
        return out;
    }

    /** 换块 retarget 时长（renderHints.resolveCameraRetargetDuration 直译：按行过渡三档） */
    private static float resolveCameraRetargetDuration(KrcParser.LyricLine line) {
        if (line == null) return 0.09f;
        float start = line.startTime / 1000f;
        float raw = Math.max((line.startTime + Math.max(line.duration, 0)) / 1000f - start, 0f);
        // timingClass：micro<0.10 / short<0.18 / normal
        boolean micro = raw < 0.10f;
        boolean shortLine = !micro && raw < 0.18f;
        if (micro) {
            // lineTransitionMode='none'
            return clamp(Math.max(raw, 0.08f) * 0.34f, 0.04f, 0.075f);
        }
        if (shortLine) {
            // 'fast'：enter=clamp(raw*0.45,.045,.06) exit=clamp(raw*0.22,.03,.04) hold=.03
            float enter = clamp(raw * 0.45f, 0.045f, 0.06f);
            float exit = clamp(raw * 0.22f, 0.03f, 0.04f);
            return clamp(enter * 0.5f + exit * 0.12f, 0.055f, 0.095f);
        }
        // 'normal'：enter=min(.42,max(.22,max(raw,.12)*.34)) hold=.06
        float enter = Math.min(0.42f, Math.max(0.22f, Math.max(raw, 0.12f) * 0.34f));
        return clamp(enter * 0.44f + 0.06f * 0.22f, 0.075f, 0.13f);
    }

    private float resolveCameraScaleForBlock(Block block) {
        float minSide = Math.max(Math.min(getWidth(), getHeight()), 1f);
        float targetLineHeight = clamp(minSide * 0.115f, 64f, 124f);
        return clamp(targetLineHeight / Math.max(block.lineHeight, 1f), 0.88f, 2.2f);
    }

    /** 结尾全景时刻：最后可排行的中点 */
    private float resolveOverviewStartTime() {
        Block last = article.lastChronological();
        if (last == null) return Float.POSITIVE_INFINITY;
        float start = last.line.startTime / 1000f;
        float end = lastRenderEnd(last);
        return start + Math.max(end - start, 0f) * 0.5f;
    }

    private float lastRenderEnd(Block b) {
        return (b.line.startTime + Math.max(b.line.duration, 1)) / 1000f;
    }

    /** 俯瞰相机（resolveArticleOverviewCamera 直译） */
    private float[] resolveOverviewCamera() {
        if (article.blocks.isEmpty()) {
            float fit = Math.min(getWidth() / Math.max(article.width, 1f), getHeight() / Math.max(article.height, 1f));
            return new float[]{article.width * 0.5f, article.height * 0.5f, clamp(fit * 0.92f, CAMERA_SCALE_MIN, 0.72f)};
        }
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (Block b : article.blocks) {
            minX = Math.min(minX, b.x); minY = Math.min(minY, b.y);
            maxX = Math.max(maxX, b.x + b.width); maxY = Math.max(maxY, b.y + b.height);
        }
        float padX = clamp(getWidth() * 0.2f, 120f, 280f);
        float padY = clamp(getHeight() * 0.2f, 96f, 220f);
        float fw = Math.max(maxX - minX + padX * 2f, 1f);
        float fh = Math.max(maxY - minY + padY * 2f, 1f);
        float fit = Math.min(getWidth() / fw, getHeight() / fh);
        return new float[]{(minX + maxX) * 0.5f, (minY + maxY) * 0.5f, clamp(fit, CAMERA_SCALE_MIN, 0.72f)};
    }

    /** overview 飞桥（resolveOverviewFlightBridge 直译） */
    private float[] resolveOverviewFlightBridge(float fromX, float fromY, float fromScale,
                                                float targetX, float targetY, float targetScale,
                                                boolean showOverview) {
        float[] ov = resolveOverviewCamera();
        float safeScale = Math.max(Math.max(fromScale, targetScale), Math.max(ov[2], 0.001f));
        float minSide = Math.max(Math.min(getWidth(), getHeight()), 1f);
        float worldDistance = (float) Math.hypot(fromX - targetX, fromY - targetY);
        float screenDistance = worldDistance * safeScale;
        if (worldDistance <= 0f || screenDistance < minSide * TELEPORT_SCREENS) return null;

        float loftStrength = clamp((screenDistance / minSide - TELEPORT_SCREENS) / 3.4f, 0f, 1f);
        float midX = mix(fromX, targetX, 0.5f), midY = mix(fromY, targetY, 0.5f);
        float waypointCenterBias = mix(0.18f, 0.42f, loftStrength);
        float wx = mix(midX, ov[0], waypointCenterBias);
        float wy = mix(midY, ov[1], waypointCenterBias);
        float endpointScale = Math.max(Math.max(fromScale, targetScale), 0.001f);
        float loftedScale = endpointScale * mix(0.62f, 0.4f, loftStrength);
        float overviewLimited = ov[2] * mix(1.85f, 1.55f, loftStrength);
        float ws = clamp(Math.max(loftedScale, overviewLimited), CAMERA_SCALE_MIN,
                Math.max(endpointScale * 0.92f, CAMERA_SCALE_MIN));
        float distFromStart = (float) Math.hypot(wx - fromX, wy - fromY) * Math.max(Math.max(fromScale, ws), 0.001f);
        float distToTarget = (float) Math.hypot(targetX - wx, targetY - wy) * Math.max(Math.max(targetScale, ws), 0.001f);
        float total = distFromStart + distToTarget;
        float phase = total <= 0f ? 0.36f : clamp(distFromStart / total, 0.26f, 0.44f);
        float duration = clamp(0.26f + (screenDistance / (minSide * 5.5f)) * 0.28f, 0.3f, 0.68f);
        return new float[]{wx, wy, ws, phase, duration};
    }

    // ===== 打印进度（fumeReveal 直译，簇空间） =====

    /** 已打印字数（词序映射+词内均匀+空格并入） */
    private int printedGraphemeCount(Block block, float time) {
        KrcParser.LyricLine line = block.line;
        LineMeasures m = block.measures;
        int n = m.clusters.length;
        int[][] ranges = m.wordRanges;
        if (ranges == null || ranges.length == 0) {
            // LRC：全行均匀
            float start = line.startTime / 1000f;
            float end = lastRenderEnd(block);
            float p = clamp((time - start) / Math.max(end - start, 0.18f), 0f, 1f);
            return Math.round(p * n);
        }
        int printed = 0;
        for (int r = 0; r < ranges.length; r++) {
            int wi = m.wordSourceIdx[r];
            if (wi < 0 || wi >= line.words.size()) break;
            KrcParser.LyricWord word = line.words.get(wi);
            int s = ranges[r][0], e = ranges[r][1];
            float wStart = word.startTime / 1000f;
            float wEnd = wStart + Math.max(0f, word.duration / 1000f);
            int len = Math.max(1, e - s);
            if (time >= wEnd) printed = e;
            else if (time > wStart) {
                float p = clamp((time - wStart) / Math.max(wEnd - wStart, 0.08f), 0f, 1f);
                printed = s + (int) clamp(Math.round(p * len + 0.2f), p > 0 ? 1 : 0, len);
                break;
            } else break;
        }
        return printed;
    }

    /** 已打印字数小数进度（相机 smooth 用） */
    private float printedGraphemeProgress(Block block, float time) {
        KrcParser.LyricLine line = block.line;
        LineMeasures m = block.measures;
        int n = m.clusters.length;
        int[][] ranges = m.wordRanges;
        if (ranges == null || ranges.length == 0) {
            float start = line.startTime / 1000f;
            float end = lastRenderEnd(block);
            return clamp((time - start) / Math.max(end - start, 0.18f), 0f, 1f) * n;
        }
        float acc = 0f;
        for (int r = 0; r < ranges.length; r++) {
            int wi = m.wordSourceIdx[r];
            if (wi < 0 || wi >= line.words.size()) break;
            KrcParser.LyricWord word = line.words.get(wi);
            int s = ranges[r][0], e = ranges[r][1];
            float wStart = word.startTime / 1000f;
            float wEnd = wStart + Math.max(0f, word.duration / 1000f);
            if (time >= wEnd) { acc = e; }
            else if (time > wStart) {
                float p = clamp((time - wStart) / Math.max(wEnd - wStart, 0.08f), 0f, 1f);
                acc = s + p * (e - s);
                break;
            } else break;
        }
        return acc;
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (article == null || layoutDirty) {
            if (layoutDirty) buildArticle();
            if (article == null) return;
        }
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float time = positionMs / 1000f;

        // 背景由页面级星空底层提供，本视图纯内容层 translucent
        float viewportCX = w * 0.5f, viewportCY = h * 0.5f;
        float screenScale = cam.scale;

        int sc = canvas.save();
        canvas.translate(viewportCX, viewportCY);
        canvas.scale(screenScale, screenScale);
        canvas.translate(-cam.x, -cam.y);

        float[] glowBases = resolveGlowBases();
        float activeGlowBoost = glowBases[0], passedGlowBase = glowBases[1];
        float margin = 32f / screenScale;
        float visLeft = cam.x - viewportCX / screenScale - margin;
        float visTop = cam.y - viewportCY / screenScale - margin;
        float visRight = cam.x + viewportCX / screenScale + margin;
        float visBottom = cam.y + viewportCY / screenScale + margin;

        for (Block block : article.blocks) {
            // 视口剔除（isFumeBlockOnScreen 语义）
            float sLeft = viewportCX + (block.x - cam.x) * screenScale;
            float sTop = viewportCY + (block.y - cam.y) * screenScale;
            float sRight = sLeft + block.width * screenScale;
            float sBottom = sTop + block.height * screenScale;
            if (sRight < -180f || sLeft > w + 180f || sBottom < -180f || sTop > h + 180f) continue;

            drawBlock(canvas, block, time, activeGlowBoost, passedGlowBase);
        }
        canvas.restoreToCount(sc);
    }

    /** 单块渲染（fumeBlockFrame + fumeCanvasText 直译：同态段合批 + 每字素印章 + cutoff 快进） */
    private void drawBlock(Canvas canvas, Block block, float time, float activeGlowBoost, float passedGlowBase) {
        KrcParser.LyricLine line = block.line;
        LineMeasures m = block.measures;
        int li = block.sourceLineIndex;
        boolean isChorus = li >= 0 && li < chorusFlags.length && chorusFlags[li];
        float start = line.startTime / 1000f;
        float lineEnd = lastRenderEnd(block);
        float nextStart = li + 1 < lines.size() ? lines.get(li + 1).startTime / 1000f : Float.POSITIVE_INFINITY;
        float passCutoff = Math.min(lineEnd, nextStart);
        float lineDuration = Math.max(lineEnd - start, 0.18f);
        float colorTrailDuration = clamp(lineDuration * (block.hero ? 0.42f : 0.52f), 0.45f, 1.45f);

        float waitingOpacity = block.hero ? 0.06f : 0.035f;
        float activeOpacity = block.hero ? 0.985f : 0.92f;
        // passed 样式（textHoldRatio=1 → standard）
        float passedOpacity = block.hero ? 0.74f : 0.58f;

        boolean isLineActive = time >= start && time <= passCutoff;
        boolean hasRevealCompleted = time >= lineEnd;
        int total = m.clusters.length;
        float[] w = m.widths(block.fontPx, textPaint);

        textPaint.setTextSize(block.fontPx);
        float baselineOffset = block.lineHeight * (isCJKText(m.text) ? 0.52f : 0.5f);

        // 词时间窗预填表（每帧每块 O(words+total)，后续 O(1) 查询）
        int[][] ranges = m.wordRanges;
        float[] winTable = null;   // [g*4] = {wStart,wEnd,glyphStart,glyphEnd}
        if (ranges != null && ranges.length > 0) {
            winTable = new float[Math.max(total, 1) * 4];
            for (int r = 0; r < ranges.length; r++) {
                int wi = m.wordSourceIdx[r];
                if (wi < 0 || wi >= line.words.size()) continue;
                KrcParser.LyricWord word = line.words.get(wi);
                int s = ranges[r][0], e = ranges[r][1];
                float wStart = word.startTime / 1000f;
                float wDur = Math.max(0.001f, word.duration / 1000f);
                int len = Math.max(1, e - s);
                float unit = wDur / len;
                for (int g = s; g < e && g < total; g++) {
                    winTable[g * 4] = wStart;
                    winTable[g * 4 + 1] = wStart + wDur;
                    winTable[g * 4 + 2] = wStart + (g - s) * unit;
                    winTable[g * 4 + 3] = wStart + (g - s + 1) * unit;
                }
            }
        }

        // 行激活：底层行辉光（hero 0.16+0.26·包络 / body 0.12+0.2；逐渲染行一笔合批）
        if (isLineActive) {
            float lineProgress = clamp((time - start) / Math.max(passCutoff - start, 0.18f), 0f, 1f);
            float envelope = delayedGlowEnvelope(lineProgress, 0.8f);
            float glowAlpha = (block.hero ? 0.16f : 0.12f) + envelope * (block.hero ? 0.26f : 0.2f);
            float glowBlur = (block.hero ? 12f : 8f) + envelope * block.fontPx * (block.hero ? 0.7f : 0.52f);
            textPaint.setShadowLayer(glowBlur, 0, 0, withAlpha(colorAccent, glowAlpha));
            textPaint.setColor(withAlpha(colorAccent, glowAlpha));
            for (RenderLine rl : block.renderLines) {
                if (rl.text.isEmpty()) continue;
                canvas.drawText(rl.text, block.x + rl.left, block.y + rl.top + baselineOffset, textPaint);
            }
            textPaint.setShadowLayer(0, 0, 0, 0);
        }

        // 主渲染状态机：0=waiting（未唱）/ 1=settled（色尾完成/passed 终值）合批；
        // 2=dynamic（激活中/尾迹中）逐字素独立画（进度连续无法合并）
        int activeColor = isChorus ? mixRGB(colorPrimary, colorAccent, 0.35f) : colorAccent;
        int printedCount = printedGraphemeCount(block, time);
        int runStart = -1, runState = -1;
        int[] rlOf = block.renderLineOfCluster;

        for (int g = 0; g < total; g++) {
            String cl = m.clusters[g];
            if (cl.trim().isEmpty()) continue;   // 空白不画不断段（合批文本自然含空格）

            int state;
            if (winTable != null) {
                float wStart = winTable[g * 4];
                if (time < wStart) {
                    state = 0;
                } else {
                    // cutoff 快进色尾（resolveVisualProgressWithCutoff 直译）
                    float glyphStart = winTable[g * 4 + 2];
                    float glyphDuration = Math.max(winTable[g * 4 + 3] - glyphStart, 0.001f);
                    float glyphTrailStart = glyphStart + glyphDuration * 0.18f;
                    float trailPhase = visualProgressWithCutoff(glyphTrailStart, colorTrailDuration, time, passCutoff);
                    state = trailPhase >= 0.999f ? 1 : 2;
                }
            } else {
                // 无词 LRC：全行均匀
                float p = clamp((time - start) / Math.max(lineEnd - start, 0.18f), 0f, 1f);
                state = (hasRevealCompleted || time >= passCutoff) ? 1
                        : (g < printedCount ? 2 : 0);
            }

            // 印章：每字素独立窗口（cutoff 冻结），无词数据无印章
            if (winTable != null) {
                drawPrintStamp(canvas, block, renderLineOf(block, rlOf, g), g, time, winTable, activeColor,
                        activeGlowBoost, lineDuration, passCutoff);
            }

            if (state == 2) {
                flushFumeRun(canvas, block, m, w, rlOf, runStart, g, runState, baselineOffset,
                        waitingOpacity, passedOpacity, passedGlowBase);
                runStart = -1;
                drawDynamicGrapheme(canvas, block, m, w, rlOf, g, time, winTable,
                        waitingOpacity, activeOpacity, passedOpacity, activeColor,
                        activeGlowBoost, passedGlowBase, colorTrailDuration, passCutoff, baselineOffset);
                continue;
            }
            if (runStart < 0 || runState != state || rlOf[g] != rlOf[runStart]) {
                flushFumeRun(canvas, block, m, w, rlOf, runStart, g, runState, baselineOffset,
                        waitingOpacity, passedOpacity, passedGlowBase);
                runStart = g;
                runState = state;
            }
        }
        flushFumeRun(canvas, block, m, w, rlOf, runStart, total, runState, baselineOffset,
                waitingOpacity, passedOpacity, passedGlowBase);
        textPaint.setShadowLayer(0, 0, 0, 0);
    }

    /** 簇 g 所属渲染行 */
    private RenderLine renderLineOf(Block block, int[] rlOf, int g) {
        int idx = rlOf != null && g < rlOf.length ? rlOf[g] : 0;
        if (idx < 0 || idx >= block.renderLines.size()) idx = block.renderLines.size() - 1;
        return block.renderLines.get(idx);
    }

    /** 同态连续段 [s,e) 合批一笔（waiting / settled 终值样式相同才可合并） */
    private void flushFumeRun(Canvas canvas, Block block, LineMeasures m, float[] w, int[] rlOf,
                              int s, int e, int state, float baselineOffset,
                              float waitingOpacity, float passedOpacity, float passedGlowBase) {
        if (s < 0 || e <= s || (state != 0 && state != 1)) return;
        RenderLine r = renderLineOf(block, rlOf, s);
        float x = block.x + r.left + (w[s] - w[r.startGlyph]);
        float y = block.y + r.top + baselineOffset;
        String seg = m.text.substring(m.cuStart[s], m.cuStart[Math.min(e, m.clusters.length)]);
        if (state == 0) {
            textPaint.setShadowLayer(0, 0, 0, 0);
            textPaint.setColor(withAlpha(colorPrimary, waitingOpacity));
        } else {
            // settled：色尾 trailProgress=1 终值（mixRGB → colorPrimary，辉光 0.1）
            float settledBlur = (2f + block.fontPx * 0.1f) * (1f - 0.35f) * 0.65f * passedGlowBase;
            textPaint.setShadowLayer(settledBlur, 0, 0, withAlpha(colorPrimary, 0.1f));
            textPaint.setColor(withAlpha(colorPrimary, passedOpacity));
        }
        canvas.drawText(seg, x, y, textPaint);
        textPaint.setShadowLayer(0, 0, 0, 0);
    }

    /** 动态字素（激活推进/尾迹褪色，进度连续逐字素画） */
    private void drawDynamicGrapheme(Canvas canvas, Block block, LineMeasures m, float[] w, int[] rlOf,
                                     int g, float time, float[] winTable,
                                     float waitingOpacity, float activeOpacity, float passedOpacity,
                                     int activeColor, float activeGlowBoost, float passedGlowBase,
                                     float colorTrailDuration, float passCutoff, float baselineOffset) {
        RenderLine r = renderLineOf(block, rlOf, g);
        float x = block.x + r.left + (w[g] - w[r.startGlyph]);
        float y = block.y + r.top + baselineOffset;

        float alpha, shadowBlur = 0f, shadowAlpha = 0f;
        int fillColor, glowColor;
        if (winTable != null) {
            float glyphStart = winTable[g * 4 + 2];
            float glyphEnd = winTable[g * 4 + 3];
            float glyphDuration = Math.max(glyphEnd - glyphStart, 0.001f);
            float glyphProgress = clamp((time - glyphStart) / glyphDuration + 0.16f, 0f, 1f);
            float eased = easeOutCubic(glyphProgress);
            float glyphTrailStart = glyphStart + glyphDuration * 0.18f;
            float trailPhase = visualProgressWithCutoff(glyphTrailStart, colorTrailDuration, time, passCutoff);
            float trailProgress = (float) Math.pow(trailPhase, 1.35f);

            if (time <= glyphTrailStart) {
                // 激活推进：词色混入 0.22+0.78p + 辉光(4+0.22f)·p
                alpha = mix(waitingOpacity, activeOpacity, eased);
                fillColor = mixRGB(colorPrimary, activeColor, 0.22f + eased * 0.78f);
                shadowBlur = (4f + block.fontPx * 0.22f) * eased * activeGlowBoost;
                shadowAlpha = 0.4f + eased * 0.44f;
                glowColor = activeColor;
            } else {
                // 尾迹褪色（cutoff 快进）→ passed
                alpha = mix(activeOpacity, passedOpacity, trailProgress);
                fillColor = mixRGB(activeColor, colorPrimary, 0.18f + trailProgress * 0.82f);
                shadowBlur = (2f + block.fontPx * 0.1f) * (1f - trailProgress * 0.35f) * 0.65f * passedGlowBase;
                shadowAlpha = 0.1f + (1f - trailProgress) * 0.16f;
                glowColor = mixRGB(activeColor, colorPrimary, 0.55f + trailProgress * 0.45f);
            }
        } else {
            // 无词 LRC 激活段：满亮主色
            alpha = activeOpacity;
            fillColor = colorPrimary;
            glowColor = colorPrimary;
        }

        if (alpha <= 0.002f) return;
        if (shadowBlur > 0.5f && shadowAlpha > 0.01f) {
            textPaint.setShadowLayer(shadowBlur, 0, 0, withAlpha(glowColor, shadowAlpha));
        } else {
            textPaint.setShadowLayer(0, 0, 0, 0);
        }
        textPaint.setColor(withAlpha(fillColor, alpha));
        canvas.drawText(m.clusters[g], x, y, textPaint);
        textPaint.setShadowLayer(0, 0, 0, 0);
    }

    /** 印章方块（fumeCanvasText stamp 段直译：每字素独立窗口 + progressWithCutoff 冻结） */
    private void drawPrintStamp(Canvas canvas, Block block, RenderLine r, int g, float time,
                                float[] winTable, int activeColor, float activeGlowBoost,
                                float lineDuration, float passCutoff) {
        float[] w = block.measures.widths(block.fontPx, textPaint);
        float wStart = winTable[g * 4], wEnd = winTable[g * 4 + 1];
        float glyphStart = winTable[g * 4 + 2], glyphEnd = winTable[g * 4 + 3];
        // 词簇数（窗口时长用）
        int len = 1;
        int s = g;
        while (s > 0 && winTable[(s - 1) * 4] == wStart) s--;
        int e = g;
        int cap = winTable.length / 4;
        while (e < cap && winTable[e * 4] == wStart) e++;
        len = Math.max(1, e - s);

        float wordDuration = Math.max(wEnd - wStart, 0.08f);
        float glyphWindowDuration = Math.max(wordDuration / len, 0.04f);
        float activationLead = clamp(Math.min(glyphWindowDuration * 0.86f, lineDuration * 0.16f),
                0.055f, block.hero ? 0.2f : 0.16f);
        float activationRelease = activationLead * 0.42f;
        float glyphDuration = Math.max(glyphEnd - glyphStart, 0.001f);
        float glyphTrailStart = glyphStart + glyphDuration * 0.18f;
        float windowStart = glyphTrailStart - activationLead;
        float windowEnd = glyphTrailStart + activationRelease;

        float stampProgress = visualProgressWithCutoff(windowStart, windowEnd - windowStart, time, passCutoff);
        if (stampProgress <= 0f || stampProgress >= 1f) return;

        float glyphTrailPhase = visualProgressWithCutoff(glyphTrailStart,
                Math.max(windowEnd - glyphTrailStart, 0.001f), time, passCutoff);
        boolean isDropping = glyphTrailPhase <= 0f;
        float dropProgress = isDropping
                ? easeOutCubic(visualProgressWithCutoff(windowStart, Math.max(glyphTrailStart - windowStart, 0.001f), time, passCutoff))
                : 1f;
        float fadeProgress = isDropping ? 0f : easeInOutCubic(glyphTrailPhase);
        float blockPulse = isDropping
                ? mix(0.18f, 1f, (float) Math.pow(dropProgress, 0.78f))
                : (float) Math.pow(1f - fadeProgress, 1.2f);

        float glyphAdvance = (g + 1 < w.length ? w[g + 1] : w[g]) - w[g];
        String cl = block.measures.clusters[g];
        float glyphVisualWidth = Math.max(glyphAdvance * 0.88f,
                isCJKText(cl) ? block.fontPx * 0.56f : block.fontPx * 0.38f);
        float centerX = block.x + r.left + (w[g] - w[r.startGlyph]) + glyphAdvance * 0.5f;
        float dropDistance = block.lineHeight * (block.hero ? 0.24f : 0.2f);
        float stampAlpha = blockPulse * (block.hero ? 0.82f : 0.72f);
        float stampW = glyphVisualWidth + block.fontPx * (block.hero ? 0.18f : 0.12f);
        float stampH = block.fontPx * (block.hero ? 0.72f : 0.62f);
        float stampY = block.y + r.top + block.lineHeight * 0.52f
                - block.fontPx * 0.38f - mix(dropDistance, 0f, dropProgress);

        if (stampW > 0f && stampAlpha > 0.02f) {
            bgPaint.setShadowLayer((8f + block.fontPx * 0.24f) * blockPulse * activeGlowBoost, 0, 0,
                    withAlpha(activeColor, 0.56f * blockPulse));
            bgPaint.setColor(withAlpha(activeColor, stampAlpha));
            canvas.drawRect(centerX - stampW * 0.5f, stampY - stampH * 0.5f,
                    centerX + stampW * 0.5f, stampY + stampH * 0.5f, bgPaint);
            bgPaint.setShadowLayer(0, 0, 0, 0);
        }
    }

    /** resolveVisualProgressWithCutoff 直译：cutoff 到点时进度冻结（不再产生新视觉） */
    private static float visualProgressWithCutoff(float startedAt, float duration, float time, float cutoff) {
        float nominalEnd = startedAt + Math.max(duration, 0.001f);
        float effectiveEnd = Math.max(startedAt + 0.001f, Math.min(nominalEnd, cutoff));
        return clamp((time - startedAt) / Math.max(effectiveEnd - startedAt, 0.001f), 0f, 1f);
    }

    private float[] resolveGlowBases() {
        // normal 档
        return new float[]{0.92f, 0.62f};
    }

    // ===== 工具（fumeMath 直译）=====

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static float quadBezier(float a, float c, float b, float t) {
        float i = 1f - t;
        return i * i * a + 2f * i * t * c + t * t * b;
    }
    private static float easeOutCubic(float v) { return 1f - (float) Math.pow(1f - clamp(v, 0f, 1f), 3); }
    private static float easeInOutCubic(float v) {
        float n = clamp(v, 0f, 1f);
        return n < 0.5f ? 4f * n * n * n : 1f - (float) Math.pow(-2f * n + 2f, 3) / 2f;
    }
    private static float delayedGlowEnvelope(float progress, float peakProgress) {
        float n = clamp(progress, 0f, 1f);
        float p = clamp(peakProgress, 0.05f, 0.95f);
        if (n <= p) return easeOutCubic(n / p);
        return 1f - (float) Math.pow((n - p) / (1f - p), 3);
    }

    /** 确定性 seed（hashString+FNV 直译） */
    private static float seeded(String input) {
        int hash = 0x811C9DC5;
        for (int i = 0; i < input.length(); i++) {
            hash ^= input.charAt(i);
            hash *= 0x01000193;
        }
        return (hash >>> 0) % 10000 / 10000f;
    }

    private static boolean isCJKChar(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF);
    }

    /** fumeMath.isCJK：整行含任一 CJK 字符即为 true */
    private static boolean isCJKText(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) if (isCJKChar(text.charAt(i))) return true;
        return false;
    }

    private static int mixRGB(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.rgb(r, g, b);
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (clamp(alpha, 0f, 1f) * 255f);
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** RenderLine top 由所在行号决定（fumeArticleLayout：paddingY + lineIndex * lineHeight；Android 化 paddingY=0） */
    private void fixRenderLineTops(Block block) {
        for (int i = 0; i < block.renderLines.size(); i++) {
            block.renderLines.get(i).top = i * block.lineHeight;
        }
    }
}
