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
}
