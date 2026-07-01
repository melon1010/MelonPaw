package com.melon.coding.lsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages LSP (Language Server Protocol) client connections.
 * Uses LSP4J for communication with language servers.
 * Corresponds to Python LSP integration in coding mode.
 */
public class LspClientManager {

    private static final Logger log = LoggerFactory.getLogger(LspClientManager.class);

    private final ConcurrentMap<String, LspServerProcess> servers = new ConcurrentHashMap<>();
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);

    /**
     * Starts an LSP server for the given language.
     *
     * @param language  Language identifier (e.g., "java", "python", "typescript")
     * @param command   Command to launch the language server
     * @param workspace Workspace root path
     * @return true if server started successfully
     */
    public boolean startServer(String language, String[] command, String workspace) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new java.io.File(workspace));
            pb.redirectErrorStream(false);
            Process process = pb.start();

            LspServerProcess lspProcess = new LspServerProcess(
                language, process,
                process.getInputStream(),
                process.getOutputStream()
            );
            servers.put(language, lspProcess);

            log.info("LSP server started for language: {} (PID: {})", language, process.pid());
            return true;
        } catch (Exception e) {
            log.error("Failed to start LSP server for {}: {}", language, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Stops the LSP server for the given language.
     */
    public void stopServer(String language) {
        LspServerProcess process = servers.remove(language);
        if (process != null) {
            process.shutdown();
            log.info("LSP server stopped for language: {}", language);
        }
    }

    /**
     * Stops all running LSP servers.
     */
    public void stopAll() {
        servers.keySet().forEach(this::stopServer);
    }

    /**
     * Sends a JSON-RPC request to the LSP server and returns the response.
     * <p>
     * Implements the LSP wire protocol: writes a Content-Length header followed
     * by the JSON-RPC body to the server's stdin, then reads the Content-Length
     * header and body from the server's stdout.
     *
     * @param language the language identifier whose server should receive the request
     * @param method   the JSON-RPC method name (e.g., "initialize", "textDocument/completion")
     * @param params   the JSON-RPC params value (must be a valid JSON fragment, e.g. {@code {"key":"value"}})
     * @return the JSON-RPC response body as a string, or an error message
     */
    public String sendRequest(String language, String method, String params) {
        LspServerProcess server = servers.get(language);
        if (server == null) {
            return "Error: No LSP server running for language '" + language + "'";
        }

        synchronized (server) {
            try {
                int id = requestIdCounter.incrementAndGet();
                String jsonRequest = buildJsonRpcRequest(id, method, params);
                byte[] jsonBytes = jsonRequest.getBytes(StandardCharsets.UTF_8);

                // Write Content-Length header + JSON body
                String header = "Content-Length: " + jsonBytes.length + "\r\n\r\n";
                server.output.write(header.getBytes(StandardCharsets.UTF_8));
                server.output.write(jsonBytes);
                server.output.flush();

                log.info("LSP request to {} ({}): {}", language, method, params);

                // Read response
                return readResponse(server.input);
            } catch (Exception e) {
                log.error("Failed to send LSP request to {}: {}", language, e.getMessage(), e);
                return "Error: " + e.getMessage();
            }
        }
    }

    /**
     * Builds a JSON-RPC 2.0 request string.
     */
    private String buildJsonRpcRequest(int id, String method, String params) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
          .append(",\"method\":\"").append(method).append("\"");
        if (params != null && !params.isBlank()) {
            sb.append(",\"params\":").append(params);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Reads a single LSP response message from the input stream.
     * Parses the Content-Length header, then reads exactly that many bytes.
     */
    private String readResponse(InputStream input) throws Exception {
        // Read headers until we find the empty line separator (\r\n\r\n)
        int contentLength = -1;
        StringBuilder headerBuilder = new StringBuilder();
        int b;
        while ((b = input.read()) != -1) {
            headerBuilder.append((char) b);
            String headerStr = headerBuilder.toString();
            if (headerStr.endsWith("\r\n\r\n")) {
                // Parse Content-Length from headers
                for (String line : headerStr.split("\r\n")) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(
                                line.substring("content-length:".length()).trim());
                    }
                }
                break;
            }
        }

        if (contentLength <= 0) {
            return "Error: No Content-Length in response";
        }

        // Read body (exactly contentLength bytes)
        byte[] body = new byte[contentLength];
        int totalRead = 0;
        while (totalRead < contentLength) {
            int n = input.read(body, totalRead, contentLength - totalRead);
            if (n == -1) {
                break;
            }
            totalRead += n;
        }

        return new String(body, 0, totalRead, StandardCharsets.UTF_8);
    }

    /**
     * Checks if an LSP server is running for the given language.
     */
    public boolean isServerRunning(String language) {
        LspServerProcess process = servers.get(language);
        return process != null && process.isAlive();
    }

    /**
     * Inner class representing a running LSP server process.
     */
    private static class LspServerProcess {
        final String language;
        final Process process;
        final InputStream input;
        final OutputStream output;

        LspServerProcess(String language, Process process, InputStream input, OutputStream output) {
            this.language = language;
            this.process = process;
            this.input = input;
            this.output = output;
        }

        boolean isAlive() {
            return process.isAlive();
        }

        void shutdown() {
            try {
                process.destroy();
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
