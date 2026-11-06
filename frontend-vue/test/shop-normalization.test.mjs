import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

test('real shop fields are not filled from a demo shop with the same id', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });

  try {
    const { normalizeShop } = await server.ssrLoadModule('/src/utils/shop.js');
    const shop = normalizeShop({
      id: 1,
      name: '103茶餐厅',
      typeId: 1,
      address: '金华路锦昌文华苑29号',
      avgPrice: 80,
      score: 37,
      sold: 4215,
      comments: 3035,
      openHours: '10:00-22:00'
    });

    assert.equal(shop.name, '103茶餐厅');
    assert.equal(shop.address, '金华路锦昌文华苑29号');
    assert.equal(shop.score, 3.7);
    assert.equal(shop.price, 80);
    assert.equal(shop.distance, '距离未知');
    assert.equal(shop.phone, '暂未提供');
    assert.equal(shop.status, '营业状态未知');
    assert.deepEqual(shop.dishes, []);
    assert.deepEqual(shop.comments, []);
    assert.equal(shop.recommendText, '销量 4215，评论 3035');
  } finally {
    await server.close();
  }
});

test('missing real shop fields stay explicitly unknown instead of receiving demo claims', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });

  try {
    const { normalizeShop } = await server.ssrLoadModule('/src/utils/shop.js');
    const shop = normalizeShop({ id: 2, name: '真实商家' });

    assert.equal(shop.score, null);
    assert.equal(shop.price, 0);
    assert.equal(shop.recommendText, '暂无商家介绍');
    assert.equal(shop.address, '暂无地址');
    assert.equal(shop.openHours, '营业时间以门店为准');
  } finally {
    await server.close();
  }
});
