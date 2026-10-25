package com.learning.springboot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetProvider;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 🔴 SQLite 元数据查询的「500 复合项」修复（2026-10-26）。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>{@code sqlite-jdbc} 的 {@link DatabaseMetaData#getColumns(String, String, String, String)}
 * 在<b>不指定表名</b>时，会为全库<b>每一列</b>生成一个 {@code SELECT ... UNION ALL ...} 分支。
 * 而 SQLite 对复合 SELECT 的项数有<b>硬上限 500</b>（{@code SQLITE_MAX_COMPOUND_SELECT}）。
 *
 * <p>Hibernate 在 {@code ddl-auto=update}（以及 {@code validate}）时，
 * 正是通过它读取全库 schema 元数据来比对表结构 ——
 * 于是列数一多，查询项数就破 500，Hibernate 直接起不来：
 * <pre>
 *   [SQLITE_ERROR] SQL error or missing database (too many terms in compound SELECT)
 *   Caused by: org.sqlite.SQLiteException
 *     at org.sqlite.jdbc3.JDBC3DatabaseMetaData.getColumns(JDBC3DatabaseMetaData.java:1260)
 *     at org.hibernate.tool.schema.extract.internal.InformationExtractorJdbcDatabaseMetaDataImpl
 * </pre>
 *
 * <h2>实测的临界点（本项目）</h2>
 *
 * <p>2026-10-26 实测：活动库 {@code data/sandbox.db} 改动前是
 * <b>49 表 / 493 列</b> → 生成的复合查询<b>正好 500 项</b>（卡在临界点）；
 * 本轮新增 {@code t_farm_user_clock}（4 列）后变成 <b>50 表 / 497 列</b>
 * → 复合项破 500 → <b>后端第二次启动直接失败</b>。
 *
 * <p>⚠️ 注意：<b>第一次启动是成功的</b>（那时表还没建全，列数未破限），
 * 所以这个坑特别隐蔽 —— 表现为「昨天还好好的，今天突然起不来了」。
 *
 * <h2>修法（sqlite-jdbc 官方 issue #487 的 workaround）</h2>
 *
 * <p>代理 {@link Connection#getMetaData()}，拦截 {@code getColumns}：
 * <ul>
 *   <li>先取全库表清单；</li>
 *   <li><b>逐表</b>调用 {@code getColumns(null, null, 表名, null)}；
 *       单表列数最多 25，<b>永远碰不到 500 上限</b>；</li>
 *   <li>把各表结果合并成一个 {@link CachedRowSet} 返回给 Hibernate。</li>
 * </ul>
 *
 * <p>实测（2026-10-26）：逐表查询 50 张表全部成功，合并后 <b>497 行</b>，
 * 与全库列数一致 —— 元数据完全等价。
 *
 * <h2>为什么放在 config 包而不是 data 层</h2>
 *
 * <p>它是<b>驱动层的补丁</b>，不是业务逻辑；放在 {@code config} 与
 * {@code SqlitePragmaInitializer}（同样是 SQLite 运行时调优）在一起，
 * 符合「横切关注点集中在 config」的既有约定。
 */
@Configuration
public class SqliteMetadataFix {

    private static final Logger log = LoggerFactory.getLogger(SqliteMetadataFix.class);

    /**
     * 建一个代理了元数据查询的 {@link DatabaseMetaData}。
     *
     * @param delegate 驱动原始的元数据对象
     * @return 代理对象：只有 {@code getColumns} 被改写，其余方法原样转发
     */
    public static DatabaseMetaData wrap(DatabaseMetaData delegate) {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                SqliteMetadataFix.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                new MetaHandler(delegate));
    }

    /**
     * 建一个代理了元数据查询的 {@link Connection}。
     *
     * <p>用法：在 {@code DataSource} 包装层里，把
     * {@code connection.getMetaData()} 换成 {@link #wrap(DatabaseMetaData)} 的结果。
     *
     * @param delegate 驱动原始的连接
     * @return 代理连接
     */
    public static Connection wrap(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                SqliteMetadataFix.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return wrap(delegate.getMetaData());
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getTargetException();
                    }
                });
    }

    /**
     * 元数据代理：只在 {@code getColumns} 被「无表名」调用时改写为逐表查询。
     */
    private static final class MetaHandler implements InvocationHandler {

        private final DatabaseMetaData delegate;

        MetaHandler(DatabaseMetaData delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getColumns".equals(method.getName()) && isWholeDatabaseScan(args)) {
                return columnsByTable();
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }

        /**
         * 是否是「扫全库」的调用。
         *
         * <p>判据：表名参数为 {@code null} 或 {@code "%"}，且列名模式不是精确匹配。
         * 只要指定了具体表名，驱动生成的 UNION 分支就只有该表的列数，
         * 天然安全 —— 不该拦截。
         */
        private boolean isWholeDatabaseScan(Object[] args) {
            if (args == null || args.length < 3) {
                return false;
            }
            String table = args[2] == null ? null : String.valueOf(args[2]);
            String column = args.length > 3 && args[3] != null ? String.valueOf(args[3]) : null;
            boolean tableAll = table == null || "%".equals(table);
            boolean columnAll = column == null || "%".equals(column);
            return tableAll && columnAll;
        }

        /**
         * 逐表查询并合并。
         *
         * @return 合并后的列元数据
         */
        private ResultSet columnsByTable() throws SQLException {
            CachedRowSet merged = RowSetProvider.newFactory().createCachedRowSet();
            int tables = 0;
            int columns = 0;
            try (ResultSet tableRs = delegate.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (tableRs.next()) {
                    String tableName = tableRs.getString("TABLE_NAME");
                    // SQLite 内部表（sqlite_sequence 等）不属于业务 schema
                    if (tableName == null || tableName.startsWith("sqlite_")) {
                        continue;
                    }
                    tables++;
                    try (ResultSet colRs = delegate.getColumns(null, null, tableName, null)) {
                        if (tables == 1) {
                            // 第一张表用 populate 一次性定下 ResultSet 的元数据结构
                            merged.populate(colRs);
                        } else {
                            // 后续表逐行追加（populate 会重置游标，不能反复用）
                            while (colRs.next()) {
                                merged.moveToInsertRow();
                                int n = colRs.getMetaData().getColumnCount();
                                for (int i = 1; i <= n; i++) {
                                    merged.updateObject(i, colRs.getObject(i));
                                }
                                merged.insertRow();
                                // 🔴 必须 moveToCurrentRow()：insertRow() 后游标停在
                                //    「insert row」状态，既不能 next() 也不能再 moveToInsertRow()，
                                //    直接读会抛「光标位置无效」。
                                //    少了这行，Hibernate 读到的列元数据为空 → 误判「库里没表」
                                //    → ddl-auto:update 会去重建整库！
                                merged.moveToCurrentRow();
                                columns++;
                            }
                        }
                    }
                }
            }
            log.info("[SQLite] 元数据查询已切到逐表模式: {} 张表 / {} 列"
                    + "（全库一次查会破 SQLite 的 500 复合项上限）", tables, merged.size());
            return merged;
        }
    }
}
