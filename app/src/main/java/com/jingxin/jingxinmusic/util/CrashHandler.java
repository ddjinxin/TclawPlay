package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃捕获处理器
 *
 * 崩溃时将完整堆栈写入本地日志文件（私有目录，无权限要求）
 * 对系统框架级可恢复异常（如 BadTokenException）记录日志但不退出进程
 * 其他异常记录日志后调用系统默认 handler 退出进程
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";

    private static CrashHandler instance;
    private Context appContext;
    private Thread.UncaughtExceptionHandler defaultHandler;

    private CrashHandler(Context context) {
        appContext = context.getApplicationContext();
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static synchronized void install(Context context) {
        if (instance == null) {
            instance = new CrashHandler(context);
            Thread.setDefaultUncaughtExceptionHandler(instance);
            Log.i(TAG, "CrashHandler 已安装");
        }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        // 1. 写入本地崩溃日志
        writeCrashFile(t, e);

        // 2. 系统框架级可恢复异常：记录日志但不退出进程
        if (isRecoverableSystemException(e)) {
            Log.w(TAG, "系统框架异常已拦截，应用继续运行: " + e.getClass().getSimpleName());
            return;
        }

        // 3. 其他异常：调用系统默认处理（弹出崩溃对话框 / 退出进程）
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(t, e);
        }
    }

    /**
     * 判断是否为系统框架级可恢复异常
     * 这类异常通常由 Activity 生命周期时序问题引起，不影响应用核心逻辑
     * 拦截后应用可继续运行，无需退出
     */
    private boolean isRecoverableSystemException(Throwable e) {
        while (e != null) {
            String name = e.getClass().getName();
            // WindowManager$BadTokenException: Activity 窗口 token 失效（常见于车机内存紧张时 Activity 恢复）
            // WindowManager$InvalidDisplayException: Display 已失效
            if (name.equals("android.view.WindowManager$BadTokenException")
                    || name.equals("android.view.WindowManager$InvalidDisplayException")) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    // ==================== 本地写文件 ====================

    private void writeCrashFile(Thread thread, Throwable e) {
        try {
            File baseDir = appContext.getExternalFilesDir(null);
            if (baseDir == null) baseDir = appContext.getFilesDir();
            File logDir = new File(baseDir, "log");
            if (!logDir.exists()) logDir.mkdirs();
            String filename = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".md";
            File file = new File(logDir, filename);

            StringBuilder sb = new StringBuilder();
            sb.append("## 静心音乐崩溃报告\n\n");
            sb.append("**时间**: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n\n");
            sb.append("**App版本**: ").append(getAppVersion()).append("\n\n");
            sb.append("**设备**: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n\n");
            sb.append("**系统**: Android ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");
            sb.append("**线程**: ").append(thread != null ? thread.getName() : "unknown").append("\n\n");
            sb.append("---\n\n");
            sb.append("### 堆栈\n\n```\n");
            sb.append(getStackTraceString(e));
            sb.append("\n```\n");

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
            Log.i(TAG, "崩溃日志已保存: " + file.getAbsolutePath());
        } catch (Exception ex) {
            Log.e(TAG, "写入崩溃日志失败: " + ex.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        tr.printStackTrace(pw);
        pw.flush();
        Throwable cause = tr.getCause();
        while (cause != null) {
            sw.write("\nCaused by: ");
            cause.printStackTrace(pw);
            pw.flush();
            cause = cause.getCause();
        }
        return sw.toString();
    }

    private String getAppVersion() {
        try {
            return appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
