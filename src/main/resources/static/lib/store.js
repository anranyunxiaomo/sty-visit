/**
 * 天穹 - 全局业务状态机
 * 采用响应式 Store 模式，实现数据与 UI 的彻底物理隔离
 */
window.VisitStore = {
    state: Vue.reactive({
        currentPath: '/',
        fileList: [],
        loading: false,
        stats: { cpu: 0, mem: 0, uptime: '' },
        activeStage: 'dashboard'
    }),

    // 业务动作：封装了所有的副作用操作
    actions: {
        async fetchFiles(path) {
            this.state.loading = true;
            try {
                const data = await ApiClient.request(`/api/file/list?path=${encodeURIComponent(path)}`);
                VisitStore.state.fileList = data;
                VisitStore.state.currentPath = path;
            } finally {
                this.state.loading = false;
            }
        },

        async refreshMonitor() {
            try {
                const data = await ApiClient.request('/api/monitor/stats');
                VisitStore.state.stats = data;
            } catch (e) {}
        }
    }
};
