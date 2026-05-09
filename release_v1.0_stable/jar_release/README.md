# 天穹 (sty-visit v1.0) - Jar 直装版专属部署指南

欢迎使用天穹堡垒机。本包是 **传统 Jar 直装版**，适用于那些拥有基础 Java 运行环境（如 CentOS/Ubuntu 裸机）且不希望安装 Docker 的老牌服务器环境。

## 📦 包含的文件说明
- `sty-visit.jar`：天穹核心程序。
- `run.sh`：为您准备的一键后台守护进程启动脚本。
- `application.yml`：应用的全局配置文件。

---

## 🛠️ 1. 环境准备与配置修改
1. **环境检查**：请确保您的服务器已安装 `Java 8`（或更高版本的 JRE/JDK），可通过 `java -version` 确认。
2. **修改外部配置**：使用 `vim application.yml` 编辑配置文件，重点修改以下字段：
   - `server.port`: 系统对外开放的 Web 端口，默认 `8080`。
   - `styvisit.admin-password`: 您的最高权限管理员密钥（强烈建议修改为包含大小写和标点符号的 12 位密码）。

---

## 🚀 2. 启动与日志维护

1. 赋予启动脚本执行权限：
   ```bash
   chmod +x run.sh
   ```
2. 执行启动命令：
   ```bash
   ./run.sh
   ```
   *服务将在后台静默守护运行，终端不会卡住。*
   
3. **查看日志**：
   项目运行时产生的所有行为审计日志和错误栈都会输出到同级目录下自动生成的 `logs/sty-visit.log` 文件中。您可以使用以下命令动态追踪：
   ```bash
   tail -f logs/sty-visit.log
   ```

**安全建议**：为防止凭证泄露，建议部署完成后确保外置的 `application.yml` 文件权限为 `600`。
