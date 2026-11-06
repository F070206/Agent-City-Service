# 微服务部署

本项目可按四个后端进程和一个网关运行。对外接口路径保持不变，网关仍监听 `8083`。

| 进程 | 端口 | 数据库 | 所有权 |
| --- | ---: | --- | --- |
| user | 8081 | `hmdp_user` | 用户、用户资料、登录 |
| content | 8082 | `hmdp_content` | 店铺、分类、笔记、关注、上传 |
| orders | 8084 | `hmdp_order` | 优惠券、订单、支付、库存补偿 |
| ai | 8085 | `hmdp_ai` | 会话、消息、向量索引 |
| gateway | 8083 | 无 | Spring Cloud Gateway 静态前端与 HTTP/SSE 路由 |

Compose 中 Nacos 控制台仅绑定宿主机 `127.0.0.1:8848`，供本机管理；业务容器通过 Compose 内部网络访问 `nacos:8848`，Nacos RPC 端口不发布到宿主机。文件上传和删除接口都要求登录；删除还需证明图片文件名属于当前用户的博客，避免匿名或跨用户操作共享图片卷中的文件。

四个业务服务在 Nacos 注册。内容服务通过 OpenFeign 和 LoadBalancer 查询用户资料；AI 服务通过相同方式查询店铺和笔记，内部只读接口继续校验共享令牌。Spring Cloud Gateway 按服务名路由，不转发 `/internal/**`，并在入口拒绝路径中的 `..` 段及编码的点、斜杠和反斜杠，避免业务路径归一化后落到内部接口；后端端口也不对宿主机发布。所有后端共享 Redis 中的登录态；AI 实例还通过 Redis Pub/Sub 转发 SSE 事件，使聊天请求和流连接分配到不同实例时仍能收到增量回答。单体模式使用 JVM 公平锁，微服务模式使用 Redis 公平锁，串行执行同一会话的 Agent 任务。订单服务独占订单相关 MQ 消费者和补偿任务。店铺与优惠券的布隆过滤器分别由内容、订单服务预热。

Gateway 对后端连接超时默认 2 秒，可通过 `GATEWAY_CONNECT_TIMEOUT_MS` 调整；响应超时保持 120 秒，以容纳较长的 AI SSE 响应。Gateway 未配置请求重试过滤器，避免在后端已处理但响应未返回时自动重放写请求。

网关对登录、用户、内容、订单和 AI 路由分别配置 Redis 令牌桶。有效登录 Token 从 `login:token:<token>` 读取用户 ID；Gateway 会在单个 JVM 内缓存有效 token 到用户 ID 的映射 2 秒，最多 10,000 项，减少重复 HGET；无效 Token 不缓存。该缓存只用于生成限流键，后端仍独立校验登录态，Redis 令牌桶仍是跨网关实例共享的配额来源。匿名请求按客户端 IP 限流，业务路径用于区分资源。多个网关实例使用同一 Redis 配额，超过配额返回 HTTP 429。持续速率和突发容量可通过 `GATEWAY_AUTH_RATE/BURST`、`GATEWAY_USER_RATE/BURST`、`GATEWAY_CONTENT_RATE/BURST`、`GATEWAY_ORDER_RATE/BURST`、`GATEWAY_AI_RATE/BURST` 调整；微服务 profile 下跳过 Controller 类级通用 AOP 限流，避免与 Gateway 入口配额叠加，方法级 AOP 仍保护秒杀、AI 等成本较高的业务操作；单体模式保留全部 AOP 限流。网关前置反向代理时，通过 `GATEWAY_TRUSTED_PROXIES` 配置可信代理来源 CIDR（逗号分隔）；只有 TCP 对端属于该列表时才解析 `X-Forwarded-For`，并从右向左跳过可信代理地址。反向代理须覆盖或追加真实客户端地址；非可信对端的转发头会被忽略，格式错误时回退到 TCP 对端地址。该配置默认为空，直连部署继续使用 TCP 对端地址。方法级 AOP 限流依赖 Redis；配额耗尽返回 429，Redis 检查失败返回 503 并拒绝进入业务方法，不会绕过限流继续放行。

通过 `deploy/compose.yaml` 部署时，以上五组 `GATEWAY_*_RATE/BURST` 均会传入主网关和 HA 副本；未设置时使用与 Gateway YAML 一致的默认值。
业务服务 Actuator 管理端点绑定到容器/主机回环地址 `127.0.0.1:8086`，与业务监听端口分离；Compose 不发布该端口，网关也不转发 Actuator 路径。单体运行时可在本机访问管理端点；容器部署时可通过 `docker exec` 在目标容器内查询。店铺缓存计数器路径为 `/actuator/metrics/cityai.shop.cache.requests`；缓存重建池的活动线程、队列积压、剩余队列容量、当前线程数和拒绝计数分别见 `/actuator/metrics/cityai.shop.cache.rebuild.active`、`cityai.shop.cache.rebuild.queued`、`cityai.shop.cache.rebuild.queue.remaining`、`cityai.shop.cache.rebuild.pool.size`、`cityai.shop.cache.refresh.rejections`。线程数、队列容量和空闲回收秒数可通过 `SHOP_CACHE_REBUILD_THREADS`、`SHOP_CACHE_REBUILD_QUEUE_CAPACITY`、`SHOP_CACHE_REBUILD_KEEP_ALIVE_SECONDS` 调整。订单库存补偿积压可通过 `/actuator/metrics/cityai.order.stock.sync.pending` 和 `/actuator/metrics/cityai.order.stock.sync.oldest.age` 查看；持续非零积压或最老任务年龄持续增加表示回补可能被 Redis 数据缺失、MySQL 故障等阻塞。
登录与验证码请求始终按连接 IP 使用独立的 `user-auth` 限流键，即使请求携带有效 Token 也不会切换到用户配额；普通 `/user/**` 请求仍按用户或匿名 IP 限流。

## 数据准备

新安装时，在 MySQL 中依次执行 `src/main/resources/db/micro-user.sql`、`micro-content.sql`、`micro-order.sql`、`micro-ai.sql`。这些脚本只用于**新库**；不要对已有业务库执行旧版 `hmdp.sql`，其中含删表语句。

已有内容库在部署笔记 Feed 持久化投递前，执行一次 `src/main/resources/db/blog-feed-dispatch.sql`。新库脚本已创建投递表。笔记和粉丝投递任务在同一数据库事务写入；发布端按 `(user_id,id)` 游标每次读取最多 500 条关注关系，使用 `(follow_user_id,user_id)` 复合索引逐页投递，限制高粉丝作者的单次内存占用。内容服务 worker 每秒最多领取 500 条任务，通过 Redis pipeline 写入 Feed，再用两条批量 SQL 分别确认成功与安排失败重试，避免每个粉丝对应一次数据库往返。Redis Lua 原子写入笔记并裁剪最旧 Feed 项，每个用户默认保留最近 1,000 条，可通过 `BLOG_FEED_MAX_ENTRIES` 调整。数据库租约允许多实例竞争，失败任务 30 秒后重试。Redis 写成功但进程在确认数据库任务前退出时，重试的 ZADD 使用相同笔记 ID，保持幂等。确认成功的任务行会按领取 token 删除；worker 每轮还会清理最多 500 条此前版本留下的 `delivered=1` 记录，避免已完成 outbox 长期累积。内容服务提供 `cityai.blog.feed.dispatch.pending`、`cityai.blog.feed.dispatch.oldest.age`、`cityai.blog.feed.dispatch.repeated.failures` 三项指标；持续积压可通过这些指标观察，并可查询 `tb_blog_feed_dispatch` 中 `delivered=0` 的记录与 `attempts`。真实 Redis 验证可设置 `BLOG_FEED_TEST_REDIS_HOST`（可选 `BLOG_FEED_TEST_REDIS_PORT`、`BLOG_FEED_TEST_REDIS_PASSWORD`）运行 `BlogFeedRetentionRedisTest`，测试只操作随机生成的 `test:blog:feed:<UUID>` 键。

已有单体库 `hmdp` 或微服务内容库 `hmdp_content` 若店铺坐标列仍为 `DOUBLE UNSIGNED`，先备份，再执行一次 `src/main/resources/db/shop-coordinate-signed.sql`。新库初始化脚本已使用有符号 `DOUBLE`，与应用允许的经度 -180 到 180、纬度 -90 到 90 一致；已有正坐标不会改变。

已有单体部署迁移时，先停写并备份 `hmdp`，创建上述四个库，然后按表组用 `mysqldump` 导出并导入目标库：

```text
hmdp_user:    tb_user tb_user_info
hmdp_content: tb_shop tb_shop_type tb_blog tb_blog_comments tb_follow
hmdp_order:   tb_voucher tb_seckill_voucher tb_voucher_order tb_order_stock_sync
hmdp_ai:      tb_ai_conversation tb_ai_message tb_ai_task（若旧库已安装任务表）
```

先核对各目标库表行数、订单状态和库存，再启动新服务。迁移窗口内不要同时运行单体与微服务写入同一批数据。数据库账号应分别授予所属库权限，服务间不应直接访问对方数据库。
若旧库尚无 `tb_order_stock_sync`，在 `hmdp_order` 执行 `src/main/resources/db/order-stock-sync.sql` 创建该表；若任务表已存在但没有 `claim_token`、`claim_until` 列，则先执行一次 `src/main/resources/db/order-stock-sync-lease.sql`。新库脚本包含十分钟领取租约结构；多个订单实例通过原子领取分担待同步任务，实例退出后租约到期可恢复领取。
已有订单库若尚无秒杀投递表，执行 `src/main/resources/db/seckill-order-dispatch.sql` 创建完整表；若表已存在但没有租约列，再执行 `src/main/resources/db/seckill-order-dispatch-lease.sql`；若还缺少超时投递列，执行 `src/main/resources/db/seckill-order-dispatch-timeout.sql`。新库脚本已包含完整表结构。秒杀 Lua 会把成功预扣的消息写入 Redis 恢复队列，请求线程随后同步写 MySQL 投递表，确认持久记录后才返回受理成功；若写入失败或结果不确定且无法确认记录，接口返回“处理中”及订单 ID，Redis 恢复副本保留供 worker 重试。订单服务 worker 默认每 250 毫秒读取最多 500 条恢复记录，批量查重并补写缺少的数据库投递任务，确认数据库记录存在后才清理 Redis 恢复副本，随后由持久化任务投递 RocketMQ。成功响应后的 Redis 副本丢失不影响数据库任务重试；MySQL 记录提交前退出时请求不会报告成功，仍可由 Redis 恢复记录补建 outbox。15 分钟超时时间与下单任务一同持久化，到期后后台 worker 发送普通关单消息，失败后按 30 秒间隔重试。超时迁移把已有行标记为已发送，防止重复调度，已有超时订单仍由数据库补扫 worker 处理。多个订单实例通过十分钟数据库租约竞争领取待投任务，领取实例退出后，租约到期即可重新领取。需监控 Redis `ZCARD seckill:dispatch:recovery`；Redis 与 MySQL 不构成跨系统强一致事务，Redis 整体丢失时仍需暂停秒杀并核对尚未持久化的请求。
部署版本化活动缓存代码前，已有订单库还需执行一次 `src/main/resources/db/seckill-activity-version.sql`；新库脚本已包含 `activity_version`。活动更新在数据库事务内递增版本，Redis 使用 Lua 仅接受不旧于当前缓存版本的数据，刷新重试会重新读取数据库最新状态。
已有订单库部署秒杀消费者防重代码前，先暂停订单写入，按 `src/main/resources/db/seckill-order-guard.sql` 中的查询检查未取消秒杀订单是否存在同一用户+券的重复记录；先人工处理重复，再执行该脚本回填占用状态。消费者使用该表防止不同订单 ID 的重复消息建立多个有效秒杀单，超时取消在同一事务内释放占用；普通券订单不受影响。新库脚本已包含占用表。
已有订单库还需执行 `src/main/resources/db/order-timeout-recovery.sql`，将单列状态索引替换为 `(status, create_time)` 复合索引，并执行 `src/main/resources/db/order-timeout-retry.sql` 创建超时关单失败重试表；新库脚本已包含所需结构。订单服务每次补扫最多读取 10 页、每页 100 条超时订单，默认补扫间隔为 30 秒；可通过 `ORDER_TIMEOUT_RECOVERY_MAX_BATCHES_PER_RUN` 调整每轮页数。扫描关单异常会持久记录订单 ID，按 30 秒间隔重试；通过十分钟 MySQL 租约多实例领取，实例退出后到期可接管。常规扫描仍使用非阻塞 Redisson 锁；Redis 锁不可用时会告警并继续扫描，依靠订单状态条件更新维持幂等。
已有 AI 库还需执行 `src/main/resources/db/ai-task.sql` 创建任务表；新库脚本已包含该表。用户消息与完整异步任务在同一事务内保存，AI 服务会补扫未执行或租约过期的任务。
若此前已创建过不含 `claim_token` 列的 `tb_ai_task`，再执行 `src/main/resources/db/ai-task-claim-token.sql`。每次领取使用独立标识，旧执行在租约被重新领取后不能写入最终回复。
已有 AI 任务表还需执行 `src/main/resources/db/ai-task-pending-recovery-index.sql`，为待处理任务的创建时间恢复扫描添加索引；新库脚本已包含该索引。
已有 AI 任务表还需执行 `src/main/resources/db/ai-task-conversation-order-index.sql` 添加会话顺序索引。任务领取会等待同一会话中更早的消息完成，当前任务结束后立即唤醒下一条可执行消息，避免并发请求因异步调度顺序不同而颠倒推理上下文。
迁移前已写入、但尚未回答的旧消息没有任务记录，需要单独核对；补扫只覆盖新代码创建的任务。
若旧部署使用了本地图片目录，还需把 `imgs` 文件复制到 Compose 的 `uploads` 卷，否则已有笔记图片无法显示。

已有内容库部署热门笔记排序优化时，执行一次 `src/main/resources/db/blog-liked-ranking-index.sql`，为 `liked DESC, id DESC` 分页排序建立复合索引；新库初始化脚本已包含该索引。

已有内容库部署关注关系查询优化时，执行一次 `src/main/resources/db/follow-lookup-indexes.sql`，为本人关注关系点查及作者粉丝列表/笔记推送分别建立双向复合索引；新库初始化脚本已包含这两个非唯一索引。迁移不改变现有关系数据或去重语义。

已有订单库部署店铺优惠券列表索引优化时，执行一次 `src/main/resources/db/voucher-shop-list-index.sql`，为 `shop_id/status` 过滤建立复合索引；新库初始化脚本已包含该索引。

## 启动

先启动 MySQL、Redis、RocketMQ、Milvus 和模型服务；Compose 会启动 Nacos、四个业务服务及独立网关。`deploy/compose.yaml` 的后端默认通过 `host.docker.internal` 访问外部基础设施；可通过 `MYSQL_HOST`、`MYSQL_PORT`、`REDIS_HOST` 和 `REDIS_PORT` 调整 MySQL 与 Redis 的地址和端口，其中 Compose 默认端口为 `3306` 和 `6379`。RocketMQ 地址通过 `ROCKETMQ_NAME_SERVER` 配置，Milvus 地址和端口通过 `SPRING_AI_MILVUS_HOST`、`SPRING_AI_MILVUS_PORT` 配置（默认 `19530`）。这些基础设施需要允许来自 Docker 容器网络的连接；仅绑定宿主机 `127.0.0.1` 时容器无法访问。

微服务显式启用 Spring Boot liveness/readiness 探针；四个业务服务的 readiness 同时检查启动状态、数据库和 Redis，Gateway readiness 检查启动状态、Redis 和 Nacos Naming 服务，管理端点都绑定在容器回环地址 `127.0.0.1:8086`。Compose 为 Nacos 检查 readiness，为业务服务和 Gateway 检查各自的 `/actuator/health/readiness`。订单服务的 Redis 秒杀库存预热在单例初始化完成阶段执行，早于 WebServer 启动和 Nacos 注册，避免新实例在库存 key 建立前被发现路由。业务服务只有在 Nacos 健康后启动；内容服务还等待用户服务健康，AI 服务等待内容服务健康，网关等待所有业务服务健康。`depends_on: service_healthy` 用于启动时门控；容器启动后依赖故障会反映为 unhealthy 状态，但 Docker restart policy 不会仅因健康检查失败而自动重启进程，运行期恢复仍依赖应用重试及部署监控。健康检查使用镜像内的 Java HTTP 探针，业务镜像无需额外安装命令行工具。

订单的创建时间与超时补扫使用 JVM 本地时间；Compose 将四个业务服务的 JVM 时区固定为 `Asia/Shanghai`，与 JDBC URL 的时区设置一致。自行启动进程时也需设置相同的 `-Duser.timezone=Asia/Shanghai`，并核对 MySQL 的时区配置。

设置 `SPRING_DATASOURCE_PASSWORD`、`SPRING_AI_OPENAI_API_KEY` 和随机生成的 `CITYAI_INTERNAL_TOKEN`，再运行。默认情况下四个服务共用 `SPRING_DATASOURCE_USERNAME/PASSWORD`，以兼容现有部署；生产部署建议分别设置 `USER_DB_USERNAME/PASSWORD`、`CONTENT_DB_USERNAME/PASSWORD`、`ORDER_DB_USERNAME/PASSWORD` 和 `AI_DB_USERNAME/PASSWORD`，并为各账号只授予对应 schema 的权限。Compose 会将四组凭据分别传给 user、content、orders 和 ai 服务；orders 的 HA 副本沿用订单账号。通过公开网关新增/更新店铺、创建/更新优惠券或触发 AI 向量索引重建时，还需设置独立的 `CITYAI_ADMIN_TOKEN`；这些管理操作必须携带 `X-Admin-Token`，未配置时默认拒绝：

```bash
docker compose -f deploy/compose.yaml build
docker compose -f deploy/compose.yaml up -d
```

需要运行第二个订单实例时，使用 `docker compose -f deploy/compose.yaml --profile order-ha up -d`。该配置启动 `orders-replica-2`，其机器编号默认为 1；默认订单实例编号为 0。若设置 `SNOWFLAKE_WORKER_ID` 或 `ORDERS_REPLICA_2_WORKER_ID`，必须确保两者不同；两个实例共用 `SNOWFLAKE_DATACENTER_ID`。不要使用 `--scale orders=2`，Compose 会把相同的环境变量复制给所有副本，造成订单 ID 冲突。扩展更多订单实例时，必须为每个实例分配不重复的 `(datacenter-id, worker-id)` 组合。

需要运行第二个网关实例时，使用 `docker compose -f deploy/compose.yaml --profile gateway-ha up -d`。第二实例默认发布到宿主机 `8089`（容器内仍为 `8083`），可通过 `GATEWAY_REPLICA_2_PORT` 修改。外部负载均衡器应同时转发到宿主机 `8083` 和 `8089`；两个网关使用相同的 Redis 主机和密码，因此 `RequestRateLimiter` 在实例间共享配额。不要使用 `--scale gateway=2`，固定的宿主机端口会冲突。

前端访问 `http://localhost:8083`。前端文件从 Gateway 镜像内 `/app/static/` 读取；上传图片保存在 Compose 的 `uploads` 卷，由网关只读挂载到 `/app/uploads/` 并作为第二个静态资源位置，因此 `/blogs/...` 可读取卷内 `blogs/...` 文件，前端更新也不会被持久卷遮住。单体模式默认使用项目目录下的 `uploads`，Spring Boot 同时从该目录提供 `/blogs/...` 静态文件；可通过 `CITYAI_UPLOAD_DIR` 更换目录，存储与静态资源根目录会一起切换。单张上传图片最大为 8 MiB。生产环境需对上传卷和四个数据库单独备份。`CITYAI_INTERNAL_TOKEN` 必须在用户、内容、AI 服务中一致，且不要放入仓库。
Compose 中 AI 索引初始化启用失败即退出，容器会在内容服务或 Milvus 尚未就绪时重启并重试。多个 AI 实例通过 Redis 锁串行检查和补建共享 Milvus 索引，锁内再次检查索引状态，避免空集合启动时并发全量写入；关闭向量库时各实例独立构建进程内索引。本地开发默认 `AI_AGENT_BOOTSTRAP_FAIL_FAST=false`，索引初始化失败会记录错误并以降级模式启动；知识库查询可使用关键词 fallback，店铺与笔记语义检索在索引可用前不会返回向量结果。

向量维度不匹配时，应用默认拒绝删除现有 collection。确认已备份并准备重新构建索引后，才可同时将 `SPRING_AI_MILVUS_INITIALIZE_SCHEMA=true` 与 `AI_AGENT_RAG_ALLOW_DESTRUCTIVE_COLLECTION_RESET=true` 显式开启；否则保留原数据并按 AI bootstrap fail-fast 设置启动失败或进入降级模式。

网关路由集成测试可设置 `GATEWAY_TEST_REDIS_HOST` 和可选的 `GATEWAY_TEST_REDIS_PORT` 后运行 `mvn -f gateway/pom.xml -Dtest=GatewayRouteIntegrationTest test`。测试使用本地模拟服务和真实 Redis，验证四类路由、内容服务的多实例负载均衡、SSE 透传及 `/internal/**` 不可从网关访问；它关闭 Nacos，不能替代完整的注册发现联调。

也可用相同源码在不同 JVM 中运行 `com.hmdp.micro.MicroserviceApplication`，并为每个进程设置 `CITYAI_ROLE=user|content|order|ai`、`NACOS_SERVER_ADDR`、数据库连接和对应 `application-micro-*.yaml` 所需变量。网关由 `gateway/pom.xml` 单独构建运行。原有 `com.hmdp.HmDianPingApplication` 保留单体本地启动方式。

## 运行限制

AI 的 SSE 连接仍保存在进程内，每个连接最多保持 120 秒（前端流等待上限为 75 秒，断流后会转为数据库轮询），避免失联连接无限占用请求资源；Redis Pub/Sub 只负责把事件广播给持有连接的实例。Pub/Sub 不提供离线重放；客户端断线期间的增量片段可能丢失，最终回答由数据库消息和现有轮询逻辑补取。Redis 公平锁防止同一会话任务在不同实例上重叠执行，但不能保证多条并发提交的消息一定按数据库消息 ID 顺序取得锁。AI 任务线程池拒绝新任务时会保存忙碌提示；补扫任务先在数据库领取，再进入线程池，并在排队或等待会话锁期间续租，队列拒绝时释放租约供后续补扫。Actuator 暴露每个 AI 实例的活动线程数、队列长度与余量、线程池大小和拒绝次数：`cityai.ai.agent.executor.active`、`cityai.ai.agent.executor.queued`、`cityai.ai.agent.executor.queue.remaining`、`cityai.ai.agent.executor.pool.size`、`cityai.ai.agent.executor.rejected`。进程异常退出后租约到期可重新领取；任务进入线程池前启动租约续期，并在排队、等待会话锁及执行期间每分钟续租；数据库持续不可用导致续租失败时，租约仍可能过期并触发重复执行，完成回复由领取标识阻止旧执行提交。模型思考和 MCP 工具调用的 Sentinel 熔断规则与状态都由各 AI JVM 本地维护；多实例需保持阈值配置一致，但各实例根据自己的调用统计独立进入熔断和恢复探测，进程重启会重置本实例的统计状态。AI 全量索引按 ID 分批读取店铺和笔记，每批最多 200 条；数据持续增长后仍应考虑事件驱动增量索引。秒杀投递任务与超时订单补扫任务由订单服务运行，需监控未完成任务与持续失败日志。

订单库存同步任务保存在 `hmdp_order.tb_order_stock_sync`。订单服务每 30 秒刷新未完成任务数 `cityai.order.stock.sync.pending`、最老任务年龄秒 `cityai.order.stock.sync.oldest.age` 和失败次数达到阈值（默认 3）的未完成任务数 `cityai.order.stock.sync.repeated.failures`，可从订单实例的 Actuator metrics 端点查看并据此设置积压告警；失败阈值可通过 `order.stock-sync.metrics.repeated-failure-threshold` 调整。Redis 库存键缺失时，订单服务会在没有未完成下单 outbox、该券未完成关单库存同步任务和 Redis 恢复消息的条件下，按 MySQL 库存重建，并从 `tb_seckill_order_guard` 恢复有效用户占用映射；若已有 Redis 映射与数据库不一致则拒绝重建。关单 Lua 只有在用户到订单 ID 的 Redis 映射与任务订单 ID 一致时才回补库存；映射缺失时拒绝猜测归属，任务按 30 秒间隔重试并计入积压/重复失败指标。若任务持续失败或 Redis 库存/占用键缺失，应暂停该券新请求，核对任务、订单终态及 Redis 占用后人工对账，避免把已写回 MySQL、尚未同步到 Redis 的回补库存重复计入。任务租约到期后可由其他实例接管；若无法确定库存基线，应保留任务并人工对账。
秒杀 outbox 每 30 秒刷新六项低基数数据库 metrics：订单投递的 `cityai.seckill.dispatch.orders.pending`、`cityai.seckill.dispatch.orders.oldest.age`、`cityai.seckill.dispatch.orders.repeated.failures`，以及到期关单投递的 `cityai.seckill.dispatch.timeouts.pending`、`cityai.seckill.dispatch.timeouts.oldest.age`、`cityai.seckill.dispatch.timeouts.repeated.failures`。Redis 恢复队列另有 `cityai.seckill.dispatch.redis.recovery.pending`，通过 `ZCARD seckill:dispatch:recovery` 观测尚未写入数据库 outbox 的受理请求。timeout 指标只计入已经到期且尚未确认发送的任务；重复失败阈值默认为 3，可通过 `order.dispatch.metrics.repeated-failure-threshold` 调整。数据库与 Redis 的读取异常分别保留对应的上次快照，避免监控端点瞬时归零。
