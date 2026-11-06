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

- 手机验证码一次性登录、Token 刷新、服务端登出撤销和登录拦截
- 首页分类、店铺详情、商户搜索与 Redis GEO 附近检索
- 探店笔记发布、点赞、关注、共同关注和关注流
- 笔记发布事务内持久化粉丝 Feed 投递任务，由租约 worker 批量写入 Redis 并失败重试
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
- 可接入 MCP SSE 路线及天气工具，并提供本地降级逻辑

### RAG 与工程治理

- Milvus 存储平台知识、店铺画像和探店笔记三类向量索引
- 向量召回与关键词 fallback 组合的混合检索
- 布隆过滤器与缓存空值共同防止缓存穿透
- 逻辑过期与互斥重建缓解热点 Key 击穿
- Redis ZSet + Lua + AOP 实现滑动窗口限流
- Redis Lua 保证秒杀资格校验与库存预扣的原子性
- 数据库条件更新保护订单状态并发迁移，持久化补偿任务同步关单库存

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

如需独立部署用户、内容、订单、AI 服务及网关，参见 [微服务部署说明](docs/microservices.md)。原有单体启动方式仍可用于本地开发。

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
src/main/resources/db/order-stock-sync.sql
```

已有数据库升级时不要重新执行包含删表语句的 `hmdp.sql`。若 `tb_shop.x` 和 `tb_shop.y` 仍为 `DOUBLE UNSIGNED`，先备份数据库，再执行 `src/main/resources/db/shop-coordinate-signed.sql`，使数据库接受应用支持的负经度/纬度。

### 3. 启动基础服务

启动 MySQL、Redis、RocketMQ 和 Milvus，并确保其地址与 `src/main/resources/application.yaml` 一致。

### 4. 设置必要的环境变量

PowerShell 示例：

```powershell
$env:SPRING_DATASOURCE_PASSWORD="你的 MySQL 密码"
$env:SPRING_AI_OPENAI_API_KEY="你的 API Key"
$env:CITYAI_AUTH_MOCK_CODE_ENABLED="true" # 仅本地无短信服务联调时启用
```

Bash 示例：

```bash
export SPRING_DATASOURCE_PASSWORD="你的 MySQL 密码"
export SPRING_AI_OPENAI_API_KEY="你的 API Key"
export CITYAI_AUTH_MOCK_CODE_ENABLED="true" # 仅本地无短信服务联调时启用
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
| `SPRING_AI_MILVUS_INITIALIZE_SCHEMA` | `true` | 是否允许 Milvus VectorStore 创建缺少的 collection/schema |
| `AI_AGENT_RAG_ALLOW_DESTRUCTIVE_COLLECTION_RESET` | `false` | 是否允许维度不匹配时删除已有 Milvus collection；仅在备份并准备重建索引后显式开启 |
| `AI_AGENT_BOOTSTRAP_ENABLED` | `true` | 是否在启动时补建/重建索引；关闭时不写入或创建 Milvus 库、collection 和 schema，但若向量库启用仍读取已有索引 |
| `AI_AGENT_RAG_VECTOR_STORE_ENABLED` | `true` | 是否连接 Milvus 进行 RAG 检索；关闭时使用仅驻留内存的空向量库，适合明确不使用向量检索的本地场景 |
| `AI_AGENT_RAG_MAX_TOP_K` | `20` | Agent 向量检索每次最多召回条数；模型请求的 `topK` 超过此值时按上限执行 |
| `AI_SSE_MAX_CONNECTIONS_PER_USER` | `8` | 每个用户在单个 AI 实例上的最大并发 SSE 连接数；超限返回 HTTP 429，断开或超时后释放名额 |
| `AI_AGENT_INSIGHT_MAX_NEARBY_PAGE` | `20` | Agent 附近店铺搜索允许的最大页码；超过上限时返回空结果，不调用内容服务 |
| `AI_AGENT_MULTI_AGENT_MAX_STEPS` | `20` | Agent 推理最大轮数，运行时限制在 1 到 50 轮，避免异常配置导致无限长循环 |
| `AI_AGENT_MCP_ENABLED` | `false` | 是否启用外部 MCP 工具 |
| `AI_AGENT_MCP_TIMEOUT_MILLIS` | `2500` | MCP 工具请求超时，运行时限制在 500 毫秒到 30 秒，避免单次依赖调用无限占用 Agent worker |
| `CITYAI_ADMIN_TOKEN` | 空（管理操作关闭） | 店铺/优惠券管理写入及 AI 向量索引重建必须通过 `X-Admin-Token` 提供独立管理令牌；请使用随机强令牌，不要复用内部服务令牌 |
| `CITYAI_UPLOAD_DIR` | `./uploads` | 图片文件存储目录；单体模式下同目录也由 Spring Boot 静态资源服务读取，改目录时无需改图片 URL。单张图片上限 8 MiB |
| `CITYAI_AUTH_MOCK_CODE_ENABLED` | `false` | 显式设为 `true` 时，验证码接口会将模拟验证码返回给前端以便本地联调；项目未接入短信供应商，生产环境必须保持关闭 |

完整可调参数请查看 `src/main/resources/application.yaml`。

> 不要把数据库密码、API Key 或其他密钥直接提交到仓库。

### 5. 启动后端

Windows 双击启动时，也可复制 `start.local.example.json` 为 `start.local.json`，填入数据库密码和模型 API Key；如需使用店铺/优惠券管理写入或 AI 向量索引重建，再填入独立的管理令牌。该本地文件已被 Git 忽略；已设置的环境变量优先使用。

Windows 可在配置好上述环境变量后，双击项目根目录的 `start.cmd`。脚本会启动 `Ubuntu-24.04` 中的 Redis、本机 `ROCKETMQ_HOME` 下的 RocketMQ，以及 Docker Desktop 中现有的 `milvus-etcd`、`milvus-minio`、`milvus-standalone` 容器，然后启动后端。MySQL 和 Docker Desktop 需先运行，数据库需先初始化；关闭窗口或按 Ctrl+C 可停止后端。
脚本会在运行期间保持一个 WSL 会话，避免 Ubuntu 空闲退出使 Redis 断连。如果 Ubuntu 内 `redis-cli ping` 返回 `PONG`，但 Windows 仍无法连接 `127.0.0.1:6379`，再检查 localhost 端口转发；WSL 的 localhost 代理提示本身不代表 Redis 连接故障。
也可双击根目录的 `stop.cmd` 关闭监听 8083 端口的本项目后端进程；脚本不会关闭 MySQL、Redis、RocketMQ 或 Milvus。

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

模型思考、流式整理、内容服务 Feign 调用、路线/天气外部工具以及 RAG 向量检索/索引写入分别使用 Sentinel 熔断，按慢调用比例和异常比例统计；默认统计窗口 60 秒、至少 5 次请求、熔断恢复等待 10 秒。内容服务共享低基数资源 `ai:content`，避免将店铺 ID 或查询词作为熔断维度；其 Feign 连接和读取超时分别默认为 500 毫秒和 2 秒。熔断后模型思考走已有错误回答，内容工具失败由 Agent 错误路径处理，流式整理使用候选答案补全，路线和天气走本地估算，知识检索继续尝试本地关键词兜底；向量索引初始化通过不带相似度阈值的探测查询判断集合是否为空，避免探测词不相关时误触发重建。重建先读取并暂存完整源数据，再替换已有索引；替换写入失败时清理部分新索引并向上报错。恢复窗口后 Sentinel 允许探测请求。可通过 `AI_AGENT_CIRCUIT_ENABLED`、`AI_AGENT_CIRCUIT_WINDOW_SECONDS`、`AI_AGENT_CIRCUIT_RECOVERY_SECONDS`、`AI_AGENT_CIRCUIT_MIN_REQUESTS`、`AI_AGENT_CIRCUIT_ERROR_RATIO`、`AI_AGENT_CIRCUIT_SLOW_RATIO`、`AI_AGENT_CIRCUIT_MODEL_SLOW_MILLIS`、`AI_AGENT_CIRCUIT_STREAM_SLOW_MILLIS` 和 `AI_AGENT_CIRCUIT_TOOL_SLOW_MILLIS` 调整阈值。流式回答还保留独立超时和已有的内容补全逻辑。

## 关键设计

### 订单 ID

普通券和秒杀订单统一通过 `SnowflakeIdWorker` 生成 ID，复用已有 MyBatis-Plus 的 `Sequence` 雪花算法，无需新增依赖，也不再访问 Redis 的 `icr:order:*` 计数器。ID 由毫秒时间戳、数据中心编号、机器编号和序列号组成，仍使用 `Long` / 数据库 `BIGINT`，接口继续以字符串返回，已有订单 ID 不做修改。

- 单实例本地运行默认使用数据中心 `0`、机器 `0`。
- 多实例部署时，通过 `SNOWFLAKE_DATACENTER_ID` 和 `SNOWFLAKE_WORKER_ID` 设置编号，两个值均为 `0~31`，所有发号实例的编号组合必须不同；这两个环境变量只控制本项目的订单生成器。不要在滚动发布中让新旧实例同时复用同一组合。
- 使用 MyBatis-Plus 自带的运行期时钟回拨保护：小幅回拨等待后重试，较大回拨或时间未恢复时抛出异常。重启不会保留上次发号时间，需保持系统时钟同步，避免时钟回拨后立即复用节点编号。
- 订单库存、登录态等功能仍然使用 Redis；这里只替换 ID 生成逻辑，不改变数据库自增主键和其他 UUID 的用途。

### 秒杀与订单

秒杀请求通过 Redis Lua 原子校验库存与一人一单资格，并在同一脚本中持久记录待处理 payload 后返回受理结果；后台 worker 批量写入数据库 outbox，再向 RocketMQ 投递下单消息，由消费者写入订单数据库。下单投递失败时由数据库任务重试。订单创建时间 + 15 分钟的关单期限随 outbox 批量落库；到期后后台 worker 发送普通关单消息，发送失败按 30 秒间隔持久重试。秒杀支付创建和支付回调都在数据库条件更新中检查该截止时刻，即使关单 worker 尚未运行，到期订单也不能发起支付或完成支付；历史 outbox 尚无截止时间时按订单创建时间推算。到期仍未支付时自动关闭订单并回滚库存，普通券订单不受秒杀期限限制。支付与超时关单通过数据库 `WHERE status = 1` 条件更新竞争同一订单状态，避免旧快照覆盖终态；支付状态直接查询数据库。
秒杀活动状态与起止时间缓存在 Redis，Redis Lua 使用服务端时间，在库存预扣的同一次原子执行中拒绝未启用、尚未开始或已经结束的活动。数据库 `LocalDateTime` 按 `Asia/Shanghai` 转为 epoch 毫秒，所有实例使用相同时区解释活动窗口；活动元数据缺失时会尝试从数据库重新加载，事务提交后的缓存刷新失败会先尝试删除旧元数据，并由后台定时重试，避免旧活动窗口继续受理请求。活动不存在或时间未配置时直接拒绝请求。自行部署时应保持数据库与 Redis 时钟同步。
当前支付回调和支付链接是演示接口，回调由已登录的订单所属用户触发，状态查询也只向订单所属用户返回结果。接入真实微信或支付宝服务时，需要单独验证平台签名并改造回调鉴权。

秒杀 Lua 在库存预扣成功的同一次原子执行中，把订单消息写入 Redis 恢复队列；请求线程随后同步写入 MySQL 投递表，确认有持久记录后才返回受理成功。若数据库写入失败或结果不确定且无法确认记录，接口返回“处理中”及订单 ID，Redis 恢复副本保留供 worker 重试。订单服务 worker 默认每 250 毫秒读取最多 500 条恢复记录，批量查重并补写缺失的数据库投递任务，确认写入后才清理 Redis payload 与恢复队列；同批任务随后进入下单投递。请求返回成功后，即使 Redis 恢复副本丢失，数据库任务仍可重试下单；Redis 预扣后、MySQL 记录提交前进程退出时，请求不会报告成功，Redis 恢复队列可在仍可用时重建任务。下单消息失败则由数据库任务按同一订单 ID 重投，消费者落库后在本地事务中标记完成。投递表同时持久化 15 分钟关单到期时间；到期后 worker 领取任务并发送普通关单消息，失败按 30 秒间隔重试。多个订单实例通过数据库中的十分钟领取租约竞争最多 500 条下单或最多 100 条关单任务，避免同时投递；实例在租约期间退出时，其他实例可在租约到期后接手。已有订单库若尚无投递表，执行 `seckill-order-dispatch.sql` 创建完整表；若表已存在但还没有租约列，再执行 `seckill-order-dispatch-lease.sql` 升级；若已有表但缺少超时投递列，执行 `seckill-order-dispatch-timeout.sql`。该超时迁移将已有行标记为已发送，避免重复安排关单消息，已有超时订单仍由数据库补扫 worker 处理。监控 Redis `ZCARD seckill:dispatch:recovery`、数据库未完成任务数与重试次数。Redis 与 MySQL 之间仍不构成跨系统强一致事务；Redis 整体丢失且请求尚未在 MySQL 留下记录时，不能确认该请求是否已进入 Redis Lua 临界阶段。

秒杀活动元数据用数据库 `activity_version` 和 Redis Lua 比较版本后更新，避免并发编辑的旧事务回调覆盖较新活动状态。已有订单库升级前先执行 `src/main/resources/db/seckill-activity-version.sql`；新库脚本已包含版本列。Redis 刷新失败会失效活动缓存并排队重试，重试时重新读取数据库当前版本。

订单消费者还会在 `tb_seckill_order_guard` 中原子占用用户+秒杀券组合，避免不同订单 ID 的重复消息并发生成多笔有效秒杀订单。同一订单 ID 并发重复投递时，会锁定读取当前占用订单并跳过 Redis 库存回补，避免把原订单的预扣误当作另一笔重复订单退回。取消订单在同一数据库事务内释放占用，因此取消后仍可再次购买；普通券购买不经过该限制。已有订单库部署新代码前，先暂停订单写入并执行 `src/main/resources/db/seckill-order-guard.sql`。该脚本先查询存量未取消秒杀订单是否存在用户+券重复；出现重复时必须先人工核对处理，再运行迁移。Redis 一人一单校验仍用于入口快速拦截，数据库占用表负责消息消费时的持久化防重。

延迟关单消息发送或消费失败时，后台每 30 秒按订单 ID 分批补扫已超时的未支付秒杀订单；补扫调用异常会写入 `tb_order_timeout_retry`，失败记录按 30 秒退避重试并使用十分钟数据库租约供多实例接管，不必等待扫描游标回绕。多实例常规扫描仍通过 Redisson 锁协调；Redis 锁不可用时依靠数据库订单状态条件更新保证幂等。部署新代码前，已有数据库应执行 `src/main/resources/db/order-timeout-recovery.sql` 更新扫描索引，并执行 `src/main/resources/db/order-timeout-retry.sql` 创建持久重试表；新库脚本已包含所需结构。补扫间隔可通过 `order.timeout-recovery.delay-ms` 调整。

关单、数据库库存回补和 `tb_order_stock_sync` 补偿任务在同一事务内提交。后台默认每 5 秒扫描到期任务（`order.stock-sync.delay-ms` 可调整），通过十分钟数据库租约原子领取，避免多个订单实例重复处理；进程退出后租约过期可重新领取。通过 Lua 原子执行 Redis 库存回补、资格释放和订单幂等标记；失败记录重试次数并在 30 秒后重试，重启后可继续扫描。释放脚本必须找到与关单任务订单 ID 一致的 Redis 用户占用映射才会改库存；映射缺失时拒绝猜测归属，任务保持未完成并重试，需按库存核对流程处理。订单服务每 30 秒刷新 `cityai.order.stock.sync.pending`（未完成任务数）、`cityai.order.stock.sync.oldest.age`（最老任务年龄秒）和 `cityai.order.stock.sync.repeated.failures`（失败次数达到阈值的未完成任务数）指标，可通过本机管理地址 `http://127.0.0.1:8086/actuator/metrics/{指标名}` 查看；容器部署时管理端口仅绑定容器回环地址，可用 `docker exec` 进入目标容器查询。失败阈值默认 3，可由 `order.stock-sync.metrics.repeated-failure-threshold` 调整。刷新失败保留上次快照并记录日志。属于**数据库事务一致性 + Redis 可重试的最终一致性**，不是 MySQL 与 Redis 跨系统强一致性。新库通过 `order-stock-sync.sql` 创建任务表；已有旧表需先执行一次 `order-stock-sync-lease.sql`，不能重新执行会删表的 `hmdp.sql`。

Redis 回补标记不设 TTL，避免延迟重试释放同一用户的新订单；仅在确认任务及所有在途执行均已结束后，才能协调清理任务与标记。秒杀库存键缺失时，订单服务会在没有未完成下单 outbox、未完成关单库存同步任务及 Redis 恢复消息的条件下，按 MySQL 库存重建，并从 `tb_seckill_order_guard` 恢复有效用户占用映射；现有 Redis 映射与数据库不一致时会拒绝重建。存在未完成关单库存同步任务或占用数据异常时仍需暂停活动并人工核对，避免把已在 MySQL 回补、尚未同步到 Redis 的库存重复计入。启动预热查询失败会阻止服务启动，避免带着不完整库存状态继续运行。需监控未完成任务数量、最早创建时间及 `attempts`，持续失败不能仅靠重试消除。数据库补扫覆盖已落库订单的延迟关单消息失败；持续无法落库的秒杀消息仍需人工排查与补偿。

秒杀 outbox 每 30 秒刷新下单投递与到期关单投递积压 gauges：`cityai.seckill.dispatch.orders.pending`、`cityai.seckill.dispatch.orders.oldest.age`、`cityai.seckill.dispatch.orders.repeated.failures`，以及 `cityai.seckill.dispatch.timeouts.pending`、`cityai.seckill.dispatch.timeouts.oldest.age`、`cityai.seckill.dispatch.timeouts.repeated.failures`。Redis 恢复队列另有 `cityai.seckill.dispatch.redis.recovery.pending`，直接统计 `seckill:dispatch:recovery` 的 ZSET 长度，以覆盖尚未写入数据库 outbox 的请求。重复失败阈值默认为 3，可由 `order.dispatch.metrics.repeated-failure-threshold` 调整；这些值通过订单实例管理端口的 Actuator metrics 暴露，数据库或 Redis 刷新失败时分别保留对应的上次快照。

### 缓存治理

高频查询先读缓存；店铺缓存命中时直接返回，只有缓存未命中才查询布隆过滤器，并在布隆放行后回源数据库。缓存空值处理布隆误判和数据库中不存在的记录。热点数据采用逻辑过期和后台异步重建，在可接受短暂旧数据的前提下避免大量请求同时回源。不同缓存使用差异化 TTL，入口限流进一步降低雪崩风险。

店铺详情冷缓存使用 Redis 互斥锁回源，逻辑过期后先返回旧值并异步重建；异步写入仅在缓存仍为读取时的旧值时生效，避免覆盖更新结果。逻辑过期使用 epoch 毫秒判断，跨时区实例共享缓存时仍以同一绝对时间为准；新格式暂时保留旧 `expireTime` 字段以兼容滚动发布中的旧实例，已有旧格式缓存按其物理 TTL 自然过期。重建线程池最多 10 个工作线程、排队容量 1000；队列满时释放当前锁、记录拒绝计数并继续返回旧缓存，避免不同热点同时过期时任务队列无限增长。后台线程为空闲超时的守护线程，应用退出不会被缓存重建池拖住。逻辑缓存额外设置两倍于逻辑期限的物理 TTL，防止重建持续失败时旧值永久保留；店铺更新在数据库提交后写入完整的新缓存值。

缓存重建池状态通过 `cityai.shop.cache.rebuild.active`、`queued`、`queue.remaining`、`pool.size` 和 `cityai.shop.cache.refresh.rejections` 指标暴露。默认 10 个线程、1000 个排队任务、空闲线程 60 秒退出；可通过 `SHOP_CACHE_REBUILD_THREADS`、`SHOP_CACHE_REBUILD_QUEUE_CAPACITY` 和 `SHOP_CACHE_REBUILD_KEEP_ALIVE_SECONDS` 调整。线程数与队列容量必须大于 0，空闲超时不能为负数。

店铺类型列表缓存未命中时通过 Redisson 分布式锁合并回源，并在拿锁后复查缓存；多个服务实例同时冷启动或缓存同时过期时只需一个实例查询数据库。缓存或锁服务不可用时仍直接回源，保持列表查询可用。

新增店铺时，Bloom ID、详情缓存与 GEO 索引均在数据库事务提交后写入 Redis，避免数据库回滚后留下不存在店铺的 Bloom 假阳性；Bloom 更新失败会进入进程内重试队列（默认每 5 秒重试，可由 `shop.bloom-sync.retry-ms` 调整），服务重启时由内容服务启动阶段分页扫描店铺表补齐。

### Agent 推理

模型负责判断下一步动作，应用负责工具注册、参数边界、执行步数、日志记录与结束条件。每轮最多执行一个已注册工具；返回多个工具调用时整轮拒绝，避免先执行部分副作用后才发现调用异常。推理轮数限制在 1 到 50 轮。单个工具结果进入模型上下文前最多保留 8,000 字符，每轮累计工具结果最多 24,000 字符；超出累计上限后停止继续调用工具，并基于已有结果收尾，避免大检索结果在多轮中持续放大上下文。AI 聊天、推荐和 RAG 查询最多 8,000 字符，限制模型输入、关键词兜底扫描和数据库 `TEXT` 写入大小。AI 聊天与推荐请求允许省略整组坐标，但经纬度必须同时提供且在有效范围内；附近店铺工具也会校验模型生成的分类和坐标，避免无效参数进入内容服务。附近店铺搜索限制最大页码，向量检索限制每次最大召回量，防止模型生成超大参数导致 GEO 查询、数据库偏移或向量结果集膨胀。
异步聊天使用固定容量的专用线程池；用户消息与包含坐标的任务记录在同一事务内保存。队列已满时在新事务中保存“请求较多”的助手回复并完成任务；后台补扫待处理和租约过期任务，实例重启后可继续执行。任务入队等待、等待会话锁和执行期间每分钟续租一次，避免长队列或长时间模型调用后被补扫重复领取；助手回复与任务完成状态在同一事务内提交，避免重复回答。AI 线程池通过 `cityai.ai.agent.executor.active`、`cityai.ai.agent.executor.queued`、`cityai.ai.agent.executor.queue.remaining`、`cityai.ai.agent.executor.pool.size` 和 `cityai.ai.agent.executor.rejected` 暴露运行状态，可从 AI 实例管理端点 `/actuator/metrics/{指标名}` 查看；容器部署时可用 `docker exec` 进入目标容器查询。新建 AI 库执行 `src/main/resources/db/ai-task.sql`；已有 AI 任务表还需执行 `src/main/resources/db/ai-task-pending-recovery-index.sql` 添加恢复扫描索引。
路线与天气工具可通过 `AI_AGENT_MCP_ENABLED=true`、对应的 `AI_AGENT_MCP_ROUTE_ENABLED` / `AI_AGENT_MCP_WEATHER_ENABLED` 和 `AI_AGENT_MCP_*_BASE_URL` 接入外部 MCP SSE 服务，默认路径为 `/sse`。请求超时由 `AI_AGENT_MCP_TIMEOUT_MILLIS` 控制，并统一限制在 500 毫秒到 30 秒。当前固定使用与 Spring AI 1.0.3 兼容的 MCP Java SDK 0.10.0（协议版本 2024-11-05）；仅提供 Streamable HTTP 的服务端需额外适配。工具名分别由 `AI_AGENT_MCP_ROUTE_TOOL_NAME` 和 `AI_AGENT_MCP_WEATHER_TOOL_NAME` 指定，返回文本应是匹配路线或天气 DTO 的 JSON，且需包含路线摘要/距离/交通信息之一，或天气/用餐建议之一。HTTP/MCP 错误、空响应和不完整结果都会进入 Sentinel 依赖统计并切换到带 `degraded=true` 的本地兜底结果。旧版自定义 `/tools/invoke` 服务可设 `AI_AGENT_MCP_*_TRANSPORT=legacy-http` 并将对应 `*_INVOKE_PATH` 设为 `/tools/invoke`。未配置时同样使用本地兜底结果。

### 分层 RAG

平台规则使用向量检索与关键词 fallback，增强确定性问题的命中率；店铺画像和探店笔记使用向量检索，服务于推荐、单店分析与评论总结。`AI_AGENT_BOOTSTRAP_ENABLED=false` 只跳过启动时索引写入，仍会读取已有 Milvus 集合；缺少集合时按 fail-fast 配置报错或使用进程内空向量库。`AI_AGENT_RAG_VECTOR_STORE_ENABLED=false` 才会完全关闭 Milvus 检索并改用进程内空向量库。索引和向量检索开关、阈值均可通过环境变量调整。
平台规则的向量库查询失败时，若本地知识文本有匹配片段仍会返回关键词结果；没有匹配内容则保留原错误。带元数据过滤条件的检索不会使用未经同样过滤的本地文本兜底。

## 测试

执行后端测试：

```bash
mvn test
```

部分集成测试依赖正在运行的 MySQL、Redis、RocketMQ、Milvus 或模型服务。若本地没有启动对应依赖，请按测试范围单独执行。

历史手动集成测试 `HmDianPingApplicationTests`、`RedissonTest`、`BoomFilterTest` 和 `ShopInsightSeedDataTest` 会使用固定 Redis 键/开发数据或写入本地文件，并依赖个人服务配置；默认 `mvn test` 会跳过它们。只有准备好隔离的本地 MySQL、Redis、Milvus 和模型测试环境，并确认接受这些测试的数据写入后，才设置 `CITYAI_RUN_LEGACY_INTEGRATION_TESTS=true` 显式运行；不要指向生产或共享开发环境。

支付与关单回归测试可单独运行：

```bash
mvn -Dtest=PaymentServiceImplTest,PaymentControllerTest,OrderStockSyncWorkerTest,SeckillStockWarmupTest,VoucherOrderIdTest test
```

真实 MySQL 验证使用 `PaymentConsistencyIntegrationTest`：设置 `PAYMENT_TEST_MYSQL_HOST`，以及可选的 `PAYMENT_TEST_MYSQL_PORT`、`PAYMENT_TEST_MYSQL_USER`、`PAYMENT_TEST_MYSQL_PASSWORD`。测试自动创建并删除 `payment_consistency_test_<UUID>` 独立数据库，需要该测试账号具备建库权限，不访问现有业务表。覆盖事务回滚、提交可见性、重复关单、补扫 Worker 新实例处理过期订单、订单恢复任务迁移幂等、双 Mapper 并发领取/租约 token fencing/失败退避与 20 轮并发竞争。原始秒杀表 DDL 的零日期默认值仅在临时测试表中替换为当前时间，以兼容严格 SQL 模式。
同时设置 `ROCKETMQ_TEST_NAMESRV` 时，`PaymentConsistencyIntegrationTest` 还会通过独立 MQ 的唯一 Topic 投递两条相同订单的延迟消息，验证真实消息到监听器后只关单、回补数据库库存和创建补偿任务各一次。请确保测试 Broker 有足够可用磁盘空间；测试不接触现有业务库。
同时设置 `PAYMENT_TEST_REDIS_HOST`（可选 `PAYMENT_TEST_REDIS_PORT`）时，该测试还会用随机券与订单 ID 验证数据库补偿任务经 `OrderStockSyncWorker` 调用 Redis Lua 后只回补一次，并删除本测试创建的 Key。此项会使用业务格式的 Redis Key，必须指向独立测试 Redis；若随机 Key 已存在，测试会在写入前失败且不清理该 Key。
同一 Redis 环境变量也启用 `SeckillActivityWindowRedisTest`：使用随机券 ID、订单 ID 和恢复队列成员验证活动时间边界及 Lua 原子写入恢复副本；清理时只删除本测试随机生成的 Key 与 ZSet 成员，不写数据库。
同时设置 MySQL、RocketMQ 和 Redis 三组测试环境变量时，还会执行联合用例：两条重复延迟消息经 MQ 触发关单，随后扫描补偿任务并执行 Redis Lua，核对数据库与 Redis 均只回补一次。该测试手动注册测试消费者，仍不覆盖 Spring 容器自动注册与重启补扫。
AI 任务数据库验证使用 `AiTaskConsistencyIntegrationTest`：设置 `AI_TASK_TEST_MYSQL_HOST`，以及可选的 `AI_TASK_TEST_MYSQL_PORT`、`AI_TASK_TEST_MYSQL_USER`、`AI_TASK_TEST_MYSQL_PASSWORD`。测试只创建并删除 `ai_task_test_<UUID>` 临时库，覆盖待处理扫描、领取竞争、租约过期恢复和回复与任务完成状态的事务回滚。

真实 Redis 验证使用 `OrderStockReleaseIntegrationTest`：设置 `PAYMENT_TEST_REDIS_HOST`，以及可选的 `PAYMENT_TEST_REDIS_PORT`、`PAYMENT_TEST_REDIS_PASSWORD`。建议使用独立 Redis；测试仅操作 `test:payment:<UUID>:` 前缀，覆盖 32 次并发回补、重复任务与再次抢购、缓存缺失及错误类型。未提供相应环境变量时，这两个外部集成测试默认不执行。

真实 RocketMQ 投递验证使用 `SeckillRocketMqIntegrationTest`：设置 `ROCKETMQ_TEST_NAMESRV` 指向独立测试 NameServer。测试创建唯一 Topic 和消费组，将创建消息经 Broker 投递到项目秒杀监听器，并验证其调用下单服务；另以最短延迟级别验证超时消息到达关单监听器。测试不写业务数据库，也不验证 Spring 容器自动注册消费者。

店铺逻辑缓存、跨实例 AI SSE 和会话串行验证使用 `ShopLogicalCacheRedisTest`、`RedisChatSseServiceTest`、`RedisConversationExecutionCoordinatorTest`：设置 `CACHE_TEST_REDIS_HOST`，可选设置 `CACHE_TEST_REDIS_PORT` 和 `CACHE_TEST_REDIS_DATABASE`（默认数据库 0）。建议使用独立 Redis 和空数据库；店铺缓存测试只操作随机 UUID 的缓存键与锁键，SSE 测试会向 `cityai:chat:sse` 频道发布随机会话 ID 的测试事件。未设置主机时测试自动跳过。

订单超时补扫分布式锁验证使用 `OrderTimeoutRecoveryWorkerRedisTest`：设置 `ORDER_RECOVERY_TEST_REDIS_HOST`，以及可选的 `ORDER_RECOVERY_TEST_REDIS_PORT`、`ORDER_RECOVERY_TEST_REDIS_DATABASE`（默认 15）和 `ORDER_RECOVERY_TEST_REDIS_PASSWORD`。测试通过两个独立 Redisson 客户端和随机锁名验证竞争实例跳过扫描、释放后接手，只操作随机锁键；未设置主机时自动跳过。

滑动窗口限流使用 Redis 服务端时间执行原子 ZSet 窗口。AOP 通过固定的 `RateLimit.lua` 和 Spring Data `DefaultRedisScript` 执行，Redis 正常时使用脚本 SHA，遇到 `NOSCRIPT` 再回退加载脚本文本，避免每个请求都传输动态拼接的 Lua 源码。真实 Redis 验证使用 `SlidingWindowLimitRedisTest`：设置 `RATE_LIMIT_TEST_REDIS_HOST`，可选设置 `RATE_LIMIT_TEST_REDIS_PORT` 和 `RATE_LIMIT_TEST_REDIS_DATABASE`（默认数据库 0）。测试只操作并清理带随机后缀的限流键；未设置主机时自动跳过。

## 说明

项目的基础本地生活业务参考了经典点评类实战项目，并在此基础上完成了缓存、限流、消息队列、订单状态机和 AI Agent 模块的重构与扩展。项目目前以学习、实践和技术交流为主要用途。
