# 天穹 核心上下文 (Project Context)

## 1. 项目定位 (Positioning)
**天穹 (sty-visit)** 是一款专为运维工程师打造的、“端云一体”的 Linux 堡垒机/终端管理中枢。
项目采用 **Golang (后端)** + **Vue/Xterm.js (前端)** 构建，实现了真正的 **零依赖、单文件极致部署**。集成了高性能 WebSocket SSH 隧道、流式文件管理、仪表盘监控及自动化批量命令执行能力。

---

## 2. 核心架构 (Architecture)

### 2.1 极简高并发设计 (Go-Native)
- **并发模型**：基于 Goroutine 和 Channel 的非阻塞 IO 处理。
- **WebSocket 防漏缓冲**：基于底层的原生管道通讯同步返回数据流，并在连接中断时强制销毁 goroutine 回收句柄。避免了传统模型下的内存膨胀与雪崩。
- **过载保护熔断**：通过读写锁与原子计数实现全生命周期管理的 SSH 连接池 (`pool`)，并严格执行 `max_sessions` 全局拦截保护。
- **资源内嵌 (embed.FS)**：前端静态资产彻底打包在 Go 二进制程序中，实现物理防篡改和真·单文件分发。

### 2.2 核心业务模块
- **终端会话层**：基于 `golang.org/x/crypto/ssh` 构建，支持 ANSI 全彩和 Window 动态重置。
- **审计与文件**：底层引入 `pkg/sftp` 用于文件游走与流式传输。基于 Gin 中间件拦截进行操作审计（Audit Middleware）。
- **批量执行调度**：支持基于预设资产配置(`nodes`)或临时并发多机器发号施令，统一调度输出。

---

## 3. 关键文件索引 (Key Files)

- **Go 后端核心 (`/go-backend/internal/api/`)**：
    - `router.go`：路由及 `embed.FS` 静态资源挂载中心。
    - `terminal.go`：WebSocket SSH 终端会话流核心引擎。
    - `manager.go`：SSH 持久化连接池、状态机与过载保护熔断器。
    - `batch.go`：批量命令集群并发执行控制器。
    - `middleware.go`：API鉴权（JWT）与审计日志拦截器。
- **前端核心 (`/go-backend/static/`)**：
    - `index.html`：单页应用入口。
    - `js/app.js`：Vue 业务逻辑控制中枢。
    - `lib/xterm.js`：终端 ANSI 渲染引擎（强制绑定 DOM/Canvas 模式，保障老旧内网机器的渲染稳定性）。

---

## 4. 当前状态 (Current Status)
- **v1.0 Go 重构全通版**：已彻底抛弃早期的 Java 臃肿架构，全面完成 Go 原生重构。后端引入安全读取模型与熔断保护。
- **交付形态**：通过跨架构编译生成含 `start.sh` 和 `config.yaml` 模板的 Universal 发行包，实现双架构(AMD64/ARM64)开箱通杀。

> [!IMPORTANT]
> **开发规范**：严禁随意引入第三方重量级依赖。确保前端无 CDN 外链，保障在绝对物理断网环境下系统的 100% 离线可用性。
