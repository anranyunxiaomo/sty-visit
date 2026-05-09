# 天穹 - 全栈架构技术规格说明书 (v5.1)

## 1. 总体设计理念
本项目遵循 **"端云一体、模式驱动、零硬编码"** 的核心原则。通过在全栈各个维度引入成熟的设计模式，实现了业务逻辑与底层物理实现（协议、网络、UI）的深度解耦，确保了系统在极端环境下的鲁棒性与安全性。

---

## 2. 后端架构规格 (Java/Spring Boot)

### 2.1 核心设计模式
| 模式名称 | 实现载体 | 核心价值 |
| :--- | :--- | :--- |
| **工厂模式 (Factory)** | `ProtocolFactory` | 屏蔽 SFTP/FTP/LOCAL 实例化细节，支持协议无感切换。 |
| **切面审计 (AOP)** | `AuditAspect` + `@AuditAction` | 零侵入采集业务日志，将审计代码与业务代码物理隔离。 |
| **建造者 (Builder)** | `AuditEntry.Builder` | 提供语义化的流式构建接口，确保日志对象的精细化。 |
| **单例模式 (Singleton)** | `SshSessionManager` | 全局管控 SSH 会话生命周期，实现资源高效复用。 |

### 2.2 业务标准化
- **语义中枢**：`StyVisitConstants.java` 定义了全系统唯一的动作标识（Actions）与状态码（Status）。
- **Result 协议**：全接口采用 `Result<T>` 统一包装，确保前后端数据交换的契约一致性。

---

## 3. Android 原生端规格 (Kotlin)

### 3.1 渲染与解析
- **AnsiParser**：具备 ANSI 转义字符物理还原能力，支持多色样式展示。
- **内存保护**：终端适配器采用 2000 行滑动窗口防御，防止超长会话导致 OOM。

### 3.2 架构模式
- **轻量级 DI**：通过 `AppContainer` 统一管理 `ApiService`、`SessionManager` 等单例服务。
- **拦截器模式 (Chain of Responsibility)**：`AuthInterceptor` 自动注入元数据头，移除业务代码中的重复请求配置。

---

## 4. H5 前端规格 (Vue/JS)

### 4.1 网络通讯
- **责任链拦截**：`ApiClient` 支持 `request`/`response` 双向拦截，用于统一注入 Auth Token 和全局异常捕获。
- **配置驱动**：`config.js` (`VisitConfig`) 集中管理所有 Endpoints 和 Storage Keys，实现环境快速切换。

### 4.2 组件通信
- **事件总线 (Observer)**：`VisitBus` 作为中枢，处理终端刷新、监控告警等跨组件交互，降低模块耦合。

---

## 5. 安全防御体系

### 5.1 数据保护
- **全链路加密**：业务流量经 AES-GCM 高强度加密，隧道流量经 SSH2 二次封装。
- **隐私归零**：Android 原生层支持 `hardLogout` 接口，物理抹除 WebView 缓存及 LocalStorage 痕迹。

### 5.2 审计与监控
- **结构化审计**：基于 AOP 的自动审计确保了每一个动作（Login, Save, Delete）都有迹可循。
- **性能监控**：实时拉取 CPU/MEM/Load 指标，并具备自动风险提级能力。

---

## 6. 维护守则
1. **禁止硬编码**：所有业务动作必须注册至 `StyVisitConstants` 或 `VisitConfig`。
2. **注解驱动**：新控制器方法必须配合 `@AuditAction` 进行审计，配合 `@RequiresSshSession` 进行鉴权。
3. **模式继承**：新增协议实现必须继承 `IRemoteProtocol` 并注册至 `ProtocolFactory`。

---
*天穹 - 构建坚不可摧的移动运维中枢*
