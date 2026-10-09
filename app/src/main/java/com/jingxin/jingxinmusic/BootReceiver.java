package com.jingxin.jingxinmusic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启接收器：系统开机完成后，若用户开启了"开机自启"开关，则自动启动应用主界面。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        // 开关未开启则不动作
        if (!com.jingxin.jingxinmusic.fragment.SettingsFragment.isBootAutoStartEnabled(context)) {
            return;
        }
        try {
            Intent launch = new Intent(context, HostActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            Log.i(TAG, "开机自启：已启动静心音乐");
        } catch (Exception e) {
            Log.e(TAG, "开机自启启动失败: " + e.getMessage());
        }
    }
}