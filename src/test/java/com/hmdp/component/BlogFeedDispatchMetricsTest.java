package com.hmdp.component;

import com.hmdp.entity.BlogFeedDispatchBacklog;
import com.hmdp.mapper.BlogFeedDispatchMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class BlogFeedDispatchMetricsTest {
    @Test
    void publishesBacklogSnapshotAndKeepsItWhenRefreshFails() {
        BlogFeedDispatchMapper mapper = mock(BlogFeedDispatchMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BlogFeedDispatchMetrics metrics = new BlogFeedDispatchMetrics(mapper, registry);
        BlogFeedDispatchBacklog snapshot = new BlogFeedDispatchBacklog();
        snapshot.setPendingCount(12L);
        snapshot.setOldestPendingAgeSeconds(46L);
        snapshot.setRepeatedFailureCount(3L);
        when(mapper.getBacklogSnapshot(3)).thenReturn(snapshot);

        metrics.refreshSnapshot();

        assertEquals(12.0, registry.get("cityai.blog.feed.dispatch.pending").gauge().value());
        assertEquals(46.0, registry.get("cityai.blog.feed.dispatch.oldest.age").gauge().value());
        assertEquals(3.0, registry.get("cityai.blog.feed.dispatch.repeated.failures").gauge().value());

        when(mapper.getBacklogSnapshot(3)).thenThrow(new IllegalStateException("database unavailable"));
        metrics.refreshSnapshot();
        assertEquals(12.0, registry.get("cityai.blog.feed.dispatch.pending").gauge().value());
        assertEquals(46.0, registry.get("cityai.blog.feed.dispatch.oldest.age").gauge().value());
    }
}
