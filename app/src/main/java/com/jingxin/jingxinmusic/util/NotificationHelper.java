package com.jingxin.jingxinmusic.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * 通知工具类
 * 统一 MusicPlayerService 和 MiniFloatService 的通知渠道创建逻辑
 */
public class NotificationHelper {

    /**
     * 创建通知渠道（Android 8+ 必需）
     * @param context Context
     * @param channelId 渠道ID
     * @param name 渠道名称
     * @param description 渠道描述
     */
    public static void createChannel(Context context, String channelId,
                                      String name, String description) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, name, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(description);
            channel.setShowBadge(false);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
