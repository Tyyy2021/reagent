# ReAgent Hybrid Plan Session Checkpoint

- 日期：2026-07-19
- 状态：用户要求暂停，实施计划仍处于自审阶段
- 工作目录：`/root/reagent`
- 功能 worktree：`/root/reagent/.worktrees/reagent-rag-mcp`

## 已确认决策

- 采用 Java 唯一 Agent 控制面 + 单个 Python capability service。
- Runtime Task 1–4 保持 Java，现有 Task 4 的 21 个工作路径不得覆盖或重置。
- Task 5–14 改为 Java/Python 混合实现。
- 本轮取消“每个 Task 同步产出面试材料”；只交付代码、测试、验收证据和必要技术文档。
- 用户已批准 `2026-07-19-reagent-java-python-hybrid-design.md`。
- 官方版本核对：MCP Java SDK 当前稳定版为 `v2.0.0`；MCP Python SDK 当前稳定版为 `v1.28.1`。
- Spring Boot 3/Jackson 2 计划使用 `mcp-core` + `mcp-json-jackson2`，不使用默认 Jackson 3 的 `mcp` 聚合包。

## 今天已写入的文档

- `docs/superpowers/plans/2026-07-19-reagent-java-python-hybrid-roadmap.md`
- `docs/superpowers/plans/2026-07-19-reagent-python-rag.md`
- `docs/superpowers/plans/2026-07-19-reagent-mcp-incident.md`
- `docs/superpowers/plans/2026-07-19-reagent-demo-delivery.md`

四份新计划共 2,153 行，覆盖 Task 4 收尾门禁和 Task 5–14。旧的 Java-only Task 5–14 计划已增加“被混合方案替代”提示；旧 Runtime Task 1–4 计划仍有效。设计规格状态已改为批准，Python MCP pin 已从 1.28.0 修正为 1.28.1。

## 已完成检查

- 四份计划均包含 writing-plans 要求的标题、Goal、Architecture、Tech Stack、Global Constraints 和 checkbox 步骤。
- 已执行 `git diff --check`，当时无空白错误。
- 已扫描 `TBD`、`TODO`、`implement later`、`fill in details`、`add appropriate` 和 `similar to task`，未发现真正占位描述。
- 已核对 Task 5–14、迁移版本、WAITING_APPROVAL、RECOVERY_REQUIRED 和 SDK pin 的主要映射。

## 明天必须先修正的自审项

1. `V3__incident_intake.sql` 与 `V4__durable_approval.sql` 计划中的外键 `task_id` 必须从 `VARCHAR(36)` 改为与现有 V1 `task.id VARCHAR(255)` 完全一致。
2. Task 5 顺序需要调整：先创建 `pyproject.toml`/lock，才能运行第一个 Python RED contract test。
3. `ValidatedIncident` 未列入文件/接口；应直接统一为已经验证过的 `IncidentRequest`，或补齐明确类型，不能留未定义引用。
4. Python `EmbeddingPort` Protocol 中的 `...` 是合法方法体，但应改成不会被误认为占位符的明确写法或补充统一说明。
5. RAG quality 示例中的 `hit.source_path` 未在前文类型中定义；改成基于 `hit.source` 与 corpus root 的真实文件检查。
6. `fake_ops/alerts.py` 已列文件但缺少拥有它的具体步骤；补充固定告警 fixture 行为或删除该文件项。
7. Task 12 的敏感信息扫描不应搜索通用单词 `evidence`，否则验收报告标题可能误报；改成具体敏感字段模式。
8. 控制台恢复 task/cursor 应使用只保存非敏感 ID 的 `localStorage`，不要依赖 browser restart 后不稳定的 `sessionStorage`。
9. Compose MySQL 初始化应创建 `reagent_app` 与 `fake_ops_app` 两个独立账号并分别授权，不能让同一应用账号同时拥有两个 schema。
10. happy/reject/failover 脚本必须生成并校验各自唯一的 `externalAlertId`，保证连续运行和重跑不会误命中旧 task。
11. 再核对 MCP Java SDK 2.0.0 的 Jackson 2 mapper/transport builder 精确构造 API；官方已确认 JDK Streamable HTTP 基本调用为 `HttpClientStreamableHttpTransport.builder(baseUrl).endpoint("/mcp").build()`。

## 明天恢复顺序

1. 读取本 checkpoint 与批准规格，不重新设计架构。
2. 逐项修正上述 11 个自审项。
3. 对照规格 1–24 节做 coverage matrix，补齐遗漏后再检查类型/方法/字段一致性。
4. 运行：

```bash
git diff --check
rg -ni 'TBD|TODO|implement later|fill in details|add appropriate|similar to task' docs/superpowers/plans/2026-07-19-*.md
rg -n 'VARCHAR\(36\).*task_id|1\.28\.0|ValidatedIncident|source_path|sessionStorage' docs/superpowers/plans/2026-07-19-*.md
```

5. 完成计划文档自审后提交一个 follow-up 修订，再向用户提供执行选择。
6. 用户选择执行方式前不开始 Task 5；实际执行仍先在现有 worktree 完成 Task 4 Step 4.7。

## 当前代码边界

- 本次只修改 `docs/superpowers`，没有修改 Java/Python 产品代码。
- 未修改或暂存根目录用户已有的 `.superpowers/`、`findings.md`、`progress.md`、`task_plan.md`。
- 未修改功能 worktree 中现有 Task 4 的 21 个工作路径。
