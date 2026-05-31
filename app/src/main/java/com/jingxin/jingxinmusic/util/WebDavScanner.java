package com.jingxin.jingxinmusic.util;

import android.util.Log;

import com.jingxin.jingxinmusic.model.Song;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * WebDAV 音乐扫描器
 * 扫描指定WebDAV目录，返回子项列表（目录/文件）
 * 可逐级浏览，也可递归扫描所有音乐文件
 */
public class WebDavScanner {

    private static final String TAG = "WebDavScanner";

    // 支持的音乐文件扩展名
    private static final String[] MUSIC_EXTENSIONS = {
            ".mp3", ".flac", ".wav", ".aac", ".ogg", ".m4a", ".wma", ".ape",
            ".opus", ".alac", ".dts", ".dsf", ".dff", ".ac3"
    };

    /**
     * 目录或文件项，用于逐级浏览
     */
    public static class DavItem {
        public String name;          // 显示名
        public String path;          // 完整WebDAV路径
        public String url;           // 完整URL
        public boolean isDirectory;  // 是否目录
        public long size;            // 文件大小
        public long modified;        // 修改时间
        public String contentType;   // MIME类型

        public DavItem(String name, String path, String url, boolean isDirectory,
                       long size, long modified, String contentType) {
            this.name = name;
            this.path = path;
            this.url = url;
            this.isDirectory = isDirectory;
            this.size = size;
            this.modified = modified;
            this.contentType = contentType;
        }
    }

    private final Sardine sardine;
    private final WebDavConfig config;

    public WebDavScanner(WebDavConfig config) {
        this.config = config;
        this.sardine = new OkHttpSardine();
        if (!config.getUsername().isEmpty()) {
            sardine.setCredentials(config.getUsername(), config.getPassword());
        }
    }

    /**
     * 测试WebDAV连接是否可用
     * @return 错误信息，null表示连接成功
     */
    public String testConnection() {
        try {
            String url = config.getMusicUrl();
            if (url.isEmpty()) return "未配置服务器地址";
            List<DavResource> resources = sardine.list(url, 0);
            if (resources == null || resources.isEmpty()) {
                return "目录不存在或为空";
            }
            return null; // 成功
        } catch (Exception e) {
            Log.e(TAG, "WebDAV连接测试失败: " + e.getMessage());
            String msg = e.getMessage();
            if (msg != null && msg.contains("401")) return "认证失败，请检查用户名和密码";
            if (msg != null && msg.contains("404")) return "目录不存在，请检查路径";
            if (msg != null && msg.contains("UnknownHost")) return "无法连接服务器，请检查地址";
            return "连接失败: " + (msg != null ? msg : "未知错误");
        }
    }

    /**
     * 确保目录URL以/结尾（WebDAV协议要求）
     */
    private String ensureTrailingSlash(String url) {
        if (url == null || url.isEmpty()) return url;
        if (!url.endsWith("/")) {
            url = url + "/";
        }
        return url;
    }

    /**
     * 对URL路径部分的中文和特殊字符进行编码
     * 保留URL结构（协议://域名:端口/路径）
     */
    private String encodeUrlPath(String url) {
        try {
            URI uri = new URI(url);
            // 对路径部分按/分段编码
            String path = uri.getRawPath();
            if (path == null) return url;

            String[] segments = path.split("/", -1);
            StringBuilder encodedPath = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) encodedPath.append("/");
                if (segments[i].isEmpty()) continue;
                // 检查是否已编码（包含%后跟两个十六进制字符）
                if (segments[i].matches(".*%[0-9A-Fa-f]{2}.*")) {
                    // 已编码，保留原样
                    encodedPath.append(segments[i]);
                } else {
                    // 未编码，进行编码
                    encodedPath.append(URLEncoder.encode(segments[i], "UTF-8")
                            .replace("+", "%20")); // 空格用%20而非+
                }
            }
            // 确保路径以/结尾（目录）
            if (path.endsWith("/") && !encodedPath.toString().endsWith("/")) {
                encodedPath.append("/");
            }

            // 重建URL
            StringBuilder result = new StringBuilder();
            result.append(uri.getScheme()).append("://");
            if (uri.getHost() != null) {
                result.append(uri.getHost());
                if (uri.getPort() > 0) {
                    result.append(":").append(uri.getPort());
                }
            }
            result.append(encodedPath);
            return result.toString();
        } catch (Exception e) {
            Log.w(TAG, "URL编码失败，使用原始URL: " + e.getMessage());
            return url;
        }
    }

    /**
     * 列出指定目录下的子项（目录和音乐文件）
     * 用于逐级浏览模式
     * @param dirUrl 目录的完整URL，null则使用配置的根目录
     * @return 子项列表
     */
    public List<DavItem> listDirectory(String dirUrl) {
        List<DavItem> items = new ArrayList<>();
        try {
            String url = dirUrl != null ? dirUrl : config.getMusicUrl();
            // 确保目录URL以/结尾
            url = ensureTrailingSlash(url);

            Log.d(TAG, "listDirectory: url=" + url);

            // 使用depth=1列出直接子项
            List<DavResource> resources = sardine.list(url, 1);
            Log.d(TAG, "listDirectory: 返回资源数=" + (resources != null ? resources.size() : 0));
            if (resources == null) return items;

            // 获取服务器基础URL（协议+域名+端口）
            String baseUrl = extractBaseUrl(url);

            // 跳过第一个（当前目录自身）
            for (int i = 1; i < resources.size(); i++) {
                DavResource res = resources.get(i);
                String name = res.getName();
                if (name == null || name.isEmpty()) continue;

                boolean isDir = res.isDirectory();
                // 构建子项完整URL：用href路径拼接到baseUrl
                String hrefPath = res.getPath();
                if (hrefPath == null || hrefPath.isEmpty()) {
                    hrefPath = res.getHref().toString();
                }
                String resUrl = buildFullUrl(baseUrl, hrefPath);

                // 如果是目录，确保URL以/结尾
                if (isDir) {
                    resUrl = ensureTrailingSlash(resUrl);
                }

                // 解码显示名称（中文目录名可能是URL编码的）
                String displayName = decodeName(name);

                Log.d(TAG, "  item: name=" + name + " displayName=" + displayName
                        + " isDir=" + isDir + " href=" + res.getHref() + " path=" + hrefPath
                        + " resUrl=" + resUrl);

                // 对URL路径中的中文等特殊字符进行编码，确保播放时可用
                if (!isDir) {
                    resUrl = encodeUrlPath(resUrl);
                }

                if (isDir) {
                    // 所有目录都显示
                    items.add(new DavItem(displayName, hrefPath, resUrl, true,
                            0, res.getModified() != null ? res.getModified().getTime() : 0,
                            res.getContentType()));
                } else if (isMusicFile(name)) {
                    // 只显示音乐文件
                    items.add(new DavItem(displayName, hrefPath, resUrl, false,
                            res.getContentLength(),
                            res.getModified() != null ? res.getModified().getTime() : 0,
                            res.getContentType()));
                }
            }

            Log.d(TAG, "列出目录 " + url + " 共 " + items.size() + " 项");
        } catch (Exception e) {
            Log.e(TAG, "列出目录失败: " + e.getMessage(), e);
        }
        return items;
    }

    /**
     * 从URL中提取基础URL（协议+域名+端口）
     */
    private String extractBaseUrl(String url) {
        try {
            URI uri = new URI(url);
            StringBuilder sb = new StringBuilder();
            sb.append(uri.getScheme()).append("://");
            if (uri.getHost() != null) {
                sb.append(uri.getHost());
                if (uri.getPort() > 0) {
                    sb.append(":").append(uri.getPort());
                }
            }
            return sb.toString();
        } catch (URISyntaxException e) {
            // 回退：简单提取
            int pathStart = url.indexOf("/", url.indexOf("//") + 2);
            if (pathStart > 0) return url.substring(0, pathStart);
            return url;
        }
    }

    /**
     * 用baseUrl + href路径构建完整URL
     * 处理href可能是绝对路径(/path)或完整URL(http://...)的情况
     */
    private String buildFullUrl(String baseUrl, String href) {
        if (href == null || href.isEmpty()) return baseUrl;
        // href是完整URL
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        // href是绝对路径
        if (href.startsWith("/")) {
            return baseUrl + href;
        }
        // 相对路径
        return baseUrl + "/" + href;
    }

    /**
     * 解码URL编码的名称
     */
    private String decodeName(String name) {
        if (name == null) return "";
        try {
            return URLDecoder.decode(name, "UTF-8");
        } catch (Exception e) {
            return name;
        }
    }

    /**
     * 将DavItem（音乐文件）转换为Song对象
     */
    public static Song davItemToSong(DavItem item, long id) {
        Song song = new Song();
        song.id = id;
        song.title = nameWithoutExtension(item.name);
        song.artist = "";
        song.album = "";
        song.duration = 0;
        song.filePath = item.path;
        song.contentUri = item.url;
        song.displayName = song.title;
        return song;
    }

    /**
     * 判断文件是否为音乐文件
     */
    public static boolean isMusicFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String ext : MUSIC_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * 去掉文件扩展名
     */
    public static String nameWithoutExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) return name.substring(0, dot);
        return name;
    }
}
