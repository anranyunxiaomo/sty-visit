const { createApp, ref, computed, onMounted, watch, nextTick } = Vue;

createApp({
    setup() {
        const currentTheme = ref(localStorage.getItem('visit_theme') || 'auto');
        const applyTheme = (theme) => {
            if (theme === 'auto') {
                if (window.matchMedia('(prefers-color-scheme: dark)').matches) {
                    document.documentElement.setAttribute('data-theme', 'dark');
                } else {
                    document.documentElement.setAttribute('data-theme', 'light');
                }
            } else if (theme === 'dark') {
                document.documentElement.setAttribute('data-theme', 'dark');
            } else {
                document.documentElement.setAttribute('data-theme', 'light');
            }
        };
        applyTheme(currentTheme.value);

        const toggleTheme = () => {
            const next = currentTheme.value === 'dark' ? 'light' : 'dark';
            currentTheme.value = next;
            localStorage.setItem('visit_theme', next);
            applyTheme(next);
            refreshIcons();
        };
        
        window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
            if (currentTheme.value === 'auto') applyTheme('auto');
        });

        const isWebLoggedIn = ref(!!localStorage.getItem('visit_token'));
        const webPwd = ref('');
        const showWorkspace = ref(false);
        const sessionInstances = new Map();
        const sessions = ref(JSON.parse(sessionStorage.getItem('visit_sessions') || '[]').map(s => ({...s, currentTab: s.currentTab || 'terminal', inspecting: false, inspectData: null, bookmarkId: s.bookmarkId || null, term:null, socket:null, fitAddon:null, monitorTimer:null, reconnectTimer:null})));
        const activeSessionId = ref(sessions.value.length > 0 ? sessions.value[0].id : null);
        const activeSession = computed(() => sessions.value.find(s => s.id === activeSessionId.value) || null);
        
        const saveSessions = () => {
            const toSave = sessions.value.map(s => ({
                id: s.id, name: s.name, host: s.host, port: s.port, user: s.user, pwd: s.pwd, isKeyAuth: s.isKeyAuth, bookmarkId: s.bookmarkId,
                currentTab: s.currentTab, currentPath: s.currentPath, fileList: s.fileList, transferList: s.transferList, monitor: s.monitor, isWsDisconnected: s.isWsDisconnected
            }));
            sessionStorage.setItem('visit_sessions', JSON.stringify(toSave));
        };

        const BOOKMARK_ICONS = [
            'server', 'database', 'cloud', 'monitor', 'cpu', 'hard-drive',
            'globe', 'shield', 'layers', 'box', 'radio', 'git-branch',
            'terminal', 'code', 'activity', 'zap'
        ];
        const BOOKMARK_COLORS = [
            { bg: '#EFF6FF', fg: '#2563EB' },
            { bg: '#F0FDF4', fg: '#16A34A' },
            { bg: '#FEF9C3', fg: '#CA8A04' },
            { bg: '#FDF4FF', fg: '#9333EA' },
            { bg: '#FFF7ED', fg: '#EA580C' },
            { bg: '#F0FDFA', fg: '#0D9488' },
            { bg: '#FFF1F2', fg: '#E11D48' },
            { bg: '#F8FAFC', fg: '#475569' },
        ];
        const strHash = (str) => {
            if (!str) return 0;
            let h = 0;
            for (let i = 0; i < String(str).length; i++) h = (h * 31 + String(str).charCodeAt(i)) & 0x7fffffff;
            return h;
        };
        const getBookmarkIcon = (id) => BOOKMARK_ICONS[strHash(id) % BOOKMARK_ICONS.length];
        const getBookmarkColor = (id) => BOOKMARK_COLORS[strHash(id) % BOOKMARK_COLORS.length];
        const BOOKMARK_EMOJIS = ['🖥️','🗄️','☁️','🔒','⚡','🌐','📡','🔧','💾','🚀','🛡️','🔌','🧩','📊','🖧','🏗️'];
        const getBookmarkEmoji = (id) => BOOKMARK_EMOJIS[strHash(id) % BOOKMARK_EMOJIS.length];
        
        const formHost = ref(localStorage.getItem('ssh_conf_host') || '127.0.0.1');
        const formPort = ref(localStorage.getItem('ssh_conf_port') || '22');
        const formUser = ref(localStorage.getItem('ssh_conf_user') || 'root');
        const formPwd = ref('');
        const loading = ref(false);
        const currentTab = computed({ get: () => activeSession.value?.currentTab || 'terminal', set: (v) => { if(activeSession.value) { activeSession.value.currentTab = v; saveSessions(); } } });
        const auditLogs = ref([]);
        const currentPath = computed({ get: () => activeSession.value?.currentPath || '.', set: (v) => { if(activeSession.value) { activeSession.value.currentPath = v; saveSessions(); } } });
        const fileList = computed({ get: () => activeSession.value?.fileList || [], set: (v) => { if(activeSession.value) { activeSession.value.fileList = v; saveSessions(); } } });
        const transferList = computed({ get: () => activeSession.value?.transferList || [], set: (v) => { if(activeSession.value) { activeSession.value.transferList = v; saveSessions(); } } });
        const monitor = computed({ get: () => activeSession.value?.monitor || { cpu: 0, mem: 0, load: 0, latency: '--' }, set: (v) => { if(activeSession.value) { activeSession.value.monitor = v; saveSessions(); } } });
        const isWsDisconnected = computed({ get: () => activeSession.value?.isWsDisconnected || false, set: (v) => { if(activeSession.value) { activeSession.value.isWsDisconnected = v; saveSessions(); } } });
        
        const selectedPaths = ref([]);
        const activeFile = ref(null);
        const editingFile = ref(null);
        const saving = ref(false);
        
        const formIsKey = ref(false);
        const bookmarks = ref([]);
        const shouldSaveBookmark = ref(false);
        const bookmarkName = ref('');
        const editingBookmarkId = ref(null); // 正在编辑的书签 ID，null 表示新建
        const terminalEl = ref(null);
        const inputPath = ref('.');
        
        const fileSearch = ref('');
        const fileSortBy = ref('name'); // 'name', 'time', 'size', 'type'
        const fileSortOrder = ref('asc');
        const sortedFileList = computed(() => {
            let list = [...fileList.value];
            if (fileSearch.value.trim()) {
                const q = fileSearch.value.trim().toLowerCase();
                list = list.filter(f => f.name.toLowerCase().includes(q));
            }
            const sortFn = (a, b) => {
                let valA, valB;
                if (fileSortBy.value === 'name') {
                    valA = a.name.toLowerCase();
                    valB = b.name.toLowerCase();
                } else if (fileSortBy.value === 'time') {
                    valA = new Date(a.lastModified).getTime();
                    valB = new Date(b.lastModified).getTime();
                } else if (fileSortBy.value === 'size') {
                    valA = a.size;
                    valB = b.size;
                } else if (fileSortBy.value === 'type') {
                    valA = a.isDirectory ? '' : a.name.split('.').pop().toLowerCase();
                    valB = b.isDirectory ? '' : b.name.split('.').pop().toLowerCase();
                }
                if (valA < valB) return fileSortOrder.value === 'asc' ? -1 : 1;
                if (valA > valB) return fileSortOrder.value === 'asc' ? 1 : -1;
                return 0;
            };
            const folders = list.filter(f => f.isDirectory).sort(sortFn);
            const files = list.filter(f => !f.isDirectory).sort(sortFn);
            return [...folders, ...files];
        });
        
        const showConnectionForm = ref(false);
        const showBatchAddForm = ref(false);
        const batchAddForm = ref({ port: '22', user: 'root', pwd: '', list: '' });
        const systemConfig = ref({ h5Enabled: true, auditRetentionDays: 31 });
        const showSnippetManager = ref(false);
        const editingSnippet = ref(null);
        const editingSnippetIndex = ref(-1);
        let aceEditor;

        const coreShortcuts = [
            {label:'Tab',val:'\t'}, {label:'ESC',val:'\x1b'}, 
            {label:'Ctrl+C',val:'\x03'}, {label:'Enter',val:'\r'}
        ];

        const batchDefaultPort = ref('22');
        const batchDefaultUser = ref('root');
        const batchDefaultPwd = ref('');
        const batchServersInput = ref('');
        const batchCommandInput = ref('');
        const batchBurnMode = ref(false);
        const batchLoading = ref(false);
        const batchResult = ref(null);
        const batchSelectedHosts = ref([]);
        const viewingBatchDetail = ref(null);
        const storedSplitMode = localStorage.getItem('visit_split_mode');
        const isSplitMode = ref(storedSplitMode === null ? true : storedSplitMode === 'true');

        // 传输中心搜索与排序状态
        const transferSearch = ref('');
        const transferTypeFilter = ref('');
        const transferSortBy = ref('time');
        const transferSortOrder = ref('desc');

        const filteredTransferList = computed(() => {
            let list = transferList.value;
            // 按类型筛选
            if (transferTypeFilter.value) {
                list = list.filter(t => t.type === transferTypeFilter.value);
            }
            // 按搜索词模糊匹配
            if (transferSearch.value.trim()) {
                const q = transferSearch.value.trim().toLowerCase();
                list = list.filter(t =>
                    (t.name || '').toLowerCase().includes(q) ||
                    (t.targetPath || '').toLowerCase().includes(q) ||
                    (t.server || '').toLowerCase().includes(q) ||
                    (t.fileExt || '').toLowerCase().includes(q)
                );
            }
            // 排序
            list = [...list].sort((a, b) => {
                let va, vb;
                if (transferSortBy.value === 'name') { va = a.name || ''; vb = b.name || ''; }
                else if (transferSortBy.value === 'type') { va = a.type || ''; vb = b.type || ''; }
                else if (transferSortBy.value === 'status') { va = a.status || ''; vb = b.status || ''; }
                else { va = a.timestamp || ''; vb = b.timestamp || ''; }
                const cmp = va < vb ? -1 : va > vb ? 1 : 0;
                return transferSortOrder.value === 'desc' ? -cmp : cmp;
            });
            return list;
        });

        // 文件元信息工具函数
        const getFileExt = (name) => {
            if (!name) return 'FILE';
            const dotIdx = name.lastIndexOf('.');
            if (dotIdx < 0 || dotIdx === name.length - 1) return 'FILE';
            return '.' + name.substring(dotIdx + 1).toUpperCase();
        };
        const getFileSizeStr = (size) => {
            if (size === undefined || size === null) return '';
            if (size === 0) return '0 B';
            if (size < 1024) return size + ' B';
            if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB';
            if (size < 1024 * 1024 * 1024) return (size / (1024 * 1024)).toFixed(1) + ' MB';
            return (size / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
        };

        const customModal = ref({
            show: false, title: '', message: '', type: 'primary', confirmText: '确定',
            alertOnly: false, inputMode: false, inputValue: '', inputPlaceholder: '',
            onConfirm: () => {}, onCancel: () => { customModal.value.show = false; }
        });

        const showConfirm = (title, message, type = 'primary', confirmText = '确定') => {
            return new Promise((resolve) => {
                customModal.value = {
                    show: true, title, message, type, confirmText,
                    alertOnly: false, inputMode: false, inputValue: '', inputPlaceholder: '',
                    onConfirm: () => { customModal.value.show = false; resolve(true); },
                    onCancel: () => { customModal.value.show = false; resolve(false); }
                };
            });
        };

        const showAlert = (title, message, type = 'primary') => {
            return new Promise((resolve) => {
                customModal.value = {
                    show: true, title, message, type,
                    confirmText: '我知道了', alertOnly: true, inputMode: false, inputValue: '', inputPlaceholder: '',
                    onConfirm: () => { customModal.value.show = false; resolve(); },
                    onCancel: () => { customModal.value.show = false; resolve(); }
                };
            });
        };

        // 带输入框的自定义 prompt
        const showPrompt = (title, message, defaultValue = '', placeholder = '') => {
            return new Promise((resolve) => {
                customModal.value = {
                    show: true, title, message, type: 'primary',
                    confirmText: '确定', alertOnly: false, inputMode: true,
                    inputValue: defaultValue, inputPlaceholder: placeholder,
                    onConfirm: () => {
                        const v = customModal.value.inputValue.trim();
                        customModal.value.show = false;
                        resolve(v || null);
                    },
                    onCancel: () => { customModal.value.show = false; resolve(null); }
                };
                // 下一帧自动耙焦输入框
                nextTick(() => { document.getElementById('modal-input-field')?.focus(); });
            });
        };

        const toggleSplitMode = () => {
            isSplitMode.value = !isSplitMode.value;
            localStorage.setItem('visit_split_mode', isSplitMode.value);
            if (isSplitMode.value) {
                // 如果开启分屏，确保当前是终端或文件
                if (currentTab.value !== 'terminal' && currentTab.value !== 'files') {
                    currentTab.value = 'terminal';
                }
            }
            nextTick(() => {
                refreshIcons();
                if (isSplitMode.value) {
                    focusTerminal();
                }
            });
        };
        const viewBatchDetail = (r) => {
            viewingBatchDetail.value = r;
        };

        let lastBatchServers = [];

        const connectToBatchHost = async (hostStr) => {
            const srv = lastBatchServers.find(s => `${s.user}@${s.host}:${s.port}` === hostStr);
            if (!srv) {
                alert('无法找到该主机的连接配置');
                return;
            }
            
            if (!confirm(`确定要直接连接到终端 ${hostStr} 吗？`)) return;

            formHost.value = srv.host;
            formPort.value = srv.port;
            formUser.value = srv.user;
            formPwd.value = atob(srv.pwd);
            formIsKey.value = srv.isKey;
            bookmarkName.value = '';
            
            batchResult.value = null;
            loading.value = true;
            try {
                await handleSshLogin();
            } finally {
                loading.value = false;
            }
        };

        const toggleAllBatchResults = (e) => {
            if (e.target.checked) {
                batchSelectedHosts.value = batchResult.value.results.map(x => x.host);
            } else {
                batchSelectedHosts.value = [];
            }
        };

        const exportSelectedBatchResults = () => {
            if (batchSelectedHosts.value.length === 0) {
                alert('请先勾选需要导出的服务器');
                return;
            }
            const selected = batchResult.value.results.filter(r => batchSelectedHosts.value.includes(r.host));
            
            let logContent = `==================================================\n批量执行报告 (部分导出) - 生成时间: ${new Date().toLocaleString()}\n执行命令:\n${batchCommandInput.value}\n==================================================\n\n`;
            for (const r of selected) {
                logContent += `▶ [${r.host}] 状态: ${r.status}\n--------------------------------------------------\n${r.output}${r.output.endsWith('\n') ? '' : '\n'}--------------------------------------------------\n\n`;
            }
            logContent += `导出主机数: ${selected.length} / 总计: ${batchResult.value.total}\n`;
            
            const blob = new Blob([logContent], { type: 'text/plain;charset=utf-8' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `batch_export_${new Date().getTime()}.log`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        };

        const executeBatchCommand = async () => {
            if (!batchServersInput.value.trim() || !batchCommandInput.value.trim()) {
                alert('服务器清单和执行命令均不能为空');
                return;
            }
            const lines = batchServersInput.value.trim().split('\n');
            const servers = [];
            for (let line of lines) {
                line = line.trim();
                if (!line) continue;
                
                const parts = line.split(',');
                let host = '', port = batchDefaultPort.value || '22', user = batchDefaultUser.value || 'root', pwd = batchDefaultPwd.value || '';
                
                if (parts.length >= 3) {
                    const hostPort = parts[0].split(':');
                    host = hostPort[0];
                    if (hostPort.length > 1) port = hostPort[1];
                    user = parts[1];
                    pwd = parts.slice(2).join(',');
                } else if (parts.length === 1) {
                    const hostPort = parts[0].split(':');
                    host = hostPort[0];
                    if (hostPort.length > 1) port = hostPort[1];
                } else {
                    const hostPort = parts[0].split(':');
                    host = hostPort[0];
                    if (hostPort.length > 1) port = hostPort[1];
                    user = parts[1];
                }
                
                servers.push({
                    host: host,
                    port: port,
                    user: user,
                    pwd: btoa(pwd),
                    isKey: false
                });
            }
            if (servers.length === 0) {
                alert('解析不到任何有效的服务器配置，请检查格式');
                return;
            }
            
            if (servers.length > 100 && !batchBurnMode.value) {
                const proceed = confirm(`⚠️ 性能警告：\n您当前批量执行的服务器数量（${servers.length} 台）超过了 100 台！\n如果不开启“即焚模式”，这 ${servers.length} 个长连接会持续占用系统底层资源。\n\n点击【确定】将自动为您开启“即焚模式”（执行完立即断开连接）并继续执行。\n点击【取消】则中止本次操作。`);
                if (proceed) {
                    batchBurnMode.value = true;
                } else {
                    return;
                }
            }
            
            lastBatchServers = servers;
            
            batchLoading.value = true;
            batchResult.value = null;
            batchSelectedHosts.value = [];
            try {
                const res = await apiFetch('/api/batch-execute', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        servers: servers,
                        command: batchCommandInput.value,
                        burnMode: batchBurnMode.value
                    })
                });
                const data = await res.json();
                if (res.ok) {
                    // 强制前端重算统计，确保“执行完毕”也被计入成功，解决后端缓存或未重启导致的数据偏差
                    if (data.results) {
                        const success = data.results.filter(r => r.status === '成功' || r.status === '执行完毕').length;
                        data.success = success;
                        data.fail = data.results.length - success;
                        data.total = data.results.length;
                    }
                    batchResult.value = data;
                } else {
                    alert('批量执行失败: ' + (data.error || '未知错误'));
                }
            } catch (err) {
                alert('网络或请求异常: ' + err.message);
            } finally {
                batchLoading.value = false;
                nextTick(() => refreshIcons());
            }
        };

        const switchSession = (id) => {
            activeSessionId.value = id;
            saveSessions();
            showConnectionForm.value = false;
            
            nextTick(() => {
                refreshIcons();
            });
        };

        const silentInspectAndSaveOS = async (session) => {
            if (!session.bookmarkId) return;
            const targetBookmark = bookmarks.value.find(b => b.id === session.bookmarkId);
            // 如果该书签已经存有 OS 记录（非首次连接），则直接跳过，不发多余探测请求
            if (targetBookmark && targetBookmark.os) return;
            
            try {
                const res = await apiFetch('/api/ssh/inspect', { 
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ bookmarkId: session.bookmarkId })
                });
                if (res && res.ok) {
                    const data = await res.json();
                    if (data.code === 200 && data.data && data.data.os) {
                        const targetBookmark = bookmarks.value.find(b => b.id === session.bookmarkId);
                        if (targetBookmark && targetBookmark.os !== data.data.os) {
                            targetBookmark.os = data.data.os;
                            await apiFetch('/api/bookmarks', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify(targetBookmark)
                            });
                            await fetchBookmarks();
                        }
                    }
                }
            } catch (e) {}
        };

        const closeSession = (id) => {
            const s = sessions.value.find(x => x.id === id);
            if (s) {
                if (s.socket) s.socket.close();
                if (s.monitorTimer) clearInterval(s.monitorTimer);
                if (s.reconnectTimer) clearTimeout(s.reconnectTimer);
            }
            sessions.value = sessions.value.filter(x => x.id !== id);
            if (activeSessionId.value === id) {
                activeSessionId.value = sessions.value.length > 0 ? sessions.value[sessions.value.length - 1].id : null;
            }
            saveSessions();
            if (sessions.value.length === 0) {
                showWorkspace.value = false;
            }
        };

        const formatTime = (date) => {
            const pad = (n) => n.toString().padStart(2, '0');
            return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
        };

        const refreshIcons = () => {
            nextTick(() => { if (typeof lucide !== 'undefined') lucide.createIcons(); });
        };

        const focusTerminal = () => {
            const term = sessionInstances.get(activeSessionId.value)?.term;
            if (term) {
                term.focus();
                const input = document.getElementById('hidden-input');
                if (input) {
                    input.focus();
                    setTimeout(() => term.focus(), 10);
                }
            }
        };

        const protectPwd = (pwd) => VisitUtils.protectPwd(pwd, localStorage.getItem('visit_session_key'));
        const revealPwd = (protectedStr) => VisitUtils.revealPwd(protectedStr, localStorage.getItem('visit_session_key'));

        const jumpServerHosts = ref([]);
        const fetchHosts = async () => {
            const res = await apiFetch('/api/hosts', { method: 'GET' });
            if (res.ok) {
                const body = await res.json();
                jumpServerHosts.value = body.data || [];
            }
        };

        const handleWebLogin = async () => {
            if (!webPwd.value || loading.value) return;
            loading.value = true;
            try {
                const res = await fetch('/api/auth/login', { 
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ password: webPwd.value })
                });
                
                if (res.ok) {
                    const body = await res.json();
                    const data = body.data || {};
                    localStorage.setItem('visit_token', data.token);
                    localStorage.setItem('visit_session_key', data.sessionKey);
                    isWebLoggedIn.value = true;
                    refreshIcons();
                    await fetchBookmarks();
                    await fetchHosts();
                    await fetchSnippets();
                    await fetchSystemConfig();
                } else {
                    const errorMsg = await res.text();
                    alert('认证未通过: ' + (errorMsg || '密码校验失败'));
                }
             } catch (e) { 
                 alert('网络异常: 无法连接至管理服务端'); 
             } finally {
                 loading.value = false;
             }
        };

        // ===== Snippets 指令库管理 =====
        const snippets = ref([]);
        const snippetCategories = ref(['网络类', '运维类', '开发类', '系统类', '安全类', '通用']);
        const showBatchSnippetForm = ref(false);
        const batchSnippetText = ref('');
        const batchSnippetCategory = ref('运维类');

        const searchSnippetQuery = ref('');
        const fetchSnippets = async () => {
            const res = await apiFetch('/api/snippets');
            if (res.ok) {
                const body = await res.json();
                snippets.value = body.data || [];
                nextTick(() => refreshIcons());
            }
        };
        
        const groupedSnippets = computed(() => {
            const groups = {};
            const pinnedList = [];
            const query = searchSnippetQuery.value.toLowerCase().trim();
            
            snippets.value.forEach(s => {
                if (query) {
                    const matchName = s.name.toLowerCase().includes(query);
                    const matchCommand = s.command.toLowerCase().includes(query);
                    if (!matchName && !matchCommand) return;
                }
                
                if (s.pinned && !query) { // 如果有搜索词，不再单独提出来置顶收藏，而是全部按真实分类或混合展示？这里还是提出来比较好。
                    pinnedList.push(s);
                } else if (s.pinned && query) {
                    pinnedList.push(s);
                } else {
                    const cat = s.category || '未分类';
                    if (!groups[cat]) groups[cat] = [];
                    groups[cat].push(s);
                }
            });
            
            const result = {};
            // 置顶收藏永远放在第一栏
            if (pinnedList.length > 0) {
                result['置顶收藏'] = pinnedList;
            }
            
            // 按原有分类顺序展示剩下的
            snippetCategories.value.forEach(c => {
                if (groups[c] && groups[c].length > 0) {
                    result[c] = groups[c];
                }
            });
            if (groups['未分类'] && groups['未分类'].length > 0) {
                result['未分类'] = groups['未分类'];
            }
            
            return result;
        });

        const searchSnippetManagerQuery = ref('');
        const filteredManagerSnippets = computed(() => {
            const query = searchSnippetManagerQuery.value.toLowerCase().trim();
            if (!query) return snippets.value;
            return snippets.value.filter(s => 
                s.name.toLowerCase().includes(query) || 
                s.command.toLowerCase().includes(query)
            );
        });

        const openSnippetEditor = (s = null, idx = -1) => {
            editingSnippetIndex.value = idx;
            if (s) {
                editingSnippet.value = { ...s };
                if (!editingSnippet.value.type) editingSnippet.value.type = 'direct';
            } else {
                editingSnippet.value = { name: '', command: '', category: '通用', type: 'direct' };
            }
        };

        const runEditingSnippet = () => {
            if (!editingSnippet.value || !editingSnippet.value.command) return;
            // 记录一下命令内容
            const sToRun = { ...editingSnippet.value };
            // 关闭所有弹窗
            editingSnippet.value = null;
            showSnippetManager.value = false;
            
            if (currentTab.value === 'batch') {
                batchCommandInput.value = sToRun.command;
            } else {
                // 切回终端并执行
                currentTab.value = 'terminal';
                nextTick(() => {
                    executeSnippet(sToRun);
                });
            }
        };

        const saveSnippet = async () => {
            if (!editingSnippet.value.name || !editingSnippet.value.command) return alert('名称和命令必填');
            let updatedList = JSON.parse(JSON.stringify(snippets.value));
            if (editingSnippetIndex.value === -1) {
                updatedList.push(editingSnippet.value);
            } else {
                updatedList[editingSnippetIndex.value] = editingSnippet.value;
            }
            const res = await apiFetch('/api/snippets', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(updatedList)
            });
            if (res.ok) {
                snippets.value = updatedList;
                editingSnippet.value = null;
                refreshIcons();
            } else {
                alert('保存失败');
            }
        };

        const executeSnippet = (s) => {
            const socket = sessionInstances.get(activeSessionId.value)?.socket;
            if (!socket) {
                alert('请先连接一个终端会话再执行指令。');
                return;
            }
            
            // Allow backward compatibility if older code still calls it with string command
            const isInteractive = (typeof s === 'object') ? (s.type === 'interactive') : false;
            const cmd = (typeof s === 'object') ? s.command : s;

            if (isInteractive) {
                socket.send(cmd + ' '); // Send with space, wait for user input
            } else {
                socket.send(cmd + '\n'); // Execute immediately
            }
            
            // Switch to terminal tab
            showWorkspace.value = true;
            currentTab.value = 'terminal';
        };
        
        const handleBatchAddSnippets = async () => {
            const lines = batchSnippetText.value.split('\n');
            let updatedList = JSON.parse(JSON.stringify(snippets.value));
            let addedCount = 0;
            
            for (let line of lines) {
                line = line.trim();
                if (!line || line.startsWith('#')) continue;
                
                const sepIdx = line.indexOf('=') !== -1 ? line.indexOf('=') : 
                               (line.indexOf('|') !== -1 ? line.indexOf('|') : line.indexOf(':'));
                
                if (sepIdx !== -1) {
                    const name = line.substring(0, sepIdx).trim();
                    const command = line.substring(sepIdx + 1).trim();
                    if (name && command) {
                        updatedList.push({ name, command, category: batchSnippetCategory.value });
                        addedCount++;
                    }
                }
            }
            
            if (addedCount === 0) {
                alert('未解析出有效的指令格式。请按照 "名称 = 指令" 的格式输入。');
                return;
            }
            
            const res = await apiFetch('/api/snippets', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(updatedList)
            });
            if (res.ok) {
                snippets.value = updatedList;
                showBatchSnippetForm.value = false;
                batchSnippetText.value = '';
                alert(`成功批量新增 ${addedCount} 条指令！`);
            } else {
                alert('批量新增失败');
            }
        };

        const openSnippetManager = () => {
            showSnippetManager.value = true;
            refreshIcons();
        };

        const deleteSnippet = async (idx) => {
            const ok = await showConfirm('删除指令', '确定要移除这条快捷指令吗？', 'danger', '确认删除');
            if (!ok) return;
            let updatedList = JSON.parse(JSON.stringify(snippets.value));
            updatedList.splice(idx, 1);
            const res = await apiFetch('/api/snippets', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(updatedList)
            });
            if (res.ok) {
                snippets.value = updatedList;
                showAlert('删除成功', '指令已从库中移除', 'primary');
            }
        };

        const toggleSnippetPin = async (s) => {
            const idx = snippets.value.findIndex(x => x.name === s.name);
            if (idx === -1) return;
            let updatedList = JSON.parse(JSON.stringify(snippets.value));
            updatedList[idx].pinned = !updatedList[idx].pinned;
            const res = await apiFetch('/api/snippets', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(updatedList)
            });
            if (res.ok) {
                snippets.value = updatedList;
                nextTick(() => refreshIcons());
            }
        };

        const pinnedSnippets = computed(() => {
            return snippets.value.filter(s => s.pinned);
        });

        const maxVisibleSnippets = ref(parseInt(localStorage.getItem('maxVisibleSnippets') || '6'));

        const saveMaxVisibleSnippets = () => {
            if (maxVisibleSnippets.value < 1) maxVisibleSnippets.value = 1;
            if (maxVisibleSnippets.value > 50) maxVisibleSnippets.value = 50;
            localStorage.setItem('maxVisibleSnippets', maxVisibleSnippets.value);
        };

        const displayedPinnedSnippets = computed(() => {
            return snippets.value.filter(s => s.pinned).slice(0, maxVisibleSnippets.value);
        });

        const fetchSystemConfig = async () => {
            try {
                const res1 = await apiFetch('/api/config/h5/status');
                if (res1.ok) {
                    const body = await res1.json();
                    systemConfig.value.h5Enabled = body.data.enabled;
                }
                const res2 = await apiFetch('/api/config/audit/retention');
                if (res2.ok) {
                    const body = await res2.json();
                    systemConfig.value.auditRetentionDays = body.data.days;
                }
            } catch (e) { console.warn('Failed to load system config'); }
        };

        const toggleH5 = async () => {
            if (!systemConfig.value.h5Enabled) {
                const ok = await showConfirm('停用网页端', '警告：关闭后你将立即无法访问网页端（直到从移动 App 端重新开启）。确定要执行此操作吗？', 'danger', '确认停用');
                if (!ok) {
                    systemConfig.value.h5Enabled = true;
                    return;
                }
            }
            await apiFetch('/api/config/h5/toggle', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: systemConfig.value.h5Enabled })
            });
        };

        
        const killProcess = async (pid) => {
            const ok = await showConfirm('终止进程', `确定强制结束进程 PID: ${pid} 吗？`, 'danger', '立即结束');
            if(!ok) return;
            try {
                const res = await apiFetch(`/api/monitor/kill?pid=${pid}`, { method: 'POST' });
                if (res.ok) showAlert('操作成功', '进程已成功终止', 'primary');
                else showAlert('操作失败', '无法结束该进程', 'danger');
            } catch (e) { showAlert('网络错误', e.message, 'danger'); }
        };

        const updateAuditRetention = async () => {
            const val = prompt('请输入审计日志保留天数 (例如: 31):', systemConfig.value.auditRetentionDays);
            const days = parseInt(val);
            if (days && days > 0) {
                const res = await apiFetch('/api/config/audit/retention', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ days })
                });
                if (res.ok) {
                    systemConfig.value.auditRetentionDays = days;
                    showAlert('修改成功', `审计日志保留期限已更新为 ${days} 天`, 'primary');
                }
            }
        };

        const wipeAllData = async () => {
            const pwd = prompt('【高危操作】将物理摧毁所有书签、快捷指令及日志数据。请输入 Admin 密钥确认：');
            if (pwd) {
                const res = await apiFetch('/api/config/wipe', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ password: pwd })
                });
                if (res.ok) {
                    showAlert('擦除完成', '所有本地数据已物理销毁，系统已重置', 'primary');
                    handleLogout();
                } else {
                    showAlert('密钥错误', '认证失败，无法执行抹除操作', 'danger');
                }
            }
        };

        const fetchBookmarks = async () => {
            const res = await apiFetch('/api/bookmarks');
            if (res.ok) {
                const body = await res.json();
                bookmarks.value = body.data || [];
                // 书签图标是动态绑定，需要 Vue 完成 DOM 更新后再让 Lucide 扫描渲染
                nextTick(() => refreshIcons());
            }
        };

        const useBookmark = async (b, isEdit = false) => {
            formHost.value = b.host;
            formPort.value = b.port;
            formUser.value = b.user;
            const encryptedStr = b.isKeyAuth ? b.key : b.pwd;
            if (encryptedStr && encryptedStr.startsWith('PROTECTED:AES:')) {
                formPwd.value = await revealPwd(encryptedStr.substring(14));
            } else {
                formPwd.value = encryptedStr;
            }
            formIsKey.value = b.isKeyAuth;
            if (isEdit) {
                // 编辑模式：自动勾选保存书签并回显名称
                shouldSaveBookmark.value = true;
                bookmarkName.value = b.name || '';
                editingBookmarkId.value = b.id;
            } else {
                // 直接点击书签连接：不需要重新保存
                shouldSaveBookmark.value = false;
                bookmarkName.value = '';
                editingBookmarkId.value = null;
            }
            showConnectionForm.value = true;
        };


        const deleteBookmark = async (id) => {
            const ok = await showConfirm('销毁书签', '确定要移除此书签连接吗？', 'danger', '立即移除');
            if (!ok) return;
            const res = await apiFetch(`/api/bookmarks?id=${id}`, { method: 'DELETE' });
            if (res.ok) fetchBookmarks();
            else showAlert('操作失败', '无法删除该书签', 'danger');
        };

        const handleSshLogin = async (directBookmarkId) => {
            if (!formPwd.value) return; if (formPwd.value.includes(':') && formPwd.value.length > 20 && !formPwd.value.startsWith('PROTECTED:AES:')) { showAlert('数据损坏', '检测到密码数据已损坏，请点击编辑按钮重新输入密码！', 'danger'); return; } // For simplicity, always require pwd or key during form submit
            if (sessions.value.length >= 10) return showAlert('连接受限', '最多允许 10 个同时活跃的会话', 'warning');
            loading.value = true;
            
            const protectedPwd = await protectPwd(formPwd.value);
            
            localStorage.setItem('ssh_conf_host', formHost.value);
            localStorage.setItem('ssh_conf_port', formPort.value);
            localStorage.setItem('ssh_conf_user', formUser.value);

            let currentBookmarkId = typeof directBookmarkId === 'string' ? directBookmarkId : editingBookmarkId.value;
            if (shouldSaveBookmark.value) {
                const pPwd = !formIsKey.value ? protectedPwd : null;
                const pKey = formIsKey.value ? protectedPwd : null;
                const finalName = bookmarkName.value.trim() || `${formUser.value}@${formHost.value}`;

                const bRes = await apiFetch('/api/bookmarks', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        id: editingBookmarkId.value || null,
                        name: finalName,
                        host: formHost.value,
                        port: formPort.value,
                        user: formUser.value,
                        pwd: pPwd,
                        key: pKey,
                        isKeyAuth: formIsKey.value
                    }),
                }, null);
                if (bRes && bRes.ok) {
                    const bData = await bRes.json();
                    if (bData.data) currentBookmarkId = bData.data.id;
                }
                editingBookmarkId.value = null;
                await fetchBookmarks();
            }

            const newId = 'seq_' + Date.now() + '_' + Math.floor(Math.random()*100000);
            const newSession = {
                id: newId,
                name: bookmarkName.value || `${formUser.value}@${formHost.value}`,
                host: formHost.value,
                port: formPort.value,
                user: formUser.value,
                pwd: protectedPwd, // Keep protected pwd in memory
                isKeyAuth: formIsKey.value,
                bookmarkId: currentBookmarkId,
                
                isWsDisconnected: false,
                currentTab: 'terminal',
                inspecting: false,
                inspectData: null,
                currentPath: '.', fileList: [], transferList: [], monitor: { cpu: 0, mem: 0, load: 0, latency: '--' }
            };
            sessions.value.push(newSession);
            activeSessionId.value = newId;
            saveSessions();

            showWorkspace.value = true;
            showConnectionForm.value = false;
            loading.value = false;
            
            nextTick(() => {
                initSessionTerminal(newSession);
                silentInspectAndSaveOS(newSession);
                startSessionMonitoring(newSession);
                fetchFiles(newSession.currentPath);
                fetchSnippets();
                fetchTransfers();
                fetchSystemConfig();
            });
        };

        const handleBatchAddBookmarks = async () => {
            const listStr = batchAddForm.value.list.trim();
            if (!listStr) return showAlert('内容为空', '请输入目标服务器清单', 'warning');
            
            const lines = listStr.split('\n').map(l => l.trim()).filter(l => l);
            if (lines.length === 0) return;
            
            loading.value = true;
            let successCount = 0;
            let errorCount = 0;
            
            for (const line of lines) {
                let ip = line;
                let port = batchAddForm.value.port || '22';
                let user = batchAddForm.value.user || 'root';
                let pwd = batchAddForm.value.pwd || '';
                
                // 解析自定义格式: IP[:Port,User,Pwd]
                const colonIdx = line.indexOf(':');
                if (colonIdx !== -1) {
                    ip = line.substring(0, colonIdx);
                    const rest = line.substring(colonIdx + 1);
                    const pieces = rest.split(',');
                    if (pieces.length > 0 && pieces[0]) port = pieces[0];
                    if (pieces.length > 1 && pieces[1]) user = pieces[1];
                    if (pieces.length > 2 && pieces[2]) pwd = pieces[2];
                }
                
                if (!pwd) {
                    errorCount++;
                    continue; // 无密码则跳过
                }
                
                const protectedPwd = await protectPwd(pwd);
                
                const bRes = await apiFetch('/api/bookmarks', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        id: null,
                        name: ip,
                        host: ip,
                        port: port,
                        user: user,
                        pwd: protectedPwd,
                        key: null,
                        isKeyAuth: false
                    })
                }, null);
                if (bRes && bRes.ok) {
                    successCount++;
                } else {
                    errorCount++;
                }
            }
            
            loading.value = false;
            if (successCount > 0) {
                showAlert('批量添加完成', `成功添加 ${successCount} 个书签${errorCount > 0 ? `，跳过或失败 ${errorCount} 个` : ''}`, 'success');
                showBatchAddForm.value = false;
                await fetchBookmarks();
            } else {
                showAlert('批量添加失败', `未成功添加书签，请检查是否填写了密码。`, 'danger');
            }
        };

        const apiFetch = async (url, options = {}, specificSession = undefined) => {
            const currentToken = localStorage.getItem('visit_token');
            const session = specificSession !== undefined ? specificSession : activeSession.value;
            const pwdProtected = session ? session.pwd : null;
            const isKey = session ? session.isKeyAuth : false;
            
            const protectedHeader = pwdProtected && !pwdProtected.startsWith('PROTECTED:AES:') && pwdProtected.includes(':') 
                ? 'PROTECTED:AES:' + pwdProtected 
                : pwdProtected;

            const headers = {
                'Authorization': `Bearer ${currentToken}`,
                'X-Visit-Client': 'H5',
                'X-SSH-Host': session ? session.host : formHost.value,
                'X-SSH-Port': session ? session.port : formPort.value,
                'X-SSH-User': session ? session.user : formUser.value,
                'X-SSH-Pwd': !isKey ? protectedHeader : null,
                'X-SSH-Key': isKey ? protectedHeader : null,
                ...options.headers
            };
            try {
                const res = await fetch(url, { ...options, headers });
                if (res.status === 401) { 
                    handleLogout(); 
                    return new Promise(() => {}); 
                }
                if (res.status === 403) {
                    const errorData = await res.json().catch(() => ({}));
                    alert(errorData.message || '访问受限：无权执行此操作');
                    throw new Error('Forbidden');
                }
                return res;
            } catch (e) { throw e; }
        };

        const handleLogout = async () => {
            sessionInstances.forEach(inst => {
                if (inst.monitorTimer) clearInterval(inst.monitorTimer);
                if (inst.socket) inst.socket.close();
            });
            try {
                await apiFetch('/api/auth/logout', { method: 'POST' });
            } catch (e) {}
            if (window.AndroidBridge && window.AndroidBridge.hardLogout) {
                window.AndroidBridge.hardLogout();
            }
            sessionStorage.clear();
            localStorage.removeItem('visit_token');
            localStorage.removeItem('visit_session_key');
            auditLogs.value = [];
            window.location.reload();
        };

        const handleSshDisconnect = () => {
            showWorkspace.value = false;
            showConnectionForm.value = false;
        };

        const startSessionMonitoring = (session) => {
            // 监控面板已移除，不再拉取系统指标
        };

        const getEditDistance = (a, b) => {
            if(a.length === 0) return b.length;
            if(b.length === 0) return a.length;
            const matrix = [];
            for(let i = 0; i <= b.length; i++) matrix[i] = [i];
            for(let j = 0; j <= a.length; j++) matrix[0][j] = j;
            for(let i = 1; i <= b.length; i++){
                for(let j = 1; j <= a.length; j++){
                    if(b.charAt(i-1) === a.charAt(j-1)){
                        matrix[i][j] = matrix[i-1][j-1];
                    } else {
                        matrix[i][j] = Math.min(matrix[i-1][j-1] + 1, Math.min(matrix[i][j-1] + 1, matrix[i-1][j] + 1));
                    }
                }
            }
            return matrix[b.length][a.length];
        };

        const triggerSnippetMenu = (term, socket, failedCmd, inst) => {
            if (!failedCmd || snippets.value.length === 0) return;
            
            // Fuzzy match snippets based on failedCmd
            const scoredSnippets = snippets.value.map(s => {
                const cmdBase = s.command.split(' ')[0].toLowerCase();
                const dist1 = getEditDistance(failedCmd.toLowerCase(), cmdBase);
                const dist2 = getEditDistance(failedCmd.toLowerCase(), s.name.toLowerCase());
                return { snippet: s, score: Math.min(dist1, dist2) };
            });
            
            // Sort by score and take top 5 with score <= 4
            const matchedSnippets = scoredSnippets
                .filter(x => x.score <= 4)
                .sort((a, b) => a.score - b.score)
                .slice(0, 5)
                .map(x => x.snippet);
                
            if (matchedSnippets.length === 0) return;
            
            inst.inSnippetMenu = true;
            inst.snippetChoices = matchedSnippets;
            
            setTimeout(() => {
                term.write(`\r\n\x1b[1;36m[Sky Visit 🪄] 未找到命令 '${failedCmd}'。是否执行指令库预设指令?\x1b[0m\r\n`);
                matchedSnippets.forEach((s, idx) => {
                    term.write(`\x1b[1;32m${idx + 1}.\x1b[0m \x1b[1;37m${s.name}\x1b[0m  \x1b[1;33m(${s.command})\x1b[0m\r\n`);
                });
                term.write(`\x1b[1;31mq.\x1b[0m \x1b[37m退出\x1b[0m\r\n`);
                term.write(`\x1b[1;36m请输入序号 (1-${matchedSnippets.length}, q): \x1b[0m`);
            }, 50);
        };

        const initSessionTerminal = (session) => {
            nextTick(async () => {
                const el = document.getElementById('term-' + session.id);
                if (!el) return;
                
                let inst = sessionInstances.get(session.id);
                if (!inst) {
                    inst = { term: null, socket: null, fitAddon: null, monitorTimer: null, reconnectTimer: null, outBuffer: '' };
                    sessionInstances.set(session.id, inst);
                }
                if (inst.term) return; // already inited
                
                inst.term = new Terminal({ 
                    cursorBlink: true, 
                    fontSize: 13,
                    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
                    theme: { background: '#000', foreground: '#FFF' } 
                });
                inst.fitAddon = new FitAddon.FitAddon();
                inst.term.loadAddon(inst.fitAddon);
                inst.term.options.scrollback = 5000;
                inst.term.open(el);
                setTimeout(() => { resizeSession(inst); }, 150);
                
                if (activeSessionId.value === session.id) inst.term.focus();

                const pwdRaw = await revealPwd(session.pwd);

                const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
                inst.socket = new WebSocket(`${protocol}//${location.host}/ws/terminal`, ['visit-auth.' + localStorage.getItem('visit_token')]);
                inst.socket.binaryType = 'arraybuffer';
                
                inst.socket.onopen = () => {
                    session.isWsDisconnected = false;
                    saveSessions();
                    if (inst.reconnectTimer) clearTimeout(inst.reconnectTimer);
                    inst.socket.send(JSON.stringify({
                        type: 'auth',
                        sshHost: session.host,
                        sshPort: Number(session.port),
                        sshUser: session.user,
                        sshPwd: btoa(pwdRaw),
                        cols: inst.term.cols, rows: inst.term.rows
                    }));
                };
                inst.socket.onmessage = (ev) => {
                    let text = '';
                    if (ev.data instanceof ArrayBuffer) {
                        text = new TextDecoder().decode(ev.data);
                        inst.term.write(new Uint8Array(ev.data));
                    } else {
                        text = ev.data;
                        inst.term.write(text);
                    }
                    
                    if (typeof inst.outBuffer !== 'string') inst.outBuffer = '';
                    inst.outBuffer += text;
                    if (inst.outBuffer.length > 500) inst.outBuffer = inst.outBuffer.slice(-500);
                    
                    const cleanBuffer = inst.outBuffer.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
                    
                    if (/command not found/.test(cleanBuffer)) {
                        let m = cleanBuffer.match(/(?:bash|zsh|sh): (.*?): command not found/) || cleanBuffer.match(/command not found: (.*)/);
                        let failedCmd = m ? m[1].trim() : '';
                        
                        if (failedCmd && failedCmd !== 'q') {
                            triggerSnippetMenu(inst.term, inst.socket, failedCmd, inst);
                        }
                        inst.outBuffer = '';
                    } else if (/Permission denied/.test(cleanBuffer)) {
                        inst.outBuffer = '';
                    }
                };
                inst.socket.onclose = (e) => { console.error("WS CLOSED", session.id, e.code, e.reason);
                    session.isWsDisconnected = true;
                    inst.inSnippetMenu = false;
                    saveSessions();
                    if (inst.reconnectTimer) clearTimeout(inst.reconnectTimer);
                    inst.reconnectTimer = setTimeout(() => {
                        if (sessions.value.find(s => s.id === session.id)) {
                            inst.term.dispose();
                            inst.term = null;
                            initSessionTerminal(session);
                        }
                    }, 5000);
                };
                inst.socket.onerror = (e) => { console.error("WS ERROR", session.id, e); inst.socket.close(); };

                inst.term.onData(data => {
                    if (inst.inSnippetMenu) {
                        if (data === 'q' || data === 'Q' || data === '\r' || data === '\x03' || data === '\x1b') {
                            inst.inSnippetMenu = false;
                            inst.term.write('\r\n\x1b[33m[Sky Visit 🪄] 已取消指令库推荐。\x1b[0m\r\n');
                            inst.socket.send('\r'); 
                            return;
                        }
                        const num = parseInt(data);
                        if (!isNaN(num) && num > 0 && num <= inst.snippetChoices.length) {
                            const selected = inst.snippetChoices[num - 1];
                            inst.inSnippetMenu = false;
                            inst.term.write(`\r\n\x1b[35m[Sky Visit 🪄] 正在执行: ${selected.name}\x1b[0m\r\n`);
                            const cmd = selected.command;
                            if (selected.type === 'interactive') {
                                inst.socket.send(cmd + ' ');
                            } else {
                                inst.socket.send(cmd + '\r');
                            }
                            return;
                        }
                        return; // 拦截无效按键
                    }
                    if (inst.socket?.readyState === 1) {
                        inst.socket.send(data);
                    }
                });
                
                el.addEventListener('click', () => {
                    if (activeSessionId.value === session.id) inst.term.focus();
                });
            });
        };
        
        const resizeSession = (inst) => {
            if (!inst || !inst.fitAddon || !inst.term) return;
            const oldCols = inst.term.cols;
            const oldRows = inst.term.rows;
            try {
                inst.fitAddon.fit();
                if (inst.socket && inst.socket.readyState === 1) {
                    if (inst.term.cols !== oldCols || inst.term.rows !== oldRows) {
                        inst.socket.send(JSON.stringify({ type: 'resize', cols: inst.term.cols, rows: inst.term.rows }));
                    }
                }
            } catch (e) {}
        };

        window.addEventListener('resize', () => {
            if (currentTab.value === 'terminal' && activeSessionId.value) {
                const inst = sessionInstances.get(activeSessionId.value);
                if (inst) resizeSession(inst);
            }
        });

        window.visitBridge = {
            send: (cmd) => {
                const inst = sessionInstances.get(activeSessionId.value);
                if (inst && inst.socket && inst.socket.readyState === 1) {
                    inst.socket.send(cmd);
                }
            }
        };

        const syncPathToTerm = () => {
            const inst = sessionInstances.get(activeSessionId.value);
            if (inst && inst.socket && inst.socket.readyState === 1) {
                inst.socket.send(`cd "${currentPath.value}"\r`);
                if (!isSplitMode.value) {
                    currentTab.value = 'terminal';
                }
            }
        };

        const doSyncPwdFromTerm = () => {
            const inst = sessionInstances.get(activeSessionId.value);
            if (!inst || !inst.socket || inst.socket.readyState !== 1) {
                alert('终端未连接，无法解析路径。');
                return;
            }
            const marker = `SYNC_PWD_${Date.now()}`;
            inst.socket.send(`echo ${marker} && pwd\r`);
            
            let attempts = 0;
            const checkBuffer = () => {
                attempts++;
                try {
                    const buffer = inst.term.buffer.active;
                    const maxLines = buffer.baseY + buffer.cursorY;
                    let extractedPwd = '';
                    
                    const bufferLength = buffer.length;
                    for (let i = bufferLength - 1; i >= Math.max(0, bufferLength - 50); i--) {
                        const line = buffer.getLine(i)?.translateToString(true).trim();
                        if (!line) continue;
                        
                        if (line.includes(marker) && !line.includes('echo')) {
                            // 标记位已找到，往下寻找以 / 开头的绝对路径
                            for (let j = i + 1; j < bufferLength; j++) {
                                const candidate = buffer.getLine(j)?.translateToString(true).trim();
                                // 如果这一行有内容，并且它以 / 开头（标准 Linux 绝对路径）
                                if (candidate && candidate.startsWith('/')) {
                                    extractedPwd = candidate;
                                    break;
                                }
                            }
                            break;
                        }
                    }
                    
                    if (extractedPwd) {
                        fetchFiles(extractedPwd);
                        currentTab.value = 'files';
                    } else if (attempts < 10) {
                        setTimeout(checkBuffer, 200);
                    } else {
                        alert('终端路径解析失败，请确保终端处于标准的 Shell 环境且响应正常。');
                    }
                } catch (e) {
                    alert('路径解析出现异常：' + e.message);
                }
            };
            
            setTimeout(checkBuffer, 200);
        };

        const syncTermToFiles = doSyncPwdFromTerm;
        const syncFilesToTerm = doSyncPwdFromTerm;

        const jumpToPath = () => {
            const path = inputPath.value.trim();
            if (path) fetchFiles(path);
        };

        const fetchFiles = async (path = currentPath.value) => {
            selectedPaths.value = [];
            try {
                let targetPath = path;
                if (!targetPath || targetPath === '.' || targetPath === '~') {
                    try {
                        const pwdRes = await apiFetch('/api/file/pwd');
                        if (pwdRes.ok) targetPath = (await pwdRes.json()).data?.path || '/';
                    } catch (e) { targetPath = '/'; }
                }
                
                if (targetPath !== currentPath.value) {
                    fileSearch.value = '';
                }

                const res = await apiFetch(`/api/file/list?path=${encodeURIComponent(targetPath)}`);
                if (res.ok) { 
                    const body = await res.json();
                    fileList.value = body.data || []; 
                    currentPath.value = targetPath; 
                    inputPath.value = targetPath; 
                    refreshIcons(); 
                } else {
                    alert('无法进入该目录，请检查路径是否正确，将自动返回根目录。');
                    if (targetPath !== '/') {
                        fetchFiles('/');
                    }
                }
            } catch (e) {
                console.error('List error:', e);
            }
        };

        const handleFileClick = (f) => f.isDirectory ? fetchFiles(f.path) : (activeFile.value = f, refreshIcons());
        const goUp = () => fetchFiles(currentPath.value.substring(0, currentPath.value.lastIndexOf('/')) || '/');
        
        const formatSize = (b) => VisitUtils.formatSize(b);
        
        const isEditable = (n) => /\.(sh|conf|txt|yml|yaml|json|py|js|html|css|php|xml|md)$/i.test(n);

        const fetchAuditLogs = async () => {
            const res = await apiFetch('/api/audit/logs');
            if (res.ok) { 
                const body = await res.json();
                auditLogs.value = body.data || []; 
                refreshIcons(); 
            }
        };

        const getAuditTagClass = (s) => s.includes('成功') ? 'bg-green-50 text-green-600' : 'bg-red-50 text-red-600';

        const openEditor = async (f) => {
            activeFile.value = null; editingFile.value = f;
            try {
                const res = await apiFetch(`/api/file/content?path=${encodeURIComponent(f.path)}`, {}, activeSession.value);
                const body = await res.json();
                const content = body.data || '';
                nextTick(() => {
                    aceEditor = ace.edit("ace-editor");
                    aceEditor.setTheme("ace/theme/chrome");
                    aceEditor.setValue(content, -1);
                });
            } catch (e) { alert('读取失败'); }
        };

        const saveFile = async () => {
            saving.value = true;
            try {
                await apiFetch('/api/file/save', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: editingFile.value.path, content: aceEditor.getValue() })
                }, activeSession.value);
                alert('已保存');
            } catch (e) { alert('保存失败'); }
            saving.value = false;
        };

        const fetchTransfers = async () => {
            try {
                const res = await apiFetch('/api/transfers');
                if (res.ok) {
                    const result = await res.json();
                    transferList.value = result.data || [];
                }
            } catch (e) {}
        };

        const clearTransfers = async () => {
            const ok = await showConfirm('清空记录', '确定要清空所有传输与操作历史记录吗？', 'primary', '立即清空');
            if (!ok) return;
            try {
                const res = await apiFetch('/api/transfers', { method: 'DELETE' });
                if (res.ok) {
                    transferList.value = [];
                    showAlert('清空成功', '所有传输记录已清除', 'primary');
                }
            } catch (e) {}
        };

        const uploadFile = async (ev) => {
            const files = ev.target.files;
            if (!files || files.length === 0) return;
            // 记录当前服务器信息
            const s = activeSession.value;
            const serverLabel = s ? (s.name && s.name !== `${s.user}@${s.host}` ? `${s.name} · ${s.host}` : `${s.user}@${s.host}`) : '未知主机';
            for (let i = 0; i < files.length; i++) {
                const file = files[i];
                const id = 'up_' + Date.now() + '_' + i;
                const transfer = { id, name: file.name, type: 'UPLOAD', status: 'UPLOADING', progress: 0, targetPath: currentPath.value, errorMessage: '', timestamp: formatTime(new Date()), isDir: false, fileExt: getFileExt(file.name), fileSize: getFileSizeStr(file.size), server: serverLabel };
                transferList.value.unshift(transfer);
                apiFetch('/api/transfers', { method: 'POST', body: JSON.stringify(transfer), headers: {'Content-Type': 'application/json'} }).catch(()=>{});
                
                const formData = new FormData();
                formData.append('file', file);
                formData.append('path', currentPath.value);
                
                try {
                    const res = await apiFetch('/api/file/upload', { method: 'POST', body: formData }, activeSession.value);
                    if (res.ok) {
                        transfer.status = 'COMPLETED';
                        transfer.progress = 100;
                    } else {
                        const data = await res.json();
                        transfer.status = 'ERROR';
                        transfer.errorMessage = data.error || '上传失败';
                    }
                } catch (err) {
                    transfer.status = 'ERROR';
                    transfer.errorMessage = err.message || '网络错误';
                }
                transfer.timestamp = formatTime(new Date());
                apiFetch('/api/transfers', { method: 'POST', body: JSON.stringify(transfer), headers: {'Content-Type': 'application/json'} }).catch(()=>{});
            }
            fetchFiles(); // 刷新列表
        };

        const downloadFile = async (f) => {
            const s = activeSession.value;
            const serverLabel = s ? (s.name && s.name !== `${s.user}@${s.host}` ? `${s.name} · ${s.host}` : `${s.user}@${s.host}`) : '未知主机';
            const id = 'dl_' + Date.now();
            const transfer = { id, name: f.name, type: 'DOWNLOAD', status: 'DOWNLOADING', progress: 0, targetPath: currentPath.value, errorMessage: '', timestamp: formatTime(new Date()), isDir: f.isDirectory, fileExt: f.isDirectory ? '' : getFileExt(f.name), fileSize: f.isDirectory ? '' : getFileSizeStr(f.size), server: serverLabel };
            transferList.value.unshift(transfer);
            apiFetch('/api/transfers', { method: 'POST', body: JSON.stringify(transfer), headers: {'Content-Type': 'application/json'} }).catch(()=>{});

            try {
                const res = await apiFetch(`/api/file/token?path=${encodeURIComponent(f.path)}`, { method: 'GET' }, activeSession.value);
                if (res.ok) {
                    const { data } = await res.json();
                    window.open(`/api/file/download?token=${data.token}`);
                    transfer.status = 'COMPLETED';
                    transfer.progress = 100;
                } else {
                    transfer.status = 'ERROR';
                    transfer.errorMessage = '获取下载令牌失败';
                }
            } catch (err) { 
                transfer.status = 'ERROR';
                transfer.errorMessage = err.message || '下载失败';
            }
            transfer.timestamp = formatTime(new Date());
            apiFetch('/api/transfers', { method: 'POST', body: JSON.stringify(transfer), headers: {'Content-Type': 'application/json'} }).catch(()=>{});
        };

        const renameFile = async (f) => {
            const newName = await showPrompt(
                '🏷️ 文件重命名',
                '请为该项目输入一个新名称',
                f.name,
                '新的文件名称...'
            );
            if (!newName || newName === f.name) return;

            const id = 'ren_' + Date.now();
            const s = activeSession.value;
            const serverLabel = s ? (s.name && s.name !== `${s.user}@${s.host}` ? `${s.name} · ${s.host}` : `${s.user}@${s.host}`) : '未知主机';
            const transfer = { id, name: `${f.name} → ${newName}`, type: 'RENAME', status: 'RENAMING', progress: 50, targetPath: f.path, errorMessage: '', timestamp: formatTime(new Date()), isDir: f.isDirectory, fileExt: f.isDirectory ? '' : getFileExt(f.name), fileSize: '', server: serverLabel };
            transferList.value.unshift(transfer);

            try {
                const dir = f.path.substring(0, f.path.lastIndexOf('/'));
                const newPath = (dir ? dir : '') + '/' + newName;
                const inst = sessionInstances.get(activeSessionId.value);
                if (inst && inst.socket && inst.socket.readyState === 1) {
                    inst.socket.send(`mv "${f.path}" "${newPath}"
`);
                    setTimeout(() => { fetchFiles(); }, 800);
                    transfer.status = 'COMPLETED';
                    transfer.progress = 100;
                    activeFile.value = null;
                } else {
                    transfer.status = 'ERROR';
                    transfer.errorMessage = '终端未连接';
                    showAlert('无法重命名', '终端连接断开，请先连接终端', 'danger');
                }
            } catch (err) {
                transfer.status = 'ERROR';
                transfer.errorMessage = err.message;
                showAlert('操作失败', err.message, 'danger');
            }
            transfer.timestamp = formatTime(new Date());
            apiFetch('/api/transfers', { method: 'POST', body: JSON.stringify(transfer), headers: {'Content-Type': 'application/json'} }).catch(()=>{});
        };

        const confirmDelete = async (f) => {
            const ok = await showConfirm('确认粉碎', `您确定要永久删除 ${f.isDirectory ? '目录' : '文件'} [${f.name}] 吗？`, 'danger', '立即粉碎');
            if (!ok) return;
            
            const id = 'del_' + Date.now();
            const s = activeSession.value;
            const serverLabel = s ? (s.name && s.name !== `${s.user}@${s.host}` ? `${s.name} · ${s.host}` : `${s.user}@${s.host}`) : '未知主机';
            const transfer = { id, name: f.name, type: 'DELETE', status: 'DELETING', progress: 50, targetPath: f.path, errorMessage: '', timestamp: formatTime(new Date()), isDir: f.isDirectory, fileExt: f.isDirectory ? '' : getFileExt(f.name), fileSize: f.isDirectory ? '' : getFileSizeStr(f.size), server: serverLabel };
            transferList.value.unshift(transfer);

            try {
                // 后端路由是 DELETE /api/file?path=...
                const res = await apiFetch(`/api/file?path=${encodeURIComponent(f.path)}`, {
                    method: 'DELETE'
                }, activeSession.value);
                if (res.ok) {
                    transfer.status = 'COMPLETED';
                    transfer.progress = 100;
                    fetchFiles();
                    activeFile.value = null;
                } else {
                    // 安全解析响应（可能是 JSON 也可能是纯文本）
                    const text = await res.text();
                    let msg = '删除失败';
                    try { msg = JSON.parse(text).error || msg; } catch { msg = text || msg; }
                    transfer.status = 'ERROR';
                    transfer.errorMessage = msg;
                    showAlert('删除失败', msg, 'danger');
                }
            } catch (err) {
                transfer.status = 'ERROR';
                transfer.errorMessage = err.message;
                showAlert('删除失败', err.message, 'danger');
            }
            transfer.timestamp = formatTime(new Date());
            apiFetch('/api/transfers', { method: 'POST', body: JSON.stringify(transfer), headers: {'Content-Type': 'application/json'} }).catch(()=>{});
        };

        const handleBatchDelete = async () => {
            const count = selectedPaths.value.length;
            const ok = await showConfirm('批量粉碎', `确定要批量删除选中的 ${count} 个项目吗？`, 'danger', '确认删除');
            if (!ok) return;

            const id = 'batch_del_' + Date.now();
            const transfer = { id, name: `批量删除 (${count}项)`, type: 'DELETE', status: 'DELETING', progress: 0, targetPath: 'Multiple', errorMessage: '', timestamp: formatTime(new Date()), isDir: true };
            transferList.value.unshift(transfer);

            // 批量删除：逐个调用 DELETE /api/file
            let doneCount = 0;
            let failCount = 0;
            for (const path of selectedPaths.value) {
                try {
                    const res = await apiFetch(`/api/file?path=${encodeURIComponent(path)}`, { method: 'DELETE' }, activeSession.value);
                    if (res.ok) doneCount++;
                    else failCount++;
                } catch { failCount++; }
                transfer.progress = Math.round((doneCount + failCount) / count * 100);
            }

            if (failCount === 0) {
                transfer.status = 'COMPLETED';
                transfer.progress = 100;
                selectedPaths.value = [];
                fetchFiles();
            } else {
                transfer.status = 'ERROR';
                transfer.errorMessage = `共 ${failCount} 项删除失败`;
                showAlert('部分失败', transfer.errorMessage, 'danger');
                fetchFiles();
            }
            transfer.timestamp = formatTime(new Date());
            apiFetch('/api/transfers', { method: 'POST', body: JSON.stringify(transfer), headers: {'Content-Type': 'application/json'} }).catch(()=>{});
        };

        onMounted(() => {
            // [PHYSICAL FIX] 混合架构对齐：如果处于原生容器环境且本地无 Token，则自动从原生 Bridge 同步状态
            if (!isWebLoggedIn.value && window.AndroidBridge) {
                const nativeToken = window.AndroidBridge.getToken();
                const nativeKey = window.AndroidBridge.getSessionKey();
                if (nativeToken) {
                    localStorage.setItem('visit_token', nativeToken);
                    if (nativeKey) localStorage.setItem('visit_session_key', nativeKey);
                    isWebLoggedIn.value = true;
                }
            }
            
            refreshIcons();
            if (isWebLoggedIn.value) {
                if (sessions.value.length > 0) {
                    sessions.value.forEach(s => {
                        initSessionTerminal(s);
                        startSessionMonitoring(s);
                    });
                    showWorkspace.value = true;
                }
                fetchBookmarks();
                fetchSnippets();
                fetchSystemConfig();
                fetchHosts();
            }
        });

        watch(currentTab, (v) => {
            if (v === 'terminal') nextTick(() => {
                setTimeout(() => {
                    const inst = sessionInstances.get(activeSessionId.value);
                    if (inst) resizeSession(inst);
                }, 150);
            });
            if (v === 'files' || (isSplitMode.value && v === 'terminal')) {
                inputPath.value = currentPath.value;
                if (fileList.value.length === 0) fetchFiles(currentPath.value);
            }
            if (v === 'audit') fetchAuditLogs();
            refreshIcons();
        });

        watch(activeSessionId, (newId) => {
            if (newId) {
                fileSearch.value = ''; // 切换会话时重置搜索
                nextTick(() => {
                    if (currentTab.value === 'terminal') {
                        setTimeout(() => {
                            const inst = sessionInstances.get(newId);
                            if (inst) {
                                resizeSession(inst);
                                if (inst.term) inst.term.focus();
                            }
                        }, 150);
                    }
                    if (currentTab.value === 'files' || (isSplitMode.value && currentTab.value === 'terminal')) {
                        inputPath.value = currentPath.value;
                        if (fileList.value.length === 0) {
                            fetchFiles(currentPath.value);
                        }
                    }
                });
            }
        });

        // 从编辑/批量表单返回书签列表时，重新 fetch 确保 v-html SVG 图标正常渲染
        watch(showConnectionForm, (isForm) => {
            if (!isForm && isWebLoggedIn.value) {
                fetchBookmarks();
            }
        });
        watch(showBatchAddForm, (isForm) => {
            if (!isForm && isWebLoggedIn.value) {
                fetchBookmarks();
            }
        });

        const dockerContainers = ref([]);
        const showDockerLogs = ref(false);
        const dockerLogContent = ref('');

        const fetchContainers = async () => {
            if (currentTab.value !== 'docker') return;
            const res = await apiFetch('/api/docker/containers');
            if (res.ok) {
                const body = await res.json();
                dockerContainers.value = body.data || [];
            }
        };

        const controlContainer = async (id, action) => {
            const res = await apiFetch(`/api/docker/control?id=${id}&action=${action}`, { method: 'POST' });
            if (res.ok) {
                setTimeout(fetchContainers, 1000);
            } else {
                alert('操作失败: ' + await res.text());
            }
        };

        const fetchContainerLogs = async (id) => {
            dockerLogContent.value = '加载中...';
            showDockerLogs.value = true;
            const res = await apiFetch(`/api/docker/logs?id=${id}`);
            if (res.ok) {
                const body = await res.json();
                dockerLogContent.value = body.data || '无日志';
            } else {
                dockerLogContent.value = '获取日志失败';
            }
        };

        const inspectServer = async () => {
            const s = activeSession.value;
            if (!s) return;
            s.inspecting = true;
            try {
                const res = await apiFetch('/api/ssh/inspect', {
                    method: 'POST'
                });
                if (res && res.ok) {
                    const data = await res.json();
                    if (data.code === 200) {
                        s.inspectData = data.data;
                        sessions.value = [...sessions.value];
                    } else {
                        showAlert('探测失败: ' + data.msg);
                    }
                }
            } catch (e) {
                showAlert('网络错误');
            } finally {
                s.inspecting = false;
                sessions.value = [...sessions.value];
            }
        };

        // Poll docker containers every 5 seconds if tab is active
        setInterval(fetchContainers, 5000);
        watch(currentTab, (val) => { if(val === 'docker') fetchContainers(); });

        return {
            isWebLoggedIn, webPwd, showWorkspace, sessions, activeSessionId, activeSession, switchSession, closeSession, formHost, formPort, formUser, formPwd, loading, 
            currentTab, terminalEl, coreShortcuts, currentPath, fileList, transferList, activeFile, monitor,
            editingFile, saving, focusTerminal, syncPathToTerm, jumpToPath, inputPath,
            handleWebLogin, handleSshLogin, handleLogout, handleSshDisconnect, sendKey: (v) => sessionInstances.get(activeSessionId.value)?.socket?.send(v), 
            handleFileClick, goUp, formatSize, formatTime, isEditable, openEditor, saveFile, 
            closeEditor: () => (editingFile.value = null, refreshIcons()),
            auditLogs, getAuditTagClass, openMenu: (f) => (activeFile.value = f, refreshIcons()),
            uploadFile, downloadFile, renameFile, confirmDelete, clearTransfers,
            showConnectionForm, showBatchAddForm, batchAddForm, handleBatchAddBookmarks,
            getBookmarkIcon, getBookmarkColor, getBookmarkEmoji,
            selectedPaths, formIsKey, bookmarks, shouldSaveBookmark, bookmarkName, editingBookmarkId, isWsDisconnected, jumpServerHosts,
            useBookmark, deleteBookmark, snippets, executeSnippet, syncTermToFiles, syncFilesToTerm, fetchSnippets,
            snippetCategories, groupedSnippets, showBatchSnippetForm, batchSnippetText, batchSnippetCategory, handleBatchAddSnippets, searchSnippetQuery,
            toggleSnippetPin, pinnedSnippets, maxVisibleSnippets, saveMaxVisibleSnippets, displayedPinnedSnippets,
            systemConfig, toggleH5, updateAuditRetention, wipeAllData, killProcess,
            showSnippetManager, searchSnippetManagerQuery, filteredManagerSnippets, editingSnippet, editingSnippetIndex, openSnippetManager, openSnippetEditor, saveSnippet, deleteSnippet, refreshIcons, runEditingSnippet,
            dockerContainers, showDockerLogs, dockerLogContent, controlContainer, fetchContainerLogs, currentTheme, toggleTheme,
            batchDefaultPort, batchDefaultUser, batchDefaultPwd,
            batchServersInput, batchCommandInput, batchBurnMode, batchLoading, batchResult, executeBatchCommand,
            batchSelectedHosts, toggleAllBatchResults, exportSelectedBatchResults,
            viewingBatchDetail, viewBatchDetail, connectToBatchHost,
            isSplitMode, toggleSplitMode,
            fileSearch, fileSortBy, fileSortOrder, sortedFileList,
            transferSearch, transferTypeFilter, transferSortBy, transferSortOrder, filteredTransferList,
            customModal, showConfirm, showAlert, showPrompt,
            getFileExt, getFileSizeStr, inspectServer
        }
    }
}).mount('#app');