package com.jingxin.jingxinmusic.util;

import android.graphics.Color;

/**
 * 一套完整的配色方案（包含白天和夜间）
 * 每个区域有渐变起始色和终止色，支持色彩渐变
 * 预定义4种风格：春意盎然、蔚蓝天地、万紫千红、高级灰
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
        .nightBg("#0A0E0A").nightBgEnd("#0A120A")           // 主背景：极深绿黑→微绿黑
        .nightItemBg("#0E120E")
        .nightBarBg("#0E140E").nightBarBgEnd("#121C12")      // 标题栏：近黑微绿
        .nightOverlay("#55000000")
        .nightCardBg("#141E14").nightCardBgEnd("#1A281A")    // 卡片：微绿灰对角
        .nightCardPressed("#1E2C1E")
        .nightMiniBg("#0E140E").nightMiniBgEnd("#141E14")    // 迷你播放条
        .nightTextPrimary("#FFFFFF").nightTextSecondary("#88AA88").nightTextTertiary("#557055").nightTextCopyright("#334A33")
        .nightDivider("#182A18").nightTabActive("#FFFFFF").nightTabInactive("#668866").nightTabIndicator("#31C27C")
        .nightLyricNormal("#FFFFFF").nightLyricCurrent("#FFEB3B").nightLyricFaded("#AAFFFFFF")
        .nightCoverTint("#142214").nightFolderBorder("#243A24")
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

    /** 方案二：蔚蓝天地 — 夜间极黑+深蓝氛围+霓虹蓝点缀，白天暖白+清澈温蓝+靛蓝指示 */
    public static final ThemeStyle JADE_WARM = new Builder("蔚蓝天地")
        // 夜间 — 极黑为底，深蓝有存在感，不是灰蒙蒙
        .nightBg("#0A0C14").nightBgEnd("#0A1020")            // 主背景：极深蓝黑→深蓝夜
        .nightItemBg("#0C0E18")
        .nightBarBg("#0E1020").nightBarBgEnd("#121828")       // 标题栏：近黑深蓝
        .nightOverlay("#55000000")
        .nightCardBg("#101830").nightCardBgEnd("#182244")     // 卡片：深蓝有质感
        .nightCardPressed("#1E2C50")
        .nightMiniBg("#0E1020").nightMiniBgEnd("#121828")     // 迷你播放条
        .nightTextPrimary("#D0E0F8").nightTextSecondary("#6888BB").nightTextTertiary("#3A5888").nightTextCopyright("#1E3860")
        .nightDivider("#141E38").nightTabActive("#D0E0F8").nightTabInactive("#4466AA").nightTabIndicator("#4499EE")
        .nightLyricNormal("#FFFFFF").nightLyricCurrent("#FFEB3B").nightLyricFaded("#AAFFFFFF")
        .nightCoverTint("#0C1830").nightFolderBorder("#142866")
        // 白天 — 暖白底+清澈温蓝，像晴空映在白玉上
        .dayBg("#ECF0FA").dayBgEnd("#D0DCF0")               // 主背景：奶白蓝→浅天蓝
        .dayItemBg("#F2F5FC")
        .dayBarBg("#DCE4F4").dayBarBgEnd("#C8D4EC")          // 标题栏：淡蓝白渐变
        .dayMiniBg("#DCE4F4").dayMiniBgEnd("#C8D4EC")        // 迷你播放条
        .dayCardBg("#CCD8F0").dayCardBgEnd("#B0C4E4")        // 卡片：温蓝渐变
        .dayCardPressed("#A0B8DC")
        .dayTextPrimary("#1A2A44").dayTextSecondary("#3A5A88").dayTextTertiary("#6888B0").dayTextCopyright("#90AAC8")
        .dayDivider("#B0C4E0").dayTabActive("#1A2A44").dayTabInactive("#5078AA").dayTabIndicator("#3366CC")
        .dayLyricNormal("#333333").dayLyricCurrent("#FFEB3B").dayLyricFaded("#AA333333")
        .dayCoverTint("#4A6698").dayFolderBorder("#6888BB")
        // 通用
        .lyricHighlight("#FFEB3B").dominantColorFallback("#CCD8ECF8").emptyStateText("#666666")
        .build();

    /** 方案三：万紫千红 — 夜间极黑+深紫氛围+品红点缀，白天暖白+薰衣草紫+玫红指示 */
    public static final ThemeStyle MINT_FRESH = new Builder("万紫千红")
        // 夜间 — 极黑为底，深紫有存在感
        .nightBg("#0E0A1A").nightBgEnd("#140E26")            // 主背景：极深紫黑→深紫夜
        .nightItemBg("#100C1E")
        .nightBarBg("#120E22").nightBarBgEnd("#1A142C")       // 标题栏：近黑深紫
        .nightOverlay("#55000000")
        .nightCardBg("#18102E").nightCardBgEnd("#221840")     // 卡片：深紫有质感
        .nightCardPressed("#2A1E4C")
        .nightMiniBg("#120E22").nightMiniBgEnd("#1A142C")     // 迷你播放条
        .nightTextPrimary("#E0D0F8").nightTextSecondary("#9078C0").nightTextTertiary("#584090").nightTextCopyright("#2E1C5A")
        .nightDivider("#1A1236").nightTabActive("#E0D0F8").nightTabInactive("#6048A8").nightTabIndicator("#CC44AA")
        .nightLyricNormal("#FFFFFF").nightLyricCurrent("#FFEB3B").nightLyricFaded("#AAFFFFFF")
        .nightCoverTint("#120C28").nightFolderBorder("#280E5C")
        // 白天 — 暖白底+薰衣草紫，像紫藤花海
        .dayBg("#F2ECFA").dayBgEnd("#DDD0F0")               // 主背景：紫薰白→淡紫
        .dayItemBg("#F6F0FC")
        .dayBarBg("#E4D8F2").dayBarBgEnd("#D0C0E4")          // 标题栏：薰衣草渐变
        .dayMiniBg("#E4D8F2").dayMiniBgEnd("#D0C0E4")        // 迷你播放条
        .dayCardBg("#D4C4EA").dayCardBgEnd("#BCA4DC")        // 卡片：温紫渐变
        .dayCardPressed("#AE90D0")
        .dayTextPrimary("#1A0E2E").dayTextSecondary("#4A2888").dayTextTertiary("#6848A8").dayTextCopyright("#8868C0")
        .dayDivider("#C0B0E0").dayTabActive("#1A0E2E").dayTabInactive("#6850A8").dayTabIndicator("#BB3388")
        .dayLyricNormal("#333333").dayLyricCurrent("#FFEB3B").dayLyricFaded("#AA333333")
        .dayCoverTint("#583898").dayFolderBorder("#7858B8")
        // 通用
        .lyricHighlight("#FFEB3B").dominantColorFallback("#CCF2ECFA").emptyStateText("#666666")
        .build();

    /** 方案四：高级灰 — 夜间深灰底+银白点缀，白天浅灰白底+炭黑点缀，灰的质感 */
    public static final ThemeStyle GRAY_PREMIUM = new Builder("高级灰")
        // 夜间 — 深灰底，层次分明，银白指示线是唯一亮色
        .nightBg("#111114").nightBgEnd("#16161C")            // 主背景：极深灰→深灰
        .nightItemBg("#141418")
        .nightBarBg("#16161A").nightBarBgEnd("#1E1E24")       // 标题栏：深灰渐变
        .nightOverlay("#55000000")
        .nightCardBg("#1E1E24").nightCardBgEnd("#282830")     // 卡片：中深灰，绒面质感
        .nightCardPressed("#32323A")
        .nightMiniBg("#16161A").nightMiniBgEnd("#1E1E24")     // 迷你播放条
        .nightTextPrimary("#F0F0F4").nightTextSecondary("#888898").nightTextTertiary("#5A5A6A").nightTextCopyright("#3A3A4A")
        .nightDivider("#222230").nightTabActive("#F0F0F4").nightTabInactive("#6A6A7E").nightTabIndicator("#E0E0E8")
        .nightLyricNormal("#FFFFFF").nightLyricCurrent("#FFEB3B").nightLyricFaded("#AAFFFFFF")
        .nightCoverTint("#1A1A22").nightFolderBorder("#2E2E3E")
        // 白天 — 浅灰白底，中灰层次，炭黑指示线
        .dayBg("#F4F4F6").dayBgEnd("#E0E0E6")               // 主背景：极浅灰白→浅灰
        .dayItemBg("#F8F8FA")
        .dayBarBg("#E8E8EE").dayBarBgEnd("#D8D8E0")          // 标题栏：浅灰渐变
        .dayMiniBg("#E8E8EE").dayMiniBgEnd("#D8D8E0")        // 迷你播放条
        .dayCardBg("#D4D4DC").dayCardBgEnd("#C0C0CA")        // 卡片：中灰渐变
        .dayCardPressed("#B0B0BA")
        .dayTextPrimary("#1A1A24").dayTextSecondary("#505068").dayTextTertiary("#78788C").dayTextCopyright("#A0A0B0")
        .dayDivider("#C0C0CC").dayTabActive("#1A1A24").dayTabInactive("#6E6E84").dayTabIndicator("#2A2A38")
        .dayLyricNormal("#333333").dayLyricCurrent("#FFEB3B").dayLyricFaded("#AA333333")
        .dayCoverTint("#6A6A80").dayFolderBorder("#8888A0")
        // 通用
        .lyricHighlight("#FFEB3B").dominantColorFallback("#CCF4F4F6").emptyStateText("#666666")
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
