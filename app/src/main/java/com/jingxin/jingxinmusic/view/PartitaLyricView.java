package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.jingxin.jingxinmusic.util.KrcParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * 云阶歌词（第六种歌词动效·移植 folia partita：乐谱阶梯·全保真版）
 *
 * 一行歌词切成若干"乐句块"（chunk）排进若干列（超长行拆列，列间 gap），块从上到下
 * 堆叠成阶梯；奇偶块左右交错 stagger（±20~100px 随机，folia 原值）+ 行位偏置
 * （rowBias×2.5）+ 块 scale 抖动（0.8~1.7×）；每块 L 形阶梯引导线（左角/右角交替，
 * waiting 收起/active 展开发光/passed 褪白）；块内词为流光同款三态（弹簧弹入+字素
 * 双光晕+颜色渐变）；块 passed 后 5s 线性缓转 ±3°（奇偶交替）。
 *
 * chunk 切分（有意不均匀）：每列可容行数 = 可用高(65%)/100px，总行数超一列容量拆多列
 * （列从右往左排、整体居中）；每块长 = round(均值±50%抖动)（行种子顺序掷骰），
 * 末块兜底吃剩余。
 * 语义单元 = CJK 语义词组（时间相邻 <200ms 且组合 ≤5 字贪心粘合，组内保留逐词时间）
 * + 标点向前粘滞（块边界只落语义缝）。
 * 布局缓存 Map（一行一次）+ 下一行预热；密度补偿（全行字素 >40 字号 ×0.8）。
 */
public class PartitaLyricView extends View {

    // ===== tuning（DEFAULT_PARTITA_TUNING 原值） =====
    private static final float STAGGER_MIN = 20f, STAGGER_MAX = 100f;
    private static final boolean SHOW_GUIDE_LINES = true;
    private static final float PASSED_ROTATE_DEG = 3f;   // folia normal 档 ±3°（chaotic 6°）
    private static final long PASSED_ROTATE_MS = 5000L;  // 5s 线性缓转

    private static final long LOOKAHEAD_MS = 150L;
    private static final float COLUMN_GAP_DP = 32f;      // 列间 gap（folia 2rem 概念）
    private static final float COLUMN_MIN_WIDTH_DP = 60f;
    private static final int MAX_COLUMNS = 4;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random seedRandom = new Random();

    private List<KrcParser.LyricLine> lines = Collections.emptyList();
    private int targetIndex = -1;
    private int positionMs;
    private int colorPrimary = Color.rgb(0xB9, 0xD7, 0xF2);
    private int colorAccent = Color.rgb(0xC9, 0xE4, 0xFF);

    private int layoutW, layoutH;
    private float textSizeCurrent;

    // ===== 列/块（一行一次，行种子确定；布局缓存，动画态不缓存） =====
    private static final class Column {
        final List<Chunk> chunks = new ArrayList<>();
        float offsetX;   // 列中心 X（页坐标系）
        float width;     // 列内容最宽块宽
    }
    private static final class Chunk {
        List<KrcParser.LyricWord> words;   // 块内词
        int rowIndex;                       // 全局行号（stagger 奇偶依据）
        float x;                            // stagger 偏移（-左/+右）
        float y;                            // 行位偏置
        float scale;
        float passedRotate;                // ±3° 交替
        long chunkStart, chunkEnd;
        // 运行态（不缓存）
        int state;                          // 0=waiting 1=active 2=passed
        long stateChangeMs;
        long enterMs = -1L;
        long passedMs = -1L;
    }
    private Column[] columns = new Column[0];
    private final HashMap<Long, Column[]> layoutCache = new HashMap<>();
    private static final int CACHE_LIMIT = 48;

    public PartitaLyricView(Context context) { super(context); init(); }
    public PartitaLyricView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public PartitaLyricView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ===== 对外接口 =====

    public void setLyricData(KrcParser.LyricData data) {
        this.lines = (data != null && data.lines != null) ? data.lines : Collections.emptyList();
        this.targetIndex = -1;
        this.columns = new Column[0];
        layoutCache.clear();
        invalidate();
    }

    public void updatePosition(int positionMs) {
        this.positionMs = positionMs;
        if (lines.isEmpty()) {
            if (targetIndex != -1) { targetIndex = -1; columns = new Column[0]; }
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
            if (idx >= 0) columns = getOrBuildLayout(idx);
        } else if (idx >= 0) {
            // 预热下一行（提前 1.2s，切行瞬间布局就绪）
            int next = idx + 1;
            if (next < lines.size()) {
                KrcParser.LyricLine nl = lines.get(next);
                if (positionMs >= nl.startTime - 1200 && !layoutCache.containsKey(nl.startTime)) {
                    buildLayout(next);
                }
            }
        }
        postInvalidateOnAnimation();
    }

    public void setColors(int primary, int accent) {
        if (primary != 0) colorPrimary = primary;
        if (accent != 0) colorAccent = accent;
        invalidate();
    }

    // ===== 布局（一行一次 + 缓存） =====

    private Column[] getOrBuildLayout(int lineIdx) {
        KrcParser.LyricLine line = lines.get(lineIdx);
        Column[] cached = layoutCache.get(line.startTime);
        if (cached != null) {
            for (Column col : cached) {
                for (Chunk c : col.chunks) {
                    c.state = 0; c.stateChangeMs = 0; c.enterMs = -1L; c.passedMs = -1L;
                }
            }
            return cached;
        }
        return buildLayout(lineIdx);
    }

    private Column[] buildLayout(int lineIdx) {
        KrcParser.LyricLine line = lines.get(lineIdx);
        List<KrcParser.LyricWord> words = (line.words != null) ? new ArrayList<>(line.words) : new ArrayList<>();
        if (words.isEmpty() && line.text != null && !line.text.isEmpty()) {
            KrcParser.LyricWord w = new KrcParser.LyricWord();
            w.text = line.text;
            w.startTime = (int) line.startTime;
            w.duration = (int) Math.max(line.duration, 1L);
            words.add(w);
        }

        // ===== 语义单元：CJK 语义词组贪心粘合 + 标点向前粘滞（块边界只落语义缝） =====
        List<List<KrcParser.LyricWord>> unitWords = semanticUnits(words);
        int unitsCount = Math.max(unitWords.size(), 1);

        // 密度补偿：全行字素 >40 → ×0.8
        int totalGraphemes = 0;
        for (KrcParser.LyricWord w : words) totalGraphemes += w.text.replace(" ", "").trim().length();
        float densityScale = totalGraphemes > 40 ? 0.8f : 1f;

        float density = getResources().getDisplayMetrics().density;
        float colGapPx = COLUMN_GAP_DP * density;

        // 每列可容行数：可用高(65%)/100px（行高 100px 概念）
        int rowsPerColumn = Math.max(1, (int) Math.floor(layoutH * 0.65f / 100f));

        // ===== 行块切分（不均匀；总量 = min(units, 一列容量×列数上限)） =====
        seedRandom.setSeed(line.startTime);
        int targetRowCount = Math.min(unitsCount, rowsPerColumn * MAX_COLUMNS);
        List<List<KrcParser.LyricWord>> chunkWordLists = new ArrayList<>();
        if (targetRowCount <= 1) {
            List<KrcParser.LyricWord> all = new ArrayList<>();
            for (List<KrcParser.LyricWord> u : unitWords) all.addAll(u);
            chunkWordLists.add(all);
        } else {
            int remainingUnits = unitsCount;
            int remainingChunks = targetRowCount;
            int unitIndex = 0;
            for (int c = 0; c < targetRowCount; c++) {
                boolean isLast = c == targetRowCount - 1;
                int chunkLen;
                if (isLast) {
                    chunkLen = remainingUnits;
                } else {
                    float avg = remainingUnits / (float) remainingChunks;
                    int max = (int) Math.ceil(avg * 1.5f);
                    float jitter = (seedRandom.nextFloat() - 0.5f) * avg;
                    chunkLen = Math.max(1, Math.min(max, Math.round(avg + jitter)));
                }
                chunkLen = Math.max(1, Math.min(chunkLen, remainingUnits - (remainingChunks - 1)));
                List<KrcParser.LyricWord> cw = new ArrayList<>();
                for (int u = 0; u < chunkLen && unitIndex < unitWords.size(); u++) {
                    cw.addAll(unitWords.get(unitIndex++));
                }
                if (!cw.isEmpty()) chunkWordLists.add(cw);
                remainingUnits -= chunkLen;
                remainingChunks--;
            }
        }

        // ===== Chunk 结构（stagger/scale/时间窗/passedRotate） =====
        List<Chunk> allChunks = new ArrayList<>();
        for (int r = 0; r < chunkWordLists.size(); r++) {
            List<KrcParser.LyricWord> cw = chunkWordLists.get(r);
            Chunk ch = new Chunk();
            ch.words = cw;
            boolean staggerLeft = r % 2 == 0;
            float magnitude = STAGGER_MIN + seedRandom.nextFloat() * (STAGGER_MAX - STAGGER_MIN);
            ch.x = (staggerLeft ? -1 : 1) * magnitude;   // folia 原值（不收敛）
            ch.y = (r - (chunkWordLists.size() - 1) / 2f) * 2.5f * density;
            ch.rowIndex = r;
            ch.scale = (0.8f + seedRandom.nextFloat() * 0.9f) * densityScale; // folia 0.8~1.7
            ch.passedRotate = (r % 2 == 0 ? 1 : -1) * PASSED_ROTATE_DEG;
            ch.chunkStart = cw.get(0).startTime;
            KrcParser.LyricWord last = cw.get(cw.size() - 1);
            ch.chunkEnd = last.startTime + last.duration;
            ch.state = 0; ch.stateChangeMs = 0; ch.enterMs = -1L; ch.passedMs = -1L;
            allChunks.add(ch);
        }

        // ===== 分列（行数超一列容量 → 拆列；列从右往左排，整体居中） =====
        List<Column> cols = new ArrayList<>();
        if (allChunks.isEmpty()) {
            cols.add(new Column());
        } else {
            int chunkIdx = 0;
            while (chunkIdx < allChunks.size()) {
                Column col = new Column();
                int count = 0;
                float maxW = COLUMN_MIN_WIDTH_DP * density;
                while (count < rowsPerColumn && chunkIdx < allChunks.size()) {
                    Chunk ch = allChunks.get(chunkIdx);
                    col.chunks.add(ch);
                    count++;
                    chunkIdx++;
                    float w = measureChunkWidth(ch);
                    if (w > maxW) maxW = w;
                }
                col.width = maxW;
                cols.add(col);
            }
        }
        Column[] out = new Column[cols.size()];
        float totalW = 0f;
        for (Column c : cols) totalW += c.width;
        totalW += colGapPx * Math.max(0, cols.size() - 1);
        float cursor = layoutW / 2f + totalW / 2f; // 从右端开始向左排（folia flex-row-reverse）
        for (int i = 0; i < cols.size(); i++) {
            Column c = cols.get(i);
            cursor -= c.width;
            c.offsetX = cursor + c.width / 2f;
            cursor -= colGapPx;
            out[i] = c;
        }

        layoutCache.put(line.startTime, out);
        if (layoutCache.size() > CACHE_LIMIT) {
            Long firstKey = null;
            for (Long k : layoutCache.keySet()) { firstKey = k; break; }
            if (firstKey != null) layoutCache.remove(firstKey);
        }
        return out;
    }

    /**
     * 语义单元：连续 CJK 词时间相邻贪心粘合成 ≤5 字词组（组内保留逐词时间）
     * + 标点开头的词向前粘滞。零依赖近似 folia 语义分词——KRC 词级数据本身带半语义性
     * （中文 KRC 多为 2~4 字词，同时间段相邻词即语义词组的主要来源）。
     */
    private static List<List<KrcParser.LyricWord>> semanticUnits(List<KrcParser.LyricWord> words) {
        List<List<KrcParser.LyricWord>> units = new ArrayList<>();
        for (KrcParser.LyricWord w : words) {
            // 标点向前粘滞
            if (!units.isEmpty() && isPunctHead(w.text)) {
                units.get(units.size() - 1).add(w);
                continue;
            }
            // CJK 时间相邻粘合：前组全 CJK、本词 CJK、间隔 <200ms、组合 ≤5 字
            if (!units.isEmpty()) {
                List<KrcParser.LyricWord> prev = units.get(units.size() - 1);
                if (isAllCjkText(prev) && isCjkText(w.text)) {
                    KrcParser.LyricWord lastW = prev.get(prev.size() - 1);
                    long gap = w.startTime - (lastW.startTime + lastW.duration);
                    int len = wordListLen(prev) + w.text.trim().length();
                    if (gap >= -30 && gap < 200 && len <= 5) {
                        prev.add(w);
                        continue;
                    }
                }
            }
            List<KrcParser.LyricWord> l = new ArrayList<>();
            l.add(w);
            units.add(l);
        }
        return units;
    }

    private static int wordListLen(List<KrcParser.LyricWord> list) {
        int n = 0;
        for (KrcParser.LyricWord w : list) n += w.text.trim().length();
        return n;
    }

    private static boolean isAllCjkText(List<KrcParser.LyricWord> list) {
        if (list.isEmpty()) return false;
        for (KrcParser.LyricWord w : list) if (!isCjkText(w.text)) return false;
        return true;
    }

    private static boolean isCjkText(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FAF) || (c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF)) {
                any = true;
            } else if (c != ' ' && c != '\u3000') {
                return false; // 含非 CJK 非空白（西文/数字）→ 不粘
            }
        }
        return any;
    }

    private static boolean isPunctHead(String text) {
        if (text == null || text.isEmpty()) return false;
        char c = text.charAt(0);
        return "，。、；！？…·,.!?".indexOf(c) >= 0;
    }

    // ===== 绘制 =====

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w; layoutH = h;
        textSizeCurrent = Math.max(16f, w * (w > h ? 0.036f : 0.062f));
        layoutCache.clear();
        if (targetIndex >= 0 && targetIndex < lines.size()) columns = buildLayout(targetIndex);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layoutW <= 0) return;
        if (targetIndex < 0 || targetIndex >= lines.size() || columns.length == 0) return;

        long now = System.currentTimeMillis();
        float density = getResources().getDisplayMetrics().density;

        // 容器呼吸浮动（±14px/7s，同流光 normal 档）
        float phase = (now % 7000L) / 7000f;
        float breathY = (float) Math.sin(phase * Math.PI * 2) * 14f * density;

        // 超宽保护：总列宽超屏 → 整体缩放收敛（多列窄屏兜底，folia 有 0.8 密度但有屏宽约束）
        float totalW = 0f;
        for (Column c : columns) totalW += c.width;
        float colFit = Math.min(1f, (layoutW * 0.92f) / Math.max(totalW, 1f));

        for (int ci = 0; ci < columns.length; ci++) {
            Column col = columns[ci];
            int chunkCount = col.chunks.size();
            if (chunkCount == 0) continue;

            // 列内行距：可用高/(列内块数)（最少 4 行空间）
            float rowH = layoutH * 0.65f / Math.max(chunkCount, 1);
            float blockH = chunkCount * rowH;
            float topCenter = layoutH * 0.46f - blockH / 2f + rowH / 2f;

            for (int r = 0; r < chunkCount; r++) {
                Chunk ch = col.chunks.get(r);
                if (ch.words.isEmpty()) continue;

                // 块三态
                int st = positionMs < ch.chunkStart - LOOKAHEAD_MS ? 0
                        : positionMs <= Math.max(ch.chunkEnd, ch.chunkStart + 300L) ? 1 : 2;
                if (st != ch.state) {
                    ch.state = st;
                    ch.stateChangeMs = now;
                    if (st == 1 && ch.enterMs < 0) ch.enterMs = now;
                    if (st == 2 && ch.passedMs < 0) ch.passedMs = now;
                }
                long el = now - ch.stateChangeMs;

                float alpha, scale, xOff, rotate = 0f;
                if (st == 0) {
                    alpha = 0f; scale = 0.85f;
                    boolean guideLeft = ch.rowIndex % 2 == 0;
                    xOff = ch.x + (guideLeft ? -40f : 40f);
                } else if (st == 1) {
                    if (ch.enterMs < 0) ch.enterMs = now;
                    float spring = springProgress(now - ch.enterMs);
                    alpha = Math.min(1f, (now - ch.enterMs) / 100f);
                    scale = 0.85f + 0.15f * Math.min(1f, spring);
                    boolean guideLeft = ch.rowIndex % 2 == 0;
                    float fromX = ch.x + (guideLeft ? -40f : 40f);
                    xOff = fromX + (ch.x - fromX) * Math.min(1f, spring);
                } else {
                    float t = Math.min(1f, el / 400f);
                    float e = 1f - (1f - t) * (1f - t);
                    alpha = 1f - 0.2f * e;
                    scale = ch.scale;
                    xOff = ch.x;
                    // passedRotate：5s 线性缓转（folia passed ±3° 交替）
                    if (ch.passedMs > 0) {
                        rotate = ch.passedRotate * Math.min(1f, (now - ch.passedMs) / (float) PASSED_ROTATE_MS);
                    }
                }
                if (alpha <= 0.01f) continue;

                float colX = layoutW / 2f + (col.offsetX - layoutW / 2f) * colFit;
                float cy = topCenter + r * rowH + ch.y + breathY;

                // L 形阶梯引导线（左/右角交替）
                if (SHOW_GUIDE_LINES) {
                    drawGuideLine(canvas, ch, colX + xOff * colFit, cy, ch.rowIndex, st, el, alpha, density, colFit);
                }

                drawChunkWords(canvas, ch, colX + xOff * colFit, cy, alpha, rotate, colFit, now);
            }
        }
        postInvalidateOnAnimation();
    }

    private void drawGuideLine(Canvas canvas, Chunk ch, float cx, float cy, int rowIndex,
                               int st, long el, float alpha, float density, float colFit) {
        float textW = measureChunkWidth(ch);
        float growT = st == 1 ? Math.min(1f, el / 400f) : 1f;
        float e = 1f - (1f - growT) * (1f - growT);
        boolean guideLeft = rowIndex % 2 == 0;

        float halfW = textW * colFit / 2f;
        float bottom = cy + textSizeCurrent * ch.scale * colFit * 0.62f;

        // active 线色 = 块内最长词的染色（关键词色个性化，同 folia getActiveColor 语义）；
        // passed 线 = 0.16 alpha 纯白残痕 + 1dp 细线（比 folia 0.22 更收敛，黑底上像铅笔痕）
        int activeColor = chunkActiveColor(ch);
        float lineWidth = st == 1 ? 1.2f * density : 1f * density;
        int lineColor = st == 1
                ? (int) (alpha * 255) << 24 | (activeColor & 0xFFFFFF)
                : (int) (alpha * 41) << 24 | 0xFFFFFF;

        guidePaint.setStrokeWidth(lineWidth);
        guidePaint.setShader(null);
        if (st == 1) {
            guidePaint.setColor(lineColor);
            guidePaint.setShadowLayer(10f * density, 0, 0,
                    (int) (alpha * 69) << 24 | (activeColor & 0xFFFFFF));
        } else {
            guidePaint.setColor(lineColor);
            guidePaint.setShadowLayer(0, 0, 0, 0);
        }

        float densityPx = density;
        // 竖线（32dp 向上长出）
        float vLen = 32f * densityPx * e;
        float vx = guideLeft ? cx - halfW - 8f * densityPx : cx + halfW + 8f * densityPx;
        canvas.drawLine(vx, bottom, vx, bottom - vLen, guidePaint);

        // 横线（块宽+36dp，锚点端亮 → 远端渐隐）：active 用 LinearGradient 流 energy 渐隐，
        // passed 纯色残痕（渐隐也压一档）
        float hLen = (textW * colFit + 36f * densityPx) * e;
        float hy = bottom - 8f * densityPx;
        if (st == 1) {
            float hx0 = guideLeft ? vx : vx - hLen;
            float from = guideLeft ? vx : vx - hLen;
            float to = guideLeft ? vx + hLen : vx;
            guidePaint.setShader(new android.graphics.LinearGradient(from, hy, to, hy,
                    lineColor, ((int) (alpha * 31) << 24) | (activeColor & 0xFFFFFF),
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawLine(hx0, hy, hx0 + hLen, hy, guidePaint);
            guidePaint.setShader(null);
        } else {
            float hx0 = guideLeft ? vx : vx - hLen;
            float from = guideLeft ? vx : vx - hLen;
            float to = guideLeft ? vx + hLen : vx;
            guidePaint.setShader(new android.graphics.LinearGradient(from, hy, to, hy,
                    lineColor, 0x29FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawLine(hx0, hy, hx0 + hLen, hy, guidePaint);
            guidePaint.setShader(null);
        }
        guidePaint.setShadowLayer(0, 0, 0, 0);
    }

    /** 块 active 染色：块内最长 CJK 词（关键词近似）用高亮色，退回全局 accent */
    private int chunkActiveColor(Chunk ch) {
        KrcParser.LyricWord longest = null;
        for (KrcParser.LyricWord w : ch.words) {
            if (w.text == null || w.text.trim().isEmpty()) continue;
            if (longest == null
                    || w.text.trim().length() > longest.text.trim().length()) {
                longest = w;
            }
        }
        return colorAccent; // 词染色的钩子位：后续接 wordColoring 词色表时在此分流
    }

    /** 块内词：流光同款三态（弹簧弹入+字素双光晕+passed 余晖），逐词推进 */
    private void drawChunkWords(Canvas canvas, Chunk ch, float centerX, float cy,
                                float containerAlpha, float rotate, float colFit, long now) {
        float size = textSizeCurrent * ch.scale;
        textPaint.setTextSize(size);
        float total = 0f;
        float[] widths = new float[ch.words.size()];
        for (int i = 0; i < ch.words.size(); i++) {
            widths[i] = textPaint.measureText(ch.words.get(i).text);
            total += widths[i];
        }
        float x = centerX - total * colFit / 2f;
        float baselineOffset = baselineFromCenter(size);

        for (int i = 0; i < ch.words.size(); i++) {
            KrcParser.LyricWord w = ch.words.get(i);
            long wEnd = w.startTime + Math.max(w.duration, 1L);
            int st = positionMs < w.startTime - LOOKAHEAD_MS ? 0
                    : positionMs <= Math.max(wEnd, w.startTime + 100L) ? 1 : 2;
            if (st == 0) { x += widths[i] * colFit; continue; }

            float alpha, scale;
            int color;
            if (st == 1) {
                float sp = springProgressMs(positionMs - (w.startTime - LOOKAHEAD_MS));
                scale = 0.5f + 0.9f * Math.min(1f, sp);
                alpha = Math.min(1f, Math.max(0.2f, sp * 2f));
                float dur = Math.max(wEnd - w.startTime, 100L);
                color = lerpColor(colorPrimary, colorAccent,
                        Math.min(1f, (positionMs - w.startTime) / dur));
            } else {
                scale = 1.16f;
                alpha = 0.82f;
                color = colorPrimary;
            }
            int a = (int) (Math.min(1f, alpha * containerAlpha) * 255);
            if (a <= 2) { x += widths[i] * colFit; continue; }

            float wCenterX = x + widths[i] * colFit / 2f;

            // 字素双光晕（active 词：内 0.7α/外 α 逐字正弦点亮）
            if (st == 1 && w.text.length() > 0) {
                float rInner = size * 0.35f, rOuter = size * 0.6f;
                long wDur = Math.max(wEnd - w.startTime, 1L);
                float wx = x;
                for (int gi = 0; gi < w.text.length(); gi++) {
                    String gc = String.valueOf(w.text.charAt(gi));
                    float gw = textPaint.measureText(gc) * colFit;
                    long gs = w.startTime + wDur * gi / w.text.length();
                    long ge = w.startTime + wDur * (gi + 1) / w.text.length();
                    float tt = positionMs <= gs ? 0f
                            : positionMs >= ge ? 1f : (positionMs - gs) / (float) (ge - gs);
                    float g = (float) Math.sin(tt * Math.PI);
                    if (g > 0.02f) {
                        int ga = (int) (g * a);
                        float gxC = wx + gw / 2f;
                        textPaint.setShadowLayer(rOuter, 0, 0, (ga << 24) | (color & 0xFFFFFF));
                        textPaint.setColor(0x00000000);
                        canvas.drawText(gc, gxC, cy + baselineOffset, textPaint);
                        textPaint.setShadowLayer(rInner, 0, 0,
                                ((int) (ga * 0.7f) << 24) | (color & 0xFFFFFF));
                        canvas.drawText(gc, gxC, cy + baselineOffset, textPaint);
                    }
                    wx += gw;
                }
            }

            // 正文（passed 块带 passedRotate 缓转）
            textPaint.setShadowLayer(0, 0, 0, 0);
            textPaint.setColor((a << 24) | (color & 0xFFFFFF));
            int sc = canvas.save();
            if (rotate != 0f) canvas.rotate(rotate, wCenterX, cy);
            canvas.scale(scale, scale, wCenterX, cy);
            canvas.drawText(w.text, wCenterX, cy + baselineOffset, textPaint);
            canvas.restoreToCount(sc);

            x += widths[i] * colFit;
        }
    }

    private float measureChunkWidth(Chunk ch) {
        float size = textSizeCurrent * ch.scale;
        textPaint.setTextSize(size);
        float total = 0f;
        for (KrcParser.LyricWord w : ch.words) total += textPaint.measureText(w.text);
        return total;
    }

    private float baselineFromCenter(float size) {
        textPaint.setTextSize(size);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        return -(fm.ascent + fm.descent) * 0.5f - fm.descent;
    }

    /** 欠阻尼弹簧（ζ0.55/ω16，与流光同手感） */
    private static float springProgressMs(long elapsedMs) {
        float t = Math.max(0, elapsedMs) / 1000f;
        if (t <= 0f) return 0f;
        if (t >= 0.6f) return 1f;
        double zeta = 0.55, omega = 16.0, wd = omega * Math.sqrt(1 - zeta * zeta);
        double v = 1 - Math.exp(-zeta * omega * t)
                * (Math.cos(wd * t) + (zeta * omega / wd) * Math.sin(wd * t));
        return (float) Math.max(0, Math.min(1.15, v));
    }

    private static float springProgress(long elapsedMs) { return springProgressMs(elapsedMs); }

    private static int lerpColor(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        return (0xFF << 24)
                | ((fr + (int) ((tr - fr) * t)) << 16)
                | ((fg + (int) ((tg - fg) * t)) << 8)
                | (fb + (int) ((tb - fb) * t));
    }
}
