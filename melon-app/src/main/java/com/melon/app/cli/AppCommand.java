package com.melon.app.cli;

import com.melon.app.MelonApplication;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.concurrent.Callable;

/**
 * 启动 HTTP 服务. 对应 Python cli/app_cmd.py.
 */
@Command(name = "app", description = "Start Melon HTTP server", mixinStandardHelpOptions = true)
public class AppCommand implements Callable<Integer> {

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "Host to bind")
    String host;

    @Option(names = "--port", defaultValue = "8088", description = "Port to listen")
    int port;

    @Override
    public Integer call() {
        new SpringApplicationBuilder(MelonApplication.class)
                .properties("server.port=" + port)
                .run();
        return 0;
    }
}
