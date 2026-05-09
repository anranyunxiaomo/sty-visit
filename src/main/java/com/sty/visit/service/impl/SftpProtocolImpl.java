package com.sty.visit.service.impl;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.sty.visit.model.FileInfo;
import com.sty.visit.service.IRemoteProtocol;
import com.sty.visit.security.ISecurityPolicy;
import com.sty.visit.security.impl.DefaultSecurityPolicy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * SFTP 协议的具体实现
 * 具备递归处理、路径规范化与 DTO 严格对齐能力。
 */
public class SftpProtocolImpl implements IRemoteProtocol {
    private final Session session;
    private final ISecurityPolicy securityPolicy = new DefaultSecurityPolicy();

    public SftpProtocolImpl(Session session) {
        this.session = session;
    }

    private ChannelSftp getChannel() throws Exception {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.setFilenameEncoding("UTF-8");
        sftp.connect();
        return sftp;
    }

    @Override
    public List<FileInfo> list(String path) throws Exception {
        securityPolicy.validatePath(path);
        ChannelSftp sftp = getChannel();
        try {
            String target = (path == null || path.isEmpty()) ? "." : path;
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(target);
            String pwd = sftp.realpath(target);
            
            List<FileInfo> list = new ArrayList<>();
            for (ChannelSftp.LsEntry e : entries) {
                if (".".equals(e.getFilename()) || "..".equals(e.getFilename())) continue;
                FileInfo info = new FileInfo();
                info.setName(e.getFilename());
                info.setPath(pwd + (pwd.endsWith("/") ? "" : "/") + e.getFilename());
                info.setDir(e.getAttrs().isDir());
                info.setSize(e.getAttrs().getSize());
                info.setUpdateTime(e.getAttrs().getMtimeString());
                list.add(info);
            }
            return list;
        } finally {
            sftp.disconnect();
        }
    }

    @Override
    public String readString(String path) throws Exception {
        securityPolicy.validatePath(path);
        ChannelSftp sftp = getChannel();
        // [SAFETY] 2MB 物理防御阈值，防止读取超大二进制文件导致 JVM 溢出
        long size = sftp.stat(path).getSize();
        if (size > 2 * 1024 * 1024) throw new SecurityException("文件过大 (>" + (size/1024/1024) + "MB)，禁止通过文本模式读取");
        
        try (InputStream is = sftp.get(path);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
            return bos.toString(StandardCharsets.UTF_8.name());
        } finally {
            sftp.disconnect();
        }
    }

    @Override
    public void writeString(String path, String content) throws Exception {
        securityPolicy.validatePath(path);
        ChannelSftp sftp = getChannel();
        try (InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            sftp.put(is, path);
        } finally {
            sftp.disconnect();
        }
    }

    @Override
    public void remove(String path) throws Exception {
        securityPolicy.validatePath(path);
        ChannelSftp sftp = getChannel();
        try {
            if (sftp.stat(path).isDir()) {
                removeRecursive(sftp, path);
            } else {
                sftp.rm(path);
            }
        } finally {
            sftp.disconnect();
        }
    }

    private void removeRecursive(ChannelSftp sftp, String path) throws SftpException {
        Vector<ChannelSftp.LsEntry> entries = sftp.ls(path);
        for (ChannelSftp.LsEntry e : entries) {
            String name = e.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            String fullPath = path + (path.endsWith("/") ? "" : "/") + name;
            
            // [Karpathy 谨慎准则]：递归过程中的每一级路径都必须经过物理校验，防止符号链接劫持
            securityPolicy.validatePath(fullPath);
            
            if (e.getAttrs().isDir()) {
                removeRecursive(sftp, fullPath);
            } else {
                sftp.rm(fullPath);
            }
        }
        sftp.rmdir(path);
    }

    @Override
    public void upload(String path, InputStream in) throws Exception {
        securityPolicy.validatePath(path);
        ChannelSftp sftp = getChannel();
        try (InputStream is = in) {
            sftp.put(is, path);
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * 下载资源：仅托管 SFTP 输入流，输出流生命周期由调用方（如 Web 容器）管理
     */
    @Override
    public void download(String path, OutputStream out) throws Exception {
        securityPolicy.validatePath(path);
        ChannelSftp sftp = getChannel();
        try (InputStream is = sftp.get(path)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) out.write(buf, 0, len);
        } finally {
            sftp.disconnect();
        }
    }
    @Override
    public String pwd() throws Exception {
        ChannelSftp sftp = getChannel();
        try {
            return sftp.pwd();
        } finally {
            sftp.disconnect();
        }
    }
}
