import request from '@/api/request';

export const orderApi = {
  getMy(current = 1) {
    return request({
      url: '/voucher-order/my',
      method: 'get',
      params: { current }
    });
  }
};
