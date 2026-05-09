package com.sty.visit.util;

/**
 * 全局业务常量库
 */
public class StyVisitConstants {
    // 安全配置
    public static final String CONFIG_DIR = "config";
    public static final String SYSTEM_CONFIG_PATH = "config/system.json";
    
    // HTTP Header & Auth
    public static final String AUTH_HEADER = "Authorization";
    public static final String AUTH_PREFIX = "Bearer ";
    public static final String ENCRYPTED_HEADER = "X-Visit-Encrypted";
    public static final String IV_HEADER = "X-Visit-IV";
    
    // 审计动作
    public static final String ACTION_LOGIN = "登录系统";
    public static final String ACTION_SSH_LOGIN = "终端连接";
    public static final String ACTION_FILE_SAVE = "保存文件";
    public static final String ACTION_FILE_DELETE = "删除文件";
    public static final String ACTION_FILE_UPLOAD = "上传文件";
    public static final String ACTION_FILE_DOWNLOAD = "下载文件";
    public static final String ACTION_LOGOUT = "退出系统";
    
    // 状态
    public static final String STATUS_SUCCESS = "成功";
    public static final String STATUS_FAILURE = "失败";
}
