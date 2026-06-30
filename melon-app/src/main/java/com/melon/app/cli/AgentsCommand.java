/**
 * @author melon
 */
package com.melon.app.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * CLI command to list and manage agents.
 * Calls the HTTP API GET /api/agents to retrieve agent configurations.
 */
@Command(name = "agents", description = "Manage agents", mixinStandardHelpOptions = true)
public class AgentsCommand implements Callable<Integer> {

    @Option(names = "--port", defaultValue = "8088", description = "Melon server port")
    int port;

    @Override
    public Integer call() {
        String url = "http://localhost:" + port + "/api/agents";
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
            } else {
                System.err.println("Error: HTTP " + response.statusCode());
                if (response.body() != null && !response.body().isBlank()) {
                    System.err.println(response.body());
                }
                return 1;
            }
        } catch (java.net.ConnectException e) {
            System.err.println("Cannot connect to Melon server at localhost:" + port);
            System.err.println("Is the server running? Start it with: melon app --port " + port);
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        return 0;
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
