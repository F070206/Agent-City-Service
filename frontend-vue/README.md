# 同城生活服务平台前端

基于 `Vue3 + Vite + Element Plus + Axios + Pinia` 的同城生活服务平台前端示例项目。

## 启动

```bash
npm install
npm run dev
```

运行前端自动化测试：

```bash
npm test
```

默认访问地址：

```bash
http://localhost:5173
```

## 说明

- 店铺、用户、笔记、优惠券和 AI 等主要数据通过后端接口获取；开发服务器将 `/api` 代理到本地 `8083` 端口
- `src/mock/` 提供部分界面展示数据，以及 AI 后端不可用时的降级回答
- 已包含首页、分类页、商家详情、个人中心、AI 美食助手
