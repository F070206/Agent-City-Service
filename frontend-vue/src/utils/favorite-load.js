export async function loadFavoriteShops(ids, getShopDetail) {
  const results = await Promise.allSettled(ids.map((shopId) => getShopDetail(shopId)));
  return {
    shops: results.filter((item) => item.status === 'fulfilled').map((item) => item.value),
    failedCount: results.filter((item) => item.status === 'rejected').length
  };
}
