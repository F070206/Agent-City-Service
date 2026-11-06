import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

async function withRequestService(run) {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  const storage = new Map();
  const events = [];
  const originalLocalStorage = globalThis.localStorage;
  const originalWindow = globalThis.window;
  globalThis.localStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: (key) => storage.delete(key)
  };
  globalThis.window = { dispatchEvent: (event) => events.push(event) };

  try {
    const { default: service } = await server.ssrLoadModule('/src/api/request.js');
    await run(service, storage, events);
  } finally {
    globalThis.localStorage = originalLocalStorage;
    globalThis.window = originalWindow;
    await server.close();
  }
}

function unauthorized(config) {
  const error = new Error('unauthorized');
  error.config = config;
  error.response = { status: 401, config };
  return Promise.reject(error);
}

test('late unauthorized response for an old token does not clear a new login', async () => {
  await withRequestService(async (service, storage, events) => {
    storage.set('hm-dianping-token', 'old-token');
    await assert.rejects(service.get('/protected', {
      adapter: (config) => {
        storage.set('hm-dianping-token', 'new-token');
        return unauthorized(config);
      }
    }), /unauthorized/);

    assert.equal(storage.get('hm-dianping-token'), 'new-token');
    assert.deepEqual(events, []);
  });
});

test('401 for the active token clears it and reports which session expired', async () => {
  await withRequestService(async (service, storage, events) => {
    storage.set('hm-dianping-token', 'expired-token');
    await assert.rejects(service.get('/protected', { adapter: unauthorized }), /unauthorized/);

    assert.equal(storage.has('hm-dianping-token'), false);
    assert.equal(events.length, 1);
    assert.equal(events[0].type, 'hm-auth-expired');
    assert.equal(events[0].detail.token, 'expired-token');
  });
});

test('HTTP 429 and 503 errors use the backend message while preserving status details', async () => {
  await withRequestService(async (service) => {
    for (const [status, errorMsg] of [
      [429, '请求过于频繁，请稍后再试'],
      [503, '登录状态服务暂不可用，请稍后再试']
    ]) {
      const error = new Error(`Request failed with status code ${status}`);
      error.response = {
        status,
        data: { success: false, errorMsg, data: { retryable: true } }
      };

      await assert.rejects(service.get('/protected', {
        adapter: () => Promise.reject(error)
      }), (received) => {
        assert.equal(received.message, errorMsg);
        assert.equal(received.response.status, status);
        assert.deepEqual(received.data, { retryable: true });
        return true;
      });
    }
  });
});

test('a cleanup request can retain its original account token without clearing a newer session', async () => {
  await withRequestService(async (service, storage) => {
    storage.set('hm-dianping-token', 'account-b-token');
    let requestConfig;
    await service.delete('/upload/blog', {
      __authTokenOverride: 'account-a-token',
      adapter: (config) => {
        requestConfig = config;
        return Promise.resolve({
          data: { success: true, data: null },
          status: 200,
          statusText: 'OK',
          headers: {},
          config
        });
      }
    });

    assert.equal(requestConfig.headers.authorization, 'account-a-token');
    assert.equal(storage.get('hm-dianping-token'), 'account-b-token');

    await assert.rejects(service.get('/protected', {
      __authTokenOverride: 'account-a-token',
      adapter: unauthorized
    }), /unauthorized/);
    assert.equal(storage.get('hm-dianping-token'), 'account-b-token');
  });
});
