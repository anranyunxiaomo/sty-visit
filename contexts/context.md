# 天穹 核心上下文 (Project Context)

## 1. 项目定位 (Positioning)
**天穹** 是一款专为运维工程师打造的、“端云一体”的移动级 Linux 堡垒机/终端管理中枢。它集成了高安全性的 SSH 隧道、流式文件管理、仪表盘监控及 Android 原生隐私保护能力。

---

## 2. 核心架构 (Architecture)

### 2.1 设计模式驱动 (Pattern-Driven)
- **工厂模式 (Factory Pattern)**：通过 `ProtocolFactory` 抽象底层协议实现（SFTP/FTP/LOCAL），解耦业务层与物理协议。
- **责任链模式 (Chain of Responsibility)**：
    - **H5 端**：`ApiClient` 拦截器链，处理鉴权注入与全局错误监控。
    - **Android 端**：`AuthInterceptor` 自动注入元数据头，精简业务代码。
- **切面审计 (AOP/Decorator)**：通过 `@AuditAction` 注解实现零侵入审计日志采集。
- **观察者模式 (Observer)**：全局 `VisitBus` 实现 H5 组件间的松耦合通信。

### 2.2 网络与协议层
- **隧道加密**：基于 JSch 的 SSH2 协议。
- **业务加密**：全链路 AES-GCM 动态加密。
- **终端解析**：Android 原生 `AnsiParser` 实现 ANSI 颜色高亮还原，具备 2000 行内存滑动窗口防御能力。

---

## 4. 关键文件索引 (Key Files)

- **后端核心**：
    - `StyVisitConstants.java`：业务语义唯一事实来源。
    - `AuditAspect.java`：自动化审计中枢。
    - `ProtocolFactory.java`：协议实例化工厂。
- **Android 核心**：
    - `AppContainer.kt`：轻量级 DI 容器。
    - `AnsiParser.kt`：终端颜色解析中枢。
    - `NetworkModule.kt`：拦截器驱动的网络层。
- **H5 核心**：
    - `config.js`：配置驱动中心。
    - `api-client.js`：责任链拦截网络库。

---

## 5. 当前状态 (Current Status)
- **v1.0 精细化重构版**：全栈已达成**零硬编码**，核心逻辑全部由模式驱动，具备极高的可维护性与生产环境健壮性。

> [!IMPORTANT]
> **开发规范**：所有新功能必须继承 `StyVisitConstants` 语义，Controller 禁止手动写审计逻辑（使用注解），前端请求必须通过 `ApiClient` 拦截器。
