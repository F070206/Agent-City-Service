export function isCurrentShopRequest(requestId, currentRequestId, requestedShopId, currentShopId) {
  return requestId === currentRequestId && String(requestedShopId) === String(currentShopId);
}
