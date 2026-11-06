# 项目优化进度与下一步验证

2026-11-06 逻辑过期缓存锁异常时保留可用旧值：对照项目缓存治理要求检查 `CacheClient.readLogicalValue`，新增回归模拟 Redis 缓存读取成功、过期值可反序列化但随后 `SET NX` 获取重建锁抛错；旧逻辑把请求失败，未利用已读到的逻辑过期数据。现在仅锁获取异常路径记录错误指标和告警并返回该旧值，不执行同步回源；下一请求仍可尝试刷新。`CacheClientTest` 15 项、`ShopCacheTest` 9 项、`ShopCacheRebuildExecutorTest` 2 项通过，0 失败/错误/跳过；`git diff --check` 通过。未使用真实 Redis 故障注入。

2026-11-06 订单支付/关单竞态离线复核：复查支付创建和回调的未支付条件更新、秒杀 15 分钟截止条件，以及关单事务内订单状态、数据库库存回补和库存同步任务写入；当前未发现新的确定性缺陷。离线运行 `PaymentServiceImplTest` 10 项、`OrderTimeoutRecoveryWorkerTest` 6 项、`OrderStockSyncWorkerTest` 7 项，全部通过。`PaymentConsistencyIntegrationTest` 31 项因本机未设置 `PAYMENT_TEST_MYSQL_HOST` 全部跳过，真实 MySQL 的支付/关单竞争本轮未验证；`git diff --check` 通过。

2026-11-06 秒杀受理与恢复链路复核：按项目描述逐段检查 `seckill.lua`、`VoucherOrderServiceImpl`、数据库 outbox worker 和库存恢复组件。确认 Redis Lua 在写入前校验活动时间、库存及相关键类型，原子预占时写用户占用映射和恢复副本；成功响应前将受理凭据写入 MySQL，worker 以租约重投，消费者用数据库唯一占用保护和条件扣库。当前代码审查未发现新的确定性库存错误。运行 `VoucherOrderIdTest` 11 项、`SeckillStockRecoveryTest` 6 项、`SeckillOrderDispatchWorkerTest` 10 项、`SeckillOrderDispatchMetricsTest` 2 项，全部通过（0 失败/错误/跳过）；其中恢复与 worker 用例使用 mock，不计作真实 Redis/RocketMQ 故障联调。`git diff --check` 通过。

2026-11-06 MCP 调用中断被错误降级：按项目描述复核 AI 外部依赖降级链路，新增本地 HTTP 延迟服务回归用例，在请求等待时中断调用线程；旧逻辑将 `InterruptedException` 当普通远端错误返回本地路线建议，且清除了线程中断标记，测试失败。现对异常原因链识别线程中断，恢复中断标记并终止该调用，不伪装成一次正常兜底回答；超时、远端错误和 Sentinel 熔断仍使用原本地降级。`LocalLifeMcpCircuitTest` 6 项、`LocalLifeMcpSseTest` 1 项、`AiDependencyGuardTest` 5 项、`AiDependencyCircuitRulesTest` 1 项通过，0 失败/错误/跳过；`git diff --check` 通过。未连接真实 MCP 服务。

2026-11-06 个人中心笔记入口标签与内容不符：确认统计卡标题为“我的评论”，打开后却渲染 `/blog/of/me` 的本人笔记；弹窗空状态也写评论，导致用户对功能含义产生误解。将统计卡、弹窗及空状态统一命名为“我的笔记”，内部 dialog key 同步改为 `blogs`，不改后端接口或计数行为。前端完整 `npm test` 15 项通过，`npm run build` 成功（Sass legacy JS API 弃用提示），`git diff --check` 通过。

2026-11-06 发布笔记后列表刷新竞态：沿“发布成功→loadProfile→loadMyBlogs”调用链确认，`loadMyBlogs` 在已有请求期间直接返回；若用户在个人中心初次加载未完成时发布，成功后刷新会被跳过，旧请求可能在随后覆盖列表。改为每次笔记列表请求分配序号，仅最新请求可更新数据、错误和 loading 状态；发布成功后直接刷新笔记列表，不重复加载订单等无关数据。前端完整 `npm test` 15 项通过，`npm run build` 成功（Sass legacy JS API 弃用提示）。未做浏览器和真实后端并发请求验证。

2026-11-06 签到请求失败缺少用户反馈：检查个人中心签到按钮调用链，确认 store 正确向上抛出接口错误，但页面处理函数未捕获，网络/Redis 异常会成为未处理事件且没有可见提示；按钮也没有提交中状态可防止重复点击。现在按钮展示 loading 并在提交中禁用，处理函数捕获异常显示具体错误或“签到失败，请稍后重试”，成功后仍按原逻辑刷新连续签到天数。前端完整 `npm test` 15 项通过，`npm run build` 成功（Sass legacy JS API 弃用提示）。此交互尚未通过浏览器真实后端故障注入验证。

2026-11-06 登录回滚遇到 401 时的无效 token 恢复：复核请求拦截器可在 401 时删除 token，而上一轮登录回滚 catch 会在任何登出异常后重新保存签发 token；用 `/user/logout` 返回 401 的回归测试复现旧逻辑保留无效 token。现登出成功或返回 401 均清理本地认证，只有网络/非 401 错误才保留 token 以便在撤销状态不确定时重试。`user-auth.test.mjs` 4 项通过；前端完整 `npm test` 15 项通过，`npm run build` 成功（Sass legacy JS API 弃用提示），`git diff --check` 通过。未对真实网络 401/超时做端到端注入。

2026-11-06 个人中心订单分页丢失前页：复核 `ProfilePage.vue` 的首屏和“加载更多”调用，确认每次都用当前页响应重建远端订单列表，因此第 2 页响应会替换第 1 页，刷新第 1 页时旧远端页还会被错误保留。先新增两条纯函数回归用例：翻页保留前页和本地订单；刷新首屏移除旧远端页。首次运行其中刷新用例失败，验证了旧页误保留。现抽出 `mergeOrderPage`，翻页时累积已同步订单，首屏刷新时替换远端页并保留本地订单；页面翻页入口显式启用累积。前端完整 `npm test` 14 项通过，`npm run build` 成功（Sass legacy JS API 弃用提示），`git diff --check` 通过。未连真实后端验证多页数据。

2026-11-06 登录初始化失败时的服务端会话保留：复核前一轮登录回滚逻辑与后端 `/user/logout`，确认登出接口会删除当前 Redis token，但网络失败时客户端无法确认服务端是否收到请求；直接清除本地 token 可能让仍有效的服务端会话无法再操作。新增回归用例模拟登出异常，旧实现因本地 token 已清空而失败。现初始化失败仍显示登录未完成并清空用户资料；服务端登出成功才清除 token，登出失败保留 token 供重试，原始资料加载错误继续抛出。`user-auth.test.mjs` 3 项、前端完整 `npm test` 12 项通过；`npm run build` 成功（Sass legacy JS API 弃用提示），`git diff --check` 通过。未对真实 Redis/HTTP 超时做故障注入。

2026-11-06 前端登录资料初始化失败：通过 `userStore.login`、`fetchMe` 和登录弹窗调用链确认，登录接口返回 token 后若 `/user/me` 异常，旧逻辑虽向界面报错但保留 token；若资料为空则仍加载访客数据并关闭登录弹窗。新增两条回归用例，旧实现分别因 token 未清理、空资料未拒绝而失败。现仅在资料有效且用户状态加载成功后关闭弹窗；其间异常会尝试调用登出接口撤销服务端 token，并清理本地认证状态后继续抛出原错误。`user-auth.test.mjs` 2 项、前端完整 `npm test` 11 项通过；`npm run build` 成功（存在 Sass legacy JS API 弃用提示），`git diff --check` 通过。未做真实后端网络故障注入。

2026-11-05 AI 断流轮询可能复用旧回答：先加回归并在旧实现上复现，当前轮发送前的历史消息查询失败被当成空数组，断流后轮询因此用助手 ID 0 找到并返回上一轮回答。现历史查询失败会在发送本轮消息前中止，交由现有聊天错误/离线降级处理，不会用不可靠游标发消息或轮询。新增用例验证查询失败时不发送 chat 且不进入轮询；旧实现下测试返回旧回答并失败，修复后 `ai-stream.test.mjs` 8 项通过。前端 `npm test` 9 项全部通过，`npm run build` 通过（Sass legacy JS API 弃用提示），`git diff --check` 无空白错误。未连接真实模型或模拟 HTTP 断流网络。

2026-11-05 关单重试遇到 Redis 占用身份映射丢失：先新增真实 Redis 回归并在旧实现上复现，释放 Lua 在 `seckill:order:reservation:<voucherId>` hash 不存在时仍把用户集合成员视为可释放占用，测试观察到旧任务将库存从 0 增至 1。现脚本在释放标记已存在时继续幂等完成；否则要求 hash 中用户映射精确等于关单任务的订单 ID，映射缺失返回 `-3`、映射指向新订单时安全跳过，避免误增库存/移除新占用。worker 对 `-3` 保持任务未完成、计失败并 30 秒后重试，由现有积压指标暴露。`OrderStockReleaseIntegrationTest` 6 项真实 Redis 和 `OrderStockSyncWorkerTest` 7 项通过，0 失败/错误/跳过；DB15 为 0 键，`git diff --check` 无空白错误。映射缺失无法仅凭 Redis 推断归属，仍需要人工核对后恢复，不宣称自动修复。

2026-11-05 秒杀库存键及占用映射安全重建：原逻辑在存在历史订单时永久拒绝补热；直接复制 MySQL 库存则会丢 Redis 一人一单占用，导致重复用户消息消费时把库存多加回来。新增恢复组件先确认无未完成下单 outbox、该券无未完成库存同步任务且 Redis 恢复队列为空，再读取 MySQL 当前活动占用，校验现存 Redis 映射和用户集合后补齐用户占用，最后才以 `SET NX` 发布 MySQL 库存。启动预热和请求时补热共用该逻辑；任务未排空或占用不一致时继续 fail-closed。新增单测覆盖映射重建、未完成任务阻止、未知用户/冲突映射拒绝及 Redis 写失败时不发布库存；真实 MySQL + Redis DB15 集成测试从空 Redis 键恢复一笔有效订单占用及库存。`SeckillStockRecoveryTest` 6 项、`SeckillStockInitializerTest` 4 项、`SeckillStockWarmupTest` 5 项、`VoucherOrderIdTest` 11 项全部通过；完整 `PaymentConsistencyIntegrationTest` 31 项中 28 项通过、3 项因未配置 RocketMQ 跳过。Redis DB15 为 0 键、随机 MySQL 测试库数量为 0，`git diff --check` 无空白错误。未完成关单库存同步时仍需人工对账，尚未对多实例并发恢复或 Redis 部分写入故障做压力注入。

2026-11-05 秒杀受理先持久化恢复凭据：复现并确认原流程在 Redis Lua 成功后即返回受理，MySQL outbox 由后台稍后写入；此窗口 Redis 整体丢失会让已报告成功的请求没有数据库恢复依据。现请求线程先将订单消息及 15 分钟关单期限写入现有 MySQL outbox，数据库写入结果不确定时按订单 ID 回查；只有确认记录存在才返回成功，无法确认则返回带订单号的处理中结果并保留 Redis 恢复副本，异步 MQ 下单流程不变。新增单测覆盖持久化成功、写入失败、提交确认丢失；真实 MySQL + Redis DB15 集成用例删除成功响应后的该订单 Redis 副本，再由数据库 payload 完成下单，验证库存从 1 扣至 0 且 outbox 完成。`VoucherOrderIdTest` 11 项、`SeckillStockWarmupTest` 5 项及 `PaymentConsistencyIntegrationTest#acceptedSeckillOrderCanRecoverFromMySqlAfterItsRedisCopyIsLost` 1 项曾分别通过；Redis DB15 测试后为 0 键，随机 MySQL 测试库无残留。该用例模拟单笔 Redis 恢复副本丢失，不等同于整库故障注入；后续库存键及占用映射重建见上一条记录。

2026-11-05 库存对账可恢复边界审查：沿秒杀 HTTP 返回、Redis `seckill.lua` 预占、`seckill:dispatch:recovery` 恢复队列及后台写 `tb_seckill_order_dispatch` 的调用链确认，服务在 Redis Lua 成功后即可返回受理，而 MySQL outbox 由后台异步补写。若 Redis 在 Lua 接受后、outbox 落库前整体丢失，这笔已受理请求不会出现在 MySQL；仅用 `tb_seckill_voucher.stock` 重建会丢单且可能多放库存。已持久化订单关单则在同一 MySQL 事务回补 DB 库存并建 `tb_order_stock_sync` 任务，DB 基线可在相关任务排空后反映持久化订单，但不能证明上述窗口中是否有 Redis-only 受理。未新增自动写库存逻辑；安全对账前需先把受理状态建立为可由 MySQL 恢复的持久边界，并验证故障窗口。

2026-11-05 AI 离线降级与检索回归：检查本机端口确认 Milvus、模型服务、MCP 均不可用，Docker Engine 也未运行；为避免向外部模型发起可能计费的请求，仅验证离线路径。`CommonConfigurationTest` 7 项验证向量存储关闭/异常时使用进程内 SimpleVectorStore 和 fail-fast 配置，`KnowledgeBaseInitializerTest` 4 项验证 bootstrap 开关及初始化失败策略，`AiRagRetrieverTest` 14 项验证向量无结果/异常时的本地关键词 fallback，合计 25 项通过、0 失败/错误/跳过。独立 `micro-ai` JVM readiness 联调未完成，不能以单测代替；真实 Milvus、模型和 MCP 服务仍待外部环境。

2026-11-05 订单支付/关单/库存补偿真实依赖联调：复核 `PaymentConsistencyIntegrationTest` 的 setup/cleanup 后，确认它只建删随机命名临时 MySQL 库；为其中两条真实 Redis 测试连接增加可选 `PAYMENT_TEST_REDIS_DATABASE`（默认 0），在 DB15 隔离运行。使用本机 MySQL、真实 Redis Lua 和单机 RocketMQ Broker（随机测试 topic，存储及日志位于 `target/order-it-mq-*` 临时目录），`PaymentConsistencyIntegrationTest` 29 项全部通过、0 失败/错误/跳过，覆盖支付与超时关单状态竞争、重复延迟消息消费、库存同步任务与 Redis 一次性回补、重复秒杀消息幂等及 SQL 迁移/领取恢复。测试结束后 MySQL 随机测试库查询无残留，Redis DB15 为 0 键，RocketMQ 进程已停止且 9876/10911 端口关闭。Docker 多容器网络与跨进程重启恢复仍待验证。

2026-11-05 微服务 Compose 构建前验证：Docker Engine 当前无法连接，未声称容器启动通过。先用临时非真实必填值分别执行默认与 `order-ha`/`gateway-ha` profile 的 `docker compose -f deploy/compose.yaml config --format json`，两种配置均成功展开；确认默认 6 个服务的启动依赖顺序，以及 HA profile 加入两个副本、订单副本 worker ID 为 1、网关副本映射 8089。按两个 Dockerfile 的命令分别执行 `mvn -q -DskipTests package dependency:copy-dependencies` 与 `mvn -q -f gateway/pom.xml -DskipTests package` 均成功；产物、健康探针 Java 源码、前端静态目录存在，微服务/网关业务端口 8083 与 readiness 端口 8086 配置吻合。仅验证 Compose 展开和 Maven 镜像构建阶段，不涵盖 Docker 镜像层、容器网络、Nacos 注册或依赖健康状态。

2026-11-05 限流与关单恢复 Redis 实测续验：在已运行的 WSL Redis 专用 DB15 重跑 `SlidingWindowLimitRedisTest` 和 `SlidingWindowLimitAspectTest`，共 8 项通过，覆盖真实 Lua 超额拒绝、切面错误 fail-closed；随后运行 `OrderTimeoutRecoveryWorkerRedisTest`，两个 Redisson 客户端争用恢复锁的用例通过，确认第一个实例持锁扫描期间另一个实例不启动扫描，锁释放后可接手。每次测试前后 DB15 均为空；`git diff --check` 通过。本轮未连接 MySQL/RocketMQ，未验证跨存储库存最终一致。

2026-11-05 秒杀 Redis Lua 真实集成验证：发现本机 Ubuntu 24.04 WSL 已运行 Redis 7.0.15；DB0 有既存键，因此未使用。为相关 Redis 集成测试增加可选 `PAYMENT_TEST_REDIS_DATABASE`（默认 0，不改变原配置），在事先为空的 DB15 中执行 `OrderStockReleaseIntegrationTest`、`SeckillActivityWindowRedisTest`、`SeckillDuplicateReservationRedisTest`、`SeckillActivityCacheRedisTest`、`SeckillOrderDispatchRecoveryRedisTest`，12 项全部通过；测试后 DB15 为 0 键。覆盖秒杀 Lua 活动窗口、重复预占恢复、订单库存释放与并发幂等、缓存版本写入、投递恢复队列。恢复队列测试有意构造异常消息并记录大量错误级日志，但断言和 Maven 结果通过。Docker Engine 仍不可用，Compose 全链路和 MySQL/MQ 联调待后续验证。

2026-11-05 前端测试命令与 Vite 端口冲突：新增统一 `npm test` 入口。复现 `node --test` 并行运行两个使用 Vite middleware server 的测试文件时共用默认 HMR WebSocket 端口，输出 `Port is already in use`；尝试关闭 HMR 后确认 Vite 在 middleware 模式仍会启动 WebSocket listener，因此改为 `node --test --test-concurrency=1`，避免测试之间争用固定端口。`npm test` 运行 8 项全部通过、0 跳过，输出无端口冲突；前端 README 已加入命令说明。

2026-11-05 上传图片文件头校验：先用扩展名 `.png`、请求 MIME `image/png` 但内容为脚本的回归请求验证，旧实现返回成功并写入文件。上传接口现额外核对 PNG/JPEG/GIF/WebP 文件签名，拒绝伪装格式且读取失败时拒绝写盘；成功上传测试样本更新为 PNG 签名。`UploadControllerSecurityTest` 全部 8 项通过，覆盖伪装图被拒和合法 PNG 签名仍上传；前端构建及后端/Gateway 全量测试在本轮均通过（后端 373 项、73 项按环境跳过；Gateway 21 项、5 项按环境跳过）；`git diff --check` 通过。校验只确认常见文件头，不解码完整图片、不做恶意载荷扫描，仍保留 8 MiB 服务端上传上限。

2026-11-05 关单补偿绑定当前秒杀订单：复核发现永久释放标记在 Redis allkeys 淘汰策略下可能丢失，旧关单任务迟到重试会把同用户后来建立的新占用当成旧占用删除并额外回补库存。秒杀 Lua 现为券级用户占用集合增加用户→当前订单 ID hash；关单回补脚本核对订单 ID，发现占用已属于新订单时将旧任务安全视为已完成，且重复消息修复数据库当前有效订单映射。扩展释放、重复拒单与 Worker 参数测试。定向测试中 52 项通过、0 失败/错误；Redis/MQ 条件集成用例按环境变量跳过，当前未接入 Redis 实例验证 Lua 实际执行。占用 hash 若与集合及释放标记同时被淘汰仍需库存对账，真实部署应监控缓存恢复和补偿失败任务。

2026-11-05 微服务 Feign 指标 Bean 隔离：角色扫描过滤器会排除未列入白名单的 client 组件；内容服务的 `RemoteUserDirectory` 与 AI 服务的 `RemoteContentCatalog` 均依赖 `InternalServiceMetrics`，但该组件此前未加入 content/ai 白名单，可能造成两个服务启动失败。现将该组件加入对应角色扫描集，并扩展 `MicroserviceIsolationTest` 验证 content/ai 包含、其他角色隔离。`MicroserviceIsolationTest` 9 项通过；新增受角色扫描过滤器约束的 Spring 容器验证，content 能构造 `RemoteUserDirectory`，AI 能构造 `RemoteContentCatalog`，两者均获得 `InternalServiceMetrics`；`git diff --check` 通过。仍未连接真实 Nacos、数据库和 Redis 完整启动服务。

2026-11-05 AI 模型空响应受控失败：确认 Agent Loop 虽捕获模型调用异常，但调用成功后在 Sentinel 范围外直接读取首条 generation；模型返回 null、空 choices 或空输出时会越过 Agent 错误状态处理，使同步推荐/店铺分析请求异常退出，且空 choices 被 Sentinel 计作成功。现将响应结构校验放入 Sentinel 调用范围，无可用结果时抛出受控依赖异常，复用通用用户提示。新增空 choices 回归；`ManualToolAgentLoopExecutorTest` 11 项、`AiDependencyGuardTest` 5 项通过，`git diff --check` 通过；未连接真实模型服务。

2026-11-05 秒杀券新增事务一致性：核对 addSeckillVoucher 发现库存键与活动元数据在数据库事务提交前写入 Redis；若后续数据库提交失败，Redis 留存的活动和库存可被 Lua 秒杀入口直接受理，即使数据库无对应券记录。现将 Redis 发布注册为事务 afterCommit 回调，事务回滚不写缓存；非事务同步场景保留原即时行为。新增 VoucherServiceActivityTest 覆盖提交前不写入、提交后写入库存与活动元数据。定向测试通过（3 项），git diff --check 通过；未做真实 Redis/MQ 联调。

2026-11-05 RAG 英文缩写大小写匹配：回归复现空向量结果下搜索小写 `svip` 无法命中知识文件中的 `SVIP`，关键词兜底原先逐字大小写敏感，导致平台会员知识丢失。检索打分现统一使用 `Locale.ROOT` 小写比较，并为空文本安全返回 0；新增类路径知识文件回归。`AiRagRetrieverTest` 14 项通过，覆盖检索、过滤、熔断兜底与大小写缩写；`git diff --check` 通过。

2026-11-05 MCP 空/不完整响应降级：新增回归复现外部 MCP 返回 HTTP 200 且 `data={}` 时，旧实现转换成默认 DTO 并回报 `degraded=false`，未给用户任何路线内容。现路线要求有摘要、距离或交通信息，天气要求有天气或用餐建议；空值/不完整 DTO 在 Sentinel 调用范围内作为依赖异常记录并走本地兜底。`LocalLifeMcpCircuitTest` 3 项、`LocalLifeMcpSseTest` 1 项和 `McpSseToolClientConcurrencyTest` 1 项通过，覆盖空响应两类工具、原熔断恢复和 MCP SSE；`git diff --check` 通过。未连接真实第三方 MCP 服务。

2026-11-05 秒杀活动时间跨 JVM 时区：新增回归先在 UTC JVM 下写入活动窗口，旧实现与上海业务时区期望相差 28,800,000 毫秒，确认 `ZoneId.systemDefault()` 会让跨时区部署的活动早/晚 8 小时。改为按项目 MySQL `serverTimezone=Asia/Shanghai` 与 Compose JVM 时区约定固定转换，真实 Redis 集成测试的期望也改为显式上海时区。`SeckillActivityCacheTest` 3 项、`VoucherServiceActivityTest` 2 项通过；Redis 相关集成测试 4 项因未设置测试 Redis 环境变量跳过；`git diff --check` 通过。未连接真实 Redis 验证 Lua 写入。

2026-11-05 店铺缓存重建池观测与配置：确认重建线程池虽有有界队列和拒绝计数，但看不到运行线程、排队积压与剩余容量，线程和队列也固定为代码常量。将生产线程池提取为 Spring 管理组件，默认仍为 10 线程/1000 队列/60 秒空闲回收，可用环境变量调整；新增 active、queued、queue.remaining、pool.size 四个低基数 gauge，保留原拒绝计数，Compose 将参数传给 content 服务。饱和状态及非法配置回归测试通过；`ShopCacheRebuildExecutorTest` 2 项、`CacheClientTest` 14 项、`ShopCacheTest` 9 项通过。Docker Compose 配置与全量测试尚待验证；未做性能调优或压测。

2026-11-04 秒杀受理热路径移除数据库读：确认每次 `seckillVoucher` 都先执行 `findForSeckill` 联表查询，再进入 Redis Lua，活动高峰仍把每个请求打到订单数据库。现将活动起止时间、券状态和类型作为 Redis hash 元数据，由秒杀券新增流程及启动预热写入，Lua 在同一原子脚本内校验元数据和库存；热路径不再查数据库，元数据缺失时才走安全检查和按需回源，库存键缺失仍保留已有订单/占用/待投递保护。更新参数签名、初始化与隔离白名单，并扩展活动时间与状态 Lua 测试。定向测试通过；全量 `mvn -q test` 共 283 项、0 失败/错误、65 项按环境条件跳过；`git diff --check` 通过。独立 Redis 未配置，Lua 实际脚本回归跳过；数据库手工修改活动状态/时间后需重启订单服务刷新缓存，后续应补管理更新的缓存失效路径并做 Redis 压测。

2026-11-04 微服务与 Gateway 依赖就绪门控：最小复现表明数据库健康指标 DOWN 时聚合 `/actuator/health` 返回 503，但未配置依赖项的 `/actuator/health/readiness` 仍返回 200，Compose 因此可能在 DB/Redis 尚不可用时启动下游服务。微服务 readiness 组现包含 `readinessState,db,redis`；Gateway 新增 Actuator readiness 并包含 `readinessState,redis`，Compose 网关探针改查回环管理端口 8086。两个随机端口 HTTP 测试分别模拟 DB/Redis DOWN→UP，验证 readiness 503→200，且管理路径在业务端口返回 404。根项目全量 `mvn -q test` 282 项、0 失败/错误、64 项按环境跳过；Gateway 全量测试 18 项、0 失败/错误、5 项因未配置 Redis 跳过；`mvn -q -f gateway/pom.xml -DskipTests package`、Compose JSON 展开与 `git diff --check` 通过。Docker Engine 仍连接超时，未做镜像构建或容器网络实测；运行期健康变为 unhealthy 后，Docker restart policy 不会单独因健康检查失败而重启进程。

2026-11-04 AI 线程池指标管理端点验证：上一步仅通过 `SimpleMeterRegistry` 直接读取指标，不能证明 Actuator 注册或管理/业务端口隔离正确。新增随机端口 Spring Boot HTTP 集成测试，导入 AI 异步配置并排除数据库、Redis、RocketMQ、Nacos、模型和 Milvus 自动配置；五个 AI 线程池指标都在 management metrics 端点返回 200，同一指标在业务端口返回 404。定向 `AiAgentTaskExecutorMetricsEndpointTest`、`AiAgentTaskExecutorMetricsTest`、`AiAsyncConfigurationTest` 通过；全量 `mvn -q test` 共 282 项、0 失败/错误、64 项按环境条件跳过；`git diff --check` 通过。验证覆盖 Spring Boot/Micrometer/Actuator 暴露和端口隔离，不等于完整 AI 微服务容器或真实依赖联调。

2026-11-04 AI 线程池积压可观测性：检查发现 AI Agent 专用线程池固定为 4 个核心线程、最多 8 个线程和 200 个排队任务，但此前 Actuator 未暴露其繁忙程度，拒绝计数也不可查询。为线程池增加活动线程、排队数、队列剩余容量、池大小四个 gauge，以及任务拒绝 counter；指标不含会话或用户标签。定向测试真实占满 8 个线程与 200 项队列并触发拒绝，确认各 gauge 与 counter；`AiAgentTaskExecutorMetricsTest`、`AiAsyncConfigurationTest` 通过。全量 `mvn -q test` 共 281 项、0 失败/错误、64 项按环境条件跳过；`git diff --check` 通过。README 和微服务说明记录指标名称及查询入口。未在完整 AI 容器启动时通过管理端口联调。

本清单对应项目描述中的五项能力。代码存在不等于完成部署验证；下列状态以当前仓库和本地已运行的定向测试为依据。

| 要求 | 当前实现证据 | 已验证范围 | 尚需证明 |
| --- | --- | --- | --- |
| 四服务拆分、Nacos、网关、Feign、共享登录态 | `deploy/compose.yaml`、`application-micro-*.yaml`、`gateway/`、`client/` | 网关使用模拟后端和真实 Redis 验证路由、SSE 与内部路径拦截；user/content 两个真实进程使用临时独立 MySQL 库和共享 Redis，通过 Feign 读取笔记作者；本地同版本 Nacos 2.3.2 的服务列表出现 `cityai-user` 与 `cityai-content`，不配置静态服务地址时 content 经 Feign 取得笔记作者、网关经 Nacos 路由 `/shop/1` 返回 200；两个真实 content 实例注册后，网关 26 次请求分别命中 13 次，停掉其中一个且 Nacos 只显示存活实例后，连续 10 次请求均返回 200；内部接口无令牌/错误令牌/正确令牌分别返回 400/403/200；user 验证码登录后，content 受保护接口由匿名 401 变为携带令牌的 200；真实内容服务经网关读取店铺详情 600 次全部成功；order 在临时 MySQL、Redis 与真实 RocketMQ 下独立启动，两个监听容器运行；2026-11-02 user/content/order/AI 四服务与 Gateway 同时使用四个隔离 MySQL 库、Redis、Nacos 2.3.2 和本地 RocketMQ broker 启动，四个服务在 Nacos 均为健康实例；验证码登录后的同一 Token 经网关访问 user、content、AI 均成功，content 的笔记详情返回 Feign 取得的作者资料，订单券列表路由成功；内部接口缺少/错误/正确令牌为 400/403/200，网关内部路径为 404；测试用服务、测试数据库和 Redis 测试凭据已清理 | Docker Compose 容器网络下的实例扩容与故障恢复联调 |
| 秒杀原子预扣、异步下单、幂等 | `seckill.lua`、`VoucherOrderServiceImpl`、`SeckillOrderDispatchWorker` | Redis 活动窗口、预扣及消息重试的定向测试；Spring 容器消费者收到两条相同消息后，临时 MySQL 库只有一条订单且库存仅扣一次（2→1）；重启订单进程再次投递相同消息后仍为一单、库存 1；HTTP 秒杀经 Redis 预扣、真实 Broker 异步落库后 MySQL/Redis 库存一致；64 个不同用户以最多 32 并发抢 20 张券，接口成功 20 次，最终 MySQL 20 单、库存 0，Redis 库存 0；Broker 中断时保留未完成投递任务、无数据库订单且数据库库存 2，恢复后任务完成、落库一单、MySQL/Redis 库存均为 1；2026-11-02 隔离库真实进程经 Gateway 调用秒杀接口，RocketMQ broker 起初因 C 盘 91% 使用率拒绝写入，数据库投递任务保留并重试；将仅用于测试的 broker 存储切到 D 盘后自动重投成功，最终订单仅一条、状态未支付、MySQL 库存 2→1、Redis 库存 2→1、用户占用存在、投递任务完成且 attempts=3、pending 标记清除；同用户重复请求被原子脚本拒绝且订单数和库存不变 | 持续高负载吞吐/P95 实测及长期故障后的库存对账 |
| 延迟关单与库存最终一致 | `PaymentServiceImpl`、`OrderStockSyncWorker`、`OrderTimeoutRecoveryWorker` | 真实 MySQL 支付与关单竞争；联合用例中两条重复延迟 MQ 消息触发关单，临时库和独立 Redis 均仅回补一次；Spring 容器自动注册的关单消费者收到两条相同消息后，订单状态为 4、MySQL/Redis 库存均只回补一次且同步任务完成；真实订单进程重启后，不投递超时消息，补扫独立关闭逾期订单，订单状态 4、MySQL/Redis 库存均从 0 回补为 1、同步任务完成；两个真实订单进程共用临时 MySQL/Redis/RocketMQ，10 笔支付回调与重复超时消息交错执行，5 笔支付、5 笔关单，每笔终态与 MySQL/Redis 库存、同步任务一致；真实进程下 Redis 库存键缺失时任务未完成、失败次数为 1，恢复已核对的基线并重启进程后任务完成且只回补一次；2026-11-02 `PaymentConsistencyIntegrationTest` 使用临时 MySQL、真实 Redis 与 RocketMQ 运行 13 项，0 失败/错误/跳过；其中重复延迟消息真实投递两次并经关单监听逻辑后只取消一次、创建一个库存同步任务，Worker 两次扫描后 Redis 库存只回补一次；支付回调、支付创建和关单并发竞争 20 轮，每笔均只到已支付或已取消终态 | 长期故障后的全量库存对账及更大并发量验证 |
| 缓存治理与分布式限流 | `CacheClient`、布隆初始化器、滑动窗口限流、网关 `RequestRateLimiter` | 真实 Redis 店铺逻辑缓存测试；24 个请求同时冷命中同一店铺时数据库回源仅 1 次；新增店铺提交前的并发空缓存可在提交后由数据库完整记录覆盖，迟到的空值不能覆盖正向缓存；`save` 返回失败时不再返回成功或发布布隆 ID；`POST /shop` 已改为调用该服务逻辑，真实进程创建店铺 15 后可立即读取且 Redis 有逻辑过期正向缓存；布隆过滤器仍使用共享 Redis 状态并保留预热失败时的启动失败语义，预热时点从 WebServer 启动后的 `ApplicationRunner` 前移至 WebServer 启动前的 `SmartInitializingSingleton`，避免预热期间对未加载 ID 产生误拒绝；通用预热器限定为单体 profile，防止 user/AI 微服务误加载其他服务 mapper 和 Bloom 依赖；`BloomFilterWarmupTest` 3 项与 `MicroserviceIsolationTest` 5 项通过；新增低基数 Micrometer 店铺缓存计数器 `cityai.shop.cache.requests`，分别观测 value hit、empty hit、miss、Bloom 拦截与错误，`CacheClientTest` 6 项和 `ShopCacheTest` 5 项通过；metrics 端点和压测前后计数差值的读取方式已写入压测方案；真实 Redis 集成测试 4 项已通过；2026-11-02 content 微服务进程关闭 Nacos 后使用本机 hmdp 只读数据启动，匿名 GET /shop/1 返回 200，/actuator/metrics/cityai.shop.cache.requests 的 COUNT 从 0 增至 1，result=value_hit 为 1；网关不转发 Actuator 路径；类级 AOP 限流原未执行，修复后真实 HTTP 请求在 Redis 留下限流键，方法级和类级双规则测试通过；配额耗尽返回 429，Redis 执行异常原先 fail-open 继续放行，现改为拒绝业务执行并返回 503，`SlidingWindowLimitAspectTest` 3 项与 `WebExceptionAdviceTest` 2 项通过；两个独立 JVM 网关进程同时访问认证接口时，一个返回 200、另一个返回 429；网关集成测试新增配额耗尽与恢复检查，连续认证请求返回 200/429，等待补充后返回 200，`GatewayRouteIntegrationTest` 5 项通过；本机模拟后端与真实 Redis 下，16 并发、每组 2 万请求，直连后端 8.9 秒/2240 RPS/P95 7.6ms，经网关 11.1 秒/1808 RPS/P95 12.0ms，两组非 200 均为 0（内容限流阈值临时设为每秒 10000）；真实 content 服务经网关 16 并发、600 请求耗时 0.9 秒/670 RPS/P95 35.5ms，600 次业务成功且期间店铺 DB 回源 0 次 | 更长时间的真实业务持续负载、缓存命中率和多实例性能实测 |
| AI Agent、RAG、MCP、SSE、熔断降级 | `ai/`、`RedisChatSseService`、`AiTaskRecoveryWorker`、Sentinel 配置 | 真实 MySQL AI 任务恢复与租约接管；2026-11-02 `AiTaskConsistencyIntegrationTest` 6 项在临时 MySQL 库通过，双 mapper 并发领取同一任务时恰好一个成功且 attempts 仅增加一次，另覆盖旧 token 失效、新 token 接管，以及 `AiTaskRecoveryWorker` 扫描过期租约、经真实 mapper 重领并由兜底路径事务完成任务；恢复用例在模型调用前注入确定性异常，未访问外部模型；单店笔记向量索引现按 ID 游标每批 200 条读取和写入；全量及单实体索引重建失败时尝试清理本轮残留，避免下次启动把部分索引误判为完整；新增内容内部分页接口使用真实 content JVM 和临时 MySQL 库验证，缺失/错误 token 分别返回 400/403，201 条笔记分页为 200+1 且 ID 边界连续；测试服务与库已清理；真实 Redis 跨实例 SSE 与会话串行，使用两条独立 Redis 客户端运行 `RedisChatSseServiceTest` 与 `RedisConversationExecutionCoordinatorTest`，两项通过；另以两个真实 AI JVM、隔离 MySQL 库和共享 Redis 启动服务，AI-A 创建会话、AI-B 建立 HTTP SSE 流后收到 Redis 发布的测试事件，两个 Redis 订阅者均在线，0 模型请求；测试库、登录键和服务进程已清理；模拟 MCP SSE 首次路线工具调用返回错误后本地降级、下次重新握手并成功调用远端，路线与天气共 3 次握手/3 次工具调用；本地 HTTP 工具故障后 Sentinel 熔断并返回本地路线建议，恢复窗口后重新调用远端并返回远端路线，相关 MCP/RAG 定向测试 10 项通过；`AiDependencyCircuitRulesTest` 与 `AiDependencyGuardTest` 共 4 项通过，确认模型思考、流式输出、路线和天气资源均加载慢调用率/异常率规则，窗口、请求数、恢复时间及阈值与配置一致，且熔断恢复后可重新调用；隔离 MySQL、Redis、Nacos 2.3.2、AI 与 Gateway 真实进程联调，匿名会话请求 401，登录态下经网关读取和创建会话均 200，创建后列表能读回，两条记录持久化在隔离 MySQL 库；`/internal/users/1` 经网关为 404；AI bootstrap 关闭且 Milvus 未启动；2026-11-02 `KnowledgeBaseInitializerTest` 4 项验证 bootstrap 禁用、成功、降级和 fail-fast 失败语义；`CommonConfigurationTest` 2 项、`AiVectorIndexPagingTest` 4 项、`AiRagRetrieverTest` 8 项全部通过（含向量库故障后关键词兜底、熔断开启期间仍返回本地知识），未调用外部模型 | 真实模型、Milvus 和外部 MCP 服务联调；开启 bootstrap 的 AI 服务启动与向量索引构建；真实 JVM 进程退出后的部署恢复验证及外部 MCP SSE 故障恢复 |

2026-11-02 秒杀多实例重投优化：确认多个订单实例会同时读取相同到期任务，消费者虽按订单 ID 幂等落库，但会产生重复 MQ 与消费者负载。现改为数据库原子领取最多 100 条任务，十分钟租约到期可恢复，发送结束后清租约并延后 30 秒重试；新库 DDL 与已有库迁移脚本已补充。`SeckillOrderDispatchWorkerTest` 2 项及 `PaymentConsistencyIntegrationTest` 14 项在隔离 MySQL 环境运行，全部失败/错误为 0，其中 3 项因未设置 Redis/MQ 环境变量跳过；并发竞争验证两条并发领取仅一条成功，租约释放后任务可再次领取；迁移验证覆盖旧表添加列与索引。

2026-11-02 秒杀用户+券持久化防重：确认消费者仅按订单 ID 去重，不同订单 ID 的同用户同券消息会分别插入订单并扣库存。新增 `tb_seckill_order_guard`，按用户+券原子预约，超时取消与释放在同一数据库事务内，普通券购买路径不使用该表；旧库迁移先查存量重复，遇到冲突时脚本插入失败，不静默挑单。`PaymentConsistencyIntegrationTest` 覆盖不同订单 ID 顺序及并发消费、取消后重购、迁移回填与冲突拒绝，并新增真实 RocketMQ 重复消息用例；本轮真实 MySQL 环境运行 19 项，0 失败/错误，4 项因未配置独立 Redis 或 RocketMQ 跳过。`SeckillOrderRetryTest` 1 项通过。真实 RocketMQ 用例尚未执行：本轮临时 Broker 启动命令被自动审核拒绝，命令未执行且未启动 Broker。

2026-11-02 AI 恢复队列去重：确认补扫会重复将相同前 100 条任务送入本机线程池，任务开始执行时才领取数据库租约，长队列期间重复占用执行队列。现在改为扫描后先原子领取，只有领取成功才入队，并在排队/等待同会话锁期间续租；执行器拒绝时释放租约供下轮重试。`AiTaskRecoveryWorkerTest`、`ChatMessageCreatedEventListenerRecoveryTest` 与 `AiTaskConsistencyIntegrationTest` 共 9 项使用临时 MySQL/单测环境运行，全部通过，覆盖恢复领取、单任务并发竞争、队列拒绝释放租约、旧租约接管和会话顺序。

2026-11-02 超时订单补扫去重：确认每个订单 JVM 各自维护分页游标并查询同一批超时订单，条件更新虽保证重复关单安全，但多实例会重复扫描并放大 MySQL 读取。补扫现在通过 Redisson 非阻塞锁只允许一个实例扫描；锁获取异常时保留数据库补扫作为降级路径。`OrderTimeoutRecoveryWorkerTest` 4 项通过，覆盖正常翻页、页数上限、竞争实例跳过及 Redis 不可用时仍扫描；另用本机 Redis DB 15 和两个独立 Redisson 客户端运行 `OrderTimeoutRecoveryWorkerRedisTest`，真实验证竞争实例跳过扫描、释放后接手，结束后只清理随机锁键。`PaymentConsistencyIntegrationTest` 14 项 0 失败、0 错误，3 项因未配置 Redis/MQ 跳过。

2026-11-02 网关反向代理限流 IP：确认匿名请求只读取 TCP 对端地址，前置代理时所有客户端会共享代理 IP 的令牌桶，且没有可信代理配置。新增默认空的 `GATEWAY_TRUSTED_PROXIES` CIDR 列表；仅对可信 TCP 对端解析转发链，从右向左跳过可信代理，忽略非可信来源的伪造头，格式错误回退 TCP 对端。`BusinessRateLimitConfigurationTest` 覆盖直连、可信代理链、非可信伪造头、错误头、多条头和 IPv6 CIDR；Gateway Compose 已传递该环境变量，`docker compose -f deploy/compose.yaml config --quiet` 解析通过；`mvn -f gateway/pom.xml test` 运行 9 项单元测试全部通过，Gateway Redis 集成测试 5 项因未设置独立 Redis 环境变量跳过。

2026-11-02 压测风险说明校准：复核代码确认支付回调与超时关单按同一订单状态条件更新竞争，关单及库存同步任务在同一数据库事务内提交，Redis 库存回补经 Lua 幂等重试；修正压测方案中将这些已实现机制误写为“仍存在缺口”的旧说明。订单表本身仍无用户+券唯一键；同日后续新增 `tb_seckill_order_guard`，对经过订单消费者的秒杀消息提供持久化用户+券防重，旧库需先迁移，直接写订单表不受该防护。

前端 AI 聊天原先启动 SSE 请求后立即发送聊天消息，存在订阅未建立时回答事件先发出的竞态。现在等待流连接收到 HTTP 响应再发送消息；连接失败或 8 秒内未就绪时沿用已有轮询路径。完成后清理连接和回复的超时计时器。`node --test test/ai-stream.test.mjs` 验证连接就绪前不发送、就绪后发送、连接失败时不发送，`npm run build` 已通过；该前端时序修复尚未完成真实浏览器与 AI 服务的端到端验证。

独立网关进程使用当前前端构建目录启动后，`/` 和 `/index.html` 返回 200 HTML，页面引用的 JS 与 CSS 均返回 200 且 MIME 类型正确，`/internal/users/1` 返回 404。此检查关闭 Nacos 且未启动四个业务服务，只证明静态资源托管和内部路径拒绝在本机生效。

真实 Nacos 双内容实例测试先复现网关故障：Nacos 已移除退出实例，网关仍因负载均衡缓存命中已停止端口，`GET /shop/1` 返回 500。关闭重复的 Spring Cloud LoadBalancer 服务列表缓存后，Nacos 客户端继续维护实例列表，网关双实例分流与单实例退出后连续访问均通过。网关和服务间调用共用配置均关闭这层缓存；重新构建后不加临时覆盖参数复测通过，网关 `GatewayRouteIntegrationTest` 与 `BusinessRateLimitConfigurationTest` 定向测试通过。

## 当前推进顺序

当前工作树已有五项能力的主要实现，并完成单元测试及部分真实依赖联调。下一阶段按以下顺序推进，先补正确性和部署证据，再做可选压测：

1. **Redis 秒杀与库存补偿实测**：已在隔离 DB15 验证活动窗口、重复请求、库存释放与并发幂等、缓存版本、投递恢复；测试后键清理通过。后续可补充 Redis 丢失/恢复故障注入，但不能仅凭 Redis 数据重建 MySQL 库存基线。
2. **微服务部署验证**：默认与 `order-ha`/`gateway-ha` Compose 配置展开、两个 Dockerfile 对应 Maven 打包阶段已通过；Docker Engine 当前不可连接。恢复后仍需真实构建镜像并检查健康就绪、Nacos 注册、Gateway 路由、上传图片静态读取和服务重启恢复。现有本机进程及双实例证据不能替代容器网络实测。
3. **订单与消息恢复验证**：已在本机隔离 MySQL、Redis DB15 与单机 RocketMQ 上运行 29 项集成用例，覆盖支付/关单竞争、重复消息及库存补偿；后续需要在容器网络和进程重启场景下复验，并注入 Redis 故障以验证失败任务恢复。
4. **AI 外部依赖验证**：离线降级、关键词检索与 bootstrap 定向测试已通过；本机没有 Milvus、模型或 MCP 服务，且完整 micro-ai JVM readiness 联调未完成。后续获得安全测试环境后再验证真实 Milvus/MCP 恢复；模型联调需避免未授权的计费调用。
5. **按需补库存对账能力**：已确认 Redis Lua 受理到 MySQL outbox 异步落库之间存在 SQL 不可见窗口，整体 Redis 丢失时不能从 DB 单独推出完整受理集合。先把秒杀受理状态改为有 MySQL 持久恢复凭据，再做可重复执行且不会重复加库存的重建，并对 Lua 后、outbox 前故障注入验证。

在外部依赖不可用期间，继续做可本地复现的业务边界检查；不把跳过的集成测试记为通过。

## 历史优化记录（按日期倒序）

2026-11-02 RAG 数据保护：确认 Milvus collection 维度不匹配时原逻辑会因默认 `initialize-schema=true` 自动删除已有 collection。现新增默认关闭的 `AI_AGENT_RAG_ALLOW_DESTRUCTIVE_COLLECTION_RESET`，只有该开关与 schema 初始化同时开启才会删除；默认路径保留原向量并按 fail-fast 策略报错或降级。`CommonConfigurationTest` 覆盖默认保护、显式授权和 schema 初始化关闭三种情况；相关配置已写入 README/部署说明，真实 Milvus 尚未接入。

2026-11-02 缓存重建过载治理：确认逻辑过期重建使用无界固定线程池，已改为 10 个守护工作线程和 1000 个任务有界队列，空闲线程 60 秒后退出。队列拒绝时释放已持有的重建锁、记录 `cityai.shop.cache.refresh.rejections`，并继续返回旧缓存；`CacheClientTest` 覆盖拒绝行为，`CacheClientTest`、`ShopCacheTest`、`ShopLogicalCacheRedisTest` 合计 16 项在隔离 Redis DB 15 全部通过，DB 测后为空。该测试以拒绝执行器验证满队列分支；真实大量不同店铺过期时的排队压力仍需负载测试测量。

2026-11-02 缓存指标验证：`ShopLogicalCacheRedisTest` 在本机 Redis 7.0.15 的独立 DB 15 运行 4 项全部通过，测试结束后该 DB 键数为 0。测试现支持 `CACHE_TEST_REDIS_DATABASE`，可将集成测试隔离到指定逻辑库；DB 0 中已有的业务测试键未被触碰。Micrometer 计数器单测通过；metrics HTTP 端点尚未用真实服务联调。随后以关闭 Nacos 的真实 content 微服务进程直连只读本机 hmdp 库和专用 Redis DB 15，预热后对 `/shop/1` 以 16 并发、目标 800 RPS 连续请求 60 秒：48,000 次请求，实际 799.9 RPS，HTTP/网络错误均为 0，响应业务字段均成功（ID JSON 序列化为字符串）；P50 1.81ms、P95 3.19ms、P99 3.94ms、P99.9 10.26ms。Micrometer 计数增量为 value_hit 48,005、miss 1、empty_hit/bloom_reject/error 均 0。此结果仅代表本机单 content 进程直连、热店铺缓存场景，不覆盖网关、并发冷缓存或多实例；压测结束后已停止进程并清理 DB 15 中本轮产生的全部三个键。

2026-11-02 Lettuce 连接池配置生效：确认自定义 `RedisConfig` 未把 `application.yaml` 中的 max-active/max-idle/min-idle 参数传给 Lettuce 工厂；现显式启用池并读取 Spring Boot 已绑定的 `RedisProperties` 配置，测试验证实际工厂的池参数为 10/10/1，禁用开关时保持默认非池化客户端。`RedisConfigTest` 2 项通过。该修复使池配置与工厂行为一致，不代表普通共享连接命令会因此提升吞吐，仍需按压测计划实测。

2026-11-02 限流决策 fail-closed：复核发现 Redis Lua 决策意外返回 `null` 或非 0/1 值时，AOP 原逻辑会按“未限流”放行业务。现在仅接受明确的 0（放行）或 1（拒绝），其他结果转换为限流服务不可用并返回 503；`SlidingWindowLimitAspectTest` 5 项和 `WebExceptionAdviceTest` 2 项通过，覆盖正常放行、真实超限、Redis 异常和空决策拒绝；真实 Redis DB 15 上 `SlidingWindowLimitRedisTest` 1 项通过，验证首个请求放行、窗口内第二个请求被拒绝，随机测试键已清理。

2026-11-02 RAG 索引启动与检索解耦：确认 `AI_AGENT_BOOTSTRAP_ENABLED=false` 原先会直接切换到空的进程内向量库，使已存在的 Milvus 索引也无法读取。现新增默认启用的 `AI_AGENT_RAG_VECTOR_STORE_ENABLED` 独立控制检索；只关闭 bootstrap 时连接已有集合并禁用建库、schema 初始化和集合删除，集合不存在则按 fail-fast 设置失败或回退空库；显式关闭向量检索才使用本地空库。README 和本机压测覆盖配置已同步。`CommonConfigurationTest` 7 项、`KnowledgeBaseInitializerTest` 4 项、`AiRagRetrieverTest` 8 项通过；本机 Milvus 未启动，本次未验证真实集合读写。

2026-11-02 Agent 工具白名单回归：检查确认模型仅收到 `AgentLoopToolRegistry` 注册的回调，Spring AI 自动工具执行关闭，手动执行遇到未注册名称会在调用任何工具前中止；最大轮数取 `AI_AGENT_MULTI_AGENT_MAX_STEPS` 并限制至少 1 轮。新增未知工具零副作用回归用例，`ManualToolAgentLoopExecutorTest` 5 项通过，包含 3 轮上限用例；本轮验证发现原行为符合预期，无需改生产逻辑。

2026-11-02 AI 任务租约排队续期：确认任务完成后立即唤醒下一条待处理消息时，原逻辑会等到获取会话锁后才领取并启动租约；当该消息在同会话锁前等待超过 30 秒，恢复 worker 可先领取并重复提交。现改为普通提交和无 token 的恢复提交均在入队前原子领取并启动租约续期；线程池拒绝时先释放领取，再走原忙碌回复事务，带领取 token 的恢复任务仍保留原有拒绝重试路径。新增会话锁等待期间续租、领取拒绝、线程池满载、续租失效和失租不写兜底的回归覆盖；`ChatMessageCreatedEventListenerRecoveryTest` 3 项、`ChatTaskClaimTest` 3 项、`ChatMessageOverloadTest` 1 项、`AiTaskRecoveryWorkerTest` 1 项、`AiTaskLeaseKeeperTest` 2 项、`ManualToolAgentLoopExecutorTest` 6 项及 `ChatMessageStreamCircuitTest` 2 项共 18 项通过。真实 MySQL `AiTaskConsistencyIntegrationTest` 仍需设置 `AI_TASK_TEST_MYSQL_HOST` 后验证。

2026-11-02 AI 任务 MySQL 恢复复核：使用本机隔离测试数据库重新运行 `AiTaskConsistencyIntegrationTest`，7 项全部通过、无跳过，覆盖旧 token、续租、任务顺序、并发领取及事务回滚；运行结束确认 `ai_task_test_*` 临时库已删除。复测先发现恢复用例将租约句柄 mock 为 `null`，导致用例在会话记忆异常之前以空指针失败但仍触发兜底完成；现替换为有效租约 mock 后，日志显示预期的“stop before model call”注入异常，测试走完恢复与提交断言。README 与微服务说明同步注明所有入队任务在排队、等待会话锁及执行期间续租。

2026-11-02 AI 租约续期调度并发：确认最多 8 个 Agent 工作线程及 200 个排队任务都会在领取后各自注册每分钟 MySQL 续租任务，而调度器原只有 1 个线程；单次慢查询会阻塞所有其他任务的心跳，租约并发到期时也串行写库。将专用续租调度池扩至 4 个线程，限制对数据库的额外并发同时隔离单个慢调用；`AiAsyncConfigurationTest` 除检查核心线程容量及线程名前缀外，还阻塞一个定时任务并确认另一个任务可独立完成。`AiAsyncConfigurationTest`、`AiTaskLeaseKeeperTest`、恢复/拒绝/会话锁等待测试、Agent loop 和 SSE circuit 定向测试共 19 项通过。若数据库调用同时耗尽全部 4 个线程，续租仍会受影响；这部分需要后续按数据库连接池与真实负载测量。

### 初期计划与环境背景（2026-11-02 历史记录）

1. 准备可重复的测试环境：MySQL 四库、Redis、Nacos、RocketMQ、Milvus、测试模型及 MCP 服务。记录版本、端口和独立测试数据；不要在生产数据上运行初始化脚本。
2. 启动四个服务和网关，逐一验证注册发现、Feign 鉴权和超时、登录态共享、两实例路由以及 SSE 跨实例转发。
3. 用真实 RocketMQ 执行秒杀、重复消息、支付与关单竞争、重启补扫和 MySQL/Redis 库存对账。
4. 验证缓存预热、击穿与限流场景，再以独立负载工具记录吞吐、P95、错误率和缓存命中率；目标数值在实测前不记为结果。
5. 秒杀成功受理持久化及无待处理任务时的 Redis 库存/用户占用重建已实现并通过隔离集成验证；下一步验证未完成关单补偿与部分 Redis 丢失的人工/自动恢复边界，再继续其他项目问题。
6. 接入真实模型、Milvus 和 MCP，验证工具白名单、最大步数、关键词降级、SSE 完成事件、熔断及恢复。

当前本机 MySQL 常驻可用；Redis、RocketMQ 与 Nacos 2.3.2 可临时启动用于隔离验证。2026-11-02 Docker Desktop 启动日志确认初始化 Inference manager 时无法移除其运行目录中的 `dockerInference` 套接字；此前 Nacos 镜像两次拉取均因 Docker Hub TLS 握手超时失败，后改用同版本官方独立发布包完成 user/content/gateway 的真实注册发现联调。AI 隔离启动验证发现：关闭 `ai.agent.bootstrap.enabled` 原本仍会因向量库 Bean 创建时同步连接 Milvus 而阻止服务启动。现已改为禁用引导时使用进程内向量库、启用时懒加载 Milvus 并按 fail-fast 配置决定是否回退，并在 AI profile 排除 Milvus 自动配置；`CommonConfigurationTest` 定向用例通过。之后从本机私有启动配置读取数据库凭据，在独立 AI 临时库完成 AI 真实进程与 Nacos/Gateway 联调：匿名请求返回 401，带 Redis 登录态的会话列表读取、创建和再次读取均成功；Milvus 未启动且 bootstrap 关闭。测试进程、登录态和隔离数据库已清理。真实模型、Milvus 与 MCP 仍未接入。服务角色隔离与内部令牌定向测试 `MicroserviceIsolationTest` 4 项通过。

2026-11-02 秒杀端到端测试使用单独随机 MySQL 库、随机登录 Token、独立 voucher/user ID 和本地 RocketMQ；订单服务、Gateway、Nacos、RocketMQ 进程已停止，隔离库及 Redis 测试键已清理。C 盘 broker 初次拒绝消息时报告磁盘占用约 91%，切换到 D 盘临时 broker 存储后重投成功。D 盘 `D:\cityai-order-smoke-c616d5e4` 仍保留约 2.5 GB 的测试 broker 文件；删除操作此前被本机自动审批拦截，本轮未重试该删除。

2026-11-02 尝试启动 Docker Desktop 4.82.0 以恢复 Milvus 环境；Docker 后端因 `C:\Users\房殿灿\AppData\Local\Docker\run\dockerInference` 文件无法访问而退出，未清理该运行文件。Docker Desktop 进程已停止，Milvus 19530 端口未监听。AI 回归测试仅覆盖 mock/本地向量存储与本地 fallback，不证明真实模型、Milvus 持久化或付费 embedding 链路；本轮未调用模型服务。

2026-11-01 的全量 `mvn test` 运行 139 项，9 项上下文错误：首个 AI 测试因 Milvus 连接超时失败，另一个单体上下文因未提供 OpenAI API Key 失败，其余为 Spring 测试上下文失败阈值触发的后续错误。全量测试需提供隔离的 Milvus 和测试模型配置后重跑；这次结果不能作为全量通过的证据。


2026-11-02 Feign 超时与 Nacos 恢复验证：新增 src/test/java/com/hmdp/client/OpenFeignTimeoutTest.java，从 application-micro.yaml 读取并断言连接/读取超时分别为 2000/10000 毫秒，再通过实际 OpenFeign 调用延迟本地 HTTP 服务，验证覆盖配置后的 250 毫秒读取超时能触发超时异常；定向测试 1 项通过。使用隔离临时副本的 Nacos 2.3.2 和真实 Nacos Java 客户端注册测试服务，停止并重启 Nacos 后客户端重新查询到健康实例，正常注销；项目内静态配置未改。临时 Nacos 已停止、8848 端口已关闭；自动审核拦截了临时目录递归删除，目录仍留在 %LOCALAPPDATA%\Temp\cityai-nacos-it-607af77965e447929d7526c0ac52d2aa。
2026-11-02 AI 流式模型总时限：复核发现 Reactor 的 timeout(Duration) 会在每个数据块到达后重新计时，因此配置的流式超时只约束空闲间隔，持续缓慢输出可能无限延长任务。改为在流订阅开始时建立绝对截止时间，并在每个数据块后继续等待同一绝对截止点；慢速数据块不会重置总时限，超时会取消流订阅并进入部分内容补全路径。新增每 1.5 秒返回数据块、但整段超过 5 秒的回归用例，验证整段截止后发送 stream_recover 状态并补全本地候选答案；ChatMessageStreamCircuitTest 3 项通过，git diff --check 无空白错误。
2026-11-02 Compose 启动就绪门控：确认 deploy/compose.yaml 原先只用短格式 depends_on，Compose 仅保证依赖容器已启动，不等待 Nacos 或业务服务可响应。新增 Java 标准库 HTTP 健康探针，业务容器检查各自 /actuator/health，网关检查根路径，Nacos 检查 readiness；业务依赖改为 service_healthy，Nacos 与微服务设置启动宽限期。新增 HttpHealthcheckTest 验证 2xx、503、404 和连接失败，2 项通过；JDK 17 单独编译网关镜像中的探针类成功；填入仅用于解析的临时环境变量后，docker compose config --format json 成功，并检查六个服务都有健康检查、五个服务的依赖条件均为 service_healthy。Docker Desktop 引擎当前未运行，未执行镜像构建或真实容器启动；依赖文档已更新。
2026-11-02 Compose 健康探针真实 Nacos 验证：Docker 引擎仍不可用，未构建容器；改用先前隔离的官方 Nacos 2.3.2 副本启动进程，readiness 接口返回 200，项目 Java HttpHealthcheck 对该真实接口返回成功。验证进程已停止，8848 端口确认释放。AI 租约调度器复核未发现足以支持改动的真实故障证据：租约为 5 分钟、每分钟续租，4 个续期线程用于隔离单个慢 DB 请求；全线程耗尽情形需在真实数据库负载下测量，本步未更改该逻辑。
2026-11-02 Spring 服务 Nacos 故障恢复联调：使用两个随机隔离 MySQL 数据库（只将新库脚本导入随机库名）、专用临时 Redis、Nacos 2.3.2 和真实 user/content/Gateway JVM。先确认两个实例在 Nacos 健康，经 Gateway 的 /blog/hot 返回 4 条笔记且作者昵称由 Feign 查询。仅重启 Nacos、保留三个业务 JVM 后，16 秒内 user/content 自动重注册为健康实例，Gateway 路由与 Feign 作者查询恢复；验证期间未发业务写请求。停止了四个测试 JVM 和专用 Redis，确认 8081/8082/8083/8848/16439 端口关闭；两个随机 MySQL 测试库已核实并删除。隔离 Nacos 副本和本次日志仍保留在本机 Temp 目录。首次启动遗漏 SPRING_DATASOURCE_URL：内容服务因默认库 hmdp_content 不存在而退出；用户服务的 /actuator/health 检查连接到了默认 hmdp_user，仅做健康检查读取、未调用业务接口或执行写操作。发现后立即停止，并在重试时显式设置随机隔离数据库 URL。
2026-11-02 双 content 实例 Nacos 故障恢复：新建随机用户/内容 MySQL 库、独立 Redis 16439，启动真实 user(8081)、content-1(8082)、content-2(8086)、Gateway(8083) 和 Nacos 2.3.2。Nacos 注册两个健康 content 实例；经 Gateway 的 40 次 Feign 只读请求全部成功，服务端 http.server.requests 计数为 21/20（其中包含之前的一次基线请求）。仅重启 Nacos，保留四个业务 JVM；17 秒后 Nacos 再次列出 8082/8086 两个健康实例，恢复检查后的 Gateway 40/40 请求成功，两个实例计数均继续增加至 41（计数含恢复探测请求）。user、双 content、Gateway、Nacos 均已停止，随机数据库已删除，独立 Redis 已关闭，相关应用端口确认释放。初次启动时 SERVER_PORT=8086 意外继承给 Gateway，导致一次启动冲突；发现时尚未发业务请求，修正后完成上述验证。四服务全部多实例及长时间 Nacos 中断仍待验证。

2026-11-02 Compose 订单多实例发号编号：确认 Compose 原先允许 --scale orders=2，但所有副本继承相同 SNOWFLAKE_WORKER_ID/SNOWFLAKE_DATACENTER_ID，与 Snowflake 必须使用不同节点组合的约束冲突，会在相同毫秒和序列号时生成重复订单 ID。新增默认关闭的 order-ha profile，提供显式 orders-replica-2 服务，默认使用 (datacenter=0, worker=1)，主实例默认 (0,0)；保留主实例编号覆盖，并提供 ORDERS_REPLICA_2_WORKER_ID，文档要求覆盖时保持两者不同。docker compose config --format json 验证默认配置不含副本、profile 配置含两个订单实例且编号组合不同；SnowflakeIdWorkerTest 全部通过。Docker 引擎不可用，未进行真实容器启动。四服务全面多实例及长时间故障恢复仍待联调。
2026-11-02 四业务服务双实例 Nacos 恢复联调：使用四个随机隔离 MySQL 库、预先确认为空的 Redis DB 15、真实 Nacos 2.3.2、RocketMQ broker、8 个 Spring 服务 JVM 和 Gateway。user/content/order/AI 各两个实例均在 Nacos 健康注册；经 Gateway 的 40 次 `/blog/hot` 全部成功，content 两实例各处理 20 次，Feign `/internal/users` 两个 user 实例各处理 20 次。user-1 写验证码、user-2 登录后，同一 Token 在 user-1、Gateway 和 content 受保护接口均通过；AI 会话列表 8/8 成功且两实例各 4 次，订单优惠券列表 8/8 成功且两实例各 4 次。停止 Nacos 约 30 秒期间，经 Gateway 的 30/30 笔记读取均成功；重启 Nacos 后 8 个业务实例及 Gateway 全部重新注册健康，Gateway/Feign 读取恢复。模型调用与 Milvus 检索关闭，未调用外部模型或执行订单写入。测试后四个临时库已删除、Redis DB 15 从 7 个键清为 0，所有测试应用、Nacos 与 RocketMQ 端口关闭；Docker Desktop 仍不可用，未验证容器网络。仅用于本次 broker 的 `D:\cityai-mi-18366ca1` 目录还在，自动审核拦截了递归删除。

2026-11-02 登录验证码日志脱敏：联调代码检查确认 `/user/code` 会把完整短信验证码写入 INFO 日志，可能使日志采集系统暴露登录凭据。移除验证码值及包含完整手机号的 Redis key，仅保留脱敏手机号与过期秒数；新增 `UserServiceImplTest` 捕获日志并断言成功日志存在但不含验证码/`code=`。`UserServiceImplTest` 1 项和 `SnowflakeIdWorkerTest` 11 项共 12 项通过，0 失败/错误/跳过。
2026-11-02 Compose 双 Gateway 扩容入口：确认 `gateway` 固定发布宿主机 8083，直接 Compose 扩容会因端口重复绑定失败。将共同配置抽为 YAML anchor，新增默认关闭的 `gateway-ha` profile 和 `gateway-replica-2`，容器内继续监听 8083，第二宿主机端口默认 8089 且可通过 `GATEWAY_REPLICA_2_PORT` 修改；两实例沿用相同 Redis、Nacos、健康检查、静态资源卷和服务依赖。`docker compose config --format json` 验证默认配置不含副本、profile 配置映射 8083:8083 与 8089:8083，Redis 配置一致。`GatewayRouteIntegrationTest` 5 项在真实 Redis DB 15 全部通过，覆盖双 Gateway 上下文共享限流桶；测试后 DB 15 为 0 个键。Docker 引擎不可用，未运行真实 Compose 容器；容器网络、外部负载均衡器健康摘除仍待验证。
2026-11-03 双订单 JVM 秒杀并发联调：使用随机 MySQL 库 `cityai_seckill_be5ab05c`、独立 Redis 16379 与 RocketMQ，启动 order-1(8084, worker=0) 和 order-2(8089, worker=1)。两实例处理 64 个不同用户请求；20 个订单成功、44 个因库存不足被拒绝，HTTP 均为 200（业务失败通过 Result 表达）；两端口分别创建 11/9 单，Snowflake worker 位分别为 0/1。MySQL 为 20 单/20 用户/20 唯一 ID，订单状态均未支付，数据库库存 20→0，guard 20，派发任务 20 笔均 completed；Redis 库存 20→0、用户集合 20、恢复队列 0。测试器中途做了 1 笔预检成功请求，计入上述 20 单。初次隔离库活动时间写入与 Asia/Shanghai JVM 时区不一致，实际活动窗口校正后重测。测试服务、RocketMQ、专用 Redis 停止，端口释放，随机库已删除。Docker 容器网络及持续负载/P95 仍未验证。请求中的 HTTP 429 来源尚未定因；类级限流注解的实际默认值为全局 1000/s，不能据此认定为每秒 1 次。

2026-11-03 限流问题复核：初步将秒杀 429 归因于类级“每秒 1 次”限制并据此提出的代码调整不成立，因为 `SlidingWindowLimit.maxRequests` 默认值为 1000；新代码尚未通过运行时或测试证据证明该改动必要，已撤销控制器、测试与压测文档的相关改动。AOP 切面回归测试 5 项通过；新增注解测试因错误使用 Spring 合并注解且在旧实现上失败，不作为证据，已删除。真实 Redis 限流测试因未配置 `RATE_LIMIT_TEST_REDIS_HOST` 自动跳过。需在后续隔离联调中记录具体限流键/决策以定位 429 来源，再决定是否修改。

2026-11-03 真实 Redis 滑动窗口限流：在隔离 Redis 16379、数据库 0 上运行 `SlidingWindowLimitRedisTest` 与 `SlidingWindowLimitAspectTest`，共 6 项通过，0 失败/错误/跳过；真实 Redis 用例确认窗口内第一次请求放行、第二次触发拒绝，且只保留一条窗口记录。测试后 Redis DB 0 为 0 个键，临时 Redis 已关闭。此测试验证通用脚本行为，不覆盖 `VoucherOrderController` 两层注解组合，也不能解释此前双订单实测出现的 429；该问题仍待带真实请求级日志/限流键的隔离复现。

2026-11-03 秒杀投递 Redis 故障降级回归：新增 `SeckillOrderDispatchWorkerTest` 场景，模拟 Redis 恢复队列连接异常，同时 MySQL 中已有一笔待派发任务；验证 Worker 仍领取数据库任务、调用 RocketMQ 发送并记录重试状态。该用例与原有两项共 3 项通过，0 失败/错误/跳过。它覆盖 Redis 队列不可用但数据库任务已持久化的恢复路径，不覆盖 Redis 整库丢失时库存/用户占用核对；整库数据丢失仍需按运维文档暂停秒杀并人工确定恢复基线。

2026-11-03 库存回补与支付/关单真实依赖回归：本机临时 Redis、随机 MySQL 测试库和隔离 RocketMQ Broker/NameServer 下运行 `OrderStockReleaseIntegrationTest` 5 项及 `PaymentConsistencyIntegrationTest` 19 项，共 24 项全部通过，0 失败/错误/跳过。覆盖 Redis 关单回补 Lua 并发幂等、回补 ACK 丢失后不释放用户新占用、缺少库存/占用时保留任务，以及支付与超时关单竞争、重复真实 RocketMQ 延迟消息、重复秒杀消息消费和持久化库存同步任务。测试库已由用例删除；Redis 0 键且关闭，MQ/Redis 测试端口已释放。测试不模拟 Redis 整库持久化数据永久丢失后的自动库存重建，该情形仍须按部署文档暂停秒杀并人工核定基线。

2026-11-04 关单回补标记生命周期复核：检查 `release-order-stock.lua` 与真实 Redis 集成用例后，确认订单级 `seckill:stock:released:<orderId>` 标记不能在数据库任务完成时直接删除或设置短 TTL：另一订单实例可能已读取旧任务但暂停执行，用户在取消后建立新订单后，迟到的旧任务会把同用户的新占用误判为旧占用释放。仅按用户 ID 存储 Redis 占用时，永久标记是迟到重试幂等的必要保护。后续修复为每个券维护用户到当前订单 ID 的 Redis hash 映射，并在回补 Lua 中核对订单身份；该映射可防止释放标记单独被淘汰后误释放新订单，但若占用集合与映射等缓存状态同时丢失，仍需依靠现有失败重试/对账恢复，不将标记视为可无条件清理。

2026-11-04 AI 恢复扫描会话饥饿修复：新增真实 MySQL 回归场景，构造一个仍持有有效租约的会话和 100 条同会话待处理消息，再加入另一个独立会话的可恢复任务。旧查询按 message_id 先取前 100 条，实际返回的 100 条全被前序任务阻塞，导致独立会话任务被批次挤出。`AiTaskMapper.findRecoverable` 现只返回每个会话最早的未完成任务候选；会话顺序仍由原子 `claim` 条件保证。回归用例在旧 SQL 上按预期失败（返回 100 而非 1），修复后 `AiTaskConsistencyIntegrationTest` 8 项在随机 MySQL 库全部通过，0 失败/错误/跳过；测试数据库已自动删除。此修复避免恢复扫描被单个长会话占满，真实多 JVM 高积压吞吐仍需压力测试。

2026-11-04 AI 恢复扫描索引优化：在本机 MySQL 8.0.44 随机临时表上，用 100,000 行任务构造 99,500 条尚未达到 30 秒恢复年龄的待处理记录和 500 条可恢复记录。旧索引下 `EXPLAIN ANALYZE` 为返回 100 条扫描了主键前 99,600 行，约 80ms；临时添加 `(status, created_time, message_id)` 后扫描 500 条候选并排序 100 条，约 0.86ms（该合成数据仅说明执行计划差异，不代表生产 SLA）。将该索引加入 `ai-agent.sql`、`ai-task.sql`、`micro-ai.sql` 新库结构，新增 `ai-task-pending-recovery-index.sql` 供已有库迁移，并更新 README/部署说明。迁移回归在临时 MySQL 表上先移除索引再执行脚本并核对索引名成功；`AiTaskConsistencyIntegrationTest` 9 项全部通过，0 失败/错误/跳过。随机执行计划库及测试库已删除。

2026-11-04 AI 恢复扫描混合积压优化：进一步以本机 MySQL 8.0.44 合成 100,000 行检查混合状态计划，单条 `OR` 查询在 99,500 条未到期待处理记录与 500 条过期租约并存时选择主键扫描，约 85ms；按待处理/过期租约拆为 `UNION ALL` 后分别利用 `(status, created_time, message_id)` 与原租约索引，约 7ms。当前 `findRecoverable` 两分支各取最多 100 条，再全局按 message_id 取 100 条；新增回归覆盖两分支混排排序。`AiTaskConsistencyIntegrationTest` 10 项在本机 MySQL 全部通过，0 失败/错误/跳过；首次运行发现该测试类原先共用数据库却未清理测试表，固定主键冲突，已补充每项测试前清表。耗时为特定合成数据的计划观测，不是生产 SLA；仅过期租约场景下 UNION 的额外合并开销仍需真实负载比较。临时计划库和测试库已清理。

2026-11-04 Docker 构建上下文优化：确认两个 Dockerfile 都以仓库根目录作为 Compose 构建上下文，且项目原先没有 `.dockerignore`；本机 `target/` 约 2.4GB、前端 `node_modules/` 约 94MB，此外上下文还包含 Git 元数据、IDE/代理配置、文档、`start.local.json` 和本地聊天历史文件。新增根目录 `.dockerignore` 排除这些非构建输入、`.env` 文件和本地聊天数据，保留服务 Dockerfile 实际读取的 Maven POM、Java 源码、部署 Dockerfile 与网关源码。静态核对 Compose 展开的六个服务仍指向仓库根上下文。Docker Desktop Linux Engine 未运行，`docker buildx build --check` 因无法连接 daemon 退出，因此本轮未完成真实 BuildKit 上下文大小和镜像构建验证；待引擎恢复后继续验证 Compose 镜像构建与容器网络故障恢复。

2026-11-04 订单超时补扫索引优化：用本机 MySQL 8.0.44 临时库复现 100,000 条未支付订单，其中前 100 条过期、后 99,900 条尚未过期；游标越过前 100 条后，原查询扫描 99,900 行并在约 47.7ms 返回空结果。试验 `(status, create_time, id)` 后扫描缩小到过期范围；再去掉显式 `id` 验证 MySQL InnoDB 二级索引隐含主键仍可应用 `id > afterId` 条件，执行计划约 0.05ms。因此把新库订单索引设为 `(status, create_time)`，并将已有库的 `order-timeout-recovery.sql` 改为替换冗余单列状态索引的迁移；同步更新 README 与微服务部署说明。`PaymentConsistencyIntegrationTest` 新增迁移回归用例，订单测试类 20 项运行、0 失败/错误、4 项因未启用 Redis/RocketMQ 外部联调而跳过。执行计划来自特定合成数据，不是生产 SLA；长时间故障后的全量库存核对和真实积压负载仍未验证。

2026-11-04 微服务启动就绪性：确认微服务配置此前未启用 Actuator readiness probes（非 Kubernetes 环境默认关闭），Compose 检查的普通 `/actuator/health` 不能表达启动任务阶段；订单秒杀库存补热仍用 `ApplicationRunner`，会在 WebServer 初始化后执行，实例存在先注册 Nacos、后预热库存的短窗口。新增 Spring Boot Actuator 启动阻塞集成测试，Runner 等待期间 readiness 返回 503，完成后变为 200。现将秒杀库存补热改为 `SmartInitializingSingleton`，使其在 WebServer 和 Nacos 注册前完成；微服务配置启用 probes，Compose 改查 `/actuator/health/readiness`，并同步部署说明。Docker Engine 不可用，尚未做真实 Compose 滚动重启时的 Nacos 路由联调。

2026-11-04 店铺缓存锁慢回源租约：确认缓存锁使用固定 10 秒 TTL，而数据库回源没有配置短于该租约的查询 deadline；慢查询超过租约后，其他实例可重新取得锁并重复回源。现为逻辑过期异步重建、冷缓存加载和通用互斥查询添加 token 校验续租，每 3 秒延长 10 秒；持锁任务结束或后台队列拒绝后取消续租，解锁仍以 token 匹配为前提。新增确定性心跳测试；`CacheClientTest` 10 项、`ShopCacheTest` 6 项全部通过。真实 Redis 慢查询与续租失败联调尚未执行。

2026-11-03 订单库存同步多实例领取：确认每个订单实例都读取同一批前 100 条未完成任务，Lua 能保证库存只回补一次，但会重复执行 Redis/数据库调用；worker 崩溃时任务也没有所有者租约。为任务表增加 claim token 和十分钟租约，worker 通过数据库原子批量领取，成功与失败更新都校验 token，失败仍延后 30 秒，进程退出后可过期接管。新库结构和一次性旧库迁移脚本已同步，部署说明已更新。`OrderStockSyncWorkerTest` 5 项通过；复用本机私有连接配置后，`PaymentConsistencyIntegrationTest` 在随机隔离 MySQL 库运行 22 项，0 失败/错误、4 项因未配置 Redis 而跳过；新增双 mapper 并发领取、租约到期接管/旧 token fencing，以及旧表迁移结构回归均通过。测试类结束后自动删除隔离库。




2026-11-03 超时订单补扫失败重试：确认原 worker 失败后只推进 JVM ID 游标，失败订单需等待全量扫描回绕且进程重启会丢失游标位置。新增独立 tb_order_timeout_retry 持久任务表；补扫失败时记录订单 ID 并退避 30 秒，worker 通过 MySQL 十分钟租约领取、token fencing 更新，成功/订单已支付后删除任务，失败后增加 attempts、释放租约并继续退避。支付和关单事务未改动，常规扫描及 Redisson 协调保留。新库脚本更新，已有库新增 order-timeout-retry.sql 幂等建表迁移；README 与微服务部署说明要求先执行迁移再部署。OrderTimeoutRecoveryWorkerTest 6 项通过；PaymentConsistencyIntegrationTest 24 项运行、0 失败/错误、4 项因 Redis/RocketMQ 未设置而跳过，真实 MySQL 覆盖双 mapper 并发领取、旧 token fencing、30 秒退避、接管与删除，以及迁移重复执行。后续复核发现重复扫描登记会清空有效 retry 租约；现已修复为只更新时间、不抢占 token，并加入有效租约不被夺权的真实 MySQL 回归断言。Redis 多实例 OrderTimeoutRecoveryWorkerRedisTest 未运行（环境变量未配置）。



2026-11-03 库存同步积压观测：确认 	b_order_stock_sync 失败任务会无限按 30 秒重试，但没有可供运维查看的低基数积压信号。新增 cityai.order.stock.sync.pending（未完成任务数）和 cityai.order.stock.sync.oldest.age（最老任务年龄秒）两个 gauge，每 30 秒执行一次聚合快照，失败时保留上次值；首次数据库快照前暴露 NaN，避免把数据库不可用误报为零积压。README 与微服务说明补充 Actuator 查询路径和运维网络限制。OrderStockSyncMetricsTest 3 项、OrderStockSyncWorkerTest 5 项通过；本机 MySQL 8.0.44 临时库验证聚合 SQL 对未完成任务返回 2/120 秒，已完成任务不混入生产条件查询；测试库已删。完整 PaymentConsistencyIntegrationTest 本轮未运行（无 PAYMENT_TEST_MYSQL_HOST），此前 24 项租约/迁移验证报告仍有效。指标用于告警积压，不重建 Redis 库存，也不构成全量对账。


2026-11-03 库存同步重复失败告警信号：继续检查库存同步积压观测后发现，仅看未完成数量和最老年龄不能直接区分普通延迟与多次回补失败。新增 cityai.order.stock.sync.repeated.failures gauge，统计 attempts 达到 order.stock-sync.metrics.repeated-failure-threshold 的未完成任务数（默认 3）；阈值经 #{failureThreshold} 参数绑定，不使用 SQL 拼接。指标测试 3 项通过；本机 MySQL 8.0.44 隔离库插入 attempts=2/3/已完成 attempts=9 三条任务，SQL 对阈值 3 返回 pending=2、oldest=120 秒、repeated failures=1；临时库已删。Actuator 端点的真实进程读取仍需 Redis/RocketMQ/Nacos 服务可用后联调。


2026-11-03 库存同步指标 Actuator 暴露验证：新增轻量随机端口 Spring Boot HTTP 集成测试，排除 Redis/Redisson、RocketMQ、Nacos、数据库和 OpenAI/Milvus 自动配置，通过 Mock mapper 快照加载三个 gauge，并请求 /actuator/metrics/cityai.order.stock.sync.pending、cityai.order.stock.sync.oldest.age、cityai.order.stock.sync.repeated.failures 均返回 HTTP 200。OrderStockSyncMetricsEndpointTest 1 项、OrderStockSyncMetricsTest 3 项通过；该用例验证 Actuator/Micrometer 暴露路径，不等于完整 order profile 启动联调。此前 MySQL 8.0.44 隔离库验证聚合 SQL 阈值结果为 2 pending、120 秒 oldest、1 repeated failure。


2026-11-04 秒杀入口数据库往返优化：确认每次秒杀请求先分别读取 tb_seckill_voucher 和 tb_voucher，再调用 Redis Lua；入口存在两次同步数据库往返。新增 SeckillVoucherMapper.findActiveById，通过单条 JOIN 同时要求活动存在且优惠券 status=1/type=1，返回活动时间；秒杀入口只调用该查询，保留每次请求实时读取及 Lua 服务端时间窗口校验，避免引入活动配置缓存失效问题。VoucherOrderIdTest 10 项通过，覆盖缺失/下架/普通券拒绝不预扣、活动窗口拒绝及成功路径只调用 mapper 的单次入口查询；SeckillVoucherMapperIntegrationTest 已编译，未配置可用 MySQL 测试凭据时跳过，之前以空密码尝试连接被 MySQL 拒绝，因此 JOIN 方言和字段映射仍待有凭据的真实 MySQL 集成验证。当前没有可用 Redis、RocketMQ 和 Docker Engine，未做端到端吞吐/P95 测量；本改动仅减少一次数据库往返，收益需在依赖恢复后按压测方案测量。


2026-11-04 秒杀正确性压测口径复核：复核 VoucherOrderController 与 SlidingWindowLimitAspect，确认秒杀方法级滑动窗口 key 为 userId+voucherId、每用户每券每分钟 1 次，不会限制不同用户抢同券；Redis Lua 在库存耗尽时返回业务失败，不回滚此前成功请求。原压测方案未充分明确 JMeter 需按 JSON success 分类、受理数上限及订单 ID/数据库 Redis 库存的收敛核对，现已补充库存 100、1000 唯一用户单次并发正确性流程和逐轮失败条件，并区分足量库存唯一用户的持续吞吐测量。进一步核实 tb_seckill_order_dispatch.completed 只表示项目投递任务完成，不能代表 Broker 消费队列清空；方案现要求另查 Topic 消费进度/积压，并逐订单核验落库、未支付终态、恢复副本与 pending 标记清理。仅更新测试方案，本轮未运行真实压测：当前无 JMeter、Redis/RocketMQ 与 Docker Engine，方案要求的隔离测试数据和服务无法准备。


2026-11-04 秒杀入口 JOIN MySQL 验证：从本机私有启动配置中临时读取数据库凭据（未输出），测试只创建并删除随机 seckill_mapper_test_<UUID> 数据库。SeckillVoucherMapperIntegrationTest 在 MySQL 8.0 实际运行 1 项通过，验证可售秒杀券字段和起止时间映射正常，普通券、下架券及无活动记录均返回空；VoucherOrderIdTest 10 项通过，0 失败/错误/跳过。随后只读检查确认无残留测试库。原先的 JOIN 映射和参数绑定风险已在真实 MySQL 下排除；本轮未做 Redis/MQ 端到端测试或吞吐测量。

2026-11-04 秒杀查询合并错误语义兼容修复：复核确认此前 INNER JOIN 将活动不存在和优惠券下架或类型不符都映射为空，改变原入口两种不同错误提示。现改为单条 LEFT JOIN 查询返回活动字段及券状态/类型，服务层仍按原先顺序区分“秒杀活动不存在”和“秒杀券不存在或已下架”，不增加数据库往返且保持每请求实时读取。扩展真实 MySQL 用例覆盖可售、普通券、下架券、活动存在但券记录缺失、无活动五类状态；SeckillVoucherMapperIntegrationTest 1 项与 VoucherOrderIdTest 10 项通过，0 失败/错误/跳过；确认随机测试库清理且 git diff --check 无空白错误。未运行 Redis/MQ 端到端或性能测试。

2026-11-04 Gateway 隔离 Redis 回归复测：本机没有可用 Docker/Nacos/RocketMQ 服务，但发现 Windows Redis 5.0.14.1 可执行文件；在 loopback 的 16379 端口启动独立、关闭 RDB/AOF 的临时 Redis，使用真实 Redis 执行 gateway 模块 GatewayRouteIntegrationTest，5 项全部通过、0 失败/错误/跳过，覆盖 content 双后端负载均衡、user/order/AI 路由、SSE 透传、内部路径拒绝、Redis 限流补充及两个 Gateway JVM 共享限流。该测试使用 SimpleDiscoveryClient 与模拟后端，不能替代 Nacos 健康实例摘除或 Compose 联调。测试完成后 Redis 进程已正常关闭且 16379 已确认释放；自动审核拒绝删除本轮创建的临时配置/日志目录，目录仅含本轮配置、PID 和 Redis 日志文件，未触及旧 RocketMQ 数据目录。

2026-11-04 AI SSE 中途断线轮询窗口修正：检查前端链路确认 SSE 建连失败后会轮询数据库消息，但 SSE 建连成功后若中途断开，会等待 75 秒再进入轮询；原轮询只做 50 次、每次 1.2 秒（约 60 秒），可能在最终回答保存前停止。为“消息已发送、SSE 后续失败”单独按 75 秒窗口继续轮询，并抽取统一轮询间隔；连接建立失败时仍保持既有最多 60 秒补取策略。新增 Node 回归模拟流连接打开后断开、数据库随后出现最终助手消息，验证不重复发送且可以取回回答。`node --test test/ai-stream.test.mjs` 1 项通过；`npm run build` 通过（仅 Sass legacy API 弃用警告）；`git diff --check` 通过。未做真实浏览器断网/恢复及后端联调。

2026-11-04 Compose 部署配置复核：读取部署说明、Compose 依赖/健康检查、四个 profile 配置及 Gateway Nacos/LoadBalancer 配置；使用仅本条命令有效的非密钥占位变量执行 `docker compose -f deploy/compose.yaml config --quiet`，展开通过。Gateway `BusinessRateLimitConfigurationTest` 9 项通过；`GatewayRouteIntegrationTest` 5 项因未设置独立 Redis 环境变量跳过。当前 Docker CLI 无法连接 Linux Engine，Nacos/Redis/RocketMQ 端口未监听，且本机没有配置 Compose 所需外部服务凭据；因此本轮不具备容器网络扩容/故障摘除验证条件，未更改 Compose 业务配置。下一步需恢复 Docker Engine 和隔离依赖后验证 profile 实例扩容、Nacos 摘除与 Gateway 恢复。

2026-11-04 AI 架构回归复核：当前 JDK/Maven 下运行 Agent loop、AI 异步配置、依赖熔断、任务完成/租约/恢复、SSE 消息处理及任务领取相关测试，共 27 项，0 失败/错误、2 项真实 Redis 测试因未设置独立 Redis 而跳过；前端 `node --test test/ai-stream.test.mjs` 1 项通过。Gateway 限流配置测试 9 项通过，Redis 路由集成 5 项因 Redis 未启动而跳过。读取既有 Surefire 报告发现旧 `ShopInsightSeedDataTest` 因默认 MySQL 无密码及 Milvus 不可达失败、`BoomFilterTest` 因缺 OpenAI API key 失败，`HmDianPingApplicationTests`/`RedissonTest` 因默认 Spring 上下文依赖未就绪失败；这些测试依赖个人环境且不属于本轮可隔离运行范围，未删除或跳过它们。`docker compose config --quiet` 使用临时非密钥占位变量通过；真实容器故障恢复和 Redis 路由测试仍待 Docker/Redis/Nacos 环境恢复。

2026-11-04 默认测试隔离与回归修正：确认 `HmDianPingApplicationTests` 会创建 500 线程、写 `D:\tokens.txt` 并写 Redis Token/GEO/库存数据，`RedissonTest` 使用固定锁名，`BoomFilterTest` 假设个人 Bloom 数据，`ShopInsightSeedDataTest` 会清理并写店铺 5 的博客/评论且依赖默认 MySQL/Milvus。为保留原覆盖同时避免普通 `mvn test` 访问/污染开发环境，四类测试现在仅在显式设置 `CITYAI_RUN_LEGACY_INTEGRATION_TESTS=true` 时运行，并在 README 说明必须提供隔离依赖。首次全量运行还暴露 5 个过时夹具断言：`AgentLoopExecutorTest` 仍 stub 三参数 overload，`SeckillStockWarmupTest` 未注入新入口 Mapper 且验证旧 Service 调用；同步测试夹具到当前四参数 Agent 与单查询秒杀入口，没有改变生产逻辑。修正后定向 `AgentLoopExecutorTest` + `SeckillStockWarmupTest` 5 项通过；全量 `mvn test` 共 234 项、0 失败/错误、60 项按条件跳过；外部 Redis/MQ/MySQL 集成覆盖仍需显式配置隔离依赖。`git diff --check` 通过。

2026-11-04 热门笔记排序索引：只读检查本机 MySQL 8.0.44 的 `hmdp.tb_blog` 只有主键索引，热门笔记 SQL `ORDER BY liked DESC LIMIT 10` 在当前 5 行表上显示 `ALL + Using filesort`。查询增加 `id DESC` 作为并列点赞数的稳定次级排序；单体与微服务新库 DDL 增加 `(liked,id)` 复合索引，并提供旧内容库迁移脚本。真实 MySQL 随机临时库插入 100,000 条合成记录：迁移前 EXPLAIN 为全表扫描+filesort；执行仓库迁移脚本、ANALYZE 后自然计划选中 `idx_blog_liked_id`，显示 `Backward index scan`、估算 10 行。10k 样本下优化器仍选择 filesort，表明小数据量有成本权衡，需以实际数据分布观察。`BlogServiceImplTest` 2 项通过并断言排序字段；全量 `mvn test` 共 234 项、0 失败/错误、60 项按条件跳过。临时 MySQL schema 已按随机名称清理，现有业务库只读且无结构修改。

2026-11-04 关注关系双向查询索引：只读检查本机 MySQL 8.0.44 `hmdp.tb_follow` 只有主键，当前作者发笔记会按 `follow_user_id` 拉取粉丝，关注状态与取关按 `(user_id, follow_user_id)` 精确查询；两条 SQL 都没有可用二级索引。新库 DDL 增加两个方向的非唯一复合索引，并提供旧内容库迁移脚本，不增加唯一约束、不改变历史关系语义。真实 MySQL 随机临时库插入 100,000 条关系：迁移前两查询均为 ALL 扫描、估算约 97,564 行；执行仓库迁移脚本并 ANALYZE 后，粉丝列表选中 `idx_follow_target_user`、估算 10 行，关注点查选中 `idx_follow_user_target` 覆盖索引、估算 1 行。`mvn test` 全量运行 234 项、0 失败/错误、60 项按条件跳过。临时 schema 已清理，现有业务库保持只读。

2026-11-04 店铺优惠券列表索引：确认公开 `GET /voucher/list/{shopId}` 实际执行 `tb_voucher.shop_id = ? AND status = 1` 并左连接秒杀券库存；本机 MySQL `hmdp.tb_voucher` 只有主键，当前执行计划 `ALL`。本轮为 `(shop_id,status)` 增加新库索引和旧库迁移脚本。随机临时 MySQL 库 100,000 张合成券迁移前 EXPLAIN 估算扫描 50,097 行；执行仓库迁移并 ANALYZE 后选中 `idx_voucher_shop_status`，估算扫描 33 行，秒杀券侧仍为主键 `eq_ref`。临时库已清理；全量 `mvn test` 运行 234 项、0 失败/错误、60 项按条件跳过。

2026-11-03 店铺类型列表缓存：确认 `GET /shop-type/list` 每次都按 sort 查询数据库，且类型列表没有对应写接口。服务层增加 Redis JSON 列表缓存，TTL 10 分钟；缓存读写失败会告警并回退查询/返回数据库结果，数据库异常不吞掉。新增 `ShopTypeServiceImplTest` 覆盖缓存命中、未命中写缓存和 Redis 读取异常回退，定向测试 3 项通过。Redis 当前未启动，未做真实 Redis 往返验证；分类数据若经数据库直接改动，缓存最多延迟 10 分钟更新。

2026-11-03 布隆过滤器启动预热游标分页：确认单体及微服务店铺/优惠券过滤器初始化均以 `LIMIT offset,size` 分页读取主键，页数增加时反复扫描已跳过行。100,000 行临时 MySQL 表 `EXPLAIN ANALYZE` 显示 offset=90,000 时覆盖索引扫描 91,000 行、约 9.3ms；`id > 90,000` 游标查询只读取 1,000 行、约 0.21ms。两个 mapper 和三种启动初始化器改为主键游标分页，过滤器条目与预热时机不变。临时 schema 已清理。`BloomFilterWarmupTest` 4 项通过，覆盖游标翻页与异常启动；全量测试待本轮完成。

2026-11-03 秒杀重复订单拒绝后的 Redis 预扣回补：真实 Redis 最小复现确认，当用户集合成员丢失但库存键仍存在时，Lua 可再次预扣；数据库占用表拒绝第二个订单后，原逻辑只完成投递任务并清理 pending，Redis 库存仍少 1。新增订单级幂等 Lua 回补：要求 pending 凭据匹配，原子增加库存、恢复用户占用集合，并写一次性标记；数据库任务先在事务内标记完成，再执行 Redis 补偿，补偿失败会抛错回滚数据库事务，Redis 幂等标记覆盖提交不确定后的任务重试；重复投递通过已完成任务状态跳过。普通消费路径不增加数据库查询。新增真实 Redis 测试覆盖数据库拒单回补和脚本重复执行幂等，2 项通过；全量 `mvn test` 241 项、0 失败/错误、62 项按环境条件跳过，`git diff --check` 通过。未连接真实 MySQL+Redis+RocketMQ 完整消费链路。

2026-11-03 秒杀恢复队列坏记录防饥饿：确认每轮恢复只读 ZSet 前 100 项，而已有 `deferRecoveryRecord` 未被调用；数据库恢复失败或 payload 缺失的队列头记录因此保持 score=0，可能长期占住扫描窗口。失败记录现在递增 score，仍保留重试；MySQL 已有对应投递任务时仍清理 Redis 副本。`SeckillOrderDispatchWorkerTest` 5 项通过，覆盖 DB 恢复失败和 payload 缺失重排；真实 Redis 测试将 100 条坏记录与第 101 条有效记录入队，首轮延后坏记录、次轮成功恢复有效任务。全量 `mvn test` 244 项、0 失败/错误、63 项按环境条件跳过；`git diff --check` 通过。MySQL+Redis+RocketMQ 完整故障恢复联调仍未覆盖。

2026-11-04 MCP SSE 共享 client 并发关闭：并发回归先复现一个 MCP 工具调用失败时立即关闭按 serverName 共享的 `McpSyncClient`，导致另一会话的在途调用也失败。改为跟踪每个共享 client 的在途调用：异常后立即从缓存摘除，让新调用重连；等旧 client 的在途调用全部退出后再关闭，避免串行化正常请求。`McpSseToolClientConcurrencyTest` 与 `LocalLifeMcpSseTest` 共 2 项通过；全量 `mvn test` 245 项、0 失败/错误、63 项按环境条件跳过；`git diff --check` 通过。并发测试使用 mock client 与同步闸门验证生命周期竞态，未接入真实外部 MCP SSE 服务。

2026-11-04 AI SSE emitter 生命周期上限：回归测试确认单体与 AI 微服务共用的 ChatSseServiceImpl 创建 SseEmitter(0)，连接没有服务端超时；当前前端最多等待流式回复 75 秒，失联 TCP 会让服务端异步请求资源可能长期占用。服务端 emitter 改为 120 秒上限，断线/超时仍走既有数据库轮询补取；微服务 Redis SSE 也复用同一实现。新增回归先在旧逻辑下按 0 超时失败，改动后 ChatSseServiceImplTest 2 项通过；全量 `mvn test` 共 246 项、0 失败/错误、63 项按环境条件跳过，`git diff --check` 通过。未做真实浏览器网络黑洞及容器超时联调。

2026-11-04 秒杀恢复队列字段损坏记录防饥饿：代码复核发现 payload 的订单 ID 与恢复队列 ID 不匹配、或 userId 为空时只记录错误并保留记录，没有递增 ZSet score；固定读取前 100 条时，这类 score=0 记录可长期阻塞后续有效任务。先加回归确认旧逻辑未调用 incrementScore，再让该分支复用现有延后策略；真实 Redis 队列集成用例现将前 100 项构造为字段不匹配的 payload，以检查第 101 项不被饥饿。SeckillOrderDispatchWorkerTest 6 项通过；全量 mvn test 247 项、0 失败/错误、63 项按环境条件跳过，git diff --check 通过。本机 Redis 未运行，修改后的真实 Redis 队列集成用例本轮跳过。

2026-11-04 Gateway 限流身份查找本地短缓存：确认每个有效 Token 的每个 Gateway 请求都会先对 Redis 执行 HGET 解析用户 ID，随后 RedisRateLimiter 还会访问 Redis 扣令牌；多次请求因此重复做同一身份查找。新增每个 Gateway JVM 最多 10,000 项、TTL 2 秒的有效 Token→用户 ID 缓存；无效 Token 不入缓存，满载时只回收过期项、满则跳过新缓存，Redis 令牌桶仍用共享 userId key 跨实例限流；后端鉴权不变。新增回归在旧逻辑下确认两次请求执行两次 HGET，改后命中缓存只查一次；缓存单测覆盖 TTL 和容量。Gateway 全量 mvn -f gateway/pom.xml test 共 17 项、0 失败/错误、5 项因未配置测试 Redis 而跳过，git diff --check 通过。本轮未做真实 Redis 高并发压测；注销后的身份映射最多在本 JVM 保留 2 秒，但该映射不会授予登录权限。

2026-11-04 滑动窗口限流 SpEL 热路径解析缓存：确认 SlidingWindowLimitAspect.parseSpEL 每个 Web 请求都新建 SpelExpressionParser 并解析源码固定的注解表达式。改为共享 parser 并通过线程安全 map 按表达式复用解析结果；每个请求仍创建自己的 StandardEvaluationContext，动态参数不会跨请求共享。原有限流 AOP 测试及新增 8 线程并发解析/独立上下文求值测试通过；全量 mvn test 共 248 项、0 失败/错误、63 项按环境条件跳过，git diff --check 通过。未做 JMH 或真实 Redis 限流吞吐测试，本轮证明解析只发生一次，不量化整体 RPS 增益。
2026-11-04 AI 前端 SSE 进度持久化降频：确认前端每收到一段 `answer_delta` 都通过 `updateMessageContent` 同步 `JSON.stringify` 整个会话历史并 `localStorage.setItem`；长回答会在主线程重复序列化、写盘。进度/增量/轮询状态仍即时更新 Pinia UI，但跳过存储；最终回答替换 loading 消息时立即保存完整历史。回归先确认旧实现 progress update 产生 1 次存储写入而期望为 0；改后 `node --test test/ai-stream.test.mjs` 2 项通过，`npm run build` 成功（有 Sass legacy JS API 弃用警告），`git diff --check` 通过。生产构建同步更新后端静态资源。本轮未做浏览器长回答性能量测。
2026-11-04 RAG 关键词兜底语料惰性缓存：确认每次知识库兜底都会重新扫描 classpath `knowledge/*.txt`、读取全部文件并按段落切分，语料运行期间不变。改为首次 fallback 时加载并发布不可变段落列表；并发首次请求由锁合并加载，加载异常不缓存以保留后续重试。向量检索、元数据过滤、关键词评分和结果排序不变。新增计数型资源测试证明两次 fallback 查询只扫描/读取一次；`AiRagRetrieverTest` 9 项通过；全量 `mvn test` 249 项、0 失败/错误、63 项按环境条件跳过，`git diff --check` 通过。未接入真实 Milvus 做语料规模和查询延迟量测。
2026-11-04 RAG 语料资源流关闭：复核发现关键词兜底首次读取 classpath 知识文件后没有关闭 InputStream。新增回归先确认旧实现未调用 close；使用 try-with-resources 修复后 AiRagRetrieverTest 9 项通过。全量 mvn test 249 项、0 失败/错误、63 项按环境条件跳过；git diff --check 通过。
2026-11-04 登录态退出链路补充：确认旧 /user/logout 仅返回功能未完成，前端退出只清本地 Token，Redis 登录态仍可被其他实例接受。当前用户服务删除当前 login:token:<token>，前端先请求服务端再清理本地状态；非 401 的服务端失败保留本地 Token 供重试。UserServiceImplTest 验证只删除当前 Token；UserLogoutControllerTest 经真实 MockMvc 控制器与登录拦截器验证未携带 Token 返回 401、有效 Token 登出后再次访问受保护接口返回 401。后端全量 mvn test 251 项、0 失败/错误、63 项按环境条件跳过；前端 npm run build、git diff --check 通过。本机 Redis 端口未开放且 Docker Engine 不可用，尚未做真实 Redis 和浏览器端到端联调。
2026-11-04 后续验证顺序：先恢复 Docker/Redis 等基础设施并在隔离数据下复核四服务加网关的容器网络、Token 登出与服务实例故障恢复；随后针对秒杀和订单链路做持续负载与长期故障对账；最后使用真实模型、Milvus 和外部 MCP 验证索引启动、Agent 流式回复及熔断恢复。每项以实际请求、指标和数据终态为验收证据，未获得该证据前不把对应部署能力标记为完成。
2026-11-04 店铺冷缓存并发更新返回值修复：确认冷回源在数据库读取期间遇到店铺更新时，setIfAbsent 会避免旧结果覆盖缓存，但当前请求仍直接返回旧结果（旧实现回归测试得到 null，新缓存已有店铺 ID 42）。CacheClient 现仅在 setIfAbsent 未写入时重读赢家缓存并返回该值，成功写入路径不变；缓存若在重读前又被删除，则仍返回本次数据库读取结果。CacheClientTest 新增回归，相关 CacheClientTest/ShopCacheTest 共 17 项通过；后端全量 mvn test 253 项、0 失败/错误、63 项按环境条件跳过，git diff --check 通过。本机 Redis 不可用，本轮未运行真实 Redis 并发集成测试。

2026-11-04 店铺缓存 Redis 故障 HTTP 状态：确认店铺详情 Redis 读取错误会冒泡，但通用 `WebExceptionAdvice` 只返回 `Result.fail`，默认 HTTP 200，网关和客户端可能把依赖故障当成功响应。考虑到店铺详情的 Bloom 过滤也依赖 Redis，直接数据库回源会在 Redis 故障时放大全站数据库负载，因此保留 fail-fast 语义；新增 `RedisConnectionFailureException` 到 HTTP 503 的映射，并以 MockMvc 回归覆盖。`WebExceptionAdviceTest` 4 项、`CacheClientTest` 14 项、`ShopCacheTest` 9 项通过；未连接 Redis 注入真实连接故障。
2026-11-04 验证码并发重放防护：确认登录原先先 GET 验证码再创建 Token，验证码在两步之间未消费，并发请求可重复通过同一验证码。改为 Redis Lua 原子比较并删除验证码；仅匹配的请求消费成功，错误验证码不删除有效码。UserServiceImplTest 覆盖已被另一请求消费时拒绝登录；后端全量 mvn test 254 项、0 失败/错误、63 项按环境条件跳过，git diff --check 通过。Redis 端口未开放，本轮未做真实并发 Redis 联调。
2026-11-04 订单一致性 MySQL 联调恢复：本机 MySQL 3306 可用，Redis/RocketMQ/Nacos/Milvus 端口未开放。使用被忽略的 start.local.json 中数据库密码（未打印），运行 `PaymentConsistencyIntegrationTest` 自动创建随机隔离库；24 项执行，0 失败/错误、4 项因未配置 Redis/RocketMQ 跳过，测试按其 finally 清理隔离库。订单数据库事务、条件状态迁移、补偿任务 SQL 和租约逻辑获得本机真实 MySQL 验证；缓存 Redis 脚本、真实 MQ 投递及 Docker 服务网络仍未验证。
2026-11-04 AI 任务真实 MySQL 验证：Redis/RocketMQ/Nacos/Milvus 仍未开放，本机 MySQL 继续可用。使用 start.local.json 私有凭据运行 `AiTaskConsistencyIntegrationTest`，自动创建并清理随机隔离库，10 项全部通过，0 失败/错误/跳过；验证任务恢复扫描、并发领取、租约过期接管、旧 claim token fencing 与任务回复状态事务一致性。该结果不覆盖 Redis 会话锁、SSE、模型和向量库。
2026-11-04 秒杀券 Mapper 真实 MySQL 验证：使用随机隔离数据库运行 `SeckillVoucherMapperIntegrationTest`，1 项通过，0 失败/错误/跳过；确认可售券字段、活动时间边界、普通券、下架券及缺失活动记录的 SQL 映射。测试自动删除隔离库。本次核验 Redis/RocketMQ/Nacos/Milvus 仍未开放，故未覆盖 Lua 预扣、异步消息消费和容器网络。
2026-11-04 Compose 配置复核：Docker CLI 存在但 Linux Engine 未运行（docker info 无法连接 dockerDesktopLinuxEngine），无法做镜像构建和容器启动。为避免暴露配置，在进程环境中仅提供校验占位值后运行 `docker compose -f deploy/compose.yaml config --services`，Compose 展开成功并列出 nacos/user/content/orders/ai/gateway 六个默认服务；复核两个 Dockerfile 的 COPY 路径和静态资源目录与 `.dockerignore`、Compose 上传卷路径一致。该静态检查不能替代 BuildKit 构建或容器网络联调。
2026-11-04 Docker Engine 联调准备：发现 Docker Desktop 已安装但未运行，已启动本机 Docker Desktop。Docker Desktop/com.docker.backend 进程已出现，但 `docker info` 与 `docker version` 在等待 daemon 时持续无响应；未启动 Compose 服务、未构建镜像、未改动容器或卷。待 Linux Engine 完成启动后继续执行 Compose build 与容器健康检查。
2026-11-04 Docker 引擎状态复查：Docker Desktop 与 backend 进程仍在，但 `docker info` 不能返回；`wsl --list --verbose` 显示 docker-desktop 与默认 Ubuntu 均为 Stopped。没有执行会影响其他 WSL 工作负载的全局 shutdown 或手工启动发行版。此环境状态下暂不能运行容器化验证；代码及 Compose 配置检查可继续。
2026-11-04 AI 熔断降级定向复核：核对模型思考、流式输出、MCP 路线/天气与 RAG 检索/索引均经过 Sentinel 资源保护；模型失败返回明确的 Agent 兜底答复，MCP 失败走本地策略，知识向量检索失败可命中关键词语料。运行 AiDependencyGuardTest、AiDependencyCircuitRulesTest、LocalLifeMcpCircuitTest 共 6 项，0 失败/错误。真实模型、Milvus、MCP 服务仍未开放，未做外部依赖恢复窗口联调。
2026-11-04 Nacos Compose 管理面收敛：确认 Nacos 仅供 Compose 内部服务发现，Compose 未配置鉴权；原配置把控制台 8848 与 RPC 9848 都发布到宿主机所有网卡。将控制台改为只绑定 127.0.0.1:8848，并取消不需要的 RPC 宿主机映射，业务容器仍用内部地址 nacos:8848。`docker compose config --format json` 验证 nacos 恰有一个 host 映射且绑定 localhost，`git diff --check` 通过。Docker Linux Engine 仍不可连接，未进行镜像启动/网络实测。
2026-11-04 Actuator 独立管理端口验证修正：将单体、微服务与 Gateway 管理端点配置到独立 `127.0.0.1:8086`，微服务 Compose 健康检查同步访问该端口，管理端口不发布到宿主机。首次全量运行暴露 `OrderStockSyncMetricsEndpointTest` 仍只记录业务 WebServer 端口；调整测试监听管理上下文实际端口后，定向测试通过。全量 `mvn test` 254 项、0 失败/错误、63 项按环境条件跳过；Compose JSON 展开确认 user 健康 URL 为 localhost:8086；`git diff --check` 通过。容器启动实测仍受 Docker Engine 未就绪限制。
2026-11-04 Actuator 业务端口隔离断言：复查指标端点测试发现其曾在独立端口尚未捕获时回退到业务端口，可能让错误绑定的实现也通过。移除回退逻辑，必须在管理上下文端口访问 metrics 返回 200，并断言业务端口访问相同 metrics 返回 404。`OrderStockSyncMetricsEndpointTest` 1 项通过，0 失败/错误/跳过；`git diff --check` 通过。
2026-11-04 Gateway 独立管理端口回归验证：Gateway 模块读取 `management.server.port=8086` 且只暴露 health；运行 `mvn -f gateway/pom.xml test` 共 17 项、0 失败/错误、5 项因未配置 Redis 跳过，确认路由/限流配置未受管理端口隔离影响。容器启动和外部网卡绑定仍需 Docker Engine 就绪后实测。
2026-11-04 Compose 管理面端口门禁检查：使用非密钥占位变量展开 Compose JSON，确认 user/content/orders/ai 四个服务健康检查均访问 `127.0.0.1:8086/actuator/health/readiness`，宿主机无 8086 映射；默认宿主机发布端口仅为 Gateway 8083 和绑定 loopback 的 Nacos 8848，无 Nacos RPC 映射。Compose 展开及 `git diff --check` 通过。此为配置级检查，Docker Engine 未响应，容器内网络仍需运行态验证。
2026-11-04 上传接口访问控制修复：发现 MvcConfig 将 `/upload/**` 整体排除登录拦截，匿名用户可上传文件及按已知文件名删除共享卷图片；仅加登录仍允许用户删除他人博客图片。移除该排除项，并在删除前校验文件名是否精确出现在当前用户的博客图片列表中。UploadControllerSecurityTest 覆盖匿名上传/删除均 401、登录上传成功、删除他人图片被拒且文件保留，定向 3 项通过。全量 `mvn test` 257 项、0 失败/错误、63 项按环境条件跳过；`git diff --check` 通过。
2026-11-04 内部用户批量查询上限：对照微服务内部接口后发现内容服务按 ID 批查最多 500 条，而 `/internal/users?ids=...` 没有上限，可将任意规模的 ID 列表直接传给 MyBatis `IN` 查询。先运行新增 MockMvc 回归，在旧代码上观察到 501 个 ID 返回 200 且进入查询层；随后限制为最多 500 个，并让内部接口参数异常返回 400。测试覆盖 500 条放行和 501 条拒绝且用户目录无交互；定向 2 项通过，全量 `mvn test` 259 项、0 失败/错误、63 项按环境条件跳过；`git diff --check` 通过。Docker Engine 与 Redis/RocketMQ/Nacos/Milvus 仍不可用，本轮未做微服务运行态联调。
2026-11-04 AI 内容接口全量读取收敛：确认 `/internal/content/shops` 与 `/internal/content/blogs` 会经 `LocalContentCatalog` 调用无界 `list()`，而 AI 索引器和仓库内所有生产调用方已改用每批 200 条的游标接口。先运行 MockMvc 回归，在旧实现上确认无分页路由返回 200；随后从内部控制器、Feign 客户端及 ContentCatalog 接口移除未使用的全量读取方法。新 HTTP 测试确认全量路由返回 404，分页路由仍返回 200；`AiVectorIndexPagingTest` 与控制器测试共 6 项通过。全量 `mvn test` 260 项、0 失败/错误、63 项按环境条件跳过；全仓搜索确认无生产全量方法调用，`git diff --check` 通过。该变更只涉及服务间内部 API，本仓库未发现外部调用方；容器运行态仍因 Docker Engine 未就绪无法验证。
2026-11-04 用户目录批查询分批：复查 `FollowServiceImpl.followCommons` 后发现 Redis 交集可能超过 500 个用户 ID，而本地用户目录会一次生成超长 SQL `IN` 查询，微服务模式则会发送超过内部接口上限的单个 Feign 请求。新增测试先确认两种实现都只发一次 501 条请求；随后在 UserDirectory 定义共享 500 条上限，并让本地 MyBatis 查询与远程 Feign 调用分别按 500/剩余条数分批，完成后仍按输入顺序返回且只查询去重后的 ID。定向 2 项及全量 `mvn test` 262 项通过，0 失败/错误、63 项按环境条件跳过；`git diff --check` 通过。真实 Redis 共同关注数据与跨服务请求仍待依赖恢复后联调。
2026-11-04 博客列表点赞状态 Redis 往返收敛：确认热门笔记与关注流在登录态下逐篇执行 `ZSCORE`，每页最多 10 篇会产生 10 次串行 Redis 往返。改为用 StringRedisTemplate 的 `executePipelined(SessionCallback)` 批量发出同一页的查询，按 pipeline 返回顺序设置点赞状态；单篇详情查询保持单条路径。`BlogServiceImplTest` 验证 pipeline 内每篇各有一个目标 ZSET/member 查询，命中与未命中状态映射正确；定向 2 项通过，全量 `mvn test` 262 项、0 失败/错误、63 项按环境条件跳过，`git diff --check` 通过。Redis 未运行，未测真实网络 RTT 或吞吐增益。
2026-11-04 秒杀订单消息异步提交：确认秒杀入口先持久化投递任务，再同步等待主订单 Topic Broker 回执；该同步发送使 HTTP 请求线程包含一次 MQ 网络往返。将主订单消息改为 RocketMQ `asyncSend`，异步回调失败只记录错误，已持久化任务继续由现有 worker 按重试计划补投，Lua 预扣和订单 ID 不变。`VoucherOrderIdTest` 覆盖异步发送使用同一订单 payload、不再同步发送主订单消息、回调失败不撤销预扣；10 项通过。全量 `mvn test` 262 项、0 失败/错误、63 项按环境条件跳过，`git diff --check` 通过。订单超时延迟消息仍为同步发送；RocketMQ 未运行，尚未实测 HTTP 延迟或异步失败后的真实 Broker 重投。

2026-11-04 秒杀超时关单移出请求路径：已确认入口仍同步调用 RocketMQ 的精确毫秒延迟发送，会把 Broker 网络等待留在 HTTP 请求中。新增数据库投递任务中的 `timeout_sent`、`timeout_retry_time` 和超时任务领取/退避状态；秒杀创建任务时记录订单创建时间 + 15 分钟，后台 worker 到期后发送普通关单消息，Broker 失败则记录并在 30 秒后持久重试。这里发送的是到期后的立即消息，避免“到期后再延迟 15 分钟”导致约 30 分钟才关单。Redis 恢复队列恢复任务时也从原始 `createTime` 计算相同到期时间。旧表迁移新增 `seckill-order-dispatch-timeout.sql`，把存量投递行标为已安排，避免部署时重复排关单；已有超时订单继续由 DB 补扫关闭。新增 worker 成功/失败重试测试及临时 MySQL 表迁移/claim 用例。定向测试：`VoucherOrderIdTest` 10 项、`SeckillOrderDispatchWorkerTest` 8 项、`SeckillStockWarmupTest` 4 项均通过；真实 MySQL `PaymentConsistencyIntegrationTest` 25 项运行，0 失败/错误、4 项因 Redis/MQ 环境变量未配置而跳过；本地测试库使用自动生成名称并由测试清理。全量 `mvn test` 265 项、0 失败/错误、64 项按环境条件跳过；`git diff --check` 通过。RocketMQ 未连接，未验证真实 Broker 到期投递。

2026-11-04 秒杀 outbox 积压可观测性：检查订单服务监控后确认库存回补任务已有 Actuator backlog gauges，但下单与到期关单 outbox 只能从日志和表中人工检查；同时 `ServiceIsolationFilter` 的 order 白名单不包含库存同步 metrics，导致该组件不在 order 微服务注册。新增六个低基数 gauges，分别观测未完成下单投递数量/最老年龄/重复发送次数，以及已到期未发送的超时投递数量/最老逾期秒数/重复发送失败数；数据每 30 秒从同一 DB 快照刷新，SQL 仅聚合未完成及已到期行以避免扫描已完成历史投递，复用表内状态/到期索引，数据库故障保留上次值，阈值可配置。将两种 metrics 组件加入 order 角色白名单，其他业务角色隔离。单测覆盖快照映射、异常时保留值，端点测试覆盖管理端点暴露，微服务隔离测试覆盖角色白名单；真实 MySQL `PaymentConsistencyIntegrationTest` 25 项运行，0 失败/错误，4 项因未配置 Redis/MQ 跳过，其中验证了 backlog SQL 在新任务、首次到期、失败退避和重复失败阈值下的计数。全量 `mvn test` 268 项、0 失败/错误、64 项按环境条件跳过；`PaymentConsistencyIntegrationTest` 再次单独运行 25 项、0 失败/错误、4 项因 Redis/MQ 环境变量未配置而跳过；`git diff --check` 通过。

2026-11-04 秒杀请求与 outbox 落库解耦：按架构目标核对调用链，确认请求线程在 Redis Lua 原子预扣并写入恢复队列后仍同步写 MySQL outbox、等待 MQ 发送回执，造成高并发受理路径包含数据库写入和消息发送延迟。移除请求内 outbox 写入及 MQ 调用，由后台 worker 每 250 毫秒最多取 500 条 Redis 恢复项，批量查重并批量写入 outbox，确认持久化后清理恢复副本，再从 outbox 投递；请求仍执行活动状态数据库查询和 Redis Lua。增加 `cityai.seckill.dispatch.redis.recovery.pending` gauge，统计尚未写入 outbox 的 Redis 恢复项，数据库和 Redis 指标分别独立刷新并在对应依赖异常时保留上次值；同步更新 README 与微服务文档。针对性 Maven 测试（metrics、管理端点、微服务隔离、投递 worker、秒杀受理与库存预热）通过；全量 `mvn -q test` 265 项、0 失败/错误、64 项按环境条件跳过。真实 MySQL 集成已验证批量 insert、状态查询和 backlog SQL；Redis/MQ 当前不可用，未做真实 Redis 恢复和 MQ 吞吐联调。`git diff --check` 通过。

2026-11-04 逻辑缓存过期时间跨时区：确认 `CacheClient` 原先将 `LocalDateTime` 直接写入共享 Redis，多实例默认时区不一致会让一份“30 分钟后过期”的值被其他时区实例立即判为过期；JShell 将写入实例设为 UTC、读取实例设为 Asia/Shanghai 后复现了该误判。新缓存增加 `expireAtEpochMillis` 并按绝对 epoch 时间判断，同时双写旧 `expireTime` 字段供滚动发布中的旧实例读取；旧格式缓存仍按原本地时间逻辑读到物理 TTL 到期后自然迁移。新增回归用例覆盖新格式绝对时间优先于旧墙上时间，冷加载用例检查双字段输出。`CacheClientTest`、`ShopCacheTest` 通过，Redis 集成用例因环境变量未配置跳过；全量 `mvn -q test` 266 项、0 失败/错误、64 项按环境条件跳过；`git diff --check` 通过。

2026-11-04 滑动窗口限流脚本复用：检查发现限流 AOP 每个请求都拼接完整 Lua 字符串并调用 Redisson `eval`；实际解析 Redisson 3.27.2 字节码确认该调用发送 EVAL 源文本，而项目已有 `RateLimit.lua` 从未被引用。改为 `DefaultRedisScript` 固定加载该资源，只向 Redis 传窗口毫秒数、阈值和键 TTL；核对项目运行依赖 Spring Data Redis 3.2.4 的执行器实现，确认常态使用 EVALSHA 并在 NOSCRIPT 时回退 EVAL。脚本仍使用 Redis `TIME`，滑窗 ZSet 更新仍在同一个 Lua 原子执行中，`maxRequests<=0` 继续放行，异常/空决策继续 fail-closed。单测增加脚本 SHA 描述复用、ARGV 与服务端时钟检查；真实 Redis 集成测试已改用与生产相同的 StringRedisTemplate，但本机 Redis 未运行，该 1 项本轮跳过。定向 AOP 与异常映射测试 8 项通过；全量 `mvn -q test` 266 项、0 失败/错误、64 项按环境条件跳过；`git diff --check` 通过。尚未执行真实 Redis `INFO commandstats` / 网络负载测量，因此只确认脚本文本在稳定请求上可走 SHA 路径，不声称具体 RPS 增益。

2026-11-04 店铺缓存命中跳过布隆查询：确认 `ShopServiceImpl.queryById` 每次都先访问 Redis 布隆过滤器，再由 `CacheClient` GET 店铺缓存，因此正值和空值缓存命中都多一次 Redis 往返。将布隆检查作为缓存未命中 guard 延后到缓存 GET 之后；布隆拒绝仍发生在 miss 计数、互斥锁和数据库回源之前，缓存命中不访问布隆过滤器，缓存命中率计数语义保持不变。新增 CacheClient 用例覆盖命中跳过 guard、拒绝后不回源/不记 miss，并更新 README 与本地压测指标说明。`CacheClientTest`、`ShopCacheTest`、`ShopLogicalCacheRedisTest` 定向测试通过；全量 `mvn -q test` 268 项、0 失败/错误、64 项按环境条件跳过，`git diff --check` 通过。Redis 未运行，未测真实网络 RTT 或吞吐增益。

2026-11-04 微服务 Gateway 与后端重复通用限流：按架构图复核发现，Gateway 已对每类公开路由按用户/IP 使用 Redis 令牌桶，但所有后端 Controller 的类级通用 AOP 限流仍会在 Gateway 转发后按网关源地址再次共用一个默认桶，可能额外压低单个服务的整体流量。`SlidingWindowLimitAspect` 现仅在 `micro` profile 下跳过类级通用限流；方法级限制（如秒杀/AI）继续运行，单体保持原行为。测试确认微服务模式类级注解不执行 Redis 限流、方法级仍执行，单体类级与方法级行为保留。同步更新微服务部署说明。定向限流与异常映射测试 9 项通过；全量 `mvn -q test` 269 项、0 失败/错误、64 项按环境条件跳过；`git diff --check` 通过。Docker Engine 查询仍无响应，因此未做 Gateway 到实际业务服务的运行态流量/限流联调。

2026-11-04 多 AI 实例 Milvus 启动初始化协调：确认每个 AI 实例都会对共享 Milvus 先检查、再按类型删除/补建索引，空集合首次启动时多个实例可能并发重建并相互删除数据。共享向量库初始化现由 Redis 分布式锁串行化，锁内复查索引状态；进程内向量库仍独立初始化，不增加 Redis 锁开销。新增测试覆盖双实例初始化串行、异常释放锁、Fail-Fast 保留和本地向量库不取锁；`KnowledgeBaseInitializerTest` 与 `AiVectorIndexPagingTest` 定向测试通过。全量 `mvn -q test` 269 项、0 失败/错误、64 项按环境条件跳过，`git diff --check` 通过。该变更使用 Redisson 分布式锁模拟测试，Redis/Milvus 未运行，实际多进程联调仍未验证。

2026-11-04 店铺画像重建的源读取失败保护：先新增回归测试，在内容服务读取店铺失败时观察到画像索引删除被执行两次，旧向量数据因此丢失。将店铺源数据读取移到向量删除之前；读取异常会直接保留已有画像，真实返回不存在时仍清理该店铺的过期画像。`AiVectorIndexPagingTest`、`KnowledgeBaseInitializerTest`、`AiDependencyCircuitRulesTest` 定向测试通过；全量 `mvn -q test` 270 项、0 失败/错误、64 项按环境条件跳过；`git diff --check` 通过。Redis/Milvus/内容服务未运行，未做外部服务故障注入联调。

2026-11-04 单店评论向量重建的源读取保护：新增回归并在旧实现上复现第二页内容服务查询失败会先删除旧评论向量、写入首批后再清空部分索引。为保持现有 200 条内存批次边界，先把各页构建出的文档暂存到系统临时文件；全量内容与店铺元数据读取成功后才删除旧店铺向量，再从临时文件按 200 条批量写 Milvus。源读取/暂存失败时旧索引不动；Milvus 替换阶段失败仍清理不完整的新索引；完成后删除暂存文件。`AiVectorIndexPagingTest` 验证首批 201 条仍分 200/1 写入、后续页读取失败不碰旧评论索引；全量 `mvn -q test` 271 项、0 失败/错误、64 项按环境条件跳过，`git diff --check` 通过。Redis/Milvus 未运行，外部服务故障注入和大规模暂存盘容量测试未做。

2026-11-04 AI 全量向量索引源失败保护：新增全量店铺画像/评论的第二页故障回归，先在旧实现上确认源读取异常会触发删除旧索引和失败清理。改为按 200 条读取并将新文档暂存到系统临时文件，完整扫完源数据后才替换向量索引；替换阶段出错仍清理不完整索引。平台知识文件也改成读取完成后再删除旧知识向量，并且仅在替换已开始时做失败清理。回归同时确认仍按 200 条分批写入；单店与全量索引共用暂存回放和临时文件清理逻辑。AiVectorIndexPagingTest 9 项通过；全量 mvn -q test 273 项、0 失败/错误、64 项按环境条件跳过；git diff --check 通过。Redis/Milvus 未运行，未做真实服务故障注入及大规模临时盘容量测试。

2026-11-04 AI 向量索引存在性探测误判：通过捕获 Milvus 的 SearchRequest 发现，知识/店铺/评论索引的“是否已有文档”检查沿用业务检索相似度阈值（知识 0.35、其他默认 0.65），固定探测词与已有内容不相关时会将非空集合误判为空并触发启动重建。回归测试先在旧实现上观察到知识探测带 0.35 阈值；现将三类存在性检查统一改为接受全部相似度的 top-1 查询，业务检索阈值保持不变，并更新 README 说明。AiRagRetrieverTest、AiVectorIndexPagingTest、KnowledgeBaseInitializerTest 定向通过；全量 mvn -q test 274 项、0 失败/错误、64 项按环境条件跳过；git diff --check 通过。Milvus 未运行，真实集合不同内容分布下的探测行为尚未联调。

2026-11-04 秒杀同一订单消息并发重复投递保护：根据订单状态与库存一致性链路检查，发现两个消费者同时处理同一订单 ID 时，第二个事务可能在首次订单尚未提交期间读到订单不存在，随后因用户券数据库占用已存在而误走“不同订单重复下单”分支，将唯一一份 Redis 预扣回补，导致有效订单与 Redis 库存不一致。服务层回归验证旧实现会执行重复预扣回补脚本；现于占用失败后使用 `SELECT ... FOR UPDATE` 读取当前有效订单 ID，同一 ID 仅完成投递清理、不回补库存，不同 ID 的重复订单继续走原回补路径。新增 mapper 查询和 `VoucherOrderIdTest` 回归，并更新 README。`VoucherOrderIdTest` 与 `PaymentConsistencyIntegrationTest` 定向命令退出码 0；全量 `mvn -q test` 275 项、0 失败/错误、64 项按环境条件跳过；`git diff --check` 通过。当前未配置 PAYMENT_TEST_MYSQL_HOST/Redis，因此真实 MySQL 并发竞争和 Redis Lua 库存结果未在本轮执行；服务层测试覆盖同一 ID 分支。

2026-11-04 AI Agent 工具参数资源上限：检查工具白名单、执行步数与异步线程池后，发现 `searchNearbyShops` 将模型给出的任意正页码传给内容查询；坐标搜索会按页码累计扩大 Redis GEO 查询量，数据库路径也会形成超大分页偏移。三个向量检索工具同样把任意正 `topK` 传给 Milvus。新增回归先观察到 `Integer.MAX_VALUE` 页码和 `topK` 均透传。现增加可配置 `AI_AGENT_INSIGHT_MAX_NEARBY_PAGE`（默认 20），超限页直接返回空结果且不访问内容服务；增加 `AI_AGENT_RAG_MAX_TOP_K`（默认 20），所有向量搜索请求和关键词兜底均将召回量限制在此上限。合法页码及原默认 topK 行为保持不变，README 已说明。`AiRagRetrieverTest` 与 `DianPingAgentToolsTest` 定向通过；全量 `mvn -q test` 278 项、0 失败/错误、64 项按环境条件跳过；`git diff --check` 通过。此为参数与结果数量边界测试，未对真实 Redis GEO/Milvus 做资源负载测量。

2026-11-04 Agent 单轮工具调用与步数边界：在 whitelist 复核中新增合法 `echo` 后接未注册工具的响应回归，旧实现先执行合法工具再拒绝未知工具，确认同一响应的逐项校验存在部分副作用风险。现因请求配置已声明 `parallelToolCalls(false)`，执行器在处理前拒绝任何包含多个工具调用的响应，单个未注册工具仍保持原有拒绝路径。另将 `AI_AGENT_MULTI_AGENT_MAX_STEPS` 限制在 1–50 轮；`Integer.MAX_VALUE` 配置曾可进入 int 循环并导致计数器溢出，现有安全硬上限。`ManualToolAgentLoopExecutorTest` 新增多调用零副作用及极端步数配置回归，定向测试通过；全量 `mvn -q test` 280 项、0 失败/错误、64 项按环境条件跳过；`git diff --check` 通过。README 已同步说明。此验证使用模拟模型响应，未连接真实模型。

2026-11-04 微服务数据库凭据隔离配置：架构图要求用户、内容、订单、AI 服务分别使用独立数据库。检查发现虽已按四个 schema 分库，但 Compose 只给所有服务注入同一用户名/密码，无法按 schema 落实最小权限。为四个服务新增 `USER_DB_*`、`CONTENT_DB_*`、`ORDER_DB_*`、`AI_DB_*` 凭据变量；默认回退原 `SPRING_DATASOURCE_USERNAME/PASSWORD`，HA 订单副本复用订单凭据，并更新部署说明。使用 `docker compose config` 验证四组自定义账号分别映射正确、旧共享凭据回退有效、order-ha profile 两个实例共享订单账号；Compose YAML 展开通过。只做静态配置检查，未连接 MySQL 验证账号授权或启动容器。

2026-11-04 秒杀支付截止条件：沿支付条件更新与超时扫描比较发现，关单 worker 延迟期间，已超过 15 分钟的秒杀订单仍可被迟到回调改成已支付；新增 SQL 条件回归先在旧代码上失败。支付 UPDATE 现检查同一订单投递记录的截止时刻，新 outbox 使用持久化 `timeout_retry_time`，旧迁移行的 NULL 值按 `create_time + 15 分钟` 推算，无投递记录的普通券订单不受影响。新增过期/未过期历史秒杀与普通券 MySQL 集成用例，并更新 README。`PaymentServiceImplTest` 10 项通过；`PaymentConsistencyIntegrationTest` 28 项已编译但因未设置 `PAYMENT_TEST_MYSQL_HOST` 全部跳过，本机 MySQL 要求密码且未提供；全量 `mvn -q test` 305 项、0 失败/错误、69 项按环境条件跳过；`git diff --check` 通过。数据库相关行为仍需在隔离 MySQL 环境验证。

2026-11-04 秒杀 Lua 恢复队列类型保护：检查 Redis Lua 与恢复 outbox 写入顺序后，使用临时隔离 Redis 将恢复队列键设为错误类型，确认脚本在 `ZADD` 报错；此前库存和用户占用写入排在该错误前，脚本运行错误不会回滚这些写入。现于首次写操作前校验共享队列键必须为不存在或 ZSET，其他类型返回系统繁忙码且不预扣，并将 Redis 窗口测试改用随机队列键避免触碰共享键。另修正恢复队列集成测试裸 `ObjectMapper` 未注册 Java 时间模块导致有效恢复 payload 无法解析的问题。四个定向真实 Redis 测试类共 11 项全部通过；临时 Redis 已关闭。全量 `mvn -q test` 306 项、0 失败/错误、70 项按环境条件跳过；`git diff --check` 通过。未模拟 Redis OOM 或进程崩溃持久化边界。

2026-11-04 AI 流式熔断计时隔离：新增 Sentinel RT 回归，模拟模型流立即产出内容、SSE 消费回调延迟，旧 `consume` 将同步发送时间计入模型 Entry 并误开模型熔断。现把一次流调用的异常比例和每次上游读取时延拆成两个 Sentinel 资源：流级 Entry 只记录一次调用错误，read Entry 计量首次建流/`hasNext`/`next`，SSE 投递在两者之外执行。这样慢客户端不计为模型慢调用，部分流失败也不会被多个成功 chunk 样本稀释；上游读慢仍可单独打开 RT 熔断。新增慢消费不熔断、慢上游读取会熔断测试，并验证流级错误比例/半开恢复、MCP 熔断与流式回退；4 个定向类共 11 项通过。全量 `mvn -q test` 308 项、0 失败/错误、70 项按环境条件跳过；`git diff --check` 通过。未连接真实模型或慢速客户端做网络负载测试。

2026-11-04 内部接口鉴权前置：复现发现 `/internal/users` 与 `/internal/content/shops/ids` 的 `List<Long>` 参数会在 Controller 方法内 `InternalApiGuard.verify` 前由 Spring MVC 转换；畸形 ID 加错误 token 会先触发参数异常并返回 500，且在鉴权前做解析工作。为 `micro-user`、`micro-content` 注册匹配 `/internal/**` 的 MVC 拦截器，在参数绑定前验证共享内部 token，拒绝时直接 403；保留方法级校验作为纵深保护。两个 MockMvc 回归确认用户与内容批量接口均在畸形参数绑定前拒绝且不触达目录；定向测试通过。全量 `mvn -q test` 310 项、0 失败/错误、70 项按环境条件跳过；`git diff --check` 通过。未做容器运行态联调。

2026-11-04 秒杀活动元数据缺失恢复验证：沿 Redis 活动元数据刷新失败与 Lua 返回码检查，新增“库存 key 仍存在、活动 hash 缺失”的服务层回归。该场景会由 Lua 返回未就绪码并触发已有按需数据库回源；服务重新写入活动元数据后重试 Lua，成功受理且不覆盖已有 Redis 库存。活动数据库记录不存在时已有测试确认不预扣。验证后确认生产逻辑已满足此恢复路径，本轮只补测试、不改业务代码。`SeckillStockWarmupTest` 5 项通过；全量 `mvn -q test` 311 项、0 失败/错误、70 项按环境条件跳过；`git diff --check` 通过。未连接 Redis 做故障注入或延迟压测。

2026-11-04 新增店铺 Bloom 写入事务边界：先把新增店铺回归改为要求事务提交前不发布 Bloom ID，旧实现立即失败，证实数据库事务回滚无法撤销事务内 Redis Bloom 写入，会留下不存在店铺的假阳性。现将 Bloom ID 发布与详情缓存/GEO 同移至 after-commit 回调；Bloom Redis 写入异常时记录低基数 ID 重试任务，默认每 5 秒重试，可用 `shop.bloom-sync.retry-ms` 调整。内容服务已有启动分页初始化器扫描全量店铺 ID，兜底进程重启造成的内存重试队列丢失。新增提交前无 Bloom 副作用、事务回滚无 Redis 副作用、提交后 Bloom 故障可重试用例，并更新 README。`ShopCacheTest` 9 项、`CacheClientTest` 14 项定向通过；全量 `mvn -q test` 313 项、0 失败/错误、70 项按环境条件跳过；`git diff --check` 通过。未在真实 Redis 和数据库事务环境验证提交/回滚及 Redis 故障场景。

2026-11-04 AI 内容服务 Feign 熔断：检查 AI 工具调用链后发现，MCP 与 RAG 已有 Sentinel 异常比例/慢调用保护，但 `micro-ai` 的 `RemoteContentCatalog` Feign 调用没有熔断器，内容服务故障时 Agent 工具和向量索引可能连续等待连接/读取超时并重复请求。现为所有远程内容查询统一加入低基数资源 `ai:content`，使用既有工具慢调用阈值、异常比例、恢复时间与最小请求配置；原有按操作拆分的 Micrometer 计时保留，Feign connect/read timeout 仍为 500ms/2s。新增 Fake Feign 异常熔断和慢调用熔断测试，确认熔断开启后不再访问客户端；更新 circuit 规则集和 README。熔断/客户端/RAG/MCP 定向组合通过；全量 `mvn -q test` 315 项、0 失败/错误、70 项按环境条件跳过；`git diff --check` 通过。未连接真实 Nacos 或内容服务验证网络超时及跨实例独立熔断行为。

2026-11-04 RocketMQ 非成功发送状态重试：复核秒杀 outbox 与关单投递确认逻辑，并用本地 RocketMQ 2.3.3 API 确认 `syncSend` 返回 `SendResult`，可正常返回 `FLUSH_DISK_TIMEOUT`、`SLAVE_NOT_AVAILABLE` 等非 `SEND_OK` 状态而不抛异常。此前延迟关单路径会将这种未确认状态标为 `timeout_sent=1`，只依赖 30 秒数据库补扫兜底；现在下单与关单投递都显式要求 `SEND_OK`，否则按既有持久化重试记录处理，关单任务不会误标已发送。新增非成功状态两条回归，确认分别保留下单 outbox 与重试关单任务；`SeckillOrderDispatchWorkerTest` 10 项通过，相关恢复 Redis / Payment 集成测试命令退出码 0；全量 `mvn -q test` 317 项、0 失败/错误、70 项按环境条件跳过；`git diff --check` 通过。未对真实 Broker 注入非成功状态或验证远程持久化 ACK 行为。

2026-11-04 AI 任务完成与会话摘要原子性：检查发现助手回复及任务状态在 `REQUIRES_NEW` 中提交后，标题、场景和会话更新时间才在独立事务更新；摘要更新失败会留下已完成任务和旧会话列表信息，恢复 worker 不会再重试。现把摘要更新移入同一完成事务，并让正常回复与异常兜底回复都更新摘要；任务 claim token 校验仍在同一事务内，任何持久化步骤失败均回滚助手消息、任务完成状态和摘要。增加服务测试和 MySQL 条件集成回归（摘要更新失败检查三项回滚）。`mvn -q -DskipTests compile`、`AiTaskCompletionServiceTest` 与 `ChatTaskClaimTest` 通过；`AiTaskConsistencyIntegrationTest` 因未设置 `AI_TASK_TEST_MYSQL_HOST` 跳过，真实 MySQL 回滚行为待隔离数据库验证。`git diff --check` 通过。

2026-11-04 共享登录状态故障与撤销边界：复核确认 Gateway 的两秒 token-owner 缓存仅用于限流分桶，业务服务仍逐请求从共享 Redis 校验；Gateway 阻断 `/internal/**`，匿名白名单中的店铺/优惠券写接口均由独立管理员 token 校验。另发现 Redis 会话查询/续期异常虽会中止请求，但通用异常映射返回 HTTP 200；新增专用认证状态异常映射为 503，并确保续期异常时清理 ThreadLocal 身份。发现 `expire` 返回 false（例如 token 在读取期间已被登出）被忽略，旧 UserDTO 仍会用于当前请求；现续期未确认时清除身份，让受保护请求走 401。新增 Redis 读故障、续期故障 503、并发撤销拒绝回归；更新登出和上传鉴权测试显式模拟成功续期。认证/登出/上传/异常映射定向通过；全量 `mvn -q test` 320 项、0 失败/错误、70 项按环境条件跳过；`git diff --check` 通过。未连接真实 Redis 做登出并发故障注入。

2026-11-04 店铺类型缓存击穿：并发回归先确认两个请求同时遇到缓存 miss 时，旧实现会各自查询数据库（数据库读取计数为 2）。现以 Redisson 固定键分布式锁串行冷缓存重建，并在持锁后复查缓存；Redis 初次读取、锁服务不可用仍回源数据库，缓存写入失败仍返回查询结果。新增并发用例模拟两个请求共享锁与缓存，验证仅有一次数据库读取；更新 README 缓存治理说明。`ShopTypeServiceImplTest` 全部通过；`git diff --check` 通过。并发测试使用 Redisson 锁模拟，未连接真实 Redis 做多实例冷启动压测。

2026-11-04 过期秒杀订单禁止发起支付：复核支付回调与超时关单的条件更新后，发现回调已检查持久化的秒杀支付截止时间，但 `createPayment` 只检查 `status=未支付`。关单补扫延迟时，过期订单仍可能返回支付创建成功，随后回调又拒绝入账。先加 SQL 条件回归并在旧实现上复现失败，再让支付创建与回调共用同一截止条件；无秒杀投递记录的普通券订单仍不受该期限限制。新增 MySQL 集成用例覆盖过期且关单未运行时不写 pay_type，README 已同步。`PaymentServiceImplTest` 10 项通过，编译通过；`PaymentConsistencyIntegrationTest` 29 项因未配置 `PAYMENT_TEST_MYSQL_HOST` 全部跳过，数据库行为待隔离 MySQL 验证；`git diff --check` 通过。

2026-11-04 AI Agent 坐标参数边界：先新增回归并在旧实现上复现：聊天请求只给经度、直接推荐给越界经度时仍进入持久化/Agent 调用；模型生成的附近店铺工具参数（越界、NaN、缺少坐标）也直接调用内容服务。现 AI 聊天与推荐允许不传坐标，但若提供必须同时传入且经纬度有限并位于合法范围；附近店铺工具还要求正分类 ID 和有效完整坐标，无效参数返回空结果且不调用内容服务。更新 README。`DianPingAgentToolsTest` 与 `AiAgentServiceImplTest` 定向通过；`git diff --check` 通过。未做外部模型生成异常参数的线上联调。

2026-11-04 MCP 外部调用超时上限：检查发现 SSE MCP SDK 与 legacy HTTP 都直接使用可配置 timeout，仅设 500ms 下限；误设极大值会让单次工具长期占用 AI worker，熔断要等调用返回才记录。新增共享超时归一化逻辑，把两种传输限制在 500ms–30s，默认 2.5s 不变；补充负值、正常值、`Integer.MAX_VALUE` 上限测试及 README 参数说明。`McpTimeoutsTest`、`LocalLifeMcpSseTest`、`LocalLifeMcpCircuitTest` 定向测试通过。使用本地模拟 MCP SSE 服务验证握手、工具调用与熔断降级；未测真实慢速外部 MCP 网络。

2026-11-04 AI 查询长度上限：检查发现聊天文本无长度约束，既写入 MySQL `TEXT`，又会进入模型上下文和 RAG 关键词兜底；推荐查询与模型生成的 RAG 工具查询也没有上限。新增 8,000 字符统一硬上限，在聊天/推荐持久化或调用模型前、以及四类 RAG 查询进入向量库/关键词处理前拒绝超长内容；两个聊天输入框同步设 `maxlength=8000`。先加回归并确认旧实现会继续进入服务调用，修复后 `AiAgentServiceImplTest` 与 `AiRagRetrieverTest` 共 18 项通过，`npm run build` 通过；README 已同步。`git diff --check` 通过。未对模型供应商的 token 计量或真实数据库 TEXT 极限做线上测试。

2026-11-04 AI 队列拒绝与租约恢复竞态：验证发现线程池拒绝新任务后先释放数据库租约，再单独写入繁忙回复；恢复 worker 若此时领取任务，`completeRejected` 的待处理状态条件更新失败，事务回滚而另一个 Agent 已启动。现在繁忙回复和任务完成使用原 claim token 在同一 `REQUIRES_NEW` 事务内完成；仅保存失败时释放租约交给恢复机制重试。调整拒绝回归验证同 token 完成且不提前释放；`ChatMessageOverloadTest`、`ChatMessageCreatedEventListenerRecoveryTest` 定向通过。`git diff --check` 通过。未做真实 MySQL 并发故障注入。

2026-11-04 秒杀活动缓存并发版本保护：检查发现两个管理员并发修改同一活动时，较早数据库事务的 `afterCommit` 回调可能晚于较新回调执行，Redis `putAll` 会让旧活动窗口或上架状态覆盖新值。活动表新增 `activity_version`，活动更新时间在数据库内原子递增版本；Redis Lua 比较版本后才整组更新活动 hash。缓存刷新失败的重试任务只记券 ID，并在重试时重新读取数据库当前活动版本；同实例并发失败状态按版本保留较新待办。补充缓存失败/旧回调回归、服务层版本回读测试、真实 Redis 条件测试和 Mapper 集成断言，README 与微服务部署文档增加旧库迁移步骤。`SeckillActivityCacheTest` 2 项、`VoucherServiceActivityTest` 2 项、补热相关测试 11 项通过；MySQL Mapper 与 Redis 版本 Lua 集成测试因 `PAYMENT_TEST_MYSQL_HOST`、`PAYMENT_TEST_REDIS_HOST` 未配置各跳过 1 项；`git diff --check` 通过。真实多实例并发及 Redis Lua 行为待有环境变量时验证。

2026-11-04 Agent 工具结果上下文预算：检查发现模型返回的工具结果虽然审计日志截断至 1,200 字符，但完整结果仍会追加到后续模型消息；评论向量结果包含数据库原始正文，模型可重复请求工具并累积大量上下文。现单个工具响应限制 8,000 字符，整轮工具响应累计限制 24,000 字符；达到累计上限时不再调用后续工具，并根据已有信息结束 Agent。新增大结果截断与累计预算停止回归，README 同步说明。`ManualToolAgentLoopExecutorTest` 10 项、`DianPingAgentToolsTest` 3 项、`AiRagRetrieverTest` 13 项通过；`git diff --check` 通过。测试使用模拟工具结果，未对真实模型 token 计量和供应商上下文窗口压测。

2026-11-04 AI SSE 并发连接保护：核对发现流断开不会取消已持久化 AI 任务（保持后台完成和轮询恢复是正确的），但连接限流按用户+会话计数，用户可创建多个会话并累积大量 120 秒 emitter。新增每用户每实例并发连接上限，默认 8，超额连接返回 HTTP 429；完成、超时、发送失败统一释放连接名额。测试覆盖同用户满额拒绝、不同用户隔离和发送失败后的名额回收。`ChatSseServiceImplTest` 4 项、`ChatMessageCreatedEventListenerRecoveryTest` 3 项通过；Redis SSE/会话协调集成测试因未配置 `PAYMENT_TEST_REDIS_HOST` 各跳过 1 项；`git diff --check` 通过。连接上限为单实例本地状态，多实例总量随 AI 实例数线性增加。

2026-11-04 Gateway 后端连接超时：检查 Gateway 路由未配置重试过滤器，确认写请求不会被 Gateway 自动重放；内部 Feign 客户端为只读 GET 并已有 500ms 连接/2s 读取超时。发现 Gateway 未配置连接超时，使用 Spring Cloud Gateway 默认 30s；现在设为可通过 `GATEWAY_CONNECT_TIMEOUT_MS` 覆盖的 2s，保留 120s 响应超时以兼容 SSE。新增 YAML 配置回归，微服务部署说明同步。Gateway `GatewayHttpTimeoutConfigurationTest` 与 `BusinessRateLimitConfigurationTest` 定向通过；网关路由集成测试依赖 `GATEWAY_TEST_REDIS_HOST`，未在本轮运行。

2026-11-05 SSE 断连与流式恢复核对：按架构描述检查服务端断连行为，确认 AI 消息先持久化为任务，断开连接不取消后台生成，完成结果写入会话消息，重连后可通过消息列表恢复；因此不把断连改成取消任务，避免破坏可靠性。总流式超时由配置控制，Reactor timeout 会取消上游订阅，现有慢流回归已验证取消和候选答案补全。定向 ChatSseServiceImplTest 4 项、ChatMessageStreamCircuitTest 3 项、ChatMessageCreatedEventListenerRecoveryTest 3 项通过；RedisChatSseServiceTest 1 项因缺少 CACHE_TEST_REDIS_HOST 跳过。未发现需修复缺陷，本轮仅记录检查结论。

2026-11-06 店铺 GEO 索引失败恢复核对：检查店铺管理更新后的详情缓存、GEO 索引与补偿路径。缓存刷新失败会删除旧缓存；GEO 更新失败放入进程内重试队列，定时重试，进程重启时 ShopGeoInitializer 从数据库按页重建，因此本轮未发现需新增持久化补偿的缺口。并发更新与 GEO 命令交错仍可能短暂出现索引滞后，后续可通过定期全量重建进一步收敛，但基础功能已有启动和失败重试恢复，本轮不扩大到并发架构改造。此结论基于代码与单测覆盖，未连接真实 Redis/多实例验证。

2026-11-06 支付与超时关单一致性回归校正：检查支付创建/回调与逾期关单竞争、数据库库存回补和 Redis 幂等补偿链路。真实 MySQL 运行暴露两处集成测试断言问题：订单 pay_type 在表结构中为 NOT NULL DEFAULT 1，拒绝支付应保留默认值；dispatch 测试沿用 @BeforeEach 的 order 1 待办数据，却断言只有新建任务，现清除该夹具以隔离 backlog 断言。未改业务迁移逻辑。使用本机忽略的私有配置连接临时随机 MySQL 库，PaymentConsistencyIntegrationTest 29 项中 25 项通过、4 项因缺少 Redis/RocketMQ 环境配置跳过；PaymentServiceImplTest 10 项、OrderTimeoutRecoveryWorkerTest 6 项、OrderStockSyncWorkerTest 5 项全部通过。

2026-11-06 Gateway 限流配置未传入 Compose：核对后发现 Gateway YAML 支持五类路由各自的速率/突发环境变量，部署文档也说明可调整，但 Compose 的共享 gateway 环境锚点未传递这十个变量，故主实例和 gateway-ha 副本始终使用 YAML 默认值。已将十项参数加入共享环境锚点，提供与 YAML 相同的默认值，并补充微服务部署说明。使用 docker compose config --format json 分别检查默认配置和自定义值 GATEWAY_AUTH_RATE=3、GATEWAY_AI_BURST=9；默认配置展开正确，自定义值同时出现在主网关与 HA 副本。Gateway BusinessRateLimitConfigurationTest、GatewayHttpTimeoutConfigurationTest、GatewayReadinessHealthTest 共通过；未配置测试 Redis，未运行真实限流和跨网关 Redis 集成测试。

2026-11-06 MCP 超时与白名单降级回归：复核 MCP 启用但服务超时/失败以及工具不在白名单时的行为，服务会返回 source=local-fallback、degraded=true 的本地结果；白名单校验发生在网络调用前。新增本地 HTTP 延迟服务超时测试和白名单拒绝测试，验证超时触发兜底、未授权工具请求数为 0。LocalLifeMcpCircuitTest 5 项、LocalLifeMcpSseTest 1 项、McpSseToolClientConcurrencyTest 1 项、McpTimeoutsTest 1 项全部通过；未发现需修复业务缺陷，未连接真实第三方 MCP 服务。

2026-11-06 RAG 知识资源部署包核验：检查关键词 fallback 从 classpath:knowledge/*.txt 加载 UTF-8 文本、按空行切分段落并进行关键词匹配；资源加载成功后在实例内缓存，向量库不可用或无有效召回时可走本地知识。Maven 可执行包构建成功，jar tf 确认 BOOT-INF/classes/knowledge/hmdp.txt 已打包；AiRagRetrieverTest 14 项和 KnowledgeBaseInitializerTest 4 项全部通过，覆盖关键词命中、SVIP 大小写、Milvus 失败回退、过滤约束、资源单次加载和并发首次读取。未发现需修复问题；未在真实 Milvus 服务或运行中的 fat JAR 环境联调。

2026-11-06 Milvus 降级启动与 readiness 边界核验：确认本地配置默认 AI_AGENT_BOOTSTRAP_FAIL_FAST=false，Milvus client/collection 初始化异常会切换进程内 SimpleVectorStore；知识库检索在向量异常/空召回时仍可用 classpath 关键词兜底。Compose AI 服务显式设 AI_AGENT_BOOTSTRAP_FAIL_FAST=true，向量存储/索引启动失败会阻止容器就绪；微服务 readiness 检查 DB 与 Redis 这两项硬依赖，Milvus 的服务部署由 fail-fast 启动门控，不把故障实例作为健康 AI 服务接流。CommonConfigurationTest 7 项、KnowledgeBaseInitializerTest 4 项、AiRagRetrieverTest 14 项全部通过。未连接真实 Milvus/验证容器故障恢复；未发现需修复缺陷。

2026-11-06 Feign 内部服务鉴权回归：核对跨服务拓扑为 content→user、ai→content；两个调用角色均加载内部令牌 Feign 拦截器，user/content/ai 服务端对 /internal/** 校验共享令牌，错误/缺失令牌返回 403；Gateway 无内部接口路由。新增 InternalFeignConfigurationTest 直接确认 Feign RequestTemplate 会附带配置的 X-Internal-Token。InternalFeignConfigurationTest 1 项、MicroserviceIsolationTest 6 项、InternalUserControllerLimitTest 3 项、InternalContentControllerPagingTest 2 项全部通过；未做容器间真实 Feign/Nacos 联调，未发现需修复缺陷。

2026-11-06 Gateway Nacos readiness 误报：复现 Gateway 已完全启动、Redis 恢复而 Nacos Naming 状态为 DOWN 时，/actuator/health/readiness 仍返回 200；原 readiness 只包括 readinessState 与 Redis，Nacos 服务发现不可用时 Docker 可能仍把网关视为可接流。新增条件化 
acosDiscovery 健康项，复用 Nacos Naming 健康指标，并将其加入 Gateway readiness；Nacos discovery 显式关闭时不创建该 Bean。部署说明同步更新。Gateway readiness HTTP 回归验证 Redis UP/Nacos DOWN→503、两者 UP→200；Nacos 指标 DOWN/UP 映射测试通过。完整 Gateway 测试共 20 项，15 项通过，5 项因缺少 GATEWAY_TEST_REDIS_HOST 跳过，0 失败/错误。依赖实现核对确认 Nacos selectInstances(..., true) 只取健康实例。未连接真实 Nacos/Docker 验证；git diff --check 未发现空白错误（仓库存在 LF/CRLF 转换警告）。

2026-11-06 退出登录与 Gateway token 缓存边界核验：检查用户退出删除共享 Redis 登录态；后端每个受保护请求重新查 login:token:<token> 并要求 LoginInterceptor 有用户身份，退出后的下一次受保护请求立即拒绝。Gateway 2 秒 token→user 映射只生成限流 key，不参与认证，不会让撤销 token 继续访问业务接口；Redis 查询/刷新失败会拒绝身份并作为服务不可用处理。RefreshTokenInterceptorTest 6 项、UserLogoutControllerTest 1 项、UserServiceImplTest 3 项，以及 Gateway BusinessRateLimitConfigurationTest 10 项、TokenUserIdCacheTest 2 项全部通过。未发现需修复安全缺陷；未做真实 Redis 多实例登出联调。

2026-11-06 秒杀异步受理状态与 Redis token 检查后续转查：本轮依用户目标检查登出撤销窗口，确认网关 token owner 缓存仅供限流，后端每个请求仍校验 Redis，未发现可绕过认证的残留窗口。同步核对已有秒杀前端状态查询发现，本轮后续应聚焦 Redis 已受理但 outbox/MQ 尚未落库时的查单身份路径；未扩大本次范围。

2026-11-05 秒杀受理结果待确认时前端丢失订单号：回归测试复现 Redis 执行异常后，接口仅在错误文案中返回订单号、Result.data 为空，前端拦截器丢弃错误响应数据，页面无法启动查单。Result.fail 增加兼容性重载；秒杀不确定结果统一返回 pending 标识和字符串订单号；前端保留失败响应 data，并复用现有异步订单记录和状态轮询。VoucherOrderIdTest 全部通过；前端 npm run build 通过（仅有 Sass legacy JS API 弃用提示）；git diff --check 通过。未连接真实 Redis/MQ 环境验证 Redis 响应丢失窗口。

2026-11-05 AI SSE 断线后的消息轮询错误处理：复核服务端消息事务提交后异步执行、SSE 仅为推送通道，客户端断线可由历史消息查询恢复。发现前端 SSE 失败切换轮询后，任一次 getMessages 网络异常会立即终止轮询，虽后台任务仍运行，界面会显示失败。现轮询期间单次查询异常继续重试；整个轮询窗口结束时若最近查询仍失败，提示任务仍在后台且可稍后查看，发送请求本身仍保持原有防重复提交行为。前端 npm run build 通过（Sass legacy JS API 弃用提示）；静态构建产物已恢复。前端无现成单测框架，本轮未增加依赖；未做真实浏览器断网/恢复联调。

2026-11-05 AI 会话断网后查看已恢复回答：检查确认用户消息、AI 任务在同一事务写入，SSE 断线不取消后台任务；历史消息接口可恢复已完成回复。前端已有重新打开会话时从后端刷新历史，但轮询查询故障提示未说明此恢复路径。现对“任务仍后台处理但暂时无法查询”的情况提示用户稍后重新打开会话查看。npm run build 通过，git diff --check 通过；无前端单测框架，本轮不增加依赖。此前已存在的轮询单次查询重试与 SSE 注册先于发送等改动属于工作树既有变更，本记录仅覆盖本次提示调整。

2026-11-05 登录验证码发送失败反馈：核对登录弹窗发现 `sendCode()` 的 Promise 未捕获，接口失败会成为未处理异步错误；按钮发送期间也可重复点击。新增发送中加载/禁用状态、明确错误提示和 try/finally；仅验证码发送成功后启动 60 秒倒计时。前端 `npm run build` 通过（存在 Sass legacy JS API 弃用提示），`git diff --check` 通过；静态构建产物已恢复。未连真实短信服务（当前项目为日志模拟验证码）。

2026-11-05 登录启动校验遇到服务故障时误清 Token：核对后端无效 Token 返回 401，共享 Redis 登录态服务故障返回 503；前端 initAuth 原先捕获任何异常都 clearAuth，网络超时或 503 会清掉仍可能有效的 Token。现在仅 401 清会话；暂时无法验证时保留 Token、清空当前内存中的用户资料/私有缓存，避免错误身份展示，并提示网络恢复后刷新重试。前端 npm run build 与 git diff --check 通过（Sass legacy JS API 弃用提示）；静态构建产物已恢复。未在真实浏览器模拟 Redis 503。

2026-11-05 商家名称检索分页参数校验：回归测试先复现 `/shop/of/name?current=0` 继续进入查询链并在数据库访问前触发空查询对象错误。名称检索现在拒绝小于 1 的页码，并沿用项目已有查询边界限制最多前 1000 条（每页 10 条）。`ShopControllerTest` 3 项全部通过，覆盖保存接口兼容、非法页码和超出边界页码；`git diff --check` 通过。未连接真实数据库执行列表查询。

2026-11-05 首页商家翻页失败后跳页及末页误请求：复核发现 loadMore 在请求前递增页码，失败会使重试跳过该页；名称检索/分类接口第 100 页满页时 hasMore 仍为 true，会向已限制 1000 条的后端请求第 101 页。改为请求成功后提交页码与列表（刷新失败保留旧列表），并在达到 1000 条上限时返回 hasMore=false。前端 npm run build 和 `git diff --check` 通过（Sass legacy JS API 弃用提示）；构建静态产物已恢复。项目暂无前端单测框架，未增加依赖；未连接数据库做真实翻页联调。

2026-11-05 笔记图片上传类型校验：新增回归先复现已登录用户可将 HTML 上传并获得成功响应。上传接口现仅接收扩展名与 MIME 一致的 PNG/JPG/JPEG/GIF/WebP 文件，并拒绝空文件；拒绝请求不会写磁盘。`UploadControllerSecurityTest` 全部 5 项通过，覆盖匿名上传/删除限制、合法图片上传、HTML 拒绝、空文件与类型不匹配拒绝、图片引用所有权删除；`git diff --check` 通过。校验依据请求文件名和客户端 MIME，未做文件魔数解码，不能作为完整恶意文件扫描；当前前端发布表单仍采用手动填写图片地址。

2026-11-05 笔记发布表单接入图片上传：确认前端没有 `/upload/blog` 调用，用户只能手动填图片 URL，无法完成应用内选图发布。新增 blogApi.uploadImage(FormData) 与资料页多图选择上传入口；每次上传成功自动追加服务端返回路径并显示缩略图，最多 9 张，单张失败时保留之前成功项，上传期间禁用发布/重置。原手动地址字段保留兼容。前端生产构建通过（Sass legacy JS API 弃用提示），静态托管构建产物已恢复；`git diff --check` 通过。前端浏览器与真实文件存储未联调。

2026-11-05 笔记发布服务端字段校验：检查确认前端表单原将店铺 ID 标注为可选并允许提交 null，但单体与微服务数据库脚本均定义 `tb_blog.shop_id NOT NULL`；服务层也未验证关联店铺或图片上限，错误会延迟到数据库。现服务端先验证正店铺 ID 与店铺存在、标题/正文非空及列长度、图片字符串非空且不超过 9 张/2048 字符；前端店铺 ID 改为必填并做正整数校验，标题和正文长度与列定义一致。新增缺少/不存在店铺与超过 9 图回归；`BlogServiceImplTest` 全部通过，前端生产构建通过（Sass legacy JS API 弃用提示），`git diff --check` 通过，构建产物已恢复。未连接真实 MySQL 验证不同部署库。

2026-11-05 笔记图片上传 URL 与 Gateway 静态资源目录：最初发现 uploads 卷挂在 `/app/static/imgs` 与 `/blogs/...` URL 不匹配，后续又确认直接挂卷根到 `/app/static` 会让持久卷遮住镜像前端静态文件，且阻止后续镜像前端更新。现将 uploads 根只读挂到 `/app/uploads`，Gateway 静态位置分为镜像 `/app/static/` 与上传 `/app/uploads/`，保持 `/blogs/...` URL 和内容服务 `/data/imgs` 写入结构。新增 `GatewayStaticResourceConfigurationTest` 验证两个静态位置；`docker compose config --quiet` 与 gateway-ha profile 的主/副网关和内容服务卷目标断言通过，`git diff --check` 通过。未启动容器或通过 HTTP 实际请求图片。

2026-11-05 单体模式图片上传静态资源目录对齐：复核发现上传接口默认仍写旧 Windows Nginx 目录，而 Spring Boot 默认只提供 classpath 静态资源；单体启动后 `/blogs/...` 图片无本项目内可用的文件服务。新增默认 `CITYAI_UPLOAD_DIR=./uploads`，将同一目录同时配置为上传存储根与 Spring 文件静态根；content profile 对齐 `/data/imgs`，Compose 网关将共享 uploads 卷只读映射到单独的 `/app/uploads` 静态位置，避免覆盖镜像中的前端文件。更新 README 与微服务说明，并新增配置回归验证单体默认、微服务默认和自定义目录均使上传根与静态根一致。`UploadStaticLocationConfigurationTest` 3 项、`UploadControllerSecurityTest` 5 项全部通过；`git diff --check` 通过。未启动完整单体服务进行 HTTP 图片请求。

2026-11-05 笔记图片静态 GET 回归：将上一轮仅检查静态目录配置的测试补强为真实 Spring MVC 请求。测试在上传根创建临时 `blogs/example.png`，通过 Boot `WebMvcAutoConfiguration` 的静态资源处理器请求 `/blogs/example.png`，确认 HTTP 200 且响应字节与文件一致；同时保留单体/微服务默认目录和自定义目录一致性断言。`UploadStaticLocationConfigurationTest` 4 项、`UploadControllerSecurityTest` 5 项全部通过；`git diff --check` 通过。覆盖 MVC 实际静态资源读取，不包含完整服务、Gateway 容器网络或浏览器展示联调。

2026-11-05 图片上传单文件大小边界：回归用例确认原本没有显式 multipart 限制配置。单体与 micro-content profile 现都设置单文件 8 MiB、请求 10 MiB；发布页显示限制，并在上传前跳过超过 8 MiB 的所选图片、继续上传其余合规文件。更新 README 与部署说明。`UploadStaticLocationConfigurationTest` 5 项、`UploadControllerSecurityTest` 5 项全部通过；前端生产构建通过（Sass legacy JS API 弃用提示），静态构建产物已恢复，`git diff --check` 通过。未做真实浏览器大文件上传；浏览器预检提供及时反馈，服务端 multipart 限制作为最终边界。

2026-11-05 未发布笔记图片清理：验证发现上传图片在草稿重置/关闭时没有清理路径，而旧删除逻辑只允许删除已被已发布笔记引用的图，无法清除草稿图片。图片现按用户 ID 分目录保存；登录用户可清理自己的未发布图，其他用户目录路径拒绝，已发布笔记引用图禁止删除。前端发布成功保留图，草稿关闭/重置异步清理，上传或发布期间阻止关闭/重置以避免请求竞态。`UploadControllerSecurityTest` 7 项通过；`npm run build` 通过（Sass legacy JS API 弃用提示），静态产物已恢复，`git diff --check` 通过。未做浏览器与文件存储的端到端联调。

2026-11-06 AI 回复处理中误清空当前会话：确认 AI 页面和侧边助手在 `pending` 时仍允许清空窗口；复现后发现会话 ID 与加载消息被立即清空，已发出的回复可能无法归入原会话。store 的 `clearHistory` 在处理中直接保持状态，两个入口按钮同步禁用。新增前端 Node 回归测试；`node --test test/ai-stream.test.mjs` 3 项通过（新测试修复前按预期失败），`npm run build` 通过（Sass legacy JS API 弃用提示），静态产物已恢复，`git diff --check` 通过。

2026-11-06 快速切换历史 AI 会话时选中项被旧响应覆盖：通过两个受控延迟请求复现，先点的会话响应后到时会覆盖用户最后选择。为切换请求加递增序号，仅最新请求可更新当前会话；新建会话和清空窗口会使在途切换响应失效。`node --test test/ai-stream.test.mjs` 4 项通过（新测试修改前按预期失败），`npm run build` 通过（Sass legacy JS API 弃用提示），静态产物已恢复，`git diff --check` 通过。

2026-11-06 AI 会话列表刷新失败被误显示为空：回归复现网络错误被 catch 成空数组，进而清除缓存列表和当前会话 ID。刷新失败现保留缓存数据与当前 ID，并在会话面板显示可重试错误提示；刷新成功清除错误状态，未登录时维持原有空列表行为。`node --test test/ai-stream.test.mjs` 5 项通过（新测试修复前按预期失败），`npm run build` 通过（Sass legacy JS API 弃用提示），静态产物已恢复，`git diff --check` 通过。

2026-11-06 商家详情笔记误依赖全站热门分页：确认详情页仅扫描热门接口前三页再按店铺过滤，低热度门店的真实笔记会漏显。新增公开的按店铺分页查询 `/blog/of/shop`，按 ID 倒序并沿用作者/点赞信息补全；接口校验店铺 ID、页码及前 1000 条边界，前端详情页改用定向查询。`BlogControllerShopQueryTest` 2 项、`BlogServiceImplTest` 8 项通过，前端 AI 回归测试 5 项通过，`npm run build` 通过（Sass legacy JS API 弃用提示），静态产物已恢复，`git diff --check` 通过。未连接数据库做端到端商家数据核对。

2026-11-06 首页探店热榜请求失败无提示：确认首页将 `/blog/hot` 异常静默转换为空数组，用户无法区分无内容和接口故障且没有重试入口。新增加载中/空列表/失败提示；失败不清空已加载数据，提供防重复点击的重试按钮。`node --test test/*.test.mjs` 5 项通过；`npm run build` 通过（Sass legacy JS API 弃用提示），静态产物已恢复，`git diff --check` 通过。没有浏览器网络故障模拟。

2026-11-06 支付方式请求可并发覆盖：复核服务端 `createPayment` 会更新未支付订单的支付方式，确认页面支付按钮虽显示 loading 仍可切换方式重复提交，查状态也可与支付并行。现在支付、模拟支付和订单状态查询互斥，按钮按操作状态禁用，loading 只显示当前支付操作。`node --test test/*.test.mjs` 5 项通过，`npm run build` 通过（Sass legacy JS API 弃用提示），静态产物已恢复，`git diff --check` 通过。未对真实支付渠道做并发联调。

2026-11-06 我的订单仅存浏览器本地：核对发现无后端订单历史查询，跨设备或清理本地存储后无法找回已有订单。新增按当前登录用户分页查询接口，每页 20 条、最多 1000 条，补齐优惠券标题、店铺 ID、金额与订单状态；个人中心合并服务端订单与本地未落库记录，查询失败保留本地缓存并提示。新查询 DTO 将雪花订单号序列化为字符串，避免 JavaScript 安全整数精度损失。`VoucherOrderHistoryControllerTest` 2 项和 `VoucherOrderHistoryTest` 1 项通过；前端回归测试 5 项与生产构建通过（Sass legacy JS API 弃用提示），静态构建产物已恢复，`git diff --check` 通过。未连接数据库进行跨设备运行联调。

2026-11-06 个人中心订单历史仅显示第一页：复核上轮订单查询发现服务端有 20 条分页，但前端只请求第一页，超过 20 条的跨设备订单无法继续查看。订单弹窗新增“加载更多”，逐页取数并按订单 ID 合并去重，加载失败保留已有列表；本地仅有记录继续保留，已标记为服务端记录的订单不误显示“移除本地记录”。前端回归测试 5 项通过，`npm run build` 通过（Sass legacy JS API 弃用提示），静态产物已恢复，`git diff --check` 通过。未连接数据库验证第 2 页真实记录。

2026-11-06 秒杀订单尚未落库时刷新状态误显示“未知状态”：核对状态接口确认 `0` 是已受理但仍在异步创建，并非订单状态码。个人中心刷新订单时现在保留创建中标志，不覆盖待支付状态并提示稍后重试；秒杀初始记录标记创建中，订单落库确认后清除此标志，服务端同步记录也清除。`node --test test/*.test.mjs` 5 项通过；`npm run build` 通过（Sass legacy JS API 弃用提示），静态产物已恢复，`git diff --check` 通过。未连接 MQ/数据库模拟长时间订单落库延迟。

2026-11-06 商家详情优惠券接口失败被误报为空列表：确认 `listByShop` 异常被吞成 `[]`，页面显示“暂无可领取优惠券”且无重试方式。现分开显示加载中、成功空列表和失败提示；可重试，重复请求期间禁用重试，并在失败时保留已有优惠券数据。`node --test test/*.test.mjs` 5 项通过；`npm run build` 通过（Sass legacy JS API 弃用提示），静态产物已恢复，`git diff --check` 通过。未做浏览器网络故障模拟。

2026-11-06 快速切换商家详情时旧数据覆盖新页面：确认同名 `/shop/:id` 路由复用 Vue 组件，旧实现只在挂载时加载。现监听店铺 ID 变化、重置详情状态，并用请求序号阻止过期详情/优惠券结果写入；购买中的请求使用原店铺快照记录订单，防止路由变化后订单误关联新店。复核同时发现秒杀订单 `creationPending` 只写入临时对象，现写入持久化订单并在确认落库后清除。新增 user store 回归覆盖 pending 标记持久化与清除；`node --test --test-concurrency=1 test/*.test.mjs` 6 项通过，`npm run build` 通过（Sass legacy JS API 弃用提示），静态产物已恢复，`git diff --check` 通过。未在浏览器快速切店或真实支付过程中做端到端联调。

2026-11-06 个人中心重复请求用户资料：确认登录初始化已通过 userStore 获取 /user/me，资料页挂载后仍再次调用 profileApi.getProfile()，产生冗余请求且第二次失败会回退默认头像昵称。移除重复请求，资料页直接读取已认证的 userStore.profile。前端 Node 回归测试 6 项通过，npm run build 通过（Sass legacy JS API 弃用提示），构建生成的静态产物已恢复，git diff --check 通过。未进行浏览器与后端联合验证。

2026-11-06 个人中心笔记加载失败被误报为空：确认 `/blog/of/me` 异常被 catch 成空数组，页面因此显示“当前还没有发布笔记”，且没有重试入口。资料页现区分加载中、失败和成功空列表，失败保留已有笔记并提供重试，加载中防止重复请求。前端 Node 回归测试 6 项通过，生产构建通过（Sass legacy JS API 弃用提示），构建生成的静态产物已恢复，`git diff --check` 通过。未进行浏览器网络故障模拟。

2026-11-06 分类页接口失败无恢复入口：确认分类列表请求在 onMounted 中未捕获异常，失败后分类页空白且无法重试；商家查询失败只弹消息，也无法恢复。新增分类和商家加载失败状态及重试入口，区分加载中/空列表；商家查询用请求序号确保快速搜索时只有最新结果生效并保留旧列表供失败时查看。前端生产构建通过（Sass legacy JS API 弃用提示），Node 回归测试 6 项通过，构建产物已恢复，git diff --check 通过。未在浏览器模拟断网或快速搜索。

2026-11-06 分类页恢复提示与商家详情评论降级说明：复核发现分类接口失败会造成页面无分类且无重试，商家查询失败也只弹消息；补充分类/商家错误、重试及加载/空状态，快速搜索只接受最后一次结果。另确认商家笔记接口失败被静默转成空列表，页面误称评论接口未实现；保留演示评论降级并明确提示“笔记暂时加载失败”。前端生产构建通过（Sass legacy JS API 弃用提示），Node 回归测试 6 项通过，构建产物已恢复，git diff --check 通过。未做浏览器断网或快速搜索模拟。

2026-11-06 AI 历史会话内容加载失败无提示：确认切换会话时 getMessages 拒绝会从 UI 事件处理链冒泡为未处理异常，当前会话虽然未被覆盖但用户不知道切换失败且无重试入口。store 现捕获错误并保留原会话/消息，记录失败会话 ID；会话面板显示错误及重试按钮。新增回归验证失败后当前会话与消息保留并设置重试目标。Node 回归测试 7 项、前端生产构建通过（Sass legacy JS API 弃用提示），构建产物已恢复，git diff --check 通过。未做浏览器断网模拟。

2026-11-06 AI 新建会话失败无提示：确认 startNewConversation 未捕获创建接口异常，UI 事件链出现未处理拒绝且无重试入口。store 现阻止重复创建、维护加载与错误状态，创建失败保留当前会话/消息并提供重试入口；创建成功清理旧切换错误。新增回归验证创建失败后状态保留和错误可见。Node 回归测试 8 项、生产构建通过（Sass legacy JS API 弃用提示），构建产物已恢复，git diff --check 通过。未做浏览器断网模拟。

2026-11-06 AI 回复期间发送草稿被清空：确认输入框在 pending 时仍可提交；submitMessage 先清空本地输入，store 随后因正在处理而忽略请求，导致用户草稿丢失。现在提交函数先检查 pending，发送按钮同步禁用，草稿留在输入框等待当前回复完成。前端生产构建和 Node 回归测试 8 项通过（Sass legacy JS API 弃用提示），构建产物已恢复，git diff --check 通过。未做浏览器交互模拟。

2026-11-06 AI 草稿保护覆盖侧边助手：复核发现主 AI 页面已在 pending 时阻止提交，但侧边抽屉仍先清空输入再由 store 忽略请求，存在相同草稿丢失。抽屉提交增加 pending 前置检查并禁用发送按钮，与主页面行为一致。前端生产构建、Node 回归测试 8 项通过（Sass legacy JS API 弃用提示），构建产物已恢复，git diff --check 通过。未做浏览器双入口交互模拟。

2026-11-06 分类页关键词搜索被忽略：复现路径为分类页提交关键词；homeApi.searchShops 只要收到 category 就调用 /shop/of/type 且不传关键词，后端按类型结果原样返回。现在该接口接收可选 name 并在数据库类型条件中追加名称模糊查询，保持分页；关键词搜索走数据库筛选以保证匹配结果分页正确。原无关键词调用兼容，地理位置查询也保留原逻辑。ShopControllerTest 4 项、前端 Node 测试 8 项及生产构建通过（Sass legacy JS API 弃用提示），构建产物已恢复，git diff --check 通过。未连接数据库验证中文模糊搜索结果。

2026-11-06 分类商家列表只能查看第一页：确认后端/API 返回分页信息，但分类页只请求并展示第 1 页。现加入加载更多，维护页码/hasMore，追加结果按店铺 ID 去重；搜索/分类变化重置第一页，失败重试原页，列表标题区分已加载数量和总量。搜索事件显式重置，避免清空关键词的空字符串被误当作加载下一页。前端生产构建、Node 回归测试 8 项通过（Sass legacy JS API 弃用提示），构建产物已恢复，git diff --check 通过。未用数据库或浏览器验证多页真实店铺数据。

2026-11-06 首页分页期间新搜索被忽略：确认 loadHomeData 在任意请求进行中直接 return，用户切换关键词后旧分页结果仍写入，下一页可能用新关键词继续旧页码。现在翻页请求中发起刷新会排队，当前请求结束后立即按最新搜索条件重新请求第一页；翻页期间仍阻止重复分页。前端生产构建、Node 回归测试 8 项通过（Sass legacy JS API 弃用提示），构建产物已恢复，git diff --check 通过。未做浏览器延迟网络下的实际搜索切换模拟。

2026-11-06 首页搜索排队后失败仍可能混页：复核分页竞态修复发现新搜索刷新失败时旧页码/列表仍保留，点击加载更多会拿新关键词请求旧页码。现在错误状态显示重试入口并阻止加载更多；重试固定从第一页开始；分页请求固定使用已成功加载的关键词/城市，搜索条件尚未应用时也禁用翻页。加载更多按钮只在 hasMore 时显示。前端生产构建、Node 回归测试 8 项通过（Sass legacy JS API 弃用提示），构建产物已恢复，git diff --check 通过。未用浏览器模拟新搜索失败或翻页竞态。

2026-11-06 AI Agent 工具边界与队列过载复核：沿模型响应→工具注册表→手动执行器以及任务提交→专用线程池链路检查，确认工具仅来自显式注册回调，单轮并发工具调用在执行前拒绝，未知工具拒绝执行，最大步数限制为 1–50；队列满时新请求保存繁忙答复，恢复任务释放租约供下次补扫。ChatMessageOverloadTest、ChatMessageCreatedEventListenerRecoveryTest、AiAgentTaskExecutorMetricsTest、ManualToolAgentLoopExecutorTest 定向测试通过，0 失败/错误；测试会打印预期模型/队列异常日志。未连接真实模型、Redis 或跨实例 AI 任务环境，本轮未发现需修复的确定性问题。

2026-11-06 RAG 关键词兜底与部署资源复核：当前工作树运行 AiRagRetrieverTest、KnowledgeBaseInitializerTest、CommonConfigurationTest 定向测试全部通过，覆盖向量故障/空召回时本地 fallback、过滤约束、并发首次加载和关闭 bootstrap 时不擅自建集合；fat JAR 中确认包含 BOOT-INF/classes/knowledge/hmdp.txt。当前 Milvus 19530 端口未监听，未进行真实 Milvus 查询和向量维度联调；未发现需修复的确定性缺陷。

2026-11-06 AI 线程池指标 HTTP 回归超时：默认全量 mvn -q test 首次发现 AiAgentTaskExecutorMetricsEndpointTest 因启动等待 15 秒短于实际双端口 Spring Boot 启动时间（约 24 秒）而失败；独立重跑也复现启动在断言超时后仍继续完成，确认是测试门限过短而非 Actuator 端点缺失。将启动事件等待延至 45 秒；定向测试随后通过，验证五项线程池指标从管理端口可读、业务端口为 404。修正后全量 mvn -q test 387 项、0 失败/错误、76 项按环境条件跳过；前端 
pm test 15 项通过，
pm run build 通过（Sass legacy JS API 弃用提示），git diff --check 通过。

2026-11-06 部署拓扑静态校验：检查当前 Compose 默认服务与 order-ha、gateway-ha 两个 profile 的共享环境、健康门控、端口和 uploads 卷挂载。首次展开按预期因缺少必填数据库密码、内部令牌和 AI Key 被 Compose 拒绝；使用临时占位环境变量后，三种配置均 docker compose config --quiet 通过，且不接触个人私密配置。mvn -q -f gateway/pom.xml -DskipTests package 通过。Docker daemon 未运行，故未构建镜像或启动容器；静态配置未发现新的错误。

2026-11-06 共享登录态真实 Redis 双客户端回归：新增环境可选的 RefreshTokenInterceptorRedisTest，使用两个独立 Lettuce 客户端和 Redis DB 15 写入/读取随机登录 Token；验证跨客户端解析用户身份并刷新过期时间，再由写端删除会话后验证读端下一次受保护请求为 401。测试 finally 仅删除随机测试键，不执行 FLUSHDB。使用本机 Redis 实际运行 RefreshTokenInterceptorRedisTest、RefreshTokenInterceptorTest、UserLogoutControllerTest，共 8 项通过；git diff --check 通过。真实多 JVM / Gateway 到各业务服务的 Token 联调仍依赖完整微服务环境。

2026-11-06 逻辑缓存并发 Redis 回归断言校准：运行现有真实 Redis ShopLogicalCacheRedisTest 时发现 lateEmptyResultCannotOverwriteCommittedShop 仍期望迟到的数据库空查询返回 null；实际 Redis 并发分支检测到另一个请求已提交正向缓存，会把该最新店铺返回给迟到请求。按当前正确语义将断言改为返回店铺 ID 42，不改生产代码；DB15 定向测试 4 项全部通过，覆盖单次冷回源、并发互斥、迟到空值与异步刷新不覆盖新值。测试只用随机键并由 finally 清理；git diff --check 通过。

2026-11-06 缓存互斥锁真实租约续期验证：现有锁续期仅有 mock 调度器覆盖；新增真实 Redis 慢回源并发用例，让第一次互斥回源持续超过 10 秒锁 TTL，第二请求在第 8 秒进入。DB15 实测第二请求未抢到锁/未重复回源，首请求写入后两个请求均返回店铺 ID 71；随后 ShopLogicalCacheRedisTest、CacheClientTest、ShopCacheTest 合计定向运行通过，0 失败/错误。Redis 测试只使用随机键并清理自身数据；git diff --check 通过。此验证覆盖单机客户端对共享 Redis 的租约续期，不等同于完整多 JVM 微服务部署测试。

2026-11-06 AOP 与 Gateway 共享限流真实 Redis 复核：确认无效/格式错误 Token 在 Gateway 按可信客户端 IP 分桶，有效 Token 按 Redis 用户 ID 分桶；登录/验证码路由始终按 IP 限流；AOP 与 Gateway Redis 限流执行失败均不放行业务。为避免使用本机 Redis DB0，本轮给 Gateway 路由集成测试增加 GATEWAY_TEST_REDIS_DATABASE 可选隔离库，并修正测试明确使用正式 GatewayApplication、关闭 Nacos 时 readiness 排除 nacosDiscovery。此前该测试因 Spring Boot 配置类发现歧义和 Nacos readiness 缺项无法启动，修正后使用真实 Redis DB15 运行 GatewayRouteIntegrationTest 5 项全部通过，覆盖两 JVM 共用限流键；AOP SlidingWindowLimitRedisTest 1 项通过，BusinessRateLimitConfigurationTest 定向测试通过。Redis 测试不清库；git diff --check 通过。未做 Redis 宕机时 Gateway 最终 HTTP 状态的真实网络故障注入，容器/Nacos 联调仍待 Docker 环境。
2026-11-06 秒杀 Lua 与恢复路径真实 Redis 复核：检查秒杀活动窗口、库存/用户原子预扣、预留与派单恢复键、重复预约补偿、订单派单恢复批次与库存回补测试夹具，确认本轮测试使用 PAYMENT_TEST_REDIS_DATABASE=15 且仅删除随机 ID 对应键/成员，不执行 FLUSHDB。实际运行 OrderStockReleaseIntegrationTest、SeckillActivityWindowRedisTest、SeckillDuplicateReservationRedisTest、SeckillActivityCacheRedisTest、SeckillOrderDispatchRecoveryRedisTest 共 13 项，0 失败/错误；覆盖错误活动窗口不扣库存、重复回补幂等、坏恢复消息不饿死后续有效订单和旧活动版本不覆盖新值。当前代码链路未发现新的确定性缺陷，因此没有生产逻辑修改。恢复测试为预期坏消息打印 ERROR 日志；日志编码在当前终端显示乱码，但 Surefire 报告确认测试通过。
2026-11-06 Compose 外部基础设施地址配置修正：复核文档承诺可配置 MySQL/Redis 地址与 Milvus 端口后，发现 MYSQL_HOST、REDIS_HOST 仍硬编码为 host.docker.internal，且 Compose 未传 SPRING_AI_MILVUS_PORT。将 MySQL、Redis 主机改为支持环境变量覆盖，并为 AI 容器透传 Milvus 端口（默认 19530）；更新部署说明。使用占位域名和非默认端口展开 Compose JSON，确认 user/content/gateway/ai 服务以及 order-ha、gateway-ha 副本继承正确；默认和双 HA profile 的 docker compose config --quiet 均通过，git diff --check 通过。未构建或启动容器，Docker daemon 状态限制运行级联调。
2026-11-06 Nacos/Feign 服务边界回归复核：沿 MicroserviceApplication profile 激活、ServiceIsolationFilter、内容到用户与 AI 到内容的 Feign 客户端、内部令牌拦截及远程调用熔断路径检查，未发现确定性实现缺陷。运行 MicroserviceIsolationTest、RemoteContentCatalogCircuitTest、OpenFeignTimeoutTest、InternalFeignConfigurationTest、UserDirectoryBatchingTest、InternalUserControllerLimitTest、InternalContentControllerPagingTest，共 20 项通过、0 失败/错误/跳过。当前 Nacos 8848 未运行，因此没有进行真实 Nacos 注册发现与跨 JVM 调用联调。
2026-11-06 店铺详情误用演示数据修复：确认 normalizeShop 按店铺 ID 将演示店铺电话、营业状态、地址、评分、封面、菜品和评论混入真实接口记录；种子数据真实店铺 ID 1–4 与演示数据重合，产生可复现的虚构商家信息。移除按 ID 填充演示字段，缺失属性改为明确的未知/暂无状态，菜品和评论仅展示真实接口数据或 AI/探店结果，并为无数据状态补充提示。新增两项 shop-normalization 回归测试；前端全量 npm test 17 项通过，npm run build 通过（仅 Sass legacy JS API 弃用提示），git diff --check 通过。未用浏览器连接实际数据库验证页面展示。
2026-11-06 登录过期响应竞态修复：确认 Axios 会在任意 401 响应时清除当前 localStorage Token；可复现旧 Token 发起的慢请求在重新登录后才返回 401，导致新会话被误删。请求时记录实际附加的 Token，只有该 Token 仍与当前存储值一致时才清理并广播过期事件；新增两项请求级回归测试分别覆盖旧 Token 延迟 401 与当前 Token 401。前端全量 npm test 19 项通过，npm run build 通过（仅 Sass legacy JS API 弃用提示），git diff --check 通过。
2026-11-06 个人中心账号切换异步响应竞态修复：复核用户 ID 分区存储后发现 initAuth 的旧会话 `/user/me` 响应即使在新登录完成后返回，仍会覆盖 Pinia 当前用户并加载旧账号收藏；旧初始化异常也可能清除新会话。资料、签到数请求现绑定发起时的 Token，仅当它仍是当前 Token 时提交状态，过期初始化响应/异常忽略；新增延迟初始资料响应回归，验证新账号资料、收藏和 Token 保持一致。前端完整 npm test 20 项通过，git diff --check 通过。
2026-11-06 个人中心跨账号异步数据隔离：发现 ProfilePage 只监听 isLoggedIn 布尔值，账号切换时不会重载；用户退出或切换期间，迟到的笔记、订单分页、收藏详情或订单状态响应可能继续更新当前视图/存储。改为监听用户 ID 与会话 Token，切换时清空旧资料、关闭旧账号弹窗并使旧请求 ticket 失效；所有用户私有异步读回在提交前校验 ticket。新登录验证资料阶段同时清空旧 profile/signCount。新增 user-scope ticket 测试及登录切换回归；前端全量 npm test 23 项通过、npm run build 通过（Sass legacy JS API 弃用提示），git diff --check 通过。
2026-11-06 已完成订单仍可重复发起支付的前端状态修正：服务端只接受 status=1 的待支付订单，但详情页支付按钮过去仅判断订单是否落库，已支付、已核销、已取消和退款状态仍允许点击并最终收到服务端拒绝。新增 canPayOrder 统一条件（订单 ready 且 status=1），三种支付入口共用，状态区显示真实订单状态；查状态按钮在后台轮询期间禁用。新增状态 1–6/未知值回归测试。前端全量 npm test 24 项通过、npm run build 通过（Sass legacy JS API 弃用提示），git diff --check 通过。
2026-11-06 个人订单分页上限与秒杀待确认保留验证：后端 `/voucher-order/my` 每页 20 条并拒绝第 51 页；前端原先只凭当前页有 20 条继续显示加载入口，导致第 50 页后重复请求必然失败。新增订单分页 helper 在当前页 50 停止；补测第 49/50 页边界，并确认远程订单首屏刷新不会移除或清除未同步落库的本地秒杀 pending 订单。前端全量 npm test 26 项通过、npm run build 通过（Sass legacy JS API 弃用提示），git diff --check 通过。

2026-11-06 博客点赞并发竞态修复：确认 likeBlog 在 Redis ZSet 读状态、SQL 点赞数增减、ZSet 更新之间无串行保护；同一用户快速并发切换时可能读到相同旧状态，令 SQL 计数与 Redis 成员状态不一致。对用户与博客维度加 Redisson 锁，覆盖完整读改写；前端列表与详情点赞按钮在当前请求期间禁用该博客的重复点击。新增后端锁顺序回归测试；定向 Maven 测试通过，前端全量测试 26 项通过、生产构建通过（仅 Sass legacy JS API 弃用提示），git diff --check 通过。未运行真实 Redis/数据库并发压测，也未浏览器验证页面交互。

2026-11-06 发布笔记图片来源混用修复：确认上传图片会登记到待清理列表，而图片地址输入框仍可手动修改；关闭/重置会删除上传文件，即使表单已将字段改为该文件地址，可能误删待发布图片。另手动图片地址与上传同时使用会被上传结果覆盖。现有上传图片时锁定地址输入框，存在手动图片地址时阻止上传；提取图片草稿规则并新增回归用例。前端全量测试 27 项通过，生产构建通过（仅 Sass legacy JS API 弃用提示），git diff --check 通过。未做浏览器端实际上传/关闭交互或真实文件系统端到端验证。

2026-11-06 笔记图片重复地址修正：回归检查发现用户可在手动图片地址中包含上传接口返回的路径，随后上传同一路径时 helper 会重复追加，直到后端拒绝超过 9 张图片。合并上传结果时现按文件名去重，并扩展草稿规则测试；前端全量 27 项测试、生产构建和 git diff --check 均通过（构建仅有 Sass legacy JS API 弃用提示）。未做浏览器真实文件上传验证。

2026-11-06 收藏列表状态与失败反馈修正：确认收藏没有服务端接口，仅存于按用户 ID 分区的浏览器 localStorage；个人中心过去将详情请求全部失败显示成“当前还没有收藏店铺”，把网络故障误报为空收藏。收藏弹窗标题现说明数据保存在此浏览器，加载中、部分失败、全部失败分别提供状态/重试，保留成功加载的店铺和原收藏 ID。新增纯函数回归覆盖部分/全部失败及空收藏。前端全量测试 28 项、生产构建、git diff --check 通过（仅 Sass legacy JS API 弃用提示）。未连接真实后端或浏览器验证线上请求错误呈现。

2026-11-06 店铺详情笔记加载失败状态修正：确认店铺详情并非读取评论表，而是读取关联店铺的探店笔记；请求失败时标题提示失败，但主体仍显示“暂无探店笔记”，且没有恢复入口。区块改称“探店笔记”，增加独立重试按钮，仅重新读取当前店铺笔记；区分加载中、空结果、失败、成功四种状态，并用请求序号防止旧请求覆盖新店铺的 loading 状态。新增状态优先级回归测试；前端全量测试 29 项、生产构建和 git diff --check 通过（仅 Sass legacy JS API 弃用提示）。未通过浏览器或真实网络故障注入验证交互。

2026-11-06 店铺详情过期请求隔离：沿路由切换链路复现旧店铺详情请求慢返回的风险：旧详情在路由已切换后仍会启动优惠券与笔记查询，并递增子请求编号，可能让新店铺结果被旧请求作废。现在详情响应后、启动并行读取前就检查请求序号和店铺 ID；详情赋值及错误处理共用同一有效性判断。新增请求有效性边界测试；前端全量测试 30 项、生产构建、git diff --check 均通过（构建仅有 Sass legacy JS API 弃用提示）。尚未用浏览器延迟网络模拟路由切换。

2026-11-06 登录验证码倒计时与手机号状态修正：确认验证码倒计时在弹窗关闭后仍继续，手机号改动也不会清空旧验证码/倒计时；慢返回的旧手机号发送请求还可能在弹窗关闭或切号后启动过期倒计时。现在关闭/重置和手机号变化会清理计时器、清空验证码并使旧发送请求失效；只有请求仍对应当前手机号且弹窗仍打开时才显示成功并开始倒计时。新增倒计时清理回归测试；前端全量测试 31 项、生产构建、git diff --check 通过（构建仅有 Sass legacy JS API 弃用提示）。未用浏览器验证手机号切换和延迟响应交互。

2026-11-06 首页热榜账号切换响应隔离：确认热榜 `/blog/hot` 和点赞操作按发起请求时的 Token 决定 `isLike`，但首页没有像个人中心那样隔离会话；旧账号请求晚返回会把旧点赞状态写进当前账号页面。热榜加载现在以 Token 建立作用域，过期响应不提交并排队刷新当前会话数据；点赞请求跨 Token 返回时不更新本地笔记/不向新用户报告旧请求错误，而是重新读取热榜。新增 Token 不匹配时请求票据失效的回归断言；前端全量测试 32 项、生产构建、git diff --check 通过（仅 Sass legacy JS API 弃用提示）。未用浏览器模拟账号切换与慢接口。

2026-11-06 店铺详情订单账号隔离：确认页面只监听店铺路由，用户退出或切换账号后仍可能展示旧账号的待支付订单；延迟返回的支付/状态响应也可能写入新账号本地订单。订单面板现在仅对登录用户显示，账号私有数据恢复完成后重新同步当前账号订单；购买、秒杀、查单和支付回包都绑定用户作用域票据，失效响应不再写入状态。新增店铺订单按用户订单列表筛选及私有状态恢复版本测试；前端全量测试 34 项通过，生产构建通过，git diff --check 通过（仅 Sass legacy JS API 弃用提示）。未用浏览器模拟跨账号慢响应或真实支付网络。

2026-11-06 分类页搜索条件与分类切换修正：复现 AppHeader 搜索事件携带新输入词，但分类页直接把事件参数当作 reset 布尔值，实际请求仍读旧 keyword，导致回车后搜不到刚输入的内容；同时分类切换和新搜索保留旧商家列表。现在显式采用事件中的关键词，并在切换条件时清空旧结果、重置分页及失效旧请求，失败页可按失败页码重试。新增搜索关键词解析回归测试；前端全量测试 36 项通过，生产构建通过，git diff --check 通过（仅 Sass legacy JS API 弃用提示）。未通过浏览器与真实接口验证分类数据呈现。

2026-11-06 AI 聊天响应丢失后的重复提交修复：确认前端仅在 `/ai/agent/chat` 收到成功响应后才标记已发送；后端每次 POST 都会新增消息和 AI 任务，因此请求已提交但响应丢失时，降级流程会重复提交同一轮。现在记录请求是否已发出，结果不确定时仅轮询数据库消息、不再重发；明确的业务拒绝或 4xx 直接返回错误。新增模拟“服务端已接受但客户端响应丢失”的回归测试；前端全量测试 37 项通过，生产构建通过，git diff --check 通过（仅 Sass legacy JS API 弃用提示）。未连接真实模型或网络故障注入。

2026-11-06 AI 轮询超时状态提示修正：确认轮询 75 秒后若每次查询成功但回复尚未生成，会错误提示普通等待超时；若查询请求失败则错误声称任务仍在后台。现在区分已提交任务仍处理、回复状态暂不可查询和未提交请求超时三种结果，使用户知道可稍后重开会话取回回复。新增两类轮询结果测试；前端全量测试 38 项通过，生产构建与 git diff --check 通过（构建仅有 Sass legacy JS API 弃用提示）。未连接真实后端做长任务/网络中断联调。

2026-11-06 未处理运行时异常 HTTP 状态修正：新增 MockMvc 回归后确认，WebExceptionAdvice 的 RuntimeException 兜底将 Result.fail 以 HTTP 200 返回，导致监控和调用方误判服务器异常为成功响应。为兜底处理器设置 HTTP 500，Result 错误体保持不变。WebExceptionAdviceTest 共 5 项通过，覆盖运行时异常 500、限流 429、Redis 503 和 ResponseStatusException 状态透传；git diff --check 通过。

2026-11-06 关注重复请求与自关注校验：确认 tb_follow 没有用户/目标唯一约束，follow 接口重复请求会重复插入关系；同时允许用户关注自己。现按用户与目标关系使用 Redisson 锁串行化，先查关系再插入，重复关注时重写 Redis Set 以修复索引；拒绝无效目标和自关注。新增 FollowServiceIdempotencyTest，覆盖重复关注仅插入一次、Redis Set 可重建及自关注拒绝，定向 Maven 测试通过。未运行真实 Redis/数据库多实例并发验证，现有数据库重复记录仍需单独治理后才适合增加唯一索引。

2026-11-06 优惠券创建失败响应修正：确认普通券控制器忽略 `save()` 返回值，会把数据库未写入报告为成功；秒杀券服务也忽略主券与秒杀库存记录两次保存结果，可能提交不完整活动。现普通券写入失败返回业务失败，秒杀券任一数据库写入失败抛异常触发事务回滚，缓存仅在保存成功后发布。新增控制器与服务回归测试；VoucherControllerAdminTest、VoucherServiceCreateTest、VoucherServiceActivityTest 定向测试通过。

2026-11-06 AI SSE 错误响应信息修正：确认流连接使用原生 fetch，不经过 Axios 统一错误拦截；429/503 等响应原先统一显示“建立 AI 流式连接失败”，丢失后端错误说明和 HTTP 状态。现在解析错误响应 JSON，保留 `Result.errorMsg` 并附上状态，非 JSON 响应回退到含状态的通用提示。新增 429/503 回归测试；前端全量 npm test 49 项通过、生产构建成功（仅 Sass legacy JS API 弃用提示），git diff --check 待收尾确认。

2026-11-06 本地验证码登录恢复：核对发现没有短信供应商，前端仍提示查看后端日志获取模拟验证码，但为日志脱敏已不再记录验证码，导致本地用户无法完成登录。新增默认关闭的 `CITYAI_AUTH_MOCK_CODE_ENABLED`：启用时验证码由接口响应返回、登录弹窗自动填入并标明本地模拟码；关闭时响应不包含验证码，后端日志继续脱敏。更新 README 环境变量说明与服务/前端回归测试；UserServiceImplTest、前端账号定向 11 项和全量前端 50 项通过，生产构建通过（仅 Sass legacy JS API 弃用提示）。未做真实短信集成验证。

2026-11-06 Windows 本地启动配置补齐：复核发现推荐的 `start.cmd`/`start.ps1` 启动流程只从 `start.local.json` 白名单加载环境变量，而验证码模拟开关未列入模板或白名单，导致双击启动用户无法按文档启用本地登录。现将 `CITYAI_AUTH_MOCK_CODE_ENABLED` 加入加载项与示例配置，默认值保持 `false`；PowerShell JSON 解析与脚本配置静态断言通过，git diff --check 通过。

2026-11-06 首次登录建号失败处理：复核登录链路发现新用户插入 `save(user)` 失败时被忽略，服务仍继续生成并返回无有效用户 ID 的登录会话。回归测试确认旧逻辑未抛错；现检查保存结果及生成 ID，失败时终止登录，避免返回不可用 Token。UserServiceImplTest 全部 6 项通过，git diff --check 通过。

2026-11-06 会员等级数值映射修正：对照单体和微服务 DDL，发现 `tb_user_info.level` 是 0–9 的 `tinyint`，Java 实体却使用 Boolean，非 0/1 等级会在查询/JSON 中丢失原值。将字段改为 Integer，保持实际存储等级；新增序列化回归覆盖等级 3 输出为 JSON 数值。UserInfoLevelTest 通过，git diff --check 通过。

2026-11-06 数值状态字段映射复核：继续扫描实体与 DDL 时发现 `tb_blog_comments.status` 同样是 0/1/2 状态，却映射成 Boolean；改为 Integer，并将种子测试的正常状态改为 0。新增统一序列化测试覆盖会员等级 3 和评论状态 2；`EntityNumericStateTest` 2 项通过，Maven 测试编译通过。

2026-11-06 NULL 点赞计数处理：核对 `tb_blog.liked` 后发现字段允许 NULL，而点赞/取消点赞使用普通加减，NULL 会一直保持 NULL，用户操作成功但热度计数不变。点赞现用 COALESCE 从 0 递增，取消点赞对 NULL/0 保持 0，避免无效负数；扩展锁测试检查两条生成的 SQL 并验证两次操作均在锁内。BlogLikeLockTest 定向通过，git diff --check 通过。
