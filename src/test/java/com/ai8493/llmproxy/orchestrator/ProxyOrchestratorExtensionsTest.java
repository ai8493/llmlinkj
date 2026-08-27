package com.ai8493.llmproxy.orchestrator;

import com.ai8493.llmproxy.adapter.BackendAdapter;
import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import com.ai8493.llmproxy.config.entity.ProtocolMappingEntity;
import com.ai8493.llmproxy.config.repository.BackendConfigRepository;
import com.ai8493.llmproxy.config.repository.ModelMappingRepository;
import com.ai8493.llmproxy.config.repository.ProtocolMappingRepository;
import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import com.ai8493.llmproxy.model.UnifiedMessage;
import com.ai8493.llmproxy.model.extensions.AnthropicExtensions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProxyOrchestratorExtensionsTest {

    @Test
    void shouldPreserveAnthropicExtensionsWhenHandleRoutes() {
        var mockAdapter = mock(BackendAdapter.class);
        var mockFactory = mock(BackendAdapterFactory.class);
        var mockProtocolRepo = mock(ProtocolMappingRepository.class);
        var mockModelRepo = mock(ModelMappingRepository.class);
        var mockBackendRepo = mock(BackendConfigRepository.class);

        var backendCfg = new BackendConfigEntity(
            "test", "anthropic", "k", "http://localhost", "claude-3",
            null, 5L, 10L, 5L, 5, 60L, null, null, null, null, "2026-01-01T00:00:00Z");
        var pm = new ProtocolMappingEntity("anthropic", "test", true, "2026-01-01T00:00:00Z", null);

        when(mockProtocolRepo.findFirstEnabledByClientProtocolOrderByUpdatedAtDesc("anthropic")).thenReturn(pm);
        when(mockBackendRepo.findById("test")).thenReturn(Optional.of(backendCfg));
        when(mockFactory.get("test")).thenReturn(mockAdapter);
        when(mockAdapter.call(any())).thenReturn(UnifiedChatResponse.builder().build());

        var orchestrator = new ProxyOrchestrator(mockFactory, mockProtocolRepo, mockModelRepo, mockBackendRepo);

        var anthropicExt = AnthropicExtensions.builder().metadataUserId("user-123").build();
        var req = UnifiedChatRequest.builder()
            .model("claude-3")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .anthropic(anthropicExt)
            .build();

        orchestrator.handle(req, "anthropic");

        ArgumentCaptor<UnifiedChatRequest> captor = ArgumentCaptor.forClass(UnifiedChatRequest.class);
        verify(mockAdapter).call(captor.capture());
        assertThat(captor.getValue().anthropic()).isNotNull();
        assertThat(captor.getValue().anthropic().metadataUserId()).isEqualTo("user-123");
    }

    @Test
    void shouldPreserveAnthropicExtensionsWhenHandleStreamRoutes() {
        var mockAdapter = mock(BackendAdapter.class);
        var mockFactory = mock(BackendAdapterFactory.class);
        var mockProtocolRepo = mock(ProtocolMappingRepository.class);
        var mockModelRepo = mock(ModelMappingRepository.class);
        var mockBackendRepo = mock(BackendConfigRepository.class);

        var backendCfg = new BackendConfigEntity(
            "test", "anthropic", "k", "http://localhost", "claude-3",
            null, 5L, 10L, 5L, 5, 60L, null, null, null, null, "2026-01-01T00:00:00Z");
        var pm = new ProtocolMappingEntity("anthropic", "test", true, "2026-01-01T00:00:00Z", null);

        when(mockProtocolRepo.findFirstEnabledByClientProtocolOrderByUpdatedAtDesc("anthropic")).thenReturn(pm);
        when(mockBackendRepo.findById("test")).thenReturn(Optional.of(backendCfg));
        when(mockFactory.get("test")).thenReturn(mockAdapter);
        when(mockAdapter.stream(any())).thenReturn(reactor.core.publisher.Flux.empty());

        var orchestrator = new ProxyOrchestrator(mockFactory, mockProtocolRepo, mockModelRepo, mockBackendRepo);

        var anthropicExt = AnthropicExtensions.builder().metadataUserId("user-456").build();
        var req = UnifiedChatRequest.builder()
            .model("claude-3")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .anthropic(anthropicExt)
            .build();

        orchestrator.handleStream(req, "anthropic").blockLast();

        ArgumentCaptor<UnifiedChatRequest> captor = ArgumentCaptor.forClass(UnifiedChatRequest.class);
        verify(mockAdapter).stream(captor.capture());
        assertThat(captor.getValue().anthropic()).isNotNull();
        assertThat(captor.getValue().anthropic().metadataUserId()).isEqualTo("user-456");
    }
}
