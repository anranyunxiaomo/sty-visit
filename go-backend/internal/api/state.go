package api

import "sync"

// GlobalState 用于管理系统运行时的动态状态
type GlobalState struct {
	H5Enabled bool
	mu        sync.RWMutex
}

var State = &GlobalState{
	H5Enabled: true, // 默认开启
}

func (s *GlobalState) IsH5Enabled() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.H5Enabled
}

func (s *GlobalState) SetH5Enabled(enabled bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.H5Enabled = enabled
}
