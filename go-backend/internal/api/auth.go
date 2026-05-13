package api

import (
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"sync"
	"time"

	"sty-visit/internal/config"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

var (
	failureCount    = make(map[string]int)
	lastAttemptTime = make(map[string]int64)
	lockoutTime     = make(map[string]int64)
	authMu          sync.Mutex
)

// GenerateSessionKey 生成随机的会话秘钥
func GenerateSessionKey() string {
	bytes := make([]byte, 32) // 256 bits
	if _, err := rand.Read(bytes); err != nil {
		return ""
	}
	return hex.EncodeToString(bytes)
}

// Login 管理员登录
func Login(c *gin.Context) {
	var body struct {
		Password string `json:"password"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "msg": "参数错误", "data": nil})
		return
	}

	ip := c.ClientIP()

	authMu.Lock()
	defer authMu.Unlock()

	now := time.Now().UnixMilli()

	// 检查锁定
	if lockTime, exists := lockoutTime[ip]; exists {
		if now-lockTime < 5*60*1000 {
			c.JSON(http.StatusTooManyRequests, gin.H{"code": 429, "msg": "尝试次数过多，IP已被锁定。请稍后再试。", "data": nil})
			return
		}
		delete(lockoutTime, ip)
		delete(failureCount, ip)
	}

	if body.Password == config.AppConfig.Auth.AdminPassword {
		delete(failureCount, ip)

		// 签发 JWT
		token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
			"sub": "admin",
			"exp": time.Now().Add(24 * time.Hour).Unix(),
		})
		tokenString, _ := token.SignedString([]byte(config.AppConfig.Auth.JwtSecret))

		sessionKey := GenerateSessionKey()

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"msg":  "success",
			"data": map[string]string{
				"token":      tokenString,
				"sessionKey": sessionKey,
			},
		})
		writeAuditLog(ip, "系统登录 | 密码验证 | SUCCESS")
	} else {
		attempts := failureCount[ip] + 1
		failureCount[ip] = attempts
		lastAttemptTime[ip] = now

		if attempts >= 5 {
			lockoutTime[ip] = now
		}
		c.JSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": "无效的管理密码", "data": nil})
		writeAuditLog(ip, "系统登录 | 密码验证 | FAIL")
	}
}

// Check 检查 Token 有效性
func Check(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"code": 200, "msg": "OK", "data": "OK"})
}

// Logout 退出登录
func Logout(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"code": 200, "msg": "注销成功", "data": nil})
}

// GetH5Status 获取H5隐身状态
func GetH5Status(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"msg":  "success",
		"data": map[string]interface{}{
			"enabled": State.IsH5Enabled(),
		},
	})
}

// ToggleH5 切换H5隐身状态
func ToggleH5(c *gin.Context) {
	var body struct {
		Enabled *bool `json:"enabled"`
	}
	if err := c.ShouldBindJSON(&body); err != nil || body.Enabled == nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "msg": "参数错误", "data": nil})
		return
	}

	State.SetH5Enabled(*body.Enabled)
	c.JSON(http.StatusOK, gin.H{"code": 200, "msg": "Policy Updated", "data": nil})
}
