package api

import (
	"net/http"
	"strings"

	sshManager "sty-visit/internal/ssh"

	"github.com/gin-gonic/gin"
)

// RemoteInspect 对目标机器执行一次轻量级探针
func RemoteInspect(c *gin.Context) {
	client, err := sshManager.GetClient(c)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "无法连接服务器: " + err.Error(), "data": nil})
		return
	}

	session, err := client.NewSession()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "获取Session失败: " + err.Error(), "data": nil})
		return
	}
	defer session.Close()

	// 拼装复合指令
	// free 命令仅适用于 Linux，如果目标是 macOS 则 fallback 处理
	script := `
if [ -f /etc/os-release ]; then
	. /etc/os-release
	echo "OS|${PRETTY_NAME} ($(uname -r))"
else
	echo "OS|$(uname -srm)"
fi
if command -v free >/dev/null 2>&1; then
	echo "MEM|$(free -m | awk 'NR==2{print $2","$3}')"
else
	echo "MEM|N/A,N/A"
fi
echo "UPTIME|$(uptime -p 2>/dev/null || uptime)"
echo "SERVICES|$(ps -e -o comm= 2>/dev/null | grep -E 'java|mysqld|nginx|dockerd|redis-server' | sort -u | xargs || echo 'N/A')"
`
	out, err := session.CombinedOutput(script)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "探针执行失败: " + err.Error(), "data": nil})
		return
	}

	output := string(out)
	result := map[string]string{
		"os":       "Unknown",
		"memTotal": "0",
		"memUsed":  "0",
		"uptime":   "Unknown",
		"services": "",
	}

	lines := strings.Split(output, "\n")
	for _, line := range lines {
		parts := strings.SplitN(line, "|", 2)
		if len(parts) == 2 {
			k := parts[0]
			v := strings.TrimSpace(parts[1])
			switch k {
			case "OS":
				result["os"] = v
			case "MEM":
				memParts := strings.Split(v, ",")
				if len(memParts) == 2 {
					result["memTotal"] = memParts[0]
					result["memUsed"] = memParts[1]
				}
			case "UPTIME":
				result["uptime"] = strings.TrimPrefix(v, "up ")
			case "SERVICES":
				result["services"] = v
			}
		}
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "msg": "success", "data": result})
}
