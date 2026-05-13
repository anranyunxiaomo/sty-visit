package api

import (
	"encoding/base64"
	"fmt"
	"net/http"
	"strings"
	"sync"

	"github.com/gin-gonic/gin"
	"golang.org/x/crypto/ssh"
	sshManager "sty-visit/internal/ssh"
)

type BatchServer struct {
	Host  string `json:"host"`
	Port  string `json:"port"`
	User  string `json:"user"`
	Pwd   string `json:"pwd"`
	IsKey bool   `json:"isKey"`
}

type BatchExecuteReq struct {
	Servers  []BatchServer `json:"servers"`
	Command  string        `json:"command"`
	BurnMode bool          `json:"burnMode"`
}

type BatchResult struct {
	Host   string `json:"host"`
	Status string `json:"status"`
	Output string `json:"output"`
}

func BatchExecute(c *gin.Context) {
	var req BatchExecuteReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "无效的请求参数"})
		return
	}

	if len(req.Servers) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "服务器列表不能为空"})
		return
	}

	if req.Command == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "执行命令不能为空"})
		return
	}

	var wg sync.WaitGroup
	var mu sync.Mutex
	results := make([]BatchResult, 0, len(req.Servers))

	for _, s := range req.Servers {
		wg.Add(1)
		go func(server BatchServer) {
			defer wg.Done()
			
			res := BatchResult{
				Host: fmt.Sprintf("%s@%s:%s", server.User, server.Host, server.Port),
			}

			// 解析密码/密钥 (Base64)
			pwdStr := server.Pwd
			if decodedBytes, err := base64.StdEncoding.DecodeString(pwdStr); err == nil {
				pwdStr = string(decodedBytes)
			}

			// 如果是密钥但是传递在 Pwd 字段，也兼容处理
			if strings.HasPrefix(pwdStr, "-----BEGIN") {
				server.IsKey = true
			}

			var sshClient *ssh.Client
			var err error

			if req.BurnMode {
				// 即焚模式：不使用连接池，执行后关闭
				if server.IsKey {
					sshClient, err = sshManager.GetClientOneOff(server.Host, server.Port, server.User, "", pwdStr)
				} else {
					sshClient, err = sshManager.GetClientOneOff(server.Host, server.Port, server.User, pwdStr, "")
				}
				if sshClient != nil {
					defer sshClient.Close() // 执行完当前协程即释放 TCP
				}
			} else {
				// 默认复用模式
				if server.IsKey {
					sshClient, err = sshManager.GetClientRaw(server.Host, server.Port, server.User, "", pwdStr)
				} else {
					sshClient, err = sshManager.GetClientRaw(server.Host, server.Port, server.User, pwdStr, "")
				}
			}

			if err != nil {
				res.Status = "失败"
				res.Output = fmt.Sprintf("连接失败: %v", err)
				
				mu.Lock()
				results = append(results, res)
				mu.Unlock()
				return
			}

			session, err := sshClient.NewSession()
			if err != nil {
				res.Status = "失败"
				res.Output = fmt.Sprintf("创建会话失败: %v", err)
				
				// 若失败，尝试从连接池中剔除
				if server.IsKey {
					sshManager.RemoveClientRaw(server.Host, server.Port, server.User, "", pwdStr)
				} else {
					sshManager.RemoveClientRaw(server.Host, server.Port, server.User, pwdStr, "")
				}
				
				mu.Lock()
				results = append(results, res)
				mu.Unlock()
				return
			}
			defer session.Close()

			// 简化为最直接的 bash -c 调用，并内置常用别名
			// 使用 eval 确保别名在解析期生效
			cmd := fmt.Sprintf("shopt -s expand_aliases 2>/dev/null; alias ll='ls -l --color=auto' 2>/dev/null; source /etc/profile 2>/dev/null; source ~/.bashrc 2>/dev/null; eval %q", req.Command)
			wrappedCmd := fmt.Sprintf("bash -c %q", cmd)
			
			output, err := session.CombinedOutput(wrappedCmd)
			outStr := string(output)
			
			// 简单过滤
			outStr = strings.ReplaceAll(outStr, "bash: cannot set terminal process group", "")
			outStr = strings.ReplaceAll(outStr, "bash: no job control in this shell", "")
			outStr = strings.ReplaceAll(outStr, "Inappropriate ioctl for device", "")
			outStr = strings.TrimSpace(outStr)

			if outStr == "" {
				if err != nil {
					res.Output = fmt.Sprintf("(无回显，错误: %v)", err)
				} else {
					res.Output = "(执行成功，但无任何输出内容)"
				}
			} else {
				res.Output = outStr
			}
			res.Status = "执行完毕"

			mu.Lock()
			results = append(results, res)
			mu.Unlock()
		}(s)
	}

	wg.Wait()

	successCount := 0
	for _, r := range results {
		if r.Status == "成功" || r.Status == "执行完毕" {
			successCount++
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"message": "执行完毕",
		"total":   len(req.Servers),
		"success": successCount,
		"fail":    len(req.Servers) - successCount,
		"results": results,
	})
}
