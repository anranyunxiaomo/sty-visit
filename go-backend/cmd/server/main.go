package main

import (
	"flag"
	"fmt"
	"log"

	"sty-visit/internal/api"
	"sty-visit/internal/config"
)

func main() {
	var configPath string
	flag.StringVar(&configPath, "c", "config.yaml", "Path to the configuration file")
	flag.Parse()

	log.Println("🚀 正在启动 天穹 (sty-visit) Golang 重构版...")

	// 1. 初始化配置
	config.InitConfig(configPath)

	// 2. 初始化路由
	router := api.SetupRouter()

	// 3. 启动 HTTP 服务
	port := config.AppConfig.Server.Port
	if port == 0 {
		port = 8080 // 默认端口
	}

	addr := fmt.Sprintf(":%d", port)
	log.Printf("✅ 服务已启动，监听端口: %s", addr)
	
	if err := router.Run(addr); err != nil {
		log.Fatalf("❌ 服务启动失败: %v", err)
	}
}
