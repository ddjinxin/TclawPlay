package com.jingxin.jingxinmusic.util;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 诊断日志落盘（排查乐酷悬浮 resize 黑屏/封面流丢失专用）
 *
 * 目标位置：车机公共下载目录 /storage/emulated/0/Download/静心音乐_诊断日志.txt
 * 写入策略（按可用性降级）：
 * 1. File 直写公共 Download（MANAGE_EXTERNAL_STORAGE 已授权或 API≤28 WRITE 已授权）
 * 2. API 29+ MediaStore.Downloads（自有文件免权限）
 * 3. 应用外部私有目录 Android/data/<pkg>/files（兜底，始终可写）
 *
 * 机制：内存缓冲（上限 512KB 超限丢最旧一半）+ 单线程后台全量重写（崩溃时同步冲刷）；
 * 每条同时输出 Logcat（tag=DL）方便 adb 抓取；跨进程重启保留历史（init 时读回旧文件）。
 */
public final class DiagLog {

    public static final String TAG = "DL";
    private static final String FILE_NAME = "静心音乐_诊断日志.txt";
    private static final int MAX_BUFFER = 512 * 1024;

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());

    /** 写入目标（展示给用户/日志头） */
    private static String sinkDesc = "未初始化";
    /** 直写文件（模式1/3），null=MediaStore 模式 */
    private static File sinkFile;
    /** MediaStore uri（模式2） */
    private static Uri sinkUri;
    private static Context appCtx;

    private static final StringBuilder buffer = new StringBuilder(8 * 1024);
    private static final Object lock = new Object();
    private static final AtomicBoolean dirty = new AtomicBoolean(false);
    private static final AtomicBoolean flushing = new AtomicBoolean(false);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiagLog-IO");
        t.setDaemon(true);
        return t;
    });

    /** 节流表（key→上次放行时刻）：高频事件防刷屏 */
    private static final Map<String, Long> throttle = new HashMap<>();

    private DiagLog() {}

    public static void init(Context context) {
        if (appCtx != null) return;
        appCtx = context.getApplicationContext();
        try {
            resolveSink();
        } catch (Exception e) {
            sinkDesc = "初始化失败:" + e;
        }
        // 会话头（版本/设备/路径）
        String header = "\n==== 会话开始 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())
                + " 版本:" + appVersion()
                + " 设备:" + Build.MANUFACTURER + " " + Build.MODEL
                + " Android:" + Build.VERSION.RELEASE + "(API " + Build.VERSION.SDK_INT + ")"
                + " 进程pid:" + android.os.Process.myPid()
                + " 日志路径:" + sinkDesc + " ====\n";
        synchronized (lock) {
            buffer.append(header);
        }
        Log.i(TAG, "DiagLog 已初始化，写入目标: " + sinkDesc);
        scheduleFlush();
    }

    /** 解析写入目标：公共 Download 直写 → MediaStore → 应用私有目录 */
    private static void resolveSink() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        // 1. 公共 Download 直写：试写探针（MANAGE 授权 / legacy / WRITE 授权路径均会成功）
        File direct = new File(downloadDir, FILE_NAME);
        if (tryProbe(direct)) {
            sinkFile = direct;
            sinkDesc = direct.getAbsolutePath();
            loadExisting(direct, null);
            return;
        }
        // 2. API 29+：MediaStore.Downloads（自有文件免权限）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && appCtx != null) {
            try {
                Uri uri = queryExisting();
                if (uri == null) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
                    uri = appCtx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                }
                if (uri != null && tryProbe(uri)) {
                    sinkUri = uri;
                    sinkDesc = "Download/" + FILE_NAME + "（MediaStore）";
                    loadExisting(null, uri);
                    return;
                }
            } catch (Exception ignored) {}
        }
        // 3. 兜底：应用外部私有目录
        File base = appCtx != null ? appCtx.getExternalFilesDir(null) : null;
        if (base == null) base = appCtx != null ? appCtx.getFilesDir() : null;
        if (base != null) {
            File priv = new File(base, FILE_NAME);
            sinkFile = priv;
            sinkDesc = priv.getAbsolutePath() + "（私有目录兜底）";
            loadExisting(priv, null);
        }
    }

    /** 直写探针：目录可建、文件可写才算成功（失败即降级） */
    private static boolean tryProbe(File f) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            OutputStream os = new FileOutputStream(f, true);
            os.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean tryProbe(Uri uri) {
        try {
            OutputStream os = appCtx.getContentResolver().openOutputStream(uri, "wa");
            if (os != null) { os.close(); return true; }
        } catch (Exception ignored) {}
        return false;
    }

    private static Uri queryExisting() {
        try {
            android.database.Cursor c = appCtx.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{FILE_NAME}, null);
            if (c != null) {
                Uri found = null;
                if (c.moveToFirst()) {
                    found = Uri.parse(MediaStore.Downloads.EXTERNAL_CONTENT_URI + "/"
                            + c.getLong(0));
                }
                c.close();
                return found;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 跨重启保留历史：旧文件内容读回缓冲（限 MAX_BUFFER/2） */
    private static void loadExisting(File file, Uri uri) {
        try {
            InputStream is;
            if (file != null) is = new FileInputStream(file);
            else is = appCtx.getContentResolver().openInputStream(uri);
            if (is == null) return;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            long total = 0, skip = 0;
            int n;
            long len = file != null ? file.length() : 0;
            if (file != null && len > MAX_BUFFER / 2) skip = len - MAX_BUFFER / 2;
            // 跳过最旧部分
            while (skip > 0 && (n = is.read(buf)) > 0) {
                if (n > skip) {
                    bos.write(buf, (int) skip, n - (int) skip);
                    total += n - skip;
                    skip = 0;
                } else {
                    skip -= n;
                }
            }
            while (total < MAX_BUFFER / 2 && (n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
            }
            is.close();
            String old = bos.toString("UTF-8");
            int cut = old.indexOf('\n'); // 截断行首对齐
            if (cut > 0) old = old.substring(cut + 1);
            synchronized (lock) {
                buffer.insert(0, old);
            }
        } catch (Exception ignored) {}
    }

    /** 常规埋点：文件 + Logcat */
    public static void d(String tag, String msg) {
        append(tag, msg);
    }

    /** 节流埋点：同一 key 在 windowMs 内只放行一条（高频事件用） */
    public static void t(String key, long windowMs, String tag, String msg) {
        long now = SystemClock.elapsedRealtime();
        synchronized (throttle) {
            Long last = throttle.get(key);
            if (last != null && now - last < windowMs) return;
            throttle.put(key, now);
        }
        append(tag, msg);
    }

    private static void append(String tag, String msg) {
        if (appCtx == null) { // 未初始化兜底：仅 Logcat
            Log.d(TAG, tag + ": " + msg);
            return;
        }
        String line = TS.format(new Date()) + " up" + SystemClock.elapsedRealtime() / 1000
                + "s " + tag + ": " + msg + "\n";
        synchronized (lock) {
            buffer.append(line);
            if (buffer.length() > MAX_BUFFER) {
                buffer.delete(0, buffer.length() - MAX_BUFFER / 2);
                int cut = buffer.indexOf("\n");
                if (cut >= 0 && cut < buffer.length() - 1) buffer.delete(0, cut + 1);
            }
        }
        Log.d(TAG, tag + ": " + msg);
        scheduleFlush();
    }

    private static void scheduleFlush() {
        if (!dirty.compareAndSet(false, true)) return; // 已有待写标记
        IO.execute(() -> {
            try {
                if (dirty.compareAndSet(true, false)) writeAll(false);
            } catch (Throwable ignored) {}
        });
    }

    /** 崩溃路径同步冲刷（CrashHandler 调用，忽略一切异常保证不挡死） */
    public static void flushSync() {
        try {
            if (flushing.get()) Thread.sleep(100); // 后台写进行中：稍等避免内容丢失
            writeAll(true);
        } catch (Throwable ignored) {}
    }

    private static void writeAll(boolean sync) {
        if (flushing.getAndSet(true)) return;
        try {
            String content;
            synchronized (lock) {
                content = buffer.toString();
            }
            if (sinkFile != null) {
                File dir = sinkFile.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                OutputStream os = new FileOutputStream(sinkFile, false);
                os.write(content.getBytes("UTF-8"));
                os.flush();
                os.close();
            } else if (sinkUri != null) {
                OutputStream os = appCtx.getContentResolver().openOutputStream(sinkUri, "wt");
                if (os != null) {
                    os.write(content.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                }
            }
        } catch (Exception e) {
            if (sync) Log.e(TAG, "同步冲刷失败: " + e);
        } finally {
            flushing.set(false);
        }
    }

    private static String appVersion() {
        try {
            return appCtx.getPackageManager().getPackageInfo(appCtx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
