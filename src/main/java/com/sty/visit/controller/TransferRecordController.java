package com.sty.visit.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sty.visit.model.Result;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transfers")
public class TransferRecordController {
    @org.springframework.beans.factory.annotation.Value("${visit.config-dir:config}")
    private String configDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping
    public Result<List<Map<String, Object>>> getTransfers() {
        try {
            File file = new File(configDir, "transfers.json");
            if (!file.exists()) return Result.success(new ArrayList<>());
            List<Map<String, Object>> list = mapper.readValue(file, mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            return Result.success(list);
        } catch (Exception e) {
            return Result.success(new ArrayList<>());
        }
    }

    @PostMapping
    public Result<String> saveTransfer(@RequestBody Map<String, Object> transfer) {
        try {
            List<Map<String, Object>> all = getTransfers().getData();
            String id = transfer.get("id").toString();
            all.removeIf(t -> t.get("id").toString().equals(id));
            all.add(0, transfer); // Insert at the beginning

            // Keep only latest 100
            if (all.size() > 100) {
                all = all.subList(0, 100);
            }

            File dir = new File(configDir);
            if (!dir.exists()) dir.mkdirs();
            mapper.writeValue(new File(configDir, "transfers.json"), all);
            return Result.success("Saved");
        } catch (Exception e) {
            return Result.error(500, "Failed: " + e.getMessage());
        }
    }

    @DeleteMapping
    public Result<String> clearTransfers() {
        try {
            File file = new File(configDir, "transfers.json");
            if (file.exists()) file.delete();
            return Result.success("Cleared");
        } catch (Exception e) {
            return Result.error(500, "Failed: " + e.getMessage());
        }
    }
}
