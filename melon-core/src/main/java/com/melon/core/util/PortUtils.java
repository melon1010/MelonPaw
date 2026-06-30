/**
 * @author melon
 */
package com.melon.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 端口工具. 检查端口可用性, 查找空闲端口.
 * Corresponds to Python port_utils.
 */
public final class PortUtils {

    private static final Logger log = LoggerFactory.getLogger(PortUtils.class);

    private PortUtils() {}

    /**
     * 检查端口是否可用 (可绑定).
     */
    public static boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 检查端口是否被占用.
     */
    public static boolean isPortInUse(int port) {
        return !isPortAvailable(port);
    }

    /**
     * 检查端口是否可连接 (远端是否有服务监听).
     */
    public static boolean canConnect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 在指定范围内查找第一个可用端口.
     * @param startPort 起始端口 (含)
     * @param endPort 结束端口 (含)
     * @return 可用端口号, 如全部占用返回 -1
     */
    public static int findAvailablePort(int startPort, int endPort) {
        for (int port = startPort; port <= endPort; port++) {
            if (isPortAvailable(port)) {
                log.debug("Found available port: {}", port);
                return port;
            }
        }
        log.warn("No available port found in range {}-{}", startPort, endPort);
        return -1;
    }

    /**
     * 查找一个可用端口 (从系统分配).
     * 让操作系统分配临时端口.
     */
    public static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            log.error("Failed to find available port", e);
            return -1;
        }
    }

    /**
     * 等待端口变为可用, 超时后返回.
     * @param port 目标端口
     * @param timeoutMillis 超时毫秒数
     * @return 端口可用返回 true, 超时返回 false
     */
    public static boolean waitForPort(int port, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (isPortAvailable(port)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
