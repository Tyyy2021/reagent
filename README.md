# ReAgent — 可恢复 Agent 运行时

> 自研一个支持**长时任务、工具并发编排、崩溃断点续跑**的 Agent 运行时,挂一个自包含编码闭环作为 demo。
> 本质是一个**领域专用的 durable execution engine**——恢复模型对齐 Temporal 的「workflow replay + idempotent activity」,聚焦 LLM Agent 场景。

`Java 21` · `Spring Boot 3` · `Spring Data JPA` · `MySQL` · `Redis` · `docker-java` · `JDK21 虚拟线程`

[![CI](https://github.com/Tyyy2021/reagent/actions/workflows/ci.yml/badge.svg)](https://github.com/Tyyy2021/reagent/actions/workflows/ci.yml)

---

## 它解决什么

一个普通的 ReAct Agent,进程一崩,正在跑的任务就全丢了:跑了一半的工具副作用、烧掉的 token、积累的上下文,无从恢复。

ReAgent 把 Agent 的每一步都**事件溯源式落库**,进程崩溃/重启后能从数据库**精确重建上下文、从断点续跑**,并对工具副作用给出**可证明的 exactly-once 语义**(诚实分级,而非空喊)。工具在**沙箱**里隔离执行(OS 级硬杀 + 资源限额 + 网络隔离),同一轮多工具在虚拟线程上并发。

## 里程碑

| 里程碑 | 内容 | 状态 |
|---|---|:--:|
| **M1 内核** | 最小 ReAct 闭环:LLM + 循环 + 工具注册 + function calling | ✅ |
| **M2 持久化 + 断点续跑** | 每步落 MySQL;崩溃后事件日志重放重建上下文、`pendingToolCalls` 算断点续跑 | ✅ |
| **M3 工具执行层** | 虚拟线程并发 + 每工具超时 + **子进程 / Docker 双沙箱**(OS 级硬杀、cgroup 限额、断网) | ✅ |
| **M5 编码闭环** | per-task workspace + 文件工具遏制,自包含 TDD 闭环(写码 → 跑测试 → 读真实报错 → 改) | ✅ |
| **exactly-once 副作用** | 账本状态机 + fail-closed 幂等分级 + sandbox journal 对账(L1–L3) | ✅ |
| **M4 流式 + 打断 + 可恢复 SSE** | 事件级 / token 级 SSE 流式;协作式取消(优雅 drain + 硬杀)+ 暂停 / 续跑;事件溯源 `event` 表 + `Last-Event-ID` 断线重连续播 | ✅ |
| **M6 可观测** | OpenTelemetry 全链路 trace(task → step → chat / tool → sandbox),OTLP → Jaeger;跨虚拟线程传 OTel context、taskId 派生 traceId(一个任务一条 trace、跨重启) | ✅ |
| **M7 分布式多 worker** | 租约失败转移 + fencing(epoch 栅栏)+ 跨 worker 控制面 / 流式(Redis Streams)/ 工作区抽象 | ✅ |

## 核心设计(难点密度)

### 1. 崩溃断点续跑(事件溯源)
`message` 表是上下文的**唯一真相源**。启动时 `CrashRecovery` 扫出所有 `RUNNING` 任务,在 JDK21 虚拟线程上 `resume`:按自增 id 重放 `message` 重建 `Context` → `pendingToolCalls()` 算出「欠着结果的工具调用」→ 接着跑。**新任务与恢复任务走同一套 `drive()` 循环**,没有特例分支。优雅关闭(IDE 停止 / 滚动更新)与任务真失败由 `ShutdownState` 区分——前者保持 `RUNNING` 待恢复,只有应用层真错误才判 `FAILED`。

### 2. exactly-once 工具副作用(诚实分级)
真 exactly-once 不可能——DB 提交与「文件已写 / 请求已发」是两个独立系统,无跨系统 2PC 就不可能原子。ReAgent 的做法是**框架提供能力、每个工具诚实声明自己能给的保证**:

- **L1 账本状态机** `PENDING → IN_PROGRESS → DONE | IN_DOUBT`:执行副作用**之前**先把 `IN_PROGRESS` 落库(关键栅栏),恢复时才能区分「一定没做」与「可能做了」。
- **L2 幂等分级**(默认 **fail-closed**):`READ_ONLY` / `IDEMPOTENT` 崩后可安全重放;`SIDE_EFFECTFUL`(任意 shell)默认不盲目重放——忘标的工具按有副作用对待。
- **L3 journal 对账**:沙箱把「命令已完成(exit=N)」作为脚本**最后一步**原子写进 workspace journal;恢复时 `IN_PROGRESS` 的命令**先查 journal**——有 → 对账标 `DONE` 不重跑,无 → 上报 in-doubt。危险窗口从 `[开始执行, 记完账]` 缩到只剩 `[命令执行中途崩]`。

> 全程的安全不变量:**非幂等副作用从不盲目重放**;journal 只把「其实跑完了」的从保守 in-doubt 救回成确定 `DONE`(提升召回)。

### 3. 工具沙箱(OS 级隔离)
- **子进程沙箱**:`setsid` 独立进程组 + 到点 `kill -9 -<pgid>` 整组硬杀(死循环也停得下,不靠线程 interrupt);`ulimit` 限内存。
- **Docker 沙箱**:`--memory` / `--cpus` cgroup 限额 + `--network=none` 断网 + 独立 rootfs,一次性容器跑完即删。
- 一接口两实现,`reagent.sandbox.type` 配置切换。**进程内文件工具也遏制在 per-task workspace 内**(`..` 上爬 / 绝对路径越界被拒),不留绕过口。

### 4. 工具并发
同一轮模型要求的多个工具调用在虚拟线程上**并发执行 + 各自超时**;落库与上下文写回仍串行按原序——`message.seq` 与 `Context` 非线程安全,串行落库很轻,既拿并发收益又天然避开账本/seq 的并发写竞争。

### 5. 流式执行 · 可打断 · 可恢复 SSE
任务在虚拟线程上运行、**与 SSE 连接解耦**:客户端断线 / 重连任务都不中断(本就持久可恢复)。事件经进程内 `TaskEventBus` 发布订阅,`GET /{id}/stream` 以 SSE 推送;模型回答**逐 token 流式**(`chatStream` 解析 DeepSeek SSE delta,跨帧分片的 tool_call arguments 按 `index` 拼装)。
- **打断 = 协作式受控崩溃**:取消(优雅 drain 默认 + `?force` 硬杀升级)/ 暂停 / 续跑在安全点检查;硬杀中途的工具副作用**直接复用 exactly-once 的 in-doubt / journal 对账**,白捡"不重放"的正确性。
- **可恢复 SSE**:非 TOKEN 事件事件溯源式落 `event` 表(自增 id = durable 游标);重连带 `Last-Event-ID` 从断点**精确补播**再无缝转 live——补播与 live 经同一 sink、完全同构,由**每任务锁**保证不漏不重。`message` 表喂模型、`event` 表喂客户端 = **CQRS 读写分离**。

### 6. 全链路 trace(可观测)
手动装配 OpenTelemetry SDK(非 starter,只要业务 span),`task → step → {chat | execute_tool → sandbox.run}` 一棵 span 树经 OTLP 导出 Jaeger;命名 / 属性对齐 OTel **GenAI 语义约定**(`chat {model}`、`gen_ai.usage.*` token、`execute_tool {name}`)。两个真正的难点:
- **跨虚拟线程传 context**:OTel `Context` 是 ThreadLocal,跨不过并发工具的 `pool.submit`——提交前捕获、子线程里 `makeCurrent` 恢复,`execute_tool` span 才挂得回 step span(与显式 `ToolContext` 传任务身份同一问题、同一解)。
- **跨崩溃恢复关联**:**taskId(UUID)派生 traceId**(去横杠 = 32 hex),新任务与每次恢复都以同一"逻辑根"为 parent → **一个可恢复任务无论重启多少次都是同一条 trace**,Jaeger 里直接看断点续跑(对标 Temporal)。零持久化、零特例分支。

### 7. 多 worker 分布式(M7)

单机可恢复升级到**集群可恢复**:多 worker 共享 MySQL,任一 worker 崩了它的在跑任务被别的 worker 接管续跑。
- **租约 + 原子 claim**:`task` 加 `owner_id` / `lease_expires_at` / `lease_epoch`。`claim` 是一条条件 `UPDATE`(`status=RUNNING 且 无主 / 是我 / 租约过期` 才占到)= 跨进程 CAS,MySQL 行锁保证两 worker 抢同一任务只有一个赢。worker 身份默认 `主机名:端口`(重启稳定 → 秒认领自己崩前的任务,k8s 可填 Pod 名)。
- **心跳续租 + 失效扫描接管**:在跑任务由独立调度线程周期续租(与任务执行解耦,长工具也不假过期);每个 worker 周期扫「`RUNNING` 且租约过期」的孤儿并 `claim` 接管 = 真·故障转移。`lease_expires_at` 用 `TIMESTAMP_UTC` 强制 UTC 存取,跨时区 worker 不误判。
- **fencing(epoch 栅栏)**:GC 停顿 / 网络分区可能让两 worker 同时自以为持有任务。`lease_epoch` 每次 claim 单调 +1 作 fencing token:心跳带 epoch 续租,被接管的旧 owner 续租落空 → 打 `FENCED`、安全点干净停手且**绝不改状态**;终态写带 `WHERE owner=me AND epoch=myEpoch` 守卫,旧 owner 跑到终点也写 0 行被挡 —— 杜绝脑裂双写。
- **跨 worker 控制面**:`cancel` / `pause` 落 DB `control_signal` 列,任意 worker 受理、当前 owner 在安全点消费 —— **位置透明**(任务在 A 跑、请求打到 B 也生效)。
- **跨 worker 流式(Redis Streams)**:`StreamTransport` 抽象,in-process(默认、零中间件)/ redis 二选一。redis 模式每任务一个 Redis Stream,`XREAD BLOCK from cursor` 让 **replay 历史与 live 是同一个游标读的连续** —— 客户端连任意 worker 都能看任意任务的实时 token 流,断线重连从游标续读不丢(比 pub/sub 强,且消掉了进程内那套补播 / live handoff)。
- **工作区抽象**:`WorkspaceStore`(checkout + commit),shared-fs 实现 = 所有 worker 挂同一共享盘(NFS/EFS/PVC),失败转移后接管 worker 看得到原 worker 写的文件;留 git / 对象存储 drop-in 缝。

## 架构

```
   HTTP POST /api/tasks
          │
   ┌──────▼─────────┐
   │ TaskController │  提交 / 查状态 / 手动恢复
   └──────┬─────────┘
          │
   ┌──────▼──────────────────────────────────────┐        ┌────────────┐
   │ AgentRunner  ── ReAct 主循环 ──               │──────► │ LlmClient  │
   │  问模型 → 执行工具 → 喂回观察 → … → 完成        │        │(DeepSeek…) │
   └──────┬───────────────────────┬───────────────┘        └────────────┘
   每步 ▲ │ 落库 / 重建            │ 本轮多工具并发(虚拟线程)
        │ │                ┌──────▼───────── ToolExecutor ─────────┐
   ┌────┴─▼──────┐         │ read/write_file/list_dir 进程内,        │
   │ StateStore  │         │   workspace 严格遏制                    │
   │   ↕ MySQL   │         │ run_command ──► Sandbox                 │
   │ task /      │         │     subprocess(setsid 硬杀 + ulimit)    │
   │ message /   │         │     docker(cgroup + --network=none)     │
   │ tool_call   │         │     完成时写 journal(L3 对账依据)        │
   └────▲────────┘         └────────────────────────────────────────┘
        │ 崩溃重启
   ┌────┴─────────┐
   │ CrashRecovery│  扫 RUNNING 任务 → 虚拟线程续跑(与新任务同一循环)
   └──────────────┘
```

## 代码结构

```
com.reagent
├── ReAgentApplication          启动类
├── api/TaskController           REST 入口:提交 / 查状态 / 手动恢复
├── core/                        ★ 内核
│   ├── AgentRunner              ReAct 主循环(可恢复 + exactly-once 决策表)
│   ├── Context / Decision / ToolCall
│   ├── CrashRecovery            启动扫 RUNNING,虚拟线程自动续跑
│   ├── ShutdownState            区分优雅关闭 vs 真失败
│   ├── InFlightTasks            单机护栏:同一任务不并发驱动
│   ├── TaskControl              打断信号:取消(优雅 / 硬杀)/ 暂停 / 续跑(+M7 FENCED)
│   ├── WorkerIdentity           ★M7 worker 稳定身份(租约 owner)
│   ├── LeaseHeartbeat           ★M7 心跳续租 + fence 检测(@Scheduled)
│   ├── FailoverScanner          ★M7 扫租约过期的孤儿任务(@Scheduled)
│   └── FailoverService          ★M7 接管入口(虚拟线程 recover)
├── persist/                     ★ 持久化 + 状态机
│   ├── StateStore               每步落库 + 重建上下文 + 幂等账本
│   ├── TaskEntity/Status/Repository       (+M7 租约 owner_id / lease_expires_at / lease_epoch + control_signal)
│   ├── MessageEntity/Repository
│   ├── ToolCallEntity/Status/Repository   (PENDING/IN_PROGRESS/DONE/IN_DOUBT)
│   └── EventEntity/Repository             事件流投影(Stage4 可恢复 SSE 的 durable 游标)
├── sandbox/                     ★ 工具沙箱
│   ├── Sandbox / SandboxSpec / SandboxResult / SandboxType / SandboxProperties
│   ├── SubprocessSandbox        setsid 进程组硬杀 + ulimit
│   ├── DockerSandbox            docker-java 一次性容器 + cgroup + 断网
│   ├── SandboxRouter            按配置路由两实现
│   ├── WorkspaceStore / SharedFsWorkspaceStore  per-task workspace 抽象(锁死爆炸半径;★M7 留 git / 对象存储 drop-in 缝)
│   └── RunJournal               L3 完成日志:布局 / 读取 / 注入沙箱的 shell 片段
├── tool/                        工具层
│   ├── Tool / ToolRegistry / ToolExecutor / ToolContext / ToolProperties
│   ├── IdempotencyClass         READ_ONLY / IDEMPOTENT / SIDE_EFFECTFUL
│   └── impl/                    read_file / list_dir / write_file / run_command / sleep_ms
├── stream/                      ★ SSE 流式 + 可恢复(M4)+ 跨 worker(M7)
│   ├── TaskEvent                事件信封(eventId = opaque durable 游标;TOKEN 无 id)
│   ├── StreamTransport          ★M7 事件流传输抽象(in-process / redis 二选一)
│   ├── TaskEventBus             in-process 实现:进程内发布订阅 + 每任务锁补播(不漏不重)
│   ├── RedisStreamTransport     ★M7 Redis Streams 跨 worker live 总线(XREAD-from-cursor 连续读)
│   └── EventStore / JpaEventStore   事件溯源:event 表读写,replay 与 live 同构
├── llm/                         模型接入
│   ├── LlmClient                接口(chat / chatStream,屏蔽厂商差异)
│   ├── OpenAiCompatibleClient   OpenAI 协议实现(通吃 DeepSeek / Ollama / 中转)
│   ├── StreamingDecisionAssembler  流式 delta 拼回 Decision(分片 tool_call 按 index 续拼)
│   └── LlmProperties
└── obs/                         ★ 可观测(M6)
    ├── OpenTelemetryConfig      手动装配 SDK(TracerProvider + OTLP→Jaeger;noop 开关)
    └── Trace                    taskId 派生 traceId + 逻辑根 Context + 属性 key 常量
```

## 怎么跑

> 沙箱依赖 Linux 进程组 / cgroup,请在 **Linux 或 WSL2** 上运行(非纯 Windows 原生)。

**前置**:JDK 21;Docker(用 `docker compose` 一键起依赖,见下)。构建用自带的 **Maven Wrapper**(`./mvnw`),无需本机装 Maven。

```bash
# 1. 起依赖:MySQL(自动建 reagent 库)+ Redis + Jaeger,一键拉起
docker compose up -d
#   需 Docker Compose 插件(docker compose version 能显示版本);没有则手动装 MySQL8 + Redis,并建库:
#   CREATE DATABASE reagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
#   默认连 localhost:3306 root/root;不同就设 MYSQL_USER / MYSQL_PASSWORD 或改 application.yml

# 2. 模型:DeepSeek API key 走环境变量(不进代码)
export DEEPSEEK_API_KEY=sk-xxxx       # 备选通义 / Ollama 见 application.yml 注释

# 3. 启动(默认子进程沙箱;建表由 JPA ddl-auto=update 首次启动自动完成)
./mvnw spring-boot:run
#   走 Docker 沙箱:./mvnw spring-boot:run -Dspring-boot.run.arguments=--reagent.sandbox.type=docker

# 看到 "Started ReAgentApplication" + "启动检查:..." 即成功(端口 8080);Jaeger UI http://localhost:16686
```

**多 worker 模式(M7)**:装 Redis(`redis-server`),起多个实例连同库同 Redis:

```bash
# worker A
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --reagent.streaming.transport=redis --reagent.worker.id=A"
# worker B(另一终端)
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --reagent.streaming.transport=redis --reagent.worker.id=B"
```

强杀 A,它在跑的任务被 B 在租约过期后自动 `claim` 接管续跑;客户端连 B 的 `/stream` 能看到在 A(或接管后)跑的任务**实时 token 流**。workspace-root 指向所有 worker 共享挂载的盘(本地同机天然共享;生产用 NFS/EFS)。

**验收**:见 `requests.http`——

- **自包含编码闭环(M5 杀手 demo)**:提交一个 TDD 任务,Agent 在自己的 workspace 里 `write_file` 写函数 + 写测试 → `run_command` 跑 `python3` 测试 → 失败就读真实报错改对,直到通过。
- **崩溃恢复**:提交多步任务 → 趁它在跑强杀进程 → 重启,启动日志打印「发现 N 个未完成任务,后台恢复中」并自动续跑到完成。
- **工具并发**:同一轮两个 `sleep_ms` 各 2s,并发 ≈2s(对照串行 ≈4s)。
- **流式 + 打断 + 重连**:`POST /api/tasks` 立返 taskId;`GET /{id}/stream` 看实时 token / 事件流;`POST /{id}/cancel`(`?force` 硬杀)/ `pause` / `resume`;断开后带 `Last-Event-ID` 重连,精确补播断点之后的事件。

## 验证

一键跑全部测试(不依赖 MySQL / Redis;有 Docker 时沙箱用例也真跑):

```bash
./mvnw test          # 54 个测试,全绿
```

54 个单测(账本状态机迁移 / 幂等分级 fail-closed / 子进程沙箱死循环硬杀 / Docker 隔离 / workspace 遏制 / journal 读写 / 流式 delta 拼装 + token usage / **事件总线补播不漏不重并发压测** / **跨虚拟线程 trace context 传播** / taskId 派生 traceId)+ 两条 e2e:① **崩溃注入**——伪造「崩在记账前」,验证 journal 在 → 对账标 `DONE` 不重放、journal 删 → 上报 in-doubt,两分支副作用都不重跑;② **断线重连续播**——首连收若干事件后断开,带 `Last-Event-ID` 重连,精确补播断点之后的每个事件、不漏不重、追平 `COMPLETED`;③ **M7 跨 worker**(真机双 worker 同库同 Redis)——A `submit` 的任务事件经 Redis Streams 被 B 进程**实时收到**(token 级跨机),接管 worker `checkout` 到同一 workspace;另以 SQL 镜像 / HTTP 端到端验 claim CAS、fence epoch 守卫、跨机 cancel 落 DB 信号。

## 路线图

M1–M7 已落地。后续可选硬化方向:`WorkspaceStore` 的 git / 对象存储后端(去共享盘依赖)、DAG 工具依赖调度、生产化(Flyway 迁移、k8s 部署 + Pod 名作 worker id、跨 worker 传 OTel trace —— `W3CTraceContextPropagator` 已留缝)。
