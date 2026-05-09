package com.sty.visit.controller;

import com.sty.visit.model.Result;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import javax.servlet.http.HttpServletRequest;

/**
 * 指令脚本库控制器 (Snippet Manager)
 * 实现运维常用指令的持久化存储与库管理。
 */
@RestController
@RequestMapping("/api/snippets")
public class SnippetController {

    @org.springframework.beans.factory.annotation.Value("${visit.config-dir:config}")
    private String configDir;

    private final ObjectMapper mapper = new ObjectMapper();

    public static class Snippet {
        private String name;
        private String command;
        private String category;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }

    @GetMapping
    public Result<List<Snippet>> getSnippets() {
        try {
            File file = new File(configDir, "snippets.json");
            if (!file.exists()) {
                return Result.success(initDefaultSnippets());
            }
            List<Snippet> list = mapper.readValue(file, mapper.getTypeFactory().constructCollectionType(List.class, Snippet.class));
            return Result.success(list);
        } catch (Exception e) {
            return Result.success(initDefaultSnippets());
        }
    }

    @Autowired
    private com.sty.visit.service.AuditService auditService;

    @PostMapping
    public Result<String> saveSnippets(HttpServletRequest request, @RequestBody List<Snippet> snippets) {
        try {
            File dir = new File(configDir);
            if (!dir.exists()) dir.mkdirs();
            mapper.writeValue(new File(configDir, "snippets.json"), snippets);
            auditService.log(request.getRemoteAddr(), "UPDATE_SNIPPETS", "Updated " + snippets.size() + " command snippets", "SUCCESS");
            return Result.success("Snippets Saved");
        } catch (Exception e) {
            auditService.log(request.getRemoteAddr(), "UPDATE_SNIPPETS", "Failed to update snippets: " + e.getMessage(), "FAILED");
            return Result.error(500, "Save Failed: " + e.getMessage());
        }
    }

    private List<Snippet> initDefaultSnippets() {
        List<Snippet> list = new ArrayList<>();
        list.add(create("查看进程", "ps aux | head -n 20", "System"));
        list.add(create("网络连接", "netstat -tulnp", "Network"));
        list.add(create("Docker 状态", "docker ps -a", "Docker"));
        list.add(create("内存清理", "sync; echo 3 > /proc/sys/vm/drop_caches", "Tool"));
        list.add(create("系统负载", "uptime", "System"));
        list.add(create("防火墙状态", "ufw status", "Security"));
        return list;
    }

    private Snippet create(String name, String cmd, String cat) {
        Snippet s = new Snippet();
        s.setName(name);
        s.setCommand(cmd);
        s.setCategory(cat);
        return s;
    }
}
