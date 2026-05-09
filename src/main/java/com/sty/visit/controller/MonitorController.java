package com.sty.visit.controller;

import com.sty.visit.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控控制器：提供系统负载、内存及磁盘使用情况
 * 为看板提供实时数据支撑
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // 1. 获取 CPU 与 内存负载 (使用 ManagementFactory)
        com.sun.management.OperatingSystemMXBean osBean = 
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        
        double cpuLoad = osBean.getSystemCpuLoad() * 100;
        if (cpuLoad < 0) cpuLoad = 0; // 适配部分环境初始获取为 -1 的情况
        
        long totalMem = osBean.getTotalPhysicalMemorySize();
        long freeMem = osBean.getFreePhysicalMemorySize();
        double memUsage = (totalMem > 0) ? (double) (totalMem - freeMem) / totalMem * 100 : 0;

        stats.put("cpu", cpuLoad);
        stats.put("mem", memUsage);
        stats.put("load", osBean.getSystemLoadAverage());

        // 2. 获取运行时间 (Uptime)
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        stats.put("uptime", formatUptime(uptimeMs));

        // 3. 获取磁盘信息
        List<Map<String, Object>> disks = new ArrayList<>();
        File[] roots = File.listRoots();
        for (File root : roots) {
            Map<String, Object> diskInfo = new HashMap<>();
            long total = root.getTotalSpace();
            long free = root.getFreeSpace();
            long usable = total - free;
            double usage = (total > 0) ? (double) usable / total * 100 : 0;
            
            diskInfo.put("mount", root.getPath());
            diskInfo.put("total", total);
            diskInfo.put("usage", Math.round(usage));
            disks.add(diskInfo);
        }
        stats.put("disks", disks);

        return Result.success(stats);
    }

    private String formatUptime(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        return String.format("%d天 %02d:%02d:%02d", days, hours % 24, minutes % 60, seconds % 60);
    }
}
