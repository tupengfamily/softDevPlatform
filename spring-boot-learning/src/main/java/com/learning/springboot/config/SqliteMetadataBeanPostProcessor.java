package com.learning.springboot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * 🔴 把 {@link SqliteMetadataFix} 接到真实的 {@link DataSource} 上（2026-10-26）。
 *
 * <h2>为什么需要这一层</h2>
 *
 * <p>{@link SqliteMetadataFix} 只是「工具类」——它能造出代理过的
 * {@code Connection} / {@code DatabaseMetaData}，但没人调用就等于没修。
 * Spring Boot 的主数据源由 {@code DataSourceAutoConfiguration} 自动装配成
 * {@code HikariDataSource}，项目里<b>没有自定义 DataSource Bean</b>，
 * 所以接入点只能是 {@link BeanPostProcessor}。
 *
 * <h2>为什么包装 DataSource 而不是包装 HikariDataSource</h2>
 *
 * <p>包装 {@code DataSource} 接口本身有两个好处：
 * <ul>
 *   <li><b>主库与沙箱库同时受益</b> —— 本项目有两个数据源
 *       （{@code spring.datasource} 与 {@code sandbox.datasource}），
 *       都是 SQLite，都有撞 500 上限的隐患；</li>
 *   <li><b>不碰 Hikari 的生命周期</b> —— 池的创建/关闭/连接校验仍由 Hikari 负责，
 *       我们只在「借出连接」这一层包一层代理。</li>
 * </ul>
 *
 * <h2>代理是否安全</h2>
 *
 * <p>代理只拦截 {@code getConnection()}，其余方法（{@code getLogWriter}、
 * {@code setLoginTimeout}、{@code getParentLogger} …）原样转发。
 * 拿到的 {@code Connection} 代理也只拦 {@code getMetaData()}，
 * 其余（{@code prepareStatement} / {@code commit} / {@code close} /
 * {@code isWrapperFor} / {@code unwrap} …）全部转发给真实连接。
 *
 * <p>⚠️ <b>代理的 Connection 必须能被正确关闭</b> —— 否则连接池的连接永不回收。
 * 本实现的 {@code close()} 是转发，语义与真实连接一致，池能正常回收。
 *
 * @see SqliteMetadataFix
 */
@Component
public class SqliteMetadataBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SqliteMetadataBeanPostProcessor.class);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof DataSource)) {
            return bean;
        }
        DataSource source = (DataSource) bean;
        log.info("[SQLite] 数据源已接入元数据修复层: {}", beanName);
        return wrapDataSource(source);
    }

    /**
     * 把 {@link DataSource} 包一层，使它借出的 {@link Connection}
     * 的元数据查询走逐表模式。
     *
     * @param delegate 原始数据源
     * @return 代理后的数据源
     */
    private DataSource wrapDataSource(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(
                SqliteMetadataBeanPostProcessor.class.getClassLoader(),
                // ⚠️ 必须带上 AutoCloseable：DataSource 接口本身没有 close()，
                //    而两个数据源 Bean 都声明了 destroyMethod = "close"。
                //    Spring 销毁时按方法名在代理类上找 close()，
                //    只实现 DataSource 会抛「Couldn't find a destroy method named 'close'」。
                //    实现 AutoCloseable 后，代理类会生成 close() 并转发给真实连接池。
                new Class<?>[]{DataSource.class, AutoCloseable.class},
                new DataSourceHandler(delegate));
    }

    /**
     * 数据源代理：只在 {@code getConnection()} 时把连接也包一层。
     */
    private static final class DataSourceHandler implements InvocationHandler {

        private final DataSource delegate;

        DataSourceHandler(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getConnection".equals(method.getName())) {
                Connection connection = (Connection) method.invoke(delegate, args);
                return SqliteMetadataFix.wrap(connection);
            }
            // destroyMethod="close" 的落点：AutoCloseable.close() → 真实数据源的 close()
            if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                Method close = delegate.getClass().getMethod("close");
                return close.invoke(delegate);
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }
    }
}
