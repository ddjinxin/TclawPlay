package com.jingxin.jingxinmusic.view.lattice;

/**
 * 拼贴墙块模板（FOLIA Lattice blockTemplates.ts 1:1 移植）
 *
 * 一个 12x8 的块为重复单元的基座，内含 12 个槽位（最大 6x4、最小 2x2），恰好铺满整块。
 * 4 个手绘基础模板 x 4 种反射（原样/水平翻/垂直翻/双翻）= 16 种朝向。
 * 朝向由块坐标无状态推导：
 *   模板号 = (col + row*2) % 4 —— 线性步进，相邻块永不重样；
 *   反射号 = 块坐标整数哈希 —— 打散周期，肉眼找不出重复规律。
 * 反射保持槽位顺序，卡片索引在任意朝向下不变。
 */
public final class LatticeTemplates {

    private LatticeTemplates() {}

    public static final int BLOCK_COLS = 12;
    public static final int BLOCK_ROWS = 8;
    public static final int SLOTS_PER_BLOCK = 12;

    /** 展开卡齿位（6x6），唯一有精确重铺解的尺寸 */
    public static final int EXPANSION_COLS = 6;
    public static final int EXPANSION_ROWS = 6;

    /** 槽位：单位格坐标 + 跨度（cols x rows） */
    public static final class Slot {
        public final short x, y, cols, rows;

        public Slot(int x, int y, int cols, int rows) {
            this.x = (short) x; this.y = (short) y;
            this.cols = (short) cols; this.rows = (short) rows;
        }
    }

    /** 4 个手绘基础模板（块内恰好铺满，无空洞无交叠） */
    private static final Slot[][] BASES = {
        // 变体 0
        {
            new Slot(0, 0, 3, 2), new Slot(3, 0, 6, 4), new Slot(9, 0, 3, 2),
            new Slot(0, 2, 3, 2), new Slot(9, 2, 3, 4), new Slot(0, 4, 4, 4),
            new Slot(4, 4, 2, 2), new Slot(6, 4, 3, 2), new Slot(4, 6, 2, 2),
            new Slot(6, 6, 2, 2), new Slot(8, 6, 2, 2), new Slot(10, 6, 2, 2),
        },
        // 变体 1
        {
            new Slot(0, 0, 2, 3), new Slot(2, 0, 2, 3), new Slot(4, 0, 2, 3),
            new Slot(6, 0, 6, 4), new Slot(0, 3, 6, 2), new Slot(6, 4, 2, 4),
            new Slot(8, 4, 2, 4), new Slot(10, 4, 2, 2), new Slot(0, 5, 2, 3),
            new Slot(2, 5, 2, 3), new Slot(4, 5, 2, 3), new Slot(10, 6, 2, 2),
        },
        // 变体 2
        {
            new Slot(0, 0, 6, 4), new Slot(6, 0, 6, 3), new Slot(6, 3, 4, 3),
            new Slot(10, 3, 2, 2), new Slot(0, 4, 4, 2), new Slot(4, 4, 2, 2),
            new Slot(10, 5, 2, 3), new Slot(0, 6, 2, 2), new Slot(2, 6, 2, 2),
            new Slot(4, 6, 2, 2), new Slot(6, 6, 2, 2), new Slot(8, 6, 2, 2),
        },
        // 变体 3
        {
            new Slot(0, 0, 2, 2), new Slot(2, 0, 2, 2), new Slot(4, 0, 4, 3),
            new Slot(8, 0, 2, 4), new Slot(10, 0, 2, 4), new Slot(0, 2, 2, 3),
            new Slot(2, 2, 2, 2), new Slot(4, 3, 4, 3), new Slot(2, 4, 2, 2),
            new Slot(8, 4, 4, 4), new Slot(0, 5, 2, 3), new Slot(2, 6, 6, 2),
        },
    };

    private static final int TEMPLATE_COUNT = BASES.length;
    private static final int REFLECTION_COUNT = 4;

    /** 16 种朝向的基础模板（4 模板 x 4 反射） */
    private static final Slot[][] ORIENTED = buildOriented(BASES);

    private static Slot flipX(Slot s) {
        return new Slot(BLOCK_COLS - s.x - s.cols, s.y, s.cols, s.rows);
    }

    private static Slot flipY(Slot s) {
        return new Slot(s.x, BLOCK_ROWS - s.y - s.rows, s.cols, s.rows);
    }

    private static Slot[][] buildOriented(Slot[][] sources) {
        Slot[][] out = new Slot[sources.length * REFLECTION_COUNT][];
        for (int t = 0; t < sources.length; t++) {
            Slot[] base = sources[t];
            Slot[] fx = new Slot[base.length];
            Slot[] fy = new Slot[base.length];
            Slot[] fxy = new Slot[base.length];
            for (int i = 0; i < base.length; i++) {
                fx[i] = flipX(base[i]);
                fy[i] = flipY(base[i]);
                fxy[i] = flipY(flipX(base[i]));
            }
            out[t * 4] = base;
            out[t * 4 + 1] = fx;
            out[t * 4 + 2] = fy;
            out[t * 4 + 3] = fxy;
        }
        return out;
    }

    /** 整数哈希（xxhash 风格 imul 混合），选反射 */
    private static int mixBlockCoords(int blockColumn, int blockRow) {
        int value = imul(blockColumn, 0x9e3779b1) ^ imul(blockRow, 0x85ebca6b);
        value = imul(value ^ (value >>> 15), 0x2c1b3c6d);
        value ^= value >>> 12;
        return value >>> 0;
    }

    private static int imul(int a, int b) {
        return a * b; // Java int 乘法本身即 32 位截断，与 JS Math.imul 等价
    }

    /** 块朝向索引：模板线性步进保相邻不重样，反射哈希打散周期（0..15） */
    public static int orientationOf(int blockColumn, int blockRow) {
        int template = ((blockColumn + blockRow * 2) % TEMPLATE_COUNT + TEMPLATE_COUNT) % TEMPLATE_COUNT;
        return template * REFLECTION_COUNT + mixBlockCoords(blockColumn, blockRow) % REFLECTION_COUNT;
    }

    /** 取某块的 12 槽位模板（blockColumn/blockRow 可为任意整数，含负） */
    public static Slot[] templateOf(int blockColumn, int blockRow) {
        return ORIENTED[orientationOf(blockColumn, blockRow)];
    }

    /** 自检：每个基础模板必须恰好铺满块（面积=96 且无交叠） */
    public static boolean validate() {
        for (Slot[] t : BASES) {
            int area = 0;
            for (Slot s : t) area += s.cols * s.rows;
            if (area != BLOCK_COLS * BLOCK_ROWS) return false;
            for (int i = 0; i < t.length; i++) {
                for (int j = i + 1; j < t.length; j++) {
                    if (overlap(t[i], t[j])) return false;
                }
            }
        }
        return true;
    }

    private static boolean overlap(Slot a, Slot b) {
        return a.x < b.x + b.cols && a.x + a.cols > b.x
                && a.y < b.y + b.rows && a.y + a.rows > b.y;
    }
}
