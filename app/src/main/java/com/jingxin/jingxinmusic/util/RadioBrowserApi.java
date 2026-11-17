package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.jingxin.jingxinmusic.model.Song;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Radio Browser 电台目录 API
 * 零第三方依赖，纯 HttpURLConnection（HttpUtil）+ org.json 实现
 * 全球社区电台目录，免费无需 key；镜像轮询 + 离线缓存
 */
public class RadioBrowserApi {

    private static final String TAG = "RadioBrowserApi";
    // 全球镜像（DE/NL/FR/US 各一个主镜像，按序轮询）
    private static final String[] MIRRORS = {
            "https://de1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info",
            "https://fr1.api.radio-browser.info",
            "https://at1.api.radio-browser.info"
    };
    /** UA 必须自定义（官方要求区分客户端做统计） */
    private static final String UA = "JingxinMusic/1.0 (Android)";
    private static int mirrorIndex = 0;

    // 常用标签（电台页横滑分类）
    public static final String[] TAGS = {
            "热门", "中国", "香港", "台湾", "新闻", "古典", "爵士", "摇滚", "电子", "轻音乐", "乡村", "流行"
    };
    public static final String TAG_HOT = "热门";
    public static final String TAG_FAVORITE = "收藏";

    private RadioBrowserApi() {}

    /**
     * 获取热门电台（votes 社区投票排序，质量过滤）
     */
    public static List<Song> fetchHot(int limit) {
        return query("/json/stations/search?limit=" + limit
                + "&hidebroken=true&order=votes&reverse=true");
    }

    /**
     * 按国家获取电台（返回可播直链 url_resolved）
     */
    public static List<Song> fetchByCountry(String country, int limit) {
        return query("/json/stations/bycountry/" + urlEncode(country)
                + "?limit=" + limit + "&hidebroken=true&order=votes&reverse=true");
    }

    /**
     * 港台目录：中国香港 + 中国台湾（社区库按 countrycode 分列收录，
     * bycountry/China 查不到，各建独立目录查询）
     */
    /** 港台目录标签（社区库 countrycode=HK/TW 分列，bycountry/China 查不到） */
    public static final String TAG_HK = "香港";
    public static final String TAG_TW = "台湾";

    /** 中国香港电台（countrycode=HK，票数排序） */
    public static List<Song> fetchHk(int limit) {
        return query("/json/stations/bycountrycodeexact/HK"
                + "?limit=" + limit + "&hidebroken=true&order=votes&reverse=true");
    }

    /** 中国台湾电台（countrycode=TW，票数排序） */
    public static List<Song> fetchTw(int limit) {
        return query("/json/stations/bycountrycodeexact/TW"
                + "?limit=" + limit + "&hidebroken=true&order=votes&reverse=true");
    }

    /**
     * 按标签获取电台
     * 界面显示中文分类名，库内标签为英文（国际社区库，中文标签几乎无收录）——查询前映射
     */
    public static List<Song> fetchByTag(String tag, int limit) {
        return query("/json/stations/bytag/" + urlEncode(queryTag(tag))
                + "?limit=" + limit + "&hidebroken=true&order=votes&reverse=true");
    }

    /** 界面中文分类名 → 库内英文标签；未映射的原样透传（收藏等本地分类不走这里） */
    private static String queryTag(String display) {
        switch (display) {
            case "新闻":   return "news";
            case "古典":   return "classical";
            case "爵士":   return "jazz";
            case "摇滚":   return "rock";
            case "电子":   return "electronic";
            case "轻音乐": return "chillout";
            case "乡村":   return "country";
            case "流行":   return "pop";
            default:       return display;
        }
    }

    /**
     * 关键词搜索
     */
    public static List<Song> search(String keyword, int limit) {
        return query("/json/stations/search?name=" + urlEncode(keyword)
                + "&limit=" + limit + "&hidebroken=true&order=votes&reverse=true");
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ========== 镜像轮询请求 ==========

    /**
     * 顺序轮询 4 个镜像直到成功；失败切下一个。
     * （最初版本的简单逻辑——熔断版曾引入加载变慢问题，回退保持简单；
     *   坏镜像会各烧一次超时，属可接受：目录层有缓存/种子兜底）
     */
    private static String requestWithFailover(String path) {
        for (int attempt = 0; attempt < MIRRORS.length; attempt++) {
            String base = MIRRORS[mirrorIndex];
            String url = base + path;
            try {
                String json = HttpUtil.getWithHeaders(url, headers());
                if (json != null) {
                    return json;
                }
                Log.w(TAG, "镜像无响应: " + base);
            } catch (Exception e) {
                Log.w(TAG, "镜像请求异常: " + base + " " + e.getMessage());
            }
            // 切下一个镜像
            mirrorIndex = (mirrorIndex + 1) % MIRRORS.length;
        }
        return null;
    }

    private static java.util.Map<String, String> headers() {
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("User-Agent", UA);
        return h;
    }

    /**
     * 执行查询并解析为 Song 列表（sourceType=SOURCE_RADIO）
     */
    private static List<Song> query(String path) {
        String json = requestWithFailover(path);
        if (json == null) {
            Log.e(TAG, "电台目录请求全部镜像失败");
            return null;
        }
        List<Song> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String uuid = o.optString("stationuuid", "");
                String name = o.optString("name", "");
                // url_resolved 才是真实可播流（url 可能是网页）
                String stream = o.optString("url_resolved", o.optString("url", ""));
                if (uuid.isEmpty() || name.isEmpty() || stream.isEmpty()) continue;
                String favicon = o.optString("favicon", "");
                String codec = o.optString("codec", "");
                int bitrate = o.optInt("bitrate", 0);
                String codecBr = codecBr(codec, bitrate);
                list.add(Song.fromRadio(uuid, name, stream, favicon, codecBr));
            }
        } catch (Exception e) {
            Log.e(TAG, "电台目录解析失败: " + e.getMessage());
            return null;
        }
        return list;
    }

    /**
     * 组装 "MP3 128k" 备注串；未知 codec/bitrate 时返回空
     */
    public static String codecBr(String codec, int bitrate) {
        if (codec == null || codec.isEmpty() || "UNKNOWN".equalsIgnoreCase(codec)) {
            return bitrate > 0 ? (bitrate + "k") : "";
        }
        String c = codec;
        if (c.startsWith("audio/")) c = c.substring("audio/".length());
        String br = bitrate > 0 ? (" " + bitrate + "k") : "";
        return c + br;
    }

    /**
     * 解析缓存的台列表 JSON（stationuuid/name/url_resolved/favicon/codec/bitrate）
     * 供 MainFragment 电台 Tab 读缓存兜底用
     */
    public static List<Song> parseStationJson(String json) {
        List<Song> list = new ArrayList<>();
        if (json == null) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String uuid = o.optString("stationuuid", "");
                String name = o.optString("name", "");
                String stream = o.optString("url_resolved", o.optString("url", ""));
                if (uuid.isEmpty() || name.isEmpty() || stream.isEmpty()) continue;
                String codec = codecBr(o.optString("codec", ""), o.optInt("bitrate", 0));
                list.add(Song.fromRadio(uuid, name, stream, o.optString("favicon", ""), codec));
            }
        } catch (Exception ignored) {}
        return list;
    }

    // ========== favicon 落盘缓存（台卡/播放页显示时按需读写，跨进程持久） ==========

    /** 缓存目录：filesDir/radio_favicons/<md5(url)>.img */
    private static File faviconDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "radio_favicons");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String faviconCacheName(String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString() + ".img";
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode()) + ".img";
        }
    }

    /**
     * 读落盘缓存的 favicon（不打网络）
     * @return Bitmap 或 null（无缓存/解码失败）
     */
    public static Bitmap loadFavicon(Context ctx, String faviconUrl) {
        if (faviconUrl == null || faviconUrl.isEmpty()) return null;
        try {
            File f = new File(faviconDir(ctx), faviconCacheName(faviconUrl));
            if (!f.exists() || f.length() == 0) return null;
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 写单个 favicon 落盘（下载成功后调用；上限 512KB 防异常大图）
     */
    public static void saveFavicon(Context ctx, String faviconUrl, byte[] data) {
        if (faviconUrl == null || data == null || data.length == 0 || data.length > 512 * 1024) return;
        try {
            File f = new File(faviconDir(ctx), faviconCacheName(faviconUrl));
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
        } catch (Exception ignored) {}
    }

    // ========== 种子清单（断网/首启兜底） ==========

    /**
     * 读取打包的中国公开台种子清单（assets/radio_china.json）
     * 目录失败时兜底，也是"中国"分类的种子数据
     */
    public static List<Song> loadSeed(Context ctx) {
        try {
            String json = FileUtil.readAsset(ctx, "radio_china.json");
            if (json == null) return null;
            JSONArray arr = new JSONArray(json);
            List<Song> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(Song.fromRadio(
                        o.optString("uuid"),
                        o.optString("name"),
                        o.optString("stream"),
                        o.optString("favicon", ""),
                        o.optString("codec", "")));
            }
            return list;
        } catch (Exception e) {
            Log.e(TAG, "读取电台种子清单失败: " + e.getMessage());
            return null;
        }
    }

    // ========== 自定义电台清单（公共 Download/jingxin_radio.json，用户可自行编辑） ==========

    /** 清单文件名：放在 Download/jingxinmusic/radio/ 目录（旧位置 Download/ 根下兼容） */
    public static final String CUSTOM_FILE_NAME = "jingxin_radio.json";
    /** 台标目录：Download/jingxinmusic/radio/（清单与"电台名.png"台标同目录） */
    public static final String CUSTOM_DIR = "jingxinmusic/radio";
    /** 自定义清单在电台页根层的唯一入口目录名（与内置分类同级；内部再分文档分类） */
    public static final String CUSTOM_ROOT = "个人";
    /** 分类字段缺省归类 */
    private static final String CUSTOM_DEFAULT_CATEGORY = "未分类";
    /** 台标支持的图片扩展名 */
    private static final String[] LOGO_EXTS = { ".png", ".jpg", ".jpeg", ".webp" };

    /** 解析结果缓存：文件身份戳（mtime+length）变了才重新解析 */
    private static volatile long customStamp = -1;
    private static volatile java.util.LinkedHashMap<String, List<Song>> customMap =
            new java.util.LinkedHashMap<>();

    /** 台标文件映射缓存：目录内容变了才重扫（key=电台名小写，value=图片文件） */
    private static volatile long logoStamp = -1;
    private static volatile java.util.HashMap<String, File> logoMap = new java.util.HashMap<>();

    /** 台标/清单目录：Download/jingxinmusic/radio（不存在返回 null） */
    private static File radioDataDir() {
        try {
            File dir = new File(android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    CUSTOM_DIR);
            return dir.isDirectory() ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static File customFile() {
        try {
            // 新位置：Download/jingxinmusic/radio/jingxin_radio.json
            File dir = radioDataDir();
            if (dir != null) {
                File f = new File(dir, CUSTOM_FILE_NAME);
                if (f.exists() && f.length() > 0) return f;
            }
            // 旧位置兼容：Download/jingxin_radio.json
            File old = new File(android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    CUSTOM_FILE_NAME);
            return (old.exists() && old.length() > 0) ? old : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析（带缓存）自定义清单：分类 → 电台列表
     * 格式：[{"name":..,"category":..,"stream":..,"codec":..(选),"favicon":..(选)}]
     * 与内置分类重名的自定义分类跳过；uuid=md5(流地址)，收藏跨文件改动稳定
     */
    private static java.util.LinkedHashMap<String, List<Song>> parseCustom(Context ctx) {
        File f = customFile();
        if (f == null) {
            customStamp = -1;
            customMap = new java.util.LinkedHashMap<>();
            return customMap;
        }
        long stamp = (f.lastModified() << 8) ^ f.length();
        if (stamp == customStamp) return customMap;
        java.util.LinkedHashMap<String, List<Song>> map = new java.util.LinkedHashMap<>();
        try {
            String json = FileUtil.readFile(f);
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.optString("name", "");
                String stream = o.optString("stream", "");
                if (name.isEmpty() || stream.isEmpty()) continue;
                String category = o.optString("category", "");
                if (category.isEmpty()) category = CUSTOM_DEFAULT_CATEGORY;
                // 与内置分类重名：跳过（fetch 分支按名字路由，避免歧义）
                if (isBuiltInTag(category)) continue;
                Song s = Song.fromRadio("custom_" + md5Hex(stream), name, stream,
                        o.optString("favicon", ""), o.optString("codec", ""));
                List<Song> list = map.get(category);
                if (list == null) { list = new ArrayList<>(); map.put(category, list); }
                list.add(s);
            }
            customStamp = stamp;
            customMap = map;
        } catch (Exception e) {
            Log.w(TAG, "自定义电台清单解析失败: " + e.getMessage());
            // 解析失败沿用上次结果（或空），不抛错——静默降级
        }
        return customMap;
    }

    private static boolean isBuiltInTag(String tag) {
        for (String t : TAGS) if (t.equals(tag)) return true;
        return TAG_FAVORITE.equals(tag);
    }

    /** 自定义清单中的分类名（按文档出现顺序）；无文档/无有效条目返回空列表 */
    public static List<String> customCategories(Context ctx) {
        java.util.LinkedHashMap<String, List<Song>> map = parseCustom(ctx);
        return new ArrayList<>(map.keySet());
    }

    /** 自定义清单是否有可显示内容（决定根层"个人"目录卡是否出现） */
    public static boolean hasCustomData(Context ctx) {
        return !parseCustom(ctx).isEmpty();
    }

    /** 该分类是否来自自定义清单（fetch 路由判断） */
    public static boolean isCustomCategory(Context ctx, String tag) {
        if (tag == null) return false;
        return parseCustom(ctx).containsKey(tag);
    }

    /** 自定义分类下的电台列表（无则空列表，不返回 null） */
    public static List<Song> loadCustomStations(Context ctx, String category) {
        List<Song> list = parseCustom(ctx).get(category);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    private static String md5Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // ========== 本地台标（Download/jingxinmusic/radio/电台名.png，用户可自行放置） ==========

    /**
     * 扫描台标目录建映射：电台名(小写trim) → 图片文件
     * 目录内容变化（文件名+大小集合）自动重扫；不支持大小写差异匹配
     */
    private static java.util.HashMap<String, File> parseLogoMap() {
        java.util.HashMap<String, File> map = new java.util.HashMap<>();
        File dir = radioDataDir();
        if (dir == null) {
            logoStamp = -1;
            logoMap = map;
            return map;
        }
        File[] files = dir.listFiles();
        if (files == null) return logoMap;
        long stamp = 7;
        for (File f : files) {
            String n = f.getName().toLowerCase();
            if (!isLogoName(n)) continue;
            stamp = stamp * 31 + n.hashCode() * 37L + f.length();
        }
        if (stamp == logoStamp) return logoMap;
        for (File f : files) {
            String n = f.getName().toLowerCase();
            if (!isLogoName(n)) continue;
            map.put(logoKey(n), f);
        }
        logoStamp = stamp;
        logoMap = map;
        return map;
    }

    private static boolean isLogoName(String lowerName) {
        for (String e : LOGO_EXTS) {
            if (lowerName.endsWith(e)) return true;
        }
        return false;
    }

    /** 文件名去扩展名 → 匹配键（trim + 小写） */
    private static String logoKey(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.trim().toLowerCase();
    }

    /**
     * 读本地台标（Download/jingxinmusic/radio/电台名.png/.jpg/.jpeg/.webp），不读网络
     * @return 降采样 Bitmap（目标 ≤512px，防超大图占内存）或 null（无文件/解码失败）
     */
    public static Bitmap loadLocalLogo(String stationName) {
        if (stationName == null || stationName.isEmpty()) return null;
        try {
            File f = parseLogoMap().get(stationName.trim().toLowerCase());
            if (f == null || !f.exists()) return null;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 512 && o.outHeight / (sample * 2) >= 512) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== 目录结果缓存（离线浏览） ==========

    private static File cacheDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "radio_cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String cacheFile(Context ctx, String key) {
        return new File(cacheDir(ctx), key + ".json").getAbsolutePath();
    }

    /**
     * 写入带时间戳的目录缓存（首次拉取后落盘，断网时读缓存）
     */
    public static void saveCache(Context ctx, String key, String json) {
        try {
            File f = new File(cacheFile(ctx, key));
            try (FileOutputStream fos = new FileOutputStream(f)) {
                String payload = System.currentTimeMillis() + "\n" + json;
                fos.write(payload.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "保存电台缓存失败: " + e.getMessage());
        }
    }

    /**
     * 读取目录缓存；超过 TTL 或不存在返回 null
     */
    public static String readCache(Context ctx, String key, long ttlMs) {
        File f = new File(cacheFile(ctx, key));
        if (!f.exists()) return null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String firstLine = br.readLine();
            if (firstLine == null) return null;
            long ts = Long.parseLong(firstLine.trim());
            if (System.currentTimeMillis() - ts > ttlMs) return null;
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}