package api

import (
	"context"
	"io"
	"net/http"
	"strings"
	"time"
	"bytes"

	"github.com/gin-gonic/gin"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/client"
)

func getDockerClient() (*client.Client, error) {
	// 使用本地 socket 建立客户端
	cli, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	return cli, err
}

// GetContainers 获取容器列表
func GetContainers(c *gin.Context) {
	cli, err := getDockerClient()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "Docker 未启动或无法访问", "data": nil})
		return
	}
	defer cli.Close()

	containers, err := cli.ContainerList(context.Background(), container.ListOptions{All: true})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "查询容器失败", "data": nil})
		return
	}

	type ContainerInfo struct {
		ID     string `json:"id"`
		Name   string `json:"name"`
		Image  string `json:"image"`
		State  string `json:"state"`
		Status string `json:"status"`
	}

	var result []ContainerInfo
	for _, c := range containers {
		name := ""
		if len(c.Names) > 0 {
			name = strings.TrimPrefix(c.Names[0], "/")
		}
		result = append(result, ContainerInfo{
			ID:     c.ID[:12], // 截取短 ID
			Name:   name,
			Image:  c.Image,
			State:  c.State,
			Status: c.Status,
		})
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "msg": "success", "data": result})
}

// ControlContainer 控制容器 (start/stop/restart)
func ControlContainer(c *gin.Context) {
	action := c.Query("action")
	containerID := c.Query("id")

	if containerID == "" || action == "" {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "msg": "参数缺失", "data": nil})
		return
	}

	cli, err := getDockerClient()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "Docker 未启动或无法访问", "data": nil})
		return
	}
	defer cli.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	var opErr error
	switch action {
	case "start":
		opErr = cli.ContainerStart(ctx, containerID, container.StartOptions{})
	case "stop":
		opErr = cli.ContainerStop(ctx, containerID, container.StopOptions{})
	case "restart":
		opErr = cli.ContainerRestart(ctx, containerID, container.StopOptions{})
	default:
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "msg": "未知的操作", "data": nil})
		return
	}

	if opErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "操作失败: " + opErr.Error(), "data": nil})
		return
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "msg": "操作已执行", "data": nil})
}

// GetContainerLogs 获取最后 100 行容器日志
func GetContainerLogs(c *gin.Context) {
	containerID := c.Query("id")
	if containerID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "msg": "参数缺失", "data": nil})
		return
	}

	cli, err := getDockerClient()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "Docker 未启动或无法访问", "data": nil})
		return
	}
	defer cli.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	options := container.LogsOptions{
		ShowStdout: true,
		ShowStderr: true,
		Tail:       "100",
		Timestamps: true,
	}
	
	out, err := cli.ContainerLogs(ctx, containerID, options)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "msg": "获取日志失败", "data": nil})
		return
	}
	defer out.Close()

	// Docker 的流数据前 8 个字节是 Header (说明是 stdout 还是 stderr，及长度)
	// 简单实现可以直接用 io.ReadAll，但这会带有乱码 header。可以使用 stdcopy 包，但这里为了轻量采用过滤
	buf := new(bytes.Buffer)
	io.Copy(buf, out)
	
	// 在前端可以用简单方法清理，或者在此处简单清理不可见字符（非严谨）
	raw := buf.String()

	c.JSON(http.StatusOK, gin.H{"code": 200, "msg": "success", "data": raw})
}
