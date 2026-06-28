---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '9d5ce96d-a4d1-4ac8-9827-ce8ead97890f'
  PropagateID: '9d5ce96d-a4d1-4ac8-9827-ce8ead97890f'
  ReservedCode1: 'a2141b2e-5e7f-4786-b393-a7fde4d9de77'
  ReservedCode2: 'a2141b2e-5e7f-4786-b393-a7fde4d9de77'
---

# 静心音乐 / TclawPlay

中文 | [English](#english)

<p align="center">
  <strong>一款为车机与日常聆听设计的 Android 音乐播放器</strong><br>
  本地音乐 · WebDAV 云端 · B站音源 · 无广告 · 无追踪 · 离线优先
</p>

<p align="center">
  完全通过 AI 对话生成，作者不看代码，只对话。
</p>

---

## 截图 / Screenshots

| 竖屏经典 | 竖屏唱片机 | 竖屏轮播 | 横屏沉浸 |
|:---:|:---:|:---:|:---:|
| *圆形旋转封面* | *黑胶唱片+唱臂+火花* | *5卡片封面轮播* | *全屏歌词+频谱* |

> 四种播放页面模式自由切换：经典 → 沉浸 → 唱片机 → 轮播

---

## 功能特性

### 🎵 播放核心
- **本地音乐扫描** — 自动扫描设备音乐，按目录/全部/收藏三种视图浏览
- **WebDAV 云端音乐** — 支持群晖 NAS 等 WebDAV 服务器，分级浏览目录，流媒体播放+本地缓存
- **B站音源** — 支持 BV 号解析，在线播放 B站音频
- **后台播放** — 前台 Service + 通知栏控制 + 锁屏 MediaSession 控制
- **播放模式** — 顺序 / 随机 / 单曲循环，3秒内按上一首回到开头

### 🎤 歌词系统
- **本地歌词** — 支持 KRC（酷狗逐字高亮）和 LRC 格式
- **在线歌词** — 酷狗 KRC 优先，网易云 LRC 备用，自动缓存
- **歌词导出** — KRC 转换为 LRC 格式导出到 Download/lyrics/
- **逐字高亮** — KRC 歌词支持逐字颜色填充，自动折行
- **歌词模式切换** — 单行/双行/多行/全屏，随心切换

### 🎨 封面与视觉
- **在线封面** — 内嵌封面优先 → 本地缓存 → 酷狗/网易云在线获取，切歌不闪烁
- **四种播放页模式**：
  - **经典模式** — 圆形旋转封面，频谱环绕
  - **沉浸模式** — 封面+歌词全屏融合
  - **唱片机模式** — 黑胶唱片旋转+金属唱臂+针尖火花动画
  - **轮播模式** — 5卡片封面轮播，中间卡片晃动动画
- **频谱可视化** — FFT 算法，支持多种样式：
  - 竖条频谱（酷狗风格，柱身+顶部能量块）
  - 圆点频谱
  - 波浪线频谱
  - 网易圆环频谱（旋转+随机跳跃）
  - 波浪圆环频谱（旋转+内环偏移）
- **模糊背景** — 封面主色调高斯模糊，自动适配昼夜主题

### 🎭 风格系统
- **四种预定义风格** — 春意盎然 / 蔚蓝天地 / 万紫千红 / 高级灰
- **卡片布局** — 本地和云端采用统一的正方形卡片网格布局
- **文件夹封面** — 自动查找目录下封面图，6色渐变循环
- **昼夜主题** — 日间浅绿白配色，夜间暗绿黑配色；LED元素日间青色、夜间绿色

### 🚗 车机适配
- **悬浮窗播放器** — MiniFloatService，支持后台显示，可拖动、双指缩放（0.5x-2.0x）
- **悬浮窗交互** — 单击切换仪表盘风格/昼夜模式，双击关闭，右上角关闭按钮
- **车机自由窗口** — 声明 SYSTEM_ALERT_WINDOW 权限，resizeableActivity 支持自由窗口
- **WebDAV 配置备份** — 自动备份到 Download 目录，换设备一键恢复

### ✨ 其他
- **收藏与历史** — 一键收藏，播放历史快速回听
- **自动恢复** — 记忆上次播放歌曲，重启后自动跳转播放页
- **目录播放** — 目录视图中点击歌曲，播放队列自动限定在该目录内
- **通知栏控制** — MediaStyle 通知，支持播放/暂停/上下曲

---

## 技术架构

### 整体架构

```
┌──────────────────────────────────────────────────┐
│                   PlayerActivity                  │
│  ┌──────────┐  switchScene()  ┌────────────────┐ │
│  │CoverScene│◄───────────────►│ CoverSceneHelper│ │
│  │ Interface│                 │ (公共逻辑)      │ │
│  └──────────┘                 └────────────────┘ │
│       ▲                                           │
│  ┌────┴──────────────────────────────────┐       │
│  │              策略模式实现               │       │
│  ├─────────────┬─────────────┬───────────┤       │
│  │  ClassicScene│ ImmersiveScene│CarouselScene│   │
│  │   (经典)     │   (沉浸)      │  (轮播)     │   │
│  │      ▲       │              │            │       │
│  │      │       │              │            │       │
│  │ RecordScene  │              │            │       │
│  │  (唱片机)    │              │            │       │
│  └─────────────┴─────────────┴───────────┘       │
│         竖屏/横屏各一套（共8个Scene类）            │
└──────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────┐
│MusicPlayerService│  ExoPlayer + MediaSession + 前台通知
└──────────────────┘
```

### 技术栈

| 项目 | 技术 |
|------|------|
| 语言 | Java 17 |
| 最低 SDK | 23 (Android 6.0) |
| 目标 SDK | 36 |
| 播放引擎 | Media3 ExoPlayer（强制软件解码，兼容老设备 FLAC） |
| 锁屏控制 | MediaSessionCompat |
| WebDAV | sardine-android + OkHttp + Okio |
| B站音源 | Bilibili API 解析 |
| 封面缓存 | 本地文件缓存 + LRU 淘汰 |
| 频谱算法 | O(n log n) FFT（从 DFT 优化而来） |
| 状态持久化 | SharedPreferences |
| 构建工具 | Gradle 9.0 + AGP 9.0.0 |

---

## 项目结构

```
app/src/main/java/com/jingxin/jingxinmusic/
├── App.java                          # Application 入口
├── MainActivity.java                 # 主页（本地/云端/收藏 三Tab）
├── PlayerActivity.java               # 播放页（Scene调度+歌词+频谱）
├── adapter/
│   ├── BrowseAdapter.java            # WebDAV目录浏览适配器
│   └── SongAdapter.java              # 歌曲列表适配器
├── model/
│   ├── BrowseItem.java               # WebDAV浏览项模型
│   ├── FolderInfo.java               # 目录信息模型
│   └── Song.java                     # 歌曲数据模型
├── scene/                            # 播放页风格策略模式
│   ├── CoverScene.java               # 策略接口
│   ├── CoverSceneHelper.java         # 公共逻辑（封面/频谱/沉浸/轮播）
│   ├── PortraitClassicScene.java     # 竖屏经典（RecordScene的父类）
│   ├── PortraitRecordScene.java      # 竖屏唱片机（继承ClassicScene）
│   ├── PortraitImmersiveScene.java   # 竖屏沉浸
│   ├── PortraitCarouselScene.java    # 竖屏轮播
│   ├── LandscapeClassicScene.java    # 横屏经典（RecordScene的父类）
│   ├── LandscapeRecordScene.java     # 横屏唱片机（继承ClassicScene）
│   ├── LandscapeImmersiveScene.java  # 横屏沉浸
│   └── LandscapeCarouselScene.java   # 横屏轮播
├── service/
│   ├── MusicPlayerService.java       # 播放服务（ExoPlayer+通知+MediaSession+广播）
│   └── MiniFloatService.java         # 悬浮窗播放器服务
├── ui/
│   ├── BiliSettingsActivity.java     # B站音源设置页
│   ├── WebDavSettingsActivity.java   # WebDAV设置页
│   ├── FolderCoverView.java          # 文件夹封面视图
│   └── SquareImageView.java          # 正方形图片裁剪
├── util/
│   ├── BiliApi.java                  # B站API解析
│   ├── BiliConfig.java               # B站配置管理
│   ├── BitmapUtil.java               # 位图工具
│   ├── BlurUtil.java                 # 高斯模糊
│   ├── ColorUtil.java                # 颜色工具
│   ├── CompatUtil.java               # 兼容性工具
│   ├── ConfigBackupHelper.java       # 配置备份/恢复
│   ├── CoverFetcher.java             # 封面在线获取（酷狗+网易云）
│   ├── CoverLoader.java              # 封面三层加载（内嵌→缓存→在线）
│   ├── CoverRotationHelper.java      # 封面旋转辅助
│   ├── FavoriteManager.java          # 收藏管理
│   ├── FileUtil.java                 # 文件读写工具
│   ├── HistoryManager.java           # 播放历史管理
│   ├── HttpUtil.java                 # HTTP请求工具
│   ├── KrcParser.java                # KRC歌词解析（逐字高亮）
│   ├── LocalDirectoryScanner.java    # 本地目录扫描
│   ├── LrcParser.java                # LRC歌词解析
│   ├── LyricFetcher.java             # 歌词获取（酷狗+网易云）
│   ├── LyricPublicUtil.java          # 歌词公共工具
│   ├── MusicApiUtil.java             # 音乐API工具
│   ├── MusicScanner.java             # 本地音乐扫描（MediaStore）
│   ├── NotificationHelper.java       # 通知栏辅助
│   ├── ThemeColors.java              # 风格色彩定义
│   ├── ThemeStyle.java               # 风格系统（4种预定义风格）
│   ├── WebDavCacheManager.java       # WebDAV缓存管理（LRU）
│   ├── WebDavConfig.java             # WebDAV配置管理
│   └── WebDavScanner.java            # WebDAV目录扫描
└── view/
    ├── CoverCarouselAdapter.java     # 轮播封面数据适配器
    ├── CoverCarouselView.java        # 轮播封面容器（5卡片+晃动动画）
    ├── CoverBorderGradientDrawable.java # 封面边框渐变
    ├── ImmersiveOverlayView.java     # 沉浸模式覆盖层
    ├── LandscapeGradientOverlay.java # 横屏渐变覆盖
    ├── LyricView.java                # 歌词视图（逐字高亮+自动折行+多种模式）
    ├── RotatingCoverView.java        # 旋转封面视图（含黑胶唱片模式）
    ├── SpectrumView.java             # 频谱视图（5种样式+FFT+AudioRecord降级）
    └── TonearmView.java              # 唱臂视图（金属渐变+旋转动画+针尖火花）
```

---

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
| `INTERNET` | 在线歌词、封面、WebDAV 流媒体、B站音源 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 + 车机自由窗口 |

---

## 编译

1. 使用 Android Studio 打开项目
2. 签名配置：在 `local.properties` 中添加 release 签名信息（参见 `app/build.gradle`）
3. 同步 Gradle 并编译

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

---

## 第三方依赖

| 依赖 | 用途 |
|------|------|
| `androidx.media3:media3-exoplayer` | ExoPlayer 媒体播放引擎 |
| `androidx.media3:media3-datasource-okhttp` | OkHttp 数据源（WebDAV 流媒体） |
| `androidx.media:media` | MediaSessionCompat 锁屏控制+通知 MediaStyle |
| `com.github.thegrizzlylabs:sardine-android` | WebDAV 客户端 |
| `androidx.recyclerview:recyclerview` | 列表展示 |
| `com.android.tools:desugar_jdk_libs` | Java 8+ API 降级支持 |

---

## APK 大小

| 构建类型 | 大小 |
|---------|------|
| Debug | ~5.0 MB |
| Release（R8 混淆） | ~2.0 MB |

---

## 更新日志

### v1.0 (2026-06)
- 本地音乐扫描 + WebDAV 云端播放 + B站音源
- KRC/LRC 歌词系统（逐字高亮+在线获取+缓存）
- 四种播放页面模式（经典/沉浸/唱片机/轮播）
- 科幻金属风格界面（昼夜主题+4种风格）
- 悬浮窗播放器（可拖动+缩放+关闭按钮）
- FFT 频谱可视化（5种样式）
- 唱片机模式：黑胶唱片+金属唱臂动画+针尖火花
- 轮播模式：5卡片封面+晃动动画+70%重叠
- 车机适配（自由窗口+配置备份恢复）
- CoverScene 策略模式架构重构（8个Scene类+继承体系）

---

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

<p align="center">
  <strong>A minimal Android music player for car head units and everyday listening</strong><br>
  Local Music · WebDAV Cloud · Bilibili Audio · No Ads · No Tracking · Offline-First
</p>

<p align="center">
  Entirely generated through AI conversations. The author never reads the code, only converses with AI.
</p>

---

## Screenshots

| Portrait Classic | Portrait Record | Portrait Carousel | Landscape Immersive |
|:---:|:---:|:---:|:---:|
| *Circular spinning cover* | *Vinyl record + tonearm + sparks* | *5-card cover carousel* | *Fullscreen lyrics + spectrum* |

> Four player page modes, freely switchable: Classic → Immersive → Record → Carousel

---

## Features

### 🎵 Playback Core
- **Local Music Scan** — Automatically scans device music; browse by folder / all songs / favorites
- **WebDAV Cloud Music** — Supports Synology NAS and other WebDAV servers, browse directories, stream with local cache
- **Bilibili Audio** — Parse BV numbers, play Bilibili audio online
- **Background Playback** — Foreground Service + notification controls + lock screen MediaSession
- **Play Modes** — Sequential / Shuffle / Repeat One; press previous within 3 seconds to restart

### 🎤 Lyrics System
- **Local Lyrics** — Supports KRC (Kugou word-by-word) and LRC formats
- **Online Lyrics** — Kugou KRC preferred, Netease LRC as fallback, auto-cached
- **Lyrics Export** — Convert KRC to LRC format, export to Download/lyrics/
- **Word-by-word Highlight** — KRC lyrics with progressive color fill, auto line wrapping
- **Lyrics Mode Switch** — Single line / double line / multi-line / fullscreen, switch at will

### 🎨 Cover & Visuals
- **Online Album Art** — Embedded cover first → local cache → Kugou/Netease online fetch, no flicker on track change
- **Four Player Page Modes**:
  - **Classic** — Circular spinning cover with spectrum overlay
  - **Immersive** — Cover + lyrics fullscreen fusion
  - **Record** — Vinyl record spinning + metallic tonearm + needle spark animation
  - **Carousel** — 5-card cover carousel with center card sway animation
- **Spectrum Visualizer** — FFT algorithm with multiple styles:
  - Bar spectrum (Kugou style, bars + top energy blocks)
  - Dot spectrum
  - Wave spectrum
  - NetEase circular ring spectrum (rotation + random jumps)
  - Wave circular ring spectrum (rotation + inner ring offset)
- **Blur Background** — Gaussian blur from cover dominant color, auto day/night theme adaptation

### 🎭 Theme System
- **Four Predefined Styles** — Spring Green / Ocean Blue / Floral Purple / Sophisticated Gray
- **Card Layout** — Unified square card grid for local and cloud music
- **Folder Covers** — Auto-find cover art in directory, 6-color gradient cycle
- **Day/Night Theme** — Light green-white daytime, dark green-black nighttime; LED elements: cyan daytime, green nighttime

### 🚗 Car Head Unit Support
- **Floating Player** — MiniFloatService, background display, draggable, pinch-to-zoom (0.5x-2.0x)
- **Float Window Interaction** — Tap to switch dashboard style/day-night mode, double-tap to close, top-right close button
- **Freeform Window** — Declares SYSTEM_ALERT_WINDOW permission, resizeableActivity enabled
- **WebDAV Config Backup** — Auto-backup to Download directory, one-tap restore on new devices

### ✨ Others
- **Favorites & History** — One-tap favorite, quick replay from history
- **Auto Resume** — Remembers last played song, auto-navigates to player on restart
- **Folder Playback** — Tapping a song in folder view limits the play queue to that folder
- **Notification Controls** — MediaStyle notification with play/pause/prev/next

---

## Technical Architecture

### Overall Architecture

```
┌──────────────────────────────────────────────────┐
│                   PlayerActivity                  │
│  ┌──────────┐  switchScene()  ┌────────────────┐ │
│  │CoverScene│◄───────────────►│ CoverSceneHelper│ │
│  │ Interface│                 │ (shared logic)  │ │
│  └──────────┘                 └────────────────┘ │
│       ▲                                           │
│  ┌────┴──────────────────────────────────┐       │
│  │         Strategy Pattern Impls         │       │
│  ├─────────────┬─────────────┬───────────┤       │
│  │  ClassicScene│ ImmersiveScene│CarouselScene│   │
│  │   (classic)  │  (immersive)  │ (carousel)  │   │
│  │      ▲       │              │            │       │
│  │      │       │              │            │       │
│  │ RecordScene  │              │            │       │
│  │   (record)   │              │            │       │
│  └─────────────┴─────────────┴───────────┘       │
│      Portrait/Landscape each (8 Scene classes)    │
└──────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────┐
│MusicPlayerService│  ExoPlayer + MediaSession + Foreground Notification
└──────────────────┘
```

### Tech Stack

| Item | Tech |
|------|------|
| Language | Java 17 |
| Min SDK | 23 (Android 6.0) |
| Target SDK | 36 |
| Playback | Media3 ExoPlayer (forced software decoding for FLAC on older devices) |
| Lock Screen | MediaSessionCompat |
| WebDAV | sardine-android + OkHttp + Okio |
| Bilibili | Bilibili API parsing |
| Cover Cache | Local file cache + LRU eviction |
| Spectrum | O(n log n) FFT (optimized from DFT) |
| Persistence | SharedPreferences |
| Build | Gradle 9.0 + AGP 9.0.0 |

---

## Project Structure

```
app/src/main/java/com/jingxin/jingxinmusic/
├── App.java                          # Application entry
├── MainActivity.java                 # Main page (Local/Cloud/Favorites tabs)
├── PlayerActivity.java               # Player page (Scene dispatch + lyrics + spectrum)
├── adapter/
│   ├── BrowseAdapter.java            # WebDAV directory browse adapter
│   └── SongAdapter.java              # Song list adapter
├── model/
│   ├── BrowseItem.java               # WebDAV browse item model
│   ├── FolderInfo.java               # Folder info model
│   └── Song.java                     # Song data model
├── scene/                            # Player page strategy pattern
│   ├── CoverScene.java               # Strategy interface
│   ├── CoverSceneHelper.java         # Shared logic (cover/spectrum/immersive/carousel)
│   ├── PortraitClassicScene.java     # Portrait classic (RecordScene's superclass)
│   ├── PortraitRecordScene.java      # Portrait record (extends ClassicScene)
│   ├── PortraitImmersiveScene.java   # Portrait immersive
│   ├── PortraitCarouselScene.java    # Portrait carousel
│   ├── LandscapeClassicScene.java    # Landscape classic (RecordScene's superclass)
│   ├── LandscapeRecordScene.java     # Landscape record (extends ClassicScene)
│   ├── LandscapeImmersiveScene.java  # Landscape immersive
│   └── LandscapeCarouselScene.java   # Landscape carousel
├── service/
│   ├── MusicPlayerService.java       # Playback service (ExoPlayer + notification + MediaSession + broadcast)
│   └── MiniFloatService.java         # Floating player service
├── ui/
│   ├── BiliSettingsActivity.java     # Bilibili audio settings page
│   ├── WebDavSettingsActivity.java   # WebDAV settings page
│   ├── FolderCoverView.java          # Folder cover view
│   └── SquareImageView.java          # Square image crop
├── util/
│   ├── BiliApi.java                  # Bilibili API parsing
│   ├── BiliConfig.java               # Bilibili config manager
│   ├── BitmapUtil.java               # Bitmap utility
│   ├── BlurUtil.java                 # Gaussian blur
│   ├── ColorUtil.java                # Color utility
│   ├── CompatUtil.java               # Compatibility utility
│   ├── ConfigBackupHelper.java       # Config backup/restore
│   ├── CoverFetcher.java             # Online cover fetcher (Kugou + Netease)
│   ├── CoverLoader.java              # Three-tier cover loading (embedded → cache → online)
│   ├── CoverRotationHelper.java      # Cover rotation helper
│   ├── FavoriteManager.java          # Favorites manager
│   ├── FileUtil.java                 # File I/O utility
│   ├── HistoryManager.java           # Play history manager
│   ├── HttpUtil.java                 # HTTP request utility
│   ├── KrcParser.java                # KRC lyrics parser (word-by-word highlight)
│   ├── LocalDirectoryScanner.java    # Local directory scanner
│   ├── LrcParser.java                # LRC lyrics parser
│   ├── LyricFetcher.java             # Lyrics fetcher (Kugou + Netease)
│   ├── LyricPublicUtil.java          # Lyrics common utility
│   ├── MusicApiUtil.java             # Music API utility
│   ├── MusicScanner.java             # Local music scanner (MediaStore)
│   ├── NotificationHelper.java       # Notification helper
│   ├── ThemeColors.java              # Theme color definitions
│   ├── ThemeStyle.java               # Theme system (4 predefined styles)
│   ├── WebDavCacheManager.java       # WebDAV cache manager (LRU)
│   ├── WebDavConfig.java             # WebDAV config manager
│   └── WebDavScanner.java            # WebDAV directory scanner
└── view/
    ├── CoverCarouselAdapter.java     # Carousel cover data adapter
    ├── CoverCarouselView.java        # Carousel cover container (5 cards + sway animation)
    ├── CoverBorderGradientDrawable.java # Cover border gradient
    ├── ImmersiveOverlayView.java     # Immersive mode overlay
    ├── LandscapeGradientOverlay.java # Landscape gradient overlay
    ├── LyricView.java                # Lyrics view (word-by-word + auto wrap + multiple modes)
    ├── RotatingCoverView.java        # Rotating cover view (with vinyl record mode)
    ├── SpectrumView.java             # Spectrum view (5 styles + FFT + AudioRecord fallback)
    └── TonearmView.java              # Tonearm view (metallic gradient + rotation + needle sparks)
```

---

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
| `INTERNET` | Online lyrics, covers, WebDAV streaming, Bilibili audio |
| `SYSTEM_ALERT_WINDOW` | Floating window + car head unit freeform mode |

---

## Build

1. Open the project in Android Studio
2. Signing: Add your release keystore info to `local.properties` (see `app/build.gradle`)
3. Sync Gradle and build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

---

## Third-party Dependencies

| Dependency | Purpose |
|------------|---------|
| `androidx.media3:media3-exoplayer` | ExoPlayer media playback engine |
| `androidx.media3:media3-datasource-okhttp` | OkHttp data source (WebDAV streaming) |
| `androidx.media:media` | MediaSessionCompat lock screen control + notification MediaStyle |
| `com.github.thegrizzlylabs:sardine-android` | WebDAV client |
| `androidx.recyclerview:recyclerview` | List display |
| `com.android.tools:desugar_jdk_libs` | Java 8+ API desugaring |

---

## APK Size

| Build Type | Size |
|------------|------|
| Debug | ~5.0 MB |
| Release (R8 obfuscated) | ~2.0 MB |

---

## Changelog

### v1.0 (2026-06)
- Local music scan + WebDAV cloud playback + Bilibili audio source
- KRC/LRC lyrics system (word-by-word highlight + online fetch + cache)
- Four player page modes (Classic / Immersive / Record / Carousel)
- Sci-fi metallic style UI (day/night theme + 4 predefined styles)
- Floating player window (draggable + pinch-to-zoom + close button)
- FFT spectrum visualizer (5 styles)
- Record mode: vinyl record + metallic tonearm animation + needle sparks
- Carousel mode: 5-card cover carousel + sway animation + 70% overlap
- Car head unit support (freeform window + config backup/restore)
- CoverScene strategy pattern architecture refactor (8 Scene classes + inheritance)

---

## DEMO

> https://pd.qq.com/s/1rzkspx0z

## Author

Jingxin — Built with TeleClaw

- Website: https://lecoauto.com
- Group: 651547480

## License

MIT License

> AI生成