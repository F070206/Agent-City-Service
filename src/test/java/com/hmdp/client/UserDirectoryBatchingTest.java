package com.hmdp.client;

import com.hmdp.service.IUserService;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDirectoryBatchingTest {
    @Test
    void localDirectorySplitsLargeDatabaseQueriesIntoBatchesOfFiveHundred() {
        IUserService userService = mock(IUserService.class);
        when(userService.listByIds(anyList())).thenReturn(List.of());
        List<Long> ids = userIds(501);

        new LocalUserDirectory(userService).findByIds(ids);

        ArgumentCaptor<List<Long>> batches = ArgumentCaptor.forClass(List.class);
        verify(userService, times(2)).listByIds(batches.capture());
        assertEquals(List.of(ids.subList(0, 500), ids.subList(500, 501)), batches.getAllValues());
    }

    @Test
    void remoteDirectorySplitsFeignRequestsIntoBatchesOfFiveHundred() {
        UserFeignClient client = mock(UserFeignClient.class);
        when(client.findByIds(anyList())).thenReturn(List.of());
        List<Long> ids = userIds(501);

        new RemoteUserDirectory(client, new InternalServiceMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .findByIds(ids);

        ArgumentCaptor<List<Long>> batches = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).findByIds(batches.capture());
        assertEquals(List.of(ids.subList(0, 500), ids.subList(500, 501)), batches.getAllValues());
    }

    private List<Long> userIds(int count) {
        return LongStream.rangeClosed(1, count).boxed().collect(Collectors.toList());
    }
}
