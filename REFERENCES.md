# ReAgent — 值得参考的开源 Agent 项目清单

> 用途:开搭前/边搭边读,学架构与思路,但代码一行行自己写。
> 按"读它学什么"组织,对应 ReAgent 的各层。clone 前扫一眼仓库活跃度。
> (清单整理于 2026-06,均为长期活跃主流项目)

读的时候带着 3 个问题去找答案,别漫无目的:
1. 它的 ReAct 循环主体在哪个文件?怎么写的?
2. 工具是怎么定义、注册、被模型选中的?
3. 它怎么管理"任务跑到一半"的状态?(大多数都没做好,这正是你的差异化)

---

## 🥇 第一优先级:最贴近 ReAgent 的(必读)

| 项目 | 一句话 | 重点学什么 | 可读性 |
|---|---|---|---|
| **smolagents**(HuggingFace) | 极简 agent 框架,几千行 | ReAct 循环 + 工具定义 + 代码执行沙箱的最干净实现,直接对标内核层 | ⭐⭐⭐⭐⭐ 最好读 |
| **OpenHands**(原 OpenDevin) | 完整的 Coding Agent 产品 | demo 终极形态:runtime、沙箱、工具集、agent-computer 交互全有 | ⭐⭐ 大,看架构 |
| **Aider** | 命令行 AI 结对编程 | Coding Agent 的工具设计 + diff 应用 + 仓库上下文管理 | ⭐⭐⭐⭐ |
| **LangGraph**(LangChain 出品) | 带状态的 agent 编排 | 重点:checkpoint / 状态持久化机制——M2 断点续跑的工业级参考 | ⭐⭐⭐ |

## 🥈 第二优先级:学 agent 内核原理(挑 1-2 个看)

| 项目 | 一句话 | 重点学什么 | 可读性 |
|---|---|---|---|
| **SWE-agent**(Princeton) | 让 agent 解 GitHub issue | Agent-Computer Interface(ACI):怎么给 agent 设计好用的工具,论文也值得读 | ⭐⭐⭐ |
| **BabyAGI** | 几百行的任务循环 demo | task planning 最朴素的形态,半小时读完建立直觉 | ⭐⭐⭐⭐⭐ |
| **AutoGen**(微软) | 多 agent 对话框架 | 多 agent 的消息传递/编排 | ⭐⭐⭐ |
| **CrewAI** | 角色化多 agent | 多 agent 分工的另一种思路,API 设计干净 | ⭐⭐⭐⭐ |

> AutoGPT 故意没放进必读——历史包袱重、代码乱,扫一眼"原始 agent 长啥样"即可,别花时间。

## 🥉 第三优先级:Java 生态(主体用 Java,必看)

| 项目 | 一句话 | 重点学什么 |
|---|---|---|
| **LangChain4j** | Java 版 LangChain | Java 里怎么做工具调用 / function calling / @Tool 注解——工具注册层直接参考 |
| **Spring AI** | Spring 官方 AI 集成 | Advisor / Tool / 流式 抽象,和技术栈无缝(MindSync 已用过) |
| **Agents-Flex** | 国产 Java agent 框架 | Java agent 的另一种实现,可对比 |

## 🔧 旁支:断点续跑的灵感来源(看概念,不用读代码)

- **Temporal** / **durable execution** 概念:不是 agent 项目,但"长任务持久化 + 崩溃自动恢复"
  的思想正是 M2 的理论基础。读它的 "durable execution" 博客,理解为什么要把每步落库——
  能让 M2 设计讲出理论高度,面试加分。

---

## 建议的阅读顺序(别一次性全看)

```
1. smolagents     → 半天,建立"agent 内核长啥样"的直觉(最重要)
2. BabyAGI        → 1 小时,看最朴素的任务循环
3. LangChain4j    → 半天,看 Java 怎么落地工具调用(要用的)
4. Aider 或 SWE-agent → 看 Coding Agent 的工具怎么设计
5. LangGraph 的 checkpoint 文档 → 专门为 M2 断点续跑取经
6. OpenHands      → 最后看,作为"完整产品长啥样"的参照,别陷进去
```

---

## 我的阅读笔记(自己往下记)

### smolagents
-

### BabyAGI
-

### LangChain4j
-

### Aider / SWE-agent
-

### LangGraph checkpoint
-

### OpenHands
-
