package api

import (
	"net/http"

	"sty-visit/internal/config"

	"github.com/gin-gonic/gin"
)

// GetHosts 返回跳板机管理的所有节点列表
func GetHosts(c *gin.Context) {
	nodes := config.AppConfig.SSH.Nodes

	// 如果没有配置 nodes，返回默认的一台保底主机
	if len(nodes) == 0 {
		nodes = []config.NodeConfig{
			{
				ID:   "default-node",
				Name: "默认主机",
				Host: config.AppConfig.SSH.Host,
				Port: config.AppConfig.SSH.Port,
				User: "root", // 默认假设，或者前端提供
			},
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"msg":  "success",
		"data": nodes,
	})
}
