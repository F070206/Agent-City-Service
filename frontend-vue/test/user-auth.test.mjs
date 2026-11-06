import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import { createPinia, setActivePinia } from 'pinia';

async function withUserStore(run) {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  const storage = new Map();
  const originalLocalStorage = globalThis.localStorage;
  globalThis.localStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: (key) => storage.delete(key)
  };
  setActivePinia(createPinia());
  try {
    const { useUserStore } = await server.ssrLoadModule('/src/store/user.js');
    const { userApi } = await server.ssrLoadModule('/src/api/modules/user.js');
    await run(useUserStore(), userApi, storage);
  } finally {
    globalThis.localStorage = originalLocalStorage;
    await server.close();
  }
}

test('restoring the active account local state advances its version after orders load', async () => {
  await withUserStore(async (store, _userApi, storage) => {
    storage.set('hm-dianping-orders:7', JSON.stringify([
      { orderId: 'account-7-order', shopId: 12, createdTime: '2026-11-06T10:00:00Z' }
    ]));
    store.token = 'token-7';
    store.profile = { id: 7, nickname: 'user 7' };

    store.loadUserScopedState();

    assert.equal(store.scopedStateVersion, 1);
    assert.equal(store.orderHistory[0].orderId, 'account-7-order');
  });
});

test('invalid local state shapes fall back to empty account collections', async () => {
  await withUserStore(async (store, _userApi, storage) => {
    storage.set('hm-dianping-favorites:7', JSON.stringify({ id: 11 }));
    storage.set('hm-dianping-orders:7', JSON.stringify({ orderId: 'broken' }));
    storage.set('hm-dianping-addresses:7', JSON.stringify('broken'));
    store.token = 'token-7';
    store.profile = { id: 7, nickname: 'user 7' };

    store.loadUserScopedState();

    assert.deepEqual(store.favoriteShopIds, []);
    assert.deepEqual(store.orderHistory, []);
    assert.deepEqual(store.addressList, []);
    assert.equal(store.scopedStateVersion, 1);
  });
});

test('sendCode returns the optional local mock code to the login form', async () => {
  await withUserStore(async (store, userApi) => {
    const originalSendCode = userApi.sendCode;
    userApi.sendCode = async () => '654321';
    try {
      assert.equal(await store.sendCode('13686869696'), '654321');
    } finally {
      userApi.sendCode = originalSendCode;
    }
  });
});

test('failed favorite persistence keeps the current favorites unchanged', async () => {
  await withUserStore(async (store) => {
    store.token = 'token-7';
    store.profile = { id: 7, nickname: 'user 7' };
    store.favoriteShopIds = [11];
    const originalSetItem = globalThis.localStorage.setItem;
    globalThis.localStorage.setItem = () => { throw new Error('storage full'); };

    try {
      assert.throws(() => store.toggleFavorite(22), /storage full/);
      assert.deepEqual(store.favoriteShopIds, [11]);
      assert.throws(() => store.clearFavorites(), /storage full/);
      assert.deepEqual(store.favoriteShopIds, [11]);
    } finally {
      globalThis.localStorage.setItem = originalSetItem;
    }
  });
});

test('failed address persistence keeps the current address list unchanged', async () => {
  await withUserStore(async (store) => {
    store.token = 'token-7';
    store.profile = { id: 7, nickname: 'user 7' };
    store.addressList = [{ id: 1, detail: '已有地址' }];
    const originalSetItem = globalThis.localStorage.setItem;
    globalThis.localStorage.setItem = () => { throw new Error('storage full'); };

    try {
      assert.throws(() => store.addAddress({ detail: '新地址' }), /storage full/);
      assert.deepEqual(store.addressList, [{ id: 1, detail: '已有地址' }]);
      assert.throws(() => store.removeAddress(1), /storage full/);
      assert.deepEqual(store.addressList, [{ id: 1, detail: '已有地址' }]);
    } finally {
      globalThis.localStorage.setItem = originalSetItem;
    }
  });
});

test('failed profile lookup clears the issued token and keeps login open', async () => {
  await withUserStore(async (store, userApi, storage) => {
    const originalLogin = userApi.login;
    const originalGetMe = userApi.getMe;
    const originalLogout = userApi.logout;
    let logoutCalls = 0;
    userApi.login = async () => 'issued-token';
    userApi.getMe = async () => { throw new Error('profile unavailable'); };
    userApi.logout = async () => { logoutCalls += 1; };
    store.loginDialogVisible = true;
    try {
      await assert.rejects(store.login({ phone: '13800000000', code: '123456' }), /profile unavailable/);
      assert.equal(store.token, '');
      assert.equal(storage.has('hm-dianping-token'), false);
      assert.equal(store.profile, null);
      assert.equal(store.loginDialogVisible, true);
      assert.equal(logoutCalls, 1);
    } finally {
      userApi.login = originalLogin;
      userApi.getMe = originalGetMe;
      userApi.logout = originalLogout;
    }
  });
});

test('empty profile response is not treated as a successful login', async () => {
  await withUserStore(async (store, userApi, storage) => {
    const originalLogin = userApi.login;
    const originalGetMe = userApi.getMe;
    const originalLogout = userApi.logout;
    userApi.login = async () => 'issued-token';
    userApi.getMe = async () => null;
    userApi.logout = async () => {};
    store.loginDialogVisible = true;
    try {
      await assert.rejects(store.login({ phone: '13800000000', code: '123456' }), /登录用户信息/);
      assert.equal(store.token, '');
      assert.equal(storage.has('hm-dianping-token'), false);
      assert.equal(store.profile, null);
      assert.equal(store.loginDialogVisible, true);
    } finally {
      userApi.login = originalLogin;
      userApi.getMe = originalGetMe;
      userApi.logout = originalLogout;
    }
  });
});

test('failed server logout preserves the token so the session can be retried', async () => {
  await withUserStore(async (store, userApi, storage) => {
    const originalLogin = userApi.login;
    const originalGetMe = userApi.getMe;
    const originalLogout = userApi.logout;
    userApi.login = async () => 'issued-token';
    userApi.getMe = async () => { throw new Error('profile unavailable'); };
    userApi.logout = async () => { throw new Error('logout unavailable'); };
    store.loginDialogVisible = true;
    try {
      await assert.rejects(store.login({ phone: '13800000000', code: '123456' }), /profile unavailable/);
      assert.equal(store.token, 'issued-token');
      assert.equal(storage.get('hm-dianping-token'), 'issued-token');
      assert.equal(store.profile, null);
      assert.equal(store.isLoggedIn, false);
      assert.equal(store.loginDialogVisible, true);
    } finally {
      userApi.login = originalLogin;
      userApi.getMe = originalGetMe;
      userApi.logout = originalLogout;
    }
  });
});

test('unauthorized server logout clears the invalid token', async () => {
  await withUserStore(async (store, userApi, storage) => {
    const originalLogin = userApi.login;
    const originalGetMe = userApi.getMe;
    const originalLogout = userApi.logout;
    userApi.login = async () => 'issued-token';
    userApi.getMe = async () => { throw new Error('profile unavailable'); };
    userApi.logout = async () => {
      const error = new Error('unauthorized');
      error.response = { status: 401 };
      throw error;
    };
    store.loginDialogVisible = true;
    try {
      await assert.rejects(store.login({ phone: '13800000000', code: '123456' }), /profile unavailable/);
      assert.equal(store.token, '');
      assert.equal(storage.has('hm-dianping-token'), false);
    } finally {
      userApi.login = originalLogin;
      userApi.getMe = originalGetMe;
      userApi.logout = originalLogout;
    }
  });
});

test('late initial auth response cannot replace a newly logged-in user or their local state', async () => {
  await withUserStore(async (store, userApi, storage) => {
    const originalLogin = userApi.login;
    const originalGetMe = userApi.getMe;
    const originalGetSignCount = userApi.getSignCount;
    let resolveInitialProfile;
    let profileRequests = 0;
    store.token = 'initial-token';
    storage.set('hm-dianping-token', 'initial-token');
    storage.set('hm-dianping-favorites:1', JSON.stringify([11]));
    storage.set('hm-dianping-favorites:2', JSON.stringify([22]));
    userApi.login = async () => 'new-token';
    userApi.getMe = () => {
      profileRequests += 1;
      if (profileRequests === 1) {
        return new Promise((resolve) => { resolveInitialProfile = resolve; });
      }
      return Promise.resolve({ id: 2, nickName: 'new user' });
    };
    userApi.getSignCount = async () => 3;

    try {
      const initialAuth = store.initAuth();
      await new Promise((resolve) => setImmediate(resolve));
      await store.login({ phone: '13800000000', code: '123456' });
      assert.equal(store.profile.id, 2);
      assert.deepEqual(store.favoriteShopIds, [22]);

      resolveInitialProfile({ id: 1, nickName: 'old user' });
      await initialAuth;

      assert.equal(store.profile.id, 2);
      assert.equal(store.profile.nickname, 'new user');
      assert.deepEqual(store.favoriteShopIds, [22]);
      assert.equal(storage.get('hm-dianping-token'), 'new-token');
    } finally {
      userApi.login = originalLogin;
      userApi.getMe = originalGetMe;
      userApi.getSignCount = originalGetSignCount;
    }
  });
});

test('switching accounts clears the old profile while the new profile is loading', async () => {
  await withUserStore(async (store, userApi, storage) => {
    const originalLogin = userApi.login;
    const originalGetMe = userApi.getMe;
    const originalGetSignCount = userApi.getSignCount;
    let resolveNewProfile;
    store.token = 'old-token';
    storage.set('hm-dianping-token', 'old-token');
    store.profile = { id: 1, nickname: 'old user' };
    userApi.login = async () => 'new-token';
    userApi.getMe = () => new Promise((resolve) => { resolveNewProfile = resolve; });
    userApi.getSignCount = async () => 0;

    try {
      const login = store.login({ phone: '13800000000', code: '123456' });
      await new Promise((resolve) => setImmediate(resolve));
      assert.equal(store.profile, null);
      assert.equal(store.isLoggedIn, false);

      resolveNewProfile({ id: 2, nickName: 'new user' });
      await login;
      assert.equal(store.profile.id, 2);
      assert.equal(store.isLoggedIn, true);
    } finally {
      userApi.login = originalLogin;
      userApi.getMe = originalGetMe;
      userApi.getSignCount = originalGetSignCount;
    }
  });
});
