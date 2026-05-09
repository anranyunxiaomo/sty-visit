package com.sty.visit.service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import com.sty.visit.model.FileInfo;

/**
 * 远程协议抽象栈：定义跨协议的文件交互标准
 * 实现业务层与底层物理协议（SFTP/FTP/LOCAL）的完全解耦
 */
public interface IRemoteProtocol {
    List<FileInfo> list(String path) throws Exception;
    String readString(String path) throws Exception;
    void writeString(String path, String content) throws Exception;
    void remove(String path) throws Exception;
    void upload(String path, InputStream in) throws Exception;
    void download(String path, OutputStream out) throws Exception;
    String pwd() throws Exception;
}
