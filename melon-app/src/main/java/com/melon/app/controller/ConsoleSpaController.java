package com.melon.app.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;

/** Serves the copied console SPA for browser routes. */
@Controller
public class ConsoleSpaController {

    @GetMapping(value = {"/", "/console", "/console/{*path}"}, produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<Resource>> index() {
        Resource index = indexResource();
        if (!index.exists()) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return Mono.just(ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(index));
    }

    private Resource indexResource() {
        Path sourceIndex = Path.of("console", "dist", "index.html").toAbsolutePath().normalize();
        if (Files.isRegularFile(sourceIndex)) {
            return new PathResource(sourceIndex);
        }
        return new ClassPathResource("static/index.html");
    }
}
