package com.melon.app.cli;

import com.melon.app.cli.paths.CliPathResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * CLI command to initialize a Melon workspace.
 */
@Command(name = "init", description = "Initialize Melon workspace", mixinStandardHelpOptions = true)
public class InitCommand implements Callable<Integer> {

    @Option(names = "--dir", defaultValue = ".", description = "Workspace directory")
    String dir;

    @Option(names = "--force", description = "Overwrite existing generated files")
    boolean force;

    @Option(names = "--defaults", description = "Use defaults only, no interactive prompts")
    boolean defaults;

    @Option(names = "--accept-security", description = "Accept security notice for non-interactive init")
    boolean acceptSecurity;

    @Override
    public Integer call() {
        Path workspace = CliPathResolver.expandUser(dir);
        CliPathResolver paths = new CliPathResolver();
        System.out.println("Initializing Melon workspace at " + workspace);
        System.out.println("Melon home: " + paths.homeDir());
        System.out.println();

        try {
            Path melonDir = workspace.resolve(".melon");
            Files.createDirectories(melonDir.resolve("skills"));
            Files.createDirectories(melonDir.resolve("state"));
            Files.createDirectories(paths.homeDir().resolve("skills"));
            Files.createDirectories(paths.cacheDir());
            Files.createDirectories(paths.logDir());

            writeIfNeeded(workspace.resolve("AGENTS.md"), AGENTS_MD_TEMPLATE, force);
            writeIfNeeded(melonDir.resolve("config.yaml"), CONFIG_YML_TEMPLATE, force);
            writeIfNeeded(paths.configPath(), CONFIG_YML_TEMPLATE, force);

            System.out.println();
            System.out.println("Melon workspace initialized successfully.");
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    private void writeIfNeeded(Path path, String content, boolean forceWrite) throws IOException {
        if (Files.exists(path) && !forceWrite) {
            System.out.println("  Skipped (already exists): " + path);
            return;
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        System.out.println("  Created " + path);
    }

    private static final String AGENTS_MD_TEMPLATE = """
            # AGENTS.md

            This file provides guidance to AI agents working in this repository.

            ## Project Overview

            <!-- Describe your project here -->

            ## Build & Test

            <!-- Add build and test commands here -->

            ## Coding Guidelines

            <!-- Add coding guidelines here -->
            """;

    private static final String CONFIG_YML_TEMPLATE = """
            server:
              host: 127.0.0.1
              port: 8088

            home_dir: ~/.melonAI

            agents:
              default:
                name: default
                active_model: dashscope:qwen-plus
                system_prompt_files:
                  - AGENTS.md
                skills: []
                approval:
                  level: AUTO
            """;
}
