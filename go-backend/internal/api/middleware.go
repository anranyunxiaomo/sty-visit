package api

import (
	"fmt"
	"net/http"
	"strings"

	"sty-visit/internal/config"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// StealthModeMiddleware 核心隐身伪装层
func StealthModeMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		uri := c.Request.URL.Path
		// 拦截根路径及前端静态资源访问
		if uri == "/" || uri == "/index.html" {
			if !State.IsH5Enabled() {
				// 模拟逼真的 Tomcat 404 伪装页面
				c.Data(http.StatusNotFound, "text/html;charset=UTF-8", []byte(`<!doctype html><html lang="en"><head><title>HTTP Status 404 – Not Found</title><style type="text/css">body {font-family:Tahoma,Arial,sans-serif;} h1, h2, h3, b {color:white;background-color:#525D76;} h1 {font-size:22px;} h2 {font-size:16px;} h3 {font-size:14px;} p {font-size:12px;} a {color:black;} .line {height:1px;background-color:#525D76;border:none;}</style></head><body><h1>HTTP Status 404 – Not Found</h1><hr class="line" /><p><b>Type</b> Status Report</p><p><b>Message</b> Not Found</p><p><b>Description</b> The origin server did not find a current representation for the target resource or is not willing to disclose that one exists.</p><hr class="line" /><h3>Apache Tomcat/9.0.83</h3></body></html>`))
				c.Abort()
				return
			}
		}
		c.Next()
	}
}

// AuthMiddleware JWT 鉴权拦截器
func AuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		tokenString := c.GetHeader("Authorization")
		if tokenString == "" {
			tokenString = c.Query("token") // 兼容老版本 WebSocket Query 传参
		}
		if tokenString == "" {
			protocols := c.GetHeader("Sec-WebSocket-Protocol")
			for _, p := range strings.Split(protocols, ",") {
				p = strings.TrimSpace(p)
				if strings.HasPrefix(p, "visit-auth.") {
					tokenString = strings.TrimPrefix(p, "visit-auth.")
					break
				}
			}
		}
		
		if tokenString == "" {
			c.JSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": "未提供授权凭证", "data": nil})
			c.Abort()
			return
		}

		if strings.HasPrefix(tokenString, "Bearer ") {
			tokenString = tokenString[7:]
		}

		token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
			}
			return []byte(config.AppConfig.Auth.JwtSecret), nil
		})

		if err != nil || !token.Valid {
			c.JSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": "凭证无效或已过期", "data": nil})
			c.Abort()
			return
		}

		c.Next()
	}
}

// AuditLogMiddleware 全局操作审计拦截器
func AuditLogMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		// 先执行业务逻辑
		c.Next()

		// 只有响应成功的操作类请求才记录
		if c.Writer.Status() >= 200 && c.Writer.Status() < 300 {
			method := c.Request.Method
			path := c.Request.URL.Path

			action := ""
			switch {
			case path == "/api/auth/login": return // 登录日志可由具体的 Login 函数控制
			case path == "/api/auth/logout": action = "系统登出"
			case path == "/api/config/h5/toggle": action = "切换前端访问状态"
			case path == "/api/file/save" && method == "POST": action = "修改并保存文件"
			case path == "/api/file" && method == "DELETE": action = "删除服务器文件"
			case path == "/api/file/upload" && method == "POST": action = "上传文件至服务器"
			case path == "/api/file/token" && method == "GET": action = "请求下载服务器文件"
			case path == "/api/monitor/kill" && method == "POST": action = "强制结束系统进程"
			case path == "/api/docker/control" && method == "POST": action = "变更 Docker 容器状态"
			case path == "/api/bookmarks" && method == "POST": action = "添加/修改服务器书签"
			case path == "/api/bookmarks" && method == "DELETE": action = "删除服务器书签"
			case path == "/api/snippets" && method == "POST": action = "更新全局指令库"
			case path == "/api/transfers" && method == "DELETE": action = "清空文件传输历史记录"
			case path == "/api/batch-execute" && method == "POST": action = "下发集群批量运维指令"
			case path == "/api/ssh/inspect" && method == "POST": return // 获取系统信息，属于静默高频读取，忽略审计记录
			default:
				// 其他未专门定义的写操作
				if method == "POST" || method == "PUT" || method == "DELETE" {
					if path == "/api/transfers" { return } // 忽略极高频的传输进度上报
					action = fmt.Sprintf("执行操作: %s", path)
				}
			}

			if action != "" {
				detail := fmt.Sprintf("%s | 接口: %s | SUCCESS", action, path)
				writeAuditLog(c.ClientIP(), detail)
			}
		}
	}
}
