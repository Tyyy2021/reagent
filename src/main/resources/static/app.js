(() => {
  "use strict";

  const STORAGE = Object.freeze({
    taskId: "reagent.demo.v1.taskId",
    cursor: "reagent.demo.v1.cursor"
  });
  const TASK_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/;
  const CURSOR_PATTERN = /^[A-Za-z0-9][A-Za-z0-9:._-]{0,127}$/;
  const MAX_RESPONSE_TEXT = 131072;
  const MAX_TIMELINE_EVENTS = 160;

  const STREAM_EVENTS = Object.freeze([
    "HELLO",
    "TASK_STARTED",
    "STEP",
    "ASSISTANT",
    "TOKEN",
    "TOOL_CALL",
    "TOOL_RESULT",
    "KNOWLEDGE_RETRIEVED",
    "APPROVAL_REQUIRED",
    "COMPLETED",
    "FAILED",
    "CANCELLED",
    "PAUSED"
  ]);
  const TERMINAL_STATES = new Set(["COMPLETED", "FAILED", "CANCELLED"]);
  const RESILIENCE_TYPES = new Set([
    "RECOVERY_ATTEMPT",
    "FENCED",
    "DEDUPLICATED"
  ]);
  const EVENT_TITLES = Object.freeze({
    TASK_STARTED: "执行租约已获取",
    STEP: "ReAct 调查步推进",
    ASSISTANT: "Agent 决策已持久化",
    TOKEN: "实时推理增量",
    TOOL_CALL: "能力调用已登记",
    TOOL_RESULT: "能力结果已落账",
    KNOWLEDGE_RETRIEVED: "冻结知识索引命中",
    APPROVAL_REQUIRED: "到达人工写入边界",
    COMPLETED: "事故响应完成",
    FAILED: "事故响应失败",
    CANCELLED: "任务已取消",
    PAUSED: "任务已暂停"
  });

  const runtime = {
    taskId: "",
    cursor: "0",
    source: null,
    seenDurableIds: new Set(),
    epochs: new Set(),
    resilienceKeys: new Set(),
    eventCount: 0,
    lastTask: null,
    activeApproval: null,
    pendingApprovalCount: 0,
    requestBusy: false
  };

  const ui = {};

  function textElement(tag, className, value) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    node.textContent = value == null ? "" : String(value).slice(0, 4000);
    return node;
  }

  function byId(id) {
    return document.getElementById(id);
  }

  function replaceText(node, value, className) {
    if (!node) return;
    node.replaceChildren(textElement("span", className || "", value));
  }

  function normalizeState(value) {
    const state = String(value || "").toUpperCase();
    if (state === "WAITING_APPROVAL") return "waiting";
    if (state === "RUNNING" || state === "PAUSED") return "running";
    if (state === "COMPLETED") return "completed";
    if (state === "FAILED") return "failed";
    if (state === "CANCELLED") return "cancelled";
    return "idle";
  }

  function showStatus(message, tone) {
    replaceText(ui.liveStatus, message);
    const knownTone = tone === "ready" || tone === "error" ? tone : "busy";
    ui.signalDot.dataset.state = knownTone;
  }

  function formatTimestamp(value) {
    if (value == null || value === "") return "—";
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) return String(value);
    return parsed.toLocaleString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
      timeZoneName: "short"
    });
  }

  function loadStoredValue(key, pattern) {
    try {
      const value = window.localStorage.getItem(key);
      if (value == null || value === "") return "";
      if (pattern.test(value)) return value;
      window.localStorage.removeItem(key);
    } catch (failure) {
      showStatus("本机恢复坐标不可用，将仅保持当前会话。", "error");
    }
    return "";
  }

  function persistValue(key, value) {
    try {
      window.localStorage.setItem(key, value);
    } catch (failure) {
      showStatus("任务继续运行，但本机无法保存恢复坐标。", "error");
    }
  }

  function clearStoredCursor() {
    try {
      window.localStorage.removeItem(STORAGE.cursor);
    } catch (failure) {
      showStatus("无法清除旧 cursor，请检查浏览器存储权限。", "error");
    }
  }

  class HttpProblem extends Error {
    constructor(status, detail) {
      super(detail || `HTTP ${status}`);
      this.name = "HttpProblem";
      this.status = status;
    }
  }

  async function requestJson(path, options) {
    const requestOptions = options || {};
    const response = await window.fetch(path, {
      credentials: "same-origin",
      ...requestOptions,
      headers: {
        Accept: "application/json",
        ...(requestOptions.headers || {})
      }
    });
    const raw = await response.text();
    if (raw.length > MAX_RESPONSE_TEXT) {
      throw new HttpProblem(response.status, "服务端响应超过控制台上限");
    }
    let body = null;
    if (raw !== "") {
      try {
        body = JSON.parse(raw);
      } catch (failure) {
        throw new HttpProblem(response.status, "服务端返回了无法识别的 JSON");
      }
    }
    if (!response.ok) {
      const detail = body && typeof body.message === "string"
        ? body.message
        : `请求失败 (${response.status})`;
      throw new HttpProblem(response.status, detail);
    }
    return body;
  }

  function randomHex() {
    const bytes = new Uint8Array(6);
    if (window.crypto && typeof window.crypto.getRandomValues === "function") {
      window.crypto.getRandomValues(bytes);
    } else {
      for (let index = 0; index < bytes.length; index += 1) {
        bytes[index] = Math.floor(Math.random() * 256);
      }
    }
    return Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
  }

  function incidentExternalId() {
    const stamp = new Date()
      .toISOString()
      .replace(/[-:]/g, "")
      .replace(/\.\d{3}Z$/, "Z");
    return `ALERT-CHECKOUT-ALERT-${stamp}-${randomHex()}`;
  }

  function fixedIncident(externalAlertId) {
    return {
      source: "fake-alertmanager",
      externalAlertId,
      service: "checkout",
      severity: "critical",
      title: "Checkout error rate is above threshold",
      summary: "5xx error rate exceeded 10% for five minutes",
      startedAt: "2026-07-19T10:00:00Z",
      labels: {
        environment: "demo",
        region: "cn-east"
      }
    };
  }

  function setTaskLinks(taskId) {
    if (!TASK_ID_PATTERN.test(taskId)) return;

    const acceptanceUrl = new URL(
      `/api/acceptance/tasks/${encodeURIComponent(taskId)}`,
      window.location.origin
    );
    ui.acceptanceLink.href = acceptanceUrl.toString();
    ui.acceptanceLink.target = "_blank";
    ui.acceptanceLink.rel = "noopener";
    ui.acceptanceLink.removeAttribute("aria-disabled");
    ui.acceptanceLink.removeAttribute("tabindex");

    const jaegerUrl = new URL(window.location.href);
    jaegerUrl.port = "16686";
    jaegerUrl.pathname = "/search";
    jaegerUrl.search = "";
    jaegerUrl.hash = "";
    jaegerUrl.searchParams.set("service", "reagent");
    jaegerUrl.searchParams.set("tags", JSON.stringify({"task.id": taskId}));
    ui.jaegerLink.href = jaegerUrl.toString();
    ui.jaegerLink.target = "_blank";
    ui.jaegerLink.rel = "noopener";
    ui.jaegerLink.removeAttribute("aria-disabled");
    ui.jaegerLink.removeAttribute("tabindex");
  }

  function resetTaskLinks() {
    for (const link of [ui.acceptanceLink, ui.jaegerLink]) {
      link.removeAttribute("href");
      link.removeAttribute("target");
      link.removeAttribute("rel");
      link.setAttribute("aria-disabled", "true");
      link.setAttribute("tabindex", "-1");
    }
  }

  function componentDisplayName(name) {
    const names = {
      database: "MYSQL",
      redis: "REDIS",
      python: "PYTHON",
      "rag-index": "RAG",
      mcp: "MCP",
      "incident-profile": "PROFILE"
    };
    return names[String(name)] || "SERVICE";
  }

  function readinessUnit(name, ready, detail) {
    const unit = document.createElement("li");
    unit.className = "readiness-unit";
    unit.dataset.state = ready ? "ready" : "down";
    const led = document.createElement("span");
    led.className = "unit-led";
    led.setAttribute("aria-hidden", "true");
    unit.append(
      led,
      textElement("span", "", name),
      textElement("small", "", detail)
    );
    return unit;
  }

  function renderReadiness(readiness) {
    const components = Array.isArray(readiness && readiness.components)
      ? readiness.components.slice(0, 12)
      : [];
    ui.readinessList.replaceChildren();
    ui.readinessList.append(
      readinessUnit("APP", Boolean(readiness && readiness.ready),
        readiness && readiness.ready ? "READY" : "DEGRADED")
    );
    for (const component of components) {
      const ready = Boolean(component && component.ready);
      const name = componentDisplayName(component && component.name);
      const detail = ready
        ? (component && component.version) || "READY"
        : (component && component.reason) || "UNAVAILABLE";
      ui.readinessList.append(readinessUnit(name, ready, detail));
    }
    replaceText(ui.readinessTime,
      readiness && readiness.checkedAt
        ? `SAMPLED ${formatTimestamp(readiness.checkedAt)}`
        : "SAMPLE TIME UNKNOWN");
    showStatus(
      readiness && readiness.ready
        ? "控制面就绪，等待事故信号。"
        : "控制面有依赖未就绪，请先检查 readiness rail。",
      readiness && readiness.ready ? "ready" : "error"
    );
  }

  async function refreshReadiness() {
    try {
      const readiness = await requestJson("/api/readiness");
      renderReadiness(readiness);
      return readiness;
    } catch (failure) {
      ui.readinessList.replaceChildren(
        readinessUnit("APP", false, "UNREACHABLE")
      );
      replaceText(ui.readinessTime, "SAMPLE FAILED");
      showStatus(`无法读取就绪度：${failure.message}`, "error");
      return null;
    }
  }

  function renderIncidentHeader(incident) {
    if (!incident || typeof incident !== "object") return;
    replaceText(ui.alertSource, incident.source || "—");
    replaceText(ui.alertExternalId, incident.externalAlertId || "—");
    replaceText(ui.alertService, incident.service || "—");
    replaceText(ui.alertStartedAt, formatTimestamp(incident.startedAt));
  }

  function taskStatusLabel(status) {
    const labels = {
      RUNNING: "RUNNING",
      WAITING_APPROVAL: "WAITING / APPROVAL",
      COMPLETED: "COMPLETED",
      FAILED: "FAILED",
      CANCELLED: "CANCELLED",
      PAUSED: "PAUSED"
    };
    return labels[String(status)] || String(status || "UNKNOWN");
  }

  function renderTask(task) {
    if (!task || typeof task !== "object") return;
    const taskId = String(task.taskId || "");
    if (!TASK_ID_PATTERN.test(taskId)) {
      throw new Error("任务状态包含非法 task ID");
    }
    const previous = runtime.lastTask;
    runtime.lastTask = task;
    runtime.taskId = taskId;
    persistValue(STORAGE.taskId, taskId);
    setTaskLinks(taskId);

    const statusState = normalizeState(task.status);
    replaceText(ui.taskId, taskId);
    replaceText(ui.taskStatus, taskStatusLabel(task.status));
    ui.taskStatusMark.dataset.state = statusState;
    replaceText(ui.taskProfile, task.profile || "—");
    replaceText(ui.taskOwner, task.ownerId || "UNCLAIMED");
    replaceText(ui.taskEpoch, task.leaseEpoch == null ? "—" : task.leaseEpoch);
    replaceText(ui.taskRecovery,
      Number.isFinite(Number(task.recoveryCount)) ? task.recoveryCount : "—");
    replaceText(ui.taskUpdatedAt, formatTimestamp(task.updatedAt));
    renderIncidentHeader(task.incident);

    const recoveryCount = Number(task.recoveryCount || 0);
    if (recoveryCount > 0) {
      recordResilience(
        "RECOVERY_ATTEMPT",
        `服务端记录 ${recoveryCount} 次自动恢复；任务上下文来自持久状态。`,
        `task-recovery-${recoveryCount}`
      );
    }
    const currentEpoch = Number(task.leaseEpoch || 0);
    const previousEpoch = Number(previous && previous.leaseEpoch || 0);
    if (previousEpoch > 0 && currentEpoch > previousEpoch) {
      recordResilience(
        "FENCED",
        `租约从 epoch ${previousEpoch} 迁移到 ${currentEpoch}；旧执行者的写入令牌失效。`,
        `task-fence-${previousEpoch}-${currentEpoch}`
      );
    }

    if (task.result) {
      replaceText(ui.outcomeResult, task.result);
    }
    if (task.status === "COMPLETED") {
      replaceText(ui.outcomeTitle, "最终诊断已持久化");
    } else if (task.status === "FAILED") {
      replaceText(ui.outcomeTitle, "响应未能完成");
      ui.finalSeal.dataset.state = "failed";
    } else if (task.status === "CANCELLED") {
      replaceText(ui.outcomeTitle, "响应已取消");
      ui.finalSeal.dataset.state = "failed";
    }
  }

  async function refreshTask() {
    if (!TASK_ID_PATTERN.test(runtime.taskId)) return null;
    const task = await requestJson(
      `/api/tasks/${encodeURIComponent(runtime.taskId)}`
    );
    renderTask(task);
    return task;
  }

  function decisionButtonsDisabled(disabled) {
    ui.approveAction.disabled = disabled;
    ui.rejectAction.disabled = disabled;
  }

  function resetApproval() {
    runtime.activeApproval = null;
    runtime.pendingApprovalCount = 0;
    replaceText(ui.approvalEvidence,
      "调查证据就绪后，这里只展示服务端提供的有界摘要。");
    replaceText(ui.approvalCallId, "—");
    replaceText(ui.approvalState, "NOT REQUESTED");
    ui.approvalPanel.dataset.state = "idle";
    decisionButtonsDisabled(true);
  }

  function selectApproval(approvals) {
    const list = Array.isArray(approvals) ? approvals : [];
    const tickets = list.filter(item =>
      item && item.toolName === "create_ticket"
    );
    const pending = tickets.filter(item =>
      String(item.status || "").toUpperCase() === "PENDING"
    );
    runtime.pendingApprovalCount = pending.length;
    return pending[0] || tickets.at(-1) || null;
  }

  function renderApproval(approvals) {
    const approval = selectApproval(approvals);
    if (!approval) {
      resetApproval();
      return null;
    }
    runtime.activeApproval = approval;
    replaceText(ui.approvalEvidence,
      approval.evidencePreview || "服务端未提供证据摘要。");
    renderObservationEvidence(approval.evidencePreview);
    replaceText(ui.approvalCallId, approval.toolCallId || "—");

    const state = String(approval.status || "").toUpperCase();
    replaceText(
      ui.approvalState,
      state === "PENDING" && runtime.pendingApprovalCount > 1
        ? `PENDING · ${runtime.pendingApprovalCount} QUEUED`
        : state || "UNKNOWN"
    );
    ui.approvalPanel.dataset.state = state.toLowerCase();
    decisionButtonsDisabled(runtime.requestBusy || state !== "PENDING");
    if (state === "PENDING") {
      showStatus("只读调查完成，create_ticket 正等待人工决策。", "busy");
    }
    if (state === "APPROVED" || state === "REJECTED") {
      replaceText(ui.outcomeDecision, state);
      ui.outcomeDecision.dataset.state =
        state === "APPROVED" ? "passed" : "failed";
    }
    return approval;
  }

  async function refreshApprovals() {
    if (!TASK_ID_PATTERN.test(runtime.taskId)) return [];
    const approvals = await requestJson(
      `/api/tasks/${encodeURIComponent(runtime.taskId)}/approvals`
    );
    renderApproval(approvals);
    return approvals;
  }

  function renderCitations(ids, sources) {
    const citationIds = Array.isArray(ids) ? ids.slice(0, 16) : [];
    const citationSources = Array.isArray(sources) ? sources.slice(0, 16) : [];
    ui.citationList.replaceChildren();
    if (citationIds.length === 0) {
      ui.citationList.append(
        textElement("p", "empty-evidence", "知识检索完成，未返回引用。")
      );
    } else {
      citationIds.forEach((id, index) => {
        const entry = document.createElement("article");
        entry.className = "citation-entry";
        const source = citationSources[index]
          || citationSources[0]
          || "Pinned to frozen incident profile";
        entry.append(
          textElement("code", "", id),
          textElement("p", "", source)
        );
        ui.citationList.append(entry);
      });
    }
    replaceText(ui.citationCount, `${citationIds.length} HITS`);
  }

  function renderObservationEvidence(preview) {
    const bounded = String(preview || "").slice(0, 512);
    const metricMatch = bounded.match(
      /(?:^|[;\s])errorRatePercent\s*=\s*([0-9]{1,3}(?:\.[0-9]{1,3})?)/i
    );
    if (metricMatch) {
      const value = Number(metricMatch[1]);
      if (Number.isFinite(value) && value >= 0 && value <= 100) {
        replaceText(ui.metricsDetail,
          `服务端有界证据 · checkout 错误率 ${metricMatch[1]}%`);
        replaceText(ui.metricsState, "VERIFIED");
        ui.metricsState.dataset.state = "done";
      }
    }

    const logMatch = bounded.match(
      /(SQLTransientConnectionException(?::\s*[^;\r\n]{0,180})?)/i
    );
    if (logMatch) {
      replaceText(ui.logsDetail, `服务端有界证据 · ${logMatch[1]}`);
      replaceText(ui.logsState, "VERIFIED");
      ui.logsState.dataset.state = "done";
    }
  }

  function updateObservation(toolName, phase, outcome) {
    const normalized = String(toolName || "");
    let detail;
    let badge;
    if (normalized === "query_metrics") {
      detail = ui.metricsDetail;
      badge = ui.metricsState;
    } else if (normalized === "search_logs") {
      detail = ui.logsDetail;
      badge = ui.logsState;
    } else {
      return;
    }
    if (phase === "call") {
      replaceText(detail,
        normalized === "query_metrics"
          ? "只读指标窗口已提交，等待有界结果。"
          : "有界日志检索已提交，等待结果。");
      replaceText(badge, "RUNNING");
      badge.dataset.state = "running";
      return;
    }
    const finalOutcome = String(outcome || "DEFINITIVE");
    replaceText(detail,
      normalized === "query_metrics"
        ? `指标证据已落账 · ${finalOutcome}`
        : `日志证据已落账 · ${finalOutcome}`);
    replaceText(badge, finalOutcome === "DEFINITIVE" ? "VERIFIED" : finalOutcome);
    badge.dataset.state =
      finalOutcome === "DEFINITIVE" ? "done" : "error";
  }

  function recordResilience(type, description, key) {
    if (!RESILIENCE_TYPES.has(type) || runtime.resilienceKeys.has(key)) return;
    runtime.resilienceKeys.add(key);
    const empty = ui.resilienceList.querySelector(".empty-evidence");
    if (empty) empty.remove();
    const item = document.createElement("li");
    item.className = "resilience-entry";
    item.append(
      textElement("code", "", type),
      textElement("p", "", description)
    );
    ui.resilienceList.append(item);
    while (ui.resilienceList.children.length > 32) {
      ui.resilienceList.firstElementChild.remove();
    }
    replaceText(ui.resilienceCount,
      `${ui.resilienceList.children.length} EVENTS`);
  }

  function renderAcceptance(evidence) {
    if (!evidence || typeof evidence !== "object") return;
    renderCitations(evidence.citationIds, evidence.citationSources);
    replaceText(ui.outcomeDecision, evidence.approvalDecision || "NONE");
    replaceText(ui.outcomeTicket, evidence.ticketId || "NO TICKET");
    replaceText(ui.outcomeWrites,
      evidence.uniqueTicketCount == null ? "—" : evidence.uniqueTicketCount);
    replaceText(ui.outcomeAcceptance,
      evidence.passed ? "PASSED" : "FAILED");
    ui.outcomeAcceptance.dataset.state =
      evidence.passed ? "passed" : "failed";
    ui.finalSeal.dataset.state = evidence.passed ? "passed" : "failed";

    const epochs = Array.isArray(evidence.workerEpochs)
      ? evidence.workerEpochs.slice(0, 16)
      : [];
    if (epochs.length > 1) {
      recordResilience(
        "RECOVERY_ATTEMPT",
        `Acceptance ledger 观察到执行 epoch：${epochs.join(" → ")}。`,
        `acceptance-recovery-${epochs.join("-")}`
      );
      recordResilience(
        "FENCED",
        "接管后的新 epoch 完成任务，旧 epoch 不再具备写权限。",
        `acceptance-fence-${epochs.join("-")}`
      );
    }
    if (evidence.passed) {
      showStatus("事故响应完成，authoritative acceptance 已通过。", "ready");
    } else {
      showStatus("任务已终结，但 acceptance ledger 未通过。", "error");
    }
  }

  async function refreshAcceptance() {
    if (!TASK_ID_PATTERN.test(runtime.taskId)) return null;
    try {
      const evidence = await requestJson(
        `/api/acceptance/tasks/${encodeURIComponent(runtime.taskId)}`
      );
      renderAcceptance(evidence);
      return evidence;
    } catch (failure) {
      replaceText(ui.outcomeAcceptance, "UNAVAILABLE");
      ui.outcomeAcceptance.dataset.state = "failed";
      showStatus(`无法读取 acceptance evidence：${failure.message}`, "error");
      return null;
    }
  }

  function eventTone(type, data) {
    if (type === "APPROVAL_REQUIRED") return "write";
    if (type === "COMPLETED") return "success";
    if (type === "FAILED" || type === "CANCELLED") return "danger";
    if (type === "TOOL_RESULT"
        && data && String(data.outcome) !== "DEFINITIVE") return "danger";
    if (type === "TOKEN" || type === "ASSISTANT") return "neutral";
    return "read";
  }

  function scalar(data, key, fallback) {
    if (!data || typeof data !== "object" || Array.isArray(data)) return fallback;
    const value = data[key];
    if (value == null || typeof value === "object") return fallback;
    return String(value);
  }

  function describeEvent(type, data) {
    if (type === "TASK_STARTED") {
      return `Worker 已获取 epoch ${scalar(data, "leaseEpoch", "—")} 的执行租约。`;
    }
    if (type === "STEP") {
      return `进入调查步骤 ${scalar(data, "step", "—")}，上下文已从持久状态装载。`;
    }
    if (type === "TOOL_CALL") {
      return `${scalar(data, "name", "unknown_tool")} 已进入工具账本。`;
    }
    if (type === "TOOL_RESULT") {
      return `${scalar(data, "name", "unknown_tool")} 返回 ${scalar(data, "outcome", "UNKNOWN")}。`;
    }
    if (type === "KNOWLEDGE_RETRIEVED") {
      return `冻结索引 ${scalar(data, "indexVersion", "—")} 返回 ${scalar(data, "hitCount", "0")} 条引用。`;
    }
    if (type === "APPROVAL_REQUIRED") {
      return "只读调查已收口；create_ticket 在任何远端写入发生前暂停。";
    }
    if (type === "COMPLETED") {
      return scalar(data, "result", "任务已完成。");
    }
    if (type === "FAILED") {
      return scalar(data, "error", "任务执行失败。");
    }
    if (type === "CANCELLED" || type === "PAUSED") {
      return `任务状态：${scalar(data, "status", type)}。`;
    }
    if (type === "TOKEN") {
      return scalar(data, "text", "");
    }
    try {
      return JSON.stringify(data == null ? {} : data).slice(0, 4000);
    } catch (failure) {
      return "事件负载无法序列化。";
    }
  }

  function renderTimelineEvent(type, data, eventId) {
    const placeholder = ui.eventStream.querySelector(".is-placeholder");
    if (placeholder) placeholder.remove();

    const item = document.createElement("li");
    item.className = "tape-event is-new";
    item.dataset.tone = eventTone(type, data);

    const node = document.createElement("span");
    node.className = "event-node";
    node.setAttribute("aria-hidden", "true");

    const meta = document.createElement("div");
    meta.className = "event-meta";
    meta.append(
      textElement("time", "", new Date().toLocaleTimeString("zh-CN", {
        hour12: false
      })),
      textElement("span", "", eventId ? `CURSOR ${eventId}` : "LIVE / EPHEMERAL")
    );

    const copy = document.createElement("div");
    copy.className = "event-copy";
    copy.append(
      textElement("strong", "", EVENT_TITLES[type] || type),
      textElement("p", "", describeEvent(type, data))
    );
    item.append(node, meta, copy);
    ui.eventStream.append(item);
    runtime.eventCount += 1;
    while (ui.eventStream.children.length > MAX_TIMELINE_EVENTS) {
      ui.eventStream.firstElementChild.remove();
    }
  }

  function parseStreamData(raw) {
    if (typeof raw !== "string" || raw.length > 65536) {
      return {message: "事件负载超过控制台上限"};
    }
    if (raw === "") return {};
    try {
      const value = JSON.parse(raw);
      if (value && typeof value === "object") return value;
      return {value};
    } catch (failure) {
      return {value: raw.slice(0, 4000)};
    }
  }

  function rememberDurableCursor(eventId) {
    if (!CURSOR_PATTERN.test(eventId)) return false;
    if (runtime.seenDurableIds.has(eventId)) {
      recordResilience(
        "DEDUPLICATED",
        `重复 durable cursor ${eventId} 已在浏览器边界抑制。`,
        `stream-dedup-${eventId}`
      );
      return false;
    }
    runtime.seenDurableIds.add(eventId);
    while (runtime.seenDurableIds.size > 512) {
      runtime.seenDurableIds.delete(runtime.seenDurableIds.values().next().value);
    }
    runtime.cursor = eventId;
    persistValue(STORAGE.cursor, eventId);
    replaceText(ui.taskCursor, eventId);
    return true;
  }

  function observeEpoch(data) {
    const rawEpoch = scalar(data, "leaseEpoch", "");
    if (!/^[1-9][0-9]{0,18}$/.test(rawEpoch)) return;
    if (runtime.epochs.has(rawEpoch)) return;
    const prior = Array.from(runtime.epochs).at(-1);
    runtime.epochs.add(rawEpoch);
    if (prior) {
      recordResilience(
        "RECOVERY_ATTEMPT",
        `事件流从 epoch ${prior} 延续到 ${rawEpoch}。`,
        `stream-recovery-${prior}-${rawEpoch}`
      );
      recordResilience(
        "FENCED",
        `epoch ${rawEpoch} 成为当前执行令牌；epoch ${prior} 的后续写入会被拒绝。`,
        `stream-fence-${prior}-${rawEpoch}`
      );
    }
  }

  function observeKnownEvidence(type, data) {
    if (type === "TASK_STARTED") observeEpoch(data);
    if (type === "KNOWLEDGE_RETRIEVED") {
      renderCitations(
        Array.isArray(data && data.chunkIds) ? data.chunkIds : [],
        []
      );
    }
    if (type === "TOOL_CALL") {
      updateObservation(scalar(data, "name", ""), "call", "");
    }
    if (type === "TOOL_RESULT") {
      updateObservation(
        scalar(data, "name", ""),
        "result",
        scalar(data, "outcome", "UNKNOWN")
      );
    }
  }

  async function refreshAuthoritative(reason) {
    if (!TASK_ID_PATTERN.test(runtime.taskId)) return;
    try {
      const [task, approvals, readiness] = await Promise.all([
        refreshTask(),
        refreshApprovals(),
        refreshReadiness()
      ]);
      if (task && TERMINAL_STATES.has(String(task.status))) {
        await refreshAcceptance();
      } else if (reason === "reconnect") {
        showStatus("事件流已恢复，并已复核服务端任务状态。", "ready");
      }
      return {task, approvals, readiness};
    } catch (failure) {
      showStatus(`状态复核失败：${failure.message}`, "error");
      return null;
    }
  }

  function taskNeedsStream(task) {
    return Boolean(task && String(task.status) === "RUNNING");
  }

  async function handleStreamEvent(type, event) {
    const data = parseStreamData(event.data);
    if (type === "HELLO") {
      showStatus(`事件流已连接 · cursor ${scalar(data, "fromEventId", "0")}`, "ready");
      await refreshAuthoritative("reconnect");
      return;
    }

    const eventId = String(event.lastEventId || "");
    if (eventId !== "") {
      if (!rememberDurableCursor(eventId)) return;
    }
    renderTimelineEvent(type, data, eventId);
    observeKnownEvidence(type, data);

    if (type === "APPROVAL_REQUIRED") {
      closeStream();
      await refreshAuthoritative("waiting");
      return;
    }
    if (type === "COMPLETED" || type === "FAILED" || type === "CANCELLED") {
      closeStream();
      await refreshAuthoritative("terminal");
      return;
    }
    if (type === "PAUSED") {
      closeStream();
      await refreshAuthoritative("paused");
    }
  }

  function closeStream() {
    if (runtime.source) {
      runtime.source.close();
      runtime.source = null;
    }
  }

  function openStream() {
    closeStream();
    if (!TASK_ID_PATTERN.test(runtime.taskId)) return;
    if (!CURSOR_PATTERN.test(runtime.cursor)) runtime.cursor = "0";

    const params = new URLSearchParams();
    params.set("cursor", runtime.cursor);
    const streamUrl = new URL(
      `/api/tasks/${encodeURIComponent(runtime.taskId)}/stream`,
      window.location.origin
    );
    streamUrl.search = params.toString();
    const source = new EventSource(streamUrl);
    runtime.source = source;

    for (const type of STREAM_EVENTS) {
      source.addEventListener(type, event => {
        handleStreamEvent(type, event).catch(failure => {
          showStatus(`事件处理失败：${failure.message}`, "error");
        });
      });
    }
    source.onerror = () => {
      if (runtime.source !== source) return;
      showStatus("事件流暂时中断；浏览器会从 durable cursor 自动重连。", "error");
    };
    showStatus(`正在从 cursor ${runtime.cursor} 连接事件流…`, "busy");
  }

  function resetEvidence() {
    runtime.eventCount = 0;
    runtime.seenDurableIds.clear();
    runtime.epochs.clear();
    runtime.resilienceKeys.clear();
    runtime.lastTask = null;
    runtime.activeApproval = null;

    const placeholder = document.createElement("li");
    placeholder.className = "tape-event is-placeholder";
    const node = document.createElement("span");
    node.className = "event-node";
    node.setAttribute("aria-hidden", "true");
    const meta = document.createElement("div");
    meta.className = "event-meta";
    meta.append(
      textElement("time", "", "STARTING"),
      textElement("span", "", "CURSOR 0")
    );
    const copy = document.createElement("div");
    copy.className = "event-copy";
    copy.append(
      textElement("strong", "", "事故信号已提交"),
      textElement("p", "", "等待 durable event stream 建立连接。")
    );
    placeholder.append(node, meta, copy);
    ui.eventStream.replaceChildren(placeholder);

    renderCitations([], []);
    replaceText(ui.metricsDetail, "等待只读指标查询。");
    replaceText(ui.metricsState, "QUEUED");
    ui.metricsState.dataset.state = "idle";
    replaceText(ui.logsDetail, "等待有界日志检索。");
    replaceText(ui.logsState, "QUEUED");
    ui.logsState.dataset.state = "idle";
    resetApproval();
    ui.resilienceList.replaceChildren(
      textElement("li", "empty-evidence",
        "当前运行尚无接管、fence 或去重事件。")
    );
    replaceText(ui.resilienceCount, "0 EVENTS");
    replaceText(ui.outcomeTitle, "等待最终诊断");
    replaceText(ui.outcomeResult, "任务终态与 acceptance ledger 将在这里汇合。");
    replaceText(ui.outcomeDecision, "—");
    replaceText(ui.outcomeTicket, "—");
    replaceText(ui.outcomeWrites, "—");
    replaceText(ui.outcomeAcceptance, "PENDING");
    ui.finalSeal.dataset.state = "pending";
  }

  async function launchIncident() {
    if (runtime.requestBusy) return;
    runtime.requestBusy = true;
    ui.launchIncident.disabled = true;
    decisionButtonsDisabled(true);
    closeStream();
    resetEvidence();

    const externalAlertId = incidentExternalId();
    const incident = fixedIncident(externalAlertId);
    renderIncidentHeader(incident);
    showStatus("正在登记固定 checkout 事故…", "busy");

    try {
      const accepted = await requestJson("/api/incidents", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(incident)
      });
      const taskId = String(accepted && accepted.taskId || "");
      if (!TASK_ID_PATTERN.test(taskId)) {
        throw new Error("事故入口返回了非法 task ID");
      }
      runtime.taskId = taskId;
      runtime.cursor = "0";
      persistValue(STORAGE.taskId, taskId);
      clearStoredCursor();
      replaceText(ui.taskCursor, "0");
      if (accepted.deduplicated) {
        recordResilience(
          "DEDUPLICATED",
          "事故入口命中 source + externalAlertId 唯一键，复用了既有任务。",
          `intake-dedup-${taskId}`
        );
      }
      await refreshAuthoritative("launch");
      openStream();
    } catch (failure) {
      showStatus(`事故触发失败：${failure.message}`, "error");
    } finally {
      runtime.requestBusy = false;
      ui.launchIncident.disabled = false;
      if (runtime.activeApproval
          && runtime.activeApproval.status === "PENDING") {
        decisionButtonsDisabled(false);
      }
    }
  }

  async function decide(decision) {
    if (runtime.requestBusy
        || !runtime.activeApproval
        || runtime.activeApproval.status !== "PENDING"
        || !TASK_ID_PATTERN.test(runtime.taskId)) return;
    const toolCallId = String(runtime.activeApproval.toolCallId || "");
    if (!TASK_ID_PATTERN.test(toolCallId)) {
      showStatus("审批记录包含非法 tool call ID。", "error");
      return;
    }

    runtime.requestBusy = true;
    decisionButtonsDisabled(true);
    showStatus(
      decision === "APPROVE"
        ? "正在提交批准决定；远端写入仍受幂等账本保护…"
        : "正在拒绝远端写入…",
      "busy"
    );
    try {
      const reason = decision === "APPROVE"
        ? "Approved from incident response console"
        : "Rejected from incident response console";
      const approval = await requestJson(
        `/api/tasks/${encodeURIComponent(runtime.taskId)}/approvals/`
          + `${encodeURIComponent(toolCallId)}/decision`,
        {
          method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify({decision, reason})
        }
      );
      renderApproval([approval]);
      const snapshot = await refreshAuthoritative("decision");
      const snapshotStatus = String(
        snapshot && snapshot.task && snapshot.task.status || ""
      );
      if (!snapshot) {
        // Keep the stream closed until an authoritative task state is available.
      } else if (TERMINAL_STATES.has(snapshotStatus)) {
        // refreshAuthoritative already rendered the terminal acceptance state.
      } else if (snapshotStatus === "WAITING_APPROVAL"
          && runtime.pendingApprovalCount > 0) {
        showStatus(
          `决定已持久化，仍有 ${runtime.pendingApprovalCount} 个写入请求待处理。`,
          "busy"
        );
      } else {
        showStatus(
          decision === "APPROVE"
            ? "批准已持久化，等待唯一工单写入与最终诊断。"
            : "拒绝已持久化，任务将以零远端写入收口。",
          "ready"
        );
      }
      if (taskNeedsStream(snapshot && snapshot.task)) openStream();
    } catch (failure) {
      if (failure instanceof HttpProblem && failure.status === 409) {
        showStatus("审批已由其他操作员处理，正在刷新权威状态。", "error");
        const snapshot = await refreshAuthoritative("conflict");
        if (taskNeedsStream(snapshot && snapshot.task)) openStream();
      } else {
        showStatus(`审批提交失败：${failure.message}`, "error");
      }
    } finally {
      runtime.requestBusy = false;
      if (runtime.activeApproval
          && runtime.activeApproval.status === "PENDING") {
        decisionButtonsDisabled(false);
      }
    }
  }

  async function restore() {
    const taskId = loadStoredValue(STORAGE.taskId, TASK_ID_PATTERN);
    const cursor = loadStoredValue(STORAGE.cursor, CURSOR_PATTERN);
    if (!taskId) {
      resetTaskLinks();
      await refreshReadiness();
      return;
    }
    runtime.taskId = taskId;
    runtime.cursor = cursor || "0";
    replaceText(ui.taskCursor, runtime.cursor);
    showStatus("发现本机恢复坐标，正在从服务端重建事故视图…", "busy");
    try {
      const task = await refreshTask();
      await Promise.all([refreshApprovals(), refreshReadiness()]);
      if (task && TERMINAL_STATES.has(String(task.status))) {
        await refreshAcceptance();
        closeStream();
        return;
      }
      openStream();
    } catch (failure) {
      showStatus(`任务恢复失败：${failure.message}`, "error");
    }
  }

  function bindUi() {
    const ids = [
      "live-status", "readiness-list", "readiness-time",
      "alert-source", "alert-external-id", "alert-service", "alert-started-at",
      "launch-incident", "task-status-mark", "task-status", "task-id",
      "task-profile", "task-owner", "task-epoch", "task-recovery",
      "task-cursor", "task-updated-at", "acceptance-link", "jaeger-link",
      "event-stream", "citation-list", "citation-count", "metrics-detail",
      "metrics-state", "logs-detail", "logs-state", "approval-panel",
      "approval-evidence", "approval-call-id", "approval-state",
      "approve-action", "reject-action", "resilience-list",
      "resilience-count", "outcome-title", "outcome-result",
      "outcome-decision", "outcome-ticket", "outcome-writes",
      "outcome-acceptance"
    ];
    for (const id of ids) {
      const property = id.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
      ui[property] = byId(id);
      if (!ui[property]) throw new Error(`Missing console element: ${id}`);
    }
    ui.signalDot = document.querySelector(".signal-dot");
    ui.finalSeal = document.querySelector(".final-seal");
    if (!ui.signalDot || !ui.finalSeal) {
      throw new Error("Missing console state indicator");
    }

    ui.launchIncident.addEventListener("click", () => {
      launchIncident();
    });
    for (const button of document.querySelectorAll("[data-decision]")) {
      button.addEventListener("click", () => {
        const decision = button.dataset.decision;
        if (decision === "APPROVE" || decision === "REJECT") {
          decide(decision);
        }
      });
    }
    window.addEventListener("beforeunload", closeStream);
  }

  async function boot() {
    try {
      bindUi();
      resetTaskLinks();
      await restore();
    } catch (failure) {
      if (ui.liveStatus && ui.signalDot) {
        showStatus(`控制台初始化失败：${failure.message}`, "error");
      }
    }
  }

  boot();
})();
