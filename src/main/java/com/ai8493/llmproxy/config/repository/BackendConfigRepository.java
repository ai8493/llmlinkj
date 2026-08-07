package com.ai8493.llmproxy.config.repository;

import java.util.List;

import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface BackendConfigRepository extends CrudRepository<BackendConfigEntity, String> {

    // SQLite UPSERT：name 重复时整行替换。
    // Spring Data JDBC 默认 save() 对非空 @Id 做 UPDATE，新行 UPDATE 影响 0 行不会回退到 INSERT，
    // 因此覆盖 save() 走 INSERT OR REPLACE 以实现 upsert 语义。
    @Modifying
    @Query("""
        INSERT OR REPLACE INTO backend_config
        (name, protocol, api_key, base_url, default_model, default_max_tokens,
         connect_timeout, read_timeout, write_timeout, max_idle_connections,
         keep_alive_duration, reasoning_effort_mode, reasoning_effort_default,
         thinking_default_type, thinking_default_budget, updated_at)
        VALUES (:name, :protocol, :apiKey, :baseUrl, :defaultModel, :defaultMaxTokens,
                :connectTimeout, :readTimeout, :writeTimeout, :maxIdleConnections,
                :keepAliveDuration, :reasoningEffortMode, :reasoningEffortDefault,
                :thinkingDefaultType, :thinkingDefaultBudget, :updatedAt)
        """)
    void upsert(
        @Param("name") String name,
        @Param("protocol") String protocol,
        @Param("apiKey") String apiKey,
        @Param("baseUrl") String baseUrl,
        @Param("defaultModel") String defaultModel,
        @Param("defaultMaxTokens") Integer defaultMaxTokens,
        @Param("connectTimeout") long connectTimeout,
        @Param("readTimeout") long readTimeout,
        @Param("writeTimeout") long writeTimeout,
        @Param("maxIdleConnections") int maxIdleConnections,
        @Param("keepAliveDuration") long keepAliveDuration,
        @Param("reasoningEffortMode") String reasoningEffortMode,
        @Param("reasoningEffortDefault") String reasoningEffortDefault,
        @Param("thinkingDefaultType") String thinkingDefaultType,
        @Param("thinkingDefaultBudget") Integer thinkingDefaultBudget,
        @Param("updatedAt") String updatedAt);

    @Override
    @SuppressWarnings("unchecked")
    default <S extends BackendConfigEntity> S save(S entity) {
        upsert(
            entity.name(),
            entity.protocol(),
            entity.apiKey(),
            entity.baseUrl(),
            entity.defaultModel(),
            entity.defaultMaxTokens(),
            entity.connectTimeout(),
            entity.readTimeout(),
            entity.writeTimeout(),
            entity.maxIdleConnections(),
            entity.keepAliveDuration(),
            entity.reasoningEffortMode(),
            entity.reasoningEffortDefault(),
            entity.thinkingDefaultType(),
            entity.thinkingDefaultBudget(),
            entity.updatedAt());
        return entity;
    }

    // 手动 LIMIT/OFFSET 分页：Spring Data JDBC 的 Pageable 会生成 OFFSET ... FETCH FIRST 语法，
    // SQLite 不支持，故手写 LIMIT/OFFSET。
    @Query("SELECT * FROM backend_config ORDER BY name LIMIT :limit OFFSET :offset")
    List<BackendConfigEntity> findPaged(@Param("limit") int limit, @Param("offset") int offset);

    @Query("SELECT COUNT(*) FROM backend_config")
    long countAll();

    // 按后端名模糊匹配：SQLite 对 ASCII 字母 LIKE 默认大小写不敏感，中文无影响。
    // kw 由调用方包裹 %（如 "%test%"），name 为空时传 "%" 匹配全部。
    @Query("SELECT * FROM backend_config WHERE name LIKE :kw ORDER BY name LIMIT :limit OFFSET :offset")
    List<BackendConfigEntity> findByNameLikePaged(
        @Param("kw") String kw,
        @Param("limit") int limit,
        @Param("offset") int offset);

    @Query("SELECT COUNT(*) FROM backend_config WHERE name LIKE :kw")
    long countByNameLike(@Param("kw") String kw);
}
