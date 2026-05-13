package ssh

import (
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"strings"
	"sync"
	"time"

	"sty-visit/internal/config"

	"github.com/gin-gonic/gin"
	"golang.org/x/crypto/ssh"
)

type PooledClient struct {
	Client     *ssh.Client
	LastActive time.Time
}

var (
	pool   = make(map[string]*PooledClient)
	poolMu sync.RWMutex
)

func init() {
	// 启动收割器，每 1 分钟检查一次过期会话
	go func() {
		for {
			time.Sleep(1 * time.Minute)
			now := time.Now()
			poolMu.Lock()
			for k, v := range pool {
				if now.Sub(v.LastActive) > 10*time.Minute {
					v.Client.Close()
					delete(pool, k)
				}
			}
			poolMu.Unlock()
		}
	}()
}

// GetClientRaw 根据底层参数获取复用连接
func GetClientRaw(host, port, user, pwd, key string) (*ssh.Client, error) {
	if host == "" {
		host = config.AppConfig.SSH.Host
	}
	if port == "" {
		port = fmt.Sprintf("%d", config.AppConfig.SSH.Port)
	}

	if host == "" || user == "" {
		return nil, fmt.Errorf("SSH 参数不完整")
	}

	// 兼容 Base64 解码和原版逻辑的极简处理
	if strings.HasPrefix(pwd, "PROTECTED:AES:") {
		// Go 重构版暂不支持深层的 AES，仅作 Base64 或明文处理（视前端情况而定）
	}

	fingerPrint := fmt.Sprintf("%s@%s:%s|%s", user, host, port, pwd)
	hash := md5.Sum([]byte(fingerPrint))
	sessionKey := hex.EncodeToString(hash[:])

	poolMu.RLock()
	pc, exists := pool[sessionKey]
	poolLen := len(pool)
	poolMu.RUnlock()

	if exists {
		pc.LastActive = time.Now()
		return pc.Client, nil
	}

	if poolLen >= config.AppConfig.SSH.MaxSessions {
		return nil, fmt.Errorf("目前底层持久化连接数已达系统上限 (%d)，触发过载保护拦截", config.AppConfig.SSH.MaxSessions)
	}

	authMethods := []ssh.AuthMethod{}
	if key != "" && strings.HasPrefix(key, "-----BEGIN") {
		signer, err := ssh.ParsePrivateKey([]byte(key))
		if err == nil {
			authMethods = append(authMethods, ssh.PublicKeys(signer))
		}
	} else if pwd != "" {
		authMethods = append(authMethods, ssh.Password(pwd))
		authMethods = append(authMethods, ssh.KeyboardInteractive(func(user, instruction string, questions []string, echos []bool) (answers []string, err error) {
			answers = make([]string, len(questions))
			for i := range answers {
				answers[i] = pwd
			}
			return answers, nil
		}))
	}

	sshConfig := &ssh.ClientConfig{
		User:            user,
		Auth:            authMethods,
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         10 * time.Second,
	}

	addr := fmt.Sprintf("%s:%s", host, port)
	client, err := ssh.Dial("tcp", addr, sshConfig)
	if err != nil {
		return nil, err
	}

	poolMu.Lock()
	pool[sessionKey] = &PooledClient{
		Client:     client,
		LastActive: time.Now(),
	}
	poolMu.Unlock()

	return client, nil
}

// GetClientOneOff 不使用连接池，直接创建一个阅后即焚的 SSH 客户端
func GetClientOneOff(host, port, user, pwd, key string) (*ssh.Client, error) {
	if host == "" {
		host = config.AppConfig.SSH.Host
	}
	if port == "" {
		port = fmt.Sprintf("%d", config.AppConfig.SSH.Port)
	}

	if host == "" || user == "" {
		return nil, fmt.Errorf("SSH 参数不完整")
	}

	authMethods := []ssh.AuthMethod{}
	if key != "" && strings.HasPrefix(key, "-----BEGIN") {
		signer, err := ssh.ParsePrivateKey([]byte(key))
		if err == nil {
			authMethods = append(authMethods, ssh.PublicKeys(signer))
		}
	} else if pwd != "" {
		authMethods = append(authMethods, ssh.Password(pwd))
		authMethods = append(authMethods, ssh.KeyboardInteractive(func(user, instruction string, questions []string, echos []bool) (answers []string, err error) {
			answers = make([]string, len(questions))
			for i := range answers {
				answers[i] = pwd
			}
			return answers, nil
		}))
	}

	sshConfig := &ssh.ClientConfig{
		User:            user,
		Auth:            authMethods,
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         10 * time.Second,
	}

	addr := fmt.Sprintf("%s:%s", host, port)
	return ssh.Dial("tcp", addr, sshConfig)
}

// GetClient 获取或创建一个复用的 SSH 客户端 (HTTP Header 包装)
func GetClient(c *gin.Context) (*ssh.Client, error) {
	return GetClientRaw(
		c.GetHeader("X-SSH-Host"),
		c.GetHeader("X-SSH-Port"),
		c.GetHeader("X-SSH-User"),
		c.GetHeader("X-SSH-Pwd"),
		c.GetHeader("X-SSH-Key"),
	)
}

// GetClientByKey 根据 SessionKey (用于下载)
func GetClientByKey(sessionKey string) *ssh.Client {
	poolMu.RLock()
	defer poolMu.RUnlock()
	if pc, exists := pool[sessionKey]; exists {
		return pc.Client
	}
	return nil
}

// RemoveClientRaw 移除指定的复用客户端
func RemoveClientRaw(host, port, user, pwd, key string) {
	fingerPrint := fmt.Sprintf("%s@%s:%s|%s", user, host, port, pwd)
	hash := md5.Sum([]byte(fingerPrint))
	sessionKey := hex.EncodeToString(hash[:])
	
	poolMu.Lock()
	if pc, exists := pool[sessionKey]; exists {
		pc.Client.Close()
		delete(pool, sessionKey)
	}
	poolMu.Unlock()
}

// ExtractSessionKey 用于绑定 Download Token
func ExtractSessionKey(c *gin.Context) string {
	host := c.GetHeader("X-SSH-Host")
	user := c.GetHeader("X-SSH-User")
	port := c.GetHeader("X-SSH-Port")
	pwd := c.GetHeader("X-SSH-Pwd")

	if host == "" {
		host = config.AppConfig.SSH.Host
	}
	if port == "" {
		port = fmt.Sprintf("%d", config.AppConfig.SSH.Port)
	}

	fingerPrint := fmt.Sprintf("%s@%s:%s|%s", user, host, port, pwd)
	hash := md5.Sum([]byte(fingerPrint))
	return hex.EncodeToString(hash[:])
}
