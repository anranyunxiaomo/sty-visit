package com.sty.visit.model;

import lombok.Data;

/**
 * 远程文件信息模型
 */
@Data
public class FileInfo {
    private String name;
    private String path;
    private boolean dir; 
    private long size;
    private String updateTime;
}
