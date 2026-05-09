package com.sty.visit.service;

import com.jcraft.jsch.Session;
import com.sty.visit.model.FileInfo;
import java.util.List;

/**
 * 文件业务契约：定义跨协议的文件操作标准
 */
public interface IFileService {
    List<FileInfo> listFiles(Session session, String path) throws Exception;
    String getFileContent(Session session, String path) throws Exception;
    void saveFile(Session session, String path, String content) throws Exception;
    void deletePath(Session session, String path) throws Exception;
    void uploadFile(Session session, String path, java.io.InputStream in) throws Exception;
    void downloadFile(Session session, String path, java.io.OutputStream out) throws Exception;
    String getCurrentPath(Session session) throws Exception;
}
