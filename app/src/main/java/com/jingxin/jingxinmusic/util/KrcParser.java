package com.jingxin.jingxinmusic.util;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KRC 歌词解析器
 * 解密酷狗专有的 KRC 格式，转换为逐字歌词数据
 */
public class KrcParser {
    
    // KRC 解密密钥（16个元素）
    private static final int[] KRC_KEYS = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105};
    
    /**
     * 解析 KRC 文件
     * @param krcFile KRC 文件
     * @return 歌词数据
     */
    public static LyricData parseKrcFile(File krcFile) {
        try {
            FileInputStream fis = new FileInputStream(krcFile);
            byte[] data = new byte[(int) krcFile.length()];
            fis.read(data);
            fis.close();
            
            // 解密
            String decrypted = decryptKrc(data);
            
            // 解析
            return parseKrcContent(decrypted);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 从 Base64 编码的 KRC 内容解析歌词（用于在线下载的歌词）
     * @param base64Content Base64 编码的 KRC 内容
     * @return 歌词数据
     */
    public static LyricData parseKrcFromBase64(String base64Content) {
        try {
            if (base64Content == null || base64Content.isEmpty()) {
                return null;
            }
            byte[] data = Base64.decode(base64Content, Base64.DEFAULT);
            return parseKrcFromBytes(data);
        } catch (Exception e) {
            android.util.Log.e("KrcParser", "Base64 解码失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从字节数组解析 KRC 内容
     * @param data KRC 原始字节数据（可能是解密后的，也可能是 API 返回的 zlib 压缩数据）
     * @return 歌词数据
     */
    public static LyricData parseKrcFromBytes(byte[] data) {
        try {
            // 先尝试解密（本地文件格式的 KRC 有 4 字节头部）
            if (data.length > 4 && new String(data, 0, 4, "UTF-8").startsWith("krc1")) {
                String decrypted = decryptKrc(data);
                return parseKrcContent(decrypted);
            } else {
                // API 返回的 Base64 解码后已经是 zlib 压缩数据，直接解压
                Inflater inflater = new Inflater();
                inflater.setInput(data);
                byte[] output = new byte[1024 * 1024];
                int length = inflater.inflate(output);
                inflater.end();
                String content = new String(output, 0, length, "UTF-8");
                return parseKrcContent(content);
            }
        } catch (Exception e) {
            android.util.Log.e("KrcParser", "从字节数组解析失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 将 Base64 KRC 内容保存为本地文件
     * @param base64Content Base64 编码的 KRC 内容
     * @param targetFile 目标文件
     * @return 是否成功
     */
    public static boolean saveKrcFromBase64(String base64Content, File targetFile) {
        try {
            if (base64Content == null || base64Content.isEmpty()) return false;
            byte[] data = Base64.decode(base64Content, Base64.DEFAULT);
            FileOutputStream fos = new FileOutputStream(targetFile);
            fos.write(data);
            fos.close();
            return true;
        } catch (Exception e) {
            android.util.Log.e("KrcParser", "保存 KRC 文件失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 解密 KRC 数据
     */
    private static String decryptKrc(byte[] data) {
        try {
            // XOR 解密
            byte[] decrypted = new byte[data.length - 4];
            for (int i = 4; i < data.length; i++) {
                decrypted[i - 4] = (byte) (data[i] ^ KRC_KEYS[(i - 4) % 16]);
            }
            
            // zlib 解压缩
            Inflater inflater = new Inflater();
            inflater.setInput(decrypted);
            byte[] output = new byte[1024 * 1024]; // 1MB buffer
            int length = inflater.inflate(output);
            inflater.end();
            
            return new String(output, 0, length, "UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 解析解密后的 KRC 内容
     */
    private static LyricData parseKrcContent(String content) {
        android.util.Log.d("KrcParser", "开始解析 KRC 内容，长度: " + (content != null ? content.length() : 0));
        if (content == null || content.isEmpty()) {
            android.util.Log.d("KrcParser", "KRC 内容为空，返回 null");
            return null;
        }
        
        LyricData lyricData = new LyricData();
        List<LyricLine> lines = new ArrayList<>();
        
        // 移除 Windows 格式的换行符 \r，避免正则表达式匹配失败
        String[] contentLines = content.replace("\r", "").split("\n");
        android.util.Log.d("KrcParser", "分割行数: " + contentLines.length);
        
        // 打印所有行内容，确认格式（限制最多20行）
        for (int i = 0; i < Math.min(20, contentLines.length); i++) {
            android.util.Log.d("KrcParser", "第" + (i+1) + "行: " + contentLines[i]);
        }
        
        for (String line : contentLines) {
            // 解析头部标签
            if (line.startsWith("[ti:")) {
                lyricData.title = extractTagValue(line, "ti");
                android.util.Log.d("KrcParser", "歌曲标题: " + lyricData.title);
            } else if (line.startsWith("[ar:")) {
                lyricData.artist = extractTagValue(line, "ar");
                android.util.Log.d("KrcParser", "歌手: " + lyricData.artist);
            } else if (line.startsWith("[al:")) {
                lyricData.album = extractTagValue(line, "al");
                android.util.Log.d("KrcParser", "专辑: " + lyricData.album);
            } else if (line.startsWith("[total:")) {
                // 总时长（毫秒）
                lyricData.duration = extractNumberValue(line, "total");
            }
            
            // 解析歌词行: [start,duration]<offset,duration,color>text...
            Pattern linePattern = Pattern.compile("\\[(\\d+),(\\d+)\\](.*)");
            Matcher matcher = linePattern.matcher(line);
            
            if (matcher.matches()) {
                android.util.Log.d("KrcParser", "匹配歌词行: " + line);
                long startTime = Long.parseLong(matcher.group(1));
                long duration = Long.parseLong(matcher.group(2));
                String lyricContent = matcher.group(3);
                
                // 解析逐字时间标签
                List<LyricWord> words = parseWords(lyricContent, startTime);
                
                if (!words.isEmpty()) {
                    LyricLine lyricLine = new LyricLine();
                    lyricLine.startTime = startTime;
                    lyricLine.duration = duration;
                    lyricLine.words = words;
                    
                    // 构建完整文本
                    StringBuilder sb = new StringBuilder();
                    for (LyricWord word : words) {
                        sb.append(word.text);
                    }
                    lyricLine.text = sb.toString();
                    
                    lines.add(lyricLine);
                }
            }
        }
        
        lyricData.lines = lines;
        return lyricData;
    }
    
    /**
     * 解析逐字时间标签
     * 格式: <offset,duration,color>text
     */
    private static List<LyricWord> parseWords(String content, long lineStartTime) {
        List<LyricWord> words = new ArrayList<>();
        android.util.Log.d("KrcParser", "解析逐字标签，内容: " + content);
        
        Pattern wordPattern = Pattern.compile("<(\\d+),(\\d+),(\\d+)>([^<]*)");
        Matcher matcher = wordPattern.matcher(content);
        
        int matchCount = 0;
        while (matcher.find()) {
            matchCount++;
            LyricWord word = new LyricWord();
            word.offset = Long.parseLong(matcher.group(1));  // 相对于行开始时间的偏移
            word.duration = Long.parseLong(matcher.group(2)); // 该字的持续时间
            word.color = Integer.parseInt(matcher.group(3));  // 颜色标记
            word.text = matcher.group(4);                      // 文字内容
            
            word.startTime = lineStartTime + word.offset;      // 绝对开始时间
            
            words.add(word);
        }
        
        android.util.Log.d("KrcParser", "匹配到的逐字标签数: " + matchCount);
        return words;
    }
    
    /**
     * 提取标签值
     */
    private static String extractTagValue(String line, String tag) {
        Pattern pattern = Pattern.compile("\\[" + tag + ":([^\\]]*)\\]");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
    
    /**
     * 提取数字值
     */
    private static long extractNumberValue(String line, String tag) {
        Pattern pattern = Pattern.compile("\\[" + tag + ":([^\\]]*)\\]");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1).trim());
        }
        return 0;
    }
    
    /**
     * 歌词数据模型
     */
    public static class LyricData {
        public String title;        // 歌曲名
        public String artist;       // 歌手
        public String album;        // 专辑
        public long duration;       // 总时长（毫秒）
        public List<LyricLine> lines; // 歌词行列表
    }
    
    /**
     * 歌词行模型
     */
    public static class LyricLine {
        public long startTime;      // 行开始时间（毫秒）
        public long duration;       // 行持续时间
        public String text;         // 完整文本
        public List<LyricWord> words; // 逐字数据
    }
    
    /**
     * 歌词单词模型
     */
    public static class LyricWord {
        public long offset;         // 相对偏移
        public long duration;       // 持续时间
        public int color;           // 颜色标记
        public String text;         // 文字
        public long startTime;      // 绝对开始时间
    }
}