package api

import (
	"io/fs"
	"net/http"

	"github.com/gin-gonic/gin"

	"sty-visit/static"
)

// SetupRouter 初始化 Gin 路由
func SetupRouter() *gin.Engine {
	// 设置为 Release 模式以减少日志刷屏
	gin.SetMode(gin.ReleaseMode)

	r := gin.New()
	r.Use(gin.Recovery())

	// 1. 注册核心隐身伪装层 (必须在最外层)
	r.Use(StealthModeMiddleware())

	// 开放 API (不需要 Token)
	api := r.Group("/api")
	{
		api.POST("/auth/login", Login)
	}

	// 注册静态资源路由 (放在 StealthModeMiddleware 之后，确保前端也被隐身保护)
	// 使用 Go 1.16+ 原生 embed 特性，彻底实现单文件打包部署
	libFS, _ := fs.Sub(static.FS, "lib")
	r.StaticFS("/lib", http.FS(libFS))

	jsFS, _ := fs.Sub(static.FS, "js")
	r.StaticFS("/js", http.FS(jsFS))

	serveRootFile := func(filename, contentType string) gin.HandlerFunc {
		return func(c *gin.Context) {
			file, err := static.FS.ReadFile(filename)
			if err != nil {
				c.Status(404)
				return
			}
			c.Data(200, contentType, file)
		}
	}

	r.GET("/apple-ui.css", serveRootFile("apple-ui.css", "text/css; charset=utf-8"))
	r.GET("/favicon.ico", serveRootFile("favicon.ico", "image/x-icon"))
	r.GET("/logo.png", serveRootFile("logo.png", "image/png"))

	noCacheHandler := func(c *gin.Context) {
		c.Header("Cache-Control", "no-cache, no-store, must-revalidate")
		c.Header("Pragma", "no-cache")
		c.Header("Expires", "0")
		file, err := static.FS.ReadFile("index.html")
		if err != nil {
			c.String(500, "Embedded index.html not found")
			return
		}
		c.Data(200, "text/html; charset=utf-8", file)
	}
	r.GET("/", noCacheHandler)
	r.GET("/index.html", noCacheHandler)

	// 保护 API (需要 Token)
	authAPI := r.Group("/api")
	authAPI.Use(AuthMiddleware(), AuditLogMiddleware())
	{
		// Auth
		authAPI.GET("/auth/check", Check)
		authAPI.POST("/auth/logout", Logout)

		// Config
		authAPI.GET("/config/h5/status", GetH5Status)
		authAPI.POST("/config/h5/toggle", ToggleH5)
		authAPI.GET("/config/audit/retention", GetAuditRetention)
		authAPI.POST("/config/audit/retention", UpdateAuditRetention)

		// Audit
		authAPI.GET("/audit/logs", GetAuditLogs)

		// File
		authAPI.GET("/file/pwd", GetPwd)
		authAPI.GET("/file/list", ListFiles)
		authAPI.GET("/file/content", GetContent)
		authAPI.POST("/file/save", SaveContent)
		authAPI.DELETE("/file", DeleteFile)
		authAPI.POST("/file/upload", UploadFile)
		authAPI.GET("/file/token", GetDownloadToken)

		// Remote Inspect
		authAPI.POST("/ssh/inspect", RemoteInspect)

		// Docker
		authAPI.GET("/docker/containers", GetContainers)
		authAPI.POST("/docker/control", ControlContainer)
		authAPI.GET("/docker/logs", GetContainerLogs)

		// Bookmarks
		authAPI.GET("/bookmarks", GetBookmarks)
		authAPI.POST("/bookmarks", SaveBookmark)
		authAPI.DELETE("/bookmarks", DeleteBookmark)

		// Snippets
		authAPI.GET("/snippets", GetSnippets)
		authAPI.POST("/snippets", SaveSnippets)

		// Transfers
		authAPI.GET("/transfers", GetTransfers)
		authAPI.POST("/transfers", SaveTransfer)
		authAPI.DELETE("/transfers", ClearTransfers)

		// Batch Execute
		authAPI.POST("/batch-execute", BatchExecute)

		// Jump Server Nodes
		authAPI.GET("/hosts", GetHosts)

		// Diag
		authAPI.POST("/diag/report", ReportIssue)
	}

	// Download is not under authAPI because it uses a token query param and is fetched directly by the browser
	api.GET("/file/download", DownloadFile)

	// WebSocket 端点 (由于鉴权特殊性，可以单独处理，但在 Android 中可以直接发 Header)
	wsAPI := r.Group("/ws")
	wsAPI.Use(AuthMiddleware())
	{
		wsAPI.GET("/terminal", WebSocketTerminal)
		wsAPI.GET("/proxy", WebSocketProxy)
	}

	return r
}
