package com.sty.visit.model;

import lombok.Data;

/**
 * 审计日志条目模型
 */
@Data
public class AuditEntry {
    private String timestamp;
    private String ip;
    private String action;
    private String detail;
    private String status;
    private String riskLevel;
    private String clientType;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AuditEntry entry = new AuditEntry();

        public Builder timestamp(String timestamp) { entry.setTimestamp(timestamp); return this; }
        public Builder ip(String ip) { entry.setIp(ip); return this; }
        public Builder action(String action) { entry.setAction(action); return this; }
        public Builder detail(String detail) { entry.setDetail(detail); return this; }
        public Builder status(String status) { entry.setStatus(status); return this; }
        public Builder riskLevel(String riskLevel) { entry.setRiskLevel(riskLevel); return this; }
        public Builder clientType(String clientType) { entry.setClientType(clientType); return this; }

        public AuditEntry build() {
            return entry;
        }
    }
}
