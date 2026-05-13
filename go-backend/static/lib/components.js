/**
 * 天穹 - 业务组件库
 * 将复杂的业务模块封装为独立对象，实现高度的独立拆分能力
 */
window.VisitComponents = {
    // 监控面板组件逻辑
    MonitorCard: {
        template: '#monitor-template',
        setup() {
            const stats = VisitStore.state.stats;
            return { stats };
        }
    },

    // 文件列表组件逻辑
    FileExplorer: {
        template: '#explorer-template',
        setup() {
            const state = VisitStore.state;
            const handleAction = (f) => (f.name.endsWith('.sh') || f.name.endsWith('.js') || f.name.endsWith('.json')) ? 'edit' : 'open';
            return { state, handleAction, VisitApp };
        }
    }
};
