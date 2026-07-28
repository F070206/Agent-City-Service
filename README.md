<div align="center">
  <h1>CityAI Hub</h1>
  <p>面向同城生活场景的 AI Agent 服务平台</p>
  <p>
    <img src="https://img.shields.io/badge/Java-17-007396" alt="Java 17">
    <img src="https://img.shields.io/badge/Spring%20Boot-3.2.4-6DB33F" alt="Spring Boot 3.2.4">
    <img src="https://img.shields.io/badge/Spring%20AI-1.0.3-412991" alt="Spring AI 1.0.3">
    <img src="https://img.shields.io/badge/Vue-3.4-42B883" alt="Vue 3.4">
    <img src="https://img.shields.io/badge/Redis-Required-DC382D" alt="Redis">
    <img src="https://img.shields.io/badge/RocketMQ-2.3.3-FF6600" alt="RocketMQ">
    <img src="https://img.shields.io/badge/Milvus-Vector%20Store-00A1EA" alt="Milvus">
  </p>
</div>

## 项目简介

CityAI Hub 是一个类“大众点评”的本地生活服务平台，在店铺、探店笔记、关注、优惠券、秒杀和订单等基础业务之上，加入了支持多轮推理、工具调用、RAG 检索、会话记忆和 SSE 流式输出的 AI 助手。

项目同时覆盖高并发业务中的缓存治理、分布式锁、限流、异步下单、延迟关单与库存一致性等实践，适合作为 Spring Boot、Redis、RocketMQ 与 Spring AI 的综合学习项目。

## 核心功能

### 本地生活业务

- 手机验证码登录、Token 刷新和登录拦截
- 首页分类、店铺详情、商户搜索与 Redis GEO 附近检索
- 探店笔记发布、点赞、关注、共同关注和关注流
- 普通优惠券购买与模拟支付
- 秒杀券库存预扣、一人一单、异步下单
- RocketMQ 延迟消息驱动超时关单与库存回滚

### AI Agent

- 基于 Spring AI 的多轮 Think-Execute 推理链路
- 工具注册、工具白名单、最大执行步数和异常兜底
- 会话、消息及工具调用日志持久化
- 最近窗口会话记忆，控制上下文长度与 Token 消耗
- 基于 SSE 的状态、增量内容和完成事件推送
- 支持店铺推荐、单店分析、评论总结、路线与天气场景
- 预留 MCP 路线及天气工具接入，并提供本地降级逻辑

### RAG 与工程治理

- Milvus 存储平台知识、店铺画像和探店笔记三类向量索引
- 向量召回与关键词 fallback 组合的混合检索
- 布隆过滤器与缓存空值共同防止缓存穿透
- 逻辑过期与互斥重建缓解热点 Key 击穿
- Redis ZSet + Lua + AOP 实现滑动窗口限流
- Redis Lua 保证秒杀资格校验与库存预扣的原子性
- Redisson/Redis 分布式锁保护订单状态并发更新

## 技术栈

| 分层 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.2.4、Spring Web、WebFlux |
| AI | Spring AI 1.0.3、OpenAI 兼容接口、Milvus、RAG、MCP 扩展 |
| 数据访问 | MyBatis-Plus 3.5.5、MySQL 8 |
| 缓存与并发 | Redis、Redisson、Lua、Bloom Filter |
| 消息队列 | RocketMQ |
| 前端 | Vue 3、Vite 4、Pinia、Vue Router、Element Plus、Axios |
| 测试与构建 | JUnit、Mockito、Maven、npm |

## 系统结构

```text
Browser / Vue 3
       |
       | HTTP + SSE
       v
Spring Boot API
  |-- 本地生活业务：用户、店铺、笔记、关注、优惠券、订单、支付
  |-- AI Agent：推理循环、工具调用、会话记忆、RAG、MCP 扩展
  |
  |-- MySQL：业务数据、会话、消息、工具日志
  |-- Redis：登录态、缓存、GEO、限流、库存、分布式锁
  |-- RocketMQ：秒杀异步下单、延迟关单
  `-- Milvus：知识库、店铺画像、探店笔记向量
```

## 项目目录

```text
.
|-- frontend-vue/                 # Vue 3 前端源码
|-- src/main/java/com/hmdp/
|   |-- ai/                       # Agent、RAG、记忆、工具与 AI 接口
|   |-- controller/               # 本地生活业务接口
|   |-- service/                  # 业务服务
|   |-- mq/                       # RocketMQ 消费者
|   |-- component/                # 初始化器、事件监听与执行协调
|   `-- utils/                    # 缓存、锁、限流与登录工具
|-- src/main/resources/
|   |-- db/                       # MySQL 初始化脚本
|   |-- knowledge/                # RAG 初始知识
|   |-- static/                   # 已构建的前端静态资源
|   |-- *.lua                     # 秒杀、限流与解锁脚本
|   `-- application.yaml          # 应用配置
|-- src/test/                     # 单元及集成测试
`-- pom.xml
```

## 环境要求

开始前请准备：

- JDK 17
- Maven 3.9+
- MySQL 8.x
- Redis 6.x+
- RocketMQ 4.x/5.x
- Milvus 2.x
- Node.js 18+ 与 npm（仅在前端开发或重新构建时需要）
- 一个兼容 OpenAI API 的模型服务及 API Key

默认端口如下：

| 服务 | 默认地址 |
| --- | --- |
| 后端 | `http://localhost:8083` |
| 前端开发服务器 | `http://localhost:5173` |
| MySQL | `localhost:3306` |
| Redis | `localhost:6379` |
| RocketMQ NameServer | `localhost:9876` |
| Milvus | `localhost:19530` |

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/F070206/Agent-City-Service.git
cd Agent-City-Service
```

### 2. 初始化数据库

创建 `hmdp` 数据库，并按顺序执行：

```text
src/main/resources/db/hmdp.sql
src/main/resources/db/ai-agent.sql
```

### 3. 启动基础服务

启动 MySQL、Redis、RocketMQ 和 Milvus，并确保其地址与 `src/main/resources/application.yaml` 一致。

### 4. 设置必要的环境变量

PowerShell 示例：

```powershell
$env:SPRING_DATASOURCE_PASSWORD="你的 MySQL 密码"
$env:SPRING_AI_OPENAI_API_KEY="你的 API Key"
```

Bash 示例：

```bash
export SPRING_DATASOURCE_PASSWORD="你的 MySQL 密码"
export SPRING_AI_OPENAI_API_KEY="你的 API Key"
```

默认使用 DashScope 的 OpenAI 兼容地址。如需切换模型服务，可设置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SPRING_AI_OPENAI_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode` | OpenAI 兼容服务地址 |
| `SPRING_AI_OPENAI_API_KEY` | 空 | 模型服务密钥 |
| `SPRING_AI_OPENAI_CHAT_MODEL` | `qwen3.5-flash` | 对话模型 |
| `SPRING_AI_OPENAI_EMBEDDING_MODEL` | `text-embedding-v4` | 向量模型 |
| `SPRING_AI_MILVUS_HOST` | `localhost` | Milvus 地址 |
| `SPRING_AI_MILVUS_PORT` | `19530` | Milvus 端口 |
| `AI_AGENT_BOOTSTRAP_ENABLED` | `true` | 启动时构建 AI 索引 |
| `AI_AGENT_MCP_ENABLED` | `false` | 是否启用外部 MCP 工具 |

完整可调参数请查看 `src/main/resources/application.yaml`。

> 不要把数据库密码、API Key 或其他密钥直接提交到仓库。

### 5. 启动后端

```bash
mvn spring-boot:run
```

项目已包含构建后的前端静态资源，后端启动后可直接访问：

```text
http://localhost:8083
```

### 6. 前端开发模式（可选）

```bash
cd frontend-vue
npm install
npm run dev
```

Vite 会把 `/api` 请求代理到 `http://localhost:8083`。访问：

```text
http://localhost:5173
```

重新构建前端并写入后端静态目录：

```bash
npm run build
```

## AI 工具扩展

路线与天气工具默认关闭。接入兼容的外部工具服务后，可通过以下变量启用：

```text
AI_AGENT_MCP_ENABLED=true
AI_AGENT_MCP_ROUTE_ENABLED=true
AI_AGENT_MCP_ROUTE_BASE_URL=<route-service-url>
AI_AGENT_MCP_ROUTE_API_KEY=<route-service-key>
AI_AGENT_MCP_WEATHER_ENABLED=true
AI_AGENT_MCP_WEATHER_BASE_URL=<weather-service-url>
AI_AGENT_MCP_WEATHER_API_KEY=<weather-service-key>
```

未启用或远端调用失败时，系统会执行已有的本地 fallback 逻辑。

## 关键设计

### 秒杀与订单

秒杀请求先通过 Redis Lua 原子校验库存与一人一单资格，预扣成功后将下单任务发送到 RocketMQ，由消费者异步写入数据库。订单创建后发送延迟消息；到期仍未支付时自动关闭订单并回滚库存。支付与超时关单竞争同一订单状态时，通过分布式锁和状态校验避免重复处理。

### 缓存治理

高频查询在缓存层前增加布隆过滤器，并使用缓存空值处理过滤器误判。热点数据采用逻辑过期和后台异步重建，在可接受短暂旧数据的前提下避免大量请求同时回源。不同缓存使用差异化 TTL，入口限流进一步降低雪崩风险。

### Agent 推理

模型负责判断下一步动作，应用负责工具注册、参数边界、执行步数、日志记录与结束条件。工具结果会进入下一轮上下文，直到产出最终回答或触发兜底，从而兼顾灵活性、可观测性与可控性。

### 分层 RAG

平台规则使用向量检索与关键词 fallback，增强确定性问题的命中率；店铺画像和探店笔记使用向量检索，服务于推荐、单店分析与评论总结。索引可在应用启动时初始化，相关开关和阈值均可通过环境变量调整。

## 测试

执行后端测试：

```bash
mvn test
```

部分集成测试依赖正在运行的 MySQL、Redis、RocketMQ、Milvus 或模型服务。若本地没有启动对应依赖，请按测试范围单独执行。

## 说明

项目的基础本地生活业务参考了经典点评类实战项目，并在此基础上完成了缓存、限流、消息队列、订单状态机和 AI Agent 模块的重构与扩展。项目目前以学习、实践和技术交流为主要用途。
