package com.hmdp.service.impl;

import com.hmdp.component.SeckillActivityCache;
import com.hmdp.dto.SeckillActivityUpdateRequest;
import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VoucherServiceActivityTest {
    @AfterEach
    void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void refreshesRedisOnlyAfterDatabaseCommitAndPreservesStock() {
        VoucherServiceImpl service = spy(new VoucherServiceImpl());
        SeckillVoucherMapper mapper = mock(SeckillVoucherMapper.class);
        ISeckillVoucherService seckillService = mock(ISeckillVoucherService.class);
        SeckillActivityCache cache = mock(SeckillActivityCache.class);
        SeckillVoucherLookup current = new SeckillVoucherLookup();
        current.setVoucherId(9L);
        current.setStock(17);
        current.setVoucherType(1);
        current.setVersion(0L);
        SeckillVoucherLookup updated = new SeckillVoucherLookup();
        updated.setVoucherId(9L);
        updated.setStock(17);
        updated.setVoucherType(1);
        updated.setVoucherStatus(1);
        updated.setBeginTime(request().getBeginTime());
        updated.setEndTime(request().getEndTime());
        updated.setVersion(1L);
        when(mapper.findForSeckill(9L)).thenReturn(current, updated);
        ReflectionTestUtils.setField(service, "seckillVoucherMapper", mapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillService);
        ReflectionTestUtils.setField(service, "seckillActivityCache", cache);
        doReturn(true).when(service).updateById(any(Voucher.class));
        when(mapper.updateActivity(eq(9L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        SeckillActivityUpdateRequest request = request();
        service.updateSeckillActivity(9L, request);
        verify(cache, never()).refreshAfterCommit(any());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.get(0).afterCommit();
        verify(cache).refreshAfterCommit(argThat(activity -> activity.getStock() == 17
                && activity.getVoucherStatus() == 1
                && activity.getVersion() == 1L
                && request.getBeginTime().equals(activity.getBeginTime())
                && request.getEndTime().equals(activity.getEndTime())));
        verify(seckillService, never()).updateById(any(SeckillVoucher.class));
    }

    @Test
    void rejectsInvalidActivityWindowBeforeDatabaseAccess() {
        VoucherServiceImpl service = spy(new VoucherServiceImpl());
        SeckillVoucherMapper mapper = mock(SeckillVoucherMapper.class);
        ReflectionTestUtils.setField(service, "seckillVoucherMapper", mapper);
        SeckillActivityUpdateRequest request = request();
        request.setEndTime(request.getBeginTime().minusSeconds(1));

        assertThrows(IllegalArgumentException.class, () -> service.updateSeckillActivity(9L, request));
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsEmptyActivityWindowBeforeDatabaseAccess() {
        VoucherServiceImpl service = spy(new VoucherServiceImpl());
        SeckillVoucherMapper mapper = mock(SeckillVoucherMapper.class);
        ReflectionTestUtils.setField(service, "seckillVoucherMapper", mapper);
        SeckillActivityUpdateRequest request = request();
        request.setEndTime(request.getBeginTime());

        assertThrows(IllegalArgumentException.class, () -> service.updateSeckillActivity(9L, request));
        verifyNoInteractions(mapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesNewSeckillStockAndActivityOnlyAfterDatabaseCommit() {
        VoucherServiceImpl service = spy(new VoucherServiceImpl());
        ISeckillVoucherService seckillService = mock(ISeckillVoucherService.class);
        SeckillActivityCache cache = mock(SeckillActivityCache.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        com.hmdp.mapper.VoucherMapper voucherMapper = mock(com.hmdp.mapper.VoucherMapper.class);
        when(redis.opsForValue()).thenReturn(values);
        doReturn(voucherMapper).when(service).getBaseMapper();
        doReturn(true).when(service).save(any(Voucher.class));
        when(seckillService.save(any(SeckillVoucher.class))).thenReturn(true);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "seckillActivityCache", cache);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        Voucher voucher = new Voucher();
        voucher.setId(42L);
        voucher.setStock(5);
        voucher.setStatus(1);
        voucher.setType(1);
        service.addSeckillVoucher(voucher);

        verify(redis, never()).opsForValue();
        verify(cache, never()).put(any());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.get(0).afterCommit();
        verify(values).set("seckill:stock:42", "5");
        verify(cache).put(argThat(activity -> activity.getVoucherId().equals(42L)
                && activity.getStock() == 5 && activity.getVoucherStatus() == 1));
    }

    private SeckillActivityUpdateRequest request() {
        SeckillActivityUpdateRequest request = new SeckillActivityUpdateRequest();
        request.setStatus(1);
        request.setBeginTime(LocalDateTime.of(2026, 11, 4, 10, 0));
        request.setEndTime(LocalDateTime.of(2026, 11, 5, 10, 0));
        return request;
    }
}
