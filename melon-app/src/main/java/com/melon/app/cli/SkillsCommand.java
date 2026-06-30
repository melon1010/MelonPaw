/**
 * @author melon
 */
package com.melon.app.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * CLI command to list available skills.
 * Tries the HTTP API first, falls back to reading the ~/.melon/skills directory.
 */
@Command(name = "skills", description = "Manage skills", mixinStandardHelpOptions = true)
public class SkillsCommand implements Callable<Integer> {

    @Option(names = "--port", defaultValue = "8088", description = "Melon server port")
    int port;

    @Override
    public Integer call() {
        // Try HTTP API first
        String url = "http://localhost:" + port + "/api/skills";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println(prettyPrint(response.body()));
                return 0;
            }
        } catch (Exception e) {
            // Fall back to reading directory
        }

        // Fall back to reading ~/.melon/skills directory
        return readSkillsFromDirectory();
    }

    private int readSkillsFromDirectory() {
        Path skillsDir = Path.of(System.getProperty("user.home"), ".melon", "skills");
        if (!Files.exists(skillsDir)) {
            System.out.println("No skills directory found at " + skillsDir);
            System.out.println("Run 'melon init' to create the directory structure.");
            return 0;
        }

        List<String[]> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(skillsDir)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                Path skillFile = dir.resolve("SKILL.md");
                String description = "";
                if (Files.exists(skillFile)) {
                    try {
                        String content = Files.readString(skillFile);
                        description = extractDescription(content);
                    } catch (IOException ignored) {
                    }
                }
                skills.add(new String[]{name, description});
            });
        } catch (IOException e) {
            System.err.println("Error reading skills directory: " + e.getMessage());
            return 1;
        }

        if (skills.isEmpty()) {
            System.out.println("No skills found in " + skillsDir);
            return 0;
        }

        System.out.println("Skills:");
        System.out.println();
        for (String[] skill : skills) {
            System.out.printf("  %-30s %s%n", skill[0], skill[1]);
        }
        System.out.println();
        System.out.println("Total: " + skills.size() + " skill(s)");
        return 0;
    }

    private String extractDescription(String content) {
        int descIdx = content.indexOf("description:");
        if (descIdx >= 0) {
            int lineEnd = content.indexOf('\n', descIdx);
            if (lineEnd > descIdx) {
                return content.substring(descIdx + 12, lineEnd).trim();
            }
        }
        return "";
    }

    private String prettyPrint(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Object obj = mapper.readValue(json, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return json;
        }
    }
}
