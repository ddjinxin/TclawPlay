package com.jingxin.jingxinmusic.util;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 歌词动效壁纸存储（移植乐酷驾驶 GridBackgroundView 壁纸机制，两态简化版）
 *
 * 模式：default（默认背景，效果页现状）/ wallpaper（壁纸模式）
 * 文件：应用私有目录 effect_wallpaper/wallpaper.{ext}（保留原始扩展名）
 *      图片 .jpg/.jpeg/.png/.webp/.bmp | 视频 .mp4/.3gp/.webm/.mkv
 * 复制即覆盖：copy 先删 wallpaper.* 全扩展名旧文件再复制（乐酷同款）
 */
public final class EffectWallpaperStore {

    public static final String PREFS_NAME = "lyric_effect";
    public static final String KEY_MODE = "lyric_effect_bg_mode";
    public static final String MODE_DEFAULT = "default";
    public static final String MODE_WALLPAPER = "wallpaper";

    private static final String DIR_NAME = "effect_wallpaper";
    private static final String FILE_PREFIX = "wallpaper";

    /** 支持的扩展名（与 FilePicker 过滤一致；判定音视频共用） */
    public static final String[] IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"};
    public static final String[] VIDEO_EXTS = {".mp4", ".3gp", ".webm", ".mkv"};

    private EffectWallpaperStore() {}

    // ==================== 模式 ====================

    public static boolean isWallpaperMode(Context ctx) {
        return MODE_WALLPAPER.equals(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MODE, MODE_DEFAULT));
    }

    public static void setMode(Context ctx, boolean wallpaper) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODE, wallpaper ? MODE_WALLPAPER : MODE_DEFAULT).apply();
    }

    // ==================== 目录与文件 ====================

    public static File getWallpaperDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        return new File(base, DIR_NAME);
    }

    private static File ensureWallpaperDir(Context ctx) {
        File dir = getWallpaperDir(ctx);
        if (!dir.exists()) dir.mkdirs();
        return dir.exists() ? dir : null;
    }

    /** 扫描当前壁纸文件（按目录现存 wallpaper.{ext}），无返回 null */
    public static File findWallpaperFile(Context ctx) {
        File dir = getWallpaperDir(ctx);
        if (dir == null || !dir.isDirectory()) return null;
        for (String ext : VIDEO_EXTS) {          // 视频优先（乐酷同优先级）
            File f = new File(dir, FILE_PREFIX + ext);
            if (f.isFile() && f.length() > 0) return f;
        }
        for (String ext : IMAGE_EXTS) {
            File f = new File(dir, FILE_PREFIX + ext);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    /** 是否已设置壁纸（设置页状态文案用） */
    public static boolean hasWallpaper(Context ctx) {
        return findWallpaperFile(ctx) != null;
    }

    /** 壁纸是否为视频（渲染层分派用；文件不存在返回 false） */
    public static boolean isVideoWallpaper(Context ctx) {
        File f = findWallpaperFile(ctx);
        if (f == null) return false;
        String name = f.getName().toLowerCase();
        for (String ext : VIDEO_EXTS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    // ==================== 复制（换壁纸即覆盖） ====================

    /**
     * 将选中文件复制为歌词动效壁纸（静态方法，FilePicker 选中时直接调用）
     * 非支持扩展名返回 false。乐酷 copyWallpaperFile 同机制：删旧→复制。
     */
    public static boolean copy(Context ctx, String srcPath) {
        File src = new File(srcPath);
        if (!src.isFile()) return false;

        String ext = getSupportedExt(src.getName());
        if (ext == null) return false;

        File dir = ensureWallpaperDir(ctx);
        if (dir == null) return false;

        // 删除全扩展名旧壁纸（换壁纸即覆盖，无需清除入口）
        String[] all = new String[IMAGE_EXTS.length + VIDEO_EXTS.length];
        System.arraycopy(IMAGE_EXTS, 0, all, 0, IMAGE_EXTS.length);
        System.arraycopy(VIDEO_EXTS, 0, all, IMAGE_EXTS.length, VIDEO_EXTS.length);
        for (String e : all) {
            File old = new File(dir, FILE_PREFIX + e);
            if (old.exists()) old.delete();
        }

        File target = new File(dir, FILE_PREFIX + ext);
        try (FileInputStream is = new FileInputStream(src);
             FileOutputStream os = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            if (target.exists()) target.delete();
            return false;
        }
    }

    /** 扩展名是否支持，支持返回小写带点扩展名，否则 null */
    public static String getSupportedExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            String ext = fileName.substring(dot).toLowerCase();
            for (String e : IMAGE_EXTS) if (e.equals(ext)) return ext;
            for (String e : VIDEO_EXTS) if (e.equals(ext)) return ext;
        }
        return null;
    }
}
