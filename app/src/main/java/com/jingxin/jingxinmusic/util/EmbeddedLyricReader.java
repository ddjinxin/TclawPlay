package com.jingxin.jingxinmusic.util;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 内嵌歌词读取器（零第三方依赖）
 * M4A：moov.udta.meta.ilst 下的 ©lyr atom（与 M4aMetadataWriter 写入格式对应）
 * MP3：ID3v2 的 USLT（非同步歌词）/ SYLT（同步歌词）帧
 * FLAC：VORBIS_COMMENT 的 LYRICS 字段（作为 LRC 文本解析）
 */
public class EmbeddedLyricReader {

    /**
     * 从音频文件读取内嵌歌词
     * @return LRC 格式歌词文本（可直接交给 LrcParser），null 表示无内嵌歌词
     */
    public static String readLyric(File audioFile) {
        if (audioFile == null || !audioFile.exists()) return null;
        String name = audioFile.getName().toLowerCase();
        try {
            if (name.endsWith(".m4a") || name.endsWith(".mp4")) {
                return readM4aLyric(audioFile);
            }
            if (name.endsWith(".mp3")) {
                return readMp3Lyric(audioFile);
            }
            if (name.endsWith(".flac")) {
                return readFlacLyric(audioFile);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ==================== M4A（ISOBMFF） ====================

    /** 遍历顶层找 moov，再找 udta → meta → ilst → ©lyr */
    private static String readM4aLyric(File file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLen = raf.length();
            long pos = 0;
            while (pos + 8 <= fileLen) {
                raf.seek(pos);
                long size = readUInt32(raf);
                byte[] typeBytes = new byte[4];
                raf.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
                if (size == 1) {
                    // 64位 size，读 extended size
                    long extSize = raf.readLong();
                    size = extSize;
                } else if (size == 0) {
                    size = fileLen - pos; // 到文件尾
                }
                if (size < 8) break;

                if ("moov".equals(type)) {
                    byte[] moov = new byte[(int) (size - 8)];
                    raf.readFully(moov);
                    String lyric = findLyricInMoov(moov);
                    return lyric;
                }
                pos += size;
            }
        }
        return null;
    }

    /** 在 moov 字节内递归找 udta → meta → ilst → ©lyr 的 data payload */
    private static String findLyricInMoov(byte[] moov) {
        // moov 内部子 atom 从偏移0开始（不含 moov 自己的8字节头）
        int udtaPos = findChildAtom(moov, 0, moov.length, "udta");
        if (udtaPos < 0) return null;
        int udtaSize = readInt32BE(moov, udtaPos);
        int udtaBodyEnd = udtaPos + udtaSize;

        int metaPos = findChildAtom(moov, udtaPos + 8, udtaBodyEnd, "meta");
        if (metaPos < 0) return null;
        int metaSize = readInt32BE(moov, metaPos);
        // meta 是 fullbox：+4 字节 ver/flags
        int ilstPos = findChildAtom(moov, metaPos + 8 + 4, metaPos + metaSize, "ilst");
        if (ilstPos < 0) return null;
        int ilstSize = readInt32BE(moov, ilstPos);
        int ilstEnd = ilstPos + ilstSize;

        // 遍历 ilst 子项找 ©lyr
        int p = ilstPos + 8;
        while (p + 8 <= ilstEnd) {
            int itemSize = readInt32BE(moov, p);
            if (itemSize < 8) break;
            int b0 = moov[p + 4] & 0xFF;
            int b1 = moov[p + 5] & 0xFF;
            int b2 = moov[p + 6] & 0xFF;
            int b3 = moov[p + 7] & 0xFF;
            boolean isLyr = (b0 == 0xA9) && b1 == 'l' && b2 == 'y' && b3 == 'r';
            if (isLyr) {
                // ©lyr 项内：data atom（[size]['data'][ver+flags(4)][payload]）
                int dataPos = p + 8;
                if (dataPos + 16 <= p + itemSize) {
                    int dataSize = readInt32BE(moov, dataPos);
                    boolean isDataAtom = moov[dataPos + 4] == 'd' && moov[dataPos + 5] == 'a'
                            && moov[dataPos + 6] == 't' && moov[dataPos + 7] == 'a';
                    if (isDataAtom && dataSize >= 16) {
                        int payloadLen = Math.min(dataSize - 16, (p + itemSize) - (dataPos + 16));
                        if (payloadLen > 0) {
                            return new String(moov, dataPos + 16, payloadLen, StandardCharsets.UTF_8);
                        }
                    }
                }
                return null; // 有 ©lyr 项但结构异常
            }
            p += itemSize;
        }
        return null;
    }

    /** 在 bytes[start,end) 范围内找指定 type 的直接子 atom，返回其起始偏移（含头），-1 未找到 */
    private static int findChildAtom(byte[] bytes, int start, int end, String type) {
        int p = start;
        while (p + 8 <= end) {
            int size = readInt32BE(bytes, p);
            if (size < 8) break;
            boolean match = bytes[p + 4] == type.charAt(0) && bytes[p + 5] == type.charAt(1)
                    && bytes[p + 6] == type.charAt(2) && bytes[p + 7] == type.charAt(3);
            if (match) return p;
            p += size;
        }
        return -1;
    }

    // ==================== MP3（ID3v2 USLT/SYLT） ====================

    private static String readMp3Lyric(File file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[10];
            raf.readFully(header);
            // ID3v2 头："ID3" + ver + rev + flags + size(syncsafe)
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') return null;
            int ver = header[3] & 0xFF;
            int tagSize = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14)
                    | ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);
            int frameHeaderSize = (ver == 3 || ver == 4) ? 10 : 6;

            byte[] tag = new byte[tagSize];
            raf.readFully(tag);

            int p = 0;
            while (p + frameHeaderSize <= tagSize) {
                // 帧ID 4字节（v2.2 为3字节，此处只支持 v2.3/v2.4 的 USLT/SYLT）
                if (frameHeaderSize == 10) {
                    if (tag[p] == 0) break; // padding
                    int frameSize;
                    if (ver == 4) {
                        // v2.4 帧大小为 syncsafe
                        frameSize = ((tag[p + 4] & 0x7F) << 21) | ((tag[p + 5] & 0x7F) << 14)
                                | ((tag[p + 6] & 0x7F) << 7) | (tag[p + 7] & 0x7F);
                    } else {
                        // v2.3 帧大小为普通 int32
                        frameSize = ((tag[p + 4] & 0xFF) << 24) | ((tag[p + 5] & 0xFF) << 16)
                                | ((tag[p + 6] & 0xFF) << 8) | (tag[p + 7] & 0xFF);
                    }
                    if (frameSize <= 0 || p + 10 + frameSize > tagSize) break;
                    String id = new String(tag, p, 4, StandardCharsets.ISO_8859_1);
                    if ("USLT".equals(id) || "SYLT".equals(id)) {
                        return parseId3LyricFrame(tag, p + 10, frameSize, "SYLT".equals(id));
                    }
                    p += 10 + frameSize;
                } else {
                    // v2.2: 3字节ID + 3字节大小，UL T为 "ULT"
                    if (tag[p] == 0) break;
                    int frameSize = ((tag[p + 3] & 0xFF) << 16) | ((tag[p + 4] & 0xFF) << 8) | (tag[p + 5] & 0xFF);
                    if (frameSize <= 0 || p + 6 + frameSize > tagSize) break;
                    String id = new String(tag, p, 3, StandardCharsets.ISO_8859_1);
                    if ("ULT".equals(id) || "SLT".equals(id)) {
                        return parseId3LyricFrame(tag, p + 6, frameSize, "SLT".equals(id));
                    }
                    p += 6 + frameSize;
                }
            }
        }
        return null;
    }

    /**
     * 解析 USLT/SYLT 帧内容
     * USLT: enc(1) + lang(3) + descriptor(以 enc 对应的 null 结尾) + 歌词文本
     * SYLT: enc(1) + lang(3) + 时间戳格式(1) + 内容类型(1) + descriptor + 文本\0 时间\0 序列
     */
    private static String parseId3LyricFrame(byte[] tag, int dataStart, int frameSize, boolean isSylt) {
        int enc = tag[dataStart] & 0xFF;
        String charsetName = getCharset(enc);
        int p = dataStart + 1 + 3; // 跳过 enc + lang
        if (isSylt) p += 2; // 跳过时间戳格式 + 内容类型

        // 跳过 descriptor（null 结尾）
        int descEnd = findNull(tag, p, dataStart + frameSize, charsetName);
        if (descEnd < 0) return null;
        p = descEnd;

        if (!isSylt) {
            // USLT：剩余整体是歌词文本
            int len = (dataStart + frameSize) - p;
            if (len <= 0) return null;
            String text = decodeString(tag, p, len, charsetName);
            return text != null ? text.replace("\r\n", "\n") : null;
        }

        // SYLT：文本\0 时间(4字节) 重复
        StringBuilder sb = new StringBuilder();
        int end = dataStart + frameSize;
        while (p < end - 4) {
            int textEnd = findNull(tag, p, end, charsetName);
            if (textEnd < 0) break;
            String text = decodeString(tag, p, textEnd - p, charsetName);
            int timeMs = ((tag[textEnd + 1] & 0xFF) << 24) | ((tag[textEnd + 2] & 0xFF) << 16)
                    | ((tag[textEnd + 3] & 0xFF) << 8) | (tag[textEnd + 4] & 0xFF);
            // 转 LRC 时间标签 [mm:ss.xx]
            if (text != null && !text.isEmpty()) {
                sb.append('[').append(formatLrcTime(timeMs)).append(']').append(text).append('\n');
            }
            p = textEnd + 1 + 4; // null + 4字节时间
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String formatLrcTime(int ms) {
        if (ms < 0) ms = 0;
        int min = ms / 60000;
        int sec = (ms % 60000) / 1000;
        int cs = (ms % 1000) / 10;
        return String.format(java.util.Locale.US, "%02d:%02d.%02d", min, sec, cs);
    }

    private static String getCharset(int enc) {
        switch (enc) {
            case 0: return "ISO-8859-1";
            case 1: return "UTF-16";
            case 2: return "UTF-16BE";
            default: return "UTF-8";
        }
    }

    /** 找到 null 结尾（区分单/双字节编码） */
    private static int findNull(byte[] tag, int start, int end, String charset) {
        boolean wide = charset.startsWith("UTF-16");
        if (!wide) {
            for (int i = start; i < end; i++) {
                if (tag[i] == 0) return i + 1;
            }
        } else {
            // 双字节：找 0x00 0x00 对齐
            for (int i = start; i + 1 < end; i += 2) {
                if (tag[i] == 0 && tag[i + 1] == 0) return i + 2;
            }
        }
        return -1;
    }

    private static String decodeString(byte[] bytes, int off, int len, String charset) {
        try {
            return new String(bytes, off, len, charset);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== FLAC（VORBIS_COMMENT） ====================

    private static String readFlacLyric(File file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') return null;

            // 跳过 metadata blocks 找 VORBIS_COMMENT (type=4)
            boolean last = false;
            while (!last) {
                int header = raf.read();
                if (header < 0) return null;
                last = (header & 0x80) != 0;
                int blockType = header & 0x7F;
                int size = ((raf.read() & 0xFF) << 16) | ((raf.read() & 0xFF) << 8) | (raf.read() & 0xFF);
                if (blockType == 4) {
                    byte[] block = new byte[size];
                    raf.readFully(block);
                    return parseVorbisComment(block);
                }
                // 跳过非目标块
                raf.seek(raf.getFilePointer() + size);
            }
        }
        return null;
    }

    private static String parseVorbisComment(byte[] block) {
        try {
            int p = 0;
            long vendorLen = readInt32LE(block, p);
            p += 4 + (int) vendorLen;
            int count = readInt32LE(block, p);
            p += 4;
            for (int i = 0; i < count && p + 4 <= block.length; i++) {
                int len = readInt32LE(block, p);
                p += 4;
                if (len < 0 || p + len > block.length) break;
                String entry = new String(block, p, len, StandardCharsets.UTF_8);
                p += len;
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    String key = entry.substring(0, eq).toUpperCase();
                    if ("LYRICS".equals(key) || "UNSYNCEDLYRICS".equals(key) || "LYRIC".equals(key)) {
                        return entry.substring(eq + 1).replace("\r\n", "\n");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ==================== 基础工具 ====================

    private static long readUInt32(RandomAccessFile raf) throws Exception {
        return ((long) (raf.read() & 0xFF) << 24) | ((raf.read() & 0xFF) << 16)
                | ((raf.read() & 0xFF) << 8) | (raf.read() & 0xFF);
    }

    private static int readInt32BE(byte[] bytes, int off) {
        return ((bytes[off] & 0xFF) << 24) | ((bytes[off + 1] & 0xFF) << 16)
                | ((bytes[off + 2] & 0xFF) << 8) | (bytes[off + 3] & 0xFF);
    }

    private static int readInt32LE(byte[] bytes, int off) {
        return ((bytes[off + 3] & 0xFF) << 24) | ((bytes[off + 2] & 0xFF) << 16)
                | ((bytes[off + 1] & 0xFF) << 8) | (bytes[off] & 0xFF);
    }
}
