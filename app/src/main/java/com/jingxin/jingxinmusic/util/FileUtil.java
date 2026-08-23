package com.jingxin.jingxinmusic.util;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 文件读写工具类
 * 统一 HistoryManager、FavoriteManager、LyricFetcher 中重复的文件读写方法
 * 读取时自动识别编码：UTF-8（严格解码）优先，失败则回退 GBK，兼容本地 ANSI/GB2312 歌词
 */
public class FileUtil {

    private static final String TAG = "FileUtil";

    /**
     * 读取文本文件全部内容（不带换行拼接）
     * 自动识别编码：UTF-8 严格解码，失败回退 GBK
     */
    public static String readFile(File file) {
        String content = readText(file);
        if (content == null) return null;
        return content.replace("\n", "").replace("\r", "");
    }

    /**
     * 读取文本文件全部内容（保留原始换行）
     * 自动识别编码：UTF-8 严格解码，失败回退 GBK（兼容本地常见 ANSI/GB2312 歌词文件）
     */
    public static String readFileWithNewlines(File file) {
        return readText(file);
    }

    /**
     * 读取文件并自动识别编码（UTF-8 优先，失败回退 GBK），保留原始换行
     */
    private static String readText(File file) {
        byte[] bytes = readBytes(file);
        if (bytes == null) return null;

        // 去除 UTF-8 BOM
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }

        // 严格 UTF-8 解码：非法字节序列直接失败（避免把 GBK 误当成 UTF-8 产生乱码）
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            Log.d(TAG, "文件非 UTF-8 编码，回退 GBK 读取: " + file.getName());
        }

        // GBK 回退（兼容 GB2312/GBK/ANSI）
        try {
            CharsetDecoder gbk = Charset.forName("GBK").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            return gbk.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (Exception e) {
            Log.e(TAG, "GBK 解码失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 读取文件全部字节
     */
    private static byte[] readBytes(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            fis.close();
            return bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "读取文件失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 写入文本文件（UTF-8）
     */
    public static void writeFile(File file, String content) {
        try {
            file.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "写文件失败: " + e.getMessage());
        }
    }

    /**
     * 清理文件名中的非法字符
     * 替换 / \ : * ? " < > | 为 _，合并连续空格，去除首尾空白
     * @param name 原始文件名
     * @return 安全的文件名，null/空返回 "unknown"
     */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unknown";
        return name.replaceAll("[/\\\\:*?\"<>|]", "_")
                   .replaceAll("\\s+", " ")
                   .trim();
    }
}