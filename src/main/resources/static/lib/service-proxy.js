/**
 * 天穹 - 声明式业务代理 (Service Proxy)
 * 利用 ES6 Proxy 机制实现 API 调用的本地化抽象
 */
window.VisitService = new Proxy({}, {
    get(target, prop) {
        // prop 对应后端 API 的动作，如 'fetchFiles', 'getStats'
        return async (...args) => {
            const urlMap = {
                fetchFiles: `/api/file/list?path=${encodeURIComponent(args[0])}`,
                getStats: `/api/monitor/stats`,
                saveFile: `/api/file/save`
            };
            
            const url = urlMap[prop];
            if (!url) throw new Error(`未定义的业务动作: ${prop}`);
            
            // 封装所有的异步请求细节与交互防御
            return await ApiClient.request(url, args[1] || {});
        };
    }
});
