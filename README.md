---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '056fc1f8-3f84-42a9-94d0-84217653155a'
  PropagateID: '056fc1f8-3f84-42a9-94d0-84217653155a'
  ReservedCode1: 'd3b1492f-c051-4c0a-a279-2c6f89fd89f8'
  ReservedCode2: 'd3b1492f-c051-4c0a-a279-2c6f89fd89f8'
---

# 静心音乐 / TclawPlay

中文 | [English](#english)

一个简洁的 Android 音乐播放器，专为车机与日常聆听设计。支持本地音乐和 WebDAV 云端音乐，无广告、无追踪、离线优先。

完全通过 AI 对话生成，作者不看代码，只对话。

## 功能特性

### 播放核心
- **本地音乐扫描** — 自动扫描设备音乐，按目录/全部/收藏三种视图浏览
- **WebDAV 云端音乐** — 支持群晖 NAS 等 WebDAV 服务器，分级浏览目录，流媒体播放+本地缓存
- **后台播放** — 前台 Service + 通知栏控制 + 锁屏 MediaSession 控制
- **播放模式** — 顺序 / 随机 / 单曲循环，3秒内按上一首回到开头

### 歌词系统
- **本地歌词** — 支持 KRC（酷狗逐字高亮）和 LRC 格式
- **在线歌词** — 酷狗 KRC 优先，网易云 LRC 备用，自动缓存
- **歌词导出** — KRC 转换为 LRC 格式导出到 Download/lyrics/
- **逐字高亮** — KRC 歌词支持逐字颜色填充，自动折行

### 封面与视觉
- **在线封面** — 内嵌封面优先 → 本地缓存 → 酷狗/网易云在线获取
- **旋转封面** — 播放时唱片旋转，主色调自动提取为背景
- **频谱可视化** — 128根柱状频谱，60fps刷新，三种样式（竖条/圆点/波浪线）
- **沉浸模式** — 长按歌词区域切换，竖屏和横屏两种效果

### 风格系统
- **四种预定义风格** — 春意盎然 / 蔚蓝天地 / 万紫千红 / 高级灰
- **卡片布局** — 本地和云端采用统一的正方形卡片网格布局
- **文件夹封面** — 自动查找目录下封面图，6色渐变循环
- **昼夜主题** — 日间浅绿白配色，夜间暗绿黑配色

### 车机适配
- **悬浮应用** — 声明 SYSTEM_ALERT_WINDOW 权限，支持车机自由窗口模式
- **窗口缩放** — resizeableActivity 支持自由窗口大小调整
- **WebDAV 配置备份** — 自动备份到 Download 目录，换设备一键恢复

### 其他
- **收藏与历史** — 一键收藏，播放历史快速回听
- **自动恢复** — 记忆上次播放歌曲，重启后自动跳转播放页
- **目录播放** — 目录视图中点击歌曲，播放队列自动限定在该目录内

## 技术栈

| 项目 | 技术 |
|------|------|
| 语言 | Java 17 |
| 最低 SDK | 23 (Android 6.0) |
| 目标 SDK | 36 |
| 播放引擎 | Media3 ExoPlayer（强制软件解码，兼容老设备 FLAC） |
| 锁屏控制 | MediaSessionCompat |
| WebDAV | sardine-android + OkHttp + Okio |
| 封面缓存 | 本地文件缓存 + LRU 淘汰 |
| 构建工具 | Gradle 9.0 + AGP 9.0.0 |

## 项目结构

```
app/src/main/java/com/jingxin/jingxinmusic/
├── MainActivity.java              # 歌曲列表页（本地+云端）
├── PlayerActivity.java            # 播放页
├── adapter/
│   ├── SongAdapter.java           # 歌曲列表适配器（目录/歌曲/收藏）
│   └── BrowseAdapter.java         # WebDAV目录浏览适配器
├── model/
│   ├── Song.java                  # 歌曲数据模型
│   ├── BrowseItem.java            # WebDAV浏览项模型
│   └── FolderInfo.java            # 目录信息模型
├── service/
│   └── MusicPlayerService.java    # 播放服务（ExoPlayer+通知+MediaSession+广播）
├── ui/
│   ├── WebDavSettingsActivity.java # WebDAV设置页
│   ├── SquareImageView.java       # 正方形图片裁剪
│   └── FolderCoverView.java       # 文件夹封面视图
├── util/
│   ├── MusicScanner.java          # 本地音乐扫描（MediaStore）
│   ├── WebDavScanner.java         # WebDAV目录扫描
│   ├── WebDavConfig.java          # WebDAV配置管理
│   ├── WebDavCacheManager.java    # WebDAV缓存管理（LRU）
│   ├── LocalDirectoryScanner.java # 本地目录扫描
│   ├── LyricFetcher.java          # 歌词获取（酷狗+网易云）
│   ├── LrcParser.java             # LRC歌词解析
│   ├── KrcParser.java             # KRC歌词解析（逐字高亮）
│   ├── CoverFetcher.java          # 封面获取（酷狗+网易云+内嵌提取）
│   ├── FavoriteManager.java       # 收藏管理
│   ├── HistoryManager.java        # 播放历史管理
│   ├── ThemeStyle.java            # 风格系统（4种预定义风格）
│   ├── ThemeColors.java           # 风格色彩定义
│   ├── BlurUtil.java              # 高斯模糊
│   ├── MusicApiUtil.java          # 音乐API工具
│   ├── CompatUtil.java            # 兼容性工具
│   └── FileUtil.java              # 文件读写工具
└── view/
    ├── LyricView.java             # 歌词视图（逐字高亮+自动折行）
    ├── RotatingCoverView.java     # 旋转封面视图
    ├── SpectrumView.java          # 频谱视图（竖条/圆点/波浪线）
    ├── ImmersiveOverlayView.java  # 沉浸模式覆盖层
    ├── LandscapeGradientOverlay.java # 横屏渐变覆盖
    └── CoverBorderGradientDrawable.java # 封面边框渐变
```

## 权限说明

| 权限 | 用途 |
|------|------|
| `READ_MEDIA_AUDIO` | Android 13+ 读取音频文件 |
| `READ_EXTERNAL_STORAGE` | Android 12 及以下读取存储 |
| `WRITE_EXTERNAL_STORAGE` | Android 9 及以下歌词导出 |
| `MANAGE_EXTERNAL_STORAGE` | 全文件访问（WebDAV配置恢复） |
| `FOREGROUND_SERVICE` | 后台播放服务 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Android 14+ 前台服务类型 |
| `POST_NOTIFICATIONS` | Android 13+ 显示播放通知 |
| `WAKE_LOCK` | 防止播放时 CPU 休眠 |
| `MODIFY_AUDIO_SETTINGS` | Visualizer 频谱采集 |
| `RECORD_AUDIO` | 频谱 AudioRecord 降级方案 |
| `INTERNET` | 在线歌词、封面、WebDAV 流媒体 |
| `SYSTEM_ALERT_WINDOW` | 车机桌面悬浮应用 |

## 编译

1. 使用 Android Studio 打开项目
2. 签名配置：在 `local.properties` 中添加 release 签名信息（参见 `app/build.gradle`）
3. 同步 Gradle 并编译

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

## 第三方依赖

| 依赖 | 用途 |
|------|------|
| `androidx.media3:media3-exoplayer` | ExoPlayer 媒体播放引擎 |
| `androidx.media3:media3-datasource-okhttp` | OkHttp 数据源（WebDAV 流媒体） |
| `androidx.media:media` | MediaSessionCompat 锁屏控制+通知 MediaStyle |
| `com.github.thegrizzlylabs:sardine-android` | WebDAV 客户端 |
| `androidx.recyclerview:recyclerview` | 列表展示 |
| `com.android.tools:desugar_jdk_libs` | Java 8+ API 降级支持 |

## APK 大小

| 构建类型 | 大小 |
|---------|------|
| Debug | ~5.0 MB |
| Release（R8 混淆） | ~2.0 MB |

## DEMO

> https://pd.qq.com/s/1rzkspx0z

## 作者

静心 — 依托 TeleClaw 盲搓制作

- 网址：https://lecoauto.com
- 交流群：651547480

## 许可证

MIT License

---

<a id="english"></a>

# TclawPlay / JingxinMusic

[中文](#) | English

A minimal Android music player designed for car head units and everyday listening. Supports both local music and WebDAV cloud streaming. No ads, no tracking, offline-first.

Entirely generated through AI conversations. The author never reads the code, only converses with AI.

## Features

### Playback Core
- **Local Music Scan** — Automatically scans device music; browse by folder / all songs / favorites
- **WebDAV Cloud Music** — Supports Synology NAS and other WebDAV servers, browse directories, stream with local cache
- **Background Playback** — Foreground Service + notification controls + lock screen MediaSession
- **Play Modes** — Sequential / Shuffle / Repeat One; press previous within 3 seconds to restart

### Lyrics System
- **Local Lyrics** — Supports KRC (Kugou word-by-word) and LRC formats
- **Online Lyrics** — Kugou KRC preferred, Netease LRC as fallback, auto-cached
- **Lyrics Export** — Convert KRC to LRC format, export to Download/lyrics/
- **Word-by-word Highlight** — KRC lyrics with progressive color fill, auto line wrapping

### Cover & Visuals
- **Online Album Art** — Embedded cover first → local cache → Kugou/Netease online fetch
- **Rotating Cover** — Vinyl-style spinning cover while playing, auto-extracts dominant color as background
- **Spectrum Visualizer** — 128-bar spectrum, 60fps refresh, three styles (bar / dot / wave)
- **Immersive Mode** — Long press lyrics area to toggle, portrait and landscape layouts

### Theme System
- **Four Predefined Styles** — Spring Green / Ocean Blue / Floral Purple / Sophisticated Gray
- **Card Layout** — Unified square card grid for local and cloud music
- **Folder Covers** — Auto-find cover art in directory, 6-color gradient cycle
- **Day/Night Theme** — Light green-white daytime, dark green-black nighttime

### Car Head Unit Support
- **Floating App** — Declares SYSTEM_ALERT_WINDOW permission, supports freeform window mode
- **Window Resizing** — resizeableActivity enabled for freeform window scaling
- **WebDAV Config Backup** — Auto-backup to Download directory, one-tap restore on new devices

### Others
- **Favorites & History** — One-tap favorite, quick replay from history
- **Auto Resume** — Remembers last played song, auto-navigates to player on restart
- **Folder Playback** — Tapping a song in folder view limits the play queue to that folder

## Tech Stack

| Item | Tech |
|------|------|
| Language | Java 17 |
| Min SDK | 23 (Android 6.0) |
| Target SDK | 36 |
| Playback | Media3 ExoPlayer (forced software decoding for FLAC on older devices) |
| Lock Screen | MediaSessionCompat |
| WebDAV | sardine-android + OkHttp + Okio |
| Cover Cache | Local file cache + LRU eviction |
| Build | Gradle 9.0 + AGP 9.0.0 |

## Project Structure

```
app/src/main/java/com/jingxin/jingxinmusic/
├── MainActivity.java              # Song list page (local + cloud)
├── PlayerActivity.java            # Player page
├── adapter/
│   ├── SongAdapter.java           # Song list adapter (folder / song / favorite)
│   └── BrowseAdapter.java         # WebDAV directory browse adapter
├── model/
│   ├── Song.java                  # Song data model
│   ├── BrowseItem.java            # WebDAV browse item model
│   └── FolderInfo.java            # Folder info model
├── service/
│   └── MusicPlayerService.java    # Playback service (ExoPlayer + notification + MediaSession + broadcast)
├── ui/
│   ├── WebDavSettingsActivity.java # WebDAV settings page
│   ├── SquareImageView.java       # Square image crop
│   └── FolderCoverView.java       # Folder cover view
├── util/
│   ├── MusicScanner.java          # Local music scanner (MediaStore)
│   ├── WebDavScanner.java         # WebDAV directory scanner
│   ├── WebDavConfig.java          # WebDAV config manager
│   ├── WebDavCacheManager.java    # WebDAV cache manager (LRU)
│   ├── LocalDirectoryScanner.java # Local directory scanner
│   ├── LyricFetcher.java          # Lyrics fetcher (Kugou + Netease)
│   ├── LrcParser.java             # LRC lyrics parser
│   ├── KrcParser.java             # KRC lyrics parser (word-by-word highlight)
│   ├── CoverFetcher.java          # Cover fetcher (Kugou + Netease + embedded extraction)
│   ├── FavoriteManager.java       # Favorites manager
│   ├── HistoryManager.java        # Play history manager
│   ├── ThemeStyle.java            # Theme system (4 predefined styles)
│   ├── ThemeColors.java           # Theme color definitions
│   ├── BlurUtil.java              # Gaussian blur
│   ├── MusicApiUtil.java          # Music API utility
│   ├── CompatUtil.java            # Compatibility utility
│   └── FileUtil.java              # File I/O utility
└── view/
    ├── LyricView.java             # Lyrics view (word-by-word highlight + auto wrap)
    ├── RotatingCoverView.java     # Rotating cover view
    ├── SpectrumView.java          # Spectrum view (bar / dot / wave)
    ├── ImmersiveOverlayView.java  # Immersive mode overlay
    ├── LandscapeGradientOverlay.java # Landscape gradient overlay
    └── CoverBorderGradientDrawable.java # Cover border gradient
```

## Permissions

| Permission | Purpose |
|------------|---------|
| `READ_MEDIA_AUDIO` | Read audio files on Android 13+ |
| `READ_EXTERNAL_STORAGE` | Read storage on Android 12 and below |
| `WRITE_EXTERNAL_STORAGE` | Lyrics export on Android 9 and below |
| `MANAGE_EXTERNAL_STORAGE` | Full file access (WebDAV config restore) |
| `FOREGROUND_SERVICE` | Background playback service |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Android 14+ foreground service type |
| `POST_NOTIFICATIONS` | Show playback notification on Android 13+ |
| `WAKE_LOCK` | Prevent CPU sleep during playback |
| `MODIFY_AUDIO_SETTINGS` | Visualizer spectrum capture |
| `RECORD_AUDIO` | Spectrum AudioRecord fallback |
| `INTERNET` | Online lyrics, covers, and WebDAV streaming |
| `SYSTEM_ALERT_WINDOW` | Car head unit floating app |

## Build

1. Open the project in Android Studio
2. Signing: Add your release keystore info to `local.properties` (see `app/build.gradle`)
3. Sync Gradle and build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

## Third-party Dependencies

| Dependency | Purpose |
|------------|---------|
| `androidx.media3:media3-exoplayer` | ExoPlayer media playback engine |
| `androidx.media3:media3-datasource-okhttp` | OkHttp data source (WebDAV streaming) |
| `androidx.media:media` | MediaSessionCompat lock screen control + notification MediaStyle |
| `com.github.thegrizzlylabs:sardine-android` | WebDAV client |
| `androidx.recyclerview:recyclerview` | List display |
| `com.android.tools:desugar_jdk_libs` | Java 8+ API desugaring |

## APK Size

| Build Type | Size |
|------------|------|
| Debug | ~5.0 MB |
| Release (R8 obfuscated) | ~2.0 MB |

## DEMO

> https://pd.qq.com/s/1rzkspx0z

## Author

Jingxin — Built with TeleClaw

- Website: https://lecoauto.com
- Group: 651547480

## License

MIT License

> AI生成