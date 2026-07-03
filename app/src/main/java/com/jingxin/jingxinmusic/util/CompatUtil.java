package com.jingxin.jingxinmusic.util;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

/**
 * 兼容性工具类
 * 处理 Android 版本差异导致的 API 调用不同
 *
 * 关键设计：所有高 API 才存在的方法签名（如 startForeground 3参数、registerReceiver 3参数）
 * 都通过反射调用，避免低版本 ART 在类验证时因方法签名不存在而抛 VerifyError 闪退。
 */
public class CompatUtil {

    private static final String TAG = "CompatUtil";

    /**
     * 兼容注册广播接收器（内部广播，RECEIVER_NOT_EXPORTED）
     * Android 13+ (API 33) 需要指定 RECEIVER_NOT_EXPORTED 标志
     * 使用反射调用 3 参数 registerReceiver，避免低版本 ART VerifyError
     */
    public static void safeRegisterReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                java.lang.reflect.Method method = Context.class.getMethod(
                        "registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class);
                method.invoke(context, receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } catch (Exception e) {
                Log.w(TAG, "反射调用 registerReceiver 3参数失败，回退2参数: " + e.getMessage());
                context.registerReceiver(receiver, filter);
            }
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /**
     * 兼容注册广播接收器（外部广播，RECEIVER_EXPORTED）
     * 用于接收外部应用发送的广播（如高德导航日夜模式）
     * 使用反射调用 3 参数 registerReceiver，避免低版本 ART VerifyError
     */
    public static void safeRegisterReceiverExported(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                java.lang.reflect.Method method = Context.class.getMethod(
                        "registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class);
                method.invoke(context, receiver, filter, Context.RECEIVER_EXPORTED);
            } catch (Exception e) {
                Log.w(TAG, "反射调用 registerReceiver 3参数失败，回退2参数: " + e.getMessage());
                context.registerReceiver(receiver, filter);
            }
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /**
     * 兼容 startForeground 调用
     * Android 14+ (API 34) 必须使用 3 参数形式指定 foregroundServiceType
     * 使用反射调用 3 参数 startForeground，避免低版本 ART VerifyError
     *
     * @param service      服务实例
     * @param id           通知ID
     * @param notification 通知对象
     */
    public static void safeStartForeground(Service service, int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                java.lang.reflect.Method method = Service.class.getMethod(
                        "startForeground", int.class, Notification.class, int.class);
                method.invoke(service, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } catch (Exception e) {
                Log.w(TAG, "反射调用 startForeground 3参数失败，回退2参数: " + e.getMessage());
                service.startForeground(id, notification);
            }
        } else {
            service.startForeground(id, notification);
        }
    }
}
