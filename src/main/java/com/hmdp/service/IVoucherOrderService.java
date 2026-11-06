package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;
import com.hmdp.dto.OrderHistoryItem;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result buyVoucher(Long voucherId);

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    void closeTimeoutOrder(Long orderId);

    List<OrderHistoryItem> listUserOrders(Long userId, Integer current);
}
