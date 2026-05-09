package com.sty.visit.service;

import com.jcraft.jsch.Session;
import com.sty.visit.model.FileInfo;
import com.sty.visit.service.impl.SftpProtocolImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 健硕版文件业务实现
 * 彻底实现业务逻辑与底层物理协议的解耦。
 */
@Slf4j
@Service
public class FileService implements IFileService {

    @Override
    public List<FileInfo> listFiles(Session session, String path) throws Exception {
        return ProtocolFactory.getProtocol(session).list(path);
    }

    @Override
    public String getFileContent(Session session, String path) throws Exception {
        return ProtocolFactory.getProtocol(session).readString(path);
    }

    @Override
    public void saveFile(Session session, String path, String content) throws Exception {
        ProtocolFactory.getProtocol(session).writeString(path, content);
    }

    @Override
    public void deletePath(Session session, String path) throws Exception {
        // [DECOUPLING] 业务层不再处理递归删除细节，由抽象协议层负责物理执行
        ProtocolFactory.getProtocol(session).remove(path);
    }

    @Override
    public void uploadFile(Session session, String path, java.io.InputStream in) throws Exception {
        ProtocolFactory.getProtocol(session).upload(path, in);
    }

    @Override
    public void downloadFile(Session session, String path, java.io.OutputStream out) throws Exception {
        ProtocolFactory.getProtocol(session).download(path, out);
    }

    @Override
    public String getCurrentPath(Session session) throws Exception {
        return ProtocolFactory.getProtocol(session).pwd();
    }
}
