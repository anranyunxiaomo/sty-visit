package com.sty.visit.util;

import com.jcraft.jsch.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

/**
 * 物理加固：强制直接连接 Socket 工厂
 * 显式绕过 JVM 系统级 SOCKS 代理设置，解决 Connection refused (SOCKS proxy) 故障。
 */
public class DirectSocketFactory implements SocketFactory {
    
    private final int timeout;

    public DirectSocketFactory(int timeout) {
        this.timeout = timeout;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        // [CORE FIX] 强制使用 Proxy.NO_PROXY，无视系统环境变量中的 socksProxyHost
        Socket socket = new Socket(Proxy.NO_PROXY);
        socket.connect(new InetSocketAddress(host, port), timeout);
        return socket;
    }

    @Override
    public InputStream getInputStream(Socket socket) throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream getOutputStream(Socket socket) throws IOException {
        return socket.getOutputStream();
    }
}
