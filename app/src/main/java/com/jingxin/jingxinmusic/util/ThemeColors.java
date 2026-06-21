package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;

/**
 * 主题颜色统一管理
 * 委托给当前 ThemeStyle 实例，支持多风格动态切换
 * 支持色彩渐变，提供 GradientDrawable 获取方法
 * 
     * 风格列表：春意盎然(0)、蔚蓝天地(1)、万紫千红(2)、高级灰(3)
 */
public class ThemeColors {

    private static final String PREFS_NAME = "theme_prefs";
    private static final String KEY_STYLE_INDEX = "style_index";

    private static ThemeStyle currentStyle = ThemeStyle.JADE_GLOW;
    private static int currentStyleIndex = 0;

    public static final ThemeStyle[] STYLES = {
        ThemeStyle.JADE_GLOW,
        ThemeStyle.JADE_WARM,
        ThemeStyle.MINT_FRESH,
        ThemeStyle.GRAY_PREMIUM
    };

    /** 从 SharedPreferences 恢复风格 */
    public static void init(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        currentStyleIndex = sp.getInt(KEY_STYLE_INDEX, 0);
        if (currentStyleIndex >= 0 && currentStyleIndex < STYLES.length) {
            currentStyle = STYLES[currentStyleIndex];
        }
    }

    /** 切换到下一个风格，返回新的风格索引 */
    public static int cycleStyle(Context context) {
        currentStyleIndex = (currentStyleIndex + 1) % STYLES.length;
        currentStyle = STYLES[currentStyleIndex];
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_STYLE_INDEX, currentStyleIndex).apply();
        return currentStyleIndex;
    }

    /** 获取当前风格 */
    public static ThemeStyle getStyle() {
        return currentStyle;
    }

    /** 获取当前风格索引 */
    public static int getStyleIndex() {
        return currentStyleIndex;
    }

    /** 设置指定风格 */
    public static void setStyle(Context context, int index) {
        if (index >= 0 && index < STYLES.length) {
            currentStyleIndex = index;
            currentStyle = STYLES[index];
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            sp.edit().putInt(KEY_STYLE_INDEX, index).apply();
        }
    }

    public static int themedColor(boolean isNight, int dayColor, int nightColor) {
        return isNight ? nightColor : dayColor;
    }

    // ========== 渐变 Drawable 工厂方法 ==========

    /** 主背景渐变 — 夜间从上到下微绿，白天左上奶白→右下柔青绿 */
    public static GradientDrawable bgGradient(boolean night) {
        if (night) {
            return new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{ currentStyle.nightBg, currentStyle.nightBgEnd });
        } else {
            // 白天：左上奶白薄荷→右下柔青绿，酷狗国风渐变
            return new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{ currentStyle.dayBg, currentStyle.dayBgEnd });
        }
    }

    /** 标题栏/Tab栏渐变 — 夜间从上到下，白天左上到右下微绿 */
    public static GradientDrawable barGradient(boolean night) {
        if (night) {
            return new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{ currentStyle.nightBarBg, currentStyle.nightBarBgEnd });
        } else {
            // 白天：薄荷白微渐变（与主背景同方向融合）
            return new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{ currentStyle.dayBarBg, currentStyle.dayBarBgEnd });
        }
    }

    /** 卡片渐变（从左上到右下，对角线） */
    public static GradientDrawable cardGradient(boolean night) {
        return new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{ night ? currentStyle.nightCardBg : currentStyle.dayCardBg,
                           night ? currentStyle.nightCardBgEnd : currentStyle.dayCardBgEnd });
    }

    /** 迷你播放条渐变（从左到右） */
    public static GradientDrawable miniGradient(boolean night) {
        return new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ night ? currentStyle.nightMiniBg : currentStyle.dayMiniBg,
                           night ? currentStyle.nightMiniBgEnd : currentStyle.dayMiniBgEnd });
    }

    // ========== 纯色获取方法 ==========

    // 夜间
    public static int nightBg()             { return currentStyle.nightBg; }
    public static int nightItemBg()         { return currentStyle.nightItemBg; }
    public static int nightBarBg()          { return currentStyle.nightBarBg; }
    public static int nightOverlay()        { return currentStyle.nightOverlay; }
    public static int nightCardBg()         { return currentStyle.nightCardBg; }
    public static int nightCardBgEnd()      { return currentStyle.nightCardBgEnd; }
    public static int nightCardPressed()    { return currentStyle.nightCardPressed; }
    public static int nightTextPrimary()    { return currentStyle.nightTextPrimary; }
    public static int nightTextSecondary()  { return currentStyle.nightTextSecondary; }
    public static int nightTextTertiary()   { return currentStyle.nightTextTertiary; }
    public static int nightTextCopyright()  { return currentStyle.nightTextCopyright; }
    public static int nightDivider()        { return currentStyle.nightDivider; }
    public static int nightTabActive()      { return currentStyle.nightTabActive; }
    public static int nightTabInactive()    { return currentStyle.nightTabInactive; }
    public static int nightTabIndicator()   { return currentStyle.nightTabIndicator; }
    public static int nightLyricNormal()    { return currentStyle.nightLyricNormal; }
    public static int nightLyricCurrent()   { return currentStyle.nightLyricCurrent; }
    public static int nightLyricFaded()     { return currentStyle.nightLyricFaded; }
    public static int nightCoverTint()      { return currentStyle.nightCoverTint; }
    public static int nightFolderBorder()   { return currentStyle.nightFolderBorder; }

    // 白天
    public static int dayBg()               { return currentStyle.dayBg; }
    public static int dayItemBg()           { return currentStyle.dayItemBg; }
    public static int dayBarBg()            { return currentStyle.dayBarBg; }
    public static int dayMiniBg()           { return currentStyle.dayMiniBg; }
    public static int dayCardBg()           { return currentStyle.dayCardBg; }
    public static int dayCardBgEnd()        { return currentStyle.dayCardBgEnd; }
    public static int dayCardPressed()      { return currentStyle.dayCardPressed; }
    public static int dayTextPrimary()      { return currentStyle.dayTextPrimary; }
    public static int dayTextSecondary()    { return currentStyle.dayTextSecondary; }
    public static int dayTextTertiary()     { return currentStyle.dayTextTertiary; }
    public static int dayTextCopyright()    { return currentStyle.dayTextCopyright; }
    public static int dayDivider()          { return currentStyle.dayDivider; }
    public static int dayTabActive()        { return currentStyle.dayTabActive; }
    public static int dayTabInactive()      { return currentStyle.dayTabInactive; }
    public static int dayTabIndicator()     { return currentStyle.dayTabIndicator; }
    public static int dayLyricNormal()      { return currentStyle.dayLyricNormal; }
    public static int dayLyricCurrent()     { return currentStyle.dayLyricCurrent; }
    public static int dayLyricFaded()       { return currentStyle.dayLyricFaded; }
    public static int dayCoverTint()        { return currentStyle.dayCoverTint; }
    public static int dayFolderBorder()     { return currentStyle.dayFolderBorder; }

    // 通用
    public static int lyricHighlight()          { return currentStyle.lyricHighlight; }
    public static int dominantColorFallback()   { return currentStyle.dominantColorFallback; }
    public static int emptyStateText()          { return currentStyle.emptyStateText; }

    // ========== 文件夹卡片6色循环渐变 ==========

    // 春意盎然 — 酷狗国风6色（低饱和柔和渐变，类似酷狗卡片）
    private static final int[][] DAY_FOLDER_GRADIENTS_JADE = {
        {0xFFB8D8A0, 0xFFA0C488},  // 黄调青柠绿
        {0xFFC8CCA0, 0xFFB0B488},  // 暖调卡其绿
        {0xFFA8C8A8, 0xFF90B090},  // 暖调豆绿
        {0xFF88B898, 0xFF70A080},  // 冷调草绿
        {0xFFB0E0B0, 0xFF98CC98},  // 浅草绿
        {0xFF80C8A0, 0xFF68B088},  // 青绿色
    };
    private static final int[][] NIGHT_FOLDER_GRADIENTS_JADE = {
        {0xFF1A2E1A, 0xFF223822},  // 深青柠
        {0xFF2A2A1A, 0xFF32321E},  // 暗卡其
        {0xFF1A2A20, 0xFF203428},  // 暗豆绿
        {0xFF162A1E, 0xFF1E3426},  // 暗草绿
        {0xFF1A301A, 0xFF223A22},  // 暗浅草
        {0xFF162E20, 0xFF1E3828},  // 暗青绿
    };

    // 蔚蓝天地 — 蓝调6色
    private static final int[][] DAY_FOLDER_GRADIENTS_WARM = {
        {0xFFB0C4E8, 0xFF90AACC},  // 天蓝
        {0xFFA0B8DC, 0xFF809CC8},  // 矢车菊蓝
        {0xFFB8C8E4, 0xFF98A8D0},  // 雾蓝
        {0xFFC0CCE0, 0xFFA0ACCC},  // 蓝灰
        {0xFF98B4D8, 0xFF7898BC},  // 深天蓝
        {0xFFA8C0E0, 0xFF88A4C8},  // 矢车菊浅
    };
    private static final int[][] NIGHT_FOLDER_GRADIENTS_WARM = {
        {0xFF0C1830, 0xFF142240},  // 暗天蓝
        {0xFF0E1A2C, 0xFF162238},  // 暗矢车菊
        {0xFF101C28, 0xFF182430},  // 暗雾蓝
        {0xFF121C24, 0xFF1A242C},  // 暗蓝灰
        {0xFF0A1628, 0xFF121E38},  // 暗深天蓝
        {0xFF0E1826, 0xFF162030},  // 暗浅矢车菊
    };

    // 万紫千红 — 紫红6色
    private static final int[][] DAY_FOLDER_GRADIENTS_MINT = {
        {0xFFC8B8E8, 0xFFB09CD0},  // 薰衣草
        {0xFFC0A8E0, 0xFFA888C8},  // 紫藤
        {0xFFA080CC, 0xFF8864B4},  // 深紫罗兰
        {0xFFCC88B8, 0xFFB46C9C},  // 紫红
        {0xFFD880A8, 0xFFC0648C},  // 洋红
        {0xFFD0A0D0, 0xFFB884BC},  // 淡梅紫
    };
    private static final int[][] NIGHT_FOLDER_GRADIENTS_MINT = {
        {0xFF140C28, 0xFF1C1238},  // 暗薰衣草
        {0xFF160E2A, 0xFF1E1438},  // 暗紫藤
        {0xFF120824, 0xFF1A0E34},  // 暗深紫罗兰
        {0xFF180A20, 0xFF22102C},  // 暗紫红
        {0xFF1A0818, 0xFF240E22},  // 暗洋红
        {0xFF180C1E, 0xFF201228},  // 暗淡梅紫
    };

    // 高级灰 — 灰质6色
    private static final int[][] DAY_FOLDER_GRADIENTS_GRAY = {
        {0xFFD0D0D8, 0xFFB8B8C2},  // 银灰
        {0xFFC8C4C0, 0xFFB0ACA6},  // 暖灰
        {0xFFC4C8CC, 0xFFACB0B6},  // 冷灰
        {0xFFDCD8D4, 0xFFC4C0BA},  // 珍珠灰
        {0xFFA8A8B2, 0xFF9090A0},  // 石板灰
        {0xFF8C8C98, 0xFF747480},  // 炭灰
    };
    private static final int[][] NIGHT_FOLDER_GRADIENTS_GRAY = {
        {0xFF1C1C22, 0xFF24242C},  // 暗银灰
        {0xFF1A1A1E, 0xFF222226},  // 暗暖灰
        {0xFF1A1C1E, 0xFF222428},  // 暗冷灰
        {0xFF1E1C1C, 0xFF262424},  // 暗珍珠灰
        {0xFF16161C, 0xFF1E1E24},  // 暗石板灰
        {0xFF121216, 0xFF1A1A1E},  // 暗炭灰
    };

    private static final int[][][] DAY_FOLDER_GRADIENTS = {
        DAY_FOLDER_GRADIENTS_JADE, DAY_FOLDER_GRADIENTS_WARM, DAY_FOLDER_GRADIENTS_MINT, DAY_FOLDER_GRADIENTS_GRAY
    };
    private static final int[][][] NIGHT_FOLDER_GRADIENTS = {
        NIGHT_FOLDER_GRADIENTS_JADE, NIGHT_FOLDER_GRADIENTS_WARM, NIGHT_FOLDER_GRADIENTS_MINT, NIGHT_FOLDER_GRADIENTS_GRAY
    };

    /**
     * 获取文件夹卡片渐变Drawable
     * @param index 文件夹位置（会自动 mod 6 循环）
     * @param night 是否夜间模式
     */
    public static GradientDrawable folderGradient(int index, boolean night) {
        int i = ((index % 6) + 6) % 6;  // 确保正数
        int styleIdx = currentStyleIndex;
        int[][] gradients = night ? NIGHT_FOLDER_GRADIENTS[styleIdx] : DAY_FOLDER_GRADIENTS[styleIdx];
        return new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{ gradients[i][0], gradients[i][1] });
    }
}
