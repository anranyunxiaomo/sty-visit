package api

import (
	"encoding/json"
	"net/http"
	"os"
	"sync"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

var (
	bookmarksFile = "../config/bookmarks.json"
	snippetsFile  = "../config/snippets.json"
	transfersFile = "../config/transfers.json"
	configMutex   sync.Mutex
	transferMutex sync.Mutex
)

// Bookmark struct
type Bookmark struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Host      string `json:"host"`
	Port      string `json:"port"`
	User      string `json:"user"`
	Pwd       string `json:"pwd"`
	Key       string `json:"key"`
	IsKeyAuth bool   `json:"isKeyAuth"`
	OS        string `json:"os"`
}

func init() {
	os.MkdirAll("../config", 0755)
}

func loadBookmarks() []Bookmark {
	configMutex.Lock()
	defer configMutex.Unlock()
	data, err := os.ReadFile(bookmarksFile)
	var items []Bookmark
	if err == nil {
		json.Unmarshal(data, &items)
	}
	return items
}

func saveBookmarks(items []Bookmark) {
	configMutex.Lock()
	defer configMutex.Unlock()
	data, _ := json.MarshalIndent(items, "", "  ")
	os.WriteFile(bookmarksFile, data, 0644)
}

func GetBookmarks(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": loadBookmarks()})
}

func SaveBookmark(c *gin.Context) {
	var item Bookmark
	if err := c.ShouldBindJSON(&item); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"msg": "参数错误"})
		return
	}

	items := loadBookmarks()
	isNew := true
	if item.ID != "" {
		for i, b := range items {
			if b.ID == item.ID {
				items[i] = item
				isNew = false
				break
			}
		}
	}
	if isNew {
		if item.ID == "" {
			item.ID = uuid.New().String()
		}
		items = append(items, item)
	}

	saveBookmarks(items)
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": item})
}

func DeleteBookmark(c *gin.Context) {
	id := c.Query("id")
	items := loadBookmarks()
	var newItems []Bookmark
	for _, b := range items {
		if b.ID != id {
			newItems = append(newItems, b)
		}
	}
	saveBookmarks(newItems)
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": nil})
}

// Snippet struct
type Snippet struct {
	Name     string `json:"name"`
	Command  string `json:"command"`
	Category string `json:"category,omitempty"`
	Type     string `json:"type,omitempty"`
	Pinned   bool   `json:"pinned"`
}

func GetSnippets(c *gin.Context) {
	configMutex.Lock()
	defer configMutex.Unlock()
	data, err := os.ReadFile(snippetsFile)
	var items []Snippet
	if err == nil {
		json.Unmarshal(data, &items)
	} else {
		items = []Snippet{}
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": items})
}

func SaveSnippets(c *gin.Context) {
	var items []Snippet
	if err := c.ShouldBindJSON(&items); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"msg": "参数错误"})
		return
	}
	configMutex.Lock()
	defer configMutex.Unlock()
	data, _ := json.MarshalIndent(items, "", "  ")
	os.WriteFile(snippetsFile, data, 0644)
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": nil})
}

// --- Transfers 持久化 ---

func loadTransfers() []map[string]interface{} {
	transferMutex.Lock()
	defer transferMutex.Unlock()
	data, err := os.ReadFile(transfersFile)
	var items []map[string]interface{}
	if err == nil {
		json.Unmarshal(data, &items)
	}
	if items == nil {
		items = []map[string]interface{}{}
	}
	return items
}

func saveTransfers(items []map[string]interface{}) {
	transferMutex.Lock()
	defer transferMutex.Unlock()
	data, _ := json.MarshalIndent(items, "", "  ")
	os.WriteFile(transfersFile, data, 0644)
}

func GetTransfers(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": loadTransfers()})
}

func SaveTransfer(c *gin.Context) {
	var item map[string]interface{}
	if err := c.ShouldBindJSON(&item); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"msg": "参数错误"})
		return
	}
	
	transfers := loadTransfers()
	id, _ := item["id"].(string)
	found := false
	for i, t := range transfers {
		if tid, _ := t["id"].(string); tid == id {
			transfers[i] = item
			found = true
			break
		}
	}
	if !found {
		transfers = append(transfers, item)
	}
	
	saveTransfers(transfers)
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": nil})
}

func ClearTransfers(c *gin.Context) {
	saveTransfers([]map[string]interface{}{})
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": nil})
}
