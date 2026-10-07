package com.jingxin.jingxinmusic.util;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * REPLAYGAIN 增益标签读取器（零第三方依赖，仿 EmbeddedLyricReader 裸字节解析风格）
 *
 * 支持：
 * - MP3 ID3v2：TXXX 帧（description=REPLAYGAIN_TRACK_GAIN）与 RVA2 帧（RVA2 只存 track 增益）
 * - FLAC：VORBIS_COMMENT 的 REPLAYGAIN_TRACK_GAIN 字段
 *
 * 返回值统一为 track gain（dB），解析失败返回 Float.NaN（调用方走扫描兜底）。
 * 解析范围严格限制在标签区内，不读音频数据，几 MB 文件毫秒级完成。
 */
public class GainTagReader {

    /** 从音频文件读取 REPLAYGAIN track 增益（dB），无标签/解析失败返回 Float.NaN */
    public static float readTrackGain(File audioFile) {
        if (audioFile == null || !audioFile.exists()) return Float.NaN;
        String name = audioFile.getName().toLowerCase();
        try {
            if (name.endsWith(".flac")) {
                return readFlacGain(audioFile);
            }
            if (name.endsWith(".mp3")) {
                return readMp3Gain(audioFile);
            }
        } catch (Exception ignored) {
        }
        return Float.NaN;
    }

    // ==================== FLAC（VORBIS_COMMENT） ====================

    private static float readFlacGain(File file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') return Float.NaN;

            boolean last = false;
            while (!last) {
                int header = raf.read();
                if (header < 0) return Float.NaN;
                last = (header & 0x80) != 0;
                int blockType = header & 0x7F;
                int size = ((raf.read() & 0xFF) << 16) | ((raf.read() & 0xFF) << 8) | (raf.read() & 0xFF);
                if (blockType == 4) { // VORBIS_COMMENT
                    byte[] block = new byte[size];
                    raf.readFully(block);
                    return parseVorbisGain(block);
                }
                raf.seek(raf.getFilePointer() + size);
            }
        }
        return Float.NaN;
    }

    /** 在 VORBIS_COMMENT 里找 REPLAYGAIN_TRACK_GAIN=xx.xx dB */
    private static float parseVorbisGain(byte[] block) {
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
                    String key = entry.substring(0, eq).toUpperCase(Locale.US);
                    if ("REPLAYGAIN_TRACK_GAIN".equals(key)) {
                        return parseGainValue(entry.substring(eq + 1));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Float.NaN;
    }

    // ==================== MP3（ID3v2 TXXX / RVA2） ====================

    private static float readMp3Gain(File file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[10];
            raf.readFully(header);
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') return Float.NaN;
            int ver = header[3] & 0xFF;
            int tagSize = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14)
                    | ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);
            if (tagSize <= 0) return Float.NaN;
            if (ver != 3 && ver != 4) return Float.NaN; // 只支持 v2.3/v2.4（v2.2 无 TXXX）

            byte[] tag = new byte[tagSize];
            raf.readFully(tag);

            int p = 0;
            while (p + 10 <= tagSize) {
                if (tag[p] == 0) break; // padding
                int frameSize;
                if (ver == 4) {
                    frameSize = ((tag[p + 4] & 0x7F) << 21) | ((tag[p + 5] & 0x7F) << 14)
                            | ((tag[p + 6] & 0x7F) << 7) | (tag[p + 7] & 0x7F);
                } else {
                    frameSize = ((tag[p + 4] & 0xFF) << 24) | ((tag[p + 5] & 0xFF) << 16)
                            | ((tag[p + 6] & 0xFF) << 8) | (tag[p + 7] & 0xFF);
                }
                if (frameSize <= 0 || p + 10 + frameSize > tagSize) break;
                String id = new String(tag, p, 4, StandardCharsets.ISO_8859_1);
                if ("TXXX".equals(id)) {
                    float g = parseTxxxFrame(tag, p + 10, frameSize);
                    if (!Float.isNaN(g)) return g;
                } else if ("RVA2".equals(id)) {
                    float g = parseRva2Frame(tag, p + 10, frameSize);
                    if (!Float.isNaN(g)) return g;
                }
                p += 10 + frameSize;
            }
        }
        return Float.NaN;
    }

    /**
     * TXXX 帧：enc(1) + description(null 结尾) + value
     * 目标 description = REPLAYGAIN_TRACK_GAIN
     */
    private static float parseTxxxFrame(byte[] tag, int start, int size) {
        try {
            int enc = tag[start] & 0xFF;
            String charset = id3Charset(enc);
            int p = start + 1;
            // 跳过 description（null 结尾，区分宽窄编码）
            int descEnd = findNull(tag, p, start + size, charset);
            if (descEnd < 0) return Float.NaN;
            String desc = new String(tag, p, descEnd - p - (charset.startsWith("UTF-16") ? 2 : 1), charset);
            if (!"REPLAYGAIN_TRACK_GAIN".equalsIgnoreCase(desc.trim())) return Float.NaN;
            int valueLen = (start + size) - descEnd;
            if (valueLen <= 0) return Float.NaN;
            return parseGainValue(new String(tag, descEnd, valueLen, charset));
        } catch (Exception ignored) {
        }
        return Float.NaN;
    }

    /**
     * RVA2 帧：ident(null 结尾) + type(1，0=track) + volume(2字节定点 16.8) + peak(可选)
     * 增益 = volume/512 dB（LAME 约定）
     */
    private static float parseRva2Frame(byte[] tag, int start, int size) {
        try {
            int p = start;
            while (p < start + size && tag[p] != 0) p++; // 跳过 ident
            if (p >= start + size) return Float.NaN;
            p++; // null
            if (p + 3 > start + size) return Float.NaN;
            int type = tag[p] & 0xFF;
            if (type != 0) return Float.NaN; // 只要 track 增益
            int volume = ((tag[p + 1] & 0xFF) << 8) | (tag[p + 2] & 0xFF);
            return volume / 512f;
        } catch (Exception ignored) {
        }
        return Float.NaN;
    }

    // ==================== 共用工具 ====================

    /** 解析 "+3.12 dB" / "-4.50 dB" 格式，失败返回 NaN */
    static float parseGainValue(String value) {
        if (value == null) return Float.NaN;
        String s = value.trim().toUpperCase(Locale.US);
        int sp = s.indexOf(' '); // 去掉单位
        if (sp > 0) s = s.substring(0, sp);
        try {
            float v = Float.parseFloat(s);
            // 合理范围校验：REPLAYGAIN 增益通常在 ±30dB 内，异常值视为无效标签
            if (v > -30f && v < 30f) return v;
        } catch (Exception ignored) {
        }
        return Float.NaN;
    }

    private static String id3Charset(int enc) {
        switch (enc) {
            case 0: return "ISO-8859-1";
            case 1: return "UTF-16";
            case 2: return "UTF-16BE";
            default: return "UTF-8";
        }
    }

    /** 找 null 结尾（返回 null 之后的第一个字节位置；宽编码找 0x0000 对齐） */
    private static int findNull(byte[] tag, int start, int end, String charset) {
        if (charset.startsWith("UTF-16")) {
            for (int i = start; i + 1 < end; i += 2) {
                if (tag[i] == 0 && tag[i + 1] == 0) return i + 2;
            }
        } else {
            for (int i = start; i < end; i++) {
                if (tag[i] == 0) return i + 1;
            }
        }
        return -1;
    }

    private static int readInt32LE(byte[] bytes, int off) {
        return ((bytes[off + 3] & 0xFF) << 24) | ((bytes[off + 2] & 0xFF) << 16)
                | ((bytes[off + 1] & 0xFF) << 8) | (bytes[off] & 0xFF);
    }
}
