package com.sty.visit.service;

import com.sty.visit.model.AuditEntry;
import com.sty.visit.util.StyVisitConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 终极加固：系统审计日志服务中心
 * 实现全量操作记录的采集、内存缓冲与物理存证。
 */
@Service
public class AuditService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuditService.class);




    // 内存环形缓冲区：保留最近 1500 条记录
    private final List<AuditEntry> memoryLogs = new CopyOnWriteArrayList<>();
    private static final int MAX_MEMORY_LOGS = 1500;
    
    // 异步日志队列与后台处理器
    private final BlockingQueue<AuditEntry> diskQueue = new LinkedBlockingQueue<>(5000);
    private Thread writerThread;

    @org.springframework.beans.factory.annotation.Autowired
    private com.sty.visit.service.SystemConfigService systemConfigService;

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final java.util.concurrent.atomic.AtomicLong totalThreats = new java.util.concurrent.atomic.AtomicLong(0);
    private static final long MAX_DAILY_LOG_SIZE = 50 * 1024 * 1024; // 50MB 磁盘配额熔断点

    @javax.annotation.PostConstruct
    public void init() {
        try {
            File statsFile = new File("logs/threat_stats.dat");
            if (statsFile.exists()) {
                String val = new String(Files.readAllBytes(statsFile.toPath()), StandardCharsets.UTF_8);
                totalThreats.set(Long.parseLong(val.trim()));
            }
        } catch (Exception e) {
            log.warn("Intelligence Persistence: Starting with fresh threat counter.");
        }
        
        // 启动异步写盘守护线程
        writerThread = new Thread(this::processQueue, "audit-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void processQueue() {
        DateTimeFormatter dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String currentDay = "";
        BufferedWriter writer = null;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                AuditEntry entry = diskQueue.take();
                String entryDay = LocalDateTime.now().format(dayFormatter);
                
                // 如果跨天，则重新打开新的日志文件句柄
                if (!entryDay.equals(currentDay) || writer == null) {
                    if (writer != null) writer.close();
                    currentDay = entryDay;
                    java.io.File logDir = new java.io.File("logs");
                    if (!logDir.exists()) logDir.mkdirs();
                    writer = new BufferedWriter(new FileWriter("logs/audit-" + currentDay + ".jsonl", true));
                }

                writer.write(mapper.writeValueAsString(entry));
                writer.newLine();

                // 如果队列目前已空，则强制执行物理刷盘，确保日志实时可见性
                if (diskQueue.isEmpty()) {
                    writer.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Async Audit IO Stream Error", e);
            }
        }
        
        // 优雅停机：确保关闭句柄
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
    }

    private void persistThreatCount() {
        try {
            Files.write(Paths.get("logs/threat_stats.dat"), 
                    String.valueOf(totalThreats.get()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to persist threat intelligence: {}", e.getMessage());
        }
    }

    public void log(String ip, String action, String detail, String status) {
        log(ip, action, detail, status, "SERVER");
    }

    public void log(String ip, String action, String detail, String status, String clientType) {
        LocalDateTime now = LocalDateTime.now();
        AuditEntry entry = AuditEntry.builder()
                .timestamp(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .ip(ip)
                .action(action)
                .detail(detail)
                .status(status)
                .clientType(clientType)
                .build();
        
        // 自动风险提级逻辑 (Sentinel Logic)
        boolean isDangerousCmd = "TERMINAL_CMD".equals(action) && 
            (detail.contains("rm ") || detail.contains("mkfs") || detail.contains("dd ") || 
             detail.contains("shutdown") || detail.contains("poweroff") || detail.contains("reboot"));

        if (StyVisitConstants.STATUS_FAILURE.equals(status) || "ERROR".equals(status) || 
            action.contains("TRAVERSAL") || action.contains("DENIED") || action.contains("LOCKED") ||
            StyVisitConstants.ACTION_FILE_DELETE.equals(action) || 
            StyVisitConstants.ACTION_FILE_SAVE.equals(action) ||
            action.contains("DELETE") || isDangerousCmd) {
            entry.setRiskLevel("HIGH");
            totalThreats.incrementAndGet();
            persistThreatCount();
        } else {
            entry.setRiskLevel("INFO");
        }

        // 1. 内存高速写入 (无锁或细粒度锁)
        if (memoryLogs.size() >= MAX_MEMORY_LOGS) {
            memoryLogs.remove(0);
        }
        memoryLogs.add(entry);

        // 2. 异步入队进行物理持久化，彻底解放业务线程
        if (!diskQueue.offer(entry)) {
            log.warn("Audit disk queue full, dropping overflow log entry to protect server stability.");
        }
    }

    public long getTotalThreats() {
        return totalThreats.get();
    }

    /**
     * 自动清理 expired 日志 (正则锚定防御)
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 1 * * ?")
    public void cleanOldLogs() {
        log.info("Starting audit log retention task...");
        java.io.File logDir = new java.io.File("logs");
        if (!logDir.exists() || !logDir.isDirectory()) return;

        java.io.File[] files = logDir.listFiles((dir, name) -> name.startsWith("audit-") && name.endsWith(".jsonl"));
        if (files == null) return;

        LocalDateTime limit = LocalDateTime.now().minusDays(systemConfigService.getAuditRetentionDays());
        DateTimeFormatter fileDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("audit-(\\d{4}-\\d{2}-\\d{2})\\.jsonl");

        for (java.io.File file : files) {
            try {
                java.util.regex.Matcher matcher = pattern.matcher(file.getName());
                if (matcher.find()) {
                    String dateStr = matcher.group(1);
                    LocalDateTime fileDate = java.time.LocalDate.parse(dateStr, fileDateFormatter).atStartOfDay();
                    if (fileDate.isBefore(limit)) {
                        if (file.delete()) log.info("Deleted expired audit log: {}", file.getName());
                    }
                }
            } catch (Exception e) {
                log.warn("Robustness Notice: Skipping non-standard audit file: {}", file.getName());
            }
        }
    }

    public List<AuditEntry> getRecentLogs() {
        List<AuditEntry> reversed = new ArrayList<>(memoryLogs);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    /**
     * [ALIGNMENT] 终极加固：物理级隐私擦除
     * 对齐功能清单 1.2 "物理隐私擦除（全量清空数据库记录）"
     */
    public void clearAllLogs() {
        // 1. 擦除内存
        clearMemoryLogs();
        
        // 2. 物理销毁磁盘文件
        File logDir = new File("logs");
        if (logDir.exists() && logDir.isDirectory()) {
            File[] files = logDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.delete()) log.info("🛡️ Privacy Wipe: Physical log destroyed: {}", f.getName());
                }
            }
        }
        totalThreats.set(0);
        persistThreatCount();
    }

    /**
     * 终极加固：物理擦除内存审计记录 (用于用户登出或隐私清理)
     */
    public void clearMemoryLogs() {
        memoryLogs.clear();
        log.info("🛡️ Audit privacy: Memory logs cleared successfully.");
    }
}
