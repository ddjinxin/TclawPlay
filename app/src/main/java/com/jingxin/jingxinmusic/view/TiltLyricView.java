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
import java.util.List;

/**
 * 倾诉歌词（第五种歌词动效·移植 folia tilt：斜体独白）
 *
 * 一行歌词按语义拆 1~4 段竖向堆叠居中，随机一段为"倾斜行"（italic 300/字距宽/强调色，
 * 字上下交替波浪错位）；段随演唱逐段揭示（段起始−0.25s 亮），行内逐字递进入场
 * （字序×40/50ms）；演唱字 scale 1→1.15/1.18 正弦脉冲 + 余晖（唱完衰减到 0.25 悬住）。
 *
 * 语义切分 sentenceSplit（SentenceLayout 直译）：
 * Lv1 标点(，。；！？、…·.,;!?) → Lv2 成对符号(「」『』《》【】()[]{}""最外层保护)
 * → Lv3 CJK 内西文多词块 → Lv4 CJK 后空格 → Lv5 特殊符(：://|~)；
 * 不足目标段数 secondarySplit 对半补切（选词间隙最近中点的段），超出则 merge 最短相邻对。
 * 行数 = log(字数+4)/log(24)×jitter(0.7~1.3,行种子)×0.75 分档 <0.45/1.05/1.7 → 1~4 行；
 * 超宽 >1.6× 按宽重切（上限4），>1× 缩字号（floor 倾斜0.5/正体0.55）；
 * 尾段 ≤2 字且 < 前段一半 → 短尾 ×1.18 放大。
 */
public class TiltLyricView extends View {

    // ===== tuning（folia DEFAULT_TILT_TUNING） =====
    private static final float SPLIT_PROBABILITY = 0.75f;
    private static final float TILT_STYLE_PROBABILITY = 0.35f;
    private static final float CHAR_REF_LENGTH = 20f, LOG_OFFSET = 4f;
    private static final float RESPLIT_THRESHOLD = 1.6f;
    private static final float SCALE_FLOOR_NORMAL = 0.55f, SCALE_FLOOR_TILT = 0.5f;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<KrcParser.LyricLine> lines = Collections.emptyList();
    private int targetIndex = -1;
    private int positionMs;
    private int colorPrimary = Color.rgb(0xC9, 0xE4, 0xFF);
    private int colorAccent = Color.rgb(0x8E, 0xE8, 0xD8);

    private int layoutW, layoutH;

    // ===== 段（一行一次准备，行种子确定） =====
    private static final class Segment {
        String text;              // trim 后段文本
        int charOffset;           // 段首在原行的字符偏移（词时间映射用）
        boolean isTilt;           // 倾斜行（italic/波浪/强调色）
        boolean isShortLastLine;  // 短尾放大
        long segStart, segEnd;    // 段起唱/收尾 ms
    }
    private Segment[] segments = new Segment[0];
    private float scaleMultiplier = 1f;
    private long lastLayoutLineStart = Long.MIN_VALUE;

    private long lastFrameMs = -1L;

    public TiltLyricView(Context context) { super(context); init(); }
    public TiltLyricView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public TiltLyricView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ===== 对外接口 =====

    public void setLyricData(KrcParser.LyricData data) {
        this.lines = (data != null && data.lines != null) ? data.lines : Collections.emptyList();
        this.targetIndex = -1;
        this.segments = new Segment[0];
        lastLayoutLineStart = Long.MIN_VALUE;
        invalidate();
    }

    public void updatePosition(int positionMs) {
        this.positionMs = positionMs;
        if (lines.isEmpty()) {
            if (targetIndex != -1) { targetIndex = -1; segments = new Segment[0]; }
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
        postInvalidateOnAnimation();
    }

    public void setColors(int primary, int accent) {
        if (primary != 0) colorPrimary = primary;
        if (accent != 0) colorAccent = accent;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w; layoutH = h;
        lastLayoutLineStart = Long.MIN_VALUE; // 尺寸变化重排
        if (targetIndex >= 0 && targetIndex < lines.size()) prepareLine(targetIndex);
    }

    // ===== 准备：一行 → 段列表 =====

    private void prepareLine(int lineIdx) {
        KrcParser.LyricLine line = lines.get(lineIdx);
        String fullText = line.text != null ? line.text : "";
        if (fullText.isEmpty()) { segments = new Segment[0]; return; }

        long seed = line.startTime;
        boolean ellipsisOnly = fullText.trim().matches("[\\s.…·。]+");

        List<String> merged;
        if (ellipsisOnly) {
            merged = new ArrayList<>();
            merged.add(fullText);
        } else {
            int charCount = fullText.trim().length();
            int numLines = determineLineCount(charCount, seed);
            merged = splitIntoSentences(fullText, numLines, seed);
        }

        // 倾斜行指认：每行掷骰<0.35 入候选，候选中随机一个
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            if (seededRandom(seed, 100 + i) < TILT_STYLE_PROBABILITY) candidates.add(i);
        }
        int tiltIdx = -1;
        if (!candidates.isEmpty()) {
            tiltIdx = candidates.get((int) (seededRandom(seed, 200) * candidates.size()));
        }

        // 段结构 + 超宽检测
        float basePx = Math.max(layoutW * 0.06875f, 90f);
        float available = Math.max(320f, layoutW) * 0.85f;

        segments = buildSegments(merged, tiltIdx, fullText);
        scaleMultiplier = computeScale(segments, basePx, available);

        // 超宽重切（≥1.6×）：按宽估算重切段数（≤4）
        if (scaleMultiplier < 0.99f && 1f / scaleMultiplier >= RESPLIT_THRESHOLD) {
            int extra = (int) Math.min(4, Math.max(segments.length + 1,
                    Math.ceil(measureWidth(fullText, basePx, false) / (available * (RESPLIT_THRESHOLD - 0.15f)))));
            if (extra > segments.length) {
                List<String> reSplit = splitIntoSentences(fullText, Math.min(4, extra), seed);
                int reTilt = -1;
                if (seededRandom(seed, 300) < TILT_STYLE_PROBABILITY) {
                    reTilt = (int) (seededRandom(seed, 301) * reSplit.size());
                }
                segments = buildSegments(reSplit, reTilt, fullText);
                scaleMultiplier = computeScale(segments, basePx, available);
            }
        }

        // 段起唱/收尾时间（词区间映射；纯 LRC 按行时长均分）
        mapSegmentTimings(line);

        // 短尾标记：末段 ≤2 字且 < 前段一半
        if (segments.length >= 2) {
            Segment last = segments[segments.length - 1];
            Segment prev = segments[segments.length - 2];
            if (!last.isTilt && last.text.trim().length() <= 2
                    && last.text.trim().length() * 2 <= prev.text.length()) {
                last.isShortLastLine = true;
            }
        }
        lastLayoutLineStart = line.startTime;
    }

    private Segment[] buildSegments(List<String> merged, int tiltIdx, String fullText) {
        Segment[] out = new Segment[merged.size()];
        int offsetAccum = 0;
        for (int i = 0; i < merged.size(); i++) {
            String raw = merged.get(i);
            int leading = leadingSpaceCount(raw);
            Segment s = new Segment();
            s.text = raw.trim();
            s.charOffset = offsetAccum + leading;
            s.isTilt = i == tiltIdx;
            s.isShortLastLine = false;
            s.segStart = 0; s.segEnd = 0;
            out[i] = s;
            offsetAccum += raw.length();
        }
        return out;
    }

    /** 段起唱/收尾：词区间映射（段内首词 start~尾词 end）；无词按行时长均分 */
    private void mapSegmentTimings(KrcParser.LyricLine line) {
        boolean hasWords = line.words != null && !line.words.isEmpty();
        long lineEnd = line.startTime + Math.max(line.duration, 1L);
        if (!hasWords) {
            // 纯 LRC：按段字符占比分摊行时长
            int total = 0;
            for (Segment s : segments) total += Math.max(s.text.length(), 1);
            long acc = line.startTime;
            for (Segment s : segments) {
                s.segStart = acc;
                acc += (lineEnd - line.startTime) * Math.max(s.text.length(), 1) / Math.max(total, 1);
                s.segEnd = acc;
            }
            return;
        }
        List<KrcParser.LyricWord> words = line.words;
        for (Segment s : segments) {
            int segEnd = s.charOffset + s.text.length();
            long st = -1, en = -1;
            int fullPos = 0;
            for (KrcParser.LyricWord w : words) {
                int ws = fullTextIndexOf(line.text, w.text, fullPos);
                if (ws < 0) { fullPos += w.text.length(); continue; }
                int we = ws + w.text.length();
                if (ws <= s.charOffset && we > s.charOffset) st = w.startTime;
                if (ws < segEnd && we >= segEnd && st >= 0) { en = w.startTime + Math.max(w.duration, 1); break; }
                if (st >= 0 && en < 0) en = w.startTime + Math.max(w.duration, 1);
                fullPos = we;
            }
            if (st < 0) st = line.startTime;
            if (en < 0) en = lineEnd;
            s.segStart = st; s.segEnd = Math.max(en, st + 300);
        }
    }

    /** indexOf 带起点（纯文本词匹配回映） */
    private static int fullTextIndexOf(String text, String word, int from) {
        if (word == null || word.isEmpty()) return -1;
        return text.indexOf(word, from);
    }

    /** 溢出缩放：取最宽正体/倾斜段，各自 floor 0.55/0.5 */
    private float computeScale(Segment[] segs, float basePx, float available) {
        float maxOverflow = 0f;
        boolean overflowIsTilt = false;
        for (Segment s : segs) {
            if (s.text.isEmpty()) continue;
            float w = measureWidth(s.text, basePx, s.isTilt);
            float ov = w / available;
            if (ov > maxOverflow) { maxOverflow = ov; overflowIsTilt = s.isTilt; }
        }
        if (maxOverflow <= 1f) return 1f;
        float floor = overflowIsTilt ? SCALE_FLOOR_TILT : SCALE_FLOOR_NORMAL;
        return Math.max(floor, 1f / maxOverflow);
    }

    private float measureWidth(String text, float px, boolean italic) {
        textPaint.setTextSize(px);
        applyTextStyle(italic, false);
        float w = textPaint.measureText(text) + text.length() * px * (italic ? 0.15f : 0.08f); // letterSpacing
        applyTextStyle(false, false);
        return w;
    }

    /** 行数分档（folia determineLineCount 直译） */
    private static int determineLineCount(int charCount, long seed) {
        float normalized = (float) (Math.log(charCount + LOG_OFFSET) / Math.log(CHAR_REF_LENGTH + LOG_OFFSET));
        float jitter = seededRandom(seed, 1) * 0.6f + 0.7f;
        float score = normalized * jitter * SPLIT_PROBABILITY;
        if (score < 0.45f) return 1;
        if (score < 1.05f) return 2;
        if (score < 1.7f) return 3;
        return 4;
    }

    // ===== 语义切分（SentenceLayout 直译） =====

    static List<String> splitIntoSentences(String text, int targetCount, long seed) {
        if (targetCount <= 1) {
            List<String> one = new ArrayList<>();
            one.add(text);
            return one;
        }
        List<String> sentences = new ArrayList<>();
        sentences.add(text);
        for (int level = 1; level <= 5; level++) {
            if (sentences.size() >= targetCount) break;
            List<String> next = new ArrayList<>();
            for (String s : sentences) next.addAll(splitByLevel(s, level));
            sentences = next;
        }
        if (sentences.size() < targetCount) sentences = secondarySplit(sentences, targetCount, seed);
        if (sentences.size() > targetCount && targetCount > 0) sentences = mergeSentences(sentences, targetCount);
        return sentences;
    }

    private static List<String> splitByLevel(String text, int level) {
        switch (level) {
            case 1: return splitByPunctuation(text);
            case 2: return splitByBracketsQuotes(text);
            case 3: return splitByWesternWords(text);
            case 4: return splitCJKBySpace(text);
            case 5: return splitBySpecialChars(text);
            default: return singleList(text);
        }
    }

    private static List<String> singleList(String t) {
        List<String> l = new ArrayList<>();
        l.add(t);
        return l;
    }

    /** Lv1 标点切（，。；！？、…·.,;!?）
     * 标点附前段尾（含后随空格）；行首标点并入上一段 */
    private static List<String> splitByPunctuation(String text) {
        List<int[]> matches = new ArrayList<>(); // [start, end)
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isPunct(c)) {
                int j = i;
                while (j < text.length() && (isPunct(text.charAt(j)))) j++;
                matches.add(new int[]{i, j});
                i = j;
            } else i++;
        }
        if (matches.isEmpty()) return singleList(text);
        List<String> result = new ArrayList<>();
        int lastIndex = 0;
        for (int[] m : matches) {
            int punctStart = m[0], punctEnd = m[1];
            int trailing = punctEnd;
            while (trailing < text.length() && text.charAt(trailing) == ' ') trailing++;
            String before = text.substring(lastIndex, punctStart);
            String punctWithSpaces = text.substring(punctStart, trailing);
            if (!before.isEmpty()) result.add(before + punctWithSpaces);
            else if (!result.isEmpty()) result.set(result.size() - 1, result.get(result.size() - 1) + punctWithSpaces);
            else result.add(punctWithSpaces);
            lastIndex = trailing;
        }
        if (lastIndex < text.length()) result.add(text.substring(lastIndex));
        List<String> out = new ArrayList<>();
        for (String r : result) if (!r.isEmpty()) out.add(r);
        return out;
    }

    private static boolean isPunct(char c) {
        return ",.;!?、，。；！？…·".indexOf(c) >= 0;
    }

    /** Lv2 成对符号最外层提取（「」『』《》【】｛｝［］[]（）() 引号） */
    private static List<String> splitByBracketsQuotes(String text) {
        char[][] pairs = {
                {'「', '」'}, {'『', '』'}, {'《', '》'}, {'【', '】'},
                {'｛', '｝'}, {'［', '］'}, {'[', ']'}, {'（', '）'}, {'(', ')'},
        };
        List<String> result = extractOutermost(text, pairs);
        return result.size() > 1 ? result : singleList(text);
    }

    private static List<String> extractOutermost(String text, char[][] pairs) {
        int[] best = findOutermostPair(text, pairs);
        if (best == null) return singleList(text);
        List<String> result = new ArrayList<>();
        String before = text.substring(0, best[0]);
        String paired = text.substring(best[0], best[3]);
        String remainder = text.substring(best[3]);
        if (!before.isEmpty()) result.addAll(extractOutermost(before, pairs));
        result.add(paired);
        if (!remainder.isEmpty()) result.addAll(extractOutermost(remainder, pairs));
        List<String> out = new ArrayList<>();
        for (String r : result) if (!r.isEmpty()) out.add(r);
        return out;
    }

    /** [openStart, openEnd, closeStart, closeEnd] 或 null */
    private static int[] findOutermostPair(String text, char[][] pairs) {
        int[] best = null;
        for (char[] p : pairs) {
            int os = text.indexOf(p[0]);
            if (os < 0) continue;
            int oe = os + 1;
            int cs = text.indexOf(p[1], oe);
            if (cs < 0) continue;
            int ce = cs + 1;
            if (best == null || os < best[0] || (os == best[0] && ce > best[3])) {
                best = new int[]{os, oe, cs, ce};
            }
        }
        return best;
    }

    /** Lv3 CJK 文本内的西文多词块切分（边界保护：- 后/多次出现的 : / | 后不切） */
    private static List<String> splitByWesternWords(String text) {
        if (!hasCjk(text)) return singleList(text);
        List<int[]> blocks = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isWestern(c)) {
                int j = i; int words = 1;
                while (j < text.length() && (isWestern(text.charAt(j)) || text.charAt(j) == '\''
                        || text.charAt(j) == '-' || text.charAt(j) == ' ')) {
                    if (text.charAt(j) == ' ' && j + 1 < text.length() && isWestern(text.charAt(j + 1))) words++;
                    j++;
                }
                if (words > 1) blocks.add(new int[]{i, j});
                i = j;
            } else i++;
        }
        if (blocks.isEmpty()) return singleList(text);
        List<String> parts = new ArrayList<>();
        int lastIndex = 0;
        for (int[] b : blocks) {
            String before = text.substring(lastIndex, b[0]);
            if (!before.isEmpty() && shouldSkipBoundary(before)) {
                parts.add(before + text.substring(b[0], b[1]));
            } else if (!before.isEmpty()) {
                parts.add(before);
                parts.add(text.substring(b[0], b[1]));
            } else {
                parts.add(text.substring(b[0], b[1]));
            }
            lastIndex = b[1];
        }
        if (lastIndex < text.length()) parts.add(text.substring(lastIndex));
        return parts.size() > 1 ? parts : singleList(text);
    }

    private static boolean shouldSkipBoundary(String before) {
        if (before.isEmpty()) return false;
        char sep = before.charAt(before.length() - 1);
        if (sep == '-') return true;
        return false;
    }

    private static boolean hasCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FAF) || (c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF)) return true;
        }
        return false;
    }

    private static boolean isWestern(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** Lv4 CJK 后空格切（全角空格总是切；半角 CJK 后切） */
    private static List<String> splitCJKBySpace(String text) {
        if (!hasCjk(text)) return singleList(text);
        List<String> segments = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inCjkBlock = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean cjk = (c >= 0x4E00 && c <= 0x9FAF) || (c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF);
            boolean western = isWestern(c);
            boolean isSpace = c == ' ' || c == '\u3000';
            if (cjk) { cur.append(c); inCjkBlock = true; }
            else if (western) cur.append(c);
            else if (isSpace) {
                if (c == '\u3000' && cur.length() > 0) {
                    cur.append(c);
                    segments.add(cur.toString());
                    cur.setLength(0);
                    inCjkBlock = false;
                } else if (inCjkBlock && cur.length() > 0) {
                    char last = cur.charAt(cur.length() - 1);
                    if (last >= 0x4E00 && last <= 0x9FAF) {
                        cur.append(c);
                        segments.add(cur.toString());
                        cur.setLength(0);
                        inCjkBlock = false;
                    } else cur.append(c);
                } else if (cur.length() == 0 && !segments.isEmpty()) {
                    segments.set(segments.size() - 1, segments.get(segments.size() - 1) + String.valueOf(c));
                } else cur.append(c);
            } else {
                if (cur.length() == 0 && !segments.isEmpty()) {
                    segments.set(segments.size() - 1, segments.get(segments.size() - 1) + String.valueOf(c));
                } else cur.append(c);
            }
        }
        if (cur.length() > 0) {
            if (!segments.isEmpty() && cur.toString().trim().isEmpty()) {
                segments.set(segments.size() - 1, segments.get(segments.size() - 1) + cur.toString());
            } else segments.add(cur.toString());
        }
        return segments.size() > 1 ? segments : singleList(text);
    }

    /** Lv5 特殊符切（：://|~），符号附前段 */
    private static List<String> splitBySpecialChars(String text) {
        List<String> parts = new ArrayList<>();
        int last = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (":/\\|~～：／｜".indexOf(c) >= 0) {
                if (i > last) parts.add(text.substring(last, i));
                last = i + 1;
            }
        }
        if (last < text.length()) parts.add(text.substring(last));
        if (parts.size() <= 1) return singleList(text);
        // 符号附前段
        List<String> out = new ArrayList<>();
        int pos = 0;
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            int idx = text.indexOf(p, pos);
            if (idx > pos) {
                String sep = text.substring(pos, idx);
                if (!out.isEmpty()) out.set(out.size() - 1, out.get(out.size() - 1) + sep);
                else out.add(sep);
            }
            if (!p.isEmpty()) { out.add(p); pos = idx + p.length(); }
            else if (idx >= 0) pos = idx;
        }
        if (pos < text.length() && !out.isEmpty()) {
            out.set(out.size() - 1, out.get(out.size() - 1) + text.substring(pos));
        }
        List<String> filtered = new ArrayList<>();
        for (String r : out) if (!r.isEmpty()) filtered.add(r);
        return filtered.size() > 1 ? filtered : singleList(text);
    }

    /** 补切：不足目标段数，对最长段对半切（选超2字段的伪随机轮转） */
    private static List<String> secondarySplit(List<String> sentences, int targetCount, long seed) {
        List<String> list = new ArrayList<>(sentences);
        while (list.size() < targetCount) {
            List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) if (list.get(i).length() > 2) candidates.add(i);
            if (candidates.isEmpty()) break;
            int textHash = 0;
            for (String s : list) for (int i = 0; i < s.length(); i++) textHash += s.charAt(i);
            int pick = (int) (pseudoRandom(textHash + list.size() + seed) * candidates.size());
            int idx = candidates.get(pick);
            String s = list.get(idx);
            int mid = s.length() / 2;
            list.remove(idx);
            list.add(idx, s.substring(0, mid));
            list.add(idx + 1, s.substring(mid));
        }
        return list;
    }

    /** 合并：超出目标段数，反复合并最短相邻对 */
    private static List<String> mergeSentences(List<String> sentences, int targetCount) {
        List<String> list = new ArrayList<>(sentences);
        while (list.size() > targetCount) {
            int best = 0;
            int shortest = Integer.MAX_VALUE;
            for (int i = 0; i < list.size() - 1; i++) {
                int combined = list.get(i).length() + list.get(i + 1).length();
                if (combined < shortest) { shortest = combined; best = i; }
            }
            list.set(best, list.get(best) + list.get(best + 1));
            list.remove(best + 1);
        }
        return list;
    }

    private static float seededRandom(long seed, int offset) {
        double x = Math.sin(seed * 1000d + offset) * 10000d;
        return (float) (x - Math.floor(x));
    }

    private static float pseudoRandom(double seed) {
        double x = Math.sin(seed) * 10000d;
        return (float) (x - Math.floor(x));
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layoutW <= 0) return;
        if (targetIndex < 0 || targetIndex >= lines.size() || segments.length == 0) return;

        float basePx = Math.max(layoutW * 0.06875f, 90f);
        float gapY = basePx * 0.3f;

        // 总高：段数×行高+段距，垂直居中（页心 45%）
        float lineHeight = basePx * 1.35f * scaleMultiplier;
        float blockH = segments.length * lineHeight + (segments.length - 1) * gapY;
        float topY = layoutH * 0.45f - blockH / 2f + lineHeight / 2f;

        long lineStart = lines.get(targetIndex).startTime;
        long lineEnd = lineStart + Math.max(lines.get(targetIndex).duration, 300L);

        for (int si = 0; si < segments.length; si++) {
            Segment s = segments[si];
            if (s.text.isEmpty()) continue;

            // 段可见性：t ≥ 段起唱−250ms 亮，行外灭
            boolean visible = positionMs >= s.segStart - 250L && positionMs <= lineEnd;
            float cy = topY + si * (lineHeight + gapY);
            drawSegmentLine(canvas, s, visible, cy, basePx);
        }
        postInvalidateOnAnimation();
    }

    /** 一段一行：整行 reveal（淡入+上浮/倾斜行缩放入场），行内逐字递进 + 演唱脉冲余晖 */
    private void drawSegmentLine(Canvas canvas, Segment s, boolean visible, float cy, float basePx) {
        float shortBoost = s.isShortLastLine ? 1.18f : 1f;
        float fontPx = basePx * scaleMultiplier * (s.isTilt ? 1f : shortBoost);
        textPaint.setTextSize(fontPx);
        applyTextStyle(s.isTilt, true);

        paintCharLoop(canvas, s, visible, cy, fontPx);

        applyTextStyle(false, false);
    }

    /** 逐字绘制：入场 reveal（每字延迟 40/50ms）+ 波浪错位（倾斜行）+ 演唱脉冲+余晖 */
    private void paintCharLoop(Canvas canvas, Segment s, boolean visible, float cy, float fontPx) {
        // 段入场动画参数
        long now = System.currentTimeMillis();
        if (lastFrameMs < 0) lastFrameMs = now;
        float dt = Math.min(0.064f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        float revealT = clamp01((positionMs - (s.segStart - 250L)) / 550f); // 行 reveal 550ms

        // 波浪幅度（倾斜行）
        float yOffset = fontPx / 6f;
        float lineW = 0f;
        float[] charW = new float[s.text.length()];
        for (int i = 0; i < s.text.length(); i++) {
            charW[i] = textPaint.measureText(String.valueOf(s.text.charAt(i)));
            lineW += charW[i];
        }
        float x = -lineW / 2f + layoutW / 2f;

        // 字时间（词内均分；无词按段时长均分）
        long segDur = Math.max(s.segEnd - s.segStart, 300L);
        java.util.List<KrcParser.LyricWord> words = null;
        KrcParser.LyricLine line = lines.get(targetIndex);
        if (line.words != null && !line.words.isEmpty()) words = line.words;

        int visualIdx = 0;
        for (int i = 0; i < s.text.length(); i++) {
            char ch = s.text.charAt(i);
            boolean isSpace = ch == ' ' || ch == '\u3000';
            if (isSpace) { x += charW[i]; continue; }
            int ci = visualIdx++;

            // 逐字入场：行内序号×延迟
            float delay = s.isTilt ? 0.05f : 0.04f;
            float charAppear = clamp01((revealT * 0.55f - ci * delay) / 0.32f);
            if (charAppear <= 0f || !visible) { x += charW[i]; continue; }

            // 演唱时间窗（词内均分近似：定位段内全局词，再按字符索引均分词时长）
            float charStart, charEnd;
            if (words != null) {
                float[] ct = charTimeByWords(s, ci, words);
                charStart = ct[0]; charEnd = ct[1];
            } else {
                long segChars = s.text.trim().length();
                charStart = (s.segStart - line.startTime) + segDur * (ci / Math.max(segChars, 1));
                charEnd = charStart + segDur / Math.max(segChars, 1);
            }

            // 逐字脉冲+余晖（folia getCharPulseIntensity 直译，秒制）
            float intensity = charPulseIntensity(positionMs - line.startTime, charStart, charEnd);

            // 缩放：1 + intensity×(倾斜0.18/正体0.15)，带 60ms 平滑感（近似直接应用）
            float scale = 1f + intensity * (s.isTilt ? 0.18f : 0.15f);

            // 波浪错位（倾斜行：偶-奇交替 ±yOffset；入场时从 2 倍落位）
            float yOff = 0f;
            if (s.isTilt) {
                float stagger = ci % 2 == 0 ? -1f : 1f;
                yOff = stagger * yOffset * (1.2f - 0.2f * charAppear);
            }

            // 入场上浮：正体 y+20→0 / 倾斜 y+24→0（随 revealT）
            float enterRise = s.isTilt ? 24f : 20f;
            float y = cy + yOff + enterRise * (1f - revealT);

            int alpha = (int) (charAppear * 255);
            int color = s.isTilt ? colorAccent : colorPrimary;
            textPaint.setColor((alpha << 24) | (color & 0xFFFFFF));

            int scSave = canvas.save();
            canvas.scale(scale, scale, x + charW[i] / 2f, y);
            canvas.drawText(String.valueOf(ch), x + charW[i] / 2f, y, textPaint);
            canvas.restoreToCount(scSave);

            x += charW[i];
        }
    }

    /** 字时间：段内词匹配（词在段内→词内均分；跨不到则段均分兜底） */
    private float[] charTimeByWords(Segment s, int ci, List<KrcParser.LyricWord> words) {
        KrcParser.LyricLine line = lines.get(targetIndex);
        // 词首字符全局索引 → 段内字符索引
        int segCharBase = countNonSpaceBefore(line.text, s.charOffset);
        int ciGlobal = segCharBase + ci;
        int gi = 0;
        for (KrcParser.LyricWord w : words) {
            int wLen = countNonSpace(w.text);
            if (ciGlobal < gi + wLen) {
                // 在此词内
                int local = ciGlobal - gi;
                float dur = Math.max(w.duration, 1);
                float st = w.startTime - line.startTime;
                return new float[]{st + dur * local / wLen, st + dur * (local + 1) / wLen};
            }
            gi += wLen;
        }
        // 兜底：段均分
        long segDur = Math.max(s.segEnd - s.segStart, 300L);
        int segChars = s.text.trim().length();
        float st = (s.segStart - line.startTime) + segDur * (ci / Math.max(segChars, 1));
        return new float[]{st, st + segDur / Math.max(segChars, 1)};
    }

    /** 全局文本 offset 前的非空格字符数 */
    private static int countNonSpaceBefore(String text, int offset) {
        int n = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\u3000') n++;
        }
        return n;
    }

    private static int countNonSpace(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\u3000') n++;
        }
        return n;
    }

    /** 逐字脉冲+余晖（folia getCharPulseIntensity 直译；ms 制换算秒逻辑） */
    private static float charPulseIntensity(long elapsedMs, float charStartMs, float charEndMs) {
        float duration = Math.min(Math.max(charEndMs - charStartMs, 200f), 900f);
        float elapsed = elapsedMs - charStartMs;
        if (elapsed < 0) return 0f;
        if (elapsed <= duration) {
            return (float) Math.sin(clamp01(elapsed / duration) * Math.PI);
        }
        float after = elapsed - duration;
        float ramp = duration * 1.2f;
        if (after >= ramp) return 0.25f;
        return 0.25f * (after / ramp);
    }

    /** 排版风格：倾斜行 italic(真斜体 skew)+宽字距；正体行常规+中字距 */
    private void applyTextStyle(boolean italic, boolean forDraw) {
        textPaint.setTextSkewX(italic ? -0.25f : 0f);
        textPaint.setFakeBoldText(!italic); // 正体 400 / 倾斜 300 视觉近似
    }

    private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** 手写 trimStart 前导空格计数（minSdk 21 无 String.trimStart） */
    private static int leadingSpaceCount(String s) {
        int n = 0;
        while (n < s.length() && (s.charAt(n) == ' ' || s.charAt(n) == '\u3000')) n++;
        return n;
    }
}
