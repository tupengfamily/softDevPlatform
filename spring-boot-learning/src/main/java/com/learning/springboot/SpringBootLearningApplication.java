package com.learning.springboot;

import com.learning.springboot.config.JwtProperties;
import com.learning.springboot.config.OnlyOfficeProperties;
import com.learning.springboot.config.TianDiTuProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ConfigurableApplicationContext;
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
@EnableConfigurationProperties({JwtProperties.class, OnlyOfficeProperties.class, TianDiTuProperties.class})
@EnableScheduling   // 启用定时任务(操作日志超期清理等)
@EnableCaching      // 启用 Caffeine 本地缓存(@Cacheable/@CacheEvict)
public class SpringBootLearningApplication {

    public static void main(String[] args) {
        // 启动前确保 SQLite 数据目录存在
        File dataDir = new File("data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            System.err.println("[WARN] 无法创建 data 目录,请手动创建: " + dataDir.getAbsolutePath());
        }

        ConfigurableApplicationContext ctx = SpringApplication.run(SpringBootLearningApplication.class, args);
        System.out.println("\n========================================");
        System.out.println("✓ Spring Boot 管理系统 启动成功!");
        System.out.println("✓ 默认管理员: admin / admin123");
        // ⚠️ 这里必须打印「实际生效」的库路径，而不是写死一个文件名。
        //    以前写死 "data/admin.db"，但主数据源其实指向 data/sandbox.db，
        //    结果横幅永远在骗人 —— 排障时被带偏过好几次。
        //    现在从 Spring 上下文里读真实的 DataSource URL。
        System.out.println("✓ 数据库: SQLite -> " + resolveDatabaseUrl(ctx));
        System.out.println("✓ API:     http://localhost:8080/api/auth/login");
        System.out.println("========================================\n");
    }

    /**
     * 解析「实际生效」的数据库路径。
     *
     * <p>从 Spring {@code Environment} 里读 {@code spring.datasource.url} ——
     * 这个值已经过了「yml → 环境变量 → 命令行」的完整覆盖链，
     * 所以它就是真正生效的那个 URL。
     *
     * <p>⚠️ 不用 {@code DataSource.getConnection().getMetaData().getURL()}：
     * 那会借出一个连接（还得记得还），而且在元数据修复层下会走一遍代理。
     * 纯读配置更轻、更安全。
     *
     * @param ctx 启动后的 Spring 上下文
     * @return 数据库文件路径
     */
    private static String resolveDatabaseUrl(ConfigurableApplicationContext ctx) {
        try {
            if (ctx != null) {
                String url = ctx.getEnvironment().getProperty("spring.datasource.url");
                if (url != null && !url.isBlank()) {
                    if (url.startsWith("jdbc:sqlite:")) {
                        return new File(url.substring("jdbc:sqlite:".length())).getAbsolutePath();
                    }
                    return url;
                }
            }
        } catch (Exception ignored) {
            // 横幅只是提示信息，取不到不能影响启动
        }
        return new File("data/sandbox.db").getAbsolutePath();
    }
}