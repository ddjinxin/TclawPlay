package com.jingxin.jingxinmusic.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 应用更新检查器
 *
 * 流程：
 * 1. checkAndDownload(activity, force) 异步调 GitHub API 拿最新 Release
 * 2. 版本相同/获取失败 → 静默（手动检查时Toast提示）
 * 3. 版本不同且未被永久忽略 → 后台下载 APK
 * 4. 下载成功 → 弹窗提示安装；失败 → Toast提示
 * 5. 用户点"稍后" → 该版本加入忽略集合，永久不再提示
 *
 * force=true 时跳过忽略集合（手动检查场景）
 */
public class UpdateHelper {

    private static final String TAG = "UpdateHelper";

    // GitHub 仓库
    private static final String REPO_OWNER = "ddjinxin";
    private static final String REPO_NAME  = "TclawPlay";
    private static final String API_URL =
            "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";

    // 国内加速镜像列表：[类型, 基址]
    private static final String[][] MIRRORS = {
        {"prefix", "https://ghproxy.net/"},
        {"prefix", "https://gh-proxy.com/"},
        {"prefix", "https://mirror.ghproxy.com/"},
        {"host",   "https://kkgithub.com"},
    };
    private static final int SPEED_TEST_BYTES = 16 * 1024;
    private static final int SPEED_TEST_TIMEOUT = 5000;

    // APK 缓存路径
    private static final String UPDATE_DIR  = "/sdcard/Download/TclawPlay/";
    private static final String UPDATE_FILE = UPDATE_DIR + "update.apk";

    // 永久忽略版本集合存储
    private static final String SP_NAME       = "update_prefs";
    private static final String KEY_IGNORED   = "ignored_versions";
    private static final String KEY_LAST_CHECK= "last_check_ver";

    private static UpdateHelper instance;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isChecking = false;

    private UpdateHelper(Context context) {
        appContext = context.getApplicationContext();
    }

    public static synchronized UpdateHelper getInstance(Context context) {
        if (instance == null) {
            instance = new UpdateHelper(context);
        }
        return instance;
    }

    // ==================== 对外接口 ====================

    /**
     * 启动时自动检查（遵守"永久忽略"列表，本次启动只检查一次）。
     */
    public void checkOnLaunch(Activity activity) {
        SharedPreferences sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String lastCheckVer = sp.getString(KEY_LAST_CHECK, "");
        if (!lastCheckVer.isEmpty()) {
            Log.d(TAG, "本次启动已检查过最新版本: " + lastCheckVer + ", 跳过");
            return;
        }
        checkAndDownload(activity, false);
    }

    /**
     * 手动触发检查（force=true，忽略"永久忽略"列表）。
     */
    public void checkManually(Activity activity) {
        checkAndDownload(activity, true);
    }

    /**
     * 核心流程：检查 → 下载 → 弹窗
     * @param force true 表示手动检查，忽略忽略列表
     */
    private void checkAndDownload(final Activity activity, final boolean force) {
        if (isChecking) {
            Log.d(TAG, "已有检查任务在运行，跳过");
            if (force) {
                mainHandler.post(() ->
                    Toast.makeText(activity, "正在检查更新，请稍候...", Toast.LENGTH_SHORT).show());
            }
            return;
        }
        isChecking = true;

        new Thread(() -> {
            try {
                // 1. 拿当前版本号
                String currentVer = getCurrentVersionName();
                if (currentVer == null) {
                    Log.e(TAG, "无法获取当前版本号");
                    finishCheck(activity, force, "无法获取当前版本号");
                    return;
                }
                Log.i(TAG, "当前版本: " + currentVer + "，开始检查 GitHub 最新版本");

                // 2. 调 GitHub API
                ReleaseInfo info = fetchLatestRelease();
                if (info == null) {
                    Log.w(TAG, "无法获取最新版本信息（网络问题）");
                    finishCheck(activity, force, "无法连接更新服务器");
                    return;
                }
                // 记录本次启动已检查的版本
                appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_LAST_CHECK, info.tagName).apply();

                String latestVer = normalizeVersion(info.tagName);
                String currentNorm = normalizeVersion(currentVer);
                Log.i(TAG, "最新版本: " + info.tagName + " (规范化: " + latestVer + ")");

                // 3. 版本对比
                if (compareVersions(currentNorm, latestVer) >= 0) {
                    Log.i(TAG, "已是最新版本");
                    finishCheck(activity, force, "已是最新版本");
                    return;
                }

                // 4. 检查永久忽略列表（force 模式跳过）
                if (!force && isVersionIgnored(info.tagName)) {
                    Log.i(TAG, "用户已忽略版本 " + info.tagName + "，不再提示");
                    finishCheck(activity, force, null);
                    return;
                }

                // 5. 后台静默下载
                Log.i(TAG, "开始后台下载 APK: " + info.downloadUrl);
                boolean ok = downloadApk(info.downloadUrl);
                if (!ok) {
                    Log.e(TAG, "下载失败");
                    finishCheck(activity, force, "下载失败，请检查网络");
                    return;
                }

                // 6. 下载成功 → 主线程弹窗
                Log.i(TAG, "下载完成，提示用户安装");
                mainHandler.post(() -> showInstallDialog(activity, info, force));

            } catch (Exception e) {
                Log.e(TAG, "检查更新异常: " + e.getMessage(), e);
                finishCheck(activity, force, "检查更新异常: " + e.getMessage());
            }
        }).start();
    }

    // ==================== 内部实现 ====================

    private String getCurrentVersionName() {
        try {
            return appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** 调 GitHub API，解析最新 Release */
    private ReleaseInfo fetchLatestRelease() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "TclawPlay-Updater");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "GitHub API 响应码: " + code);
                return null;
            }
            String body = readStream(conn.getInputStream());
            return parseReleaseJson(body);
        } catch (Exception e) {
            Log.e(TAG, "fetchLatestRelease 失败: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 简单 JSON 解析（不引入第三方库，手写提取需要的3个字段） */
    private ReleaseInfo parseReleaseJson(String json) {
        try {
            String tagName   = extractString(json, "tag_name");
            String body      = extractString(json, "body");
            String downloadUrl = extractFirstAssetUrl(json);
            if (tagName == null || downloadUrl == null) return null;
            ReleaseInfo info = new ReleaseInfo();
            info.tagName   = tagName;
            info.notes     = (body != null) ? body : "";
            info.downloadUrl = downloadUrl;
            return info;
        } catch (Exception e) {
            Log.e(TAG, "parseReleaseJson 失败: " + e.getMessage());
            return null;
        }
    }

    /** 从 JSON 中提取字符串字段（简易实现，处理转义字符） */
    private String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        idx = json.indexOf(":", idx);
        if (idx < 0) return null;
        idx++;
        while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) idx++;
        if (idx >= json.length() || json.charAt(idx) != '"') return null;
        idx++;
        StringBuilder sb = new StringBuilder();
        while (idx < json.length()) {
            char c = json.charAt(idx);
            if (c == '\\' && idx + 1 < json.length()) {
                char next = json.charAt(idx + 1);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(next); break;
                }
                idx += 2;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
                idx++;
            }
        }
        return sb.toString();
    }

    /** 从 JSON 中提取第一个 asset 的 browser_download_url */
    private String extractFirstAssetUrl(String json) {
        String key = "browser_download_url";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx = json.indexOf("\"", json.indexOf(":", idx));
        if (idx < 0) return null;
        idx++;
        int end = json.indexOf("\"", idx);
        if (end < 0) return null;
        return json.substring(idx, end);
    }

    /** 下载 APK：并发测速选最快镜像，按速度顺序依次尝试，全失败才回退主源 */
    private boolean downloadApk(String originalUrl) {
        // 先清理旧 APK
        File oldFile = new File(UPDATE_FILE);
        if (oldFile.exists()) oldFile.delete();

        File dir = new File(UPDATE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "创建下载目录失败: " + UPDATE_DIR);
            return false;
        }

        // 构造所有候选 URL（镜像 + 主源兜底）
        List<String> mirrors = new ArrayList<>();
        for (String[] m : MIRRORS) {
            mirrors.add(buildMirrorUrl(m, originalUrl));
        }

        // 并发测速，按速度从快到慢排序
        Log.i(TAG, "开始并发测速 " + mirrors.size() + " 个镜像...");
        List<SpeedResult> sorted = speedTest(mirrors);
        for (SpeedResult sr : sorted) {
            Log.i(TAG, "测速排名: " + sr.url + " → " + sr.latencyMs + "ms");
        }

        // 按测速结果尝试下载（最快的优先）
        for (SpeedResult sr : sorted) {
            Log.i(TAG, "尝试从镜像下载: " + sr.url);
            if (tryDownload(sr.url, UPDATE_FILE, 20000)) {
                Log.i(TAG, "镜像下载成功: " + sr.url);
                return true;
            }
            Log.w(TAG, "镜像下载失败，尝试下一个");
        }

        // 所有镜像都失败，回退 GitHub 主源
        Log.w(TAG, "所有镜像均失败，回退 GitHub 主源");
        return tryDownload(originalUrl, UPDATE_FILE, 20000);
    }

    /** 构造镜像 URL */
    private String buildMirrorUrl(String[] mirror, String originalUrl) {
        String type = mirror[0];
        String base = mirror[1];
        if ("prefix".equals(type)) {
            return base + originalUrl;
        } else {
            return originalUrl.replace("https://github.com", base);
        }
    }

    /**
     * 并发测速：对每个镜像发 GET 请求读前 16KB，测首段延迟。
     * @return 按延迟从低到高排序的结果列表（失败的排除）
     */
    private List<SpeedResult> speedTest(List<String> urls) {
        ExecutorService executor = Executors.newFixedThreadPool(urls.size());
        List<Future<SpeedResult>> futures = new ArrayList<>();
        for (final String url : urls) {
            futures.add(executor.submit(() -> {
                long start = System.currentTimeMillis();
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "TclawPlay-Updater");
                    conn.setConnectTimeout(SPEED_TEST_TIMEOUT);
                    conn.setReadTimeout(SPEED_TEST_TIMEOUT);
                    conn.setInstanceFollowRedirects(true);
                    int code = conn.getResponseCode();
                    if (code != 200) return new SpeedResult(url, Long.MAX_VALUE);
                    String ct = conn.getContentType();
                    if (ct != null && ct.toLowerCase().contains("text/html")) {
                        return new SpeedResult(url, Long.MAX_VALUE);
                    }
                    InputStream is = conn.getInputStream();
                    byte[] buf = new byte[8192];
                    int total = 0, n;
                    while (total < SPEED_TEST_BYTES && (n = is.read(buf)) > 0) {
                        total += n;
                    }
                    is.close();
                    if (total == 0) return new SpeedResult(url, Long.MAX_VALUE);
                    long latency = System.currentTimeMillis() - start;
                    return new SpeedResult(url, latency);
                } catch (Exception e) {
                    return new SpeedResult(url, Long.MAX_VALUE);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }));
        }
        List<SpeedResult> results = new ArrayList<>();
        for (Future<SpeedResult> f : futures) {
            try {
                SpeedResult sr = f.get();
                if (sr.latencyMs != Long.MAX_VALUE) results.add(sr);
            } catch (Exception ignored) {}
        }
        executor.shutdown();
        results.sort((a, b) -> Long.compare(a.latencyMs, b.latencyMs));
        return results;
    }

    private boolean tryDownload(String urlStr, String destPath, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "TclawPlay-Updater");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "下载响应码: " + code + " url=" + urlStr);
                return false;
            }
            // 校验1：Content-Type 拒绝 HTML
            String contentType = conn.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                Log.w(TAG, "返回内容是 HTML，非 APK（镜像错误页）: " + urlStr);
                return false;
            }
            int total = conn.getContentLength();
            InputStream is = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(destPath);
            byte[] buf = new byte[8192];
            int n;
            long downloaded = 0;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    Log.d(TAG, "下载进度: " + (downloaded * 100 / total) + "%");
                }
            }
            fos.flush();
            fos.close();
            is.close();
            // 校验2：下载完整性
            if (total > 0 && downloaded != total) {
                Log.e(TAG, "下载不完整: " + downloaded + "/" + total + " bytes, url=" + urlStr);
                new File(destPath).delete();
                return false;
            }
            // 校验3：APK 魔数（PK\x03\x04）
            if (!isApkFile(destPath)) {
                Log.e(TAG, "下载文件不是有效 APK（魔数校验失败）: " + destPath + " url=" + urlStr);
                new File(destPath).delete();
                return false;
            }
            Log.i(TAG, "下载完成: " + destPath + " (" + downloaded + " bytes)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "下载失败: " + urlStr + " - " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 校验 APK 魔数：ZIP 文件前4字节是 0x50 0x4B 0x03 0x04 (PK\x03\x04)。
     */
    private boolean isApkFile(String path) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(path);
            byte[] header = new byte[4];
            int read = fis.read(header);
            if (read < 4) return false;
            return header[0] == 0x50 && header[1] == 0x4B
                && header[2] == 0x03 && header[3] == 0x04;
        } catch (Exception e) {
            return false;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 弹窗提示安装 */
    private void showInstallDialog(Activity activity, ReleaseInfo info, boolean force) {
        if (activity == null || activity.isFinishing()) return;

        StringBuilder msg = new StringBuilder();
        msg.append("发现新版本: ").append(info.tagName).append("\n\n");
        if (info.notes != null && !info.notes.isEmpty()) {
            String notes = info.notes.length() > 500
                    ? info.notes.substring(0, 500) + "..." : info.notes;
            msg.append("更新内容:\n").append(notes).append("\n\n");
        }
        msg.append("已下载完成，是否立即安装?");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("应用更新");
        builder.setMessage(msg.toString());
        builder.setCancelable(false);
        builder.setPositiveButton("立即安装", (DialogInterface d, int w) -> {
            installApk(activity);
        });
        builder.setNegativeButton("稍后", (DialogInterface d, int w) -> {
            if (!force) {
                addIgnoredVersion(info.tagName);
                Log.i(TAG, "用户忽略版本: " + info.tagName);
            }
        });
        builder.show();
    }

    /** 调起系统安装器 */
    private void installApk(Activity activity) {
        try {
            File apkFile = new File(UPDATE_FILE);
            if (!apkFile.exists()) {
                Toast.makeText(activity, "APK 文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addCategory(Intent.CATEGORY_DEFAULT);

            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "调起安装失败: " + e.getMessage(), e);
            Toast.makeText(activity, "调起安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 永久忽略列表 ====================

    private boolean isVersionIgnored(String version) {
        Set<String> set = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_IGNORED, new HashSet<>());
        return set.contains(version);
    }

    private void addIgnoredVersion(String version) {
        SharedPreferences sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(sp.getStringSet(KEY_IGNORED, new HashSet<>()));
        set.add(version);
        sp.edit().putStringSet(KEY_IGNORED, set).apply();
    }

    // ==================== 辅助 ====================

    /** 规范化版本号：去掉 "v" 前缀，去空白 */
    private String normalizeVersion(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.toLowerCase().startsWith("v")) v = v.substring(1);
        return v;
    }

    /**
     * 语义化版本对比（支持 1.0 == 1.0.0）。
     * @return v1 < v2 返回 -1；相等返回 0；v1 > v2 返回 1
     */
    private int compareVersions(String v1, String v2) {
        if (v1 == null) v1 = "";
        if (v2 == null) v2 = "";
        String[] a = v1.split("\\.");
        String[] b = v2.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = (i < a.length && !a[i].isEmpty()) ? parseIntSafe(a[i]) : 0;
            int bi = (i < b.length && !b[i].isEmpty()) ? parseIntSafe(b[i]) : 0;
            if (ai != bi) return ai > bi ? 1 : -1;
        }
        return 0;
    }

    /** 解析整数，非数字部分取 0 */
    private int parseIntSafe(String s) {
        try {
            int end = 0;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            if (end == 0) return 0;
            return Integer.parseInt(s.substring(0, end));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 检查流程结束统一出口 */
    private void finishCheck(Activity activity, boolean force, String msg) {
        isChecking = false;
        if (force && msg != null) {
            mainHandler.post(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
        }
    }

    private String readStream(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        return new String(baos.toByteArray(), "UTF-8");
    }

    // ==================== 数据结构 ====================

    private static class ReleaseInfo {
        String tagName;
        String notes;
        String downloadUrl;
    }

    /** 测速结果：URL + 延迟(ms) */
    private static class SpeedResult {
        final String url;
        final long latencyMs;
        SpeedResult(String url, long latencyMs) {
            this.url = url;
            this.latencyMs = latencyMs;
        }
    }
}
