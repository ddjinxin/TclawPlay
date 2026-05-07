---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '2a5633af-7662-4f5c-8a02-51aacbc3a92e'
  PropagateID: '2a5633af-7662-4f5c-8a02-51aacbc3a92e'
  ReservedCode1: '5aa674d8-d4bc-4f3a-8c3f-2c1b1e7e0611'
  ReservedCode2: '5aa674d8-d4bc-4f3a-8c3f-2c1b1e7e0611'
---

# 静心音乐 / JingxinMusic

中文 | [English](#english)

一个简洁的 Android 本地音乐播放器，专为车机与日常聆听设计。无广告、无追踪、纯离线优先。

## 功能亮点

- **本地音乐扫描** — 自动扫描设备中的音乐文件，按目录/全部/收藏三种视图浏览
- **在线歌词获取** — 酷狗 KRC 逐字歌词优先，网易云 LRC 备用，支持歌词自动折行
- **在线封面获取** — 内嵌封面优先 → 本地缓存 → 酷狗/网易云在线获取，自动缓存
- **频谱可视化** — 三种样式（竖条 / 圆点 / 波浪线），支持 Visualizer 直读音频输出，AudioRecord 麦克风降级
- **旋转封面** — 播放时唱片旋转，暂停时停止，封面主色调自动提取为背景
- **后台播放** — 前台 Service + 通知栏控制 + 锁屏 MediaSession 控制
- **播放模式** — 顺序 / 随机 / 单曲循环
- **收藏与历史** — 一键收藏，播放历史快速回听
- **昼夜主题** — 黑金夜间模式 / 淡雅白天模式，一键切换
- **自动恢复** — 记忆上次播放歌曲，重启后自动跳转播放页
- **目录播放** — 在目录视图中点击歌曲，播放队列自动限定在该目录内

## 技术栈

| 项目 | 技术 |
|------|------|
| 语言 | Java 17 |
| 最低 SDK | 23 (Android 6.0) |
| 目标 SDK | 36 |
| 播放引擎 | Media3 ExoPlayer（强制软件解码，兼容老设备 FLAC） |
| 锁屏控制 | MediaSessionCompat |
| 构建工具 | Gradle 8.5 + AGP 8.5.0 |

## 项目结构

```
app/src/main/java/com/jingxin/jingxinmusic/
├── MainActivity.java          # 歌曲列表页
├── PlayerActivity.java        # 播放页
├── adapter/
│   └── SongAdapter.java       # 列表适配器（目录/歌曲/收藏）
├── model/
│   ├── Song.java              # 歌曲数据模型
│   └── FolderInfo.java        # 目录信息模型
├── service/
│   └── MusicPlayerService.java # 播放服务（ExoPlayer + 通知 + MediaSession）
├── util/
│   ├── MusicScanner.java      # 音乐扫描（MediaStore）
│   ├── LyricFetcher.java      # 歌词获取（酷狗 + 网易云）
│   ├── LrcParser.java         # LRC 歌词解析
│   ├── KrcParser.java         # KRC 歌词解析（逐字高亮）
│   ├── CoverFetcher.java      # 封面获取（酷狗 + 网易云 + 内嵌提取）
│   ├── FavoriteManager.java   # 收藏管理
│   ├── HistoryManager.java    # 播放历史管理
│   ├── BlurUtil.java          # 高斯模糊
│   └── FileUtil.java          # 文件读写工具
└── view/
    ├── LyricView.java         # 歌词视图（逐字颜色填充 + 自动折行）
    ├── RotatingCoverView.java # 旋转封面视图
    └── SpectrumView.java      # 频谱视图（竖条/圆点/波浪线）
```

## 权限说明

| 权限 | 用途 |
|------|------|
| `READ_MEDIA_AUDIO` | Android 13+ 读取音频文件 |
| `READ_EXTERNAL_STORAGE` | Android 12 及以下读取存储 |
| `MANAGE_EXTERNAL_STORAGE` | 全文件访问（扫描所有目录下的歌词文件） |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 后台播放服务 |
| `POST_NOTIFICATIONS` | Android 13+ 显示播放通知 |
| `WAKE_LOCK` | 防止播放时 CPU 休眠 |
| `RECORD_AUDIO` | 频谱 AudioRecord 降级方案 |
| `MODIFY_AUDIO_SETTINGS` | Visualizer 频谱采集 |
| `INTERNET` | 在线获取歌词和封面 |

## 编译

1. 使用 Android Studio 打开项目
2. 签名配置：在 `local.properties` 中添加 release 签名信息（参见 `app/build.gradle`）
3. 同步 Gradle 并编译

```bash
./gradlew assembleRelease
```

## 截图

> 可自行运行后截图补充

## 作者

静心 — 依托天翼 TeleClaw 盲搓制作

- 网址：https://lecoauto.com
- 交流群：651547480

## 许可证

MIT License

---

<a id="english"></a>

# JingxinMusic

[中文](#) | English

A minimal Android local music player designed for car head units and everyday listening. No ads, no tracking, offline-first.

## Highlights

- **Local Music Scan** — Automatically scans music on device; browse by folder / all songs / favorites
- **Online Lyrics** — Kugou KRC word-by-word lyrics preferred, Netease LRC as fallback; auto line wrapping
- **Online Album Art** — Embedded cover first → local cache → Kugou/Netease online fetch with caching
- **Spectrum Visualizer** — Three styles (bar / dot / wave), supports Visualizer direct audio output with AudioRecord mic fallback
- **Rotating Cover** — Vinyl-style spinning cover while playing, auto-extracts dominant color as background
- **Background Playback** — Foreground Service + notification controls + lock screen MediaSession
- **Play Modes** — Sequential / Shuffle / Repeat One
- **Favorites & History** — One-tap favorite, quick replay from history
- **Day/Night Theme** — Gold-on-black night mode / light day mode, one-tap switch
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
| Build | Gradle 8.5 + AGP 8.5.0 |

## Project Structure

```
app/src/main/java/com/jingxin/jingxinmusic/
├── MainActivity.java          # Song list page
├── PlayerActivity.java        # Player page
├── adapter/
│   └── SongAdapter.java       # List adapter (folder / song / favorite)
├── model/
│   ├── Song.java              # Song data model
│   └── FolderInfo.java        # Folder info model
├── service/
│   └── MusicPlayerService.java # Playback service (ExoPlayer + notification + MediaSession)
├── util/
│   ├── MusicScanner.java      # Music scanner (MediaStore)
│   ├── LyricFetcher.java      # Lyrics fetcher (Kugou + Netease)
│   ├── LrcParser.java         # LRC parser
│   ├── KrcParser.java         # KRC parser (word-by-word highlight)
│   ├── CoverFetcher.java      # Cover fetcher (Kugou + Netease + embedded extraction)
│   ├── FavoriteManager.java   # Favorites manager
│   ├── HistoryManager.java    # Play history manager
│   ├── BlurUtil.java          # Gaussian blur
│   └── FileUtil.java          # File I/O utility
└── view/
    ├── LyricView.java         # Lyrics view (word-by-word color fill + auto wrap)
    ├── RotatingCoverView.java # Rotating cover view
    └── SpectrumView.java      # Spectrum view (bar / dot / wave)
```

## Permissions

| Permission | Purpose |
|------------|---------|
| `READ_MEDIA_AUDIO` | Read audio files on Android 13+ |
| `READ_EXTERNAL_STORAGE` | Read storage on Android 12 and below |
| `MANAGE_EXTERNAL_STORAGE` | Full file access (scan lyrics files in all directories) |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background playback service |
| `POST_NOTIFICATIONS` | Show playback notification on Android 13+ |
| `WAKE_LOCK` | Prevent CPU sleep during playback |
| `RECORD_AUDIO` | Spectrum AudioRecord fallback |
| `MODIFY_AUDIO_SETTINGS` | Visualizer spectrum capture |
| `INTERNET` | Online lyrics and cover art |

## Build

1. Open the project in Android Studio
2. Signing: Add your release keystore info to `local.properties` (see `app/build.gradle`)
3. Sync Gradle and build

```bash
./gradlew assembleRelease
```

## Screenshots

> Add your own screenshots after running the app

## Author

Jingxin — Built with TeleClaw

- Website: https://lecoauto.com
- Group: 651547480

## License

MIT License

> AI生成