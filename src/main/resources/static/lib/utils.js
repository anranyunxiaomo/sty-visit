/**
 * 天穹 - 通用工具集
 * 包含加密、格式化等可复用逻辑
 */
const VisitUtils = {
    /**
     * 格式化字节大小
     */
    formatSize(b) {
        if (b === undefined || b === null || isNaN(b)) return '0 B';
        if (b < 1024) return b + ' B';
        if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB';
        return (b / (1024 * 1024)).toFixed(1) + ' MB';
    },

    /**
     * 密码加固 (AES-GCM)
     */
    async protectPwd(pwd, sessionKey) {
        if (!pwd) return '';
        if (pwd.startsWith('PROTECTED:AES:')) return pwd.substring(14);
        if (!sessionKey) return btoa(pwd);
        try {
            const pwdData = new TextEncoder().encode(pwd);
            const rawKey = new Uint8Array(atob(sessionKey).split('').map(c => c.charCodeAt(0)));
            const cryptoKey = await crypto.subtle.importKey('raw', rawKey, { name: 'AES-GCM' }, false, ['encrypt']);
            const iv = crypto.getRandomValues(new Uint8Array(12));
            const encrypted = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, cryptoKey, pwdData);
            return btoa(String.fromCharCode(...iv)) + ':' + btoa(String.fromCharCode(...new Uint8Array(encrypted)));
        } catch (e) { return btoa(pwd); }
    },

    /**
     * 密码还原
     */
    async revealPwd(protectedStr, sessionKey) {
        if (!protectedStr || !protectedStr.includes(':')) return atob(protectedStr);
        if (!sessionKey) return '';
        try {
            const [ivBase64, cipherBase64] = protectedStr.split(':');
            const iv = new Uint8Array(atob(ivBase64).split('').map(c => c.charCodeAt(0)));
            const cipher = new Uint8Array(atob(cipherBase64).split('').map(c => c.charCodeAt(0)));
            const rawKey = new Uint8Array(atob(sessionKey).split('').map(c => c.charCodeAt(0)));
            const cryptoKey = await crypto.subtle.importKey('raw', rawKey, { name: 'AES-GCM' }, false, ['decrypt']);
            const decrypted = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, cryptoKey, cipher);
            return new TextDecoder().decode(decrypted);
        } catch (e) { return ''; }
    }
};

window.VisitUtils = VisitUtils;
