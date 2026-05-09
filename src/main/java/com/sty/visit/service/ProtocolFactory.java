package com.sty.visit.service;

import com.jcraft.jsch.Session;
import com.sty.visit.service.impl.SftpProtocolImpl;

/**
 * 远程协议工厂 (Factory Pattern)
 * 屏蔽协议实现的实例化细节，支持未来无感扩展 FTP/SMB 等协议。
 */
public class ProtocolFactory {
    
    /**
     * 根据会话类型自动选择最优协议栈
     */
    public static IRemoteProtocol getProtocol(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("Protocol logic error: Session required");
        }
        
        // 默认采用高安全性的 SFTP 协议实现
        return new SftpProtocolImpl(session);
    }
}
