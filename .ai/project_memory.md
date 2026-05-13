# 项目记忆库 (Project Memory)

> 该文档由 AI 自动维护，用于记录项目演进过程中的关键架构决策、全局状态变更以及踩坑记录，防止跨对话失忆。

## [2026-05-13] 彻底的单文件化与跨架构发布 (Embed.FS & Universal Build)
*   **前端静态内嵌化 (Go Embed)**：不再保留外置的 `static` 目录，利用 Go 1.16+ 的 `//go:embed` 机制将所有 Vue、JS、CSS、图片等静态资源编译封装进了 Go 二进制内核中。极大提升了发版交付的整洁度，实现了防篡改、无外部网络请求依赖的“绝对单文件部署”。
*   **极简构建与体积瘦身**：清空了工作区历史中间态文件、移除了曾导致浏览器崩溃的 `xterm-addon-webgl.js`。编译时引入了 `-ldflags="-s -w"` 指令，将挂载了全量前端资产的二进制核心体积成功压缩至仅有 26MB。
*   **外置配置模板自动化**：修改了 Viper 的初始化逻辑 (`viper.SafeWriteConfigAs`)，如果程序启动时未找到外置配置，系统会在当前目录（或指定的 `-c` 路径下）自动生成一份带有详尽中文注释的 `config.yaml` 模板供用户修改。实现了真正的 Zero-Config 到 Custom-Config 的平滑过渡。
*   **跨架构 (AMD/ARM) 发行包突破**：因 Linux ELF 格式不支持双架构融合，故放弃混合编译，采用了 Universal ZIP 包装方案。包内同时交叉编译生成 Linux AMD64 与 ARM64 双核，辅以 `start.sh` 架构自动侦测脚本。用户只管无脑执行 `./start.sh`，系统即可自适应感应环境并拉起对应内核。
*   **文档历史清洗**：全面移除了所有文档中关于 Java (JRE 8)、Docker (docker-compose) 以及 JAR 部署的遗留痕迹，将 `README.md`, `FEATURE_LIST.md`, `contexts/context.md` 全部平滑升级至 Go 版本的“极简二进制单文件外置配置”操作说明体系。

## [2026-05-11] 核心后端架构完全 Golang 化迁移 (Java 彻底剥离)
*   **重构动因与语言选型**：原 Java/Spring Boot 架构启动慢且常驻内存过高（300-500MB），对仅作为“辅助运维”的小型服务器负担过重。经重构选型后，整个后端全量剥离 Java 并迁移至 **Golang**。新版二进制文件大小缩减，运行时内存稳定在 15-30MB 左右，极大地提升了“资源零侵占”的产品初衷。
*   **配置文件生态降级**：废除了原有的 `application.yml` 和繁重的层级化嵌套结构，转而启用了结构扁平、原汁原味的 Go 风格 `config.yaml`（依赖 `github.com/spf13/viper` 解析），极大地简化了代码层调用链路。
*   **SSH / SFTP 全面替代**：利用 Go 原生生态 `golang.org/x/crypto/ssh` 与 `github.com/pkg/sftp` 彻底抛弃了老旧的 JSch。使用 `github.com/gorilla/websocket` 实现终端数据的全双工打通，不仅彻底解决了移动端 SSH 中英文字符重叠卡顿的问题，而且通过 Go 天然的协程池实现了类似原有 Java 版本的会话长连接池粘滞保持机制（MD5 摘要生成 `sessionKey`）。
*   **隐形守卫机制 (Stealth Mode)**：继承并优化了全局物理截断层。利用 Gin 中间件重构了 `H5AccessFilter`，一旦触发隐身开关，后端会直接裸输出足以乱真的 Tomcat 404 HTML 以抵御网页探测。
*   **诊断与运维降级**：利用 `gopsutil` 精准获取宿主机 CPU、内存、负载、磁盘及 Uptime 指标，实现了监控大屏秒级回传。同时精简了不必要的底层审计写入模块，所有的合规审计记录直接无状态地吐入控制台 Standard Log。
*   **构建部署建议**：未来编译产出将仅剩一个单文件二进制包 (`server_bin`)。直接取代过去的 `sty-visit.jar` 和 JRE 环境。
*   **产品演进 (Phase 1 & Phase 2)**：摒弃了单一密码之上再加 TOTP 两步验证的过度防御方案（依照最小化运维成本原则）。目前已扩展实现**深度进程管控** (Top 10 进程实时监控及一键 Kill 能力)，以及**全量 Docker 管理器** (直连 Docker Sock 实现容器列表、状态启停控制及最后 100 行错误日志秒级抓取)。这些核心升级极大地丰富了运维面板的业务厚度。
*   **高级网络与跳板机进化 (Phase 3)**：重构了 `config.yaml` 引入 `nodes` 列表结构，天穹现已从“单机面板”蜕变为**真正的 Jump Server（跳板机）**。同时，依托于 Go 强大的并发能力与 `x/crypto/ssh`，新增了 `/ws/proxy` WebSocket 代理层。App 端可通过此隧道，将远端内网的端口（如 3306 数据库端口）直接无缝映射至操作者的本地环境，彻底打通内网穿透最后一公里。

## [2026-05-09] 开启 H5 权限与 Docker 化封装
*   **全局状态变更**：`SystemConfigService` 中的 `h5Enabled` 默认值已被硬编码为 `true`，以实现默认开放 H5 网页面板访问。如果后续需要关闭，须通过管理员重置配置或 Native 端发起变更。
*   **容器化决策**：在根目录新增了 `Dockerfile`，由于项目为 Java 8 架构且要求无 JDK 环境亦可运行，最终选用 `eclipse-temurin:8-jre-alpine` 作为基础镜像。此方案只保留运行时环境，体积更小，且消除了宿主机对于 Java 环境的强依赖。
*   **外挂配置管理**：重构了容器的文件流向机制，引入 `docker-compose.yml`。通过 `COPY application.yml` 实现基础配置打底，同时通过 `volumes` 机制实现了宿主机侧对外置基础配置（`application.yml`）、动态业务库（`/config`）以及日志库（`/logs`）的双向绑定，极大地提升了最终用户的无脑启动体验与数据安全性。

## [2026-05-09] 项目全量更名（天穹 / sty-visit）与发版清理
*   **物理包名迁移**：由 `com.visit` 整体迭代迁移至 `com.sty.visit` 架构，影响范围包含所有 Java 源代码、Android Kotlin 源码、安全过滤器、各类 Bean 初始化日志域（如 `com.sty.visit: INFO`）。
*   **品牌焕新**：所有前端页面标题、Markdown 文档以及应用名均替换为 **天穹 (sty-visit)**，且在 `README.md` 中强制推送了 `docker-compose` 作为最高优先级的发版部署方案。
*   **版本号统一与交付规格化**：将全线业务模块（`pom.xml`, `run.sh`, `FEATURE_LIST.md`, 基础配置及上下文文档）的版本号收敛拉齐至 **`v1.0 / 1.0.0`**，重命名发布目录为 `release_v1.0_stable`。并大幅补充扩写了 Docker Compose 环境下引入和热加载外置 `application.yml` 配置文件的详细指导步骤。
*   **初始密码规范化**：将所有应用层配置（含根目录与发布目录下的 `application.yml`）与文档向导中的默认超级密钥，统一修改为了符合强口令规则（>=12位，大小写+数字+特殊字符）的短语密码 `StyVisit@2026`，增强了小白用户的开箱安全性。
*   **发布前净身大扫除**：在最终交付前，彻底清除了根目录下的全局日志残留（`logs/*`, `config/*`, `backend.log`）、临时放置的冗余 Jar 包（`sty-visit.jar`）以及在更名重构期间产生的 Python 脚本与过程中的 AI 实施计划草稿文档，确保提交树与发行包绝对干净。

## [2026-05-09] 修复 H5 物理封锁报错问题
*   **踩坑记录与机制重申**：针对 `H5AccessFilter` 返回伪装的 Tomcat 404 错误页问题进行了排查，确认由于打包时误入了 `system.json` 导致初始状态 `h5Enabled` 被设置为 `false`。已物理抹除发布包（`release_v1.0_stable`）中的脏配置。未来若要启用此安全封锁，系统只会通过代码缺省降级或 API 实时热更新来动态生成该配置。同时重新构建并覆盖了线上的 v1.0.0 Release。

### Phase 4: 前端 UI 现代化与架构拆分 (2026-05)
1. **架构拆分解耦**：编写脚本将原本长达 1300 行的 `index.html` 成功分离，抽出核心业务逻辑到独立的 `js/app.js` (基于原生 ES Module)，实现了零构建依赖的组件化形态。
2. **Apple Minimal & Dark Tech 规范融合**：重写了 `apple-ui.css`，采用了时下主流的 Bento Box (便当盒) 布局与发光玻璃材质。实装了深浅色一键动态切换机制，适配不同操作系统的显示习惯。
3. **功能组件可视层实装**：
   - 部署了 **监控大屏面板**（附带 Top 资源消耗进程以及进程 Kill 功能）。
   - 实装了全新的 **Docker 管控舱 Tab**，支持对服务器容器进行全生命周期管理及实时查阅日志流。
   - 在首页植入了 **Jump Server (跳板机)** 的资产图谱，打通了直接穿透到内网服务器的前端交互链路。

4. **Go 架构割接踩坑与平滑修复 (2026-05-11)**：
   - **路由缺失补全**：在从 Java 迁移至 Golang 后，遗漏了核心的辅助性 API（`/api/bookmarks`, `/api/snippets`, `/api/transfers`, `/api/config/audit/retention`）以及静态图片路由（`logo.png`）。目前均已在 Go 侧重新补齐实现。
   - **WebSocket 路由漂移**：修正了 `WebSocketTerminal` 在 Go 中的挂载路径，将其从 `/api/terminal` 平滑迁移至全局 `/ws/terminal`，并复用了通过 URL Query 获取 Token 的鉴权逻辑。
   - **样式原子类补充**：修补了由于剔除 Tailwind 框架导致的“Bento 布局崩塌”问题，将排版所需的原子类（Grid, Flex, Margin, Padding 等）手工缝合进了 `apple-ui.css` 的底层，完美恢复了界面的视觉秩序。
