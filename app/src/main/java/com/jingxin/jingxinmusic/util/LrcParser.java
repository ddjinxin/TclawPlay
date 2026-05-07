package com.jingxin.jingxinmusic.util;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标准 LRC 歌词解析器
 * 解析 [mm:ss.xx] 或 [mm:ss.xxx] 时间标签 + 歌词文本
 */
public class LrcParser {

    private static final String TAG = "LrcParser";

    // 匹配 LRC 时间标签：[mm:ss.xx] 或 [mm:ss]
    private static final Pattern TIME_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]");
    // 匹配歌词元信息：[ti:xxx] [ar:xxx] [al:xxx]
    private static final Pattern META_PATTERN = Pattern.compile("\\[([a-zA-Z]+):(.+?)\\]");

    /**
     * 解析 LRC 格式歌词文本
     * @param lrcText LRC 歌词内容
     * @return LyricData（复用 KrcParser 的数据模型，words 为 null 表示逐行模式）
     */
    public static KrcParser.LyricData parse(String lrcText) {
        if (lrcText == null || lrcText.isEmpty()) return null;

        KrcParser.LyricData data = new KrcParser.LyricData();
        data.lines = new ArrayList<>();

        String[] lines = lrcText.split("\n");
        long prevTime = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 跳过元信息行
            Matcher metaMatcher = META_PATTERN.matcher(line);
            if (metaMatcher.matches()) {
                String key = metaMatcher.group(1).toLowerCase();
                String value = metaMatcher.group(2).trim();
                switch (key) {
                    case "ti": data.title = value; break;
                    case "ar": data.artist = value; break;
                    case "al": data.album = value; break;
                }
                continue;
            }

            // 提取时间标签
            List<Long> times = new ArrayList<>();
            Matcher timeMatcher = TIME_PATTERN.matcher(line);
            while (timeMatcher.find()) {
                int min = Integer.parseInt(timeMatcher.group(1));
                int sec = Integer.parseInt(timeMatcher.group(2));
                String msStr = timeMatcher.group(3);
                int ms = msStr != null ? Integer.parseInt(msStr) : 0;
                // 2位补3位（如 .12 → 120ms）
                if (msStr != null && msStr.length() <= 2) {
                    ms = ms * 10;
                }
                long timeMs = min * 60000L + sec * 1000L + ms;
                times.add(timeMs);
            }

            if (times.isEmpty()) continue;

            // 提取歌词文本（去掉所有时间标签）
            String text = TIME_PATTERN.matcher(line).replaceAll("").trim();

            for (long time : times) {
                KrcParser.LyricLine lyricLine = new KrcParser.LyricLine();
                lyricLine.startTime = time;
                lyricLine.duration = 0; // 后续计算
                lyricLine.text = text;
                lyricLine.words = null; // 标记为逐行模式
                data.lines.add(lyricLine);
            }
        }

        // 按时间排序
        data.lines.sort((a, b) -> Long.compare(a.startTime, b.startTime));

        // 计算每行持续时间
        for (int i = 0; i < data.lines.size(); i++) {
            if (i + 1 < data.lines.size()) {
                data.lines.get(i).duration = data.lines.get(i + 1).startTime - data.lines.get(i).startTime;
            } else {
                data.lines.get(i).duration = 5000; // 最后一行默认5秒
            }
        }

        Log.d(TAG, "LRC 解析完成，共 " + data.lines.size() + " 行");
        return data;
    }
}
