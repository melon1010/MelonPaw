package com.melon.app.cli;

import com.melon.app.cli.paths.CliPathResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = "clean", description = "Clear Melon home data", mixinStandardHelpOptions = true)
public class CleanCommand implements Callable<Integer> {
    @Option(names = "--dry-run", description = "Show what would be deleted") boolean dryRun;
    @Option(names = "--yes", description = "Confirm deletion") boolean yes;

    @Override
    public Integer call() {
        CliPathResolver paths = new CliPathResolver();
        Path home = paths.homeDir();
        if (!Files.exists(home)) {
            System.out.println("Melon home does not exist: " + home);
            return 0;
        }
        if (!dryRun && !yes) {
            System.err.println("Refusing to clean without --yes. Use --dry-run to preview.");
            return 1;
        }
        try (Stream<Path> stream = Files.list(home)) {
            for (Path child : stream.toList()) {
                clean(child);
            }
        } catch (Exception e) {
            System.err.println("Clean failed: " + e.getMessage());
            return 1;
        }
        System.out.println((dryRun ? "Would clean " : "Cleaned ") + home);
        return 0;
    }

    private void clean(Path path) throws Exception {
        if (dryRun) {
            System.out.println(path);
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
