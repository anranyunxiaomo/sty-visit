#!/bin/bash

# 获取脚本所在绝对目录并切换到该目录，确保在 release_v5.0_stable 下执行时能正确读取到同级业务配置
cd "$(dirname "$0")" || exit 1

# Visit Manager Pro 一键启动脚本

echo "------------------------------------------------"
echo "   Visit Manager Pro - 全功能运维面板启动程序   "
echo "------------------------------------------------"

# 1. 配置文件权限锁定 (运维安全建议)
if [ -f "application.yml" ]; then
    chmod 600 application.yml
fi

# 2. 运行项目 (自动检测 Jar 包路径)
if [ -f "target/visit-manager-1.3.12.jar" ]; then
    JAR_FILE="target/visit-manager-1.3.12.jar"
elif [ -f "visit-manager-1.3.12.jar" ]; then
    JAR_FILE="visit-manager-1.3.12.jar"
elif [ -f "visit-manager.jar" ]; then
    JAR_FILE="visit-manager.jar"
else
    echo "[!] 启动失败: 未检测到运行时 Jar 包。"
    exit 1
fi

echo "[*] Visit Manager Pro 正在启动 ($JAR_FILE)..."
java -Xmx1024m -Xms512m \
     -Djava.security.egd=file:/dev/./urandom \
     -jar "$JAR_FILE" --spring.config.location=file:./application.yml
