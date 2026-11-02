package com.jingxin.jingxinmusic.view.collage;

/**
 * 织块墙几何引擎（自研，纯几何无 UI）
 *
 * 结构：
 * - 平面以 10×10 单元格「织块」（region）无限平铺；每个织块由确定性递归二分
 *   （guillotine 尺切）恰好切成 11 张不小于 2×2 的卡：切分方向、左右叶数、
 *   切线位置全部由整数哈希驱动——任意织块坐标可独立复现同一图案，无需模板表
 * - 队列映射：以 tileW×tileH 个织块组成的「超级块」为周期包裹整个队列，
 *   槽位对队列长度取模环绕（尾部环绕重复），同一首歌在平面上周期多处出现
 * - 展开让位：被点卡取 6×6 锚位（由原卡位置就近夹取），锚外余区剥离成至多
 *   4 条矩形带，各带按叶数预算再递归细分，恰好铺满、总叶数恰 11——运行时
 *   求解，无离线让位表
 * - 可行性由静态 DP 表保证：FEASIBLE[w][h][k] = w×h 矩形能否切出 k 张合法卡；
 *   切分只在可行解空间内取值，任何哈希输入下无死路
 *
 * 性能：织块模式按超级块内坐标惰性缓存，滚动拾取 O(1)；成本与队列长度无关。
 */
public final class CollageLayout {

    /** 织块边长（单元格数） */
    public static final int REGION = 10;
    /** 每织块卡数（恰切，保证队列映射为纯算术） */
    public static final int LEAVES = 11;
    /** 展开锚尺寸（单元格），恒 REGION 内部可行 */
    public static final int EXPAND = 6;
    /** 最小卡边（单元格） */
    private static final int MIN_SIDE = 2;

    /** 超级块长宽比偏好（横向铺开） */
    private static final float TILE_ASPECT = 1.6f;

    /** 叶子正方偏好概率：0.75 = 约 75% 的叶子切分取正方切线（其余按面积均衡切） */
    private static final float SQ_BIAS = 0.75f;

    /** 叶子长宽比上限：理想候选与过滤扫描规避超限形状（长条只在几何唯一解时出现） */
    private static final float SQ_LEAF_RATIO = 2.0f;

    // ===== 可行性 DP 表 =====
    // FEASIBLE[w][h][k]：w×h 矩形 guillotine 恰切 k 张 ≥MIN_SIDE 卡
    private static final boolean[][][] FEASIBLE = buildFeasible();

    private static boolean[][][] buildFeasible() {
        int n = REGION;
        int maxK = LEAVES;
        boolean[][][] f = new boolean[n + 1][n + 1][maxK + 1];
        for (int w = 1; w <= n; w++) {
            for (int h = 1; h <= n; h++) {
                for (int k = 1; k <= maxK; k++) {
                    boolean ok = false;
                    if (k == 1) {
                        ok = w >= MIN_SIDE && h >= MIN_SIDE;
                    } else if (w * h >= MIN_SIDE * MIN_SIDE * k) {
                        // 任一竖切/横切 + 任一叶数分配可行即可
                        outer:
                        for (int pos = MIN_SIDE; pos <= w - MIN_SIDE && !ok; pos++) {
                            for (int kL = 1; kL < k; kL++) {
                                if (f[pos][h][kL] && f[w - pos][h][k - kL]) {
                                    ok = true;
                                    break outer;
                                }
                            }
                        }
                        outerH:
                        for (int pos = MIN_SIDE; pos <= h - MIN_SIDE && !ok; pos++) {
                            for (int kL = 1; kL < k; kL++) {
                                if (f[w][pos][kL] && f[w][h - pos][k - kL]) {
                                    ok = true;
                                    break outerH;
                                }
                            }
                        }
                    }
                    f[w][h][k] = ok;
                }
            }
        }
        return f;
    }

    /** 矩形 w×h 能否恰切 k 张卡 */
    public static boolean feasible(int w, int h, int k) {
        if (w < 0 || h < 0 || w > REGION || h > REGION || k < 0 || k > LEAVES) return false;
        if (k == 0) return w == 0 || h == 0;
        return FEASIBLE[w][h][k];
    }

    /** 矩形最多能切几张卡 */
    public static int capacity(int w, int h) {
        if (w < MIN_SIDE || h < MIN_SIDE) return 0;
        for (int k = LEAVES; k >= 1; k--) {
            if (FEASIBLE[w][h][k]) return k;
        }
        return 0;
    }

    // ===== 哈希（自定整数混合，[0,1) 输出）=====

    private static int mix(int v) {
        v ^= v >>> 16;
        v *= 0x7feb352d;
        v ^= v >>> 15;
        v *= 0x846ca68b;
        v ^= v >>> 16;
        return v;
    }

    private static float hash01(int a, int b, int c) {
        int v = mix(a * 0x2f9e3a1b ^ b * 0x51f15eed ^ c * 0x1b873593);
        return (v >>> 8) * (1f / 0x1000000);
    }

    // ===== 织块模式（惰性缓存）=====

    /** 织块索引 → 11 卡的单元格矩形（x,y,w,h × 11，共 44 int） */
    private final java.util.HashMap<Integer, int[]> patterns = new java.util.HashMap<>();

    /** 队列长度 */
    private int total = 0;
    /** 超级块尺寸（织块数） */
    private int tileW = 1, tileH = 1;
    /** 超级块槽位总数（= tileW*tileH*LEAVES ≥ total） */
    private int tileSlots = LEAVES;

    /** 队列长度变化时重构超级块几何（unit 无关，px 由 View 层换算） */
    public void configure(int totalEntries) {
        int t = Math.max(0, totalEntries);
        if (t == total) return;
        total = t;
        patterns.clear();
        if (t <= 0) {
            tileW = tileH = 1;
            tileSlots = LEAVES;
            return;
        }
        int regionsNeeded = (int) Math.ceil(t / (double) LEAVES);
        tileW = Math.max(1, Math.round((float) Math.sqrt(regionsNeeded * TILE_ASPECT)));
        tileH = Math.max(1, (int) Math.ceil(regionsNeeded / (double) tileW));
        tileSlots = tileW * tileH * LEAVES;
        expandKey = -1;
        expandPattern = null;
    }

    public int total() { return total; }

    /** 织块索引（超级块内） */
    private int tileIndex(int regionX, int regionY) {
        int lx = floorMod(regionX, tileW);
        int ly = floorMod(regionY, tileH);
        return ly * tileW + lx;
    }

    /** 织块基础模式（11 卡 × x,y,w,h，单元格坐标） */
    public int[] patternAt(int regionX, int regionY) {
        int idx = tileIndex(regionX, regionY);
        return tilePatternByIdx(idx);
    }

    /**
     * 递归 guillotine：把 x,y,w,h 矩形恰切成 k 张卡写入 out，返回写入游标。
     * 两阶段决策：先跨叶数组合（kL 螺旋）+ 双轴搜索"形状合规"切线（叶子不超
     * 长宽比上限）；全组合无解才退回纯可行性切线（此时长条是几何唯一解）。
     */
    private static int carve(int x, int y, int w, int h, int k, int seed, int[] out, int at) {
        if (k <= 1 || !feasible(w, h, k)) {
            out[at] = x;
            out[at + 1] = y;
            out[at + 2] = w;
            out[at + 3] = h;
            return at + 4;
        }
        // 1) 左右叶数三角初选（向均衡收拢）
        float u1 = hash01(seed, x * 31 + y, 512 + k);
        float u2 = hash01(seed, x * 17 + y * 13, 256 + k * 7);
        int first = 1 + Math.round((k - 1) * (u1 + u2) * 0.5f);
        first = Math.max(1, Math.min(k - 1, first));
        boolean vPref = preferVertical(w, h, seed, y);

        // 2) 阶段 A：形状合规解（kL 双向螺旋 × 双轴）
        int chosenKL = -1, chosenPos = -1;
        boolean chosenV = false;
        for (int d = 0; d < k && chosenKL < 0; d++) {
            int hiKL = first + d, loKL = first - d;
            int loBound = Math.max(1, loKL), hiBound = Math.min(k - 1, hiKL);
            for (int kL = hiBound; kL >= loBound && chosenKL < 0; kL--) {
                if (d != 0 && kL != hiKL && kL != loKL) continue; // 只试螺旋两端
                if (!leavesSplittable(w, h, k, kL)) continue;
                int pos = cutPositionStrict(w, h, k, kL, vPref, seed, x, y);
                if (pos >= 0) {
                    chosenKL = kL;
                    chosenPos = pos;
                    chosenV = vPref;
                    break;
                }
                pos = cutPositionStrict(w, h, k, kL, !vPref, seed, x, y);
                if (pos >= 0) {
                    chosenKL = kL;
                    chosenPos = pos;
                    chosenV = !vPref;
                    break;
                }
            }
        }
        // 3) 阶段 B：纯可行性兜底（长条为几何唯一解时才到达）
        if (chosenKL < 0) {
            chosenKL = pickLeaves(w, h, k, first);
            chosenPos = cutPosition(w, h, k, chosenKL, vPref, seed, x, y);
            if (chosenPos >= 0) {
                chosenV = vPref;
            } else {
                chosenPos = cutPosition(w, h, k, chosenKL, !vPref, seed, x, y);
                if (chosenPos >= 0) {
                    chosenV = !vPref;
                } else {
                    // 理论不可达（可行性表保证）；强制中切保叶数进度
                    if (w >= h) {
                        chosenV = true;
                        chosenPos = Math.min(Math.max(MIN_SIDE, w / 2), w - MIN_SIDE);
                    } else {
                        chosenV = false;
                        chosenPos = Math.min(Math.max(MIN_SIDE, h / 2), h - MIN_SIDE);
                    }
                }
            }
        }
        // 4) 递归左右（不同子种子）
        if (chosenV) {
            int at2 = carve(x, y, chosenPos, h, chosenKL, seed ^ 0x5bd1e995, out, at);
            return carve(x + chosenPos, y, w - chosenPos, h, k - chosenKL, seed ^ 0x85ebca77, out, at2);
        }
        int at2 = carve(x, y, w, chosenPos, chosenKL, seed ^ 0x5bd1e995, out, at);
        return carve(x, y + chosenPos, w, h - chosenPos, k - chosenKL, seed ^ 0x85ebca77, out, at2);
    }

    /** 切轴偏好：长边优先劈（横长→竖切 80%，竖长→横切 80%），哈希保留变化 */
    private static boolean preferVertical(int w, int h, int seed, int y) {
        return w > h
                ? hash01(seed, 77, y) < 0.8f
                : (w < h ? hash01(seed, 78, y) < 0.2f : hash01(seed, 79, y) < 0.5f);
    }

    /** 叶数分配：初选不可行时向两侧螺旋找（确定性） */
    private static int pickLeaves(int w, int h, int k, int first) {
        int kl = Math.max(1, Math.min(k - 1, first));
        if (leavesSplittable(w, h, k, kl)) return kl;
        for (int d = 1; d < k; d++) {
            int hi = kl + d;
            if (hi < k && leavesSplittable(w, h, k, hi)) return hi;
            int lo = kl - d;
            if (lo >= 1 && leavesSplittable(w, h, k, lo)) return lo;
        }
        return 1; // 不可达兜底
    }

    /** w×h 切 kL/k-kL 是否存在可行切线 */
    private static boolean leavesSplittable(int w, int h, int k, int kL) {
        for (int pos = MIN_SIDE; pos <= w - MIN_SIDE; pos++) {
            if (feasible(pos, h, kL) && feasible(w - pos, h, k - kL)) return true;
        }
        for (int pos = MIN_SIDE; pos <= h - MIN_SIDE; pos++) {
            if (feasible(w, pos, kL) && feasible(w, h - pos, k - kL)) return true;
        }
        return false;
    }

    /**
     * 切线位置（合规层）：正方偏好 → 均衡抖动 → 过滤扫描，叶子侧形状全部合规。
     * @return 位置（沿切轴的格数），无合规解返回 -1（调用方换 kL/换轴）
     */
    private static int cutPositionStrict(int w, int h, int k, int kL, boolean vertical,
                                         int seed, int x, int y) {
        int pos = cutPositionBase(w, h, k, kL, vertical, seed, x, y);
        if (pos >= 0) return pos;
        return -1;
    }

    /**
     * 切线位置（含无形状兜底）：合规层无解后追加纯可行性扫描——
     * pickLeaves 已按无过滤可行性验证过 kL，此层必有解；落到此处说明长条是几何唯一解。
     */
    private static int cutPosition(int w, int h, int k, int kL, boolean vertical,
                                   int seed, int x, int y) {
        int pos = cutPositionBase(w, h, k, kL, vertical, seed, x, y);
        if (pos >= 0) return pos;
        int span = vertical ? w : h;
        int other = vertical ? h : w;
        for (int p = MIN_SIDE; p <= span - MIN_SIDE; p++) {
            if (cutOk(w, h, k, kL, vertical, p, other)) return p;
        }
        return -1;
    }

    /**
     * 切线位置主体：优先叶子正方切线（观感正方形卡增多）；其次均衡 ± 抖动，
     * 叶子侧规避长条（形状比超限的位置不作候选）。
     * @return 位置（沿切轴的格数），无合规解返回 -1
     */
    private static int cutPositionBase(int w, int h, int k, int kL, boolean vertical,
                                       int seed, int x, int y) {
        int span = vertical ? w : h;
        int other = vertical ? h : w;
        boolean leafLeft = kL == 1;
        boolean leafRight = k - kL == 1;
        // 正方候选：本次切分直接产生叶子的一侧，切线取 pos=other（左叶 other×other）
        // 或 pos=span−other（右叶正方）；另一侧同为叶时须形状合规（防连锁长条）；
        // 可行性由 cutOk 判定，不可行自动回落均衡切线
        int sqPos = -1;
        if (leafLeft && other >= MIN_SIDE && other <= span - MIN_SIDE
                && cutOk(w, h, k, kL, vertical, other, other)
                && (!leafRight || shapeOk(span - other, other))) {
            sqPos = other;                        // 左叶正方
        } else if (leafRight && span - other >= MIN_SIDE
                && cutOk(w, h, k, kL, vertical, span - other, other)
                && (!leafLeft || shapeOk(span - other, other))) {
            sqPos = span - other;                 // 右叶正方
        }
        if (sqPos >= 0 && hash01(seed ^ 0x27d4eb2f, x + 7, y + 13) < SQ_BIAS) {
            return sqPos;
        }
        float ratio = kL / (float) k;
        float jitter = 0.7f + 0.6f * hash01(seed, x + 1, y + 2);
        float idealF = span * ratio * jitter;
        int ideal = Math.max(MIN_SIDE, Math.min(span - MIN_SIDE, Math.round(idealF)));
        // 从理想位置向外找第一个可行切线（叶子侧同时规避长条位置）
        for (int d = 0; d < span; d++) {
            int hi = ideal + d;
            if (hi > span - MIN_SIDE) break;
            if (cutOk(w, h, k, kL, vertical, hi, other)
                    && (!leafLeft || shapeOk(hi, other))
                    && (!leafRight || shapeOk(span - hi, other))) {
                return hi;
            }
            int lo = ideal - d;
            if (lo >= MIN_SIDE && lo != hi) {
                if (cutOk(w, h, k, kL, vertical, lo, other)
                        && (!leafLeft || shapeOk(lo, other))
                        && (!leafRight || shapeOk(span - lo, other))) {
                    return lo;
                }
            }
        }
        // 过滤全扫描（叶子长条规避，从边向内找合规位置）
        for (int pos = MIN_SIDE; pos <= span - MIN_SIDE; pos++) {
            if (cutOk(w, h, k, kL, vertical, pos, other)
                    && (!leafLeft || shapeOk(pos, other))
                    && (!leafRight || shapeOk(span - pos, other))) {
                return pos;
            }
        }
        return -1;
    }

    /** 两边形状比不超叶子上限（长条规避；兜底路径不调用） */
    private static boolean shapeOk(int a, int b) {
        int mn = Math.min(a, b), mx = Math.max(a, b);
        return mx <= mn * SQ_LEAF_RATIO;
    }

    private static boolean cutOk(int w, int h, int k, int kL, boolean vertical, int pos, int other) {
        if (vertical) {
            return feasible(pos, other, kL) && feasible(w - pos, other, k - kL);
        }
        return feasible(other, pos, kL) && feasible(other, h - pos, k - kL);
    }

    // ===== 队列映射 =====

    /** 织块内叶位对应队列条目（环绕） */
    public int queueIndexOf(int regionX, int regionY, int leaf) {
        if (total <= 0) return 0;
        int slot = tileIndex(regionX, regionY) * LEAVES + leaf;
        return ((slot % total) + total) % total;
    }

    /**
     * 队列条目离 (px, py)（像素坐标）最近的实例（regionX/regionY 绝对织块坐标 + 叶位）。
     * 同一首歌的周期 = 超级块边长；对每个承载槽算最近重复副本，取中心距最小者。
     */
    public int[] nearestInstance(int queueIndex, float px, float py,
                                 float regionPxW, float regionPxH,
                                 float viewportW, float viewportH) {
        if (total <= 0 || queueIndex < 0 || queueIndex >= total) return null;
        float cx = px + viewportW / 2f;
        float cy = py + viewportH / 2f;
        int bestRegionX = 0, bestRegionY = 0, bestLeaf = 0;
        float bestDist = Float.POSITIVE_INFINITY;
        for (int slot = queueIndex; slot < tileSlots; slot += total) {
            int idx = slot / LEAVES;
            int leaf = slot % LEAVES;
            int lx = idx % tileW;
            int ly = idx / tileW;
            int[] p = tilePatternByIdx(idx);
            int cellX = p[leaf * 4], cellY = p[leaf * 4 + 1];
            int cellW = p[leaf * 4 + 2], cellH = p[leaf * 4 + 3];
            float baseX = (lx * REGION + cellX + cellW / 2f) * regionPxW / REGION;
            float baseY = (ly * REGION + cellY + cellH / 2f) * regionPxH / REGION;
            // 最近重复周期
            int repX = Math.round((cx - baseX) / (tileW * regionPxW));
            int repY = Math.round((cy - baseY) / (tileH * regionPxH));
            float rx = baseX + repX * tileW * regionPxW;
            float ry = baseY + repY * tileH * regionPxH;
            float d = (rx - cx) * (rx - cx) + (ry - cy) * (ry - cy);
            if (d < bestDist) {
                bestDist = d;
                bestLeaf = leaf;
                bestRegionX = repX * tileW + lx;
                bestRegionY = repY * tileH + ly;
            }
        }
        return new int[]{bestRegionX, bestRegionY, bestLeaf};
    }

    /** 按超级块内织块索引取基础模式（惰性生成并落缓存） */
    private int[] tilePatternByIdx(int idx) {
        Integer key = idx;
        int[] p = patterns.get(key);
        if (p != null) return p;
        int lx = idx % tileW;
        int ly = idx / tileW;
        p = new int[LEAVES * 4];
        int end = carve(0, 0, REGION, REGION, LEAVES,
                mix(lx * 0x27d4eb2f ^ ly * 0x165667b1 ^ 0x9e3779b9), p, 0);
        // 恰切保证；极端兜底：不足处补整块单叶（不与已占重叠的退化保护由调用层信任可行性表）
        while (end < LEAVES * 4) {
            p[end] = 0;
            p[end + 1] = 0;
            p[end + 2] = REGION;
            p[end + 3] = REGION;
            end += 4;
        }
        patterns.put(key, p);
        return p;
    }

    // ===== 展开让位（锚定剥离 + 带内细分，运行时求解）=====

    private int expandKey = -1;
    private int[] expandPattern = null;
    /** 让位叶收集缓冲（带内生成 + 排序复用） */
    private final int[] stripLeaves = new int[LEAVES * 4];

    /**
     * 展开让位模式：leaf 位为 6×6 锚（由原卡位置就近夹取），其余 10 卡由锚外
     * 余区剥离的至多 4 条矩形带细分而成，恰好铺满织块、总叶数恰 11。
     * 身份映射：锚叶保持 leaf 位，其余卡按原叶序对应新叶序（位置从左上排序）。
     */
    public int[] reflowAt(int regionX, int regionY, int leaf) {
        int idx = tileIndex(regionX, regionY);
        int key = idx * LEAVES + leaf;
        if (expandKey == key && expandPattern != null) return expandPattern;

        int[] base = patternAt(regionX, regionY);
        // 锚位：原卡中心 − 半锚，夹 [0, REGION−EXPAND] 后吸附偶数格——
        // 保证锚外四条剥离带非空侧边 ≥ MIN_SIDE（1 格宽条带无合法卡可切）
        int ax = clamp(Math.round(base[leaf * 4] + base[leaf * 4 + 2] / 2f) - EXPAND / 2, 0, REGION - EXPAND);
        int ay = clamp(Math.round(base[leaf * 4 + 1] + base[leaf * 4 + 3] / 2f) - EXPAND / 2, 0, REGION - EXPAND);
        ax = Math.min(ax / 2 * 2, REGION - EXPAND);
        ay = Math.min(ay / 2 * 2, REGION - EXPAND);

        int[] out = new int[LEAVES * 4];
        boolean[] filled = new boolean[LEAVES];
        out[leaf * 4] = ax;
        out[leaf * 4 + 1] = ay;
        out[leaf * 4 + 2] = EXPAND;
        out[leaf * 4 + 3] = EXPAND;
        filled[leaf] = true;

        // 四条剥离带（互不重叠、并 = 织块\锚）
        int[] bx = new int[4], by = new int[4], bw = new int[4], bh = new int[4];
        int bands = 0;
        if (ax > 0) { // 左整条
            bx[bands] = 0; by[bands] = 0; bw[bands] = ax; bh[bands] = REGION; bands++;
        }
        if (ax + EXPAND < REGION) { // 右整条
            bx[bands] = ax + EXPAND; by[bands] = 0;
            bw[bands] = REGION - ax - EXPAND; bh[bands] = REGION; bands++;
        }
        if (ay > 0) { // 锚上方中带
            bx[bands] = ax; by[bands] = 0; bw[bands] = EXPAND; bh[bands] = ay; bands++;
        }
        if (ay + EXPAND < REGION) { // 锚下方中带
            bx[bands] = ax; by[bands] = ay + EXPAND;
            bw[bands] = EXPAND; bh[bands] = REGION - ay - EXPAND; bands++;
        }

        // 叶数预算：每带保底 1 叶（cap>0 时），余量按面积加权分配（cap 钳制，尾差兜底给最大带）
        int budget = LEAVES - 1;
        int[] share = new int[bands];
        int[] cap = new int[bands];
        int assigned = 0;
        int largest = 0;
        int largestArea = -1;
        for (int b = 0; b < bands; b++) {
            cap[b] = capacity(bw[b], bh[b]);
            share[b] = Math.min(1, cap[b]);
            assigned += share[b];
            int area = bw[b] * bh[b];
            if (area > largestArea) {
                largestArea = area;
                largest = b;
            }
        }
        int remaining = budget - assigned;
        while (remaining > 0) {
            int gave = 0;
            for (int b = 0; b < bands && remaining > 0; b++) {
                if (share[b] >= cap[b]) continue;
                int weight = Math.round((bw[b] * bh[b]) / (float) largestArea * 2f);
                if (weight < 1) weight = 1;
                int add = Math.min(weight, Math.min(cap[b] - share[b], remaining));
                share[b] += add;
                remaining -= add;
                gave += add;
            }
            if (gave == 0) {
                // 全带到 cap 仍有剩余（理论不可能：Σcap ≥ 16 > 10）：堆给最大带
                share[largest] += remaining;
                remaining = 0;
            }
        }

        // 带内细分 → 叶按位置（y 主序 x 次序）收集
        int leafCount = 0;
        for (int b = 0; b < bands; b++) {
            if (share[b] <= 0) continue;
            int end = carve(bx[b], by[b], bw[b], bh[b], share[b],
                    mix(idx * 0x1b873593 ^ b * 0x85ebca6b ^ 0x2545f491), stripLeaves, leafCount * 4);
            leafCount = (end) / 4;
        }
        // 稳定排序（y 主序、x 次序）：小规模插入排序
        for (int i = 1; i < leafCount; i++) {
            int tx = stripLeaves[i * 4], ty = stripLeaves[i * 4 + 1];
            int tw = stripLeaves[i * 4 + 2], th = stripLeaves[i * 4 + 3];
            int j = i - 1;
            while (j >= 0 && (stripLeaves[j * 4 + 1] > ty
                    || (stripLeaves[j * 4 + 1] == ty && stripLeaves[j * 4] > tx))) {
                stripLeaves[(j + 1) * 4] = stripLeaves[j * 4];
                stripLeaves[(j + 1) * 4 + 1] = stripLeaves[j * 4 + 1];
                stripLeaves[(j + 1) * 4 + 2] = stripLeaves[j * 4 + 2];
                stripLeaves[(j + 1) * 4 + 3] = stripLeaves[j * 4 + 3];
                j--;
            }
            stripLeaves[(j + 1) * 4] = tx;
            stripLeaves[(j + 1) * 4 + 1] = ty;
            stripLeaves[(j + 1) * 4 + 2] = tw;
            stripLeaves[(j + 1) * 4 + 3] = th;
        }

        // 依序填入非锚叶位（保持原叶序身份）
        int cursor = 0;
        for (int i = 0; i < LEAVES && cursor < leafCount; i++) {
            if (filled[i]) continue;
            out[i * 4] = stripLeaves[cursor * 4];
            out[i * 4 + 1] = stripLeaves[cursor * 4 + 1];
            out[i * 4 + 2] = stripLeaves[cursor * 4 + 2];
            out[i * 4 + 3] = stripLeaves[cursor * 4 + 3];
            cursor++;
        }

        expandKey = key;
        expandPattern = out;
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 地板取模（含负数；除数恒正） */
    private static int floorMod(int a, int b) {
        return ((a % b) + b) % b;
    }
}
