package com.melon.core.util;

import java.util.List;
import java.util.Map;

/**
 * 启动显示工具. 打印启动 Banner、服务地址和端口、已加载的 Agent 列表.
 * 对应 Python 的 startup_display 工具函数.
 */
public final class StartupDisplayUtil {

    private StartupDisplayUtil() {
    }

    // ======================== Banner ========================

    /**
     * 启动 Banner.
     */
    private static final String BANNER = """
            ====================================================
                  ____                          _ _
                 |  _ \\ __ ___   ___   __| | |__
                 | |_) / _` \\ \\ / / | |_| / _ \\
                 |  _ < (_| |\\ V /|__   | |  __/
                 |_| \\_\\__,_| \\_/    |_| |_|\\___|
            ====================================================""";

    /**
     * 打印启动 Banner.
     */
    public static void printBanner() {
        System.out.println(BANNER);
        System.out.println("  Version: " + getVersion());
        System.out.println("  Java: " + SystemInfoUtil.getJavaVersion());
        System.out.println("  OS: " + SystemInfoUtil.getOSName()
                + " (" + SystemInfoUtil.getOsArch() + ")");
        System.out.println("  Processors: " + SystemInfoUtil.getAvailableProcessors());
        System.out.println("  Max Memory: " + SystemInfoUtil.formatBytes(SystemInfoUtil.getMaxMemory()));
        System.out.println("====================================================");
    }

    // ======================== Server Info ========================

    /**
     * 打印服务地址和端口信息.
     *
     * @param host 监听地址
     * @param port 监听端口
     * @param contextPath 上下文路径
     */
    public static void printServerInfo(String host, int port, String contextPath) {
        String basePath = contextPath != null && !contextPath.isBlank()
                ? contextPath : "";
        System.out.println();
        System.out.println("----------------------------------------------------");
        System.out.println("  Melon server started successfully!");
        System.out.println("----------------------------------------------------");
        System.out.println("  Local:      http://localhost:" + port + basePath);
        if (host != null && !host.equals("0.0.0.0") && !host.equals("*")) {
            System.out.println("  External:   http://" + host + ":" + port + basePath);
        } else {
            System.out.println("  Network:    http://<your-ip>:" + port + basePath);
        }
        System.out.println("----------------------------------------------------");
        System.out.println();
    }

    /**
     * 打印服务地址和端口信息 (无上下文路径).
     */
    public static void printServerInfo(String host, int port) {
        printServerInfo(host, port, "");
    }

    // ======================== Agent List ========================

    /**
     * 打印已加载的 Agent 列表.
     *
     * @param agents Agent 信息列表, 每项是 Map 包含 id, name, model, running 等字段
     */
    public static void printAgentList(List<Map<String, Object>> agents) {
        System.out.println("----------------------------------------------------");
        System.out.println("  Loaded Agents (" + agents.size() + "):");
        System.out.println("----------------------------------------------------");
        if (agents.isEmpty()) {
            System.out.println("  (no agents configured)");
        } else {
            for (Map<String, Object> agent : agents) {
                String id = strOr(agent.get("id"), "?");
                String name = strOr(agent.get("name"), id);
                String model = strOr(agent.get("active_model"), strOr(agent.get("model"), "n/a"));
                boolean running = Boolean.TRUE.equals(agent.get("running"));
                String status = running ? "[RUNNING]" : "[STOPPED]";
                System.out.printf("  %s %-12s | model: %-30s | %s%n",
                        status, id, model, name);
            }
        }
        System.out.println("----------------------------------------------------");
        System.out.println();
    }

    // ======================== Providers ========================

    /**
     * 打印已注册的 Provider 列表.
     *
     * @param providers Provider ID 列表
     */
    public static void printProviders(List<String> providers) {
        System.out.println("----------------------------------------------------");
        System.out.println("  Registered Providers (" + providers.size() + "):");
        System.out.println("----------------------------------------------------");
        if (providers.isEmpty()) {
            System.out.println("  (no providers registered)");
        } else {
            for (String provider : providers) {
                System.out.println("  - " + provider);
            }
        }
        System.out.println("----------------------------------------------------");
        System.out.println();
    }

    // ======================== Shutdown ========================

    /**
     * 打印关闭信息.
     */
    public static void printShutdown() {
        System.out.println();
        System.out.println("====================================================");
        System.out.println("  Melon is shutting down...");
        System.out.println("  Goodbye!");
        System.out.println("====================================================");
    }

    // ======================== Helpers ========================

    /**
     * 获取版本号.
     */
    private static String getVersion() {
        return System.getProperty("melon.version", "dev");
    }

    private static String strOr(Object value, String defaultValue) {
        return value != null ? value.toString() : defaultValue;
    }
}
