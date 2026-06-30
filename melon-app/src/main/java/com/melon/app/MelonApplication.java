/**
 * @author melon
 */
package com.melon.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Melon Spring Boot 主类.
 * 启动 HTTP 服务, 初始化 Agent 运行时.
 */
@SpringBootApplication
public class MelonApplication {

    public static void main(String[] args) {
        SpringApplication.run(MelonApplication.class, args);
    }
}
