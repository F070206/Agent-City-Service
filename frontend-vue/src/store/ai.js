import { defineStore } from 'pinia';
import { aiApi } from '@/api/modules/ai';
import { buildAiResponse } from '@/mock/handlers';
import { useUserStore } from '@/store/user';

const AI_STORAGE_KEY = 'hm-dianping-ai-store';

function resolveAiStorageKey(token = localStorage.getItem('hm-dianping-token')) {
  return `${AI_STORAGE_KEY}:${token || 'guest'}`;
}
const DEFAULT_WELCOME_CONTENT = '你好呀，我是 AI 美食助手。你可以让我推荐附近美食、推荐菜品，或者解答美食问题。';
const AI_STREAM_WAIT_MS = 75000;
const AI_POLL_INTERVAL_MS = 1500;
const AI_POLL_ATTEMPTS = 50;

function createWelcomeMessage() {
  return {
    id: Date.now(),
    role: 'assistant',
    content: DEFAULT_WELCOME_CONTENT
  };
}

function loadPersistedState(token) {
  try {
    const raw = localStorage.getItem(resolveAiStorageKey(token));
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function wait(duration) {
  return new Promise((resolve) => setTimeout(resolve, duration));
}

function withTimeout(promise, duration, message) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(message)), duration);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

function buildPrompt(message, currentShop) {
  if (!currentShop) {
    return message;
  }
  return [
    `当前店铺ID=${currentShop.id}`,
    `店铺名称：${currentShop.name}`,
    `店铺地址：${currentShop.address}`,
    `人均：${currentShop.price}`,
    `用户问题：${message}`
  ].join('\n');
}

function toRecommendCards(items = []) {
  return items.map((item) => ({
    name: item.shopName || `店铺 ${item.shopId}`,
    score: item.rating || item.score || '暂无评分',
    price: item.avgPrice || 0,
    reason: item.reason || [item.area, item.address, item.openHours].filter(Boolean).join(' ｜ ')
  }));
}

function buildInsightReply(shop, insight) {
  const lines = [
    `${shop?.name || '这家店'}推荐概览：`,
    insight?.summary || 'AI 已返回分析结果。'
  ];

  if (insight?.signatureDishes?.length) {
    lines.push(`招牌推荐：${insight.signatureDishes.join('、')}`);
  }
  if (insight?.environmentSummary) {
    lines.push(`环境：${insight.environmentSummary}`);
  }
  if (insight?.serviceSummary) {
    lines.push(`服务：${insight.serviceSummary}`);
  }
  if (insight?.suitableCrowd) {
    lines.push(`适合人群：${insight.suitableCrowd}`);
  }
  if (insight?.riskTips?.length) {
    lines.push(`提醒：${insight.riskTips.join('；')}`);
  }

  return lines.filter(Boolean).join('\n\n');
}

function extractErrorMessage(error) {
  return error?.message || 'AI 服务暂时不可用，请稍后再试';
}

function shouldFallbackToMock(error) {
  const message = extractErrorMessage(error).toLowerCase();
  return [
    'network error',
    'failed to fetch',
    'timeout',
    '建立 ai 流式连接失败',
    'waiting ai',
    'stream',
    '服务器异常'
  ].some((keyword) => message.includes(keyword));
}

function normalizeConversation(item) {
  return {
    id: Number(item?.id) || Date.now(),
    title: item?.title || '新会话',
    scene: item?.scene || 'MULTI_AGENT',
    createdTime: item?.createdTime || '',
    updatedTime: item?.updatedTime || item?.createdTime || ''
  };
}

function normalizeMessages(messages = []) {
  const list = messages
    .filter((item) => item && item.role !== 'tool')
    .map((item) => ({
      id: Number(item.id) || Date.now() + Math.random(),
      role: item.role === 'assistant' ? 'assistant' : 'user',
      content: item.content || '',
      createdTime: item.createdTime
    }));
  return list.length ? list : [createWelcomeMessage()];
}

/**
 * AI 助手状态：
 * 1. 优先走后端 AI 聊天接口
 * 2. 快捷入口优先命中后端专用推荐/分析接口
 * 3. 对话接口优先使用 SSE 流式接收，失败后降级轮询
 * 4. 支持新建会话、查看历史会话、切换历史消息
 * 5. 后端不可用时降级到本地 mock，并持久化当前聊天窗口
 */
export const useAiStore = defineStore('ai', {
  state: () => {
    const sessionToken = localStorage.getItem('hm-dianping-token') || '';
    const persisted = loadPersistedState(sessionToken);
    return {
      sessionToken,
      sessionVersion: 0,
      drawerVisible: false,
      currentShopContext: null,
      pending: false,
      conversationSwitchRequestId: 0,
      conversationSwitchError: '',
      failedConversationId: null,
      conversationCreateError: '',
      conversationCreating: false,
      conversationListError: '',
      conversationId: persisted?.conversationId || null,
      conversations: [],
      history: persisted?.history?.length ? persisted.history : [createWelcomeMessage()],
      mode: persisted?.mode || 'backend'
    };
  },
  getters: {
    currentConversationTitle(state) {
      return state.conversations.find((item) => Number(item.id) === Number(state.conversationId))?.title || '当前会话';
    }
  },
  actions: {
    syncSessionScope() {
      const token = localStorage.getItem('hm-dianping-token') || '';
      if (token === this.sessionToken) return false;

      this.sessionToken = token;
      this.sessionVersion += 1;
      this.conversationSwitchRequestId += 1;
      const persisted = loadPersistedState(token);
      this.conversationId = persisted?.conversationId || null;
      this.history = Array.isArray(persisted?.history) && persisted.history.length
        ? persisted.history
        : [createWelcomeMessage()];
      this.mode = persisted?.mode || 'backend';
      this.conversations = [];
      this.pending = false;
      this.conversationCreating = false;
      this.conversationSwitchError = '';
      this.failedConversationId = null;
      this.conversationCreateError = '';
      this.conversationListError = '';
      return true;
    },
    isSessionCurrent(version) {
      return version === this.sessionVersion;
    },
    syncState() {
      this.syncSessionScope();
      localStorage.setItem(
        resolveAiStorageKey(),
        JSON.stringify({
          conversationId: this.conversationId,
          history: this.history,
          mode: this.mode
        })
      );
    },
    openDrawer() {
      this.syncSessionScope();
      this.drawerVisible = true;
      void this.bootstrapConversations();
    },
    closeDrawer() {
      this.drawerVisible = false;
    },
    setCurrentShop(shop) {
      this.currentShopContext = shop || null;
    },
    pushMessage(message) {
      this.history.push(message);
      this.syncState();
    },
    updateMessageContent(messageId, content, persist = true) {
      this.history = this.history.map((item) => (item.id === messageId ? { ...item, content } : item));
      if (persist) {
        this.syncState();
      }
    },
    replaceLoadingMessage(messageId, message) {
      this.history = this.history.filter((item) => item.id !== messageId);
      this.history.push(message);
      this.syncState();
    },
    clearHistory() {
      if (this.pending) {
        return;
      }
      this.conversationSwitchRequestId += 1;
      this.conversationId = null;
      this.history = [{
        id: Date.now(),
        role: 'assistant',
        content: '当前聊天窗口已重置，你可以开始一个新问题。历史会话仍可在会话列表中查看。'
      }];
      this.syncState();
    },
    ensureLoggedIn() {
      this.syncSessionScope();
      const userStore = useUserStore();
      if (userStore.isLoggedIn) {
        return true;
      }
      userStore.openLoginDialog();
      this.pushMessage({
        id: Date.now(),
        role: 'assistant',
        content: '请先登录后再使用 AI 助手，这样我才能调用后端聊天接口为你生成更智能的回复。'
      });
      return false;
    },
    async bootstrapConversations() {
      this.syncSessionScope();
      const userStore = useUserStore();
      if (!userStore.isLoggedIn) {
        this.conversations = [];
        this.conversationListError = '';
        return;
      }
      await this.loadConversations();
      if (this.conversationId) {
        await this.switchConversation(this.conversationId, { refreshList: false, silent: true });
      }
    },
    async loadConversations() {
      this.syncSessionScope();
      const userStore = useUserStore();
      if (!userStore.isLoggedIn) {
        this.conversations = [];
        this.conversationListError = '';
        return [];
      }
      const sessionVersion = this.sessionVersion;
      let list;
      try {
        list = await aiApi.listConversations();
      } catch {
        if (!this.isSessionCurrent(sessionVersion)) return this.conversations;
        this.conversationListError = '会话列表加载失败，请检查网络后重试';
        return this.conversations;
      }
      if (!this.isSessionCurrent(sessionVersion)) return this.conversations;
      this.conversationListError = '';
      this.conversations = (list || []).map(normalizeConversation);
      if (this.conversationId && !this.conversations.some((item) => Number(item.id) === Number(this.conversationId))) {
        this.conversationId = null;
      }
      this.syncState();
      return this.conversations;
    },
    async startNewConversation() {
      this.syncSessionScope();
      const userStore = useUserStore();
      if (this.pending || this.conversationCreating) {
        return;
      }
      if (!userStore.isLoggedIn) {
        userStore.openLoginDialog();
        return;
      }
      this.conversationSwitchRequestId += 1;
      this.conversationCreating = true;
      this.conversationCreateError = '';
      const sessionVersion = this.sessionVersion;
      try {
        const conversation = normalizeConversation(await aiApi.createConversation());
        if (!this.isSessionCurrent(sessionVersion)) return;
        this.conversationId = conversation.id;
        this.history = [createWelcomeMessage()];
        this.mode = 'backend';
        this.conversations = [conversation, ...this.conversations.filter((item) => Number(item.id) !== Number(conversation.id))];
        this.conversationSwitchError = '';
        this.failedConversationId = null;
        this.syncState();
      } catch (error) {
        if (!this.isSessionCurrent(sessionVersion)) return;
        this.conversationCreateError = error?.message || '新建会话失败，请重试';
      } finally {
        if (this.isSessionCurrent(sessionVersion)) this.conversationCreating = false;
      }
    },
    async switchConversation(conversationId, { refreshList = true, silent = false } = {}) {
      this.syncSessionScope();
      const userStore = useUserStore();
      if (this.pending) {
        return;
      }
      if (!userStore.isLoggedIn) {
        if (!silent) {
          userStore.openLoginDialog();
        }
        return;
      }
      const requestId = ++this.conversationSwitchRequestId;
      const sessionVersion = this.sessionVersion;
      this.conversationSwitchError = '';
      this.failedConversationId = null;
      let messages;
      try {
        messages = await aiApi.getMessages(conversationId);
      } catch (error) {
        if (requestId === this.conversationSwitchRequestId && this.isSessionCurrent(sessionVersion)) {
          this.conversationSwitchError = error?.message || '会话内容加载失败，请重试';
          this.failedConversationId = Number(conversationId);
        }
        return;
      }
      if (requestId !== this.conversationSwitchRequestId || !this.isSessionCurrent(sessionVersion)) {
        return;
      }
      this.conversationId = Number(conversationId);
      this.history = normalizeMessages(messages || []);
      this.mode = 'backend';
      if (refreshList) {
        await this.loadConversations();
      }
      this.syncState();
    },
    async ensureConversation() {
      this.syncSessionScope();
      if (this.conversationId) {
        return this.conversationId;
      }
      const sessionVersion = this.sessionVersion;
      const conversation = normalizeConversation(await aiApi.createConversation());
      if (!this.isSessionCurrent(sessionVersion)) throw new Error('登录状态已切换，请重试');
      this.conversationId = conversation.id;
      this.conversations = [conversation, ...this.conversations.filter((item) => Number(item.id) !== Number(conversation.id))];
      this.syncState();
      return this.conversationId;
    },
    async refreshConversationList() {
      await this.loadConversations();
    },
    async sendQuickScene(scene) {
      const action = scene?.trim();
      if (!action) {
        return;
      }
      if (action === '推荐附近美食') {
        return this.sendRecommend();
      }
      if (action === '推荐商家菜品') {
        return this.sendShopInsight(this.currentShopContext);
      }
      if (action === '预订建议') {
        const prompt = this.currentShopContext
          ? `如果我准备去 ${this.currentShopContext.name} 用餐，请给我一份预约时间、人数和点单建议。`
          : '请给我一份通用的餐厅预订建议。';
        return this.sendMessage(prompt, { currentShop: this.currentShopContext });
      }
      if (action === '解答美食问题') {
        return this.sendMessage('火锅蘸料怎么调更好吃？');
      }
      return this.sendMessage(action, { currentShop: this.currentShopContext });
    },
    async sendRecommend(query) {
      if (!this.ensureLoggedIn() || this.pending) {
        return;
      }

      const sessionVersion = this.sessionVersion;
      const userStore = useUserStore();
      const finalQuery = query?.trim() || `${userStore.city}附近美食推荐`;
      const loadingId = Date.now() + 1;
      this.pending = true;
      this.pushMessage({ id: Date.now(), role: 'user', content: finalQuery });
      this.pushMessage({ id: loadingId, role: 'assistant', content: '正在为你检索附近值得去的店铺...' });

      try {
        await wait(1200);
        if (!this.isSessionCurrent(sessionVersion)) return;
        const result = await aiApi.recommend({ query: finalQuery, topK: 3 });
        if (!this.isSessionCurrent(sessionVersion)) return;
        this.mode = 'backend';
        const content = [result?.summary, result?.missingInfoNotice].filter(Boolean).join('\n\n') || 'AI 已返回推荐结果。';
        this.replaceLoadingMessage(loadingId, {
          id: Date.now() + 2,
          role: 'assistant',
          content,
          cards: toRecommendCards(result?.items || [])
        });
      } catch (error) {
        if (!this.isSessionCurrent(sessionVersion)) return;
        if (!shouldFallbackToMock(error)) {
          this.mode = 'backend';
          const recoverable = String(error?.message || '').includes('仍在后台处理');
          this.replaceLoadingMessage(loadingId, {
            id: Date.now() + 2,
            role: 'assistant',
            content: recoverable
              ? `${extractErrorMessage(error)}。稍后可重新打开此会话查看最新回复。`
              : extractErrorMessage(error)
          });
        } else {
          const fallback = buildAiResponse(finalQuery, this.currentShopContext);
          this.mode = 'mock';
          this.replaceLoadingMessage(loadingId, {
            id: Date.now() + 2,
            role: 'assistant',
            content: `${fallback.content}\n\n（提示：推荐接口暂不可用，已切换到降级回答）`,
            cards: fallback.cards || []
          });
        }
      } finally {
        if (this.isSessionCurrent(sessionVersion)) {
          this.pending = false;
          this.syncState();
        }
      }
    },
    async sendShopInsight(shop = this.currentShopContext) {
      if (!shop) {
        return this.sendMessage('这家店什么菜好吃');
      }
      if (!this.ensureLoggedIn() || this.pending) {
        return;
      }

      const sessionVersion = this.sessionVersion;
      const loadingId = Date.now() + 1;
      this.pending = true;
      this.setCurrentShop(shop);
      this.pushMessage({ id: Date.now(), role: 'user', content: `这家店什么菜好吃（${shop.name}）` });
      this.pushMessage({ id: loadingId, role: 'assistant', content: '正在结合当前店铺信息生成推荐...' });

      try {
        await wait(1200);
        if (!this.isSessionCurrent(sessionVersion)) return;
        const insight = await aiApi.analyzeShop(shop.id);
        if (!this.isSessionCurrent(sessionVersion)) return;
        this.mode = 'backend';
        this.replaceLoadingMessage(loadingId, {
          id: Date.now() + 2,
          role: 'assistant',
          content: buildInsightReply(shop, insight)
        });
      } catch (error) {
        if (!this.isSessionCurrent(sessionVersion)) return;
        if (!shouldFallbackToMock(error)) {
          this.mode = 'backend';
          this.replaceLoadingMessage(loadingId, {
            id: Date.now() + 2,
            role: 'assistant',
            content: extractErrorMessage(error)
          });
        } else {
          const fallback = buildAiResponse('这家店什么菜好吃', shop);
          this.mode = 'mock';
          this.replaceLoadingMessage(loadingId, {
            id: Date.now() + 2,
            role: 'assistant',
            content: `${fallback.content}\n\n（提示：店铺分析接口暂不可用，已切换到降级回答）`,
            cards: fallback.cards || []
          });
        }
      } finally {
        if (this.isSessionCurrent(sessionVersion)) {
          this.pending = false;
          this.syncState();
        }
      }
    },
    async sendMessage(content, extra = {}) {
      const message = content?.trim();
      if (!message || this.pending) {
        return;
      }
      if (!this.ensureLoggedIn()) {
        return;
      }

      const sessionVersion = this.sessionVersion;
      this.pending = true;
      this.pushMessage({ id: Date.now(), role: 'user', content: message });

      const loadingId = Date.now() + 1;
      this.pushMessage({ id: loadingId, role: 'assistant', content: '正在思考中，请稍候...' });

      try {
        await wait(1200);
        if (!this.isSessionCurrent(sessionVersion)) return;
        const reply = await this.askBackend(message, extra.currentShop || this.currentShopContext, loadingId, sessionVersion);
        if (!this.isSessionCurrent(sessionVersion)) return;
        this.replaceLoadingMessage(loadingId, reply);
        await this.refreshConversationList();
      } catch (error) {
        if (!this.isSessionCurrent(sessionVersion)) return;
        if (!shouldFallbackToMock(error)) {
          this.mode = 'backend';
          this.replaceLoadingMessage(loadingId, {
            id: Date.now() + 2,
            role: 'assistant',
            content: extractErrorMessage(error)
          });
        } else {
          this.mode = 'mock';
          const fallback = buildAiResponse(message, extra.currentShop || this.currentShopContext);
          this.replaceLoadingMessage(loadingId, {
            id: Date.now() + 2,
            role: 'assistant',
            content: `${fallback.content}\n\n（提示：后端 AI 当前不可用，已切换到降级回答）`,
            cards: fallback.cards || []
          });
        }
      } finally {
        if (this.isSessionCurrent(sessionVersion)) {
          this.pending = false;
          this.syncState();
        }
      }
    },
    async askBackend(message, currentShop, loadingId, sessionVersion = this.sessionVersion) {
      const conversationId = await this.ensureConversation();
      if (!this.isSessionCurrent(sessionVersion)) throw new Error('登录状态已切换，请重试');
      // Polling uses this cursor to distinguish the current reply from previous turns.
      // If history cannot be read, do not send a request with a zero cursor and risk
      // returning an old assistant message as the answer to this turn.
      const beforeMessages = await aiApi.getMessages(conversationId);
      if (!this.isSessionCurrent(sessionVersion)) throw new Error('登录状态已切换，请重试');
      const lastAssistantId = Math.max(0, ...beforeMessages.filter((item) => item.role === 'assistant').map((item) => Number(item.id) || 0));
      const prompt = buildPrompt(message, currentShop);

      try {
        const streamedReply = await this.askBackendByStream({ conversationId, prompt, lastAssistantId, loadingId, sessionVersion });
        this.mode = 'backend';
        return streamedReply;
      } catch (error) {
        if (!this.isSessionCurrent(sessionVersion)) throw error;
        if (error?.chatRejected === true) throw error;
        const pollingReply = await this.waitAssistantByPolling({
          conversationId,
          prompt,
          lastAssistantId,
          loadingId,
          shouldSend: error?.chatSent !== true,
          pollOnly: error?.chatSent === true,
          sessionVersion
        });
        this.mode = 'backend';
        return pollingReply;
      }
    },
    async askBackendByStream({ conversationId, prompt, lastAssistantId, loadingId, sessionVersion = this.sessionVersion }) {
      const abortController = new AbortController();
      let chatSent = false;
      let chatRequestAttempted = false;
      let markStreamReady;
      let failStreamReady;
      const streamReady = new Promise((resolve, reject) => {
        markStreamReady = resolve;
        failStreamReady = reject;
      });

      try {
        const replyPromise = new Promise((resolve, reject) => {
          let resolved = false;
          let streamingStarted = false;
          let streamingBuffer = '';

          aiApi.streamConversation(conversationId, {
            signal: abortController.signal,
            onOpen: markStreamReady,
            onEvent: (event) => {
              if (!this.isSessionCurrent(sessionVersion)) return;
              const payload = event?.data?.payload ?? event?.data ?? {};
              if (event.event === 'status') {
                if (!streamingStarted) {
                  const detail = payload.detail || payload.status || 'AI 正在处理中...';
                  this.updateMessageContent(loadingId, `正在思考中，请稍候...\n${detail}`, false);
                }
                return;
              }

              if (event.event === 'answer_delta') {
                streamingStarted = true;
                streamingBuffer += payload.delta || '';
                this.updateMessageContent(loadingId, streamingBuffer || 'AI 正在输出中...', false);
                return;
              }

              if (event.event === 'answer_done' && !resolved) {
                resolved = true;
                abortController.abort();
                resolve({
                  id: Number(payload.messageId) || Date.now() + 2,
                  role: 'assistant',
                  content: payload.content || streamingBuffer || 'AI 已返回结果，但内容为空。'
                });
                return;
              }

              if (event.event === 'message') {
                const assistantId = Number(payload.id) || 0;
                if (payload.role === 'assistant' && assistantId > lastAssistantId && !resolved && !streamingStarted) {
                  resolved = true;
                  abortController.abort();
                  resolve({
                    id: assistantId || Date.now() + 2,
                    role: 'assistant',
                    content: payload.content || 'AI 已返回结果，但内容为空。'
                  });
                }
                return;
              }

              if (event.event === 'error' && !resolved) {
                resolved = true;
                abortController.abort();
                reject(new Error(payload.detail || 'AI 流式处理失败'));
              }
            }
          }).catch((error) => {
            if (!resolved && error?.name !== 'AbortError') {
              resolved = true;
              failStreamReady(error);
              reject(error);
            }
          });
        });

        // The stream must be registered before a fast reply can be published.
        replyPromise.catch(() => {});
        await withTimeout(streamReady, 8000, '建立 AI 流式连接超时');
        if (!this.isSessionCurrent(sessionVersion)) throw new Error('登录状态已切换，请重试');
        chatRequestAttempted = true;
        await aiApi.chat({ conversationId, content: prompt });
        chatSent = true;

        return await withTimeout(replyPromise, AI_STREAM_WAIT_MS, '等待 AI 流式回复超时');
      } catch (error) {
        if (!this.isSessionCurrent(sessionVersion)) throw error;
        error.chatSent = chatSent || chatRequestAttempted;
        const responseStatus = Number(error?.response?.status);
        error.chatRejected = chatRequestAttempted
          && (error?.isBackendRejection === true || (responseStatus >= 400 && responseStatus < 500));
        throw error;
      } finally {
        abortController.abort();
      }
    },
    async waitAssistantByPolling({ conversationId, prompt, lastAssistantId, loadingId, shouldSend = true, pollOnly = false, sessionVersion = this.sessionVersion }) {
      let requestSubmitted = !shouldSend;
      if (shouldSend) {
        await aiApi.chat({ conversationId, content: prompt });
        requestSubmitted = true;
      }

      const pollAttempts = pollOnly ? Math.ceil(AI_STREAM_WAIT_MS / AI_POLL_INTERVAL_MS) : AI_POLL_ATTEMPTS;
      let lastPollError = null;
      for (let attempt = 0; attempt < pollAttempts; attempt += 1) {
        if (!this.isSessionCurrent(sessionVersion)) throw new Error('登录状态已切换，请重试');
        this.updateMessageContent(loadingId, `正在思考中，请稍候...\n已切换到轮询模式（第 ${attempt + 1} 次检查）`, false);
        await wait(AI_POLL_INTERVAL_MS);
        if (!this.isSessionCurrent(sessionVersion)) throw new Error('登录状态已切换，请重试');
        let messages;
        try {
          messages = await aiApi.getMessages(conversationId);
          lastPollError = null;
        } catch (error) {
          if ([401, 403].includes(Number(error?.response?.status))) {
            throw error;
          }
          lastPollError = error;
          continue;
        }
        const latestAssistant = [...messages]
          .reverse()
          .find((item) => item.role === 'assistant' && (Number(item.id) || 0) > lastAssistantId);

        if (latestAssistant) {
          return {
            id: Number(latestAssistant.id) || Date.now() + 2,
            role: 'assistant',
            content: latestAssistant.content || 'AI 已返回结果，但内容为空。'
          };
        }
      }

      if (lastPollError) {
        throw new Error('暂时无法查询 AI 回复状态，请稍后重试或重新打开会话查看');
      }
      if (requestSubmitted) {
        throw new Error('AI 仍在后台处理，请稍后重新打开会话查看最新回复');
      }
      throw new Error('等待 AI 回复超时');
    }
  }
});
