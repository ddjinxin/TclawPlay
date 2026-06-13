package com.jingxin.jingxinmusic.util;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B站API封装
 * 扫码登录 + 收藏夹查询
 * 零第三方依赖，纯HttpURLConnection实现
 */
public class BiliApi {

    private static final String TAG = "BiliApi";

    // B站API基础URL
    private static final String PASSPORT_URL = "https://passport.bilibili.com";
    private static final String API_URL = "https://api.bilibili.com";

    // 扫码登录状态
    public static final int QRCODE_NOT_SCANNED = 0;   // 未扫码
    public static final int QRCODE_SCANNED = 1;        // 已扫码未确认
    public static final int QRCODE_CONFIRMED = 2;      // 已确认
    public static final int QRCODE_EXPIRED = 3;         // 已过期
    public static final int QRCODE_ERROR = -1;          // 请求失败

    // ========== 扫码登录 ==========

    /**
     * 第一步：申请二维码
     * @return QrCodeResult 包含url(二维码内容)和qrcode_key，失败返回null
     */
    public static QrCodeResult getQrCode() {
        try {
            String url = PASSPORT_URL + "/x/passport-login/web/qrcode/generate";
            String json = HttpUtil.getWithHeaders(url, null);
            if (json == null) {
                Log.e(TAG, "申请二维码请求失败");
                return null;
            }
            JSONObject root = new JSONObject(json);
            int code = root.optInt("code", -1);
            if (code != 0) {
                Log.e(TAG, "申请二维码失败: code=" + code + " msg=" + root.optString("message"));
                return null;
            }
            JSONObject data = root.getJSONObject("data");
            QrCodeResult result = new QrCodeResult();
            result.url = data.getString("url");
            result.qrcodeKey = data.getString("qrcode_key");
            return result;
        } catch (Exception e) {
            Log.e(TAG, "申请二维码异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 第二步：轮询扫码状态（同时捕获Cookie）
     * 使用OkHttp + CookieJar，确保能捕获到Set-Cookie（包括重定向过程中的）。
     * Android的HttpURLConnection不暴露Set-Cookie响应头，OkHttp的headers()也可能遗漏重定向中的Cookie，
     * 所以用CookieJar是最可靠的方式。
     */
    public static QrPollResult pollQrCode(String qrcodeKey, BiliConfig config) {
        QrPollResult result = new QrPollResult();
        try {
            String url = PASSPORT_URL + "/x/passport-login/web/qrcode/poll"
                    + "?qrcode_key=" + URLEncoder.encode(qrcodeKey, "UTF-8");

            // 用CookieJar捕获所有响应（含重定向）中的Cookie
            final List<okhttp3.Cookie> capturedCookies = new ArrayList<>();
            okhttp3.CookieJar cookieJar = new okhttp3.CookieJar() {
                @Override
                public void saveFromResponse(okhttp3.HttpUrl url, List<okhttp3.Cookie> cookies) {
                    capturedCookies.addAll(cookies);
                }
                @Override
                public List<okhttp3.Cookie> loadForRequest(okhttp3.HttpUrl url) {
                    return java.util.Collections.emptyList();
                }
            };

            // 不跟随重定向，手动处理，以确保能读取每一层响应的Set-Cookie
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build();

            // 手动处理重定向（最多5次），确保每层响应的Cookie都被CookieJar捕获
            okhttp3.Response response = client.newCall(request).execute();
            int redirectCount = 0;
            while (response.isRedirect() && redirectCount < 5) {
                String location = response.header("Location");
                response.close();
                if (location == null) break;
                okhttp3.Request redirectRequest = new okhttp3.Request.Builder()
                        .url(location)
                        .header("User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build();
                response = client.newCall(redirectRequest).execute();
                redirectCount++;
            }

            // ！！！先读headers再关response！！！
            List<String> setCookieHeaders = new ArrayList<>(response.headers("Set-Cookie"));
            String body = response.body() != null ? response.body().string() : "";
            response.close();

            JSONObject root = new JSONObject(body);
            int code = root.optInt("code", -1);
            JSONObject data = root.optJSONObject("data");

            // B站的poll接口返回结构：
            // 外层code=0 表示请求成功，内层data.code才是真正的扫码状态
            // data.code: 86101=未扫码, 86090=已扫码未确认, 0=确认成功, 86038=已过期
            int innerCode = data != null ? data.optInt("code", -1) : -1;

            if (code == 0 && innerCode == 0) {
                // 扫码确认成功！
                result.status = QRCODE_CONFIRMED;
                if (data != null) {
                    result.refreshToken = data.optString("refresh_token", "");
                }

                // 从CookieJar捕获的cookie中提取（最可靠，覆盖重定向场景）
                Map<String, String> cookies = new HashMap<>();
                for (okhttp3.Cookie cookie : capturedCookies) {
                    String name = cookie.name();
                    if ("SESSDATA".equals(name) || "bili_jct".equals(name)
                            || "DedeUserID".equals(name) || "DedeUserID__ckMd5".equals(name)) {
                        cookies.put(name, cookie.value());
                    }
                }

                // 备份：也从最终响应的Set-Cookie头解析
                for (String cookieHeader : setCookieHeaders) {
                    String[] parts = cookieHeader.split(";");
                    for (String part : parts) {
                        part = part.trim();
                        int eqIdx = part.indexOf('=');
                        if (eqIdx > 0) {
                            String key = part.substring(0, eqIdx).trim();
                            String value = part.substring(eqIdx + 1).trim();
                            if ("SESSDATA".equals(key) || "bili_jct".equals(key)
                                    || "DedeUserID".equals(key) || "DedeUserID__ckMd5".equals(key)) {
                                if (!cookies.containsKey(key)) {
                                    try {
                                        cookies.put(key, URLDecoder.decode(value, "UTF-8"));
                                    } catch (Exception e) {
                                        cookies.put(key, value);
                                    }
                                }
                            }
                        }
                    }
                }

                // 保存到result
                if (cookies.containsKey("SESSDATA")) result.sessdata = cookies.get("SESSDATA");
                if (cookies.containsKey("bili_jct")) result.biliJct = cookies.get("bili_jct");
                if (cookies.containsKey("DedeUserID")) result.dedeUserId = cookies.get("DedeUserID");

                StringBuilder cookieSb = new StringBuilder();
                if (result.sessdata != null) cookieSb.append("SESSDATA=").append(result.sessdata);
                if (result.biliJct != null) {
                    if (cookieSb.length() > 0) cookieSb.append("; ");
                    cookieSb.append("bili_jct=").append(result.biliJct);
                }
                if (result.dedeUserId != null) {
                    if (cookieSb.length() > 0) cookieSb.append("; ");
                    cookieSb.append("DedeUserID=").append(result.dedeUserId);
                }
                result.cookieStr = cookieSb.toString();

                return result;
            }

            // 未成功，根据内层data.code判断状态
            switch (innerCode) {
                case 86038:
                case 86040:  // 某些版本用的过期码
                    result.status = QRCODE_EXPIRED;
                    break;
                case 86090:
                    result.status = QRCODE_SCANNED;
                    break;
                case 86101:
                    result.status = QRCODE_NOT_SCANNED;
                    break;
                default:
                    result.status = QRCODE_ERROR;
                    result.message = "data.code=" + innerCode + " " + root.optString("message", "");
                    break;
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "轮询扫码异常: " + e.getMessage());
            result.status = QRCODE_ERROR;
            result.message = e.getMessage();
            return result;
        }
    }

    // ========== 用户信息 ==========

    /**
     * 获取当前登录用户信息（验证Cookie有效性）
     * @param config B站配置（包含Cookie）
     * @return UserInfo，失败返回null
     */
    public static UserInfo getUserInfo(BiliConfig config) {
        try {
            String url = API_URL + "/x/web-interface/nav";
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", config.getAuthCookie());
            headers.put("Referer", "https://www.bilibili.com");

            String json = HttpUtil.getWithHeaders(url, headers);
            if (json == null) return null;

            JSONObject root = new JSONObject(json);
            int code = root.optInt("code", -1);
            if (code != 0) {
                Log.e(TAG, "获取用户信息失败: code=" + code);
                return null;
            }

            JSONObject data = root.getJSONObject("data");
            UserInfo info = new UserInfo();
            info.mid = data.optLong("mid", 0);
            info.nickname = data.optString("uname", "");
            info.avatarUrl = data.optString("face", "");
            info.isLogin = data.optBoolean("isLogin", false);

            // 同时提取WBI密钥（缓存备用）
            JSONObject wbiImg = data.optJSONObject("wbi_img");
            if (wbiImg != null) {
                String imgUrl = wbiImg.optString("img_url", "");
                String subUrl = wbiImg.optString("sub_url", "");
                if (!imgUrl.isEmpty() && !subUrl.isEmpty()) {
                    updateWbiKeys(imgUrl, subUrl);
                }
            }

            return info;
        } catch (Exception e) {
            Log.e(TAG, "获取用户信息异常: " + e.getMessage());
            return null;
        }
    }

    // ========== WBI签名 ==========

    // WBI打乱表（从B站JS逆向得到，固定值）
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    // 缓存WBI密钥
    private static String cachedImgKey;
    private static String cachedSubKey;
    private static long keyExpireTime;

    /**
     * 从img_url和sub_url中更新WBI密钥
     */
    private static void updateWbiKeys(String imgUrl, String subUrl) {
        cachedImgKey = extractKeyFromUrl(imgUrl);
        cachedSubKey = extractKeyFromUrl(subUrl);
        // 密钥8小时过期
        keyExpireTime = System.currentTimeMillis() + 8 * 60 * 60 * 1000;
    }

    /**
     * 从图片URL中提取密钥部分
     * 例: https://i0.hdslb.com/bfs/wbi/7cd91b5efaaa.jpg → 7cd91b5efaaa
     */
    private static String extractKeyFromUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        // 取最后一个/到.之间的部分
        int lastSlash = url.lastIndexOf('/');
        int dot = url.lastIndexOf('.');
        if (lastSlash >= 0 && dot > lastSlash) {
            return url.substring(lastSlash + 1, dot);
        }
        return url;
    }

    /**
     * 获取WBI混合密钥（mixin_key）
     * 如果缓存过期，先从nav接口刷新
     */
    private static String getMixinKey(BiliConfig config) {
        // 检查缓存有效性
        if (cachedImgKey != null && cachedSubKey != null
                && System.currentTimeMillis() < keyExpireTime) {
            return computeMixinKey(cachedImgKey, cachedSubKey);
        }

        // 缓存过期，从nav接口刷新
        UserInfo info = getUserInfo(config);
        if (info == null || cachedImgKey == null) {
            Log.e(TAG, "刷新WBI密钥失败");
            return "";
        }

        return computeMixinKey(cachedImgKey, cachedSubKey);
    }

    /**
     * 计算mixin_key：将imgKey+subKey拼接后按打乱表重排取前32位
     */
    private static String computeMixinKey(String imgKey, String subKey) {
        String raw = imgKey + subKey;
        char[] chars = new char[32];
        for (int i = 0; i < 32; i++) {
            chars[i] = raw.charAt(MIXIN_KEY_ENC_TAB[i]);
        }
        return new String(chars);
    }

    /**
     * 对请求参数进行WBI签名
     * @param params 请求参数
     * @param config B站配置
     * @return 带w_rid和wts的参数Map
     */
    public static Map<String, String> signWbi(Map<String, String> params, BiliConfig config) {
        String mixinKey = getMixinKey(config);
        if (mixinKey.isEmpty()) {
            // 签名失败，返回原始参数
            Log.w(TAG, "WBI签名失败，返回原始参数");
            return params;
        }

        // 加入时间戳
        Map<String, String> signedParams = new TreeMap<>(params);
        signedParams.put("wts", String.valueOf(System.currentTimeMillis() / 1000));

        // 按key排序拼接参数
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : signedParams.entrySet()) {
            // 过滤特殊字符（!()'）
            String value = entry.getValue().replaceAll("[!'()]", "");
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(entry.getKey())).append('=').append(URLEncoder.encode(value));
        }

        // 拼接mixin_key后计算MD5
        sb.append(mixinKey);
        String wRid = MD5Util.md5(sb.toString());

        signedParams.put("w_rid", wRid);
        return signedParams;
    }

    /**
     * 将参数Map拼接为URL查询字符串
     */
    public static String paramsToQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            try {
                sb.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                sb.append('=');
                sb.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            } catch (Exception e) {
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        return sb.toString();
    }

    // ========== 收藏夹 ==========

    /**
     * 获取用户收藏夹列表
     * @param config B站配置
     * @return 收藏夹列表，失败返回空列表
     */
    public static List<FavoriteFolder> getFavoriteFolders(BiliConfig config) {
        List<FavoriteFolder> folders = new ArrayList<>();
        try {
            String uid = config.getDedeUserId();
            if (uid.isEmpty()) {
                // 没有uid，先获取用户信息
                UserInfo info = getUserInfo(config);
                if (info != null && info.mid > 0) {
                    uid = String.valueOf(info.mid);
                    config.setDedeUserId(uid);
                } else {
                    Log.e(TAG, "获取收藏夹需要用户ID");
                    return folders;
                }
            }

            String url = API_URL + "/x/v3/fav/folder/created/list-all?up_mid=" + uid;
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", config.getAuthCookie());
            headers.put("Referer", "https://www.bilibili.com");

            String json = HttpUtil.getWithHeaders(url, headers);
            if (json == null) return folders;

            JSONObject root = new JSONObject(json);
            int code = root.optInt("code", -1);
            if (code != 0) {
                Log.e(TAG, "获取收藏夹列表失败: code=" + code + " msg=" + root.optString("message"));
                return folders;
            }

            JSONObject data = root.optJSONObject("data");
            if (data == null) return folders;
            JSONArray list = data.optJSONArray("list");
            if (list == null) return folders;

            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                FavoriteFolder folder = new FavoriteFolder();
                folder.id = item.optLong("id", 0);
                folder.title = item.optString("title", "");
                folder.mediaCount = item.optInt("media_count", 0);
                folder.cover = item.optString("cover", "");
                folders.add(folder);
            }
        } catch (Exception e) {
            Log.e(TAG, "获取收藏夹列表异常: " + e.getMessage());
        }
        return folders;
    }

    /**
     * 获取收藏夹内的视频列表（自动翻页）
     * @param folderId 收藏夹ID
     * @param config B站配置
     * @return 视频列表，失败返回空列表
     */
    public static List<FavoriteItem> getFavoriteItems(long folderId, BiliConfig config) {
        List<FavoriteItem> items = new ArrayList<>();
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", config.getAuthCookie());
            headers.put("Referer", "https://www.bilibili.com");

            int page = 1;
            int pageSize = 20;
            boolean hasMore = true;

            while (hasMore) {
                String url = API_URL + "/x/v3/fav/resource/list"
                        + "?media_id=" + folderId
                        + "&pn=" + page
                        + "&ps=" + pageSize
                        + "&platform=web";

                String json = HttpUtil.getWithHeaders(url, headers);
                if (json == null) break;

                JSONObject root = new JSONObject(json);
                int code = root.optInt("code", -1);
                if (code != 0) {
                    Log.e(TAG, "获取收藏夹内容失败: code=" + code);
                    break;
                }

                JSONObject data = root.optJSONObject("data");
                if (data == null) break;

                JSONArray medias = data.optJSONArray("medias");
                if (medias == null || medias.length() == 0) break;

                for (int i = 0; i < medias.length(); i++) {
                    JSONObject media = medias.getJSONObject(i);
                    // 跳过已失效的视频
                    if (media.optInt("attr", 0) == 9) continue;

                    FavoriteItem item = new FavoriteItem();
                    item.id = media.optLong("id", 0);
                    item.bvid = media.optString("bvid", "");
                    item.title = media.optString("title", "");
                    // 提取UP主名称
                    JSONObject upperObj = media.optJSONObject("upper");
                    if (upperObj != null) {
                        item.upperName = upperObj.optString("name", "");
                    }
                    item.duration = media.optLong("duration", 0); // 秒
                    item.cover = media.optString("cover", "");

                    // 提取分P信息：API返回的page是整数（如"page":100），不是对象
                    item.pageCount = media.optInt("page", 1);

                    items.add(item);
                }

                // 判断是否有下一页
                hasMore = data.optBoolean("has_more", false);
                page++;

                // 安全限制：最多翻10页
                if (page > 10) break;
            }
        } catch (Exception e) {
            Log.e(TAG, "获取收藏夹内容异常: " + e.getMessage());
        }
        return items;
    }

    // ========== 视频分P ==========

    /**
     * 获取视频的分P列表
     * @param bvid BV号
     * @param config B站配置
     * @return 分P列表，失败返回空列表
     */
    public static List<VideoPage> getVideoPages(String bvid, BiliConfig config) {
        List<VideoPage> pages = new ArrayList<>();
        try {
            Map<String, String> params = new HashMap<>();
            params.put("bvid", bvid);
            params = signWbi(params, config);

            String url = API_URL + "/x/web-interface/view?" + paramsToQueryString(params);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", config.getAuthCookie());
            headers.put("Referer", "https://www.bilibili.com");

            String json = HttpUtil.getWithHeaders(url, headers);
            if (json == null) return pages;

            JSONObject root = new JSONObject(json);
            if (root.optInt("code", -1) != 0) return pages;

            JSONObject data = root.getJSONObject("data");
            JSONArray pagesArr = data.optJSONArray("pages");
            if (pagesArr == null) {
                // 单P视频，从顶层取cid
                VideoPage vp = new VideoPage();
                vp.cid = data.optLong("cid", 0);
                vp.page = 1;
                vp.part = data.optString("title", "");
                vp.duration = data.optLong("duration", 0);
                if (vp.cid > 0) pages.add(vp);
                return pages;
            }

            for (int i = 0; i < pagesArr.length(); i++) {
                JSONObject p = pagesArr.getJSONObject(i);
                VideoPage vp = new VideoPage();
                vp.cid = p.optLong("cid", 0);
                vp.page = p.optInt("page", i + 1);
                vp.part = p.optString("part", "");
                vp.duration = p.optLong("duration", 0);
                if (vp.cid > 0) pages.add(vp);
            }
        } catch (Exception e) {
            Log.e(TAG, "获取视频分P异常: " + e.getMessage());
        }
        return pages;
    }

    // ========== 音频播放 ==========

    /**
     * 根据BV号获取视频cid（第一P）
     * @param bvid BV号
     * @param config B站配置
     * @return cid，失败返回0
     */
    public static long getCid(String bvid, BiliConfig config) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("bvid", bvid);
            params = signWbi(params, config);

            String url = API_URL + "/x/web-interface/view?" + paramsToQueryString(params);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", config.getAuthCookie());
            headers.put("Referer", "https://www.bilibili.com");

            String json = HttpUtil.getWithHeaders(url, headers);
            if (json == null) return 0;

            JSONObject root = new JSONObject(json);
            if (root.optInt("code", -1) != 0) {
                Log.e(TAG, "获取cid失败: " + root.optString("message"));
                return 0;
            }

            JSONObject data = root.getJSONObject("data");
            // 取第一P的cid
            long cid = data.optLong("cid", 0);
            return cid;
        } catch (Exception e) {
            Log.e(TAG, "获取cid异常: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 获取音频流URL
     * BV号 → cid → playurl → DASH音频流
     * @param bvid BV号
     * @param config B站配置
     * @return AudioPlayInfo，失败返回null
     */
    public static AudioPlayInfo getAudioPlayInfo(String bvid, BiliConfig config) {
        // 第一步：获取cid
        long cid = getCid(bvid, config);
        if (cid == 0) {
            Log.e(TAG, "获取cid失败，无法播放: " + bvid);
            return null;
        }
        return getAudioPlayInfo(bvid, cid, config);
    }

    /**
     * 获取音频流URL（已知cid）
     * @param bvid BV号
     * @param cid 视频cid
     * @param config B站配置
     * @return AudioPlayInfo，失败返回null
     */
    public static AudioPlayInfo getAudioPlayInfo(String bvid, long cid, BiliConfig config) {
        try {
            // WBI签名请求playurl
            Map<String, String> params = new HashMap<>();
            params.put("bvid", bvid);
            params.put("cid", String.valueOf(cid));
            params.put("fnver", "0");
            params.put("fnval", "16");    // 请求DASH格式
            params.put("fourk", "1");
            params.put("platform", "pc");
            params = signWbi(params, config);

            String url = API_URL + "/x/player/playurl?" + paramsToQueryString(params);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", config.getAuthCookie());
            headers.put("Referer", "https://www.bilibili.com");

            String json = HttpUtil.getWithHeaders(url, headers);
            if (json == null) return null;

            JSONObject root = new JSONObject(json);
            if (root.optInt("code", -1) != 0) {
                Log.e(TAG, "获取playurl失败: " + root.optString("message"));
                return null;
            }

            JSONObject data = root.getJSONObject("data");
            AudioPlayInfo info = new AudioPlayInfo();
            info.cid = cid;
            info.quality = data.optInt("quality", 0);

            // 优先从DASH格式提取音频
            JSONObject dash = data.optJSONObject("dash");
            if (dash != null) {
                JSONArray audioArr = dash.optJSONArray("audio");
                if (audioArr != null && audioArr.length() > 0) {
                    // 选第一个音频流（通常是最高质量）
                    // 30250=杜比全景声, 30251=Hi-Res无损, 30280=320kbps, 30232=132kbps, 30216=64kbps
                    int bestBandwidth = -1;
                    JSONObject bestAudio = null;
                    for (int i = 0; i < audioArr.length(); i++) {
                        JSONObject audio = audioArr.getJSONObject(i);
                        int bandwidth = audio.optInt("bandwidth", 0);
                        if (bandwidth > bestBandwidth) {
                            bestBandwidth = bandwidth;
                            bestAudio = audio;
                        }
                    }
                    if (bestAudio != null) {
                        info.audioUrl = bestAudio.getString("baseUrl");
                        if (info.audioUrl == null || info.audioUrl.isEmpty()) {
                            // 备用：从backupUrl取
                            JSONArray backupUrls = bestAudio.optJSONArray("backupUrl");
                            if (backupUrls != null && backupUrls.length() > 0) {
                                info.audioUrl = backupUrls.getString(0);
                            }
                        }
                        info.audioQuality = bestAudio.optInt("id", 0);
                    }
                }
            }

            // 没有DASH音频，尝试从非DASH格式取（fallback）
            if (info.audioUrl == null || info.audioUrl.isEmpty()) {
                JSONArray durl = data.optJSONArray("durl");
                if (durl != null && durl.length() > 0) {
                    // 非DASH格式，直接取视频URL（包含画面+声音，ExoPlayer只播音频也行）
                    JSONObject durlItem = durl.getJSONObject(0);
                    info.audioUrl = durlItem.optString("url", "");
                    info.audioQuality = 0;
                }
            }

            if (info.audioUrl == null || info.audioUrl.isEmpty()) {
                Log.e(TAG, "未获取到音频流URL: " + bvid);
                return null;
            }

            // 音频URL有效期约2小时
            info.expireTime = System.currentTimeMillis() + 110 * 60 * 1000; // 110分钟
            info.bvid = bvid;

            return info;
        } catch (Exception e) {
            Log.e(TAG, "获取音频流异常: " + e.getMessage());
            return null;
        }
    }

    // ========== 数据类 ==========

    /** 二维码申请结果 */
    public static class QrCodeResult {
        public String url;         // 二维码内容（用B站APP扫码）
        public String qrcodeKey;   // 轮询用的key
    }

    /** 扫码轮询结果 */
    public static class QrPollResult {
        public int status;              // 状态码
        public String message;          // 错误信息
        public String refreshToken;     // 刷新令牌
        public String cookieStr;        // 完整Cookie字符串
        public String sessdata;         // SESSDATA
        public String biliJct;          // bili_jct
        public String dedeUserId;       // DedeUserID
    }

    /** 用户信息 */
    public static class UserInfo {
        public long mid;            // 用户ID
        public String nickname;     // 昵称
        public String avatarUrl;    // 头像URL
        public boolean isLogin;     // 是否已登录
    }

    /** 音频播放信息 */
    public static class AudioPlayInfo {
        public String bvid;         // BV号
        public long cid;            // 视频cid
        public String audioUrl;     // 音频流URL
        public int audioQuality;    // 音频质量ID
        public int quality;         // 视频质量
        public long expireTime;     // URL过期时间（毫秒时间戳）
    }

    /** 视频分P信息 */
    public static class VideoPage {
        public long cid;            // 分P的cid
        public int page;            // 分P序号（从1开始）
        public String part;         // 分P标题
        public long duration;       // 分P时长（秒）
    }

    /** 收藏夹 */
    public static class FavoriteFolder {
        public long id;             // 收藏夹ID
        public String title;        // 收藏夹名称
        public int mediaCount;      // 视频数量
        public String cover;        // 封面URL
    }

    /** 收藏夹内的视频项 */
    public static class FavoriteItem {
        public long id;             // 视频aid
        public String bvid;         // BV号
        public String title;        // 标题（HTML格式，显示时需去除标签）
        public String upperName;    // UP主名称
        public long duration;       // 时长（秒）
        public String cover;        // 封面URL
        public int pageCount;       // 分P数量
    }

    // ========== MD5工具类 ==========

    /**
     * 简易MD5工具，供WBI签名使用
     */
    private static class MD5Util {
        static String md5(String input) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] bytes = md.digest(input.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes) {
                    sb.append(String.format("%02x", b & 0xff));
                }
                return sb.toString();
            } catch (Exception e) {
                Log.e(TAG, "MD5计算失败: " + e.getMessage());
                return "";
            }
        }
    }
}
