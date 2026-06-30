/**
 * @author melon
 */
package com.melon.tools.shell;

import java.lang.management.ManagementFactory;
import java.util.regex.Pattern;

/**
 * Utility functions for shell command execution.
 * Provides self-kill detection, default shell resolution, and cross-platform command wrapping.
 */
public final class ShellCommandUtil {

    private static final String CURRENT_PID = String.valueOf(ProcessHandle.current().pid());

    // Patterns for detecting self-kill commands
    private static final Pattern[] SELF_KILL_PATTERNS = {
        // Windows taskkill targeting current PID
        Pattern.compile("taskkill\\s+.*(?:/PID|/pid)\\s+" + Pattern.quote(CURRENT_PID), Pattern.CASE_INSENSITIVE),
        // Windows taskkill targeting current process name
        Pattern.compile("taskkill\\s+.*(?:/IM|/im)\\s+(?:java|javaw)\\.exe", Pattern.CASE_INSENSITIVE),
        // Unix kill targeting current PID
        Pattern.compile("(?:^|&&|;|\\|)\\s*kill\\s+.*-\\d*\\s+" + Pattern.quote(CURRENT_PID) + "\\b"),
        Pattern.compile("kill\\s+-9\\s+" + Pattern.quote(CURRENT_PID) + "\\b"),
        Pattern.compile("kill\\s+" + Pattern.quote(CURRENT_PID) + "\\b"),
        // pkill targeting java process
        Pattern.compile("pkill\\s+.*java", Pattern.CASE_INSENSITIVE),
        // killall targeting java
        Pattern.compile("killall\\s+.*java", Pattern.CASE_INSENSITIVE),
        // curl piped to bash/sh (remote code execution)
        Pattern.compile("curl\\s+.*\\|\\s*(?:bash|sh)\\b", Pattern.CASE_INSENSITIVE),
        // wget piped to bash/sh (remote code execution)
        Pattern.compile("wget\\s+.*\\|\\s*(?:bash|sh)\\b", Pattern.CASE_INSENSITIVE),
        // eval with command substitution (potentially dangerous)
        Pattern.compile("\\beval\\s+.*\\$\\(", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\beval\\s+.*`", Pattern.CASE_INSENSITIVE),
        // exec replacing the current process
        Pattern.compile("\\bexec\\s+\\S+", Pattern.CASE_INSENSITIVE),
        // kill current shell process ($$)
        Pattern.compile("kill\\s+\\$\\$"),
        // kill parent process ($PPID)
        Pattern.compile("kill\\s+\\$PPID", Pattern.CASE_INSENSITIVE),
        // pkill targeting current PID directly
        Pattern.compile("pkill\\s+.*-\\d*\\s+" + Pattern.quote(CURRENT_PID) + "\\b"),
        // killall targeting current process name
        Pattern.compile("killall\\s+.*java\\s+" + Pattern.quote(CURRENT_PID), Pattern.CASE_INSENSITIVE),
    };

    private ShellCommandUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * Detects whether a command is attempting to kill the current process (self-kill).
     * Checks for taskkill, kill, pkill, killall, curl|bash, wget|sh, eval, exec,
     * and kill $$ / $PPID patterns targeting the current PID or the Java process.
     *
     * @param command the command string to check
     * @return true if the command is detected as a self-kill attempt, false otherwise
     */
    public static boolean isDangerousSelfKill(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        for (Pattern pattern : SELF_KILL_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the default shell for the current platform.
     * <ul>
     *   <li>Windows: returns {@code "cmd.exe"}</li>
     *   <li>Unix: reads the {@code $SHELL} environment variable;
     *       falls back to {@code "/bin/bash"} if unset</li>
     * </ul>
     *
     * @return the default shell command path
     */
    public static String getDefaultShell() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "cmd.exe";
        }
        String shell = System.getenv("SHELL");
        return (shell != null && !shell.isBlank()) ? shell : "/bin/bash";
    }

    /**
     * Wraps a command for platform-specific execution by prepending the
     * appropriate shell invocation prefix.
     * <ul>
     *   <li>Windows: wraps with {@code cmd /D /S /C }</li>
     *   <li>Unix: wraps with the default shell and {@code -c } flag</li>
     * </ul>
     *
     * @param command the raw command string to wrap
     * @return the wrapped command string ready for platform execution
     */
    public static String wrapCommand(String command) {
        if (command == null) {
            return "";
        }
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "cmd /D /S /C " + command;
        } else {
            return getDefaultShell() + " -c " + command;
        }
    }
}
