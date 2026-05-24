package com.jingxin.jingxinmusic.util;

import android.graphics.Color;

/**
 * 主题颜色统一管理
 * 所有白天/夜间颜色集中定义，各处通过 ThemeColors 引用
 * 避免硬编码颜色值散落在多个文件中
 * 
 * 品牌色：绿色系（#4CAF50为主色调）
 * 夜间：暗绿打底，亮绿强调
 * 白天：白绿打底，深绿强调
 */
public class ThemeColors {

    // ========== 夜间模式 ==========

    // 背景
    public static final int NIGHT_BG = Color.parseColor("#080C08");           // 主背景（暗绿底）
    public static final int NIGHT_ITEM_BG = Color.parseColor("#0C100C");     // 列表项背景（暗绿近黑）
    public static final int NIGHT_BAR_BG = Color.parseColor("#0E1A0E");      // 标题栏/Tab栏/迷你播放条背景（暗绿）
    public static final int NIGHT_OVERLAY = Color.parseColor("#55000000");   // 半透明黑色遮罩

    // 文字
    public static final int NIGHT_TEXT_PRIMARY = Color.parseColor("#FFFFFF");    // 主要文字（歌名/标题）
    public static final int NIGHT_TEXT_SECONDARY = Color.parseColor("#999999"); // 次要文字（歌手）
    public static final int NIGHT_TEXT_TERTIARY = Color.parseColor("#81C784");  // 时长/提示文字（浅绿）
    public static final int NIGHT_TEXT_COPYRIGHT = Color.parseColor("#444444"); // 版权/底部文字

    // 分割线
    public static final int NIGHT_DIVIDER = Color.parseColor("#1B2E1B");         // 暗绿分割线

    // Tab
    public static final int NIGHT_TAB_ACTIVE = Color.parseColor("#66BB6A");      // 激活Tab（亮绿）
    public static final int NIGHT_TAB_INACTIVE = Color.parseColor("#888888");    // 非激活Tab（灰色）
    public static final int NIGHT_TAB_INDICATOR = Color.parseColor("#4CAF50");   // Tab下划线（品牌绿）

    // 导航
    public static final int NIGHT_NAV_ICON = Color.parseColor("#66BB6A");        // 返回按钮/导航图标（亮绿）

    // 卡片
    public static final int NIGHT_CARD_PRESSED = Color.parseColor("#1B3A1B");    // 卡片按压（暗绿高亮）

    // 歌词
    public static final int NIGHT_LYRIC_NORMAL = Color.parseColor("#FFFFFF");
    public static final int NIGHT_LYRIC_CURRENT = Color.parseColor("#FFEB3B");  // 亮黄色
    public static final int NIGHT_LYRIC_FADED = Color.parseColor("#AAFFFFFF");  // 半透明白

    // ========== 白天模式 ==========

    // 背景
    public static final int DAY_BG = Color.parseColor("#F2F7F2");             // 主背景（微绿白）
    public static final int DAY_ITEM_BG = Color.parseColor("#FFFFFF");        // 列表项背景（白色）
    public static final int DAY_BAR_BG = Color.parseColor("#E8F0E8");         // 标题栏/Tab栏背景（浅绿灰）
    public static final int DAY_MINI_BG = Color.parseColor("#E8F0E8");        // 迷你播放条背景（浅绿灰）

    // 文字
    public static final int DAY_TEXT_PRIMARY = Color.parseColor("#333333");    // 主要文字（歌名/标题）
    public static final int DAY_TEXT_SECONDARY = Color.parseColor("#777777");  // 次要文字（歌手）
    public static final int DAY_TEXT_TERTIARY = Color.parseColor("#388E3C");   // 时长/提示文字（中绿）
    public static final int DAY_TEXT_COPYRIGHT = Color.parseColor("#AAAAAA");  // 版权/底部文字

    // 分割线
    public static final int DAY_DIVIDER = Color.parseColor("#C8E6C9");         // 淡绿分割线

    // Tab
    public static final int DAY_TAB_ACTIVE = Color.parseColor("#2E7D32");      // 激活Tab（深绿）
    public static final int DAY_TAB_INACTIVE = Color.parseColor("#999999");    // 非激活Tab（灰色）
    public static final int DAY_TAB_INDICATOR = Color.parseColor("#4CAF50");   // Tab下划线（品牌绿）

    // 导航
    public static final int DAY_NAV_ICON = Color.parseColor("#2E7D32");        // 返回按钮/导航图标（深绿）

    // 卡片
    public static final int DAY_CARD_PRESSED = Color.parseColor("#C8E6C9");    // 卡片按压（浅绿高亮）

    // 歌词
    public static final int DAY_LYRIC_NORMAL = Color.parseColor("#333333");
    public static final int DAY_LYRIC_CURRENT = Color.parseColor("#FFEB3B");   // 亮黄色
    public static final int DAY_LYRIC_FADED = Color.parseColor("#AA333333");   // 半透明深灰

    // ========== 通用 ==========

    public static final int LYRIC_HIGHLIGHT = Color.parseColor("#FFEB3B");     // 当前/已播放歌词高亮（日夜通用）
    public static final int DOMINANT_COLOR_FALLBACK = Color.parseColor("#CCF5F5F5"); // 主色调提取失败回退色
    public static final int EMPTY_STATE_TEXT = Color.parseColor("#666666");     // 空状态文字（不受主题控制）
    public static final int BRAND_GREEN = Color.parseColor("#4CAF50");          // 品牌绿（通用强调色）
}
