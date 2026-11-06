package com.hmdp.ai.config;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import java.util.Iterator;
import java.util.function.Supplier;

public final class AiDependencyGuard {
    private AiDependencyGuard() {
    }

    public static <T> T call(String resource, CheckedSupplier<T> supplier) throws Exception {
        Entry entry = enter(resource);
        try {
            return supplier.get();
        } catch (Exception e) {
            Tracer.trace(e);
            throw e;
        } finally {
            entry.exit();
        }
    }

    public static <T> T callUnchecked(String resource, Supplier<T> supplier) {
        try {
            return call(resource, supplier::get);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("外部依赖调用失败：" + resource, e);
        }
    }

    public static <T> void consume(String resource, CheckedSupplier<Iterable<T>> source,
            CheckedConsumer<T> consumer) throws Exception {
        consume(resource, resource, source, consumer);
    }

    public static <T> void consume(String resource, String readResource,
            CheckedSupplier<Iterable<T>> source, CheckedConsumer<T> consumer) throws Exception {
        Entry streamEntry = enter(resource);
        try {
            // Iterators backed by streaming clients perform network reads during hasNext/next.
            // Keep iterator creation and one read in the same Sentinel entry, then deliver the
            // value outside it so downstream SSE latency is not attributed to the model. The
            // stream entry tracks one exception result for the whole invocation; the read entry
            // supplies latency measurements for each upstream wait.
            Iterator<T> iterator = null;
            while (true) {
                Iterator<T> current = iterator;
                StreamRead<T> read;
                try {
                    read = call(readResource, () -> {
                        Iterator<T> active = current == null ? source.get().iterator() : current;
                        if (!active.hasNext()) {
                            return new StreamRead<>(active, null, false);
                        }
                        return new StreamRead<>(active, active.next(), true);
                    });
                } catch (Exception e) {
                    if (!resource.equals(readResource) && !isCircuitOpen(e)) {
                        Tracer.trace(e);
                    }
                    throw e;
                }
                iterator = read.iterator();
                if (!read.hasValue()) {
                    return;
                }
                consumer.accept(read.value());
            }
        } finally {
            streamEntry.exit();
        }
    }

    private record StreamRead<T>(Iterator<T> iterator, T value, boolean hasValue) {
    }

    private static Entry enter(String resource) {
        try {
            return SphU.entry(resource);
        } catch (BlockException e) {
            throw new IllegalStateException("外部依赖暂时熔断：" + resource, e);
        }
    }

    private static boolean isCircuitOpen(Throwable error) {
        return error instanceof IllegalStateException && error.getCause() instanceof BlockException;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface CheckedConsumer<T> {
        void accept(T value) throws Exception;
    }
}
