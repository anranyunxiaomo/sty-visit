#!/bin/bash

# 获取脚本所在绝对目录并切换到该目录，确保在 release_v5.0_stable 下执行时能正确读取到同级业务配置
cd "$(dirname "$0")" || exit 1

# 天穹 一键启动脚本

echo "------------------------------------------------"
echo "   天穹 - 全功能运维面板启动程序   "
echo "------------------------------------------------"

# 1. 配置文件权限锁定 (运维安全建议)
if [ -f "application.yml" ]; then
    chmod 600 application.yml
fi

# 2. 运行项目 (自动检测 Jar 包路径)
if [ -f "target/sty-visit-1.0.0.jar" ]; then
    JAR_FILE="target/sty-visit-1.0.0.jar"
elif [ -f "sty-visit-1.0.0.jar" ]; then
    JAR_FILE="sty-visit-1.0.0.jar"
elif [ -f "sty-visit.jar" ]; then
    JAR_FILE="sty-visit.jar"
else
    echo "[!] 启动失败: 未检测到运行时 Jar 包。"
    exit 1
fi

echo "[*] 天穹 正在启动 ($JAR_FILE)..."
java -Xmx1024m -Xms512m \
     -Djava.security.egd=file:/dev/./urandom \
     -jar "$JAR_FILE" --spring.config.location=file:./application.yml
