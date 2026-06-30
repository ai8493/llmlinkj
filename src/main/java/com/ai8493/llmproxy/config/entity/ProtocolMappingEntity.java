package com.ai8493.llmproxy.config.entity;

import com.ai8493.llmproxy.config.entity.ModelMappingEntity;
import java.util.List;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Table;

@Table("protocol_mapping")
public record ProtocolMappingEntity(
    String clientProtocol,
    String backendCfgName,
    boolean enabled,
    String updatedAt,
    @Transient List<ModelMappingEntity> modelMappings
) {
    // Spring Data JDBC 3.5.11 对 record + @Transient 构造参数存在绑定缺陷：
    // 读行时仍走 canonical 构造器但找不到 modelMappings 属性。用 4-arg 构造器读行绕开。
    @PersistenceConstructor
    public ProtocolMappingEntity(String clientProtocol, String backendCfgName, boolean enabled, String updatedAt) {
        this(clientProtocol, backendCfgName, enabled, updatedAt, null);
    }
}
