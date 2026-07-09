package com.melon.app;

import com.melon.app.cli.MelonCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;

/**
 * Melon Spring Boot main class.
 */
@SpringBootApplication
public class MelonApplication {

    public static void main(String[] args) {
        if (args.length > 0) {
            int exitCode = new CommandLine(new MelonCli()).execute(args);
            System.exit(exitCode);
        }
        SpringApplication.run(MelonApplication.class, args);
    }
}
