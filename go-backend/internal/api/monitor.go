package api

import (
	"fmt"
	"net/http"
	"time"
	"strconv"
	"strings"
	"os"

	"github.com/gin-gonic/gin"
)

// writeAuditLog 独立写入审计日志文件
func writeAuditLog(ip, detail string) {
	os.MkdirAll("../config", 0755)
	f, err := os.OpenFile("../config/audit.log", os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err == nil {
		defer f.Close()
		timestamp := time.Now().Format("2006/01/02 15:04:05")
		f.WriteString(fmt.Sprintf("%s [AUDIT/DIAG] [%s] %s\n", timestamp, ip, detail))
	}
}

// ReportIssue 接收诊断信息
func ReportIssue(c *gin.Context) {
	var body struct {
		Type     string `json:"type"`
		Detail   string `json:"detail"`
		Platform string `json:"platform"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "msg": "Invalid params", "data": nil})
		return
	}

	if body.Type == "" { body.Type = "CLIENT_ERROR" }
	if body.Detail == "" { body.Detail = "unknown" }
	if body.Platform == "" { body.Platform = "UNKNOWN" }

	ip := c.ClientIP()
	
	// 在此处集成审计落盘
	writeAuditLog(ip, fmt.Sprintf("%s | %s | %s | SUCCESS", body.Type, body.Detail, body.Platform))

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"msg":  "success",
		"data": "Issue reported and audited.",
	})
}

func formatUptime(uptimeSeconds uint64) string {
	d := time.Duration(uptimeSeconds) * time.Second
	days := d / (24 * time.Hour)
	d -= days * 24 * time.Hour
	hours := d / time.Hour
	d -= hours * time.Hour
	minutes := d / time.Minute
	d -= minutes * time.Minute
	seconds := d / time.Second

	return fmt.Sprintf("%d天 %02d:%02d:%02d", days, hours, minutes, seconds)
}

var auditRetentionDays = 7

// GetAuditRetention 获取审计日志留存天数
func GetAuditRetention(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": auditRetentionDays})
}

// UpdateAuditRetention 更新审计日志留存天数
func UpdateAuditRetention(c *gin.Context) {
	daysStr := c.Query("days")
	days, err := strconv.Atoi(daysStr)
	if err == nil && days > 0 {
		auditRetentionDays = days
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": auditRetentionDays})
}

// GetAuditLogs 获取系统审计日志
func GetAuditLogs(c *gin.Context) {
	data, err := os.ReadFile("../config/audit.log")
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"code": 200, "data": []map[string]string{
			{"timestamp": time.Now().Format("2006/01/02 15:04:05"), "action": "系统提示", "detail": "暂无审计记录", "status": "暂无审计记录", "ip": "local"},
		}})
		return
	}

	lines := strings.Split(string(data), "\n")
	var logs []map[string]string

	// 倒序读取最后50条
	for i := len(lines) - 1; i >= 0; i-- {
		line := lines[i]
		if strings.Contains(line, "[AUDIT/DIAG]") {
			parts := strings.SplitN(line, "[AUDIT/DIAG]", 2)
			if len(parts) == 2 {
				timePart := strings.TrimSpace(parts[0])
				detailPart := strings.TrimSpace(parts[1])

				status := "未知"
				if strings.Contains(detailPart, "SUCCESS") || strings.Contains(detailPart, "success") || strings.Contains(detailPart, "已强制结束") {
					status = "成功"
				} else if strings.Contains(detailPart, "ERROR") || strings.Contains(detailPart, "FAIL") {
					status = "失败"
				}

				ip := "系统"
				fullAction := detailPart
				if strings.HasPrefix(detailPart, "[") {
					endIdx := strings.Index(detailPart, "]")
					if endIdx > 0 {
						ip = detailPart[1:endIdx]
						fullAction = strings.TrimSpace(detailPart[endIdx+1:])
					}
				}

				action := fullAction
				detail := fullAction
				actionParts := strings.SplitN(fullAction, "|", 2)
				if len(actionParts) > 0 {
					action = strings.TrimSpace(actionParts[0])
				}
				if len(actionParts) > 1 {
					detail = strings.TrimSpace(actionParts[1])
				}

				logs = append(logs, map[string]string{
					"timestamp": timePart,
					"action":    action,
					"detail":    detail,
					"status":    status,
					"ip":        ip,
				})
			}
		}
		if len(logs) >= 50 {
			break
		}
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": logs})
}
