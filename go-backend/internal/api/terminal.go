package api

import (
	"encoding/json"
	"encoding/base64"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"sty-visit/internal/config"
	sshManager "sty-visit/internal/ssh"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	"golang.org/x/crypto/ssh"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		return true // 放行所有跨域，依赖鉴权拦截器
	},
}

// WSAuthMessage 认证报文结构
type WSAuthMessage struct {
	Type    string `json:"type"`
	SSHUser string `json:"sshUser"`
	SSHPwd  string `json:"sshPwd"`
	SSHHost string `json:"sshHost"`
	SSHPort int    `json:"sshPort"`
	Cols    int    `json:"cols"`
	Rows    int    `json:"rows"`
}

// WSResizeMessage 窗口调整报文结构
type WSResizeMessage struct {
	Type string `json:"type"`
	Cols int    `json:"cols"`
	Rows int    `json:"rows"`
}

type SSHSession struct {
	Client  *ssh.Client
	Session *ssh.Session
	Stdin   io.WriteCloser
	mu      sync.Mutex
}

func (s *SSHSession) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.Session != nil {
		s.Session.Close()
	}
	// 不再关闭 s.Client.Close()，由全局 sshManager 统一管理
}

// WebSocketTerminal 处理 /ws/terminal 连接
func WebSocketTerminal(c *gin.Context) {
	respHeader := http.Header{}
	if reqProtocol := c.GetHeader("Sec-WebSocket-Protocol"); reqProtocol != "" {
		respHeader.Set("Sec-WebSocket-Protocol", reqProtocol)
	}

	ws, err := upgrader.Upgrade(c.Writer, c.Request, respHeader)
	if err != nil {
		log.Printf("WS 升级失败: %v", err)
		return
	}
	defer ws.Close()

	var sshConn *SSHSession

	defer func() {
		if sshConn != nil {
			sshConn.Close()
		}
	}()

	for {
		msgType, msg, err := ws.ReadMessage()
		if err != nil {
			break
		}

		// 纯文本消息可能包含 JSON 控制报文或纯输入字符
		if msgType == websocket.TextMessage {
			text := string(msg)

			// 解析控制指令
			if strings.Contains(text, `"type":"auth"`) {
				var authMsg WSAuthMessage
				if err := json.Unmarshal(msg, &authMsg); err == nil {
					sshConn, err = establishSSH(ws, authMsg)
					if err != nil {
						ws.WriteMessage(websocket.TextMessage, []byte("\r\n[SSH ERROR] "+err.Error()+"\r\n"))
						return
					}
				} else {
					log.Printf("WSAuthMessage Unmarshal Error: %v", err)
				}
				continue
			}

			if strings.Contains(text, `"type":"resize"`) {
				if sshConn != nil && sshConn.Session != nil {
					var resizeMsg WSResizeMessage
					if err := json.Unmarshal(msg, &resizeMsg); err == nil {
						sshConn.Session.WindowChange(resizeMsg.Rows, resizeMsg.Cols)
					}
				}
				continue
			}

			// 作为普通输入流写入 SSH
			if sshConn != nil && sshConn.Stdin != nil {
				sshConn.Stdin.Write(msg)
			}

		} else if msgType == websocket.BinaryMessage {
			if sshConn != nil && sshConn.Stdin != nil {
				sshConn.Stdin.Write(msg)
			}
		}
	}
}

func establishSSH(ws *websocket.Conn, authMsg WSAuthMessage) (*SSHSession, error) {
	host := authMsg.SSHHost
	if host == "" {
		host = config.AppConfig.SSH.Host
	}
	port := authMsg.SSHPort
	if port == 0 {
		port = config.AppConfig.SSH.Port
	}
	user := authMsg.SSHUser

	pwd := authMsg.SSHPwd
	if decodedBytes, err := base64.StdEncoding.DecodeString(pwd); err == nil {
		pwd = string(decodedBytes)
	}
	
	ws.WriteMessage(websocket.TextMessage, []byte("[INFO] 正在建立 TCP 链路...\r\n"))

	client, err := sshManager.GetClientRaw(host, fmt.Sprintf("%d", port), user, pwd, "")
	if err != nil {
		return nil, err
	}

	ws.WriteMessage(websocket.TextMessage, []byte("[INFO] TCP 链路已建立，正在分配 Shell...\r\n"))

	session, err := client.NewSession()
	if err != nil {
		sshManager.RemoveClientRaw(host, fmt.Sprintf("%d", port), user, pwd, "")
		// 可能是旧连接已失效，尝试重新获取一个全新的 Client
		client, err = sshManager.GetClientRaw(host, fmt.Sprintf("%d", port), user, pwd, "")
		if err != nil {
			return nil, err
		}
		session, err = client.NewSession()
		if err != nil {
			return nil, err
		}
	}

	stdin, err := session.StdinPipe()
	if err != nil {
		session.Close()
		return nil, err
	}
	stdout, err := session.StdoutPipe()
	if err != nil {
		session.Close()
		return nil, err
	}
	stderr, err := session.StderrPipe()
	if err != nil {
		session.Close()
		return nil, err
	}

	// 请求 PTY
	modes := ssh.TerminalModes{
		ssh.ECHO:          1,
		ssh.TTY_OP_ISPEED: 14400,
		ssh.TTY_OP_OSPEED: 14400,
	}
	
	cols := authMsg.Cols
	if cols == 0 { cols = 80 }
	rows := authMsg.Rows
	if rows == 0 { rows = 24 }

	if err := session.RequestPty("xterm-256color", rows, cols, modes); err != nil {
		session.Close()
		return nil, err
	}

	if err := session.Shell(); err != nil {
		session.Close()
		return nil, err
	}

	var writeMu sync.Mutex
	writeWS := func(msgType int, data []byte) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		return ws.WriteMessage(msgType, data)
	}

	writeWS(websocket.TextMessage, []byte("[INFO] Shell 分配成功，会话已激活。\r\n\r\n"))

	// 启动协程读取 SSH 输出并推送给 WS
	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := stdout.Read(buf)
			if n > 0 {
				if err := writeWS(websocket.BinaryMessage, buf[:n]); err != nil {
					break
				}
			}
			if err != nil {
				break
			}
		}
	}()

	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := stderr.Read(buf)
			if n > 0 {
				if err := writeWS(websocket.BinaryMessage, buf[:n]); err != nil {
					break
				}
			}
			if err != nil {
				break
			}
		}
	}()

	// 发送 ls 颜色别名 (与 Java 代码对齐)
	go func() {
		time.Sleep(1500 * time.Millisecond)
		stdin.Write([]byte("alias ls='ls -1 --color=auto -F --group-directories-first' && clear\r"))
	}()

	return &SSHSession{
		Client:  client,
		Session: session,
		Stdin:   stdin,
	}, nil
}
