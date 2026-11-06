import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

test('only the latest request for the current shop may continue loading details', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });

  try {
    const { isCurrentShopRequest } = await server.ssrLoadModule('/src/utils/shop-request.js');
    assert.equal(isCurrentShopRequest(2, 2, '12', 12), true);
    assert.equal(isCurrentShopRequest(1, 2, '12', 12), false);
    assert.equal(isCurrentShopRequest(2, 2, 11, 12), false);
  } finally {
    await server.close();
  }
});
