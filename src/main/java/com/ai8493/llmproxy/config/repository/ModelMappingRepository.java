package com.ai8493.llmproxy.config.repository;

import com.ai8493.llmproxy.config.entity.ModelMappingEntity;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ModelMappingRepository extends CrudRepository<ModelMappingEntity, Void> {

    // 复合主键 (client_protocol, backend_cfg_name, request_model)，无单一 @Id，
    // Spring Data JDBC 的 save() 强制要求 @Id，因此覆盖 save() 走原生 SQL upsert。
    @Modifying
    @Query("""
        INSERT OR REPLACE INTO model_mapping
        (client_protocol, backend_cfg_name, request_model, actual_model, updated_at)
        VALUES (:clientProtocol, :backendCfgName, :requestModel, :actualModel, :updatedAt)
        """)
    void upsert(
        @Param("clientProtocol") String clientProtocol,
        @Param("backendCfgName") String backendCfgName,
        @Param("requestModel") String requestModel,
        @Param("actualModel") String actualModel,
        @Param("updatedAt") String updatedAt);

    @Override
    @SuppressWarnings("unchecked")
    default <S extends ModelMappingEntity> S save(S entity) {
        upsert(
            entity.clientProtocol(),
            entity.backendCfgName(),
            entity.requestModel(),
            entity.actualModel(),
            entity.updatedAt());
        return entity;
    }

    @Query("SELECT * FROM model_mapping WHERE client_protocol = :clientProtocol AND backend_cfg_name = :backendCfgName ORDER BY request_model")
    List<ModelMappingEntity> findByOwner(@Param("clientProtocol") String clientProtocol,
                                         @Param("backendCfgName") String backendCfgName);

    @Modifying
    @Query("DELETE FROM model_mapping WHERE client_protocol = :clientProtocol AND backend_cfg_name = :backendCfgName")
    void deleteByOwner(@Param("clientProtocol") String clientProtocol,
                        @Param("backendCfgName") String backendCfgName);

    @Modifying
    @Query("DELETE FROM model_mapping WHERE client_protocol = :clientProtocol AND backend_cfg_name = :backendCfgName AND request_model = :requestModel")
    void deleteByKey(@Param("clientProtocol") String clientProtocol,
                      @Param("backendCfgName") String backendCfgName,
                      @Param("requestModel") String requestModel);

    // 按复合键 (client_protocol, backend_cfg_name, request_model) 查单条。
    // 与 findByOwner 的区别：findByOwner 返回某 (client, backend) 下全部映射列表，
    // 本方法精确匹配请求模型名，避免拉全表后过滤。
    @Query("SELECT * FROM model_mapping WHERE client_protocol = :clientProtocol AND backend_cfg_name = :backendCfgName AND request_model = :requestModel LIMIT 1")
    ModelMappingEntity findByOwnerAndRequestModel(
        @Param("clientProtocol") String clientProtocol,
        @Param("backendCfgName") String backendCfgName,
        @Param("requestModel") String requestModel);
}
