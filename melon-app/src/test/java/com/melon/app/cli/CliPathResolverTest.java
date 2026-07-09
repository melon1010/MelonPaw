package com.melon.app.cli;

import com.melon.app.cli.paths.CliPathResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliPathResolverTest {

    @Test
    void resolvesHomeRelativePathsWithoutMachineSpecificPrefixes() {
        CliPathResolver resolver = new CliPathResolver(Path.of("/tmp/melon-test-home"));
        assertEquals(Path.of("/tmp/melon-test-home/config.yaml"), resolver.configPath());
        assertEquals(Path.of("/tmp/melon-test-home/logs"), resolver.logDir());
    }

    @Test
    void expandsUserHome() {
        Path expanded = CliPathResolver.expandUser("~/melon");
        assertTrue(expanded.startsWith(Path.of(System.getProperty("user.home"))));
    }
}
