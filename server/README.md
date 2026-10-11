# 图评服务端（tupingd）

电视上预览一张照片时，角落出现一个二维码；客人用微信扫它，在手机上看这张图的缩略图、写一句评论；提交成功后，电视上评论以弹幕形式飘过。

这个目录是那颗服务端：一个静态二进制，没有运行时，自己带着 H5 评论页。协议、参数与决策的唯一真相源是 [`docs/tuping-design.md`](../docs/tuping-design.md)。

---

## 部署

### 一行部署（推荐）

镜像发布在 Docker Hub 上，而且是**多架构**的：amd64 还是 arm64 由镜像自己的清单决定，你不用选、不用
下载二进制、也不用在同一个目录里凑齐几个文件。整件事就是把一份 compose 粘进去、改一行、点启动。

飞牛 **Docker 应用 → Compose → 新增项目**，项目名 `tuping`，把下面这一段（完整带注释的那份在
[`docker-compose.image.yml`](docker-compose.image.yml)）粘进去：

```yaml
services:
  tuping:
    image: kungfucode/tuping:0.1.0     # 换成发布镜像的命名空间
    container_name: tuping
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - ./data:/data
    environment:
      TUPING_ADMIN_TOKEN: "change-me"  # ← 唯一要改的一行
```

| 变量 | 填什么 |
| --- | --- |
| `TUPING_ADMIN_TOKEN` | 管理员的密码（见下面的**管理员**）。**留空 = 没有管理员**：评论页不出现 管理员登录，`/admin` 回 404 |

另外两个都不用手填：`TUPING_SECRET` 由服务器第一次启动自己生成（见**签名密钥**），`TUPING_BASE_URL`
默认取"谁在连"的那个地址（见**图评地址从哪来**）。

点**启动**即可 —— 这一条路**没有「构建」这一步**，它只是把镜像拉下来。

起来之后：

```sh
curl http://192.168.31.14:8080/healthz
# {"baseUrl":"","baseUrlSource":"the address each caller used","ok":true,"version":"0.1.0","uptimeSec":3,"thumbBytes":0}
```

`baseUrl` 是空的，因为没钉地址——`baseUrlSource` 就是说明这件事的字段。钉过的话它会是
`"TUPING_BASE_URL"`，`baseUrl` 是那个值。

**电视端的「图评服务器」填这台 NAS 的地址**（`http://192.168.31.14:8080`）。二维码里的地址是电视注册照片时一起报上来的那个，所以两边不会不一致。

**拉不动镜像的话**，那是 NAS 的镜像源问题，不是这份 compose 的：飞牛默认的 `docker.fnnas.com` 会回
`401 Unauthorized`，直连 Docker Hub 在国内又常被重置（`connection reset by peer`）。在 Docker 应用的
**镜像 → 仓库设置** 里换成能用的加速源（实测可用：`https://docker.1panel.live`、
`https://docker.xuanyuan.me`），然后**在面板右上角把 Docker 服务停止再启动**（改镜像源必须重启
daemon），再去启动。镜像是发布在 Docker Hub 上的，所以这些加速源正好都能转。

### 数据目录的属主：不用你做

这一条曾经是部署里唯一需要 SSH 的一步，现在不需要了。原理值得知道，因为它解释了你会看到的两行日志（§17.21）：

- 镜像里的进程是 uid **10001**（`adduser -u 10001 tuping`），而 bind mount 的宿主目录是 **Docker 以 root 建的** —— 权限检查比的是数字 uid，两者对不上，SQLite 就建不出 `tuping.db`（症状：容器起来几秒退出、反复重启，日志一行 `unable to open database file`）；
- 所以镜像**以 root 启动**，二进制在打开数据库之前把数据目录（含老文件）的属主交给 10001，然后立刻放弃 root：日志里先是一行 `running unprivileged uid=10001`，之后对外服务的进程就是 10001，内核层面也是如此。

唯一需要你动手的例外：**挂载的是 SMB/CIFS 这类不支持 `chown` 的文件系统**。那时日志里会出现 `could not take ownership ... staying root`，给 compose 的服务加一行 `user: "0:0"` 即可（进程一直以 root 运行，功能不受影响）。

### 升级

镜像的 tag 就是版本：把 compose 里的 `0.1.0` 换成新的，再点「启动 / 重新部署」。

**只有「启动 / 重新部署」会换掉正在跑的容器**（它等于 `docker compose up -d`）：只点「重启」不会换镜像，
只点「构建」只重做镜像。更新后以为"新代码没生效"，几乎都是这一步。

---

## 自己构建镜像（离线，或者改了代码）

NAS 出不了外网、或者你想改 Dockerfile / 改代码，就走这一条：镜像在 NAS 上构建。需要四个文件，都在
`dist/` 里（见下面的**从源码构建**），release 附件里也有同一套：

```
docker-compose.yml      ← 这一份里是 build: .
Dockerfile
tupingd-linux-amd64     ← 按架构选：x86_64 用 amd64，aarch64 用 arm64（不用改名）
.dockerignore           ← 可选：让构建不再把 data/ 打包给 daemon
```

前三个必须和 compose 放在**同一层**（Dockerfile 里是 `COPY tupingd-linux-${TARGETARCH}`，BuildKit 按目标平台取，
传对架构那一个就行）。`.dockerignore` 是可选的 —— 没有它构建照样成功，只是 `data/` 会被一起打包给 daemon；
release 附件里没有它（GitHub 会把点开头的附件改名成 `default.dockerignore`），需要的话从 `dist/` 里拿。

```
/vol1/docker/tuping/
├── docker-compose.yml
├── Dockerfile
├── .dockerignore
├── tupingd-linux-amd64
└── data/                    ← 服务器自己接管它的属主，不用你建也不用你 chown
```

在**文件管理**里建这个目录，把四个文件传进去，别放进子目录。`/vol1/docker` 在文件管理里看不见的话，
换成你能浏览的共享文件夹（例如 `/vol1/1000/docker/tuping`），并把 compose 里那一行卷路径一起改过去。
`.dockerignore` 的作用是让构建不再把 `data/` 里的数据库和缩略图打包给 Docker daemon。

然后 **Docker 应用 → Compose → 新增项目**：项目名 `tuping`，路径选到那一层 → **构建** → **启动**。
群晖/威联通/裸 docker 就在那个目录里 `docker compose up -d --build`。

这条路上有三件事和前一条不同：

- **构建也要拉 registry**：`FROM alpine:3.20` 就是一次拉取，卡在那里同样是镜像源的问题（见上面的
  **拉不动镜像的话**）。省下的只是"不用先发一个镜像"，不是"不碰网络"。
- **换了二进制要重新点「构建」**，再点「启动」—— 理由同上面的**升级**：只点「重启」会继续用旧镜像。
- 这一份 compose 里的 `image: tuping:latest` 只是给构建出来的镜像起一个本地名字，不是从哪儿拉。

---

## 从源码构建

开发机上需要 Go，NAS 上不需要 —— 二进制是交叉编译出来的（`CGO_ENABLED=0`，纯 Go，所以交叉编译不需要额外的工具链）。下面的例子写 `powershell`：只装了 Windows PowerShell 的机器上没有 `pwsh`，而这个写法在两者下都成立。

```powershell
# 一次性：把 Go 装到 F:\go-toolchain，不装任何系统级东西
powershell -File tools/setup-go.ps1

# 构建 + 测试
powershell -File tools/tuping.ps1 -Tasks build,test

# 两个架构的发布二进制 + Dockerfile + 两份 compose + .dockerignore + 本文件，一起进 dist/
powershell -File tools/tuping.ps1 -Tasks dist -Version 0.1.0

# 在开发机上直接跑，不需要 Docker 也不需要 NAS
powershell -File tools/tuping.ps1 -Tasks run -BaseUrl http://192.168.31.20:8080

# 项目已经在 NAS 上建好之后：上传新二进制并重新构建（需要 SSH）
powershell -File tools/tuping.ps1 -Tasks deploy -NasHost 192.168.31.14
```

`-Tasks run` 会把库和缩略图放在 `artifacts/tmp/` 下（不入库），手机连同一个 Wi-Fi 打开 `http://<开发机 IP>:8080/c/<短码>` 就能把整条链路走一遍。

---

## 环境变量

| 变量 | 默认 | 作用 |
| --- | --- | --- |
| `TUPING_ADMIN_TOKEN` | 空 | 管理员令牌。空 = 关闭 `/v1/admin/*` 和网页管理员登录（见下） |
| `TUPING_BASE_URL` | 空 | 可选：把二维码里的地址钉死。留空 = 用"谁在连"的那个地址（见下面的**图评地址从哪来**） |
| `TUPING_SECRET` | 空 = 自动 | 签名密钥：缩略图 URL、管理员 cookie、IP 哈希。留空时第一次启动生成并存到文件（见下面的**签名密钥**） |
| `TUPING_SECRET_FILE` | 数据库同目录的 `secret` | 上面那个文件的位置 |
| `TUPING_ADDR` | `:8080` | 监听地址 |
| `TUPING_DB` | `/data/tuping.db` | SQLite 文件 |
| `TUPING_THUMBS` | `/data/thumbs` | 规范缩略图目录 |
| `TUPING_REGISTER_TOKEN` | 空 | 设了以后注册照片必须带它 |
| `TUPING_OPEN_REGISTRATION` | `true` | `false` = 只有已持有令牌的设备能注册 |
| `TUPING_REVIEW_WORDS` | 空 | 逗号分隔，命中进待审 |
| `TUPING_REJECT_WORDS` | 空 | 逗号分隔，命中直接拒 |
| `TUPING_THUMB_TTL` | `720h` | 缩略图多久没人读就被回收 |
| `TZ` | 镜像里是 `Asia/Shanghai` | 日志时间戳的时区。不在东八区就在 compose 里覆盖它 |

`tupingd -check` 会把当前配置打印出来，用来核对容器里的环境变量是不是真的传进去了：`baseUrl`、`db`、`thumbs`
是实际取值（`baseUrl` 没设时会写明白"每个 URL 用调用者的地址"），`adminToken` / `registerToken` 只显示「已设置/
未设置」，`secret` 会说明它打算从哪儿拿（`TUPING_SECRET` / 那个文件 / 第一次启动时生成）。它**不会**创建任何
文件，所以在还没启动过的 NAS 上也可以放心跑。

---

## 签名密钥

`TUPING_SECRET` 不是你操作的开关，是服务器自己每次请求都在用的一把钥匙：给缩略图 URL 签名（10 分钟有效）、
给管理员登录的 cookie 签名（12 小时有效）、把评论里的 IP 变成不可还原的 HMAC。它有两个要求——**外人不能知道**，
**重启后不能变**——所以默认由服务器自己满足：

- 第一次启动：生成 32 字节随机值，写进 `/data/secret`（权限 600），并在日志里 WARN 一行；
- 之后每次启动：读同一个文件，日志里 `signingKey=file`；
- 设了 `TUPING_SECRET`：用它，**不碰那个文件**，日志里 `signingKey=TUPING_SECRET`。

于是部署者只需要填 `TUPING_ADMIN_TOKEN` 一个自己记得住的值。这两者刻意不相关：管理员令牌是手打进去的、在
明文 http 上过网，而签名密钥从不离开数据目录；把签名密钥写成"令牌+固定串"会让一次 cookie 截获升级成**离线
爆破口令**（cookie 的 MAC 密钥里含令牌，见 §17.19）。

想轮换（比如怀疑密钥被人看到过）：改 `TUPING_SECRET` 重启，或者删掉 `/data/secret` 重启。代价只有两个——
**所有管理员要重新登录**、**手上正在用的缩略图 URL 失效**；评论一条不动，已经发出去的二维码照样能用
（二维码指向 `/c/<code>`，没有签名），访客的匿名身份也不受影响。

---

## 图评地址从哪来

二维码和页面里的链接都需要一个**完整地址**，而服务端不知道自己是被人从哪个地址访问的——同一份服务端可以
同时是局域网地址、端口映射和内网穿透域名。所以这个地址不是配置出来的，是按这个顺序取（§17.6、§17.20）：

| 顺序 | 取值 | 什么时候用得上 |
| --- | --- | --- |
| 1 | **注册这张照片的电视上报的地址**，记在短码上 | 正常情况。电视把自己配置的图评地址一并报上来，那一定是它刚刚连通的那个 |
| 2 | `TUPING_BASE_URL`（如果填了） | 钉死一个地址，给"上报的地址不能信"的场合用，比如反向代理改写了 Host |
| 3 | **这次请求用的地址**（Host 头） | 老客户端没上报地址时的兜底。它和电视上报的是同一类证据，只是不那么明确 |

三档都没有（例如请求来自 `127.0.0.1`／`localhost`，这种地址从来不是手机能打开的）时，两种结果：

- **二维码**：接口回 400 并说明两条出路（钉一个 `TUPING_BASE_URL`，或者在电视上重新注册），而不是把
  `/c/<短码>` 编进二维码——那种码扫出来是个死胡同；
- **评论页**：照旧能用。缩略图会退化成相对路径 `/t/<短码>…`，浏览器按页面自己的来源解析，一定打得开。

想确认某个短码的二维码里到底编的是哪个地址，不用摄像头：

```sh
curl -sD - -o /dev/null http://192.168.31.14:8080/v1/qr/<短码>.png | grep -i x-page-url
```

地址来自第 3 档时，这个 PNG 的 `Cache-Control` 是 `private, no-store`（同一条短码在不同 Host 下内容不同，
不能让共享缓存把它发给别人）；来自第 1、2 档时才是可缓存的。

---

## 管理员

设了 `TUPING_ADMIN_TOKEN` 之后：

- 评论页页脚会多一行 **管理员登录**（`http://<地址>/admin`），口令就是那个令牌；
- 登录后，照片页每条评论下面出现 **编辑** 和 **删除**，页顶一条「管理员模式」提示；
- 登录状态是一个签名的 cookie，12 小时有效；换令牌会让所有已登录的会话立刻失效；
- 同一个 cookie 也能直接打开 `/v1/admin/comments` 看审核队列（浏览器里不用再往地址栏粘 token）；
- **编辑和删除会立刻告诉电视**：正在放这张照片的那台会停下那条弹幕（编辑则换成新文字），不用等它重新打开
  照片。删除是软删除，行还在库里，所以误删可以捞回来。

访客发评论不受影响：不登录的人看到的还是原来那一页，一行 JavaScript 都没有。删除是**软删除**（`status=3`），
行还在库里。设计取舍见 [`docs/tuping-design.md`](../docs/tuping-design.md#1715-管理员登录一个令牌一个签名-cookie以及页面上的编辑删除2026-10-11)。

一个前提：**这一页是明文 http**，所以令牌在局域网里也是明文过网的。§10.1 的边界一字未改 —— 这个实例不能
映射到公网。

---

## 备份

两个东西：

```sh
sqlite3 /vol1/docker/tuping/data/tuping.db ".backup '/vol1/backup/tuping-$(date +%F).db'"
rsync -a /vol1/docker/tuping/data/thumbs/ /vol1/backup/tuping-thumbs/
```

缩略图目录丢了不会丢评论，只是已有的二维码要等下一次注册才恢复出图；数据库丢了才是真的丢评论。

数据目录里还有一个小文件 `secret`（签名密钥，见上）。备份它是可选的——不备份的后果只是恢复之后所有管理员
要重新登录、缩略图 URL 全换一遍，评论和二维码都还在。想连它一起备：

```sh
rsync -a /vol1/docker/tuping/data/secret /vol1/backup/tuping-secret
```

---

## 安全边界

**这个端口绝不能映射到公网。** 它是为局域网设计的：明文 HTTP，评论对连到这个实例的所有人可见，唯一一个账号是管理员（口令是 `TUPING_ADMIN_TOKEN`，走的也是同一条明文 http）。公网那一版（活动域、`msgSecCheck`、审核台）是设计文档里的第二阶段，不是这一版的配置项。

同一句话的另一面：**「图片域」是「同一个实例内的同一张图共享」**，不是跨实例共享。你自己部署的这一份，评论只在你自己的库，别人部署的那一份看不到，换地址就是从零开始。

---

## 目录

```
cmd/tupingd/        入口：环境变量 → 配置 → 监听
internal/api/       HTTP 端点、鉴权、限流、WebSocket
internal/guard/     长度、广告模板、分级词表
internal/phash/     规范缩略图 → pHash‖dHash、剥黑边、方差
internal/qr/        短码 → 二维码 PNG（含缓存）
internal/store/     SQLite、迁移、身份匹配、可逆合并
internal/web/       H5 评论页 + 登录页 + 编辑页（go:embed 进同一个二进制）
```

服务端与相册端在同一个仓库（设计 D6），所以协议一改，协议文档、TV 端、服务端和 mock 可以在一次提交里一起改。两边的版本号是**故意不对齐**的：APK 写在 `app/build.gradle.kts`，服务端由 `-ldflags -X` 注入，tag 用 `app-v1.4.0` / `server-v0.1.0` 区分。
