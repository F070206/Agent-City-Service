export const ORDER_PAGE_SIZE = 20;
export const MAX_ORDER_PAGE = 50;

export function canPayOrder(order, orderReady) {
  return orderReady && Number(order?.status) === 1;
}

export function findLatestOrderForShop(orders, shopId) {
  return orders.find((order) => Number(order.shopId) === Number(shopId)) || null;
}

export function hasMoreOrderPages(pageItems, currentPage) {
  return Array.isArray(pageItems)
    && pageItems.length >= ORDER_PAGE_SIZE
    && Number(currentPage) < MAX_ORDER_PAGE;
}

export function orderPageFromResult(result) {
  return result?.status === 'fulfilled' && Array.isArray(result.value) ? result.value : null;
}

export function orderPayAmount(order) {
  const localAmount = order?.payAmount;
  if (isFiniteAmount(localAmount)) return String(localAmount);

  const payValue = order?.payValue;
  return isFiniteAmount(payValue) ? (Number(payValue) / 100).toFixed(2) : '';
}

function isFiniteAmount(value) {
  return value != null && String(value).trim() !== '' && Number.isFinite(Number(value));
}

export function beginOrderStatusRefresh(activeOrderIds, orderId) {
  const key = String(orderId);
  if (activeOrderIds.has(key)) return null;
  return new Set([...activeOrderIds, key]);
}

export function endOrderStatusRefresh(activeOrderIds, orderId) {
  const next = new Set(activeOrderIds);
  next.delete(String(orderId));
  return next;
}

export function mergeOrderPage(localOrders, remoteOrders, append = false) {
  const remotePages = append
    ? localOrders.filter((order) => order.localOnly !== true)
    : [];
  const remoteById = new Map(remotePages.map((order) => [String(order.orderId), order]));
  for (const order of remoteOrders) {
    remoteById.set(String(order.orderId), order);
  }
  const allRemoteOrders = [...remoteById.values()];
  const localById = new Map(localOrders.map((order) => [String(order.orderId), order]));
  const remoteIds = new Set(allRemoteOrders.map((order) => String(order.orderId)));
  const syncedOrders = allRemoteOrders.map((order) => {
    const local = localById.get(String(order.orderId)) || {};
    return {
      ...local,
      ...order,
      orderId: String(order.orderId),
      shopName: local.shopName || (order.shopId ? `店铺 ${order.shopId}` : ''),
      voucherTitle: order.voucherTitle || local.voucherTitle,
      payAmount: orderPayAmount({ ...order, payAmount: local.payAmount }),
      actualAmount: local.actualAmount || '',
      asyncCreated: local.asyncCreated ?? Number(order.voucherType) === 1,
      creationPending: false,
      localOnly: false,
      createdTime: order.createdTime || local.createdTime
    };
  });
  const remainingOrders = localOrders
    .filter((order) => !remoteIds.has(String(order.orderId)) && order.localOnly !== false)
    .map((order) => ({ ...order, localOnly: order.localOnly ?? true }));
  return [...syncedOrders, ...remainingOrders];
}
