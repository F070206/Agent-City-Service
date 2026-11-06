import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

test('SSE connection errors preserve backend message and HTTP status', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  const originalLocalStorage = globalThis.localStorage;
  const originalFetch = globalThis.fetch;
  globalThis.localStorage = { getItem: () => 'session-token' };

  try {
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    for (const [status, errorMsg] of [
      [429, '当前 AI 连接较多，请稍后重试'],
      [503, 'AI 服务暂不可用，请稍后重试']
    ]) {
      globalThis.fetch = async () => ({
        ok: false,
        status,
        headers: { get: () => 'application/json' },
        json: async () => ({ success: false, errorMsg })
      });

      await assert.rejects(aiApi.streamConversation(8), (error) => {
        assert.equal(error.message, errorMsg);
        assert.equal(error.response.status, status);
        return true;
      });
    }
  } finally {
    globalThis.localStorage = originalLocalStorage;
    globalThis.fetch = originalFetch;
    await server.close();
  }
});
