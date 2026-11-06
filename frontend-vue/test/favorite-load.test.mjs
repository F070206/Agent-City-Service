import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

test('favorite loading reports partial and complete lookup failures without changing saved ids', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });

  try {
    const { loadFavoriteShops } = await server.ssrLoadModule('/src/utils/favorite-load.js');
    const ids = [1, 2, 3];
    const result = await loadFavoriteShops(ids, async (id) => {
      if (id === 2) throw new Error('offline');
      return { id };
    });

    assert.deepEqual(ids, [1, 2, 3]);
    assert.deepEqual(result, { shops: [{ id: 1 }, { id: 3 }], failedCount: 1 });

    const allFailed = await loadFavoriteShops([4], async () => {
      throw new Error('offline');
    });
    assert.deepEqual(allFailed, { shops: [], failedCount: 1 });

    const empty = await loadFavoriteShops([], async () => null);
    assert.deepEqual(empty, { shops: [], failedCount: 0 });
  } finally {
    await server.close();
  }
});
