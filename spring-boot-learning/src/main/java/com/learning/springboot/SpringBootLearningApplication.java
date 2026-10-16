package com.learning.springboot;

import com.learning.springboot.config.JwtProperties;
import com.learning.springboot.config.OnlyOfficeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;

/**
 * Spring Boot 应用启动类
 * <p>
 * 核心注解:
 * - @SpringBootApplication: 组合注解,等同于
 *   @Configuration + @EnableAutoConfiguration + @ComponentScan
 * - @EnableConfigurationProperties: 启用 @ConfigurationProperties 注解的类
 * <p>
 * SpringApplication.run() 启动 Spring Boot 应用,自动配置内嵌 Tomcat 等。
 */
@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class, OnlyOfficeProperties.class})
@EnableScheduling   // 启用定时任务(操作日志超期清理等)
public class SpringBootLearningApplication {

    public static void main(String[] args) {
        // 启动前确保 SQLite 数据目录存在
        File dataDir = new File("data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            System.err.println("[WARN] 无法创建 data 目录,请手动创建: " + dataDir.getAbsolutePath());
        }

        SpringApplication.run(SpringBootLearningApplication.class, args);
        System.out.println("\n========================================");
        System.out.println("✓ Spring Boot 管理系统 启动成功!");
        System.out.println("✓ 默认管理员: admin / admin123");
        System.out.println("✓ 数据库: SQLite -> " + new File("data/admin.db").getAbsolutePath());
        System.out.println("✓ API:     http://localhost:8080/api/auth/login");
        System.out.println("========================================\n");
    }
}