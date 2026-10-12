package com.learning.springboot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Web 静态资源配置
 * <p>
 * /uploads/** 映射到运行目录下的 data/uploads/(用户上传的头像等图片),
 * 前端可通过 /uploads/xxx.png 直接访问;文件随 data/ 目录持久化(Docker 已挂载)。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        try {
            Path dir = Path.of("data", "uploads");
            Files.createDirectories(dir); // 保证目录存在(否则 URI 缺尾斜杠导致映射失败)
            String uploadDir = dir.toAbsolutePath().toUri().toString();
            if (!uploadDir.endsWith("/")) uploadDir += "/";
            registry.addResourceHandler("/uploads/**")
                    .addResourceLocations(uploadDir);
        } catch (IOException e) {
            throw new IllegalStateException("初始化上传目录失败", e);
        }
    }
}
