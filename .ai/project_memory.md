# 项目记忆库 (Project Memory)

> 该文档由 AI 自动维护，用于记录项目演进过程中的关键架构决策、全局状态变更以及踩坑记录，防止跨对话失忆。

## [2026-05-09] 开启 H5 权限与 Docker 化封装
*   **全局状态变更**：`SystemConfigService` 中的 `h5Enabled` 默认值已被硬编码为 `true`，以实现默认开放 H5 网页面板访问。如果后续需要关闭，须通过管理员重置配置或 Native 端发起变更。
*   **容器化决策**：在根目录新增了 `Dockerfile`，由于项目为 Java 8 架构且要求无 JDK 环境亦可运行，最终选用 `eclipse-temurin:8-jre-alpine` 作为基础镜像。此方案只保留运行时环境，体积更小，且消除了宿主机对于 Java 环境的强依赖。
*   **外挂配置管理**：重构了容器的文件流向机制，引入 `docker-compose.yml`。通过 `COPY application.yml` 实现基础配置打底，同时通过 `volumes` 机制实现了宿主机侧对外置基础配置（`application.yml`）、动态业务库（`/config`）以及日志库（`/logs`）的双向绑定，极大地提升了最终用户的无脑启动体验与数据安全性。

## [2026-05-09] 项目全量更名（天穹 / sty-visit）与发版清理
*   **物理包名迁移**：由 `com.visit` 整体迭代迁移至 `com.sty.visit` 架构，影响范围包含所有 Java 源代码、Android Kotlin 源码、安全过滤器、各类 Bean 初始化日志域（如 `com.sty.visit: INFO`）。
*   **品牌焕新**：所有前端页面标题、Markdown 文档以及应用名均替换为 **天穹 (sty-visit)**，且在 `README.md` 中强制推送了 `docker-compose` 作为最高优先级的发版部署方案。
*   **版本号统一与交付规格化**：将全线业务模块（`pom.xml`, `run.sh`, `FEATURE_LIST.md`, 基础配置及上下文文档）的版本号收敛拉齐至 **`v1.0 / 1.0.0`**，重命名发布目录为 `release_v1.0_stable`。并大幅补充扩写了 Docker Compose 环境下引入和热加载外置 `application.yml` 配置文件的详细指导步骤。
*   **交付结构拆分与受众定制**：彻底打破了以往一揽子全放的粗放式打包，在 `release_v1.0_stable` 内划分出了三个纯净的受众区：`docker_release`（包含一键拉起的容器编排与镜像指引）、`jar_release`（面向传统 Java 服务器环境的裸装版）以及 `app_release`（移动客户端）。并为每个发行版配备了相互独立、侧重点完全不同的 `README.md`，极大降低了用户的心智负担。
*   **初始密码规范化**：将所有应用层配置（含根目录与发布目录下的 `application.yml`）与文档向导中的默认超级密钥，统一修改为了符合强口令规则（>=12位，大小写+数字+特殊字符）的短语密码 `StyVisit@2026`，增强了小白用户的开箱安全性。
*   **发布前净身大扫除**：在最终交付前，彻底清除了根目录下的全局日志残留（`logs/*`, `config/*`, `backend.log`）、临时放置的冗余 Jar 包（`sty-visit.jar`）以及在更名重构期间产生的 Python 脚本与过程中的 AI 实施计划草稿文档，确保提交树与发行包绝对干净。
*   **版本演进生命周期指南**：在根目录 `README.md` 中专门增设了“第 4 章：版本更新与平滑升级指南”，向终端用户提供了 Docker 方案下 (`docker-compose down` -> 替换包 -> `docker-compose up -d --build`) 以及 Jar 方案下的无损热替代升级方案，确保未来业务迭代期间，用户的数据与配置得到极致保障。
*   **品牌视觉统一 (App Icon)**：结合东方网络防御哲学，最终为天穹敲定了基于“浑天仪 + 护身罩”的国风赛博图标。该图标具备极高的商业发版水准。同时已完成底层 `mipmap` 图片的 192x192 裁切与全量替换，并通过 Gradle 清理并重新构建发版了最新的 `sty-visit.apk`。
