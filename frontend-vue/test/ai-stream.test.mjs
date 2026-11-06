import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import { createPinia, setActivePinia } from 'pinia';

test('chat waits until the SSE connection opens', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    globalThis.localStorage = { getItem: () => null };
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    const originalStream = aiApi.streamConversation;
    const originalChat = aiApi.chat;
    const calls = [];
    let open;
    let emit;
    aiApi.streamConversation = async (_id, options) => {
      open = options.onOpen;
      emit = options.onEvent;
      await new Promise((resolve) => options.signal.addEventListener('abort', resolve));
    };
    aiApi.chat = async () => {
      calls.push('chat');
    };
    try {
      const store = useAiStore();
      store.updateMessageContent = () => {};
      const reply = store.askBackendByStream({
        conversationId: 1, prompt: 'hello', lastAssistantId: 0, loadingId: 1
      });
      await new Promise((resolve) => setImmediate(resolve));
      assert.deepEqual(calls, []);
      open();
      await new Promise((resolve) => setImmediate(resolve));
      assert.deepEqual(calls, ['chat']);
      emit({ event: 'answer_done', data: { payload: { messageId: 2, content: 'world' } } });
      assert.equal((await reply).content, 'world');

      aiApi.streamConversation = async () => {
        throw new Error('stream unavailable');
      };
      await assert.rejects(store.askBackendByStream({
        conversationId: 1, prompt: 'again', lastAssistantId: 2, loadingId: 3
      }), /stream unavailable/);
      assert.deepEqual(calls, ['chat']);

      aiApi.streamConversation = async (_id, options) => {
        options.onOpen();
        await new Promise((resolve) => options.signal.addEventListener('abort', resolve));
      };
      aiApi.chat = async () => {
        calls.push('chat');
      };
      aiApi.getMessages = async () => [{ id: 3, role: 'assistant', content: 'recovered' }];
      const originalWait = globalThis.setTimeout;
      const originalGetMessages = aiApi.getMessages;
      const originalEnsureConversation = store.ensureConversation;
      store.ensureConversation = async () => 1;
      let messageReads = 0;
      aiApi.getMessages = async () => {
        messageReads += 1;
        return messageReads === 1 ? [] : [{ id: 3, role: 'assistant', content: 'recovered' }];
      };
      globalThis.setTimeout = (callback) => {
        queueMicrotask(callback);
        return 0;
      };
      try {
        const replyAfterDisconnect = await store.askBackend({
          content: 'slow request', currentShop: null
        }, null, 4);
        assert.equal(replyAfterDisconnect.content, 'recovered');
        assert.deepEqual(calls, ['chat', 'chat']);
      } finally {
        globalThis.setTimeout = originalWait;
        aiApi.getMessages = originalGetMessages;
        store.ensureConversation = originalEnsureConversation;
      }
    } finally {
      aiApi.streamConversation = originalStream;
      aiApi.chat = originalChat;
    }
  } finally {
    await server.close();
  }
});

test('failed pre-chat history lookup stops before sending a turn with an unknown reply cursor', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    const store = useAiStore();
    const originalGetMessages = aiApi.getMessages;
    const originalChat = aiApi.chat;
    const originalWait = globalThis.setTimeout;
    const originalEnsureConversation = store.ensureConversation;
    const originalWaitAssistantByPolling = store.waitAssistantByPolling;
    let reads = 0;
    let sends = 0;
    let polls = 0;
    aiApi.getMessages = async () => {
      reads += 1;
      throw new Error('history unavailable');
    };
    aiApi.chat = async () => { sends += 1; };
    store.ensureConversation = async () => 7;
    store.waitAssistantByPolling = async () => {
      polls += 1;
    };
    globalThis.setTimeout = (callback) => {
      queueMicrotask(callback);
      return 0;
    };
    try {
      await assert.rejects(store.askBackend('本轮的新问题', null, 99), /history unavailable/);
      assert.equal(reads, 1);
      assert.equal(sends, 0);
      assert.equal(polls, 0);
    } finally {
      aiApi.getMessages = originalGetMessages;
      aiApi.chat = originalChat;
      store.ensureConversation = originalEnsureConversation;
      store.waitAssistantByPolling = originalWaitAssistantByPolling;
      globalThis.setTimeout = originalWait;
    }
  } finally {
    await server.close();
  }
});

test('a lost chat response polls the accepted turn without submitting a duplicate message', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    const store = useAiStore();
    const originalEnsureConversation = store.ensureConversation;
    const originalStream = aiApi.streamConversation;
    const originalChat = aiApi.chat;
    const originalGetMessages = aiApi.getMessages;
    const originalSetTimeout = globalThis.setTimeout;
    let chatSubmissions = 0;
    let historyReads = 0;
    store.ensureConversation = async () => 19;
    store.updateMessageContent = () => {};
    aiApi.streamConversation = async (_id, options) => {
      options.onOpen();
      await new Promise((resolve) => options.signal.addEventListener('abort', resolve, { once: true }));
    };
    aiApi.chat = async () => {
      chatSubmissions += 1;
      throw new Error('response lost after server accepted the message');
    };
    aiApi.getMessages = async () => {
      historyReads += 1;
      return historyReads === 1
        ? []
        : [{ id: 21, role: 'assistant', content: '已接收的回答' }];
    };
    globalThis.setTimeout = (callback) => {
      queueMicrotask(callback);
      return 0;
    };
    try {
      const reply = await store.askBackend('请推荐一家店', null, 20);

      assert.equal(reply.content, '已接收的回答');
      assert.equal(chatSubmissions, 1);
      assert.equal(historyReads, 2);
    } finally {
      store.ensureConversation = originalEnsureConversation;
      aiApi.streamConversation = originalStream;
      aiApi.chat = originalChat;
      aiApi.getMessages = originalGetMessages;
      globalThis.setTimeout = originalSetTimeout;
    }
  } finally {
    await server.close();
  }
});

test('polling timeout distinguishes a pending task from an unavailable status query', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    const store = useAiStore();
    const originalGetMessages = aiApi.getMessages;
    const originalSetTimeout = globalThis.setTimeout;
    store.updateMessageContent = () => {};
    globalThis.setTimeout = (callback) => {
      queueMicrotask(callback);
      return 0;
    };
    try {
      aiApi.getMessages = async () => [];
      await assert.rejects(
        store.waitAssistantByPolling({
          conversationId: 5, prompt: '问题', lastAssistantId: 0, loadingId: 1,
          shouldSend: false, pollOnly: true
        }),
        /AI 仍在后台处理/
      );

      aiApi.getMessages = async () => { throw new Error('connection lost'); };
      await assert.rejects(
        store.waitAssistantByPolling({
          conversationId: 5, prompt: '问题', lastAssistantId: 0, loadingId: 1,
          shouldSend: false, pollOnly: true
        }),
        (error) => /暂时无法查询 AI 回复状态/.test(error.message)
          && !error.message.includes('仍在后台处理')
      );
    } finally {
      aiApi.getMessages = originalGetMessages;
      globalThis.setTimeout = originalSetTimeout;
    }
  } finally {
    await server.close();
  }
});

test('message polling stops immediately when the session is unauthorized', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    const store = useAiStore();
    const originalGetMessages = aiApi.getMessages;
    let reads = 0;
    const unauthorized = new Error('登录状态已失效');
    unauthorized.response = { status: 401 };
    aiApi.getMessages = async () => {
      reads += 1;
      throw unauthorized;
    };
    try {
      await assert.rejects(
        store.waitAssistantByPolling({
          conversationId: 5, prompt: '问题', lastAssistantId: 0, loadingId: 1,
          shouldSend: false, pollOnly: true
        }),
        (error) => error === unauthorized
      );
      assert.equal(reads, 1);
    } finally {
      aiApi.getMessages = originalGetMessages;
    }
  } finally {
    await server.close();
  }
});

test('stream progress updates do not persist the full conversation for every chunk', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    const writes = [];
    globalThis.localStorage = {
      getItem: () => null,
      setItem: (...args) => writes.push(args)
    };
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const store = useAiStore();
    store.history = [{ id: 1, role: 'assistant', content: '正在思考中...' }];

    store.updateMessageContent(1, '部分流式内容', false);

    assert.equal(store.history[0].content, '部分流式内容');
    assert.equal(writes.length, 0);

    store.replaceLoadingMessage(1, { id: 2, role: 'assistant', content: '最终回答' });

    assert.equal(writes.length, 1);
    assert.match(writes[0][1], /最终回答/);
  } finally {
    await server.close();
  }
});

test('clearing the chat window is ignored while an AI reply is pending', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const store = useAiStore();
    store.pending = true;
    store.conversationId = 42;
    store.history = [
      { id: 1, role: 'user', content: '正在处理的问题' },
      { id: 2, role: 'assistant', content: '正在思考中，请稍候...' }
    ];

    store.clearHistory();

    assert.equal(store.conversationId, 42);
    assert.equal(store.history.length, 2);
    assert.equal(store.history[1].content, '正在思考中，请稍候...');
  } finally {
    await server.close();
  }
});

test('the latest history conversation selection wins when requests return out of order', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const { useUserStore } = await server.ssrLoadModule('/src/store/user.js');
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    const store = useAiStore();
    const userStore = useUserStore();
    userStore.token = 'test-token';
    userStore.profile = { id: 7 };
    const originalGetMessages = aiApi.getMessages;
    const requests = new Map();
    aiApi.getMessages = (id) => new Promise((resolve) => requests.set(id, resolve));
    try {
      const firstSelection = store.switchConversation(10, { refreshList: false });
      const latestSelection = store.switchConversation(20, { refreshList: false });
      requests.get(20)([{ id: 20, role: 'user', content: '最近选择' }]);
      await latestSelection;
      requests.get(10)([{ id: 10, role: 'user', content: '较早选择' }]);
      await firstSelection;

      assert.equal(store.conversationId, 20);
      assert.equal(store.history[0].content, '最近选择');
    } finally {
      aiApi.getMessages = originalGetMessages;
    }
  } finally {
    await server.close();
  }
});

test('a failed conversation list refresh preserves the current session and cached list', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const { useUserStore } = await server.ssrLoadModule('/src/store/user.js');
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    const store = useAiStore();
    const userStore = useUserStore();
    userStore.token = 'test-token';
    userStore.profile = { id: 7 };
    store.conversationId = 10;
    store.conversations = [{ id: 10, title: '已缓存会话' }];
    const originalListConversations = aiApi.listConversations;
    aiApi.listConversations = async () => { throw new Error('network unavailable'); };
    try {
      const conversations = await store.loadConversations();
      assert.equal(store.conversationId, 10);
      assert.deepEqual(store.conversations, [{ id: 10, title: '已缓存会话' }]);
      assert.deepEqual(conversations, [{ id: 10, title: '已缓存会话' }]);
      assert.equal(store.conversationListError, '会话列表加载失败，请检查网络后重试');
    } finally {
      aiApi.listConversations = originalListConversations;
    }
  } finally {
    await server.close();
  }
});

test('switching accounts never copies the previous AI conversation into the new account storage', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  const storage = new Map([
    ['hm-dianping-token', 'account-a-token'],
    ['hm-dianping-ai-store:account-a-token', JSON.stringify({
      conversationId: 88,
      history: [{ id: 1, role: 'user', content: 'account A private message' }],
      mode: 'backend'
    })],
    ['hm-dianping-ai-store:account-b-token', JSON.stringify({
      conversationId: 99,
      history: [{ id: 2, role: 'user', content: 'account B own history' }],
      mode: 'backend'
    })]
  ]);
  const originalLocalStorage = globalThis.localStorage;
  globalThis.localStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: (key) => storage.delete(key)
  };
  try {
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const { useUserStore } = await server.ssrLoadModule('/src/store/user.js');
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    const aiStore = useAiStore();
    const userStore = useUserStore();
    userStore.token = 'account-a-token';
    userStore.profile = { id: 1 };
    const originalListConversations = aiApi.listConversations;
    aiApi.listConversations = async () => [];
    try {
      storage.set('hm-dianping-token', 'account-b-token');
      userStore.token = 'account-b-token';
      userStore.profile = { id: 2 };

      await aiStore.loadConversations();

      const accountBState = JSON.parse(storage.get('hm-dianping-ai-store:account-b-token'));
      assert.equal(aiStore.conversationId, null);
      assert.equal(aiStore.history.some((message) => message.content === 'account B own history'), true);
      assert.equal(aiStore.history.some((message) => message.content === 'account A private message'), false);
      assert.equal(accountBState.history.some((message) => message.content === 'account B own history'), true);
      assert.equal(accountBState.history.some((message) => message.content === 'account A private message'), false);
    } finally {
      aiApi.listConversations = originalListConversations;
    }
  } finally {
    globalThis.localStorage = originalLocalStorage;
    await server.close();
  }
});

test('a late conversation list from the previous account cannot replace the new account list', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  const storage = new Map([['hm-dianping-token', 'account-a-token']]);
  const originalLocalStorage = globalThis.localStorage;
  globalThis.localStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: (key) => storage.delete(key)
  };
  try {
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const { useUserStore } = await server.ssrLoadModule('/src/store/user.js');
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    const aiStore = useAiStore();
    const userStore = useUserStore();
    userStore.token = 'account-a-token';
    userStore.profile = { id: 1 };
    const originalListConversations = aiApi.listConversations;
    let resolveAccountA;
    let callCount = 0;
    aiApi.listConversations = () => {
      callCount += 1;
      if (callCount === 1) return new Promise((resolve) => { resolveAccountA = resolve; });
      return Promise.resolve([{ id: 22, title: 'account B conversation' }]);
    };
    try {
      const accountARequest = aiStore.loadConversations();
      await new Promise((resolve) => setImmediate(resolve));
      storage.set('hm-dianping-token', 'account-b-token');
      userStore.token = 'account-b-token';
      userStore.profile = { id: 2 };
      await aiStore.loadConversations();
      resolveAccountA([{ id: 11, title: 'account A private conversation' }]);
      await accountARequest;

      assert.deepEqual(aiStore.conversations, [{
        id: 22, title: 'account B conversation', scene: 'MULTI_AGENT', createdTime: '', updatedTime: ''
      }]);
      assert.equal(storage.get('hm-dianping-ai-store:account-b-token').includes('account A private conversation'), false);
    } finally {
      aiApi.listConversations = originalListConversations;
    }
  } finally {
    globalThis.localStorage = originalLocalStorage;
    await server.close();
  }
});

test('a failed history switch preserves the current conversation and exposes a retry target', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const { useUserStore } = await server.ssrLoadModule('/src/store/user.js');
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    const store = useAiStore();
    const userStore = useUserStore();
    userStore.token = 'test-token';
    userStore.profile = { id: 7 };
    store.conversationId = 10;
    store.history = [{ id: 1, role: 'user', content: '当前会话内容' }];
    const originalGetMessages = aiApi.getMessages;
    aiApi.getMessages = async () => { throw new Error('network unavailable'); };
    try {
      await store.switchConversation(20, { refreshList: false });

      assert.equal(store.conversationId, 10);
      assert.equal(store.history[0].content, '当前会话内容');
      assert.equal(store.failedConversationId, 20);
      assert.equal(store.conversationSwitchError, 'network unavailable');
    } finally {
      aiApi.getMessages = originalGetMessages;
    }
  } finally {
    await server.close();
  }
});

test('a failed new conversation keeps the current chat and exposes a retryable error', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });
  try {
    globalThis.localStorage = { getItem: () => null, setItem: () => {} };
    setActivePinia(createPinia());
    const { useAiStore } = await server.ssrLoadModule('/src/store/ai.js');
    const { useUserStore } = await server.ssrLoadModule('/src/store/user.js');
    const { aiApi } = await server.ssrLoadModule('/src/api/modules/ai.js');
    const store = useAiStore();
    const userStore = useUserStore();
    userStore.token = 'test-token';
    userStore.profile = { id: 7 };
    store.conversationId = 10;
    store.history = [{ id: 1, role: 'user', content: '当前会话内容' }];
    const originalCreateConversation = aiApi.createConversation;
    aiApi.createConversation = async () => { throw new Error('network unavailable'); };
    try {
      await store.startNewConversation();

      assert.equal(store.conversationId, 10);
      assert.equal(store.history[0].content, '当前会话内容');
      assert.equal(store.conversationCreateError, 'network unavailable');
      assert.equal(store.conversationCreating, false);
    } finally {
      aiApi.createConversation = originalCreateConversation;
    }
  } finally {
    await server.close();
  }
});
