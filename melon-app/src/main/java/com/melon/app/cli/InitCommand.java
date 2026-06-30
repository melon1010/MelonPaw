/**
 * @author melon
 */
package com.melon.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * CLI command to initialize a Melon workspace.
 * Creates the .melon directory structure, AGENTS.md template, and config.yaml.
 */
@Command(name = "init", description = "Initialize Melon workspace", mixinStandardHelpOptions = true)
public class InitCommand implements Callable<Integer> {

    @Option(names = "--dir", defaultValue = ".", description = "Workspace directory")
    String dir;

    @Override
    public Integer call() {
        Path workspace = Path.of(dir).toAbsolutePath().normalize();
        System.out.println("Initializing Melon workspace at " + workspace);
        System.out.println();

        try {
            // 1. Create .melon directory structure in workspace
            Path melonDir = workspace.resolve(".melon");
            Files.createDirectories(melonDir);
            System.out.println("  Created " + melonDir);

            Path sessionsDir = melonDir.resolve("sessions");
            Files.createDirectories(sessionsDir);
            System.out.println("  Created " + sessionsDir);

            Path skillsDir = melonDir.resolve("skills");
            Files.createDirectories(skillsDir);
            System.out.println("  Created " + skillsDir);

            Path stateDir = melonDir.resolve("state");
            Files.createDirectories(stateDir);
            System.out.println("  Created " + stateDir);

            // 2. Create AGENTS.md template
            Path agentsMd = workspace.resolve("AGENTS.md");
            if (!Files.exists(agentsMd)) {
                Files.writeString(agentsMd, AGENTS_MD_TEMPLATE);
                System.out.println("  Created " + agentsMd);
            } else {
                System.out.println("  Skipped (already exists): " + agentsMd);
            }

            // 3. Create config.yaml
            Path configYml = melonDir.resolve("config.yaml");
            if (!Files.exists(configYml)) {
                Files.writeString(configYml, CONFIG_YML_TEMPLATE);
                System.out.println("  Created " + configYml);
            } else {
                System.out.println("  Skipped (already exists): " + configYml);
            }

            // 4. Ensure global ~/.melon directory structure exists
            Path homeMelon = Path.of(System.getProperty("user.home"), ".melon");
            Files.createDirectories(homeMelon.resolve("sessions"));
            Files.createDirectories(homeMelon.resolve("skills"));
            System.out.println("  Ensured global directory structure at " + homeMelon);

            System.out.println();
            System.out.println("Melon workspace initialized successfully.");
            System.out.println("  Edit " + agentsMd + " to customize your agent.");
            System.out.println("  Edit " + configYml + " to configure models and tools.");
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        return 0;
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

            home_dir: ~/.melon

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
