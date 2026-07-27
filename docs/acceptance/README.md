# Acceptance evidence

ReAgent 的验收材料由测试或运行中的服务生成，不维护手工填写的通过数量。
报告中的任务 ID、审批状态、worker epoch、工单尝试次数和唯一工单数都来自
持久化运行事实。

## Incident evidence

运行：

```bash
./mvnw -B -Pci -Dit.test=IncidentAcceptanceIT verify
```

生成：

- `target/acceptance/incident-evidence.json`
- `target/acceptance/incident-evidence.md`

`IncidentAcceptanceIT` 在断言场景通过后才写入条目。JSON 适合自动审计；
Markdown 是同一组字段的窄表格投影。两者不包含告警完整 payload、工具参数、
日志全文、凭据或 API key。

## Retrieval quality

运行：

```bash
cd services/agent-capabilities
uv sync --locked --all-groups
export HF_HOME="${HF_HOME:-$PWD/../../.superpowers/sdd/hf-cache-task9}"
export HF_HUB_CACHE="$HF_HOME"
uv run --locked pytest -m quality -q
```

生成：

- `services/agent-capabilities/build/reports/rag-quality.json`

报告包含固定评测查询的排名和聚合检索指标。首次运行会下载
`sentence-transformers/all-MiniLM-L6-v2`；后续运行复用 Hugging Face
缓存。离线复验应先准备同一模型缓存，再设置 `HF_HUB_OFFLINE=1` 和
`TRANSFORMERS_OFFLINE=1`。

## Compose scenario evidence

运行三条确定性场景：

```bash
bash scripts/demo-reset.sh
bash scripts/demo-smoke.sh
bash scripts/demo-reject.sh
bash scripts/demo-failover.sh
bash scripts/demo-down.sh
```

每条场景生成新的 run-scoped `externalAlertId`，并只打印有界摘要：
任务状态、审批结论、工单尝试次数、唯一工单数、worker epoch 和工单 ID。
`demo-failover.sh` 在 Python 已提交工单但尚未返回时只杀 Worker A，再由
Worker B 以更高 epoch 接管；同一幂等 key 的尝试次数增加，但唯一工单数
必须保持为一。

CI 的 `compose-smoke-failover` job 还会上传：

- `build/compose-artifacts/compose-ps.txt`
- `build/compose-artifacts/compose.log`
- `build/compose-artifacts/jaeger-traces.json`

这些文件在场景成功或失败时都会收集，便于定位容器状态、有限日志尾和跨服务
trace。Jaeger 导出是运行时查询结果，不是手工制作的示例。

## Full rerun

本机具备 JDK 21、Python 3.12、`uv`、Docker 与 Compose v2 时，可运行：

```bash
bash scripts/verify-all.sh
```

该命令依次执行 Java fast/integration、Python lint/type/fast/integration/
quality，以及 Compose happy/reject/failover。HTTP/状态等待、Compose
命令以及 Maven、uv、pytest 门禁都有上限；失败时脚本打印项目级 Compose
状态和有限日志尾，退出码保持非零。

完整复验先在固定 host 路径准备锁定 MiniLM cache；Compose reset 后再通过
非 root one-off 容器把该缓存复制到新建的 `model-cache` named volume。
因此网络只用于首次准备，后续 Compose 场景复用同一模型文件。

`demo-down.sh` 只停止本项目并保留 named volumes。只有显式执行
`demo-reset.sh` 才会对固定项目 `reagent-demo` 执行 `down -v`。
