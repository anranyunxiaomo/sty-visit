# 天穹 官方产品说明与操作指南 (v1.0 Stable)

<p align="center">
  <img src="assets/logo.png" width="200" alt="天穹 (sty-visit) Logo">
</p>

> **工业级、极简式、单文件部署的远程运维管理平台**

天穹 是一款专为运维工程师打造的、“端云一体”的移动级 Linux 堡垒机。经历 v1.0 的全面架构重构后，现已基于 **Golang** 实现底层协议彻底自研，彻底抛弃了 Java/Docker 环境依赖，为您带来**零依赖、秒级启动、跨架构通杀**的终极部署体验。

---

## 1. 产品核心定位与功能 (Product Features)

### 1.1 极简接入与单文件打包 (Zero-Dependency)
- **单二进制封装**：前端的所有 Vue 页面、静态资源、xterm 终端引擎全部通过 `go:embed` 技术硬编码进了唯一的内核文件中，实现防篡改与极简分发。
- **跨 CPU 架构支持**：官方发行包同时包含 AMD64 与 ARM64（鲲鹏、飞腾等）内核，配合智能 `start.sh` 脚本自动检测拉起。
- **物理隐身模式 (Stealth Mode)**：独创 H5 管理面板开关。关闭开关后，任何外部扫描与越权访问均会直接返回 404 Not Found 页面。

### 1.2 高性能并发终端 (Terminal & Batch)
- **高性能 WebSocket 流缓冲**：彻底重写的底层帧合并机制，完美应对 `tree /` 等极端 IO 刷屏操作，前端不卡死、内存不溢出。
- **全彩 ANSI 终端 (SSH)**：完整支持 ANSI 转义序列、16/256色，原生兼容 `top`, `htop`, `vim`。
- **批量并发指令引擎**：支持在一组（几十上百台）目标服务器中并发执行相同的运维命令，结果实时聚合显示。

### 1.3 文件流转与审计追踪 (File & Audit)
- **可视化 SFTP 引擎**：支持全盘目录漫游浏览，多层级穿透与文件增删改查。
- **双端传输监控**：传输日志与任务进度持久化，方便随时断点追溯。
- **全量行为审计录像**：详尽记录所有登录请求、文件变动、连接断开等行为。录像文件自动按配置天数轮转清理。

---

## 2. 部署与安装指南 (Deployment & Installation)

请获取最新的 **`sty-visit-universal-linux.zip`** 发行包。

### 第 1 步：上传至服务器并解压
将安装包上传至您的 Linux 服务器（如 `/opt/sty-visit/` 目录）并解压。

### 第 2 步：修改外部配置文件
使用任意文本编辑器打开 `config.yaml`。
- `server.port`: 服务运行端口（默认 8080）
- `auth.admin_password`: 控制台登录密码（默认 `StyVisit@2026`，**务必修改！**）

### 第 3 步：智能启动
您无需关心 CPU 架构，直接赋予权限并执行 `start.sh`：
```bash
chmod +x start.sh
nohup ./start.sh -c /etc/sty-visit/config.yaml > /var/log/sty-visit.log 2>&1 &
```

### 第 4 步：访问系统
浏览器访问 `http://您的服务器IP:8080`，输入密码即可进入堡垒机管理后台。

---

## 3. 日志与数据存储解析 (Data Persistence)

系统运行时会产生以下三类数据，请注意备份：
1. **业务状态数据**：受 `config.yaml` 中 `config_dir` 控制。存放 `bookmarks.json`、`snippets.json`。
2. **操作审计录像**：存放在同级目录下的 `audit/` 文件夹中。
3. **程序运行报错日志**：受启动重定向命令 `> server.log` 控制。

---
*天穹: 为运维而生，为安全而战。 (C) 2026 Antigravity Engineering*
