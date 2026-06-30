/**
 * @author melon
 */
package com.melon.core.util;

/**
 * 跨平台工具.
 */
public class PlatformUtil {

    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    public static String getDefaultShell() {
        if (isWindows()) {
            String comspec = System.getenv("COMSPEC");
            return comspec != null ? comspec : "cmd.exe";
        }
        String shell = System.getenv("SHELL");
        return shell != null ? shell : "/bin/sh";
    }
}
