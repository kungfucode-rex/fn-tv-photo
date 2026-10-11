# 图评服务端（tupingd）

电视上预览一张照片时，角落出现一个二维码；客人用微信扫它，在手机上看这张图的缩略图、写一句评论；提交成功后，电视上评论以弹幕形式飘过。

这个目录是那颗服务端：一个静态二进制，没有运行时，自己带着 H5 评论页。协议、参数与决策的唯一真相源是 [`docs/tuping-design.md`](../docs/tuping-design.md)。

---

## 部署

**给不想读长文的人**：在 Docker（飞牛/群晖/威联通/任何 Linux）上跑一个容器，把 8080 端口和 `data/`
目录给它，环境变量里只填一个管理员口令；跑起来之后手机打开 `/admin` 能登录、电视上填同一个地址就能用。
它默认走明文 HTTP，也能直接说 HTTPS（见下面的 **HTTPS**），但这两件事都不改变它的边界：
**这个端口不要映射到公网** —— 它没有账号体系（只有一个管理员口令），评论对所有能连到它的人可见，
局域网就是它设计的边界。

### 一行部署（推荐）

镜像发布在 Docker Hub 上，而且是**多架构**的：amd64 还是 arm64 由镜像自己的清单决定，你不用选、不用
下载二进制、也不用在同一个目录里凑齐几个文件。整件事就是把一份 compose 粘进去、改一行、点启动。

飞牛 **Docker 应用 → Compose → 新增项目**，项目名 `tuping`，把下面这一段粘进去：

```yaml
services:
  tuping:
    image: "kungfucode/tuping:0.1.1"
    container_name: "tuping"
    restart: "unless-stopped"
    ports:
      - "8080:8080"
    volumes:
      - "./data:/data"
    environment:
      TUPING_ADMIN_TOKEN: "change-me"
```

**改一行**：`TUPING_ADMIN_TOKEN` 换成你的口令。其余：`8080` 左边那个是 NAS 上的端口（被占用就改成
`8090:8080` 之类）；`./data` 是评论、缩略图、签名密钥落地的目录；`container_name` 让容器就叫 `tuping`
（下面那些 `docker exec tuping …` 用的是这个名字，去掉它容器会叫 `tuping-tuping-1` 之类）。**带全部注释的
同一份**在 [`docker-compose.image.yml`](docker-compose.image.yml)（镜像 tag、TLS、注册令牌、内容词表都在
里面）。

> 这一段**每行都很短、每个值都带引号**，两件事都是有意的：从渲染过的网页复制时，有些工具会按窗口宽度
> 重排长行，而且在**未加引号**的值里按 `:` 和 `-` 断行 —— `kungfucode/tuping:0.1.1` 会变成两行、
> `unless-stopped` 会变成 `unless-` + `stopped`，整段就解析失败（报错通常在 `expected <block end>` 附近）。
> 引号里的内容不会被拆，所以值一律加引号。粘完还是扫一眼：如果某行被拆开了，把值接回冒号后面那一行即可。
> 不想冒这个险就别粘 —— 用文件管理把 [`docker-compose.image.yml`](docker-compose.image.yml) 传进一个
> 目录、在面板里按**路径**建项目，或者直接用下面这条命令。

没有 Compose 的地方，等价的一条命令：

```sh
docker run -d --name tuping --restart unless-stopped -p 8080:8080 \
  -v "$PWD/data:/data" -e TUPING_ADMIN_TOKEN='你的口令' kungfucode/tuping:0.1.1
```

| 变量 | 填什么 |
| --- | --- |
| `TUPING_ADMIN_TOKEN` | 管理员的密码（见下面的**管理员**）。**留空 = 没有管理员**：评论页不出现 管理员登录，`/admin` 回 404 |

另外两个都不用手填：`TUPING_SECRET` 由服务器第一次启动自己生成（见**签名密钥**），`TUPING_BASE_URL`
默认取"谁在连"的那个地址（见**图评地址从哪来**）。

点**启动**即可 —— 这一条路**没有「构建」这一步**，它只是把镜像拉下来。

起来之后（日志里应该先有一行 `running unprivileged uid=10001 gid=10001`，然后是 `tupingd listening`）：

```sh
curl http://192.168.31.14:8080/healthz
# {"baseUrl":"","baseUrlSource":"the address each caller used","ok":true,"version":"0.1.1","uptimeSec":3,"thumbBytes":0}
```

`baseUrl` 是空的，因为没钉地址——`baseUrlSource` 就是说明这件事的字段。钉过的话它会是
`"TUPING_BASE_URL"`，`baseUrl` 是那个值。

**电视端的「图评服务器」填这台 NAS 的地址**（`http://192.168.31.14:8080`）。二维码里的地址是电视注册照片时一起报上来的那个，所以两边不会不一致。

**拉不动镜像的话**，那是 NAS 的镜像源问题，不是这份 compose 的：飞牛默认的 `docker.fnnas.com` 会回
`401 Unauthorized`，直连 Docker Hub 在国内又常被重置（`connection reset by peer`）。在 Docker 应用的
**镜像 → 仓库设置** 里换成能用的加速源，然后**在面板右上角把 Docker 服务停止再启动**（改镜像源必须重启
daemon），再去启动。

要注意的是：**有些加速源只镜像 Docker Hub 的官方镜像**，对 `kungfucode/tuping` 这种仓库直接回 `403`
（403 而不是 401，意味着它连认证流程都不给，客户端只能失败）。2026-10-11 匿名拉取本镜像 manifest 的实测：

| 加速源 | 能拉到本镜像吗 |
| --- | --- |
| `https://dockerproxy.net` | ✅ 200 |
| `https://hub.1panel.dev` | ✅ 200 |
| `https://docker.m.daocloud.io` | 401 → 客户端会照它给的 challenge 取 token，通常可用 |
| `https://hub.rat.dev` | 302 → 客户端跟随，通常可用 |
| `https://docker.1panel.live`、`https://docker.xuanyuan.me` | ❌ 403：只放官方镜像（它们拉 `library/alpine` 是好的，这正是构建那条路能通的原因） |
| `https://docker.fnnas.com`（飞牛默认） | ❌ 401 |

所以镜像源列表里除了拉官方镜像那个，**再放一个能拉任意仓库的**（例如 `https://dockerproxy.net`）——
Docker 会按顺序试，两类镜像各走各的。

### 数据目录的属主：不用你做

这一条曾经是部署里唯一需要 SSH 的一步，现在不需要了。原理值得知道，因为它解释了你会看到的两行日志（§17.21）：

- 镜像里的进程是 uid **10001**（`adduser -u 10001 tuping`），而 bind mount 的宿主目录是 **Docker 以 root 建的** —— 权限检查比的是数字 uid，两者对不上，SQLite 就建不出 `tuping.db`（症状：容器起来几秒退出、反复重启，日志一行 `unable to open database file`）；
- 所以镜像**以 root 启动**，二进制在打开数据库之前把数据目录（含老文件）的属主交给 10001，然后立刻放弃 root：日志里先是一行 `running unprivileged uid=10001`，之后对外服务的进程就是 10001，内核层面也是如此。

唯一需要你动手的例外：**挂载的是 SMB/CIFS 这类不支持 `chown` 的文件系统**。那时日志里会出现 `could not take ownership ... staying root`，给 compose 的服务加一行 `user: "0:0"` 即可（进程一直以 root 运行，功能不受影响）。

### 升级、备份、卸载

**升级**：镜像的 tag 就是版本 —— 把 compose 里的 tag 换成新的，再点「启动 / 重新部署」。

**只有「启动 / 重新部署」会换掉正在跑的容器**（它等于 `docker compose up -d`）：只点「重启」不会换镜像，
**也不会把新的环境变量装进容器**（加了 `TUPING_TLS_CERT` 却没重建，就是"配了 HTTPS 但 http 照旧"最常见
的原因）。只点「构建」只重做镜像。更新后以为"新代码没生效"，几乎都是这一步。

**备份**：`data/` 里的三样东西 —— `tuping.db`（评论）、`thumbs/`（缩略图）、`secret`（签名密钥）。
前两个是必须的（见下面的**备份**一节），`secret` 丢了只是让管理员重新登录一次。

**卸载**：停掉项目、删掉容器即可；想连评论一起清掉就删 `data/` 目录。这个服务不在别处留任何东西 ——
没有外发请求、没有账号、没有云。

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
powershell -File tools/tuping.ps1 -Tasks dist -Version 0.1.1

# 在开发机上直接跑，不需要 Docker 也不需要 NAS
powershell -File tools/tuping.ps1 -Tasks run -BaseUrl http://192.168.31.20:8080

# 本地验证 HTTPS：先给两个变量（自签证书自己签一张就行），再照上面那样跑
# $env:TUPING_TLS_CERT='F:\certs\cert.pem'; $env:TUPING_TLS_KEY='F:\certs\key.pem'
# powershell -File tools/tuping.ps1 -Tasks run -Addr 0.0.0.0:8443 -BaseUrl https://192.168.31.20:8443

# 项目已经在 NAS 上建好之后：上传新二进制并重新构建（需要 SSH）
powershell -File tools/tuping.ps1 -Tasks deploy -NasHost 192.168.31.14
```

`-Tasks run` 会把库和缩略图放在 `artifacts/tmp/` 下（不入库），手机连同一个 Wi-Fi 打开 `http://<开发机 IP>:8080/c/<短码>` 就能把整条链路走一遍。同一个地址上还有两个不用启动服务的开关：`tupingd -check`（打印配置，包括证书是谁签给谁的、什么时候到期）和 `tupingd -health`（按当前环境变量里的协议探测自己，容器里的健康检查就是它）。

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
| `TUPING_TLS_CERT` | 空 | 可选：证书链的路径，设了就自己用 HTTPS 服务（见下面的 **HTTPS**） |
| `TUPING_TLS_KEY` | 空 | 可选：私钥的路径，必须和上面那个成对出现 |
| `TZ` | 镜像里是 `Asia/Shanghai` | 日志时间戳的时区。不在东八区就在 compose 里覆盖它 |

`tupingd -check` 会把当前配置打印出来，用来核对容器里的环境变量是不是真的传进去了：`baseUrl`、`tls`、`db`、
`thumbs` 是实际取值（`baseUrl` 没设时会写明白"每个 URL 用调用者的地址"，`tls` 打开时会写出证书是谁签给谁的、
什么时候到期），`adminToken` / `registerToken` 只显示「已设置/未设置」，`secret` 会说明它打算从哪儿拿
（`TUPING_SECRET` / 那个文件 / 第一次启动时生成）。它**不会**创建任何文件，所以在还没启动过的 NAS 上也可以
放心跑。

另一个可以随时敲的是 `tupingd -health`：它按当前环境变量里的协议去探测自己，健康就退出 0、否则退出非 0
并说明打不开哪个地址。容器里的健康检查用的就是它（见下面的 **HTTPS**）。

---

## HTTPS（可选）

> **前提：跑着一个认识这两个变量的二进制。** 这个特性是 **0.1.1** 才有的：更早的镜像（包括
> `kungfucode/tuping:0.1.0`）里没有这段代码，设了 `TUPING_TLS_CERT/KEY` 它**既不生效、也不报错**
> —— 端口照旧说明文 http，手机用 https 连过去只会得到一个 400。上面那份 compose pin 的就是 0.1.1；
> 如果你手上是更早的镜像，换成 0.1.1 或更新，要么走上面的**自己构建镜像**。
>
> 两条命令就能确认正在跑的那个二进制认不认（第一条要能 SSH，第二条只要看面板日志）：
>
> ```sh
> docker exec tuping /usr/local/bin/tupingd -check | grep '^tls'
> ```
>
> 打印 `tls = off (plain http)` 或 `tls = on (…)` = 认识；**一行都没有** = 这个二进制太旧了。
> 面板日志里那行 `tupingd listening` 是同一件事：新二进制一定有 `tls=true|false` 字段，旧的没有这个字段。

**服务端自己会说 HTTPS，但证书不归它管。** 这是刻意的分工：域名、映射、证书申请与续期都是部署环境的事
（ACME 客户端、反代、隧道各有各的做法），服务端只负责三件事 —— 用你给的两个文件提供服务、文件被换掉之后
自己重读、你只给了一半的时候大声失败。

| 变量 | 填什么 |
| --- | --- |
| `TUPING_TLS_CERT` | 证书链（PEM）的路径，例如 `/certs/fullchain.pem` |
| `TUPING_TLS_KEY` | 私钥（PEM）的路径，例如 `/certs/privkey.pem` |

在 compose 里就是三处改动：

```yaml
    ports:
      - "443:8080"
    volumes:
      - "/vol1/docker/tuping/certs:/certs:ro"
    environment:
      TUPING_TLS_CERT: "/certs/fullchain.pem"
      TUPING_TLS_KEY: "/certs/privkey.pem"
```

`TUPING_BASE_URL` 可选：留空时用电视上报的那个地址，那时它天然就是 https；要钉就钉成
`https://photos.example.com`（要和证书上的域名一致）。

**打开之后自动跟着变的三件事**（都不用配置）：

1. **页面和二维码里的链接都变成 `https://`**。协议头不是配置项，是从连接上读出来的（`r.TLS`）：同一份服务端，
   明文访问得到 http 链接，TLS 访问得到 https 链接。二维码里的地址本来就是电视上报的"它刚用过的那个"
   （§17.20），所以电视端把**图评服务器**改成 `https://…` 之后，扫出来的码就是 https 的。
2. **cookie 带上 `Secure`**：管理员会话、访客身份、记住的昵称，只在加密连接上下发（明文部署下不设这个标志，
   否则这些 cookie 会**永远不再回来**）。
3. **HTTP/2**，白拿。

**四件要知道的事**：

- **两个文件必须在容器里**。设了变量却读不到文件（挂载忘了加、路径写错、文件名不对、权限读不到），
  **容器会启动失败并反复重启**，日志点名是哪一个变量、哪个路径，并写出两条出路：把文件挂到那个路径
  （要能被 uid 10001 读到），或者取消这两个变量回到明文 http。**这是有意的**：读不到就当明文服务，正是
  上面那个"配了没反应"的坑。证书还没准备好就先别设这两个变量 —— 不设就是明文，局域网照常能用。
- **两个变量必须成对**。只设一个会**启动失败并点名缺的那个** —— 悄悄退回明文，会让"证书配好了"这件事在
  每一块屏幕上都看不出来，而管理员口令正在局域网里明文过网。
- **证书换掉不用重启**。文件内容一变，下一次握手就用新的（最长一分钟的延迟），所以 ACME 的续期脚本不用管
  这个容器。续期写坏了也不会把服务弄挂：新的读不出来就继续用旧的，日志里一行 error。启动时和每次重载都会
  记下到期时间，到期前 14 天内会 WARN，已经过期会 ERROR。
- **两个文件要能被 uid 10001 读到**。进程在开始服务之前就放弃了 root（§17.21），root 能读不算数。
  `/certs:ro` 这种挂载通常就够了，权限是 600 且属主不是 10001 的时候要改。
- **换到 HTTPS 之后，以前印出去的 http 二维码会失效**：那个端口不再说明文。电视上重新填一次地址即可
  （每张新照片注册时都会带上当前地址）。镜像里的健康检查不用你管 —— 它探测的是进程自己
  （`tupingd -health`，按环境变量里配置的协议探测，明文和 TLS 都对；拉下来的旧 compose 里若还是
  `wget http://127.0.0.1:8080/healthz`，把那一行换成 `["CMD", "/usr/local/bin/tupingd", "-health"]`
  即可，否则容器会一直显示不健康，而服务其实是好的）。

**只想在局域网里用自签证书？** 可以，但先想清楚代价：二维码是给**客人的手机**扫的，而微信打开自签证书的
页面会拦住（或至少弹一个多数人不会越过的警告），客人不会为了看一张照片去装你的 CA。自签证书这套配置在
你自己的机器上 `curl -k` 是通的，在客人手机上不是。

**反向代理终止 TLS 的场合**（tupingd 自己是明文，前面挂 nginx/Caddy）：这个进程看到的连接是明文，所以它
自己推导出来的地址会是 `http://…`。这时把 `TUPING_BASE_URL` 钉成 `https://…` 就对了（电视上报自己的地址时
本来也已经是对的）。**不要**同时设 `TUPING_TLS_CERT/KEY` —— 那会让这个进程自己也要求 TLS。顺带一句：服务端
**不读** `X-Forwarded-Proto`，理由和它不读 `X-Forwarded-For` 一样（§10.1）—— 那是任何调用者都能填的头，
拿它决定二维码里印什么地址，等于让陌生人替你选地址。

---

## 签名密钥

`TUPING_SECRET` 不是你操作的开关，是服务器自己每次请求都在用的一把钥匙：给缩略图 URL 签名（10 分钟有效）、
给管理员登录的 cookie 签名（12 小时有效）、把评论里的 IP 变成不可还原的 HMAC。它有两个要求——**外人不能知道**，
**重启后不能变**——所以默认由服务器自己满足：

- 第一次启动：生成 32 字节随机值，写进 `/data/secret`（权限 600），并在日志里 WARN 一行；
- 之后每次启动：读同一个文件，日志里 `signingKey=file`；
- 设了 `TUPING_SECRET`：用它，**不碰那个文件**，日志里 `signingKey=TUPING_SECRET`。

于是部署者只需要填 `TUPING_ADMIN_TOKEN` 一个自己记得住的值。这两者刻意不相关：管理员令牌是手打进去的、默认
在明文 http 上过网（配上 **HTTPS** 之后就只在 TLS 里），而签名密钥从不离开数据目录；把签名密钥写成"令牌+
固定串"会让一次 cookie 截获升级成**离线爆破口令**（cookie 的 MAC 密钥里含令牌，见 §17.19）。

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

一个前提：**默认这一页是明文 http**，所以令牌在局域网里也是明文过网的；想让它别这样，就把 **HTTPS** 那一段
配上，令牌和会话就都在 TLS 里了。但 §10.1 的边界另有其事，而且一字未改 —— 这个实例不能映射到公网。

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

**这个端口绝不能映射到公网。** 它是为局域网设计的：评论对连到这个实例的所有人可见，唯一一个账号是管理员（口令是 `TUPING_ADMIN_TOKEN`）。HTTPS（见上面的 **HTTPS**）保护的是**这段路上的明文**——口令、会话、评论、缩略图——它不是账号体系：拿到地址的人照样能看、能评。公网那一版（活动域、`msgSecCheck`、审核台）是设计文档里的第二阶段，不是这一版的配置项。

同一句话的另一面：**「图片域」是「同一个实例内的同一张图共享」**，不是跨实例共享。你自己部署的这一份，评论只在你自己的库，别人部署的那一份看不到，换地址就是从零开始。

**限流是有的，但它不是账号体系。** 发评论这一个端点带四道额度：每个访客 5 条/分、50 条/天，每个 IP 30 条/分，每张图 200 条/小时，以及**整个端点 120 条/分**（2026-10-11 加的那一道：前三道都能靠换 cookie／换 IP／换图片绕开，所以端点本身要有一个不看来源的上限）。全表与理由在 [`docs/tuping-design.md`](../docs/tuping-design.md) §7；被拦下的回答是 `429` 加一句「发得太快了，歇一会儿」。它挡的是刷屏和脚本，不是"谁能看"。

---

## 目录

```
cmd/tupingd/        入口：环境变量 → 配置 → 监听（含 TLS、证书热重载、-check / -health 两个自检开关）
internal/api/       HTTP 端点、鉴权、限流、WebSocket
internal/guard/     长度、广告模板、分级词表
internal/phash/     规范缩略图 → pHash‖dHash、剥黑边、方差
internal/qr/        短码 → 二维码 PNG（含缓存）
internal/store/     SQLite、迁移、身份匹配、可逆合并
internal/web/       H5 评论页 + 登录页 + 编辑页（go:embed 进同一个二进制）
```

服务端与相册端在同一个仓库（设计 D6），所以协议一改，协议文档、TV 端、服务端和 mock 可以在一次提交里一起改。两边的版本号是**故意不对齐**的：APK 写在 `app/build.gradle.kts`，服务端由 `-ldflags -X` 注入，tag 用 `app-v1.4.0` / `server-v0.1.1` 区分。
