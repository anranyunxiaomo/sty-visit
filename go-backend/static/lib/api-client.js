/**
 * 天穹 - 统一 API 客户端
 * 封装 Result 协议，实现全局错误处理与健硕的网络通信
 */
window.ApiClient = {
    // 拦截器栈 (Chain of Responsibility Pattern)
    interceptors: {
        request: [],
        response: []
    },
    _lastRequestMap: new Map(), // [BUG FIX] 初始化 Map 实例

    async request(url, options = {}) {
        // 1. 执行请求拦截器
        for (const interceptor of this.interceptors.request) {
            const res = await interceptor(url, options);
            if (res) { url = res.url || url; options = res.options || options; }
        }

        // [GUARD] 交互防御：防止 300ms 内的重复暴力点击
        const now = Date.now();
        if (this._lastRequestMap.has(url) && (now - this._lastRequestMap.get(url) < 300)) {
            console.warn('🛡️ Interaction Guard: 请求过快，已拦截');
            return null; 
        }
        this._lastRequestMap.set(url, now);

        // 自动注入认证 Token
        const token = localStorage.getItem(window.VisitConfig.STORAGE_KEYS.AUTH_TOKEN);
        const headers = {
            'Content-Type': 'application/json',
            'Authorization': token ? `Bearer ${token}` : '',
            ...(options.headers || {})
        };

        try {
            const response = await fetch(url, { ...options, headers });
            let result = await response.json();

            // 2. 执行响应拦截器
            for (const interceptor of this.interceptors.response) {
                result = await interceptor(result) || result;
            }

            // 契约校验：必须符合 Result 协议 {code, message, data}
            if (result.code !== 200) {
                this.handleError(result.message || '请求失败');
                throw new Error(result.message);
            }

            return result.data;
        } catch (error) {
            console.error('Network/Logic Error:', error);
            throw error;
        }
    },

    handleError(msg) {
        // 1. 全局上报：实现全链路异常存证
        fetch(window.VisitConfig.ENDPOINTS.DIAG_REPORT, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ type: 'H5_RUNTIME_ERROR', detail: msg, platform: 'H5' })
        }).catch(() => {});

        // 2. UI 展现
        if (window.VisitUI && window.VisitUI.showToast) {
            window.VisitUI.showToast(msg, 'error');
        } else {
            alert('系统异常: ' + msg);
        }
    }
};
