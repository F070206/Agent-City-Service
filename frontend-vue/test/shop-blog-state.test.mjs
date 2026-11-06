import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

test('shop blog section distinguishes loading, empty, failed, and loaded states', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });

  try {
    const { getShopBlogState } = await server.ssrLoadModule('/src/utils/shop-blog-state.js');
    assert.equal(getShopBlogState({ failed: false, loading: true, blogs: [] }), 'loading');
    assert.equal(getShopBlogState({ failed: false, loading: false, blogs: [] }), 'empty');
    assert.equal(getShopBlogState({ failed: true, loading: false, blogs: [] }), 'failed');
    assert.equal(getShopBlogState({ failed: false, loading: false, blogs: [{ id: 1 }] }), 'loaded');
    assert.equal(getShopBlogState({ failed: true, loading: true, blogs: [] }), 'failed');
  } finally {
    await server.close();
  }
});
