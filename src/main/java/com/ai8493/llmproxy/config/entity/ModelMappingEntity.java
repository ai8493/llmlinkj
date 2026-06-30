package com.ai8493.llmproxy.config.entity;

import org.springframework.data.relational.core.mapping.Table;

@Table("model_mapping")
public record ModelMappingEntity(
    String clientProtocol,
    String backendCfgName,
    String requestModel,
    String actualModel,
    String updatedAt
) {}
