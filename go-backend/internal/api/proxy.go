package api

import (
	"log"
	"net/http"

	"sty-visit/internal/ssh"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
)

var proxyUpgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

// WebSocketProxy 实现从前端 WebSocket 到远端机器的端口转发
func WebSocketProxy(c *gin.Context) {
	target := c.Query("target") // 形如 127.0.0.1:3306
	if target == "" {
		log.Println("代理失败: 未指定 target")
		return
	}

	ws, err := proxyUpgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		log.Printf("Proxy WS 升级失败: %v", err)
		return
	}
	defer ws.Close()

	// 从请求中获取/建立 SSH 隧道
	// 如果是浏览器访问，可能只能用 token（类似下载文件的逻辑）。
	// 如果是 App 访问，可以发 Header。这里兼容两者。
	sshClient, err := ssh.GetClient(c)
	if err != nil {
		// 尝试通过 token 机制获取
		token := c.Query("token")
		if token != "" {
			// 如果你的架构有全局映射可以根据 token 拿，暂时这里用简单的 GetClientByKey 假设
			// 实际中如果前端 WebSocket 没法带 Header，需要先调一个接口把凭证暂存并换取一次性 token
			// 这里的实现假定客户端可以传正确的凭证（例如 Android 客户端能带 Header）
		}
		if sshClient == nil {
			ws.WriteMessage(websocket.TextMessage, []byte("[PROXY ERROR] "+err.Error()))
			return
		}
	}

	// 通过 SSH 隧道拨号到目标端口
	remoteConn, err := sshClient.Dial("tcp", target)
	if err != nil {
		ws.WriteMessage(websocket.TextMessage, []byte("[PROXY ERROR] 无法连接到目标端口: "+err.Error()))
		return
	}
	defer remoteConn.Close()

	// 开启双向流转发
	errc := make(chan error, 2)

	// WS -> TCP
	go func() {
		for {
			msgType, msg, err := ws.ReadMessage()
			if err != nil {
				errc <- err
				return
			}
			if msgType == websocket.BinaryMessage || msgType == websocket.TextMessage {
				_, err = remoteConn.Write(msg)
				if err != nil {
					errc <- err
					return
				}
			}
		}
	}()

	// TCP -> WS
	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := remoteConn.Read(buf)
			if n > 0 {
				err = ws.WriteMessage(websocket.BinaryMessage, buf[:n])
				if err != nil {
					errc <- err
					return
				}
			}
			if err != nil {
				errc <- err
				return
			}
		}
	}()

	<-errc
}
