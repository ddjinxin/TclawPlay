package com.jingxin.jingxinmusic.util;

import android.graphics.Color;

/**
 * 一套完整的配色方案（包含白天和夜间）
 * 每个区域有渐变起始色和终止色，支持色彩渐变
 * 预定义3种风格：翡翠夜光、碧玉温润、清新薄荷
 */
public class ThemeStyle {

    public final String name;  // 风格名称

    // 夜间 — 渐变起始色
    public final int nightBg;
    public final int nightBgEnd;           // 主背景渐变终止
    public final int nightItemBg;
    public final int nightBarBg;
    public final int nightBarBgEnd;        // 标题栏渐变终止
    public final int nightOverlay;
    public final int nightCardBg;
    public final int nightCardBgEnd;       // 卡片渐变终止
    public final int nightCardPressed;
    public final int nightTextPrimary;
    public final int nightTextSecondary;
    public final int nightTextTertiary;
    public final int nightTextCopyright;
    public final int nightDivider;
    public final int nightTabActive;
    public final int nightTabInactive;
    public final int nightTabIndicator;
    public final int nightLyricNormal;
    public final int nightLyricCurrent;
    public final int nightLyricFaded;
    public final int nightCoverTint;
    public final int nightFolderBorder;
    public final int nightMiniBg;
    public final int nightMiniBgEnd;       // 迷你播放条渐变终止

    // 白天 — 渐变起始色
    public final int dayBg;
    public final int dayBgEnd;             // 主背景渐变终止
    public final int dayItemBg;
    public final int dayBarBg;
    public final int dayBarBgEnd;          // 标题栏渐变终止
    public final int dayMiniBg;
    public final int dayMiniBgEnd;         // 迷你播放条渐变终止
    public final int dayCardBg;
    public final int dayCardBgEnd;         // 卡片渐变终止
    public final int dayCardPressed;
    public final int dayTextPrimary;
    public final int dayTextSecondary;
    public final int dayTextTertiary;
    public final int dayTextCopyright;
    public final int dayDivider;
    public final int dayTabActive;
    public final int dayTabInactive;
    public final int dayTabIndicator;
    public final int dayLyricNormal;
    public final int dayLyricCurrent;
    public final int dayLyricFaded;
    public final int dayCoverTint;
    public final int dayFolderBorder;

    // 通用
    public final int lyricHighlight;
    public final int dominantColorFallback;
    public final int emptyStateText;

    private ThemeStyle(Builder b) {
        this.name = b.name;
        nightBg = b.nightBg; nightBgEnd = b.nightBgEnd; nightItemBg = b.nightItemBg;
        nightBarBg = b.nightBarBg; nightBarBgEnd = b.nightBarBgEnd;
        nightOverlay = b.nightOverlay;
        nightCardBg = b.nightCardBg; nightCardBgEnd = b.nightCardBgEnd; nightCardPressed = b.nightCardPressed;
        nightTextPrimary = b.nightTextPrimary; nightTextSecondary = b.nightTextSecondary;
        nightTextTertiary = b.nightTextTertiary; nightTextCopyright = b.nightTextCopyright;
        nightDivider = b.nightDivider; nightTabActive = b.nightTabActive; nightTabInactive = b.nightTabInactive;
        nightTabIndicator = b.nightTabIndicator; nightLyricNormal = b.nightLyricNormal;
        nightLyricCurrent = b.nightLyricCurrent; nightLyricFaded = b.nightLyricFaded;
        nightCoverTint = b.nightCoverTint; nightFolderBorder = b.nightFolderBorder;
        nightMiniBg = b.nightMiniBg; nightMiniBgEnd = b.nightMiniBgEnd;
        dayBg = b.dayBg; dayBgEnd = b.dayBgEnd; dayItemBg = b.dayItemBg;
        dayBarBg = b.dayBarBg; dayBarBgEnd = b.dayBarBgEnd;
        dayMiniBg = b.dayMiniBg; dayMiniBgEnd = b.dayMiniBgEnd;
        dayCardBg = b.dayCardBg; dayCardBgEnd = b.dayCardBgEnd; dayCardPressed = b.dayCardPressed;
        dayTextPrimary = b.dayTextPrimary; dayTextSecondary = b.dayTextSecondary;
        dayTextTertiary = b.dayTextTertiary; dayTextCopyright = b.dayTextCopyright;
        dayDivider = b.dayDivider; dayTabActive = b.dayTabActive; dayTabInactive = b.dayTabInactive;
        dayTabIndicator = b.dayTabIndicator; dayLyricNormal = b.dayLyricNormal;
        dayLyricCurrent = b.dayLyricCurrent; dayLyricFaded = b.dayLyricFaded;
        dayCoverTint = b.dayCoverTint; dayFolderBorder = b.dayFolderBorder;
        lyricHighlight = b.lyricHighlight; dominantColorFallback = b.dominantColorFallback;
        emptyStateText = b.emptyStateText;
    }

    // ========== 预定义3种风格 ==========

    /** 方案一：春意盎然 — 夜间极黑+霓虹绿，白天酷狗国风（奶白薄荷+暖金黄点缀） */
    public static final ThemeStyle JADE_GLOW = new Builder("春意盎然")
        // 夜间 — 纯黑为底，底部微微透出春绿（如远方萤火）
        .nightBg("#0A0A0E").nightBgEnd("#0A120A")           // 主背景：极深蓝黑→微绿黑
        .nightItemBg("#0E0E12")
        .nightBarBg("#0E0E14").nightBarBgEnd("#12121C")      // 标题栏：近黑微蓝
        .nightOverlay("#55000000")
        .nightCardBg("#14141E").nightCardBgEnd("#1A1A28")    // 卡片：微蓝灰对角
        .nightCardPressed("#1E1E2C")
        .nightMiniBg("#0E0E14").nightMiniBgEnd("#14141E")    // 迷你播放条
        .nightTextPrimary("#FFFFFF").nightTextSecondary("#8888AA").nightTextTertiary("#555570").nightTextCopyright("#33334A")
        .nightDivider("#18182A").nightTabActive("#FFFFFF").nightTabInactive("#666688").nightTabIndicator("#31C27C")
        .nightLyricNormal("#FFFFFF").nightLyricCurrent("#FFEB3B").nightLyricFaded("#AAFFFFFF")
        .nightCoverTint("#141422").nightFolderBorder("#24243A")
        // 白天 — 酷狗国风：奶白薄荷底+暖金黄点缀，清新治愈
        .dayBg("#F0F9EF").dayBgEnd("#C8E9C6")               // 主背景：奶白薄荷→柔青绿
        .dayItemBg("#F5FBF4")                                // 列表项：极浅绿白
        .dayBarBg("#EFF8EE").dayBarBgEnd("#E4F3E2")          // 标题栏：薄荷白微渐变
        .dayMiniBg("#E8F4E6").dayMiniBgEnd("#D8ECD6")        // 迷你播放条：浅绿
        .dayCardBg("#E0F0E8").dayCardBgEnd("#D0E8D8")        // 卡片：半透明白绿
        .dayCardPressed("#C8E0CC")
        .dayTextPrimary("#1E3A22").dayTextSecondary("#3D6B48").dayTextTertiary("#7AAA82").dayTextCopyright("#A0CCA4")
        .dayDivider("#D8ECD0").dayTabActive("#1E3A22").dayTabInactive("#6AAA74").dayTabIndicator("#E8B44C")
        .dayLyricNormal("#333333").dayLyricCurrent("#FFEB3B").dayLyricFaded("#AA333333")
        .dayCoverTint("#8CBF94").dayFolderBorder("#A8D4B0")
        // 通用
        .lyricHighlight("#FFEB3B").dominantColorFallback("#CCF5F5F5").emptyStateText("#666666")
        .build();

    /** 方案二：碧玉温润 — 暗碧玉质感，温润中国风 */
    public static final ThemeStyle JADE_WARM = new Builder("碧玉温润")
        // 夜间 — 从墨玉到碧翠的渐变
        .nightBg("#0A0E0C").nightBgEnd("#101C16")            // 主背景：墨玉黑→深翠
        .nightItemBg("#0E140F")
        .nightBarBg("#121A16").nightBarBgEnd("#182420")       // 标题栏
        .nightOverlay("#55000000")
        .nightCardBg("#16221C").nightCardBgEnd("#1E3028")     // 卡片
        .nightCardPressed("#243830")
        .nightMiniBg("#121A16").nightMiniBgEnd("#182420")     // 迷你播放条
        .nightTextPrimary("#D8ECD4").nightTextSecondary("#6A9868").nightTextTertiary("#466A44").nightTextCopyright("#2A4A28")
        .nightDivider("#1C2C20").nightTabActive("#D8ECD4").nightTabInactive("#4A7A48").nightTabIndicator("#66BB6A")
        .nightLyricNormal("#FFFFFF").nightLyricCurrent("#FFEB3B").nightLyricFaded("#AAFFFFFF")
        .nightCoverTint("#122818").nightFolderBorder("#1E4428")
        // 白天 — 从暖米白到温绿灰的渐变
        .dayBg("#EDEAE4").dayBgEnd("#D4D0C6")               // 主背景：暖米白→暖灰
        .dayItemBg("#F4F2EC")
        .dayBarBg("#C8C4B8").dayBarBgEnd("#AEA89A")          // 标题栏
        .dayMiniBg("#C8C4B8").dayMiniBgEnd("#AEA89A")        // 迷你播放条
        .dayCardBg("#B0BCa4").dayCardBgEnd("#98A88E")        // 卡片
        .dayCardPressed("#8C9C82")
        .dayTextPrimary("#263226").dayTextSecondary("#4A6448").dayTextTertiary("#6A8468").dayTextCopyright("#8AA688")
        .dayDivider("#9AAE94").dayTabActive("#263226").dayTabInactive("#5A7A58").dayTabIndicator("#388E3C")
        .dayLyricNormal("#333333").dayLyricCurrent("#FFEB3B").dayLyricFaded("#AA333333")
        .dayCoverTint("#4A7850").dayFolderBorder("#78A878")
        // 通用
        .lyricHighlight("#FFEB3B").dominantColorFallback("#CCF5F5F5").emptyStateText("#666666")
        .build();

    /** 方案三：清新薄荷 — 浅色调渐变，活泼明亮 */
    public static final ThemeStyle MINT_FRESH = new Builder("清新薄荷")
        // 夜间 — 从深墨到暗叶的渐变
        .nightBg("#0A100A").nightBgEnd("#141E14")            // 主背景：墨黑→暗叶
        .nightItemBg("#0E160E")
        .nightBarBg("#121C12").nightBarBgEnd("#1A281A")       // 标题栏
        .nightOverlay("#55000000")
        .nightCardBg("#1A2C1A").nightCardBgEnd("#243824")     // 卡片
        .nightCardPressed("#2A422A")
        .nightMiniBg("#121C12").nightMiniBgEnd("#1A281A")     // 迷你播放条
        .nightTextPrimary("#E0F4E0").nightTextSecondary("#5AA05A").nightTextTertiary("#3A6A3A").nightTextCopyright("#1E4A1E")
        .nightDivider("#182818").nightTabActive("#E0F4E0").nightTabInactive("#3A7A3A").nightTabIndicator("#81C784")
        .nightLyricNormal("#FFFFFF").nightLyricCurrent("#FFEB3B").nightLyricFaded("#AAFFFFFF")
        .nightCoverTint("#102810").nightFolderBorder("#1E441E")
        // 白天 — 从嫩薄荷白到浅草绿的渐变
        .dayBg("#E4F4E6").dayBgEnd("#B8E0BA")               // 主背景：薄荷白→浅草绿
        .dayItemBg("#ECF8EE")
        .dayBarBg("#A8D8AA").dayBarBgEnd("#88C88A")          // 标题栏
        .dayMiniBg("#A8D8AA").dayMiniBgEnd("#88C88A")        // 迷你播放条
        .dayCardBg("#90CC94").dayCardBgEnd("#70BC74")        // 卡片
        .dayCardPressed("#64B068")
        .dayTextPrimary("#1A3A1A").dayTextSecondary("#3A7A3A").dayTextTertiary("#5AA058").dayTextCopyright("#7AC07A")
        .dayDivider("#80C080").dayTabActive("#1A3A1A").dayTabInactive("#4A8A4A").dayTabIndicator("#43A047")
        .dayLyricNormal("#333333").dayLyricCurrent("#FFEB3B").dayLyricFaded("#AA333333")
        .dayCoverTint("#3A7A3A").dayFolderBorder("#60AA60")
        // 通用
        .lyricHighlight("#FFEB3B").dominantColorFallback("#CCF5F5F5").emptyStateText("#666666")
        .build();

    // ========== Builder ==========

    private static class Builder {
        String name;
        int nightBg, nightBgEnd, nightItemBg;
        int nightBarBg, nightBarBgEnd;
        int nightOverlay;
        int nightCardBg, nightCardBgEnd, nightCardPressed;
        int nightMiniBg, nightMiniBgEnd;
        int nightTextPrimary, nightTextSecondary, nightTextTertiary, nightTextCopyright;
        int nightDivider, nightTabActive, nightTabInactive, nightTabIndicator;
        int nightLyricNormal, nightLyricCurrent, nightLyricFaded;
        int nightCoverTint, nightFolderBorder;
        int dayBg, dayBgEnd, dayItemBg;
        int dayBarBg, dayBarBgEnd;
        int dayMiniBg, dayMiniBgEnd;
        int dayCardBg, dayCardBgEnd, dayCardPressed;
        int dayTextPrimary, dayTextSecondary, dayTextTertiary, dayTextCopyright;
        int dayDivider, dayTabActive, dayTabInactive, dayTabIndicator;
        int dayLyricNormal, dayLyricCurrent, dayLyricFaded;
        int dayCoverTint, dayFolderBorder;
        int lyricHighlight, dominantColorFallback, emptyStateText;

        Builder(String name) { this.name = name; }

        // 夜间
        Builder nightBg(String c) { nightBg = Color.parseColor(c); return this; }
        Builder nightBgEnd(String c) { nightBgEnd = Color.parseColor(c); return this; }
        Builder nightItemBg(String c) { nightItemBg = Color.parseColor(c); return this; }
        Builder nightBarBg(String c) { nightBarBg = Color.parseColor(c); return this; }
        Builder nightBarBgEnd(String c) { nightBarBgEnd = Color.parseColor(c); return this; }
        Builder nightOverlay(String c) { nightOverlay = Color.parseColor(c); return this; }
        Builder nightCardBg(String c) { nightCardBg = Color.parseColor(c); return this; }
        Builder nightCardBgEnd(String c) { nightCardBgEnd = Color.parseColor(c); return this; }
        Builder nightCardPressed(String c) { nightCardPressed = Color.parseColor(c); return this; }
        Builder nightMiniBg(String c) { nightMiniBg = Color.parseColor(c); return this; }
        Builder nightMiniBgEnd(String c) { nightMiniBgEnd = Color.parseColor(c); return this; }
        Builder nightTextPrimary(String c) { nightTextPrimary = Color.parseColor(c); return this; }
        Builder nightTextSecondary(String c) { nightTextSecondary = Color.parseColor(c); return this; }
        Builder nightTextTertiary(String c) { nightTextTertiary = Color.parseColor(c); return this; }
        Builder nightTextCopyright(String c) { nightTextCopyright = Color.parseColor(c); return this; }
        Builder nightDivider(String c) { nightDivider = Color.parseColor(c); return this; }
        Builder nightTabActive(String c) { nightTabActive = Color.parseColor(c); return this; }
        Builder nightTabInactive(String c) { nightTabInactive = Color.parseColor(c); return this; }
        Builder nightTabIndicator(String c) { nightTabIndicator = Color.parseColor(c); return this; }
        Builder nightLyricNormal(String c) { nightLyricNormal = Color.parseColor(c); return this; }
        Builder nightLyricCurrent(String c) { nightLyricCurrent = Color.parseColor(c); return this; }
        Builder nightLyricFaded(String c) { nightLyricFaded = Color.parseColor(c); return this; }
        Builder nightCoverTint(String c) { nightCoverTint = Color.parseColor(c); return this; }
        Builder nightFolderBorder(String c) { nightFolderBorder = Color.parseColor(c); return this; }

        // 白天
        Builder dayBg(String c) { dayBg = Color.parseColor(c); return this; }
        Builder dayBgEnd(String c) { dayBgEnd = Color.parseColor(c); return this; }
        Builder dayItemBg(String c) { dayItemBg = Color.parseColor(c); return this; }
        Builder dayBarBg(String c) { dayBarBg = Color.parseColor(c); return this; }
        Builder dayBarBgEnd(String c) { dayBarBgEnd = Color.parseColor(c); return this; }
        Builder dayMiniBg(String c) { dayMiniBg = Color.parseColor(c); return this; }
        Builder dayMiniBgEnd(String c) { dayMiniBgEnd = Color.parseColor(c); return this; }
        Builder dayCardBg(String c) { dayCardBg = Color.parseColor(c); return this; }
        Builder dayCardBgEnd(String c) { dayCardBgEnd = Color.parseColor(c); return this; }
        Builder dayCardPressed(String c) { dayCardPressed = Color.parseColor(c); return this; }
        Builder dayTextPrimary(String c) { dayTextPrimary = Color.parseColor(c); return this; }
        Builder dayTextSecondary(String c) { dayTextSecondary = Color.parseColor(c); return this; }
        Builder dayTextTertiary(String c) { dayTextTertiary = Color.parseColor(c); return this; }
        Builder dayTextCopyright(String c) { dayTextCopyright = Color.parseColor(c); return this; }
        Builder dayDivider(String c) { dayDivider = Color.parseColor(c); return this; }
        Builder dayTabActive(String c) { dayTabActive = Color.parseColor(c); return this; }
        Builder dayTabInactive(String c) { dayTabInactive = Color.parseColor(c); return this; }
        Builder dayTabIndicator(String c) { dayTabIndicator = Color.parseColor(c); return this; }
        Builder dayLyricNormal(String c) { dayLyricNormal = Color.parseColor(c); return this; }
        Builder dayLyricCurrent(String c) { dayLyricCurrent = Color.parseColor(c); return this; }
        Builder dayLyricFaded(String c) { dayLyricFaded = Color.parseColor(c); return this; }
        Builder dayCoverTint(String c) { dayCoverTint = Color.parseColor(c); return this; }
        Builder dayFolderBorder(String c) { dayFolderBorder = Color.parseColor(c); return this; }

        Builder lyricHighlight(String c) { lyricHighlight = Color.parseColor(c); return this; }
        Builder dominantColorFallback(String c) { dominantColorFallback = Color.parseColor(c); return this; }
        Builder emptyStateText(String c) { emptyStateText = Color.parseColor(c); return this; }

        ThemeStyle build() { return new ThemeStyle(this); }
    }
}
