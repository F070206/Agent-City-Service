import axios from 'axios';

/**
 * Axios 实例：
 * - 开发时通过 Vite 代理把 /api 转发到 Spring Boot 8083
 * - 生产构建后由 Spring Boot 同源托管，直接请求真实后端接口
 * - 统一解包后端 Result 返回结构
 */
const service = axios.create({
  baseURL: import.meta.env.DEV ? '/api' : '',
  timeout: 8000
});

const TOKEN_KEY = 'hm-dianping-token';

service.interceptors.request.use((config) => {
  const token = typeof config.__authTokenOverride === 'string'
    ? config.__authTokenOverride
    : localStorage.getItem(TOKEN_KEY);
  config.headers = config.headers || {};
  if (token) {
    config.headers.authorization = token;
    config.__authToken = token;
  }
  return config;
});

service.interceptors.response.use(
  (response) => {
    const payload = response.data;
    if (payload && typeof payload.success === 'boolean') {
      if (!payload.success) {
        const error = new Error(payload.errorMsg || '请求失败');
        error.data = payload.data;
        error.isBackendRejection = true;
        return Promise.reject(error);
      }
      return payload.data;
    }
    return payload;
  },
  (error) => {
    const payload = error?.response?.data;
    if (payload && typeof payload.success === 'boolean' && payload.success === false
        && typeof payload.errorMsg === 'string' && payload.errorMsg.trim()) {
      error.message = payload.errorMsg;
      error.data = payload.data;
    }
    const requestToken = error?.config?.__authToken;
    if (error?.response?.status === 401 && requestToken
        && localStorage.getItem(TOKEN_KEY) === requestToken) {
      localStorage.removeItem(TOKEN_KEY);
      window.dispatchEvent(new CustomEvent('hm-auth-expired', { detail: { token: requestToken } }));
    }
    return Promise.reject(error);
  }
);

export default service;
