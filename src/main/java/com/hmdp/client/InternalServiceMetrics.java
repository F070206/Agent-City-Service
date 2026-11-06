package com.hmdp.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;

@Component
public class InternalServiceMetrics {
    private final MeterRegistry registry;

    public InternalServiceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public <T> T record(String service, String operation, Callable<T> call) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return call.call();
        } catch (RuntimeException e) {
            outcome = "failure";
            throw e;
        } catch (Exception e) {
            outcome = "failure";
            throw new IllegalStateException("Internal service call failed", e);
        } finally {
            sample.stop(Timer.builder("cityai.internal.client.requests")
                    .description("Internal service client request count and latency")
                    .tag("service", service)
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }
}
