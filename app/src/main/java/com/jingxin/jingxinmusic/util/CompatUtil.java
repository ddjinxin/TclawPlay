package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.os.Build;

/**
 * 兼容性工具类
 * 处理 Android 版本差异导致的 API 调用不同
 * 提取自 PlayerActivity/MainActivity/MusicPlayerService 中的 registerReceiver 判断
 */
public class CompatUtil {

    /**
     * 兼容注册广播接收器
     * Android 13+ (API 33) 需要指定 RECEIVER_NOT_EXPORTED 标志
     * Android 12 以下不需要该标志
     * @param context 上下文
     * @param receiver 广播接收器
     * @param filter Intent 过滤器
     */
    public static void safeRegisterReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }
}
