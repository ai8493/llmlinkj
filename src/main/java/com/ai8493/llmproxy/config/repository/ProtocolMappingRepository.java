package com.ai8493.llmproxy.config.repository;

import java.util.List;

import com.ai8493.llmproxy.config.entity.ProtocolMappingEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ProtocolMappingRepository
    extends CrudRepository<ProtocolMappingEntity, Void> {

    // ProtocolMappingEntity 主键是 (client_protocol, backend_cfg_name) 复合键，无法用 @Id 标注单个字段。
    // Spring Data JDBC 3.5.x 的 save() 调用 verifyIdProperty() 强制要求 @Id，因此覆盖 save() 走原生 SQL。
    @Modifying
    @Query("""
        INSERT OR REPLACE INTO protocol_mapping
        (client_protocol, backend_cfg_name, enabled, updated_at)
        VALUES (:clientProtocol, :backendCfgName, :enabled, :updatedAt)
        """)
    void upsert(
        @Param("clientProtocol") String clientProtocol,
        @Param("backendCfgName") String backendCfgName,
        @Param("enabled") boolean enabled,
        @Param("updatedAt") String updatedAt);

    @Override
    @SuppressWarnings("unchecked")
    default <S extends ProtocolMappingEntity> S save(S entity) {
        upsert(
            entity.clientProtocol(),
            entity.backendCfgName(),
            entity.enabled(),
            entity.updatedAt());
        return entity;
    }

    @Query("SELECT * FROM protocol_mapping ORDER BY client_protocol, backend_cfg_name")
    List<ProtocolMappingEntity> findAllOrdered();

    @Query("SELECT * FROM protocol_mapping WHERE client_protocol = :clientProtocol AND backend_cfg_name = :backendCfgName")
    ProtocolMappingEntity findByKey(@Param("clientProtocol") String clientProtocol,
                                    @Param("backendCfgName") String backendCfgName);

    @Query("SELECT * FROM protocol_mapping WHERE backend_cfg_name = :name")
    List<ProtocolMappingEntity> findByBackendCfgName(@Param("name") String backendCfgName);

    @Modifying
    @Query("DELETE FROM protocol_mapping WHERE client_protocol = :clientProtocol AND backend_cfg_name = :backendCfgName")
    void deleteByKey(@Param("clientProtocol") String clientProtocol,
                     @Param("backendCfgName") String backendCfgName);

    // 取指定入站协议下 enabled=1 且 updated_at 最大的一条。
    // enabled 为 INTEGER 列直接等值匹配（SQLite 存 0/1）；updated_at 为 TEXT（ISO-8601），
    // 字符串排序与时间排序等价。LIMIT 1 取首条。
    @Query("SELECT * FROM protocol_mapping WHERE client_protocol = :clientProtocol AND enabled = 1 ORDER BY updated_at DESC LIMIT 1")
    ProtocolMappingEntity findFirstEnabledByClientProtocolOrderByUpdatedAtDesc(
        @Param("clientProtocol") String clientProtocol);

    // 按客户端协议精确过滤：client_protocol 为有限枚举（不含 %），调用方直接传原值（如 "openai"），
    // LIKE 'openai' 等价于 = 匹配；查全部时传 "%"。
    @Query("SELECT * FROM protocol_mapping WHERE client_protocol LIKE :kw ORDER BY client_protocol, backend_cfg_name LIMIT :limit OFFSET :offset")
    List<ProtocolMappingEntity> findByClientProtocolLikePaged(
        @Param("kw") String kw,
        @Param("limit") int limit,
        @Param("offset") int offset);

    @Query("SELECT COUNT(*) FROM protocol_mapping WHERE client_protocol LIKE :kw")
    long countByClientProtocolLike(@Param("kw") String kw);
}
