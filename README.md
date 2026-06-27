# ReAgent

自研可恢复 Agent 运行时。当前进度:**M2 状态持久化 + 崩溃断点续跑**。

> 完整设计、路线图与各里程碑「实现纪实 + 面试 Q&A」见 `C:\Users\taoyong\Documents\ReAgent\DESIGN.md`

## 进度

- **M1 内核 MVP** ✅:最小 ReAct 闭环(LLM + 循环 + 工具),状态在内存。
- **M2 状态持久化 + 断点续跑** ✅:每步落 MySQL,进程崩溃后重启自动从数据库重建上下文续跑,工具调用按 `tool_call_id` 幂等去重。
- **M3 工具沙箱 + 并发**(下一步):工具隔离执行 + 同一轮多工具并发。

## M2 做了什么

- **三张表**:`task`(状态机 RUNNING/COMPLETED/FAILED)、`message`(对话事件日志,**重建上下文的唯一真相源**)、`tool_call`(幂等账本,主键 = 模型给的 tool_call_id)。
- **断点续跑**:启动时 `CrashRecovery` 扫出 RUNNING 任务 → JDK21 虚拟线程 `resume` → 从 `message` 表重放重建 `Context` → `pendingToolCalls()` 算出待补工具 → 接着跑。新任务与恢复任务走**同一套 `drive()` 循环**。
- **幂等**:执行工具前查账本,已 DONE 的复用结果、跳过重复执行(防恢复时重复副作用)。
- **优雅关闭 ≠ 崩溃**:`ShutdownState` 区分进程关闭中断(保持 RUNNING 可恢复)与任务真失败(判 FAILED)。

## 代码结构

```
com.reagent
├── ReAgentApplication      启动类
├── api/TaskController       REST 入口:提交 / 查状态 / 手动恢复
├── core/                    ★ 内核
│   ├── AgentRunner          ReAct 主循环(可恢复:每轮先补欠的工具,再问模型)
│   ├── Decision / ToolCall  模型决策 / 工具调用
│   ├── Context              对话上下文(支持从持久化消息重建 + 算待补工具)
│   ├── CrashRecovery        启动时扫 RUNNING 任务,虚拟线程自动续跑
│   └── ShutdownState        标记"正在关闭",区分关闭中断 vs 真失败
├── persist/                 ★ M2 持久化层
│   ├── StateStore           核心:每步落库 + 从库重建上下文 + 幂等
│   ├── TaskEntity / TaskStatus / TaskRepository
│   ├── MessageEntity / MessageRepository
│   └── ToolCallEntity / ToolCallStatus / ToolCallRepository
├── tool/                    工具层
│   ├── Tool                 工具统一接口
│   ├── ToolRegistry         自动注册 + 生成 function-calling 规格
│   ├── ToolExecutor         执行 + 异常兜底
│   └── impl/                ReadFileTool / ListDirTool
└── llm/                     模型接入
    ├── LlmClient            接口(屏蔽厂商差异)
    ├── OpenAiCompatibleClient  OpenAI 协议实现(通吃 DeepSeek/Ollama/中转)
    └── LlmProperties        配置
```

## 怎么跑

### 1. 准备 MySQL
- 确保 MySQL 在跑,建库:`CREATE DATABASE reagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`
- 默认连接 `localhost:3306` 用户 `root`/`root`;不同就设环境变量 `MYSQL_USER` / `MYSQL_PASSWORD`,或改 `application.yml`。
- 建表无需手动,JPA `ddl-auto=update` 首次启动自动建。

### 2. 准备模型(DeepSeek)
- 去 https://platform.deepseek.com 创建 API key,设环境变量 `DEEPSEEK_API_KEY`(不进代码)。
- 备选通义/Ollama 配置见 `application.yml` 注释。

### 3. 启动
IntelliJ 打开本文件夹(Maven 项目导入)→ **用 JDK 21** 运行 `ReAgentApplication`。
看到 `Started ReAgentApplication` + `启动检查:没有未完成任务` 即成功(端口 8080)。

### 4. 验收
打开 `requests.http`:
- **正常任务**:发「M2 验收 → 1)」那段,响应带 `taskId` + `result`;在 **Run 控制台**看 `--- 第 N 步 ---` 一步步跑。
- **崩溃恢复演示**:发一个多步任务 → 趁它在跑点红色 ■ 停止(模拟崩溃)→ 重新 Run,启动日志会打印 `发现 N 个未完成任务,后台恢复中` 并自动续跑到完成。

## 下一步(M3)
工具沙箱隔离(子进程 → Docker)+ 同一轮多工具并发执行(把当前串行的 `executeTools` 用虚拟线程并发化 + 依赖分析)。
