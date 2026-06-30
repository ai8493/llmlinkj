package com.ai8493.llmproxy.filter;

import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;

@Configuration
public class TraceIdConfig {

    @PostConstruct
    void setup() {
        Hooks.onEachOperator(Operators.liftPublisher((pub, sub) -> sub));
    }
}
