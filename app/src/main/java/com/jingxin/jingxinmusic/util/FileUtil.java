package com.jingxin.jingxinmusic.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * 文件读写工具类
 * 统一 HistoryManager、FavoriteManager、LyricFetcher 中重复的文件读写方法
 */
public class FileUtil {

    private static final String TAG = "FileUtil";

    /**
     * 读取文本文件全部内容（不带换行拼接）
     */
    public static String readFile(File file) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取文本文件全部内容（保留原始换行）
     */
    public static String readFileWithNewlines(File file) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "读取文件失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 写入文本文件
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
