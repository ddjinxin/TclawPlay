package com.jingxin.jingxinmusic.util;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 将封面和歌词内嵌到 m4a 文件的 ISOBMFF 容器中
 * 在 moov.udta.meta.ilst 下写入 covr 和 ©lyr 标签
 * 零第三方依赖，纯字节数组操作
 */
public class M4aMetadataWriter {

    private static final String TAG = "M4aMetadataWriter";

    // ©lyr 的4字节标识：0xA9 + "lyr"
    private static final byte[] TAG_LYR = new byte[]{(byte) 0xA9, 'l', 'y', 'r'};

    /**
     * 将封面和歌词嵌入 m4a 文件（原地替换）
     *
     * @param m4aFile  源m4a文件
     * @param coverJpg 封面JPEG数据，null则不嵌入封面
     * @param lyrics   歌词文本（LRC格式），null则不嵌入歌词
     * @return true=成功
     */
    public static boolean embedMetadata(File m4aFile, byte[] coverJpg, String lyrics) {
        if ((coverJpg == null || coverJpg.length == 0) && (lyrics == null || lyrics.isEmpty())) {
            Log.d(TAG, "无封面也无歌词，跳过嵌入");
            return true;
        }

        try {
            byte[] fileBytes = readFile(m4aFile);
            if (fileBytes == null) return false;

            // 1. 扫描顶层 atom，找到 moov 和 mdat 的位置
            int moovOffset = -1, moovSize = 0;
            int mdatOffset = -1;

            int pos = 0;
            while (pos + 8 <= fileBytes.length) {
                int size = readInt32BE(fileBytes, pos);
                String type = readType(fileBytes, pos + 4);
                if (size <= 0) break;

                if ("moov".equals(type)) {
                    moovOffset = pos;
                    moovSize = size;
                } else if ("mdat".equals(type)) {
                    mdatOffset = pos;
                }
                pos += size;
            }

            if (moovOffset < 0) {
                Log.e(TAG, "未找到moov atom");
                return false;
            }

            // 2. 提取 moov 字节
            byte[] moovBytes = subarray(fileBytes, moovOffset, moovSize);

            // 3. 构建新的 udta atom
            byte[] newUdta = buildUdtaAtom(coverJpg, lyrics);

            // 4. 将 udta 插入/替换到 moov 中
            int udtaPos = findChildAtom(moovBytes, 8, moovSize, "udta");
            byte[] newMoov;
            if (udtaPos >= 0) {
                // 替换已有 udta
                int oldUdtaSize = readInt32BE(moovBytes, udtaPos);
                newMoov = splice(moovBytes, udtaPos, udtaPos + oldUdtaSize, newUdta);
            } else {
                // 在 moov 末尾追加 udta
                newMoov = splice(moovBytes, moovSize, moovSize, newUdta);
            }

            // 更新 moov size
            writeInt32BE(newMoov, 0, newMoov.length);

            // 5. 计算 moov 大小变化
            int delta = newMoov.length - moovSize;

            // 6. 如果 moov 在 mdat 前面，修正所有 chunk offset
            boolean moovBeforeMdat = (mdatOffset >= 0 && moovOffset < mdatOffset);
            if (moovBeforeMdat && delta != 0) {
                fixChunkOffsets(newMoov, 8, newMoov.length, delta);
            }

            // 7. 重构文件：替换 moov
            byte[] newFile = splice(fileBytes, moovOffset, moovOffset + moovSize, newMoov);

            // 8. 写入临时文件，成功后替换
            File tempFile = new File(m4aFile.getParent(), m4aFile.getName() + ".meta.tmp");
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(newFile);
            fos.flush();
            fos.close();

            // 替换原文件
            if (!m4aFile.delete()) {
                Log.e(TAG, "删除原文件失败");
                tempFile.delete();
                return false;
            }
            if (!tempFile.renameTo(m4aFile)) {
                Log.e(TAG, "重命名临时文件失败");
                return false;
            }

            Log.d(TAG, "元数据嵌入成功: " + m4aFile.getName()
                    + " 封面=" + (coverJpg != null ? coverJpg.length + "B" : "无")
                    + " 歌词=" + (lyrics != null ? lyrics.length() + "字" : "无"));
            return true;

        } catch (Exception e) {
            Log.e(TAG, "嵌入元数据失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== ISOBMFF atom 构建 ====================

    /**
     * 构建 udta atom，包含 meta > hdlr + ilst
     */
    private static byte[] buildUdtaAtom(byte[] coverJpg, String lyrics) {
        byte[] metaBytes = buildMetaAtom(coverJpg, lyrics);
        return boxAtom("udta", metaBytes);
    }

    /**
     * 构建 meta atom（full box: 4字节 ver+flags + children）
     */
    private static byte[] buildMetaAtom(byte[] coverJpg, String lyrics) {
        byte[] hdlr = buildHdlrAtom();
        byte[] ilst = buildIlstAtom(coverJpg, lyrics);

        // meta = header(8) + ver+flags(4) + hdlr + ilst
        int totalSize = 8 + 4 + hdlr.length + ilst.length;
        byte[] meta = new byte[totalSize];
        writeInt32BE(meta, 0, totalSize);
        writeType(meta, 4, "meta");
        // version=0, flags=0 (already zero)
        System.arraycopy(hdlr, 0, meta, 12, hdlr.length);
        System.arraycopy(ilst, 0, meta, 12 + hdlr.length, ilst.length);
        return meta;
    }

    /**
     * 构建 hdlr atom（meta 下必需，handler_type = 'mdir'）
     */
    private static byte[] buildHdlrAtom() {
        // hdlr: header(8) + ver+flags(4) + pre_defined(4) + handler_type(4) + reserved(12) + name(1)
        int size = 8 + 4 + 4 + 4 + 12 + 1; // = 33
        byte[] hdlr = new byte[size];
        writeInt32BE(hdlr, 0, size);
        writeType(hdlr, 4, "hdlr");
        // version=0, flags=0 (offset 8-11, already zero)
        // pre_defined=0 (offset 12-15, already zero)
        writeType(hdlr, 16, "mdir"); // handler_type
        // reserved=0 (offset 20-31, already zero)
        // name="" null-terminated (offset 32 = 0, already zero)
        return hdlr;
    }

    /**
     * 构建 ilst atom，包含 covr 和 ©lyr
     */
    private static byte[] buildIlstAtom(byte[] coverJpg, String lyrics) {
        int count = 0;
        byte[] covrItem = null, lyrItem = null;

        if (coverJpg != null && coverJpg.length > 0) {
            covrItem = buildTagItem("covr", 0x0000000D, coverJpg); // type 13 = JPEG
            count++;
        }
        if (lyrics != null && !lyrics.isEmpty()) {
            lyrItem = buildTagItem(TAG_LYR, 0x00000001, lyrics.getBytes()); // type 1 = UTF-8
            count++;
        }

        if (count == 0) return new byte[8]; // empty ilst

        int childrenSize = (covrItem != null ? covrItem.length : 0) + (lyrItem != null ? lyrItem.length : 0);
        return boxAtom("ilst", covrItem, lyrItem);
    }

    /**
     * 构建一个 ilst 标签项：[size][tag][data_atom]
     */
    private static byte[] buildTagItem(String tag, int flags, byte[] payload) {
        byte[] dataAtom = buildDataAtom(flags, payload);
        int size = 8 + dataAtom.length;
        byte[] item = new byte[size];
        writeInt32BE(item, 0, size);
        writeType(item, 4, tag);
        System.arraycopy(dataAtom, 0, item, 8, dataAtom.length);
        return item;
    }

    /**
     * 构建一个 ilst 标签项（4字节tag，如 ©lyr）
     */
    private static byte[] buildTagItem(byte[] tag, int flags, byte[] payload) {
        byte[] dataAtom = buildDataAtom(flags, payload);
        int size = 8 + dataAtom.length;
        byte[] item = new byte[size];
        writeInt32BE(item, 0, size);
        System.arraycopy(tag, 0, item, 4, 4);
        System.arraycopy(dataAtom, 0, item, 8, dataAtom.length);
        return item;
    }

    /**
     * 构建 data atom：[size]['data'][ver+flags(4)][reserved(4)][payload]
     */
    private static byte[] buildDataAtom(int flags, byte[] payload) {
        int size = 8 + 4 + 4 + payload.length; // header + ver+flags + reserved + payload
        byte[] atom = new byte[size];
        writeInt32BE(atom, 0, size);
        writeType(atom, 4, "data");
        writeInt32BE(atom, 8, flags);   // version(0) + flags
        // reserved 4 bytes = 0 (already zero)
        System.arraycopy(payload, 0, atom, 16, payload.length);
        return atom;
    }

    /**
     * 构建容器 atom：[size][type][children...]
     */
    private static byte[] boxAtom(String type, byte[]... children) {
        int childrenSize = 0;
        for (byte[] child : children) {
            if (child != null) childrenSize += child.length;
        }
        int size = 8 + childrenSize;
        byte[] atom = new byte[size];
        writeInt32BE(atom, 0, size);
        writeType(atom, 4, type);
        int offset = 8;
        for (byte[] child : children) {
            if (child != null) {
                System.arraycopy(child, 0, atom, offset, child.length);
                offset += child.length;
            }
        }
        return atom;
    }

    // ==================== chunk offset 修正 ====================

    /**
     * 递归修正 moov 内所有 stco/co64 的 offset 值
     * 当在 moov 前面插入了数据，mdat 位置后移，chunk offset 需要加上 delta
     */
    private static void fixChunkOffsets(byte[] data, int start, int end, long delta) {
        int pos = start;
        while (pos + 8 <= end) {
            int size = readInt32BE(data, pos);
            String type = readType(data, pos + 4);

            if ("stco".equals(type) && pos + 16 <= end) {
                fixStco(data, pos, delta);
            } else if ("co64".equals(type) && pos + 16 <= end) {
                fixCo64(data, pos, delta);
            } else if (isContainerAtom(type)) {
                int childStart = pos + 8;
                // meta 是 full box，多 4 字节 ver+flags
                if ("meta".equals(type)) childStart += 4;
                if (size > 8) {
                    fixChunkOffsets(data, childStart, pos + size, delta);
                }
            }

            if (size <= 0) break;
            pos += size;
        }
    }

    /**
     * 修正 stco atom 中的偏移量
     * stco: [size][stco][ver+flags][entry_count][offset1][offset2]...
     */
    private static void fixStco(byte[] data, int pos, long delta) {
        try {
            int entryCount = readInt32BE(data, pos + 12);
            for (int i = 0; i < entryCount; i++) {
                int offsetPos = pos + 16 + i * 4;
                if (offsetPos + 4 > data.length) break;
                long oldOffset = readInt32BE(data, offsetPos) & 0xFFFFFFFFL;
                writeInt32BE(data, offsetPos, (int) (oldOffset + delta));
            }
            Log.d(TAG, "修正stco: " + entryCount + " entries, delta=" + delta);
        } catch (Exception e) {
            Log.w(TAG, "修正stco失败: " + e.getMessage());
        }
    }

    /**
     * 修正 co64 atom 中的偏移量
     * co64: [size][co64][ver+flags][entry_count][offset1(8bytes)][offset2(8bytes)]...
     */
    private static void fixCo64(byte[] data, int pos, long delta) {
        try {
            int entryCount = readInt32BE(data, pos + 12);
            for (int i = 0; i < entryCount; i++) {
                int offsetPos = pos + 16 + i * 8;
                if (offsetPos + 8 > data.length) break;
                long oldOffset = readInt64BE(data, offsetPos);
                writeInt64BE(data, offsetPos, oldOffset + delta);
            }
            Log.d(TAG, "修正co64: " + entryCount + " entries, delta=" + delta);
        } catch (Exception e) {
            Log.w(TAG, "修正co64失败: " + e.getMessage());
        }
    }

    /** 判断是否为容器 atom（包含子 atom） */
    private static boolean isContainerAtom(String type) {
        switch (type) {
            case "moov": case "trak": case "mdia": case "minf":
            case "stbl": case "edts": case "udta": case "meta":
            case "moof": case "traf": case "mvex":
                return true;
            default:
                return false;
        }
    }

    // ==================== atom 查找 ====================

    /**
     * 在指定范围内查找直接子 atom
     * @return 子 atom 在 data 中的起始位置，-1=未找到
     */
    private static int findChildAtom(byte[] data, int start, int end, String targetType) {
        int pos = start;
        while (pos + 8 <= end) {
            int size = readInt32BE(data, pos);
            String type = readType(data, pos + 4);
            if (type.equals(targetType)) return pos;
            if (size <= 0) break;
            pos += size;
        }
        return -1;
    }

    // ==================== 字节数组工具 ====================

    /** 读取文件到 byte[] */
    private static byte[] readFile(File file) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = fis.read(data, read, data.length - read);
                if (n <= 0) break;
                read += n;
            }
            fis.close();
            return data;
        } catch (Exception e) {
            Log.e(TAG, "读取文件失败: " + e.getMessage());
            return null;
        }
    }

    /** 提取子数组 */
    private static byte[] subarray(byte[] src, int offset, int length) {
        byte[] dst = new byte[length];
        System.arraycopy(src, offset, dst, 0, length);
        return dst;
    }

    /**
     * 替换/插入：将 src 中 [from, to) 替换为 replacement
     * 如果 from==to，则是插入
     */
    private static byte[] splice(byte[] src, int from, int to, byte[] replacement) {
        int newLen = src.length - (to - from) + replacement.length;
        byte[] result = new byte[newLen];
        System.arraycopy(src, 0, result, 0, from);
        System.arraycopy(replacement, 0, result, from, replacement.length);
        System.arraycopy(src, to, result, from + replacement.length, src.length - to);
        return result;
    }

    /** 读大端序 32 位整数 */
    private static int readInt32BE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /** 写大端序 32 位整数 */
    private static void writeInt32BE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >> 24);
        data[offset + 1] = (byte) (value >> 16);
        data[offset + 2] = (byte) (value >> 8);
        data[offset + 3] = (byte) value;
    }

    /** 读大端序 64 位整数 */
    private static long readInt64BE(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 56)
                | ((long) (data[offset + 1] & 0xFF) << 48)
                | ((long) (data[offset + 2] & 0xFF) << 40)
                | ((long) (data[offset + 3] & 0xFF) << 32)
                | ((long) (data[offset + 4] & 0xFF) << 24)
                | ((long) (data[offset + 5] & 0xFF) << 16)
                | ((long) (data[offset + 6] & 0xFF) << 8)
                | (long) (data[offset + 7] & 0xFF);
    }

    /** 写大端序 64 位整数 */
    private static void writeInt64BE(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >> 56);
        data[offset + 1] = (byte) (value >> 48);
        data[offset + 2] = (byte) (value >> 40);
        data[offset + 3] = (byte) (value >> 32);
        data[offset + 4] = (byte) (value >> 24);
        data[offset + 5] = (byte) (value >> 16);
        data[offset + 6] = (byte) (value >> 8);
        data[offset + 7] = (byte) value;
    }

    /** 读 4 字节 atom 类型为字符串 */
    private static String readType(byte[] data, int offset) {
        return new String(data, offset, 4);
    }

    /** 写 4 字节字符串为 atom 类型 */
    private static void writeType(byte[] data, int offset, String type) {
        byte[] bytes = type.getBytes();
        System.arraycopy(bytes, 0, data, offset, 4);
    }
}
