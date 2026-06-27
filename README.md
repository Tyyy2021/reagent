# ReAgent — 可恢复 Agent 运行时

> 自研一个支持**长时任务、工具并发编排、崩溃断点续跑**的 Agent 运行时,挂一个自包含编码闭环作为 demo。
> 本质是一个**领域专用的 durable execution engine**——恢复模型对齐 Temporal 的「workflow replay + idempotent activity」,聚焦 LLM Agent 场景。

`Java 21` · `Spring Boot 3` · `Spring Data JPA` · `MySQL` · `docker-java` · `JDK21 虚拟线程`

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
| M4 / M6 / M7 | SSE 流式 + 打断 · OpenTelemetry 全链路 trace · 多 worker 分布式调度 | 规划中 |

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
│   └── InFlightTasks            单机护栏:同一任务不并发驱动
├── persist/                     ★ 持久化 + 状态机
│   ├── StateStore               每步落库 + 重建上下文 + 幂等账本
│   ├── TaskEntity/Status/Repository
│   ├── MessageEntity/Repository
│   └── ToolCallEntity/Status/Repository   (PENDING/IN_PROGRESS/DONE/IN_DOUBT)
├── sandbox/                     ★ 工具沙箱
│   ├── Sandbox / SandboxSpec / SandboxResult / SandboxType / SandboxProperties
│   ├── SubprocessSandbox        setsid 进程组硬杀 + ulimit
│   ├── DockerSandbox            docker-java 一次性容器 + cgroup + 断网
│   ├── SandboxRouter            按配置路由两实现
│   ├── WorkspaceManager         per-task workspace(锁死爆炸半径)
│   └── RunJournal               L3 完成日志:布局 / 读取 / 注入沙箱的 shell 片段
├── tool/                        工具层
│   ├── Tool / ToolRegistry / ToolExecutor / ToolContext / ToolProperties
│   ├── IdempotencyClass         READ_ONLY / IDEMPOTENT / SIDE_EFFECTFUL
│   └── impl/                    read_file / list_dir / write_file / run_command / sleep_ms
└── llm/                         模型接入
    ├── LlmClient                接口(屏蔽厂商差异)
    ├── OpenAiCompatibleClient   OpenAI 协议实现(通吃 DeepSeek / Ollama / 中转)
    └── LlmProperties
```

## 怎么跑

> 沙箱依赖 Linux 进程组 / cgroup,请在 **Linux 或 WSL2** 上运行(非纯 Windows 原生)。

**前置**:JDK 21、MySQL 8、(可选)Docker——子进程沙箱是默认,无 Docker 也能跑。

```bash
# 1. MySQL:建库(建表由 JPA ddl-auto=update 首次启动自动完成)
CREATE DATABASE reagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
# 默认连 localhost:3306 root/root;不同就设 MYSQL_USER / MYSQL_PASSWORD 或改 application.yml

# 2. 模型:DeepSeek API key 走环境变量(不进代码)
export DEEPSEEK_API_KEY=sk-xxxx       # 备选通义 / Ollama 见 application.yml 注释

# 3. 启动(默认子进程沙箱)
mvn spring-boot:run
#   走 Docker 沙箱:mvn spring-boot:run -Dspring-boot.run.arguments=--reagent.sandbox.type=docker

# 看到 "Started ReAgentApplication" + "启动检查:..." 即成功(端口 8080)
```

**验收**:见 `requests.http`——

- **自包含编码闭环(M5 杀手 demo)**:提交一个 TDD 任务,Agent 在自己的 workspace 里 `write_file` 写函数 + 写测试 → `run_command` 跑 `python3` 测试 → 失败就读真实报错改对,直到通过。
- **崩溃恢复**:提交多步任务 → 趁它在跑强杀进程 → 重启,启动日志打印「发现 N 个未完成任务,后台恢复中」并自动续跑到完成。
- **工具并发**:同一轮两个 `sleep_ms` 各 2s,并发 ≈2s(对照串行 ≈4s)。

## 验证

39 个单测(账本状态机迁移 / 幂等分级 fail-closed / 子进程沙箱死循环硬杀 / Docker 隔离 / workspace 遏制 / journal 读写)+ **崩溃注入 e2e**:伪造「崩在记账前」,验证 journal 在 → 对账标 `DONE` 不重放、journal 删 → 上报 in-doubt,两分支副作用都不重跑。

## 路线图

M4 SSE 流式 + 中途打断 · M6 OpenTelemetry 全链路 trace(Jaeger 可视化)· M7 多 worker + 租约/心跳/故障转移的分布式可恢复。
