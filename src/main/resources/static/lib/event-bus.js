/**
 * 天穹 - 全局事件总线
 * 实现组件间的零耦合通信，确保业务模块可以完全独立拆分与运行
 */
window.VisitBus = {
    events: {},
    
    /**
     * 监听事件
     */
    on(event, callback) {
        if (!this.events[event]) this.events[event] = [];
        this.events[event].push(callback);
    },

    /**
     * 触发事件 (广播模式)
     */
    emit(event, data) {
        if (!this.events[event]) return;
        this.events[event].forEach(cb => cb(data));
    },

    /**
     * 典型业务事件定义
     */
    Constants: {
        FILE_CHANGED: 'file:changed',      // 文件内容或列表发生变化
        MONITOR_ALARM: 'monitor:alarm',    // 监控指标触发告警
        AUTH_EXPIRED: 'auth:expired'       // 登录会话过期
    }
};
