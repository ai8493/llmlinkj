package com.ai8493.llmproxy.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class ProxyMetrics {

    private final MeterRegistry registry;

    public ProxyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordRequest(String protocol, String backend,
                               boolean stream, String status) {
        Counter.builder("proxy.requests.total")
            .tags("protocol", protocol, "backend", backend,
                  "stream", String.valueOf(stream), "status", status)
            .register(registry)
            .increment();
    }

    public void recordBackendCallDuration(String backend, String model, long durationMs) {
        Timer.builder("proxy.backend.call.duration")
            .tags("backend", backend, "model", model)
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordConversionDuration(String direction, long durationMs) {
        Timer.builder("proxy.conversion.duration")
            .tags("direction", direction)
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordBackendError(String backend, String errorCode) {
        Counter.builder("proxy.backend.errors")
            .tags("backend", backend, "error_code", errorCode)
            .register(registry)
            .increment();
    }
}
