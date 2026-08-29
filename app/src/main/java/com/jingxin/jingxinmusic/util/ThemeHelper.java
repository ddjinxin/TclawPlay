package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.util.Log;

import java.util.Calendar;

/**
 * 日夜主题自动判断工具
 *
 * 优先级：高德导航广播 > 时间规则 > 记忆（isNight）
 *
 * App 启动时调用 applyAutoTheme()：
 * - 若 amapTriggered=true，说明高德导航刚刚设置过主题，保留不覆盖
 * - 否则根据当前时间和用户配置的日夜时间判断白天/夜间，写入 isNight
 * - 用户可在设置页开关"按时间自动切换"并自定义白天/夜间分界时间
 */
public class ThemeHelper {

    private static final String TAG = "ThemeHelper";
    private static final String PREFS_NAME = "theme";

    // 是否启用按时间自动切换（默认关闭，首次安装沿用记忆逻辑）
    public static final String KEY_AUTO_THEME = "auto_theme_enabled";
    // 白天开始时间（小时，0-23），默认 6
    public static final String KEY_DAY_START = "day_start_hour";
    // 夜间开始时间（小时，0-23），默认 18
    public static final String KEY_NIGHT_START = "night_start_hour";

    /**
     * App 启动时调用：根据优先级确定主题
     */
    public static void applyAutoTheme(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        boolean autoEnabled = prefs.getBoolean(KEY_AUTO_THEME, false);

        // 未启用自动切换：保持记忆逻辑，不做任何改动
        if (!autoEnabled) return;

        // 高德导航刚触发过：保留高德的设置，不覆盖
        boolean amapTriggered = prefs.getBoolean("amapTriggered", false);
        if (amapTriggered) {
            Log.d(TAG, "高德导航已设置主题，跳过时间判断");
            return;
        }

        // 按时间判断
        boolean nightByTime = isNightByTime(prefs);
        boolean currentNight = prefs.getBoolean("isNight", true);
        if (nightByTime != currentNight) {
            Log.d(TAG, "时间判断切换主题: " + (currentNight ? "夜间" : "白天") + " → " + (nightByTime ? "夜间" : "白天"));
            prefs.edit().putBoolean("isNight", nightByTime).apply();
        }
    }

    /**
     * 根据当前时间和用户配置的日夜时间判断是否为夜间
     */
    public static boolean isNightByTime(android.content.SharedPreferences prefs) {
        int dayStart = prefs.getInt(KEY_DAY_START, 6);   // 默认 6 点白天
        int nightStart = prefs.getInt(KEY_NIGHT_START, 18); // 默认 18 点夜间

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

        // 例：dayStart=6, nightStart=18
        // 6~17 为白天，18~5 为夜间
        if (dayStart <= nightStart) {
            return hour < dayStart || hour >= nightStart;
        } else {
            // 跨天情况：dayStart=22, nightStart=6
            // 6~21 为白天，22~5 为夜间
            return hour >= nightStart && hour < dayStart;
        }
    }

    /**
     * 格式化时间显示：6 → "6:00"
     */
    public static String formatHour(int hour) {
        return hour + ":00";
    }
}
