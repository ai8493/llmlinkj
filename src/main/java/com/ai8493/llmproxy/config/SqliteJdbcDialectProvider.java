package com.ai8493.llmproxy.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.data.jdbc.core.dialect.DialectResolver.JdbcDialectProvider;
import org.springframework.data.relational.core.dialect.Dialect;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQLite 方言解析器。
 *
 * 通过 SpringFactoriesLoader 注册到 DialectResolver，当数据库产品名为 "SQLite" 时返回 SqliteJdbcDialect。
 */
public class SqliteJdbcDialectProvider implements JdbcDialectProvider {

    @Override
    public Optional<Dialect> getDialect(JdbcOperations operations) {
        if (!(operations instanceof JdbcTemplate jdbcTemplate)) {
            return Optional.empty();
        }
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            return Optional.empty();
        }
        try (Connection con = dataSource.getConnection()) {
            DatabaseMetaData meta = con.getMetaData();
            if ("SQLite".equalsIgnoreCase(meta.getDatabaseProductName())) {
                return Optional.of(SqliteJdbcDialect.INSTANCE);
            }
        } catch (Exception ignored) {
            // 拿不到 metadata 时让 DefaultDialectProvider 走默认逻辑
        }
        return Optional.empty();
    }
}
