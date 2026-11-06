import assert from 'node:assert/strict';
import test from 'node:test';
import {
  beginOrderStatusRefresh,
  canPayOrder,
  endOrderStatusRefresh,
  findLatestOrderForShop,
  hasMoreOrderPages,
  mergeOrderPage,
  orderPageFromResult
} from '../src/utils/orders.js';

test('an invalid order page response is rejected before it reaches the merge path', () => {
  const localOrders = [{ orderId: 'local-1', localOnly: true }];

  assert.equal(orderPageFromResult({ status: 'fulfilled', value: { items: [] } }), null);
  assert.equal(orderPageFromResult({ status: 'rejected', reason: new Error('offline') }), null);
  assert.equal(orderPageFromResult({ status: 'fulfilled', value: localOrders }), localOrders);
  assert.deepEqual(mergeOrderPage(localOrders, orderPageFromResult({
    status: 'fulfilled', value: { items: [] }
  }) || []), localOrders);
});

test('payment is enabled only for a persisted unpaid order', () => {
  assert.equal(canPayOrder({ status: 1 }, true), true);
  assert.equal(canPayOrder({ status: 1 }, false), false);
  for (const status of [2, 3, 4, 5, 6, undefined]) {
    assert.equal(canPayOrder({ status }, true), false);
  }
  assert.equal(canPayOrder(null, true), false);
});

test('order status refresh locks each order against duplicate requests', () => {
  let active = new Set();
  active = beginOrderStatusRefresh(active, 42);

  assert.equal(active.has('42'), true);
  assert.equal(beginOrderStatusRefresh(active, '42'), null);
  active = beginOrderStatusRefresh(active, 43);
  assert.deepEqual([...active].sort(), ['42', '43']);

  active = endOrderStatusRefresh(active, '42');
  assert.deepEqual([...active], ['43']);
});

test('shop order lookup only returns an order from the supplied user scoped list', () => {
  const firstAccountOrders = [
    { orderId: 'account-a', shopId: 10 },
    { orderId: 'account-a-other-shop', shopId: 11 }
  ];
  const secondAccountOrders = [{ orderId: 'account-b-other-shop', shopId: 11 }];

  assert.equal(findLatestOrderForShop(firstAccountOrders, '10').orderId, 'account-a');
  assert.equal(findLatestOrderForShop(secondAccountOrders, 10), null);
});

test('order pagination stops at the backend 50 page limit', () => {
  const fullPage = Array.from({ length: 20 }, (_, index) => ({ orderId: index }));
  assert.equal(hasMoreOrderPages(fullPage, 49), true);
  assert.equal(hasMoreOrderPages(fullPage, 50), false);
  assert.equal(hasMoreOrderPages(fullPage.slice(0, 19), 1), false);
  assert.equal(hasMoreOrderPages(null, 1), false);
});

test('loading another remote order page keeps earlier pages and local-only orders', () => {
  const firstPage = mergeOrderPage([
    { orderId: 'local-1', localOnly: true, shopName: '本地订单' }
  ], [
    { orderId: 1, shopId: 10, payValue: 1200 },
    { orderId: 2, shopId: 20, payValue: 2300 }
  ]);

  const secondPage = mergeOrderPage(firstPage, [
    { orderId: 3, shopId: 30, payValue: 3400 }
  ], true);

  assert.deepEqual(secondPage.map((order) => order.orderId), ['1', '2', '3', 'local-1']);
  assert.equal(secondPage.find((order) => order.orderId === '1').payAmount, '12.00');
  assert.equal(secondPage.find((order) => order.orderId === 'local-1').shopName, '本地订单');
});

test('refreshing the first order page replaces stale remote pages but keeps local-only orders', () => {
  const refreshed = mergeOrderPage([
    { orderId: '1', localOnly: false },
    { orderId: '2', localOnly: false },
    { orderId: 'local-1', localOnly: true }
  ], [{ orderId: 1, status: 2 }]);

  assert.deepEqual(refreshed.map((order) => order.orderId), ['1', 'local-1']);
  assert.equal(refreshed[0].status, 2);
});

test('remote order refresh preserves an accepted seckill order that is still pending', () => {
  const pendingOrder = {
    orderId: '9007199254740993',
    asyncCreated: true,
    creationPending: true,
    localOnly: true,
    status: 1
  };
  const merged = mergeOrderPage([pendingOrder], [
    { orderId: 'remote-1', shopId: 10, status: 1 }
  ]);

  assert.deepEqual(merged.find((order) => order.orderId === pendingOrder.orderId), pendingOrder);
});
