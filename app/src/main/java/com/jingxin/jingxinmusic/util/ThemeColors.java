package com.jingxin.jingxinmusic.util;

import android.graphics.Color;

/**
 * 主题颜色统一管理
 * 所有白天/夜间颜色集中定义，各处通过 ThemeColors 引用
 * 避免硬编码颜色值散落在多个文件中
 */
public class ThemeColors {

    // ========== 夜间模式 ==========

    // 背景
    public static final int NIGHT_BG = Color.parseColor("#000000");           // 主背景（纯黑）
    public static final int NIGHT_ITEM_BG = Color.parseColor("#0a0a0a");     // 列表项背景（近黑）
    public static final int NIGHT_BAR_BG = Color.parseColor("#1a1a1a");      // 标题栏/Tab栏/迷你播放条背景
    public static final int NIGHT_OVERLAY = Color.parseColor("#55000000");   // 半透明黑色遮罩

    // 文字
    public static final int NIGHT_TEXT_PRIMARY = Color.parseColor("#FFFFFF");    // 主要文字（歌名/标题）
    public static final int NIGHT_TEXT_SECONDARY = Color.parseColor("#888888"); // 次要文字（歌手/箭头）
    public static final int NIGHT_TEXT_TERTIARY = Color.parseColor("#666666");  // 提示/时长文字
    public static final int NIGHT_TEXT_COPYRIGHT = Color.parseColor("#444444"); // 版权/底部文字

    // 分割线
    public static final int NIGHT_DIVIDER = Color.parseColor("#333333");

    // Tab
    public static final int NIGHT_TAB_ACTIVE = Color.parseColor("#FFFFFF");
    public static final int NIGHT_TAB_INACTIVE = Color.parseColor("#888888");

    // 歌词
    public static final int NIGHT_LYRIC_NORMAL = Color.parseColor("#FFFFFF");
    public static final int NIGHT_LYRIC_CURRENT = Color.parseColor("#FFEB3B");  // 亮黄色
    public static final int NIGHT_LYRIC_FADED = Color.parseColor("#AAFFFFFF");  // 半透明白

    // ========== 白天模式 ==========

    // 背景
    public static final int DAY_BG = Color.parseColor("#F5F5F5");             // 主背景
    public static final int DAY_ITEM_BG = Color.parseColor("#FFFFFF");        // 列表项背景（白色）
    public static final int DAY_BAR_BG = Color.parseColor("#E0E0E0");         // 标题栏/Tab栏背景
    public static final int DAY_MINI_BG = Color.parseColor("#E8E8E8");        // 迷你播放条背景

    // 文字
    public static final int DAY_TEXT_PRIMARY = Color.parseColor("#333333");    // 主要文字（歌名/标题）
    public static final int DAY_TEXT_SECONDARY = Color.parseColor("#999999");  // 次要文字（歌手/提示）
    public static final int DAY_TEXT_TERTIARY = Color.parseColor("#666666");   // 时长/搜索提示
    public static final int DAY_TEXT_COPYRIGHT = Color.parseColor("#AAAAAA");  // 版权/底部文字

    // 分割线
    public static final int DAY_DIVIDER = Color.parseColor("#CCCCCC");

    // Tab
    public static final int DAY_TAB_ACTIVE = Color.parseColor("#333333");
    public static final int DAY_TAB_INACTIVE = Color.parseColor("#999999");

    // 歌词
    public static final int DAY_LYRIC_NORMAL = Color.parseColor("#333333");
    public static final int DAY_LYRIC_CURRENT = Color.parseColor("#FFEB3B");   // 亮黄色
    public static final int DAY_LYRIC_FADED = Color.parseColor("#AA333333");   // 半透明深灰

    // ========== 通用 ==========

    public static final int LYRIC_HIGHLIGHT = Color.parseColor("#FFEB3B");     // 当前/已播放歌词高亮（日夜通用）
    public static final int DOMINANT_COLOR_FALLBACK = Color.parseColor("#CCF5F5F5"); // 主色调提取失败回退色
    public static final int EMPTY_STATE_TEXT = Color.parseColor("#666666");     // 空状态文字（不受主题控制）
}
