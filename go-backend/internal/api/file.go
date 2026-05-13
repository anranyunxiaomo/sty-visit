package api

import (
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"sty-visit/internal/ssh"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/pkg/sftp"
)

type FileInfo struct {
	Name         string `json:"name"`
	Path         string `json:"path"`
	IsDirectory  bool   `json:"isDirectory"`
	Size         int64  `json:"size"`
	LastModified int64  `json:"lastModified"`
	Permissions  string `json:"permissions"`
}

type TokenInfo struct {
	Path       string
	SessionKey string
	Expiry     time.Time
}

var (
	downloadTokenCache = make(map[string]TokenInfo)
	tokenMu            sync.RWMutex
)

func init() {
	go func() {
		for {
			time.Sleep(10 * time.Minute)
			tokenMu.Lock()
			now := time.Now()
			for k, v := range downloadTokenCache {
				if now.After(v.Expiry) {
					delete(downloadTokenCache, k)
				}
			}
			tokenMu.Unlock()
		}
	}()
}

// 获取 sftp 客户端辅助函数
func getSftpClient(c *gin.Context) (*sftp.Client, error) {
	client, err := ssh.GetClient(c)
	if err != nil {
		return nil, err
	}
	return sftp.NewClient(client)
}

// GetPwd 获取当前工作目录
func GetPwd(c *gin.Context) {
	sftpClient, err := getSftpClient(c)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": "SSH 会话失败", "data": nil})
		return
	}
	defer sftpClient.Close()

	pwd, err := sftpClient.Getwd()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": err.Error(), "data": nil})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"msg":  "success",
		"data": map[string]string{"path": pwd},
	})
}

// ListFiles 列出目录文件
func ListFiles(c *gin.Context) {
	path := c.Query("path")

	sftpClient, err := getSftpClient(c)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": "SSH 会话失败", "data": nil})
		return
	}
	defer sftpClient.Close()

	if path == "" {
		path, _ = sftpClient.Getwd()
	}

	files, err := sftpClient.ReadDir(path)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "读取目录失败: " + err.Error(), "data": nil})
		return
	}

	var result []FileInfo
	for _, f := range files {
		// 跳过 . 和 .. (按需，sftp 通常不返回这两者)
		result = append(result, FileInfo{
			Name:         f.Name(),
			Path:         filepath.Join(path, f.Name()),
			IsDirectory:  f.IsDir(),
			Size:         f.Size(),
			LastModified: f.ModTime().UnixMilli(),
			Permissions:  f.Mode().String(),
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"msg":  "success",
		"data": result,
	})
}

// GetContent 读取文件内容
func GetContent(c *gin.Context) {
	path := c.Query("path")

	sftpClient, err := getSftpClient(c)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": "SSH 会话失败", "data": nil})
		return
	}
	defer sftpClient.Close()

	file, err := sftpClient.Open(path)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "打开文件失败: " + err.Error(), "data": nil})
		return
	}
	defer file.Close()

	// 注意：此处对大文件直接读取到内存可能导致 OOM，原版 Java 是直接读成 String
	// 实际生产中应限制大小
	content, err := io.ReadAll(io.LimitReader(file, 10*1024*1024)) // 限制最大 10MB
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "读取文件失败: " + err.Error(), "data": nil})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"msg":  "success",
		"data": string(content),
	})
}

// SaveContent 保存文件内容
func SaveContent(c *gin.Context) {
	var body struct {
		Path    string `json:"path"`
		Content string `json:"content"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "msg": "参数错误", "data": nil})
		return
	}

	sftpClient, err := getSftpClient(c)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": "SSH 会话失败", "data": nil})
		return
	}
	defer sftpClient.Close()

	file, err := sftpClient.OpenFile(body.Path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "打开文件失败: " + err.Error(), "data": nil})
		return
	}
	defer file.Close()

	if _, err := file.Write([]byte(body.Content)); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "保存失败: " + err.Error(), "data": nil})
		return
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "msg": "保存成功", "data": nil})
}

// DeleteFile 删除文件或目录
func DeleteFile(c *gin.Context) {
	path := c.Query("path")
	if path == "" {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "msg": "路径不能为空", "data": nil})
		return
	}

	sftpClient, err := getSftpClient(c)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": "SSH 会话失败", "data": nil})
		return
	}
	defer sftpClient.Close()

	stat, err := sftpClient.Stat(path)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "文件不存在或无权限", "data": nil})
		return
	}

	if stat.IsDir() {
		err = sftpClient.RemoveDirectory(path)
	} else {
		err = sftpClient.Remove(path)
	}

	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "删除失败: " + err.Error(), "data": nil})
		return
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "msg": "删除成功", "data": nil})
}

// UploadFile 上传文件
func UploadFile(c *gin.Context) {
	path := c.PostForm("path")
	file, err := c.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "msg": "上传文件缺失", "data": nil})
		return
	}

	sftpClient, err := getSftpClient(c)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": "SSH 会话失败", "data": nil})
		return
	}
	defer sftpClient.Close()

	remotePath := path
	if !strings.HasSuffix(remotePath, "/") {
		remotePath += "/"
	}
	remotePath += file.Filename

	dstFile, err := sftpClient.Create(remotePath)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "无法在服务器创建文件: " + err.Error(), "data": nil})
		return
	}
	defer dstFile.Close()

	srcFile, err := file.Open()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "读取上传文件失败: " + err.Error(), "data": nil})
		return
	}
	defer srcFile.Close()

	if _, err := io.Copy(dstFile, srcFile); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "传输失败: " + err.Error(), "data": nil})
		return
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "msg": "上传成功", "data": nil})
}

// GetDownloadToken 获取下载令牌
func GetDownloadToken(c *gin.Context) {
	path := c.Query("path")
	if path == "" {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "msg": "路径不能为空", "data": nil})
		return
	}

	token := uuid.New().String()
	sessionKey := ssh.ExtractSessionKey(c)

	tokenMu.Lock()
	downloadTokenCache[token] = TokenInfo{
		Path:       path,
		SessionKey: sessionKey,
		Expiry:     time.Now().Add(1 * time.Minute),
	}
	tokenMu.Unlock()

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"msg":  "success",
		"data": map[string]string{"token": token},
	})
}

// DownloadFile 通过令牌下载文件
func DownloadFile(c *gin.Context) {
	token := c.Query("token")

	tokenMu.RLock()
	info, exists := downloadTokenCache[token]
	tokenMu.RUnlock()

	if !exists || time.Now().After(info.Expiry) {
		c.String(http.StatusForbidden, "下载链接已失效")
		return
	}

	client := ssh.GetClientByKey(info.SessionKey)
	if client == nil {
		c.String(http.StatusUnauthorized, "SSH 会话已断开")
		return
	}

	sftpClient, err := sftp.NewClient(client)
	if err != nil {
		c.String(http.StatusInternalServerError, "SFTP 隧道失败")
		return
	}
	defer sftpClient.Close()

	file, err := sftpClient.Open(info.Path)
	if err != nil {
		c.String(http.StatusInternalServerError, "文件打开失败")
		return
	}
	defer file.Close()

	// 清理令牌
	tokenMu.Lock()
	delete(downloadTokenCache, token)
	tokenMu.Unlock()

	filename := filepath.Base(info.Path)
	encodedName := url.QueryEscape(filename)
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", encodedName))
	c.Header("Content-Type", "application/octet-stream")

	io.Copy(c.Writer, file)
}
