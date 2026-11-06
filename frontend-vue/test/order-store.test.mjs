import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import { createPinia, setActivePinia } from 'pinia';

test('pending seckill state is persisted and cleared when the order is confirmed', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    const storage = new Map();
    globalThis.localStorage = {
      getItem: (key) => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, String(value)),
      removeItem: (key) => storage.delete(key)
    };
    setActivePinia(createPinia());
    const { useUserStore } = await server.ssrLoadModule('/src/store/user.js');
    const store = useUserStore();

    store.recordOrder({
      orderId: '9007199254740993',
      asyncCreated: true,
      creationPending: true,
      status: 1
    });

    assert.equal(store.orderHistory[0].creationPending, true);
    assert.equal(store.orderHistory[0].orderId, '9007199254740993');

    store.updateOrder('9007199254740993', { creationPending: false, status: 1 });

    assert.equal(store.orderHistory[0].creationPending, false);
    assert.equal(store.orderHistory[0].status, 1);
  } finally {
    await server.close();
  }
});
