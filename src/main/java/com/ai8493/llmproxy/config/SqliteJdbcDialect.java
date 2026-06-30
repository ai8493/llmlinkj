package com.ai8493.llmproxy.config;

import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.jdbc.core.dialect.JdbcArrayColumns;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.relational.core.dialect.AnsiDialect;

/**
 * SQLite 方言实现。
 *
 * Spring Data JDBC 不内置 SQLite 方言，这里继承 AnsiDialect 提供基础 SQL 语法支持。
 * 通过 SqliteJdbcDialectProvider 注册到 DialectResolver SPI 中。
 *
 * SQLite 没有 BOOLEAN 类型，存为 INTEGER (0/1)。读取时需要 Integer → Boolean 转换器。
 * 必须用 @ReadingConverter 标注，否则 Spring Data JDBC 会把它当作 Integer 的写入目标类型，
 * 把所有 Integer 字段（如 defaultMaxTokens=65536）写成 Boolean 再被 SQLite 存成 1。
 */
public class SqliteJdbcDialect extends AnsiDialect implements JdbcDialect {

    public static final SqliteJdbcDialect INSTANCE = new SqliteJdbcDialect();

    private SqliteJdbcDialect() {}

    // AnsiDialect.getArraySupport() 返回 ArrayColumns，与 JdbcDialect 要求的 JdbcArrayColumns 不兼容，
    // 这里显式返回 JdbcArrayColumns.Unsupported 解决冲突。SQLite 不支持数组列。
    @Override
    public JdbcArrayColumns getArraySupport() {
        return JdbcArrayColumns.Unsupported.INSTANCE;
    }

    @Override
    public Collection<Object> getConverters() {
        return List.of(new IntegerToBooleanConverter());
    }

    /** Integer (0/1) → Boolean，只用于读取方向。 */
    @ReadingConverter
    public static class IntegerToBooleanConverter implements Converter<Integer, Boolean> {
        @Override
        public Boolean convert(Integer source) {
            return source != null && source != 0;
        }
    }
}

