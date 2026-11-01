package com.jingxin.jingxinmusic.view.lattice;

/**
 * 拼贴墙无限平铺几何（FOLIA Lattice layout.ts 1:1 移植，纯函数零状态）
 *
 * 队列长度 N 映射到 ceil(N/12) 个块，按 FIELD_ASPECT 横向偏好排成
 * blocksPerRow x blockRows 的大 cell，cell 两向无限重复铺满平面：
 * - 一首歌在屏上可有多个实例（instanceId = cellSlot:repeatX:repeatY 唯一定位）
 * - 环绕填充：queueIndex = cellSlot % total，尾部环绕，空缺不随 cell 周期复发
 * - 裁剪成本与队列长度无关：只遍历视口相交的块 x 12 槽位
 */
public final class LatticeGeometry {

    private LatticeGeometry() {}

    /** 重复 cell 的长宽偏好（横向 2.2:1，避免竖条纹观感） */
    private static final float FIELD_ASPECT = 2.2f;

    /** 单视口实例上限（车机降载：FOLIA 桌面版为 400） */
    public static final int MAX_INSTANCES = 260;

    /** 重复 cell 几何：blocksPerRow x blockRows 的块阵列 */
    public static final class Cell {
        public final int blocksPerRow;
        public final int blockRows;
        public final int cellSlots;
        public final float cellWidth;   // px
        public final float cellHeight;  // px
        public final float blockWidth;  // px
        public final float blockHeight; // px

        Cell(int bpr, int br, int slots, float cw, float ch, float bw, float bh) {
            this.blocksPerRow = bpr; this.blockRows = br; this.cellSlots = slots;
            this.cellWidth = cw; this.cellHeight = ch; this.blockWidth = bw; this.blockHeight = bh;
        }
    }

    /** 一个绘制实例：某槽位在世界某位置的矩形 */
    public static final class Instance {
        public final int cellSlot;    // 实例在 cell 内的槽位序号（全局槽）
        public final int repeatX;     // cell 重复坐标 x
        public final int repeatY;     // cell 重复坐标 y
        public final int queueIndex;  // 对应队列条目（环绕）
        public final int blockSlot;   // 块内槽位 0..11（模板索引）
        public final float x, y, width, height; // 世界 px

        Instance(int cellSlot, int repeatX, int repeatY, int queueIndex, int blockSlot,
                 float x, float y, float w, float h) {
            this.cellSlot = cellSlot; this.repeatX = repeatX; this.repeatY = repeatY;
            this.queueIndex = queueIndex; this.blockSlot = blockSlot;
            this.x = x; this.y = y; this.width = w; this.height = h;
        }

        public float centerX() { return x + width / 2f; }
        public float centerY() { return y + height / 2f; }
    }

    /** 建 cell 几何（pitch = cellSize + gap） */
    public static Cell cellOf(int totalEntries, float cellSize, float gap) {
        float pitch = cellSize + gap;
        float blockWidth = LatticeTemplates.BLOCK_COLS * pitch;
        float blockHeight = LatticeTemplates.BLOCK_ROWS * pitch;
        int blocks = Math.max(1, (int) Math.ceil(Math.max(0, totalEntries)
                / (float) LatticeTemplates.SLOTS_PER_BLOCK));
        int blocksPerRow = blocks <= 1 ? 1 : Math.max(1, Math.round(
                (float) Math.sqrt(FIELD_ASPECT * blocks
                        * LatticeTemplates.BLOCK_ROWS / (float) LatticeTemplates.BLOCK_COLS)));
        int blockRows = (int) Math.ceil(blocks / (float) blocksPerRow);
        return new Cell(blocksPerRow, blockRows,
                blocksPerRow * blockRows * LatticeTemplates.SLOTS_PER_BLOCK,
                blocksPerRow * blockWidth, blockRows * blockHeight,
                blockWidth, blockHeight);
    }

    /** 建一个实例（绝对块坐标可为负），纯算术 */
    private static Instance build(Cell cell, int totalEntries,
                                  int absoluteColumn, int absoluteRow, int blockSlot,
                                  float cellSize, float gap) {
        int repeatX = Math.floorDiv(absoluteColumn, cell.blocksPerRow);
        int repeatY = Math.floorDiv(absoluteRow, cell.blockRows);
        int localColumn = absoluteColumn - repeatX * cell.blocksPerRow;
        int localRow = absoluteRow - repeatY * cell.blockRows;
        int cellSlot = (localRow * cell.blocksPerRow + localColumn)
                * LatticeTemplates.SLOTS_PER_BLOCK + blockSlot;
        LatticeTemplates.Slot slot = LatticeTemplates.templateOf(localColumn, localRow)[blockSlot];
        float pitch = cellSize + gap;
        return new Instance(cellSlot, repeatX, repeatY,
                ((cellSlot % totalEntries) + totalEntries) % totalEntries, blockSlot,
                repeatX * cell.cellWidth + localColumn * cell.blockWidth + slot.x * pitch,
                repeatY * cell.cellHeight + localRow * cell.blockHeight + slot.y * pitch,
                slot.cols * pitch - gap, slot.rows * pitch - gap);
    }

    /** 实例的绝对块坐标 */
    public static int columnOf(Instance i, Cell cell) {
        int blockInCell = i.cellSlot / LatticeTemplates.SLOTS_PER_BLOCK;
        return i.repeatX * cell.blocksPerRow + (blockInCell % cell.blocksPerRow);
    }

    public static int rowOf(Instance i, Cell cell) {
        int blockInCell = i.cellSlot / LatticeTemplates.SLOTS_PER_BLOCK;
        return i.repeatY * cell.blockRows + (blockInCell / cell.blocksPerRow);
    }

    /**
     * 视口内实例（含 overscan 外扩）。
     * 成本只与屏上块数相关，与队列长度无关。
     */
    public static void collectNear(Cell cell, int totalEntries,
                                   float left, float top, float right, float bottom,
                                   float overscan, float cellSize, float gap,
                                   java.util.ArrayList<Instance> out) {
        out.clear();
        if (totalEntries <= 0 || cell.blockWidth <= 0 || cell.blockHeight <= 0) return;
        float vl = left - overscan, vr = right + overscan;
        float vt = top - overscan, vb = bottom + overscan;
        int fromCol = (int) Math.floor(vl / cell.blockWidth);
        int toCol = (int) Math.floor(vr / cell.blockWidth);
        int fromRow = (int) Math.floor(vt / cell.blockHeight);
        int toRow = (int) Math.floor(vb / cell.blockHeight);
        for (int row = fromRow; row <= toRow; row++) {
            for (int col = fromCol; col <= toCol; col++) {
                for (int slot = 0; slot < LatticeTemplates.SLOTS_PER_BLOCK; slot++) {
                    Instance in = build(cell, totalEntries, col, row, slot, cellSize, gap);
                    if (in.x >= vr || in.x + in.width <= vl) continue;
                    if (in.y >= vb || in.y + in.height <= vt) continue;
                    out.add(in);
                    if (out.size() >= MAX_INSTANCES) return;
                }
            }
        }
    }

    /**
     * 队列条目离某世界点最近的实例。
     * 一首歌可在一个 cell 内占多个槽位，逐一取其最近重复比较（FOLIA locateNearestInstance）。
     */
    public static Instance nearestOf(Cell cell, int totalEntries, int queueIndex,
                                     float px, float py, float cellSize, float gap) {
        if (totalEntries <= 0 || queueIndex < 0 || queueIndex >= totalEntries) return null;
        Instance best = null;
        float bestDist = Float.POSITIVE_INFINITY;
        for (int cellSlot = queueIndex; cellSlot < cell.cellSlots; cellSlot += totalEntries) {
            int blockInCell = cellSlot / LatticeTemplates.SLOTS_PER_BLOCK;
            int localColumn = blockInCell % cell.blocksPerRow;
            int localRow = blockInCell / cell.blocksPerRow;
            int blockSlot = cellSlot % LatticeTemplates.SLOTS_PER_BLOCK;
            Instance base = build(cell, totalEntries, localColumn, localRow, blockSlot, cellSize, gap);
            int repeatX = Math.round((px - base.x - base.width / 2f) / cell.cellWidth);
            int repeatY = Math.round((py - base.y - base.height / 2f) / cell.cellHeight);
            Instance cand = build(cell, totalEntries,
                    repeatX * cell.blocksPerRow + localColumn,
                    repeatY * cell.blockRows + localRow, blockSlot, cellSize, gap);
            float dx = cand.x + cand.width / 2f - px;
            float dy = cand.y + cand.height / 2f - py;
            float d = dx * dx + dy * dy;
            if (d < bestDist) { bestDist = d; best = cand; }
        }
        return best;
    }

    /**
     * 展开让位：块内重铺（查让位表），块外形不变 → 零级联。
     * 输出 12 组 rect 到 out[outOffset + i*4 .. +3]：x,y,w,h。
     * @return 写入组数（成功=12），失败 0
     */
    public static int expandedBlockRects(Cell cell, Instance active,
                                         float cellSize, float gap, float[] out, int outOffset) {
        if (out == null || out.length - outOffset < 12 * 4) return 0;
        int col = columnOf(active, cell);
        int row = rowOf(active, cell);
        int localColumn = col - active.repeatX * cell.blocksPerRow;
        int localRow = row - active.repeatY * cell.blockRows;
        int orientation = LatticeTemplates.orientationOf(localColumn, localRow);
        int[] table = LatticeReflows.reflowOf(orientation, active.blockSlot);
        if (table == null) return 0;
        float pitch = cellSize + gap;
        float originX = active.repeatX * cell.cellWidth + localColumn * cell.blockWidth;
        float originY = active.repeatY * cell.cellHeight + localRow * cell.blockHeight;
        for (int s = 0; s < LatticeTemplates.SLOTS_PER_BLOCK; s++) {
            int x = table[s * 4], y = table[s * 4 + 1], c = table[s * 4 + 2], r = table[s * 4 + 3];
            out[outOffset + s * 4] = originX + x * pitch;
            out[outOffset + s * 4 + 1] = originY + y * pitch;
            out[outOffset + s * 4 + 2] = c * pitch - gap;
            out[outOffset + s * 4 + 3] = r * pitch - gap;
        }
        return LatticeTemplates.SLOTS_PER_BLOCK;
    }
}
