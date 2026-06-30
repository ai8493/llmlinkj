package com.ai8493.llmproxy.config;

import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

@Configuration
public class ReactorHooksConfig {

    static {
        Hooks.enableAutomaticContextPropagation();
    }
}
