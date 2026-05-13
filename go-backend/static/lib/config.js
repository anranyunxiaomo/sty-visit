/**
 * 天穹 - H5 全局配置库
 */
window.VisitConfig = {
    // API 路由配置
    API_BASE: '/api',
    ENDPOINTS: {
        LOGIN: '/api/auth/login',
        LOGOUT: '/api/auth/logout',
        DIAG_REPORT: '/api/diag/report',
        AUDIT_LOGS: '/api/audit/logs',
        SYSTEM_STATS: '/api/system/stats'
    },

    // 存储键名
    STORAGE_KEYS: {
        AUTH_TOKEN: 'visit_auth_token',
        SESSION_KEY: 'visit_session_key',
        LAST_SERVER: 'visit_last_server'
    },

    // UI 配置
    UI: {
        TOAST_DURATION: 3000,
        THEME: 'apple-minimal-2026'
    }
};
