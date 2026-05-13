/**
 * 天穹 - 插件化扩展引擎
 * 实现 H5 业务模块的动态注入与独立拆分
 */
window.VisitApp = {
    ...window.VisitApp, // 保留已有逻辑

    /**
     * 动态注入业务模块
     * @param {string} namespace 业务命名空间
     * @param {object} module 业务逻辑对象
     */
    extend(namespace, module) {
        if (this[namespace]) {
            console.warn(`🛡️ Plugin System: 命名空间 ${namespace} 已存在，正在执行热覆盖`);
        }
        this[namespace] = module;
        console.log(`🚀 Plugin System: 业务模块 [${namespace}] 注入成功，具备独立运行能力`);
    }
};
