import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

test('resetting login code countdown clears its timer and value', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });

  try {
    const { resetLoginCodeCountdown } = await server.ssrLoadModule('/src/utils/login-code-countdown.js');
    const timer = { value: 17 };
    const countdown = { value: 42 };
    const cleared = [];

    resetLoginCodeCountdown({ timer, countdown }, (id) => cleared.push(id));

    assert.deepEqual(cleared, [17]);
    assert.equal(timer.value, null);
    assert.equal(countdown.value, 0);
  } finally {
    await server.close();
  }
});
