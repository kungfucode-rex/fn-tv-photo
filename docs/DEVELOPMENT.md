# 开发文档

这份文档写给**改这个项目的人**，而不是用这个应用的人 —— 后者看 [`../README.md`](../README.md)。

- [项目结构](#项目结构)
- [工具链与构建](#工具链与构建)
- [在模拟器 / 电视上跑](#在模拟器--电视上跑)
- [测试与 mock 服务器](#测试与-mock-服务器)
- [发布](#发布)
- [几条会咬人的约定](#几条会咬人的约定)
- [验证方式](#验证方式)
- [已知限制](#已知限制)

---

## 项目结构

```
app/src/main/java/com/tvphoto/
  data/
    fn/               飞牛协议：加密、签名、登录、HTTP、各端点
    AppContainer.kt   手写 DI；API 与媒体共用同一个 token 来源
    BaseUrl.kt        地址归一化（含全角输入法输入）
    CrashLog.kt       为设置页保留最近一次未捕获异常
    UpdateChecker.kt  最新 release 是什么；唯一不走 NAS 的客户端
    ApkInstaller.kt   下载 APK、校验 SHA-256、交给系统安装器
    OkHttpDataSource.kt  视频走应用自己的 OkHttp 客户端（token、
                          访问码 cookie、固定证书信任）
    PreviewOriginal.kt   预览用原图还是大缩略图，以及某个安装选了哪个
    SessionRepository.kt  登录生命周期、保活、过期后重新登录
    SessionRecovery.kt    过期之后重试的规则（纯逻辑，可单测）
    PhotoRepository.kt    界面唯一的数据入口
    ThemeMode.kt      深色或浅色，以及某次安装被设成哪个
    SlideshowMusic.kt     应用自带的曲目，以及一组选中曲目如何
                          存储与排序（含 `file:` 前缀与归一化）
    SlideshowMusicPlayer.kt  幻灯片底下循环播放勾选曲目的 ExoPlayer
                           （内置读 asset，自定义读本地文件）
    music/            自定义音乐：曲库、扫码上传、目录扫描
      MusicEntry.kt         条目模型、标题回退、排序
      MusicLibrary.kt       曲库目录、扫描、入库/删除/清空、容量、探测缓存
      MusicOrder.kt         顺序偏好的编解码
      MusicUploadServer.kt  电视上那个内嵌 HTTP 服务（只在面板打开时监听）
      MusicUploadProtocol.kt 纯逻辑：请求解析、token、路由、体读取、文件名清洗
      MusicQr.kt            zxing → BitMatrix（纯 JVM）
    FolderLayout.kt   文件夹的两种形状（网格 / 列表），以及选了哪个
    review/           图评：电视这一端
      PhotoIdentity.kt    一张照片在本地的身份（host + 相册 + id）
      ReviewClient.kt     协议客户端（自己的 OkHttp，不固定 NAS 证书）
      ReviewRepository.kt 注册、评论、socket，以及一份内存缓存
      ReviewSettings.kt   图评设置、地址清洗、评分等级表
      ReviewModels.kt     协议模型，以及"拒绝令牌"/"没有这张照片"两个判断
      DeviceRepair.kt     设备被拒时的修复：换新设备，并丢掉旧服务器的回答
      CommentMerge.kt     按 id 覆盖而不是追加（服务端推的是状态）
      DanmakuFeed.kt      弹幕的轨道、速度与重播规则
  domain/Models.kt    面向界面的模型
  domain/AppVersion.kt   版本号怎么比：tag 的前导 v、缺的段、预发布后缀
  domain/PagingQueue.kt  翻页指针：按一下之后队列停在哪里
  domain/SlideshowOrder.kt  幻灯片下一张走哪张：只走照片，跳过视频
  domain/NextPage.kt  什么时候该要下一页（网格的滚动线，和放映时的无条件）
  ui/
    login/ home/ gallery/ viewer/   各屏幕
    music/                 自定义音乐：上传页（二维码 + 带序号的列表）、
                           查看器列表的行模型，以及长按后的模式机（纯逻辑）
    components/Spinner.kt   画出来的圆弧，启动与查看器共用
    components/SettingsRows.kt  InfoRow / ActionRow（设置页与音乐页共用）
    viewer/CornerMenu.kt    角上那一行展开了哪一个（幻灯片设置 / 图评开关）
    viewer/DanmakuOverlay.kt  把评论画成飘过去的弹幕
    home/SettingsSection.kt  设置页：左分类 + 右分页
    MainViewModel.kt  导航栈 + 各分区状态
    UpdateState.kt    检测升级那一行的状态机
app/src/main/assets/music/   幻灯片可播放的那一首内置曲目
server/                  图评服务端（Go，独立 go.mod；Gradle 完全不碰它）
  cmd/tupingd/           入口：环境变量 → 配置 → 监听
  internal/api/          HTTP 端点、鉴权、限流、WebSocket
  internal/guard/        长度、广告模板、分级词表
  internal/phash/        规范缩略图 → pHash‖dHash、剥黑边、方差
  internal/qr/           短码 → 二维码 PNG（含缓存）
  internal/store/        SQLite、身份匹配、可逆合并
  internal/web/          H5 评论页 + 管理员登录页（go:embed 进同一个二进制）
  Dockerfile             只 COPY 预编译二进制，FROM alpine
  docker-compose.yml     部署者只需要改 TUPING_ADMIN_TOKEN 一个值
  docker-compose.image.yml  拉发布镜像的那一份（推荐）
  README.md              服务端自己的部署文档
tools/
  build.ps1           带本地工具链的 Gradle wrapper；并发布 APK；写 version.json
  publish-apk.ps1     把构建好的 APK 复制到 \\fnos-ms01\Temp\软件 并校验
  publish-test.ps1    升号 + 打 debug 包 + 发布，一条命令发一个测试版
  release-manifest.ps1  写 version.json（发布时附到同一个 release 上）
  publish-gitee.ps1   建 Gitee 发行版、传附件并提交到仓库
  setup-toolchain.ps1   自包含 JDK 17 + Android SDK + Gradle
  setup-emulator.ps1 / create-avd.ps1   Android TV 系统镜像与 AVD
  setup-go.ps1        把 Go 装到 F:\go-toolchain（和 Android 那套并列，可一步删掉）
  tuping.ps1          图评服务端：build / test / dist（交叉编译两个架构）/ run / deploy
  tv.ps1              安装 / 启动 / 截图 / 注入按键
  mock-nas/           一个强制实施真实认证规则的飞牛模拟服务器，另有
                      若干开关用于制造与时间和变更相关的行为
                      （慢速媒体、增删照片、固定某种签名规则）
    fetch-photos.js   拉取它要发的测试照片（真照片，不是渐变色块）
    assets/photos/    这些照片本身，按每种缩略图尺寸各存一份，附 CREDITS.md
  mock-review/        图评的模拟服务器 + 独立参考客户端（verify.js），
                      同一个脚本既能对着 mock 跑，也能对着真服务端跑
docs/fnos-photo-api.md   逆向出来的协议，附证据
docs/tuping-design.md    图评的协议与决策，唯一真相源
docs/custom-music-design.md  自定义背景音乐：曲库、扫码上传与目录扫描
docs/verification.md     每一项功能是怎么验证的、踩过哪些坑
docs/DEVELOPMENT.md      这份文档
AGENTS.md                给 agent / 新同事的项目速览
```

---

## 工具链与构建

`tools/setup-toolchain.ps1` 会在 `F:\android-toolchain` 下准备一套自包含的 JDK 17 + Android SDK + Gradle，
所以不会有东西装到系统级，整套东西可以一步删掉。`tools/setup-emulator.ps1` 和 `tools/create-avd.ps1`
会补上 Android TV 系统镜像和一个 AVD。

有一条环境笔记值得记下：所有 Gradle 发行版 URL 都会重定向到 `github.com`，而它在这个网络里不可达。
工具链脚本改用腾讯镜像。

构建走 `tools/build.ps1`（它自己设置 `JAVA_HOME` / `ANDROID_HOME` / `PATH`，然后调 Gradle）：

```powershell
& .\tools\build.ps1 -Tasks testDebugUnitTest          # 只编译 + 跑单测
& .\tools\build.ps1 -Tasks assembleDebug              # debug APK（模拟器用）
& .\tools\build.ps1 -Tasks assembleRelease            # release APK（会签名、发布、写 version.json）
& .\tools\build.ps1 -Tasks testDebugUnitTest,assembleDebug   # 一次跑完
```

一次构建 APK 的运行**同时会把它上传到电视安装时所用的共享目录**（`tools/publish-apk.ps1`），并把副本读回来
比对哈希。构建与发布有意合成一步：这样 NAS 上的文件不会落后于源码，也没人需要记得去复制。默认只发布
**release** —— debug APK 是给模拟器用的，而一个大家用来安装的目录里多放一个文件，只会让人装错。
`-NoPublish` 只构建，`-PublishVariants @('release','debug')` 两个都发，
`-PublishDestination <path>` 发到别处。共享目录休眠或未认证不会让构建失败 —— 它会告警，而 APK 仍在
`app/build/outputs/apk/`。

release APK 的文件名带的是**应用名 + 版本号**，而不是 Gradle 变体名 —— `FN-tvphoto-1.4.11.apk`。版本号在
`app/build.gradle.kts` 里只写一次（`appVersionName`），manifest、设置页和文件名都跟着它走，改版本号时文件名
一起变。共享目录上的名字再带上 APK 自己的**构建时间**（`FN-tvphoto-1.4.11-<yyyyMMdd-HHmmss>.apk`），这样旧
构建还在、可以回退，也不会有东西被悄悄覆盖。

每次 release 构建还会在 APK 旁边写一份 **`version.json`**（`tools/release-manifest.ps1`，加 `-Site`/`-Repo`
可以为 Gitee 再生成一份）—— 设置里那一行 检测升级 读的就是它：版本号、版本代码、APK 地址和 SHA-256。
**没有这份清单的 release 不算出错**：应用退回到只读 tag，报告有新版本并给出手动路线，但不会去下载一个它
无法校验的文件。

release 构建用 `app/tvphoto.jks` 签名。该密钥库**不在**版本控制中；请先生成一个（或者让 debug 构建用自己的密钥）：

```powershell
keytool -genkeypair -v -keystore app/tvphoto.jks -alias tvphoto `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass tvphoto -keypass tvphoto `
  -dname "CN=TV Photo, OU=AndroidTV, O=TV Photo, L=Local, ST=Local, C=CN"
```

debug 构建的 application id 是 `com.kungfucode.fntvphoto.debug`（release 是 `com.kungfucode.fntvphoto`；
两者的代码命名空间都是 `com.tvphoto`，这也是 `tools/tv.ps1` 把 activity 写成 `com.tvphoto.MainActivity` 的原因）。

### 装到电视 / 模拟器上

```bash
adb install -r app/build/outputs/apk/release/FN-tvphoto-1.4.11.apk
```

`tools/tv.ps1 install` **不打包**：它装的是磁盘上那份 `app/build/outputs/apk/debug/app-debug.apk`。
所以改完代码要先 `tools/build.ps1 -Tasks assembleDebug`（或 `-Tasks testDebugUnitTest,assembleDebug` 一次跑完）
—— `testDebugUnitTest` 只编译、不重新打包，只跑它就直接安装的话，装上的是上一次的 APK。`install` 也不检查
这一点（要加的话最容易的位置就是这个脚本）。

---

## 测试与 mock 服务器

这里的验证是刻意搭起来的，因为没有办法拿封闭协议对着真机做单元测试 —— 详见下面的**验证方式**。

```powershell
& .\tools\build.ps1 -Tasks testDebugUnitTest          # app 的 JVM 单测
& .\tools\tuping.ps1 -Tasks test                     # 服务端的 Go 单测
```

`tools/mock-nas` 是一个真正强制实施规则的飞牛模拟服务器（签名、`reqid` 的新鲜度、TLS、访问码），
`tools/mock-review` 是图评的模拟服务器加一个独立参考客户端。两个都在各自的 `README.md` 里说明，
怎么跑见下面的**验证方式**。

---

## 发布

**发布测试版本 = 升级版本 + 打包 + 放到老地方，一步不能少。** 一条命令做完：

```powershell
& .\tools\publish-test.ps1
```

它把 `appVersionName` 的末位和 `versionCode` 各加一、调 `tools/build.ps1 -Tasks assembleDebug -PublishVariants debug`、
再调 `tools/publish-apk.ps1`，最后**从共享目录里把版本号读回来核对**（读出来的不是新版本就报错退出）。第一步
不是记账：电视的安装器不接受同号覆盖，没升号的 debug 包就是谁也装不上的包。`-DryRun` 只说不做，
`-GradleFile <副本> -BumpOnly` 用来单独验证升号这一步。

`app/build.gradle.kts` 里那份版本日记是散文，仍然手写：脚本只把该补的那一行打出来
（`// 24 is 1.4.10, which ...`），描述由写的人填。

正式 release 是手工发的：`assembleRelease` 产出 APK 和 `version.json`，把 APK 和 `version.json` 一起附到
GitHub / Gitee 的 release 上。Gitee 那份可以用 `tools/publish-gitee.ps1`。

> **服务端的 release 不能变成 GitHub 的「Latest」。** 应用读升级清单的地址是
> `releases/latest/download/version.json`，它默认"最新的 release 是一个带清单的相册版"。而 GitHub 把**最后
> 发布**的那个 release 标成 Latest，所以 `server-v0.1.2` 在 `v1.4.11` 之后八分钟发出去时，那个地址就指向了
> 一个没有清单的 release，开始回 404。server 那个 workflow 现在带 `make_latest: false` —— 两个产品的发布流
> 靠的就是这一行分开。

---

## 几条会咬人的约定

**两个 PowerShell 构建脚本刻意保持纯 ASCII。** `tools/build.ps1` 与 `tools/publish-apk.ps1` 里没有任何消息
使用 ASCII 之外的字符：Windows PowerShell 会把 `.ps1` 按 ANSI 解码，除非文件带 UTF-8 BOM，于是脚本里字面量
`软件` 会变成乱码，每次复制都以 "share is not reachable" 失败。共享目录名是用
`[char]0x8F6F + [char]0x4EF6` 拼出来的。

**`AppContainer` 是手写的 DI。** 应用小到 DI 框架得不偿失，唯一的硬性要求是**媒体客户端和会话共用同一个 token
来源**，所以它们一起在那里构建。图评故意用**另一个** OkHttp 客户端：NAS 那个在首次使用时固定自签名证书，
而局域网里的图评服务器用的是普通证书或者根本没有，共用会让图评服务器因为一个与"是不是对的服务器"无关的理由
被拒绝。

**数据层不带 `Context`。** 失败消息在 `data/` 里是刻意与语言无关的技术描述（`FnException` 的注释说明了原因），
界面用 `R.string.error_prefix` 加上本地化前缀，把技术细节作为诊断信息显示。

**一个设置一个值。** 幻灯片的间隔与顺序现在有两个入口（查看器的幻灯片控制条、设置页的幻灯片设置分页），
两边写的是同一份偏好，通过同一个 `MainViewModel` setter，读的是同一个 `StateFlow`。同一份东西两个入口是可以
的（两个场景都自然），两个入口各存一份值是不可以的。

---

## 验证方式

在这里没有办法拿封闭协议对着真机做单元测试，所以验证是刻意搭起来的，而且**每一项都留下了读数**。
完整的记录 —— 每一条功能是怎么验的、每一步读数是什么、以及过程中抓出来的 bug —— 在
[`verification.md`](verification.md)。改完一个功能之后，值得在那里补一条同样格式的记录。

几条约定：

- **设备上才看得出来的东西**（布局、焦点、有没有真的发请求、音频有没有在放）在 Android TV 模拟器上
  对着 `tools/mock-nas` 走一遍，那个 mock 会真正校验 `authx` 签名与 `reqid` 新鲜度，并带一组
  `__control/` 开关（慢速媒体、增删照片、固定签名规则）。
- **协议与策略层面的规则**（什么时候该重登、版本号怎么比、弹幕占几行）放到 `testDebugUnitTest` 里，
  为此把纯逻辑抽成 `domain/` 或 `data/SessionRecovery.kt` 这样的小对象。
- **图评**另外有服务端自己的 Go 单测（`& .\tools\tuping.ps1 -Tasks test`）和一个独立写出来的参考客户端
  `tools/mock-review/verify.js`。

---

## 已知限制

- **协议是私有且无版本的。** 签名用的 salt/secret 是从飞牛前端里抠出来的全局常量，而 v1 与 v2 端点已经并存。
  服务器端的一次改动就可能让这个应用失效。风险评估与 WebDAV 替代方案见 [fnos-photo-api.md](fnos-photo-api.md)。
- **密码以明文存在 `SharedPreferences` 里。** 这是没有任何 refresh-token 流程所迫：要从过期的 `AccessToken`
  恢复，就必须重放整次登录。对一个局域网设备应用来说这是有意的取舍，但值得知道。
- **只读。** 浏览、查看和幻灯片已实现；删除、上传、重命名和收藏没有。（**这不是指音乐**：自定义背景音乐
  可以从手机传进来，也可以引用 U 盘上的文件，见 [custom-music-design.md](custom-music-design.md)。）
- **上传的曲子"真的放出声"还没在设备上听过。** 扫码上传、入库、勾选、删除、排序、恢复内置曲目都在
  模拟器上走过（[verification.md](verification.md) 第 25 条），但声音本身没有量过 —— 目前只有"播放器会给
  `file:` 条目 `Uri.fromFile`"这一点保证，真机上应当按第 8 条那套 `dumpsys audio` 的读法再读一次。
  1.5.3 之前还有一条没验的路（扫描本机 / U 盘），它已经被删掉了，见 [custom-music-design.md](custom-music-design.md) §5。
- **查看器没有双指缩放或平移。**
- **长列表深处的删除会被较晚察觉。** 每 30 秒的重读只读一页，所以它只能为自己那个窗口担保：删除的照片若在该
  窗口之下，就要等到某一轮列表缩小到窗口能够到它时，它才会退出轮播。
- **应用内升级的最后一跳只有 mock 的证据。** 每个 release 上都附了 `version.json`，所以"检查、比对、报出新版本"
  这一半是真对着 GitHub 与 Gitee 走的；但"从真实 release 下载、校验、调起安装器"目前只有对着本地 mock 的记录。
- **在应用内安装需要一次系统授权。** Android 8 及以上要先在系统设置里允许本应用安装应用，之后每一次安装仍要
  用户在系统安装器上自己确认 —— 这一步是故意的，应用不会静默安装。有些电视固件根本没有可用的安装器，那时只剩
  手动路线。
- **只对着 mock 服务器与作者的 NAS 测过**，其他真实设备仍可能在细节上有差异。
- `POST /p/api/v1/photo/collect` 收藏端点和 AI 功能（人物、地点、搜索）在 API 里存在，但界面上没有暴露。
