---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'bde60b54-b334-49cc-aa5b-aaa684d10c29'
  PropagateID: 'bde60b54-b334-49cc-aa5b-aaa684d10c29'
  ReservedCode1: '81743515-a615-497d-bd79-a24d37517785'
  ReservedCode2: '81743515-a615-497d-bd79-a24d37517785'
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

<p align="center">
  🎬 <a href="https://pd.qq.com/s/a8mi9wmfs?b=2">项目演示视频</a>
</p>

---

## 许可证

**AGPL-3.0**（GNU Affero General Public License v3.0），完整许可证见仓库内 `LICENSE`
或 <https://www.gnu.org/licenses/agpl-3.0.html>。

任何人可以自由使用、修改与分发本应用，但分发或提供网络服务时必须以 AGPL-3.0
同样开放相应源代码。

**衍生声明**：拼贴风格（`view/lattice/`、`stage/LatticePageV2`，源自
[FOLIA](https://github.com/chthollyphile/folia-major) 的 Lattice 封面墙）与七种歌词
动效（流光/时计/心象/倾诉/云阶/浮名/镜台，分别源自 folia 的 classic/pendolo/
cadenza/tilt/partita/fume/diorama）为 FOLIA 衍生实现（作者 chthollyphile），
分发时须保留本声明及其版权信息。
频谱部分样式（柱状/酷狗/AI语音/波形柱/扩散圆环/波浪圆环）移植自
[SpectrumForAndroid](https://github.com/michael007js/SpectrumForAndroid)（作者
michael007js，无明确许可声明，特此注明出处并致谢）。

---

## 📢 最新版本 (v1.0.9.3)

### v1.0.9.3 (2026-11)
#### 新功能

- **第 9 播放风格「拼贴」** — 无限封面墙：封面无尽延展的拼贴世界，拖拽惯性浏览；点任意卡展开 6×6 大卡并切歌，大卡集成传输三键/卡顶进度线/卡底频谱/歌名歌手；长按呼出顶栏，点大卡直达歌词动效页
- **展开卡流光描边** — 播放时彗星光沿大卡边框流转（9 秒一圈），暂停转为呼吸灯；颜色与频谱/进度线同源，跟随封面取色
- **装扮键风格选择弹窗** — 顶栏装扮键由循环切换改为弹出风格列表（经典/沉浸/唱机/轮播/拼贴，当前风格高亮），与音效/动效弹窗同格式
- **前景配色跟随封面** — 播放页与沉浸页前景配色统一封面取色，新增高亮色跟随封面开关
- **控制条图标全组升级** — 玻璃胶囊播放键图标放大 30%，其余控制键图标同步换装

#### 稳定性与性能

- 全项目撞除软层合成（LAYER_TYPE_SOFTWARE），浮名/镜台/时计/AI语音频谱等动效回归硬件加速，低配车机更流畅
- 乐酷画中画窗口 resize 黑屏修复，默认背景皮肤链路定型
- 顶栏右五键内边距横竖屏统一，拼贴页频谱复位修复

#### 版权与合规

- 项目整体许可统一为 AGPL-3.0；README 声明 FOLIA（拼贴风格 + 七种歌词动效）与 SpectrumForAndroid（部分频谱样式）衍生来源并致谢
- 「星空漫游」更名为「星空」

### v1.0.9.2 (2026-10)
#### 新功能

- **直达歌词动效** — 设置页新增开关，开启后打开应用直接进入上次记忆的歌词动效播放（八种效果任选其一记忆），与"启动直达播放"互斥
- **推送更新开关** — 设置页新增（默认开启），关闭后启动时不再自动检查新版本，帮助页手动检查不受影响

#### 稳定性优化

- 返回首页封面网格、车机后台动画负载、进度刷新性能等多项修复强化

### v1.0.9.1 (2026-10)
#### 全新应用图标

- 全新视觉设计：整图 48~192 全密度 + 自适应图标（108~432 前景/背景分层），Android 8.0+ 圆形/圆角遮罩自适应，手机/车机/电视统一换装

### v1.0.9.0 (2026-10)
#### 大改点（对照 v1.0.8.2）

**① 歌词动效体系：2 种 → 8 种（移植 folia visualizer 全家桶）**

以 folia 开源项目为蓝本全保真移植其中七种逐字动效（KRC 逐字时间驱动，无逐字歌词时优雅降级），另有自研「星空」效果。

- **流光（Glow）** — folia classic 逐词三态点亮机（waiting/active/passed），含由远及近景深光尘氛围底
- **时计（Pendolo）** — 左侧机械机芯擒纵轮系+宝石轴承，右侧弧形歌词轨；副歌扫字染深/双层辉光/行背光，超宽缩字不折行
- **心象（Cadenza）** — 词云爆发+主角词碰撞散布，歌词变成一颗颗飞散的心象记忆
- **倾诉（Tilt）** — 斜体独白风，逐段揭示+字级余晖衰减
- **云阶（Partita）** — 语义单元切分+多列阶梯排布+L 形引导线，块内逐词点亮
- **浮名（Fume）** — 整曲排成一篇竖排文章，相机运镜扫读
- **镜台（Diorama）** — 越野式蜿蜒走廊逐句推进，13 种电影运镜，切歌 3.2s 飞行过渡，尘埃粒子+深度雾

统一交互：长按歌词进入/单击退出，动效页统一封面流控制（左二上一曲/中心暂停/右二下一曲）、高亮色随封面双色刷新、日夜模式自动适配、动效页统一星空背景。

**② 播放页风格架构整合**

经典/沉浸/唱机/轮播四风格从"各写各的"整合为平等继承 StandardPageBase 基座，顶栏括件化为 PlayerTopBar，StagePage 协议统一分发（ensurePage 工厂+activePage 多态），频谱跨风格适配升级：不支持环类频谱样式时自动归位竖条并持久化。架构整体减重构近 800 行，而四种风格视觉效果不变。

**③ 修复与体验优化**

- **乐酷分屏崩溃根治** — 乐酷桌面分屏内调整分屏大小时，静心音乐进程被 RemoteServiceException 杀死黑屏；三层修复：后台判定去抖 300ms→2000ms（不再把分屏重排误判为进后台）、悬浮窗启停 2 秒节流、MiniFloatService onStartCommand 兑底 startForeground（前台服务标准写法），乐酷上切其他应用时独立悬浮窗照常出现
- **时计车机 8.1 渲染稳定性** — 机芯弹簧积分器在车机低帧率下发散致机芯逐渐消失（pos 爆炸→NaN→view 不再绘制）；换 RK4 积分器（同手感）+NaN 自愈兑底
- **当前行逐字脉冲/新句落入动效** — 逐字脉冲时长取本字真实演唱段（260~700ms）、新句落入改欠阻尼弹簧（落位 12% 过冲回弹）
- **动效选择弹窗滚动** — 仅选项区可滚、标题钉顶、80% 屏高上限，修复车机横屏末尾选项不可选
- **移除回环动效** — 实践发现与倾听场景不搭，弹窗回到 7 项

### v1.0.8.2 (2026-10)
#### 优化
- **酷狗柱状频谱左右对称** — 第二组数据改为镜像取样，右半屏与左半屏显示相同数据序列，轮廓左右对称（钟形权重峰值落在屏幕中线），修复右侧一段总偏低、整体不协调的问题
- **酷狗频谱双色光晕跟随** — 双色光晕模式下两组渐变跟随封面双色插值（切歌自动变色），静态底色块同步；两色过近时自动拉开色相（阈值 40°）保层次感，退出光晕回落原版暖色
- **圆点频谱尺寸自适应** — 圆点大小改为宽高双维取小（直径≤条带高度 22%），修复横屏/超宽屏下圆点过大，竖屏观感不变

### v1.0.8.1 (2026-10)
#### 新功能
- **播放页控制栏自动隐藏** — 支持 5~30 秒可配置延时或彻底关闭，设置页新增延时选项，无操作时控制栏自动淡出，点击屏幕恢复
- **开机自启动开关** — 设置页新增开关，开启后开机广播自动启动应用，车机/电视盒子上电即拉起音乐

### v1.0.8.0 (2026-10)
#### 新功能
- **软件音效处理器** — 绕开车机厂商私有 EQ 效果器（如 NXP 多声道切换静音问题），自研 Media3 AudioProcessor 软件方案：5 段 biquad 级联滤波，7 预设（关闭/人声/流行/摇滚/古典/低音增强/全景环绕），EQ 曲线基于 Winamp 流派曲线 1.5 倍加强档（峰值 ±9dB），BassBoost/Virtualizer 80%，入口在播放页弹窗（贴左垂直居中、单列、字体自适应同频谱弹窗）
- **音量均衡（响度归一化）** — 基于 ReplayGain/EBU R128 的音量均衡开关，扫描歌曲真实响度并自动拉平听感，切换歌曲无需手动调音量

#### 修复
- **fallback 播放跨线程崩溃** — 音频异常设备走 MediaPlayer 兜底播放时，setOnCompletionListener/setOnErrorListener/catch 块在 FallbackInit 子线程直接调用 playNext()/handlePlaybackError() 访问 exoPlayer，触发 `IllegalStateException: Player is accessed on the wrong thread`；统一通过 handler.post() 切回主线程执行

#### 优化
- **冗余代码清理** — 删除 SpectrumView 4 个未使用字段、33 处未使用 import、1 个未使用 drawable，无功能影响

### v1.0.7.9 (2026-10)
#### 修复
- **音频异常设备外部控制失效** — 部分车机（如 TS18）音频服务异常时 ExoPlayer 构造失败，大量歌曲走 MediaPlayer 兑底播放；此时乐酷桌面等外部应用经 MediaSession 按暂停/恢复无反应，进度恒为 0、歌词不滚动。MediaSession 回调与通知栏统一走 fallback 感知的 pausePlayback()/resumePlayback()，进度取值改用 fallback-aware 的 getCurrentPosition()，同一根因一并修复
- **日志规范清理** — BlurUtil/KrcParser 残留的 printStackTrace() 统一改为 android.util.Log.e（崩溃落盘堆栈为功能代码保留）

### v1.0.7.8 (2026-10)
#### 修复
- **适配安卓 5.1 / 6 / 7** — 全面适配低端车机系统，修复多项崩溃与卡顿：
  - **安卓 6 崩溃修复** — R8 混淆 XmlPullParser 接口名导致框架 XmlBlock 运行时 IncompatibleClassChangeError 崩溃；getStorageVolumes() 在安卓 6 无此方法导致扫描崩溃，加 API 24 版本守卫走兑底路径探测；GradientDrawable.getColors() API 24+ 导致浏览页崩溃，改用 ThemeColors 直接返回颜色数组；onServiceConnected 子线程更新 UI 导致 CalledFromWrongThreadException
  - **安卓 5.1 音频服务异常设备 ANR 防护** — ExoPlayer 构造/释放移到子线程（构造内 generateAudioSessionId 会 Binder 到 AudioFlinger，AudioFlinger 卡死设备上无限阻塞主线程）；未就绪时自动走 MediaPlayer 兑底；AudioRecord 释放子线程化防主线程卡死
  - **安卓 5.1 弹窗修复** — 频谱选择/歌词搜索/历史弹窗点击外部无法关闭（5.1 的 PopupWindow 在 background 为 null 时不创建 PopupViewContainer，外部点击被 decor 吞掉），设透明背景触发容器创建
  - **安卓 5.1 小内存优化** — 内嵌封面统一降采样解码（原全尺寸解码在 ~64MB 堆上大封面直接 OOM）；频谱改双缓冲复用数组，消除每帧 new float[] 的 GC 压力与撕裂
  - **安卓 7 及全版本** — setForeground 统一走 API 23 兼容封装，悬浮窗在 5.1~7 上不再崩溃
- **U盘拔出后歌曲残留** — 车机 ROM 拔盘后 MediaStore 数据库残留死记录（BAD_REMOVAL），扫描时入列前校验文件真实存在，拔盘后重新扫描歌曲干净消失
- **扫描后无封面、播放后才有** — 网格与播放页封面缓存两套命名互不相认，统一按「歌名 - 歌手.jpg」保存全应用共享；网格在线搜索改用清洗后标题+歌手，命中率与播放页一致
- **文件夹封面不更新** — 目录封面缓存永不失效，换歌后仍显示旧封面；新增目录音乐文件指纹校验（.cover_cache_meta.txt），目录内容一变缓存自动失效重建

### v1.0.7.7 (2026-09)
#### 修复
- **Gitee API 限流导致无法检测更新** — Gitee API v5 被 WAF 限流返回 403 时，新增 Gitee Releases 网页解析回退方案，从网页 HTML 提取最新版本号、APK 下载链接和更新说明，确保各类网络环境下更新检测正常

### v1.0.7.6 (2026-09)
#### 新功能
- **双色光晕背景皮肤** — 设置页新增背景皮肤选项：默认/双色光晕，自研自绘极光光晕效果，128×128 LUT 精确椭圆深度函数，10×10 降采样+非均匀 8 色相桶取色，双光晕白天提亮 20%、夜间调暗 15%，仅经典/唱机/轮播模式生效（沉浸模式除外）
- **首页动态背景体系** — 底色+左下角蒙版染色+右上角渐变三层自绘架构，蒙版像素取自封面主色，8 桶 HSV 取色+10 档色相查表；首页 Tab 栏双行文字+滑动指示器+流光线，图标颜色随封面取色联动，列表项动态模式透明背景；帮助/设置/WebDAV/B站设置页同步接入
- **内嵌歌词读取** — 歌词加载顺序新增内嵌歌词最优先（开关开启时），支持 MP3 USLT/SYLT、M4A ©lyr、FLAC LYRICS 标签
- **频谱颜色跟随封面** — 竖条/圆点/波浪线/波形柱/网易圆环/扩散圆环频谱在双色光晕模式下颜色跟随封面双色（白天跟随、夜间白色，非光晕模式保持原色）
- **歌词高亮色随封面联动** — 双色光晕+经典模式下歌词高亮颜色跟随封面主色
- **播放页沉浸模式双色光晕适配** — 竖屏封面 43% 高度+光晕整屏叠加；横屏封面最底层+渐变过渡带+光晕叠加，退出恢复底色

#### 优化
- **版权文案** — 改为「心静，音自清·v动态版本号」
- **光晕模式进度条** — 进度条/圆球跟随按钮色（白天黑、夜间白），轨道浅灰
- **页面交互重构** — 首页帮助按钮改为设置按钮；设置页右上角新增帮助按钮；帮助页移除设置按钮；B站/WebDAV 路径栏设置按钮换云朵扳手新图标，边距 12→8dp
- **帮助页细节** — 文字内容日夜切换时重建、底部按钮单行防折行

#### 修复
- **高德日夜跟随链路修复** — 接收器从 Service 上移到 App 应用级，不播放音乐（Service 未运行）时高德日夜切换依然全页面生效；设置/B站/WebDAV/帮助四页悬浮态广播跟随，修复注册静默失效
- **冷启动主色持久化** — 主色持久化存储，冷启动秒出封面渐变，光晕取色后同步静态主色，返回首页渐变一致
- **光晕配色联动修复** — 切回光晕后歌名文字主题色、进度条配色同步；退出光晕后频谱恢复原色；快速切歌取色竞态；横屏旋转不再残留旧底色
- **FLAC 歌手 unknown 兜底** — 元数据缺失时显示正确兜底信息

### v1.0.7.5 (2026-09)
#### 修复
- **Android 8.1 进入播放页闪退** — v1.0.7.4 进度条缩放使用 setMinHeight/setMaxHeight，该方法在部分 Android 8.1 ROM 上不存在导致崩溃；改用轨道 drawable 高度实现，全版本兼容

### v1.0.7.4 (2026-09)
#### 优化
- **播放页进度条比例缩放** — 圆球=屏高 2%、时间文字=屏高 2%、轨道=屏高 0.5%，超宽屏按宽高比补偿，覆盖横竖屏经典/唱机/沉浸/轮播全部模式，高分辨率车机进度条不再偏小
- **横屏播放页顶部按钮边距调整** — 顶部右侧三按钮（频谱/换装/歌词搜索）padding 改为屏高 5%（原 22%），竖屏保持不变
- **图标更新** — 歌词搜索（三行歌词样式）、换装（皮肤）、首页帮助（问号圆环）三处按钮图标替换为新绘制样式
- **横屏首页顶部图标下限调整** — 图标尺寸下限从 36dp 降至 20dp，比例值（栏高 72%）在常见车机分辨率下正常生效

### v1.0.7.3 (2026-09)
#### 优化
- **竖屏非全屏歌词字号调大** — 当前行屏宽 6%（原 4.8%）、非当前行 4.5%（原 3.6%），与全屏模式当前行统一，横屏比例不变

### v1.0.7.2 (2026-09)
#### 优化
- **播放页返回按钮调大** — 顶部左侧返回按钮横竖屏均放大 30%，内边距不变，其他按钮不受影响
- **轮播模式横竖屏切换边距修复** — 封面位置改用纯比例计算顶栏高度，不再依赖 getHeight() 实时值，横屏轮播切竖屏首帧边距不再偏小
- **乐酷悬浮区域歌词方向一致性** — 歌词方向判断与歌名统一数据源（悬浮时优先用悬浮区域宽高），修复悬浮区域模式下两者不一致的问题

### v1.0.7.1 (2026-09)
#### 优化
- **竖屏全屏歌词字号分级** — 竖屏全屏歌词模式下当前行按屏宽 6%、其他行 5%（原统一 4.8%），主次分明
- **横屏全屏歌词字号与非全屏一致** — 横屏全屏模式下当前行 4.8%、其他行 3.6%，与双行/多行模式字号逻辑完全统一
- **播放页弹窗分辨率缩放** — 频谱选择、歌词搜索、播放历史三个弹窗随页面分辨率等比缩放（基准 16sp，竖屏锚点屏高 1.88%、横屏锚点屏高 3%），高分辨率车机弹窗不再过小；播放历史从系统对话框改为自定义浮窗并纳入统一缩放

#### 修复
- **竖屏歌词字号与歌名不一致** — 竖屏歌词字号基数扣除 margin 后比歌名小 12%；改为基数加回水平 margin 与歌名（全屏宽）完全一致，两者严格等大；屏幕方向改用系统 Configuration 判断，不再用视图宽高比（竖屏歌词区"宽矮"视图会被误判为横屏）
- **经典全屏→沉浸模式歌词残留** — 沉浸模式不支持全屏歌词，进入时若处于全屏则自动降级为多行模式
- **经典全屏→唱机模式唱臂残留** — 竖屏全屏歌词下切到唱机模式唱臂未隐藏，进入时自动隐藏、退出全屏恢复显示

### v1.0.7.0 (2026-09)
#### 优化
- **全页面纯比例缩放改造** — 首页横竖屏所有区域按屏高比例缩放：顶部栏/Tab栏/路径栏 8%、Mini播放条 10%、搜索栏 8%、版权文字 1.6%；搜索栏改缩放父容器高度，文字随容器比例缩放；播放页歌名/封面边距、进度时间文字全部改比例值
- **播放页歌名/歌词字号纯比例** — 所有场景（经典/沉浸/轮播、横竖屏）歌名字号统一按宽度比例缩放，歌词字号按 viewWidth 比例缩放，全部去除固定像素上下限
- **播放页按钮按屏高分档缩放** — 按屏高像素三档定比例 + 超宽屏宽高比修正，低 density 高分辨率车机不再按钮偏小
- **网格歌名优化** — 按屏高比例缩放（竖屏 2%、横屏 2.5%）并去除 sp 下限，改单行显示，横竖屏切换自动重算字号
- **横屏播放页顶部按钮放大** — 返回按钮和右侧三按钮占屏高比例各 +0.5%（底部按钮不变）

#### 修复
- **偶发"格式不支持"误判** — 网络抖动、解码器被占用等瞬态错误不再直接判定为格式不支持：IO/解码器初始化类错误自动重试一次，重试成功继续播放；Toast 按真实原因分类提示（网络/格式），不再一律报"不支持此音频格式"
- **轨道选择竞态误判** — 切歌瞬间音频轨道尚未选中不再误触发 MediaPlayer 兜底，延迟 500ms 二次确认后才判定格式不支持
- **连续播放失败无提示** — 连续 3 首失败停止自动切歌时，播放页 Toast 告知用户原因（此前该广播无人接收，静默停止）
- **WebDAV m4a 播放失败** — 无 ALAC 解码器设备上 WebDAV m4a 走 MediaPlayer 兜底因无法携带认证头 401 必败，改为 ExoPlayer 直接播放

### v1.0.6.9 (2026-09)
#### 优化
- **封面公共目录优化** — 封面不再复制到公共目录 Download/jingxinmusic/cover/，只存储应用私有缓存；MediaSession metadata 去掉 URI 字段只保留 Bitmap，减少公共目录 IO，简化封面传输链路

### v1.0.6.8 (2026-09)
#### 新功能
- **播放进度记忆** — 重启 App 后从上次播放位置继续播放，无需手动拖拽进度条
#### 优化
- **清除全部调试日志** — 移除 157 处 Log.d 调试输出，保留 Log.w/Log.e，减少不必要的日志开销

### v1.0.6.7 (2026-08)
#### 优化
- **悬浮窗权限对话框新增「不再提示」按钮** — 部分车机无法设置悬浮窗权限，点「不再提示」后不再弹出，避免反复打扰用户

### v1.0.6.6 (2026-08)
#### 优化
- **音乐扫描缓存优化** — 去除缓存时间过期机制，手动扫描前缓存一直有效（仅 U盘变化/插拔时自动失效），解决车机每次启动都全量扫描的问题，启动秒开

### v1.0.6.5 (2026-08)
#### 修复
- **频谱分屏停止** — startSpectrumWithPermission 新增守卫防止广播死循环；onPause/onStop 不再停止频谱，避免车机系统 onResume↔onStop 循环反复销毁 Visualizer
- **CrashHandler 崩溃恢复优化** — BadTokenException 从「吞掉不退出」改为「重启进程」，解决车机 Activity 重建后分屏黑屏问题

### v1.0.6.4 (2026-08)
#### 新功能
- **本地崩溃日志** — CrashHandler 自动落盘崩溃日志到 `Android/data/.../files/log/`，含完整堆栈/设备/版本信息；系统框架级异常（BadTokenException）只记录不退出进程
- **日夜主题时间自动切换** — 设置页新增「按时间自动切换」开关，可自定义白天/夜间分界时间；优先级：高德广播 > 时间规则 > 记忆

#### 修复
- **频谱首次播放不显示** — ExoPlayer 异步 prepare 时 sessionId=0，改为 300ms 重试最多 10 次再降级 AudioRecord
- **设置页时间选择行文字颜色不随日夜模式变化** — 5 个 TextView 提前初始化，applyTheme 统一着色
- **Android 10 车机 MediaStore ContentObserver 注册崩溃** — 车机 ROM 的 MediaStore Provider 不可用导致 SecurityException，try-catch 防护
- **Android 10+ 误弹存储写入权限对话框** — WRITE_EXTERNAL_STORAGE 的 maxSdkVersion=28，仅 API<=28 检查写入权限

### v1.0.6.3 (2026-08)
#### 修复
- **首次进入乐酷悬浮区域时页面布局异常** — 主动发 FLOAT_LAYOUT_REFRESH 广播刷新布局 + 修复 updateFloatWindowSize 未更新 LayoutParams 导致尺寸弹回
- **独立悬浮窗点击跳全屏后不自动关闭** — 乐酷重复 closemap 广播导致 MiniFloatService 被重新启动，改为记录 wasFloating 状态 + closeFloat() 统一移除
- **轮播模式横→竖切换时歌词字号跳变为全屏大小** — 修复悬浮区域调整高度时竖屏判定误用全屏宽度
- **唱片机模式下唱臂随悬浮区域宽度变化的位置更新** — 改用 coverView OnLayoutChangeListener 精确跟随封面实际 layout
- **频谱抢占式仲裁 + 独立悬浮窗 START_NOT_STICKY + lecoFloatExit 竞态修复**

### v1.0.6.2 (2026-08)
- **LRC 编码自动识别** — 自动检测 UTF-8/GBK 编码，解决乱码歌词
- **横屏路径栏比例调整**
- **双悬浮窗修复**

### v1.0.6.1 (2026-08)
- **双源更新机制** — Gitee 优先 + GitHub 回退，提升国内更新成功率
- **FileProvider 修复** — 修复 Android 7+ APK 安装崩溃
- **启动检查更新修复**

### v1.0.6 (2026-08)
#### 新功能
- 乐酷桌面悬浮区域显示完整播放页，支持拖动调整大小
- 单 Activity + Fragment 架构改造，Fragment 事务切换页面，悬浮态更稳定
- 播放页新增歌词搜索按钮，手动多源搜索（酷狗20条+网易云10条），时长匹配排序+最佳标记
- 设置页统一收纳功能按钮，新增歌词高亮颜色选择器、频谱开关
- 新增优先读取本地封面/歌词开关、启动直达播放开关
- 白天高亮歌词颜色改为红色 #E53935
- 移除悬浮窗右上角关闭按钮，仅保留双击关闭

#### 修复
- Android 14/15/16 多版本兼容性崩溃
- WebDAV 悬浮态返回目录浏览数据丢失
- 悬浮模式列数计算错误、频谱停止、胶囊播放按钮失效
- Android 11+ U盘音乐扫描失败，新增 MANAGE_EXTERNAL_STORAGE 授权流程
- 高德车机版日夜切换导致播放页歌曲跳回第一首
- 返回列表页重复加载扫描
- 收藏数量显示和收藏搜索功能
- 封面加载逻辑改进（内嵌封面提取步骤）
- B站设置页返回按钮日夜模式着色
- 默认封面显示耳机图标替代纯色方块
- Android 15 播放页封面与顶部按钮交叉（经典+轮播模式）

#### 优化
- 帮助页面更新（设置页10项说明，删除已移除章节）
- 冗余代码清理，悬浮首页横竖屏判断统一

<!--EN-->

## 📢 Latest Version (v1.0.9.3)

### v1.0.9.3 (2026-11)
#### New Features

- **Ninth player style "Lattice"** — an infinite cover wall: an endless mosaic world of covers with drag/fling browsing; tap any tile to expand it into a 6×6 card and switch songs — the expanded card integrates transport keys, top progress line, bottom spectrum band and title/artist; long-press anywhere for the top bar, tap the big cover to jump into the lyric-effect page
- **Expanded-card border light** — a comet streak travels along the expanded card's border while playing (9 s per lap) and turns into a breathing glow when paused; color shared with spectrum/progress line, following the album cover
- **Style picker popup** — the outfit button now opens a style list (Classic / Immersive / Turntable / Carousel / Lattice, current highlighted) instead of cycling, matching the effect/lyric-effect popups
- **Foreground colors follow album cover** — player and immersive pages tint foreground from the cover; new highlight-follows-cover toggle
- **Control bar icon overhaul** — glass-capsule play button icon enlarged 30%, all other control icons restyled

#### Stability & Performance

- Removed all software-layer compositing project-wide — Fume / Diorama / Pendolo / AI-voice spectrum back to hardware acceleration, smoother on low-end car head units
- Fixed Leco PiP resize black screen; default background skin pipeline finalized
- Unified top-bar right five-button padding across orientations; lattice spectrum re-seat fix

#### License & Compliance

- Project license unified under AGPL-3.0; derivation notices for FOLIA (Lattice style + seven lyric effects) and SpectrumForAndroid (some spectrum styles)
- "Starfield Roam" renamed to "Starfield"

### v1.0.9.2 (2026-10)

- **Direct-to-lyric-effect** — new settings toggle: launch straight into the last-remembered lyric effect playback; mutually exclusive with direct-to-player
- **Update-push toggle** — new settings switch (default on): disable automatic update checks at launch; manual check in Help unaffected
- Stability hardening: home cover grid, background animation load, progress refresh and more

### v1.0.9.1 (2026-10)
#### Brand-new App Icon

- Complete visual redesign: full-density legacy icons (48–192) + adaptive icon layers (108–432 foreground/background) for Android 8.0+ masks — consistent across phone, car head unit and TV

### v1.0.9.0 (2026-10)
#### Major Changes (vs v1.0.8.2)

**① Lyric Effects: 2 → 8 (folia visualizer family port + in-house Starfield)**

Faithful port of the folia open-source project — seven per-word lyric effects, driven by KRC per-char timing with graceful fallback; plus an in-house Starfield effect.

- **Glow** — folia classic per-word three-state engine (waiting/active/passed) with depth-of-field ambient dust
- **Pendolo** — mechanical escapement gear core + arc lyric track; chorus word-sweep deepening, dual-layer glow, row backlight, no-wrap autoshrink for long lines
- **Cadenza** — word-cloud burst with protagonist-word collision scatter
- **Tilt** — italic monologue style, per-paragraph reveal + char-level afterglow decay
- **Partita** — semantic-unit splitting, multi-column stairway, L-shaped guide lines, per-word in-chunk highlight
- **Fume** — whole song set as a vertical article with camera dolly reading
- **Diorama** — winding corridor advancing line by line, 13 cinematic shot types, 3.2s flight transition on song change, dust particles + depth fog

Unified interaction: long-press lyrics to enter, single-tap to exit; cover-flow control (left two = prev / center = play-pause / right two = next), highlight color adapts to album cover dual colors, day/night auto theme, starfield background on all effect pages.

**② Player Page Style Architecture Consolidation**

Classic / Immersive / Turntable / Carousel styles unified into StandardPageBase equal inheritance; top bar componentized as PlayerTopBar; StagePage protocol with ensurePage factory + activePage polymorphic dispatch; spectrum cross-style adaptation: ring-style fallback to bars with persistence. Nearly 800 lines removed with visual parity.

**③ Fixes & Polish**

- **Leco split-screen crash fixed** — App process killed by RemoteServiceException when resizing split-screen in Leco launcher, black screen; three-layer fix: background-detection debounce 300ms→2000ms (no longer misjudging split-screen reflow as background), float-window start/stop 2s throttle, MiniFloatService onStartCommand startForeground safety net — independent float window still appears correctly when switching to other apps on Leco
- **Pendolo on Android 8.1 car stability** — spring integrator diverged at low frame rates (pos explosion → NaN → view stops drawing); switched to RK4 integrator with identical feel + NaN self-healing fallback
- **Per-char pulse / new-line drop animation** — pulse length from real sung duration (260~700ms), under-damped spring drop with 12% overshoot rebound
- **Effect picker scrolling** — options-only scroll with pinned title, 80% screen-height cap, fixes unreachable bottom options on car landscape
- **Removed Claddagh effect** — didn't fit listening scenarios, picker back to 7 items

### v1.0.8.2 (2026-10)
#### Improvements
- **Kugou bar spectrum left-right symmetry** — Second group data now mirrored, right half displays the same data sequence as left half, symmetric outline (bell weight peak lands on screen centerline), fixes the always-low right segment and unbalanced look
- **Kugou spectrum aurora dual-color follow** — Under aurora dual-color mode, both gradient groups follow cover dual-color interpolation (auto recolors on track change), static bottom blocks synced; auto hue spread when two colors too close (40° threshold) to preserve layering, falls back to original warm colors on exit
- **Dot spectrum size adaptive** — Dot size now min of width/height dimensions (diameter ≤ 22% of band height), fixes oversized dots on landscape/ultrawide screens, portrait look unchanged

### v1.0.8.1 (2026-10)
#### New Features
- **Player page control bar auto-hide** — Configurable 5~30s delay or fully off, settings page adds delay option, control bar fades out automatically when idle, tap screen to restore
- **Auto-start on boot toggle** — Settings page adds toggle, app auto-launches on boot broadcast, music starts when car head unit / TV box powers on

### v1.0.8.0 (2026-10)
#### New Features
- **Software audio effect processor** — Bypasses car head unit vendor EQ (e.g., NXP multi-channel switch silence issue), self-developed Media3 AudioProcessor software solution: 5-stage biquad cascade filter, 7 presets (Off/Vocal/Pop/Rock/Classical/Bass Boost/Surround), EQ curves based on Winamp genre curves at 1.5× boost (peak ±9dB), BassBoost/Virtualizer 80%, accessible via player page popup (left-aligned, vertically centered, single column, font auto-scale matching spectrum popup)
- **Volume normalization (loudness equalization)** — ReplayGain/EBU R128-based volume normalization toggle, scans true loudness of songs and automatically levels perceived volume, no manual adjustment needed when switching tracks

#### Bug Fixes
- **Fallback playback cross-thread crash** — On audio-abnormal devices using MediaPlayer fallback, setOnCompletionListener/setOnErrorListener/catch block called playNext()/handlePlaybackError() directly on FallbackInit thread accessing exoPlayer, triggering `IllegalStateException: Player is accessed on the wrong thread`; unified via handler.post() to switch back to main thread

#### Improvements
- **Redundant code cleanup** — Removed 4 unused fields in SpectrumView, 33 unused imports across 28 files, 1 unused drawable, no functional impact

### v1.0.7.9 (2026-10)
#### Bug Fixes
- **External control failure on audio-abnormal devices** — Some car head units (e.g., TS18) with abnormal audio services fail to construct ExoPlayer, causing many songs to fall back to MediaPlayer; external apps like Leco Desktop sending MediaSession pause/resume get no response, progress stuck at 0, lyrics not scrolling. MediaSession callbacks and notification bar now unified through fallback-aware pausePlayback()/resumePlayback(), position reading uses fallback-aware getCurrentPosition(), same root cause fixed together
- **Log cleanup** — Residual printStackTrace() in BlurUtil/KrcParser unified to android.util.Log.e (crash log stack traces kept as functional code)

### v1.0.7.8 (2026-10)
#### Bug Fixes
- **Android 5.1 / 6 / 7 compatibility** — Full adaptation for low-end car systems, fixing multiple crashes and lag:
  - **Android 6 crash fix** — R8 obfuscation of XmlPullParser interface name caused framework XmlBlock runtime IncompatibleClassChangeError; getStorageVolumes() missing on Android 6 caused scan crash, added API 24 guard with fallback path; GradientDrawable.getColors() API 24+ caused browse page crash, switched to ThemeColors returning color array directly; onServiceConnected thread UI update caused CalledFromWrongThreadException
  - **Android 5.1 audio-abnormal ANR protection** — ExoPlayer construct/release moved to sub-thread (generateAudioSessionId in constructor Binder-calls AudioFlinger, which blocks indefinitely on frozen AudioFlinger devices); auto-fallback to MediaPlayer when not ready; AudioRecord release sub-threaded to prevent main thread freeze
  - **Android 5.1 popup fix** — Spectrum/lyric search/history popups couldn't close on outside tap (5.1 PopupWindow with null background doesn't create PopupViewContainer, outside tap swallowed by decor), set transparent background to trigger container creation
  - **Android 5.1 low-memory optimization** — Embedded cover unified downsampled decode (full-size decode on ~64MB heap caused OOM with large covers); spectrum switched to double-buffered array reuse, eliminating per-frame new float[] GC pressure and tearing
  - **Android 7 and all versions** — setForeground unified via API 23 compat wrapper, float window no longer crashes on 5.1~7
- **USB drive removal residual songs** — Car ROM MediaStore retains dead records after USB removal (BAD_REMOVAL), scan now validates file existence before adding, songs cleanly disappear after re-scan
- **Cover missing after scan, only after playback** — Grid and player cover caches used incompatible naming, unified to "song name - artist.jpg" shared app-wide; grid online search uses cleaned title+artist, matching player page hit rate
- **Folder cover not updating** — Folder cover cache never invalidated, showing old cover after song change; added folder music file fingerprint check (.cover_cache_meta.txt), cache auto-invalidates and rebuilds when folder content changes

### v1.0.7.7 (2026-09)
#### Bug Fixes
- **Update detection failure due to Gitee API rate limiting** — When Gitee API v5 returns 403 (WAF rate limited), added fallback to parse Gitee Releases page HTML for latest version, APK download link, and release notes, ensuring update detection works across all network conditions

### v1.0.7.6 (2026-09)
#### New Features
- **Dual-color aurora background skin** — New background skin option in settings: Default/Dual-color Aurora, self-drawn aurora effect with 128×128 LUT and precise elliptical depth function, 10×10 downsampling + non-uniform 8 hue-bucket color extraction; aurora brightened 20% in day, dimmed 15% at night; applies to Classic/Record/Carousel modes only (not Immersive)
- **Home dynamic background system** — three-layer self-drawn architecture (base color + bottom-left mask tint + top-right gradient), mask pixels from cover dominant color, 8-bucket HSV extraction + 10-tier hue lookup; home tab bar with two-line text + sliding indicator + light stream, icon colors linked to cover extraction, transparent list items in dynamic mode; Help/Settings/WebDAV/Bilibili pages integrated
- **Embedded lyrics reading** — Embedded lyrics now highest priority in load order (when toggle enabled), supporting MP3 USLT/SYLT, M4A ©lyr, FLAC LYRICS tags
- **Spectrum colors follow cover** — Bar/dot/wave/NetEase ring/diffusion ring spectrums follow cover dual colors in aurora mode (day: follow, night: white; non-aurora keeps original colors)
- **Lyrics highlight color follows cover** — In aurora + classic mode, lyrics highlight color follows cover dominant color
- **Immersive mode aurora adaptation** — Portrait: cover at 43% height + full-screen aurora overlay; Landscape: cover at bottom layer + gradient transition band + aurora overlay, background restored on exit

#### Improvements
- **Copyright text** — Changed to "A calm mind, clear sound · v{dynamic version}"
- **Aurora progress bar** — Progress bar/thumb follows button color (black in day, white at night), light gray track
- **Page interaction refactor** — Home help button changed to settings; settings page adds help button at top-right; help page removes settings button; Bilibili/WebDAV path bar settings button gets new cloud-wrench icon, margins 12→8dp
- **Help page details** — Text content rebuilt on day/night switch, bottom buttons single-line no-wrap

#### Bug Fixes
- **Amap day/night follow chain** — Receiver moved from Service to Application level; Amap day/night switching now works on all pages even when not playing music (Service not running); Settings/Bilibili/WebDAV/Help pages follow via broadcast in floating state, fixed silent registration failure
- **Cold start dominant color persistence** — Dominant color persisted, cover gradient appears instantly on cold start; aurora extraction syncs static color, home gradient consistent when returning
- **Aurora color linkage fixes** — Song title theme color and progress bar colors sync when switching back to aurora; spectrum restores original colors after exiting aurora; fast song-switch extraction race; landscape rotation no longer leaves old background color
- **FLAC artist unknown fallback** — Proper fallback info when metadata missing

### v1.0.7.5 (2026-09)
#### Fix
- **Android 8.1 player crash** — v1.0.7.4 progress bar scaling used setMinHeight/setMaxHeight, which does not exist on some Android 8.1 ROMs causing a crash; replaced with track drawable height, compatible with all versions

### v1.0.7.4 (2026-09)
#### Improvements
- **Player progress bar proportional scaling** — Thumb 2%, time text 2%, track 0.5% of screen height, ultrawide aspect compensation, covers all modes in both orientations
- **Landscape player top buttons padding** — Top-right three buttons (spectrum/outfit/lyric search) padding reduced to 5% screen height (was 22%); portrait unchanged
- **Icon updates** — Lyric search, outfit, and help buttons replaced with new drawn styles
- **Landscape home top icon lower bound** — Icon size floor reduced from 36dp to 20dp so the proportional value (72% of bar height) takes effect on common car screens

### v1.0.7.3 (2026-09)
#### Improvements
- **Portrait non-fullscreen lyrics font enlarged** — Current line 6% screen width (was 4.8%), other lines 4.5% (was 3.6%), matching fullscreen current line size; landscape ratios unchanged

### v1.0.7.2 (2026-09)
#### Improvements
- **Player back button enlarged** — Top-left back button scaled up 30% in both orientations, padding unchanged
- **Carousel orientation switch margin fix** — Cover position uses pure-ratio top bar height calculation, no longer depends on getHeight()
- **Leco floating lyrics direction consistency** — Lyrics direction detection unified with song title data source

### v1.0.7.1 (2026-09)
#### Improvements
- Portrait fullscreen lyrics font tiering — current line 6%, other lines 5%
- Landscape fullscreen lyrics font unified with non-fullscreen
- Player page dialogs scale with resolution

#### Bug Fixes
- Portrait lyrics font size mismatch with song title
- Classic fullscreen → immersive mode lyrics residual
- Classic fullscreen → record mode tonearm residual

### v1.0.6.8 (2026-09)
#### New Features
- **Playback progress memory** — Resume from last played position after app restart, no manual seeking needed
#### Improvements
- **Debug log cleanup** — Removed 157 Log.d debug outputs, kept Log.w/Log.e, reducing unnecessary logging overhead

### v1.0.6.7 (2026-08)
#### Improvements
- **Float permission dialog adds "Never ask" button** — Some car head units cannot configure overlay permission; "Never ask" stops repeated prompts

### v1.0.6.6 (2026-08)
#### Improvements
- **Music scan cache optimization** — Removed cache time expiry, cache valid until manual scan (auto-invalidates on USB change/plug), instant startup

### v1.0.6.5 (2026-08)
#### Bug Fixes
- **Spectrum stops in split-screen** — Added guard in startSpectrumWithPermission to prevent broadcast loop; onPause/onStop no longer stop spectrum, fixing Visualizer destruction by car system's onResume↔onStop cycle
- **CrashHandler crash recovery** — BadTokenException changed from "swallow and continue" to "restart process", fixing black screen after car Activity relaunch

### v1.0.6.4 (2026-08)
#### New Features
- **Local crash logging** — CrashHandler writes crash logs to `Android/data/.../files/log/` with full stack trace/device/version info; system framework exceptions (BadTokenException) logged without process exit
- **Day/night theme auto-switch by time** — New "auto-switch by time" toggle in settings with customizable day/night boundary; priority: Amap broadcast > time rule > memory

#### Bug Fixes
- **Spectrum not showing on first play** — ExoPlayer async prepare returns sessionId=0, now retries every 300ms up to 10 times before falling back to AudioRecord
- **Settings time selector text color not following day/night theme** — 5 TextViews initialized early, applyTheme unified coloring
- **Android 10 car head unit MediaStore ContentObserver crash** — SecurityException from unavailable MediaStore Provider on car ROM, try-catch protection
- **Android 10+ false storage write permission dialog** — WRITE_EXTERNAL_STORAGE maxSdkVersion=28, only check write permission on API<=28

### v1.0.6.3 (2026-08)
#### Bug Fixes
- **First entry to Leco floating area layout error** — Active FLOAT_LAYOUT_REFRESH broadcast + fixed updateFloatWindowSize not updating LayoutParams causing size bounce
- **Standalone float window not closing after tap-to-fullscreen** — Repeated closemap broadcast caused MiniFloatService restart, fixed with wasFloating state + closeFloat() unified removal
- **Carousel mode landscape→portrait lyrics font size jump** — Fixed floating area height adjustment using full-screen width for portrait detection
- **Record mode tonearm position not following floating width** — Switched to coverView OnLayoutChangeListener for precise tracking
- **Spectrum preemptive arbitration + standalone float START_NOT_STICKY + lecoFloatExit race condition fix**

### v1.0.6.2 (2026-08)
- **LRC encoding auto-detection** — Auto-detect UTF-8/GBK encoding to fix garbled lyrics
- **Landscape path bar ratio adjustment**
- **Dual float window fix**

### v1.0.6.1 (2026-08)
- **Dual-source update** — Gitee priority + GitHub fallback, improved domestic update success rate
- **FileProvider fix** — Fixed Android 7+ APK install crash
- **Startup update check fix**

### v1.0.6 (2026-08)
#### New Features
- Leco Desktop floating area full player page display, supports drag-to-resize
- Single Activity + Fragment architecture refactor, Fragment transaction page switching, more stable floating
- Player page lyrics search button, multi-source search (Kugou 20 + Netease 10), duration-matched sorting + best match
- Unified settings page, new lyrics highlight color picker, spectrum toggle
- New local cover/lyrics priority toggle, launch-to-player toggle
- Daytime lyrics highlight color changed to red #E53935
- Removed float window close button, double-tap to close only

#### Bug Fixes
- Android 14/15/16 multi-version compatibility crashes
- WebDAV floating state directory browse data loss
- Floating mode column count, spectrum stop, capsule play button failure
- Android 11+ USB music scan failure, added MANAGE_EXTERNAL_STORAGE authorization flow
- Amap car day/night change causing song restart
- List page duplicate load on return from player
- Favorites count display and search functionality
- Cover loading logic improvement (embedded cover extraction)
- Bilibili settings back button day/night tinting
- Default cover headphone icon instead of solid block
- Android 15 cover overlapping top buttons (classic + carousel portrait)

#### Improvements
- Help page update (10 settings descriptions, removed obsolete sections)
- Redundant code cleanup, unified floating landscape/portrait detection

<!--END-->

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
- **在线更新** — GitHub Release 自动检查更新，4镜像测速下载，三层校验，启动静默检查+手动检查
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

**频谱部分样式**（柱状/酷狗柱状/AI语音/波形柱/扩散圆环/波浪圆环及频段归一化算法）移植自开源项目
[SpectrumForAndroid](https://github.com/michael007js/SpectrumForAndroid)（作者 michael007js，
无明确许可声明，仅作技术学习用途移植，特此注明出处并致谢）。竖条/圆点/波浪线/网易圆环为自实现。

**拼贴播放风格**与**歌词动效体系**移植自开源项目
[FOLIA](https://github.com/chthollyphile/folia-major)（AGPL-3.0）：
- 拼贴风格：`view/lattice/`、`stage/LatticePageV2`，源自 Lattice 封面墙
- 歌词动效：`view/GlowLyricView`、`PendoloLyricView`、`PendoloClockworkView`、
  `CadenzaLyricView`、`TiltLyricView`、`PartitaLyricView`、`FumeLyricView`、
  `DioramaLyricView` 及对应 `lyriceffect/` 页面，分别源自 folia 的
  classic / pendolo / cadenza / tilt / partita / fume / diorama 效果

原项目为 AGPL-3.0，与本项目整体许可一致（见下方许可证说明）。

---

## APK 大小

| 构建类型 | 大小 |
|---------|------|
| Debug | ~5.0 MB |
| Release（R8 混淆） | ~2.0 MB |

---

## 更新日志

### v1.0.8.2 (2026-10)
#### 优化
- 酷狗柱状频谱左右对称：第二组数据镜像取样，右半屏与左半屏数据序列相同，轮廓对称，修复右侧偏低
- 酷狗频谱双色光晕跟随：渐变跟随封面双色插值，切歌自动变色，两色过近自动拉开色相；退出回落原版暖色
- 圆点频谱尺寸自适应：宽高双维取小（直径≤条带高度 22%），横屏/超宽屏不过大，竖屏不变

### v1.0.8.1 (2026-10)
#### 新功能
- 播放页控制栏自动隐藏：支持 5~30 秒可配置延时或彻底关闭，设置页新增延时选项，无操作时控制栏自动淡出，点击屏幕恢复
- 开机自启动开关：设置页新增开关，开启后开机广播自动启动应用，车机/电视盒子上电即拉起音乐

### v1.0.8.0 (2026-10)
#### 新功能
- 软件音效处理器：绕开车机厂商私有 EQ 效果器（NXP 多声道切换静音），自研 Media3 AudioProcessor 软件方案，5 段 biquad 级联滤波，7 预设（关闭/人声/流行/摇滚/古典/低音增强/全景环绕），EQ 曲线基于 Winamp 流派曲线 1.5 倍加强档（峰值 ±9dB），BassBoost/Virtualizer 80%，入口在播放页弹窗
- 音量均衡（响度归一化）：基于 ReplayGain/EBU R128 扫描歌曲真实响度并自动拉平听感，切换歌曲无需手动调音量

#### 修复
- fallback 播放跨线程崩溃：音频异常设备走 MediaPlayer 兜底时，setOnCompletionListener/setOnErrorListener/catch 块在 FallbackInit 子线程直接调用 playNext()/handlePlaybackError() 访问 exoPlayer 触发 `IllegalStateException: Player is accessed on the wrong thread`，统一通过 handler.post() 切回主线程

#### 优化
- 冗余代码清理：删除 SpectrumView 4 个未使用字段、33 处未使用 import、1 个未使用 drawable

### v1.0.7.9 (2026-10)
#### 修复
- 音频异常设备外部控制失效：MediaSession 回调/通知栏统一走 fallback 感知的 pausePlayback()/resumePlayback()，进度改用 fallback-aware 的 getCurrentPosition()，外部应用（乐酷桌面）在 MediaPlayer 兑底模式下暂停/进度/歌词恢复正常
- 日志规范清理：printStackTrace() 残留统一为 android.util.Log.e

### v1.0.7.8 (2026-10)
#### 修复
- 适配安卓 5.1/6/7 低端车机：安卓 6 四项崩溃修复（XmlPullParser 混淆/getStorageVolumes/GradientDrawable.getColors/子线程UI）；5.1 音频服务异常设备 ExoPlayer 构造/释放子线程化防 ANR；5.1 弹窗点外部可关闭；5.1 小内存封面降采样+频谱双缓冲；全版本 setForeground API 23 兼容
- U盘拔出后歌曲残留：扫描入列前校验文件真实存在，拔盘后重新扫描干净消失
- 扫描后无封面：网格与播放页封面缓存统一「歌名 - 歌手.jpg」全应用共享
- 文件夹封面不更新：新增目录指纹校验，换歌自动失效重建

### v1.0.7.6 (2026-09)
#### 新功能
- **双色光晕背景皮肤** — 设置页新增背景皮肤选项（默认/双色光晕），自研自绘极光光晕效果，128×128 LUT 精确移植，10×10 降采样+8 色相桶取色，白天提亮 20%、夜间调暗 15%，经典/唱机/轮播模式生效
- **首页动态背景体系** — 底色+左下蒙版染色+右上渐变三层自绘，蒙版像素取自封面主色；首页 Tab 栏双行文字+滑动指示器+流光线，图标颜色随封面取色联动；帮助/设置/WebDAV/B站页同步接入
- **内嵌歌词读取** — 加载顺序新增内嵌歌词最优先（开关开启时），支持 MP3 USLT/SYLT、M4A ©lyr、FLAC LYRICS
- **频谱颜色跟随封面** — 竖条/圆点/波浪线/波形柱/网易圆环/扩散圆环在光晕模式下跟随封面双色（白天跟随、夜间白色）
- **歌词高亮色随封面联动** — 双色光晕+经典模式下歌词高亮色跟随封面主色
- **沉浸模式双色光晕适配** — 竖屏封面 43% 高度+光晕整屏叠加；横屏封面最底层+渐变过渡带+光晕叠加

#### 优化
- 版权文案改为「心静，音自清·v动态版本号」
- 光晕模式进度条/圆球跟随按钮色（白天黑、夜间白），轨道浅灰
- 首页帮助按钮改设置、设置页新增帮助按钮、帮助页移除设置按钮；B站/WebDAV 路径栏设置按钮换云朵扳手新图标，边距 12→8dp
- 帮助页文字日夜切换重建、底部按钮单行防折行

#### 修复
- 高德日夜接收器上移 App 应用级，不播放音乐时链路不断；设置/B站/WebDAV/帮助四页悬浮态广播跟随，修复注册静默失效
- 首页主色持久化，冷启动秒出封面渐变，返回首页渐变一致
- 切回光晕后歌名/进度条配色同步、退出光晕频谱恢复原色、快速切歌取色竞态、横屏旋转残留旧底色
- FLAC 歌手 unknown 兜底

### v1.0.7.3 (2026-09)
#### 优化
- 竖屏非全屏歌词字号调大——当前行屏宽 6%（原 4.8%）、非当前行 4.5%（原 3.6%），横屏不变

### v1.0.7.2 (2026-09)
#### 优化
- 播放页返回按钮调大——横竖屏均放大 30%，内边距不变
- 轮播模式横竖屏切换边距修复——封面位置改用纯比例计算顶栏高度，横屏切竖屏首帧边距不再偏小
- 乐酷悬浮区域歌词方向一致性——歌词方向判断与歌名统一数据源

### v1.0.7.1 (2026-09)
#### 优化
- 竖屏全屏歌词字号分级——当前行屏宽 6%、其他行 5%，主次分明
- 横屏全屏歌词字号与非全屏统一——当前行 4.8%、其他行 3.6%
- 播放页弹窗（频谱/歌词搜索/播放历史）随分辨率等比缩放，播放历史改自定义浮窗

#### 修复
- 竖屏歌词与歌名字号不一致——基数加回水平 margin 严格等大，方向判断改用 Configuration
- 经典全屏→沉浸模式歌词残留——沉浸模式不支持全屏，自动降级多行
- 经典全屏→唱机模式唱臂残留——进入时隐藏唱臂、退出恢复

### v1.0.7.0 (2026-09)
#### 优化
- 全页面纯比例缩放改造——首页横竖屏所有区域按屏高比例缩放（顶部栏/Tab栏/路径栏 8%、Mini播放条 10%、搜索栏 8%、版权 1.6%），搜索栏改缩放父容器；播放页歌名/歌词字号纯比例无固定上下限
- 播放页按钮按屏高分档缩放+超宽屏修正——低 density 高分辨率车机不再按钮偏小
- 网格歌名按屏高比例缩放（竖屏 2%、横屏 2.5%），去除 sp 下限，改单行显示，横竖屏切换自动重算
- 横屏播放页顶部按钮占屏高比例各 +0.5%（底部按钮不变）

#### 修复
- 偶发"格式不支持"误判——瞬态错误（网络抖动/解码器被占用）自动重试一次，Toast 按真实原因分类提示
- 轨道选择竞态误判——延迟 500ms 二次确认后才判定格式不支持
- 连续播放失败无提示——停止自动切歌时播放页 Toast 告知用户原因
- WebDAV m4a 播放失败——无 ALAC 解码器设备不再走 MediaPlayer 兜底（401 认证失败），改由 ExoPlayer 直接播放

### v1.0.6.9 (2026-09)
#### 优化
- 封面公共目录优化——封面不再复制到公共目录 Download/jingxinmusic/cover/，只存储应用私有缓存；MediaSession metadata 去掉 URI 字段只保留 Bitmap，减少公共目录 IO，简化封面传输链路

### v1.0.6.7 (2026-08)
#### 优化
- 悬浮窗权限对话框新增「不再提示」按钮——部分车机无法设置悬浮窗权限，点「不再提示」后不再弹出

### v1.0.6.6 (2026-08)
#### 优化
- 音乐扫描缓存优化——去除缓存时间过期机制，手动扫描前一直有效（仅 U盘变化/插拔时自动失效），启动秒开

### v1.0.6.5 (2026-08)
#### 修复
- 频谱分屏停止——startSpectrumWithPermission 守卫防止广播死循环；onPause/onStop 不再停止频谱，避免车机系统 onResume↔onStop 循环反复销毁 Visualizer
- CrashHandler 崩溃恢复优化——BadTokenException 从「吞掉不退出」改为「重启进程」，解决车机 Activity 重建后分屏黑屏

### v1.0.6.4 (2026-08)
#### 新功能
- 本地崩溃日志（CrashHandler 落盘，系统框架异常只记录不退出）
- 日夜主题时间自动切换（设置页可配置白天/夜间分界时间）

#### 修复
- 频谱首次播放不显示（sessionId=0 重试机制）
- 设置页时间选择行文字颜色不随日夜模式变化
- Android 10 车机 MediaStore ContentObserver 注册崩溃
- Android 10+ 误弹存储写入权限对话框

### v1.0.6.3 (2026-08)
#### 修复
- 首次进入乐酷悬浮区域时页面布局异常——主动发广播刷新布局+修复 updateFloatWindowSize 未更新 LayoutParams 导致尺寸弹回
- 独立悬浮窗点击跳全屏后不自动关闭——乐酷重复 closemap 广播导致 MiniFloatService 被重新启动
- 轮播模式横→竖切换时歌词字号跳变为全屏大小
- 唱片机模式下唱臂随悬浮区域宽度变化的位置更新
- 频谱抢占式仲裁 + 独立悬浮窗 START_NOT_STICKY + lecoFloatExit 竞态修复

### v1.0.6.2 (2026-08)
- LRC 编码自动识别（UTF-8/GBK），解决乱码歌词
- 横屏路径栏比例调整
- 双悬浮窗修复

### v1.0.6.1 (2026-08)
- 双源更新机制（Gitee 优先 + GitHub 回退），提升国内更新成功率
- FileProvider 修复，解决 Android 7+ APK 安装崩溃
- 启动检查更新修复

### v1.0.6 (2026-08)
#### 新功能
- 乐酷桌面悬浮区域显示完整播放页，支持拖动调整大小
- 单 Activity + Fragment 架构改造，Fragment 事务切换页面，悬浮态更稳定
- 播放页新增歌词搜索按钮，手动多源搜索（酷狗20条+网易云10条），时长匹配排序+最佳标记
- 设置页统一收纳功能按钮，新增歌词高亮颜色选择器、频谱开关
- 新增优先读取本地封面/歌词开关、启动直达播放开关
- 白天高亮歌词颜色改为红色 #E53935
- 移除悬浮窗右上角关闭按钮，仅保留双击关闭

#### 修复
- Android 14/15/16 多版本兼容性崩溃
- WebDAV 悬浮态返回目录浏览数据丢失
- 悬浮模式列数计算错误、频谱停止、胶囊播放按钮失效
- Android 11+ U盘音乐扫描失败，新增 MANAGE_EXTERNAL_STORAGE 授权流程
- 高德车机版日夜切换导致播放页歌曲跳回第一首
- 返回列表页重复加载扫描
- 收藏数量显示和收藏搜索功能
- 封面加载逻辑改进（内嵌封面提取步骤）
- B站设置页返回按钮日夜模式着色
- 默认封面显示耳机图标替代纯色方块
- Android 15 播放页封面与顶部按钮交叉（经典+轮播模式）

#### 优化
- 帮助页面更新（设置页10项说明，删除已移除章节）
- 冗余代码清理，悬浮首页横竖屏判断统一

### v1.0.5 (2026-08)
- 新增胶囊模式（灵动岛风格）和卡拉OK模式（双行歌词+封面+频谱）悬浮窗
- 卡拉OK悬浮窗歌词超宽时自动缩小字号以完全显示
- 新增帮助页（10种频谱样式参数说明、联系我们弹窗、检查更新按钮）
- 帮助图标单色镂空信息泡风格，适配昼夜模式
- 音乐扫描JSON缓存机制（10分钟有效期，启动秒开）
- 悬浮窗缩放限制调整（经典60%→80%，胶囊40%→60%）
- 悬浮窗面板修复（胶囊宽度滑条裁剪问题、面板文字颜色固定浅色）

### v1.0.4.1 (2026-08)
- 歌词文件命名改用原始标题（sanitizeFileName），搜索逻辑不变
- 兼容旧格式歌词文件回退匹配

### v1.0.4 (2026-08)
- 乐酷桌面首次启动无法获取封面修复（Service 自主加载封面，不依赖 PlayerActivity 前台）
- 播放页歌手颜色统一为与歌名一致（TextPrimary）

### v1.0.3 (2026-08)
- 悬浮窗透明度调节滑条（点击封面弹出面板，0-255实时调节，自动记忆）

### v1.0.2 (2026-08)
- 在线更新功能（GitHub Release检查+镜像测速下载+三层校验+安装）
- 封面公共目录从 Pictures/静心音乐/ 改为 Download/jingxinmusic/cover/
- 修复封面 saveCoverToPublic IS_PENDING 未清零导致对其他应用不可见的 bug
- 横屏路径栏比例从 6% 调整为 8%

### v1.0.1 (2026-07)
- 状态栏 inset 适配（MainActivity/PlayerActivity）
- B站异步竞态修复（biliFetching 标志位）
- 封面缓存去重（CoverLoader 检查缓存避免重复写入）
- 歌词广播修复（setPackage 解决 Android 16 广播丢弃）

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

> https://pd.qq.com/s/a8mi9wmfs?b=2

## 作者

静心 — 依托 TeleClaw 盲搓制作

- 网址：https://lecoauto.com
- 交流群：651547480

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

## License

**AGPL-3.0** (GNU Affero General Public License v3.0). Full license text: `LICENSE`
in this repository or <https://www.gnu.org/licenses/agpl-3.0.html>.

Everyone is free to use, modify and distribute this application, provided that any
distribution or network service makes the corresponding source code available under
AGPL-3.0 as well.

**Derivation notice**: the Lattice style (`view/lattice/`, `stage/LatticePageV2`, from
FOLIA's Lattice cover wall) and seven lyric effects (Glow / Pendolo / Cadenza / Tilt /
Partita / Fume / Diorama, from folia's classic / pendolo / cadenza / tilt / partita /
fume / diorama) are derived from [FOLIA](https://github.com/chthollyphile/folia-major)
(by chthollyphile); distributions must retain this notice and its copyright information.
Some spectrum styles (Columnar / Kugou / AI Voice / Wave Column / Diffusion Ring /
Wave Ring) are ported from
[SpectrumForAndroid](https://github.com/michael007js/SpectrumForAndroid)
(by michael007js, no explicit license notice; attributed here with thanks).

---

## 📢 Latest Version (v1.0.9.3)

### v1.0.9.3 (2026-11)
#### New Features

- **Ninth player style "Lattice"** — an infinite cover wall: an endless mosaic world of covers with drag/fling browsing; tap any tile to expand it into a 6×6 card and switch songs — the expanded card integrates transport keys, top progress line, bottom spectrum band and title/artist; long-press anywhere for the top bar, tap the big cover to jump into the lyric-effect page
- **Expanded-card border light** — a comet streak travels along the expanded card's border while playing (9 s per lap) and turns into a breathing glow when paused; color shared with spectrum/progress line, following the album cover
- **Style picker popup** — the outfit button now opens a style list (Classic / Immersive / Turntable / Carousel / Lattice, current highlighted) instead of cycling, matching the effect/lyric-effect popups
- **Foreground colors follow album cover** — player and immersive pages tint foreground from the cover; new highlight-follows-cover toggle
- **Control bar icon overhaul** — glass-capsule play button icon enlarged 30%, all other control icons restyled

#### Stability & Performance

- Removed all software-layer compositing project-wide — Fume / Diorama / Pendolo / AI-voice spectrum back to hardware acceleration, smoother on low-end car head units
- Fixed Leco PiP resize black screen; default background skin pipeline finalized
- Unified top-bar right five-button padding across orientations; lattice spectrum re-seat fix

#### License & Compliance

- Project license unified under AGPL-3.0; derivation notices for FOLIA (Lattice style + seven lyric effects) and SpectrumForAndroid (some spectrum styles)
- "Starfield Roam" renamed to "Starfield"

### v1.0.9.2 (2026-10)

- **Direct-to-lyric-effect** — new settings toggle: launch straight into the last-remembered lyric effect playback; mutually exclusive with direct-to-player
- **Update-push toggle** — new settings switch (default on): disable automatic update checks at launch; manual check in Help unaffected
- Stability hardening: home cover grid, background animation load, progress refresh and more

### v1.0.9.1 (2026-10)
#### Brand-new App Icon

- Complete visual redesign: full-density legacy icons (48–192) + adaptive icon layers (108–432 foreground/background) for Android 8.0+ masks — consistent across phone, car head unit and TV

### v1.0.9.0 (2026-10)
#### Major Changes (vs v1.0.8.2)

**① Lyric Effects: 2 → 8 (folia visualizer family port + in-house Starfield)**

Faithful port of the folia open-source project — seven per-word lyric effects, driven by KRC per-char timing with graceful fallback; plus an in-house Starfield effect.

- **Glow** — folia classic per-word three-state engine (waiting/active/passed) with depth-of-field ambient dust
- **Pendolo** — mechanical escapement gear core + arc lyric track; chorus word-sweep deepening, dual-layer glow, row backlight, no-wrap autoshrink for long lines
- **Cadenza** — word-cloud burst with protagonist-word collision scatter
- **Tilt** — italic monologue style, per-paragraph reveal + char-level afterglow decay
- **Partita** — semantic-unit splitting, multi-column stairway, L-shaped guide lines, per-word in-chunk highlight
- **Fume** — whole song set as a vertical article with camera dolly reading
- **Diorama** — winding corridor advancing line by line, 13 cinematic shot types, 3.2s flight transition on song change, dust particles + depth fog

Unified interaction: long-press lyrics to enter, single-tap to exit; cover-flow control (left two = prev / center = play-pause / right two = next), highlight color adapts to album cover dual colors, day/night auto theme, starfield background on all effect pages.

**② Player Page Style Architecture Consolidation**

Classic / Immersive / Turntable / Carousel styles unified into StandardPageBase equal inheritance; top bar componentized as PlayerTopBar; StagePage protocol with ensurePage factory + activePage polymorphic dispatch; spectrum cross-style adaptation: ring-style fallback to bars with persistence. Nearly 800 lines removed with visual parity.

**③ Fixes & Polish**

- **Leco split-screen crash fixed** — App process killed by RemoteServiceException when resizing split-screen in Leco launcher, black screen; three-layer fix: background-detection debounce 300ms→2000ms (no longer misjudging split-screen reflow as background), float-window start/stop 2s throttle, MiniFloatService onStartCommand startForeground safety net — independent float window still appears correctly when switching to other apps on Leco
- **Pendolo on Android 8.1 car stability** — spring integrator diverged at low frame rates (pos explosion → NaN → view stops drawing); switched to RK4 integrator with identical feel + NaN self-healing fallback
- **Per-char pulse / new-line drop animation** — pulse length from real sung duration (260~700ms), under-damped spring drop with 12% overshoot rebound
- **Effect picker scrolling** — options-only scroll with pinned title, 80% screen-height cap, fixes unreachable bottom options on car landscape
- **Removed Claddagh effect** — didn't fit listening scenarios, picker back to 7 items

### v1.0.8.2 (2026-10)
#### Improvements
- **Kugou bar spectrum left-right symmetry** — Second group data now mirrored, right half displays the same data sequence as left half, symmetric outline (bell weight peak lands on screen centerline), fixes the always-low right segment and unbalanced look
- **Kugou spectrum aurora dual-color follow** — Under aurora dual-color mode, both gradient groups follow cover dual-color interpolation (auto recolors on track change), static bottom blocks synced; auto hue spread when two colors too close (40° threshold) to preserve layering, falls back to original warm colors on exit
- **Dot spectrum size adaptive** — Dot size now min of width/height dimensions (diameter ≤ 22% of band height), fixes oversized dots on landscape/ultrawide screens, portrait look unchanged

### v1.0.8.1 (2026-10)
#### New Features
- **Player page control bar auto-hide** — Configurable 5~30s delay or fully off, settings page adds delay option, control bar fades out automatically when idle, tap screen to restore
- **Auto-start on boot toggle** — Settings page adds toggle, app auto-launches on boot broadcast, music starts when car head unit / TV box powers on

### v1.0.8.0 (2026-10)
#### New Features
- **Software audio effect processor** — Bypasses car head unit vendor EQ (e.g., NXP multi-channel switch silence issue), self-developed Media3 AudioProcessor software solution: 5-stage biquad cascade filter, 7 presets (Off/Vocal/Pop/Rock/Classical/Bass Boost/Surround), EQ curves based on Winamp genre curves at 1.5× boost (peak ±9dB), BassBoost/Virtualizer 80%, accessible via player page popup
- **Volume normalization (loudness equalization)** — ReplayGain/EBU R128-based volume normalization toggle, scans true loudness and automatically levels perceived volume across tracks

#### Bug Fixes
- **Fallback playback cross-thread crash** — On audio-abnormal devices using MediaPlayer fallback, callbacks in FallbackInit thread directly accessed exoPlayer triggering `IllegalStateException: Player is accessed on the wrong thread`; unified via handler.post() to main thread

#### Improvements
- **Redundant code cleanup** — Removed 4 unused fields in SpectrumView, 33 unused imports, 1 unused drawable, no functional impact

### v1.0.7.9 (2026-10)
#### Bug Fixes
- **External control failure on audio-abnormal devices** — MediaSession callbacks and notification bar unified through fallback-aware pausePlayback()/resumePlayback(), position reading uses fallback-aware getCurrentPosition()
- **Log cleanup** — Residual printStackTrace() in BlurUtil/KrcParser unified to android.util.Log.e

### v1.0.7.8 (2026-10)
#### Bug Fixes
- **Android 5.1 / 6 / 7 compatibility** — Full adaptation for low-end car systems: Android 6 four crash fixes (XmlPullParser obfuscation/getStorageVolumes/GradientDrawable.getColors/thread UI); 5.1 audio-abnormal ANR protection (ExoPlayer construct/release sub-threaded); 5.1 popup outside-tap fix; 5.1 low-memory cover downsample + spectrum double-buffer; all versions setForeground API 23 compat
- **USB removal residual songs** — Scan validates file existence before adding, songs cleanly disappear after USB removal
- **Cover cache unification** — Grid and player cover caches unified to "song name - artist.jpg" shared app-wide
- **Folder cover not updating** — Added folder music file fingerprint check, cache auto-invalidates on content change

### v1.0.7.7 (2026-09)
#### Bug Fixes
- **Update detection failure due to Gitee API rate limiting** — When Gitee API v5 returns 403 (WAF rate limited), added fallback to parse Gitee Releases page HTML for latest version, APK download link, and release notes, ensuring update detection works across all network conditions

### v1.0.7.6 (2026-09)
#### New Features
- **Dual-color aurora background skin** — New background skin option in settings: Default/Dual-color Aurora, self-drawn aurora effect with 128×128 LUT and precise elliptical depth function, 10×10 downsampling + non-uniform 8 hue-bucket color extraction; aurora brightened 20% in day, dimmed 15% at night; applies to Classic/Record/Carousel modes only (not Immersive)
- **Home dynamic background system** — three-layer self-drawn architecture (base color + bottom-left mask tint + top-right gradient), mask pixels from cover dominant color, 8-bucket HSV extraction + 10-tier hue lookup; home tab bar with two-line text + sliding indicator + light stream, icon colors linked to cover extraction, transparent list items in dynamic mode; Help/Settings/WebDAV/Bilibili pages integrated
- **Embedded lyrics reading** — Embedded lyrics now highest priority in load order (when toggle enabled), supporting MP3 USLT/SYLT, M4A ©lyr, FLAC LYRICS tags
- **Spectrum colors follow cover** — Bar/dot/wave/NetEase ring/diffusion ring spectrums follow cover dual colors in aurora mode (day: follow, night: white; non-aurora keeps original colors)
- **Lyrics highlight color follows cover** — In aurora + classic mode, lyrics highlight color follows cover dominant color
- **Immersive mode aurora adaptation** — Portrait: cover at 43% height + full-screen aurora overlay; Landscape: cover at bottom layer + gradient transition band + aurora overlay, background restored on exit

#### Improvements
- **Copyright text** — Changed to "A calm mind, clear sound · v{dynamic version}"
- **Aurora progress bar** — Progress bar/thumb follows button color (black in day, white at night), light gray track
- **Page interaction refactor** — Home help button changed to settings; settings page adds help button at top-right; help page removes settings button; Bilibili/WebDAV path bar settings button gets new cloud-wrench icon, margins 12→8dp
- **Help page details** — Text content rebuilt on day/night switch, bottom buttons single-line no-wrap

#### Bug Fixes
- **Amap day/night follow chain** — Receiver moved from Service to Application level; Amap day/night switching now works on all pages even when not playing music (Service not running); Settings/Bilibili/WebDAV/Help pages follow via broadcast in floating state, fixed silent registration failure
- **Cold start dominant color persistence** — Dominant color persisted, cover gradient appears instantly on cold start; aurora extraction syncs static color, home gradient consistent when returning
- **Aurora color linkage fixes** — Song title theme color and progress bar colors sync when switching back to aurora; spectrum restores original colors after exiting aurora; fast song-switch extraction race; landscape rotation no longer leaves old background color
- **FLAC artist unknown fallback** — Proper fallback info when metadata missing

### v1.0.7.5 (2026-09)
#### Fix
- **Android 8.1 player crash** — v1.0.7.4 progress bar scaling used setMinHeight/setMaxHeight, which does not exist on some Android 8.1 ROMs causing a crash; replaced with track drawable height, compatible with all versions

### v1.0.7.4 (2026-09)
#### Improvements
- **Player progress bar proportional scaling** — Thumb 2%, time text 2%, track 0.5% of screen height, ultrawide aspect compensation, covers all modes in both orientations
- **Landscape player top buttons padding** — Top-right three buttons (spectrum/outfit/lyric search) padding reduced to 5% screen height (was 22%); portrait unchanged
- **Icon updates** — Lyric search, outfit, and help buttons replaced with new drawn styles
- **Landscape home top icon lower bound** — Icon size floor reduced from 36dp to 20dp so the proportional value (72% of bar height) takes effect on common car screens

### v1.0.7.3 (2026-09)
#### Improvements
- **Portrait non-fullscreen lyrics font enlarged** — Current line 6% screen width (was 4.8%), other lines 4.5% (was 3.6%), matching fullscreen current line size; landscape ratios unchanged

### v1.0.7.2 (2026-09)
#### Improvements
- **Player back button enlarged** — Top-left back button scaled up 30% in both orientations, padding unchanged
- **Carousel orientation switch margin fix** — Cover position uses pure-ratio top bar height calculation, no longer depends on getHeight()
- **Leco floating lyrics direction consistency** — Lyrics direction detection unified with song title data source

### v1.0.7.1 (2026-09)
#### Improvements
- Portrait fullscreen lyrics font tiering — current line 6%, other lines 5%
- Landscape fullscreen lyrics font unified with non-fullscreen
- Player page dialogs scale with resolution

#### Bug Fixes
- Portrait lyrics font size mismatch with song title
- Classic fullscreen → immersive mode lyrics residual
- Classic fullscreen → record mode tonearm residual

### v1.0.6.8 (2026-09)
#### New Features
- **Playback progress memory** — Resume from last played position after app restart, no manual seeking needed
#### Improvements
- **Debug log cleanup** — Removed 157 Log.d debug outputs, kept Log.w/Log.e, reducing unnecessary logging overhead

### v1.0.6.7 (2026-08)
#### Improvements
- **Float permission dialog adds "Never ask" button** — Some car head units cannot configure overlay permission; "Never ask" stops repeated prompts

### v1.0.6.6 (2026-08)
#### Improvements
- **Music scan cache optimization** — Removed cache time expiry, cache valid until manual scan (auto-invalidates on USB change/plug), instant startup

### v1.0.6.5 (2026-08)
#### Bug Fixes
- **Spectrum stops in split-screen** — Added guard in startSpectrumWithPermission to prevent broadcast loop; onPause/onStop no longer stop spectrum, fixing Visualizer destruction by car system's onResume↔onStop cycle
- **CrashHandler crash recovery** — BadTokenException changed from "swallow and continue" to "restart process", fixing black screen after car Activity relaunch

### v1.0.6.4 (2026-08)
- Leco Desktop floating display with drag-to-resize
- Single Activity + Fragment architecture
- Player page lyrics search (multi-source + duration matching)
- Unified settings page with color picker & spectrum toggle
- Local cover/lyrics priority and launch-to-player toggles
- Red daytime lyrics highlight (#E53935)
- Double-tap to close float window

### Bug Fixes
- Android 14/15/16 compatibility, USB scan, spectrum & capsule fixes
- WebDAV directory loss, favorites count, cover loading improvements
- Android 15 cover overlap, default cover headphone icon

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
- **Online Update** — GitHub Release auto-check, 4-mirror speed test download, triple validation, silent check on launch + manual check
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

**Some spectrum styles** (Columnar / Kugou / AI Voice / Wave Column / Diffusion Ring / Wave Ring,
plus the band normalization algorithm) are ported from the open-source project
[SpectrumForAndroid](https://github.com/michael007js/SpectrumForAndroid) (by michael007js,
which carries no explicit license notice; ported for technical learning purposes with
attribution and thanks). Bar / Dot / Wave / NetEase Ring styles are original implementations.

The **Lattice playback style** and the **lyric effect suite** are ported from
the open-source project [FOLIA](https://github.com/chthollyphile/folia-major) (AGPL-3.0):
- Lattice style: `view/lattice/`, `stage/LatticePageV2`, from FOLIA's Lattice cover wall
- Lyric effects: `view/GlowLyricView`, `PendoloLyricView`, `PendoloClockworkView`,
  `CadenzaLyricView`, `TiltLyricView`, `PartitaLyricView`, `FumeLyricView`,
  `DioramaLyricView` and their `lyriceffect/` pages, from folia's classic / pendolo /
  cadenza / tilt / partita / fume / diorama effects respectively

The upstream project is AGPL-3.0, consistent with this project's overall license
(see License section below).

---

## APK Size

| Build Type | Size |
|------------|------|
| Debug | ~5.0 MB |
| Release (R8 obfuscated) | ~2.0 MB |

---

## Changelog

### v1.0.7.6 (2026-09)
#### New Features
- **Dual-color aurora background skin** — Settings page adds background skin option (Default/Dual-color Aurora), self-drawn aurora effect, 128×128 LUT, 10×10 downsampling + 8 hue-bucket extraction, 20% brighter in day, 15% dimmed at night; Classic/Record/Carousel modes
- **Home dynamic background system** — three-layer self-drawn background (base + bottom-left mask tint + top-right gradient), mask pixels from cover dominant color; tab bar two-line text + sliding indicator + light stream, icon colors linked to cover; Help/Settings/WebDAV/Bilibili pages integrated
- **Embedded lyrics reading** — Embedded lyrics highest priority in load order (when enabled), supporting MP3 USLT/SYLT, M4A ©lyr, FLAC LYRICS
- **Spectrum colors follow cover** — Bar/dot/wave/NetEase ring/diffusion ring follow cover dual colors in aurora mode (day: follow, night: white)
- **Lyrics highlight color follows cover** — Auroras + classic mode lyrics highlight follows cover dominant color
- **Immersive mode aurora adaptation** — Portrait cover 43% height + full-screen aurora; landscape cover bottom layer + gradient band + aurora overlay

#### Improvements
- Copyright text changed to "A calm mind, clear sound · v{version}"
- Aurora progress bar/thumb follows button color (black day, white night), light gray track
- Home help→settings button, settings page adds help button, help page removes settings button; Bilibili/WebDAV path bar new cloud-wrench icon, margins 12→8dp
- Help page text rebuilt on day/night switch, bottom buttons single-line no-wrap

#### Bug Fixes
- Amap day/night receiver moved to Application level, chain intact when not playing; Settings/Bilibili/WebDAV/Help follow via broadcast in floating state, fixed silent registration failure
- Home dominant color persistence, cover gradient on cold start, consistent gradient when returning
- Song title/progress bar color sync when switching back to aurora, spectrum color restore after exit, fast song-switch race, landscape rotation old background residual
- FLAC artist unknown fallback

### v1.0.7.3 (2026-09)
#### Improvements
- Portrait non-fullscreen lyrics font enlarged — current line 6% screen width (was 4.8%), other lines 4.5% (was 3.6%); landscape unchanged

### v1.0.7.2 (2026-09)
#### Improvements
- Player back button enlarged — scaled up 30% in both orientations, padding unchanged
- Carousel orientation switch margin fix — pure-ratio top bar height calculation
- Leco floating lyrics direction consistency

### v1.0.7.1 (2026-09)
#### Improvements
- Portrait fullscreen lyrics font tiering — current line 6% screen width, other lines 5%, clearer visual hierarchy
- Landscape fullscreen lyrics font unified with non-fullscreen — current line 4.8%, other lines 3.6%
- Player page dialogs (spectrum/lyrics search/history) scale with resolution, history converted to custom floating window

#### Bug Fixes
- Portrait lyrics font size mismatch with song title — base size adjusted to match by adding horizontal margin, orientation detection switched to Configuration
- Classic fullscreen → immersive mode lyrics residual — immersive mode doesn't support fullscreen, auto-downgrades to multi-line
- Classic fullscreen → record mode tonearm residual — auto-hide on enter, restore on exit

### v1.0.7.0 (2026-09)
#### Improvements
- Full-page pure proportional scaling — all home page regions scale by screen height ratio in both orientations (top bar/tab bar/path bar 8%, mini player bar 10%, search bar 8%, copyright 1.6%), search bar scales parent container; player page song title/lyric fonts now pure proportional without fixed pixel limits
- Player buttons scale by screen-height tiers with ultrawide correction — no more undersized buttons on low-density high-resolution car head units
- Grid song titles scale by screen height (portrait 2%, landscape 2.5%), sp minimum removed, single-line display, auto-recalculated on orientation change
- Landscape player top buttons +0.5% screen height each (bottom buttons unchanged)

#### Bug Fixes
- Occasional "format unsupported" false positive — transient errors (network jitter/decoder occupied) now auto-retry once; Toast shows real cause (network/format) instead of blanket "unsupported audio format"
- Track selection race condition — 500ms delayed re-check before declaring format unsupported, preventing false MediaPlayer fallback on song switch
- Silent stop after consecutive failures — playback page now shows Toast explaining why auto-skip stopped (broadcast was previously never received)
- WebDAV m4a playback failure — devices without ALAC decoder no longer route to MediaPlayer fallback (401 auth failure), ExoPlayer plays directly

### v1.0.6.9 (2026-09)
#### Improvements
- Cover public directory optimization — album art no longer copied to public Download/jingxinmusic/cover/, stored only in app private cache; MediaSession metadata drops URI fields keeping Bitmap only, reducing public directory IO and simplifying cover delivery chain

### v1.0.6.8 (2026-09)
#### New Features
- Playback progress memory — resume from last played position after app restart

#### Improvements
- Debug log cleanup — removed 157 Log.d outputs, kept Log.w/Log.e

### v1.0.6.7 (2026-08)
#### Improvements
- Float permission dialog adds "Never ask" button — some car head units cannot configure overlay permission, prevents repeated prompts

### v1.0.6.6 (2026-08)
#### Improvements
- Music scan cache optimization — removed cache time expiry, cache valid until manual scan (auto-invalidates on USB change/plug), instant startup

### v1.0.6.5 (2026-08)
#### Bug Fixes
- Spectrum stops in split-screen — startSpectrumWithPermission guard prevents broadcast loop; onPause/onStop no longer stop spectrum, fixing Visualizer destruction by car system's onResume↔onStop cycle
- CrashHandler crash recovery — BadTokenException changed from "swallow and continue" to "restart process", fixing black screen after car Activity relaunch

### v1.0.6.4 (2026-08)
#### New Features
- Local crash logging (CrashHandler, system framework exceptions logged without exit)
- Day/night theme auto-switch by time (customizable day/night boundary in settings)

#### Bug Fixes
- Spectrum not showing on first play (sessionId=0 retry mechanism)
- Settings time selector text color not following day/night theme
- Android 10 car head unit MediaStore ContentObserver crash
- Android 10+ false storage write permission dialog

### v1.0.6.3 (2026-08)
#### Bug Fixes
- First entry to Leco floating area layout error — active broadcast refresh + fixed updateFloatWindowSize LayoutParams
- Standalone float window not closing after tap-to-fullscreen — repeated closemap broadcast caused Service restart
- Carousel mode landscape→portrait lyrics font size jump to fullscreen
- Record mode tonearm position not following floating width change
- Spectrum preemptive arbitration + standalone float START_NOT_STICKY + lecoFloatExit race fix

### v1.0.6.2 (2026-08)
- LRC encoding auto-detection (UTF-8/GBK), fixes garbled lyrics
- Landscape path bar ratio adjustment
- Dual float window fix

### v1.0.6.1 (2026-08)
- Dual-source update (Gitee priority + GitHub fallback), improved domestic update success rate
- FileProvider fix for Android 7+ APK install crash
- Startup update check fix

### v1.0.6 (2026-08)
#### New Features
- Leco Desktop floating area full player page display, supports drag-to-resize
- Single Activity + Fragment architecture refactor, Fragment transaction page switching, more stable floating
- Player page lyrics search button, multi-source search (Kugou 20 + Netease 10), duration-matched sorting + best match
- Unified settings page, new lyrics highlight color picker, spectrum toggle
- New local cover/lyrics priority toggle, launch-to-player toggle
- Daytime lyrics highlight color changed to red #E53935
- Removed float window close button, double-tap to close only

#### Bug Fixes
- Android 14/15/16 multi-version compatibility crashes
- WebDAV floating state directory browse data loss
- Floating mode column count, spectrum stop, capsule play button failure
- Android 11+ USB music scan failure, added MANAGE_EXTERNAL_STORAGE authorization flow
- Amap car day/night change causing song restart
- List page duplicate load on return from player
- Favorites count display and search functionality
- Cover loading logic improvement (embedded cover extraction)
- Bilibili settings back button day/night tinting
- Default cover headphone icon instead of solid block
- Android 15 cover overlapping top buttons (classic + carousel portrait)

#### Improvements
- Help page update (10 settings descriptions, removed obsolete sections)
- Redundant code cleanup, unified floating landscape/portrait detection

### v1.0.5 (2026-08)
- Added capsule mode (Dynamic Island style) and Karaoke mode (dual-line lyrics + cover + spectrum) float window
- Karaoke float lyrics auto-shrink font size when text exceeds width
- New help page (10 spectrum style details, contact dialog, check update button)
- Help icon monochrome hollow info-bubble style, day/night adaptation
- Music scan JSON cache (10-min TTL, instant startup)
- Float window zoom limits adjusted (classic 60%→80%, capsule 40%→60%)
- Float panel fixes (capsule width slider clipping, panel text color fixed to light)

### v1.0.4.1 (2026-08)
- Lyric file naming changed to raw title (sanitizeFileName), search logic unchanged
- Backward compatible with legacy filename formats

### v1.0.4 (2026-08)
- Fixed Leco desktop cover not available on first launch (Service loads covers independently, no longer depends on PlayerActivity foreground)
- Unified artist text color to match song title (TextPrimary)

### v1.0.3 (2026-08)
- Float window opacity slider (tap cover to open panel, 0-255 real-time control, auto-persist)

### v1.0.2 (2026-08)
- Online update feature (GitHub Release check + mirror speed test + triple validation + install)
- Cover public directory changed from Pictures/静心音乐/ to Download/jingxinmusic/cover/
- Fixed saveCoverToPublic IS_PENDING not cleared bug (covers invisible to other apps)
- Landscape path bar ratio adjusted from 6% to 8%

### v1.0.1 (2026-07)
- Status bar inset adaptation (MainActivity/PlayerActivity)
- Bilibili async race condition fix (biliFetching flag)
- Cover cache deduplication (CoverLoader checks cache before writing)
- Lyrics broadcast fix (setPackage for Android 16 broadcast)

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

> https://pd.qq.com/s/a8mi9wmfs?b=2

## Author

Jingxin — Built with TeleClaw

- Website: https://lecoauto.com
- Group: 651547480

> AI生成