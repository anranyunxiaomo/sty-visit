package com.sty.visit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;

/**
 * 系统配置服务：管理全局运行时策略，如 H5 访问开关
 */
@Service
public class SystemConfigService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SystemConfigService.class);
    
    @org.springframework.beans.factory.annotation.Value("${visit.config-dir:config}")
    private String configDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private SystemConfig config = new SystemConfig();

    public static class SystemConfig {
        private boolean h5Enabled = true; // 默认开启访问
        private int auditRetentionDays = 31; // 默认保留 31 天
        
        public boolean isH5Enabled() { return h5Enabled; }
        public void setH5Enabled(boolean h5Enabled) { this.h5Enabled = h5Enabled; }
        public int getAuditRetentionDays() { return auditRetentionDays; }
        public void setAuditRetentionDays(int auditRetentionDays) { this.auditRetentionDays = auditRetentionDays; }
    }

    @PostConstruct
    public void init() {
        File file = new File(configDir, "system.json");
        if (file.exists()) {
            try {
                config = mapper.readValue(file, SystemConfig.class);
                log.info("🛡️ SystemConfig Loaded: h5Enabled = {}, auditRetentionDays = {}", 
                    config.isH5Enabled(), config.getAuditRetentionDays());
            } catch (IOException e) {
                log.error("Failed to load system config, using defaults");
            }
        } else {
            save();
        }
    }

    public boolean isH5Enabled() {
        return config.isH5Enabled();
    }

    public synchronized void setH5Enabled(boolean enabled) {
        config.setH5Enabled(enabled);
        save();
        log.warn("🛡️ System Security Policy Changed: h5Enabled = {}", enabled);
    }

    public int getAuditRetentionDays() {
        return config.getAuditRetentionDays();
    }

    public synchronized void setAuditRetentionDays(int days) {
        config.setAuditRetentionDays(days);
        save();
        log.warn("🛡️ Audit Retention Policy Changed: days = {}", days);
    }

    private void save() {
        try {
            File dir = new File(configDir);
            if (!dir.exists()) dir.mkdirs();
            mapper.writeValue(new File(configDir, "system.json"), config);
        } catch (IOException e) {
            log.error("Failed to save system config");
        }
    }
}
