import { defineStore } from 'pinia';
import { userApi } from '@/api/modules/user';

const DEFAULT_AVATAR = 'https://dummyimage.com/120x120/ff6700/ffffff&text=U';
const TOKEN_KEY = 'hm-dianping-token';
const FAVORITES_KEY = 'hm-dianping-favorites';
const ORDERS_KEY = 'hm-dianping-orders';
const ADDRESSES_KEY = 'hm-dianping-addresses';

function readJson(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function writeJson(key, value) {
  localStorage.setItem(key, JSON.stringify(value));
}

function sortOrders(list) {
  return list
    .filter((item) => item && typeof item === 'object' && !Array.isArray(item))
    .sort((left, right) => new Date(right.updateTime || right.createdTime || 0) - new Date(left.updateTime || left.createdTime || 0));
}

function resolveScopedKey(baseKey, userId) {
  return `${baseKey}:${userId || 'guest'}`;
}

/**
 * 用户状态：
 * 1. 保存 token / 当前用户信息
 * 2. 维护登录弹窗开关
 * 3. 保存本地收藏、订单、地址等界面状态
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    city: '北京',
    token: localStorage.getItem(TOKEN_KEY) || '',
    profile: null,
    signCount: 0,
    loginDialogVisible: false,
    favoriteShopIds: [],
    orderHistory: [],
    addressList: [],
    scopedStateVersion: 0
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.token && state.profile?.id),
    displayAvatar: (state) => state.profile?.avatar || DEFAULT_AVATAR,
    displayName: (state) => state.profile?.nickname || '点击登录',
    isFavorite: (state) => (shopId) => state.favoriteShopIds.includes(Number(shopId))
  },
  actions: {
    async initAuth() {
      const token = this.token;
      if (!token) {
        this.resetLocalUserState();
        return;
      }
      try {
        await this.fetchMe(token);
        if (this.token !== token) return;
        this.loadUserScopedState();
        await this.fetchSignCount(token);
      } catch (error) {
        if (this.token !== token) return;
        if (error?.response?.status === 401) {
          this.clearAuth();
          return;
        }
        this.profile = null;
        this.resetLocalUserState();
        window.dispatchEvent(new CustomEvent('hm-auth-check-unavailable'));
      }
    },
    setCity(city) {
      this.city = city;
    },
    openLoginDialog() {
      this.loginDialogVisible = true;
    },
    closeLoginDialog() {
      this.loginDialogVisible = false;
    },
    async sendCode(phone) {
      return userApi.sendCode(phone);
    },
    async login(form) {
      const token = await userApi.login(form);
      this.token = token;
      localStorage.setItem(TOKEN_KEY, token);
      this.profile = null;
      this.signCount = 0;
      this.resetLocalUserState();
      try {
        const profile = await this.fetchMe(token);
        if (this.token !== token) return;
        if (!profile) {
          throw new Error('无法获取登录用户信息，请稍后重试');
        }
        this.loadUserScopedState();
        await this.fetchSignCount(token);
        if (this.token !== token) return;
        this.loginDialogVisible = false;
      } catch (error) {
        if (this.token !== token) return;
        try {
          await userApi.logout();
          if (this.token === token) this.clearAuth();
        } catch (logoutError) {
          if (this.token !== token) return;
          if (logoutError?.response?.status === 401) {
            this.clearAuth();
          } else {
            // Keep the token when revocation is uncertain so the session can be retried.
            this.token = token;
            localStorage.setItem(TOKEN_KEY, token);
          }
          this.profile = null;
          this.signCount = 0;
          this.resetLocalUserState();
        }
        throw error;
      }
    },
    async fetchMe(expectedToken = this.token) {
      const me = await userApi.getMe();
      if (this.token !== expectedToken) return null;
      this.profile = me
        ? {
            id: me.id,
            nickname: me.nickName,
            avatar: me.icon || DEFAULT_AVATAR
          }
        : null;
      if (!this.profile) {
        this.resetLocalUserState();
      }
      return this.profile;
    },
    async fetchSignCount(expectedToken = this.token) {
      const signCount = (await userApi.getSignCount().catch(() => 0)) || 0;
      if (this.token === expectedToken) this.signCount = signCount;
      return signCount;
    },
    async signToday() {
      const token = this.token;
      await userApi.sign();
      await this.fetchSignCount(token);
    },
    getScopedStorageKey(baseKey) {
      return resolveScopedKey(baseKey, this.profile?.id);
    },
    loadUserScopedState() {
      const favorites = readJson(this.getScopedStorageKey(FAVORITES_KEY), []);
      const orders = readJson(this.getScopedStorageKey(ORDERS_KEY), []);
      const addresses = readJson(this.getScopedStorageKey(ADDRESSES_KEY), []);
      this.favoriteShopIds = Array.isArray(favorites)
        ? favorites.map(Number).filter((id) => Number.isFinite(id) && id > 0)
        : [];
      this.orderHistory = sortOrders(Array.isArray(orders) ? orders : []);
      this.addressList = Array.isArray(addresses)
        ? addresses.filter((item) => item && typeof item === 'object' && !Array.isArray(item))
        : [];
      this.scopedStateVersion += 1;
    },
    resetLocalUserState() {
      this.favoriteShopIds = [];
      this.orderHistory = [];
      this.addressList = [];
    },
    persistFavorites() {
      writeJson(this.getScopedStorageKey(FAVORITES_KEY), this.favoriteShopIds);
    },
    persistOrders() {
      this.orderHistory = sortOrders(this.orderHistory);
      writeJson(this.getScopedStorageKey(ORDERS_KEY), this.orderHistory);
    },
    persistAddresses() {
      writeJson(this.getScopedStorageKey(ADDRESSES_KEY), this.addressList);
    },
    clearAuth() {
      this.token = '';
      this.profile = null;
      this.signCount = 0;
      this.resetLocalUserState();
      localStorage.removeItem(TOKEN_KEY);
    },
    async logout() {
      try {
        await userApi.logout();
      } catch (error) {
        if (error?.response?.status !== 401) {
          throw error;
        }
      }
      this.clearAuth();
    },
    toggleFavorite(shopId) {
      const targetId = Number(shopId);
      if (!Number.isFinite(targetId) || targetId <= 0) {
        throw new Error('店铺信息无效，无法更新收藏');
      }
      if (this.favoriteShopIds.includes(targetId)) {
        const nextFavorites = this.favoriteShopIds.filter((id) => id !== targetId);
        writeJson(this.getScopedStorageKey(FAVORITES_KEY), nextFavorites);
        this.favoriteShopIds = nextFavorites;
        return false;
      }
      const nextFavorites = [...this.favoriteShopIds, targetId];
      writeJson(this.getScopedStorageKey(FAVORITES_KEY), nextFavorites);
      this.favoriteShopIds = nextFavorites;
      return true;
    },
    clearFavorites() {
      writeJson(this.getScopedStorageKey(FAVORITES_KEY), []);
      this.favoriteShopIds = [];
    },
    recordOrder(order) {
      const orderId = String(order.orderId);
      const nextOrder = {
        createdTime: new Date().toISOString(),
        updateTime: new Date().toISOString(),
        ...order,
        orderId,
        status: Number(order.status ?? 1),
        creationPending: order.creationPending ?? false,
        localOnly: order.localOnly ?? true
      };
      const currentIndex = this.orderHistory.findIndex((item) => String(item.orderId) === orderId);
      if (currentIndex >= 0) {
        this.orderHistory.splice(currentIndex, 1, {
          ...this.orderHistory[currentIndex],
          ...nextOrder
        });
      } else {
        this.orderHistory.unshift(nextOrder);
      }
      this.persistOrders();
    },
    updateOrder(orderId, patch = {}) {
      const targetId = String(orderId);
      this.orderHistory = this.orderHistory.map((item) => {
        if (String(item.orderId) !== targetId) {
          return item;
        }
        return {
          ...item,
          ...patch,
          updateTime: new Date().toISOString()
        };
      });
      this.persistOrders();
    },
    clearOrders() {
      this.orderHistory = [];
      this.persistOrders();
    },
    addAddress(address) {
      const nextAddress = {
        id: Date.now(),
        city: address.city || this.city,
        contact: address.contact || '默认联系人',
        phone: address.phone || '',
        detail: address.detail || '',
        createdTime: new Date().toISOString()
      };
      const nextAddresses = [nextAddress, ...this.addressList];
      writeJson(this.getScopedStorageKey(ADDRESSES_KEY), nextAddresses);
      this.addressList = nextAddresses;
    },
    removeAddress(addressId) {
      const nextAddresses = this.addressList.filter((item) => Number(item.id) !== Number(addressId));
      writeJson(this.getScopedStorageKey(ADDRESSES_KEY), nextAddresses);
      this.addressList = nextAddresses;
    }
  }
});
