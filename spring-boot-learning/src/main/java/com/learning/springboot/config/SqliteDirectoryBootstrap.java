package com.learning.springboot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SQLite 父目录兜底创建（2026-10-26）。
 *
 * <p><b>为什么需要</b>：SQLite 驱动<b>只能创建 db 文件，不能创建父目录</b>。
 * 当 {@code jdbc:sqlite:} URL 指向一个尚不存在的目录时，Hikari 建连接会直接抛：
 * <pre>
 *   java.sql.SQLException: path to '.../target/test-data/test-sandbox.db':
 *   '.../target/test-data' does not exist
 * </pre>
 *
 * <p><b>为什么之前没暴露</b>：本机 {@code target/test-data/} 目录是<b>早先跑测试时
 * 顺带建出来的</b>，属于「本地侥幸」。<b>全新 clone 后第一次跑 {@code mvn test}
 * 必然失败</b>（17 个 {@code @SpringBootTest} 类因 ApplicationContext 加载不了
 * 而连环报错）。这类「本地能跑、CI/新同事必炸」的坑，只能靠从干净目录复现才能发现。
 *
 * <p><b>为什么用 {@link BeanFactoryPostProcessor}</b>：Hikari 在<b>构造函数里</b>
 * 就初始化连接池，{@code BeanPostProcessor} 来不及（那时连接池已经建失败）；
 * {@code @PostConstruct} 的执行顺序又取决于 Bean 注册顺序，<b>无法保证</b>
 * 数据源创建时目录已就位。只有 {@code BeanFactoryPostProcessor} 运行在
 * <b>所有单例 Bean 实例化之前</b>，是唯一能保证「数据源首次建连接前目录已存在」的位置。
 *
 * <p><b>为什么由 {@link SqliteDataSourceConfig} 用 static {@code @Bean} 注册</b>：
 * BFPP 若是 {@code @Component}，它自己也要走一遍 Bean 创建流程，
 * 而 {@code ApplicationContextAwareProcessor}（负责注入 Environment）此时还没注册，
 * 拿不到 Environment。用 static {@code @Bean} + 构造器注入可彻底绕开这个时序问题。
 *
 * <p><b>覆盖面</b>：同时处理 {@code spring.datasource.url}（主库）与
 * {@code sandbox.datasource.url}（沙箱库）两条路径，
 * 以后再加第三个数据源也不会漏。
 *
 * <p><b>边界</b>：只对 {@code jdbc:sqlite:} 前缀生效；内存库（{@code :memory:}）
 * 和非 SQLite 库直接跳过，不做任何解析。
 */
public class SqliteDirectoryBootstrap implements BeanFactoryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SqliteDirectoryBootstrap.class);

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    private static final String MAIN_URL_KEY = "spring.datasource.url";

    private static final String SANDBOX_URL_KEY = "sandbox.datasource.url";

    private final Environment environment;

    public SqliteDirectoryBootstrap(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        ensureDirectoryFor(environment.getProperty(MAIN_URL_KEY), "主库");
        ensureDirectoryFor(environment.getProperty(SANDBOX_URL_KEY), "沙箱库");
    }

    private void ensureDirectoryFor(String url, String label) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (!url.startsWith(SQLITE_PREFIX)) {
            return;
        }
        String rawPath = url.substring(SQLITE_PREFIX.length()).trim();
        if (rawPath.isEmpty() || rawPath.contains(":memory:")) {
            return;
        }
        try {
            // 🔴 URL 里可能带 JDBC 查询参数（?cache=shared 之类），先剥掉
            int q = rawPath.indexOf('?');
            if (q >= 0) {
                rawPath = rawPath.substring(0, q);
            }
            if (rawPath.isEmpty() || rawPath.contains(":memory:")) {
                return;
            }
            Path dbFile = Paths.get(rawPath).toAbsolutePath();
            Path parent = dbFile.getParent();
            if (parent == null) {
                return;
            }
            if (Files.isDirectory(parent)) {
                return;
            }
            Files.createDirectories(parent);
            log.info("[SQLite] {}父目录已创建: {}", label, parent);
        } catch (Exception ex) {
            // ⚠️ 这里不能抛异常中断启动 —— 目录建不出来时，
            //    让数据源自己报更准确的「does not exist」错误更有诊断价值。
            log.warn("[SQLite] {}父目录创建失败（将交由数据源报错）: {}", label, rawPath, ex);
        }
    }

    /** 供测试与诊断使用：返回库文件的绝对路径 */
    public static String resolveAbsolutePath(String url) {
        if (url == null || !url.startsWith(SQLITE_PREFIX)) {
            return url;
        }
        String rawPath = url.substring(SQLITE_PREFIX.length()).trim();
        int q = rawPath.indexOf('?');
        if (q >= 0) {
            rawPath = rawPath.substring(0, q);
        }
        return new File(rawPath).getAbsolutePath();
    }
}
