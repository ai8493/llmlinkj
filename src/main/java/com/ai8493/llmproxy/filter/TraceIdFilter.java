package com.ai8493.llmproxy.filter;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.Optional;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements WebFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = Optional.ofNullable(
                exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER))
            .orElse(UUID.randomUUID().toString().substring(0, 8));

        exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);

        // 在订阅前设入 MDC，配合 Hooks.enableAutomaticContextPropagation 自动传播
        MDC.put("traceId", traceId);

        return chain.filter(exchange)
            .contextWrite(ctx -> ctx.put("traceId", traceId))
            .doFinally(sig -> MDC.remove("traceId"));
    }
}
