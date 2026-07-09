package com.melon.app.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCliCommandTest {

    @TempDir Path tempDir;

    @Test
    void cleanDryRunDoesNotDeleteFiles() throws Exception {
        withHome(tempDir, () -> {
            Path file = tempDir.resolve("cache/item.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "data");
            assertEquals(0, new CommandLine(new MelonCli()).execute("clean", "--dry-run"));
            assertTrue(Files.exists(file));
        });
    }

    @Test
    void cleanYesDeletesChildrenButKeepsHome() throws Exception {
        withHome(tempDir, () -> {
            Path file = tempDir.resolve("cache/item.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "data");
            assertEquals(0, new CommandLine(new MelonCli()).execute("clean", "--yes"));
            assertTrue(Files.isDirectory(tempDir));
            assertFalse(Files.exists(file));
        });
    }

    @Test
    void doctorFixCreatesLocalLayout() throws Exception {
        withHome(tempDir, () -> {
            assertEquals(0, new CommandLine(new MelonCli()).execute("doctor", "fix"));
            assertTrue(Files.isDirectory(tempDir.resolve("cache")));
            assertTrue(Files.isDirectory(tempDir.resolve("logs")));
            assertTrue(Files.isDirectory(tempDir.resolve("workspaces/default")));
            assertTrue(Files.isRegularFile(tempDir.resolve("config.yaml")));
        });
    }

    @Test
    void daemonLogsReadsTail() throws Exception {
        withHome(tempDir, () -> {
            Path log = tempDir.resolve("logs/melon.log");
            Files.createDirectories(log.getParent());
            Files.writeString(log, "a\nb\nc\n");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream original = System.out;
            try {
                System.setOut(new PrintStream(out));
                assertEquals(0, new CommandLine(new MelonCli()).execute("daemon", "logs", "--lines", "2"));
            } finally {
                System.setOut(original);
            }
            String text = out.toString();
            assertFalse(text.contains("a\n"));
            assertTrue(text.contains("b"));
            assertTrue(text.contains("c"));
        });
    }

    private void withHome(Path home, ThrowingRunnable runnable) throws Exception {
        String old = System.getProperty("melon.home");
        try {
            System.setProperty("melon.home", home.toString());
            runnable.run();
        } finally {
            if (old == null) {
                System.clearProperty("melon.home");
            } else {
                System.setProperty("melon.home", old);
            }
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
