(() => {
  "use strict";

  const API = Object.freeze({
    state: "/api/state",
    conversation: "/api/conversation",
    runs: "/api/runs",
    retry: "/api/runs/retry",
    permission: "/api/permission",
    permissionMode: "/api/permission-mode",
    reset: "/api/conversation/reset",
    compact: "/api/compact"
  });

  const ACTIVE_RUN_STATES = new Set(["RUNNING", "WAITING_PERMISSION", "COMPACTING"]);
  const TERMINAL_RUN_STATES = new Set(["COMPLETED", "FAILED"]);
  const SSE_EVENT_NAMES = [
    "run_started", "turn_started", "text", "tool_call", "tool_result",
    "permission_request", "permission_resolved", "context_usage",
    "compaction_started", "context_compacted", "compaction_failed",
    "compaction_circuit_opened", "tool_result_offloaded", "log",
    "turn_complete", "loop_complete", "conversation_reset"
  ];

  const dom = {
    appShell: document.querySelector("#appShell"),
    healthButton: document.querySelector("#healthButton"),
    connectionLabel: document.querySelector("#connectionLabel"),
    contextButton: document.querySelector("#contextButton"),
    contextLabel: document.querySelector("#contextLabel"),
    contextFill: document.querySelector("#contextFill"),
    permissionMode: document.querySelector("#permissionMode"),
    newConversationButton: document.querySelector("#newConversationButton"),
    runBanner: document.querySelector("#runBanner"),
    runBannerText: document.querySelector("#runBannerText"),
    conversationScroll: document.querySelector("#conversationScroll"),
    conversation: document.querySelector("#conversation"),
    welcome: document.querySelector("#welcome"),
    jumpLatestButton: document.querySelector("#jumpLatestButton"),
    composerForm: document.querySelector("#composerForm"),
    composerInput: document.querySelector("#composerInput"),
    composerHint: document.querySelector("#composerHint"),
    sendButton: document.querySelector("#sendButton"),
    healthDialog: document.querySelector("#healthDialog"),
    healthSummary: document.querySelector("#healthSummary"),
    healthErrors: document.querySelector("#healthErrors"),
    healthErrorList: document.querySelector("#healthErrorList"),
    workDirValue: document.querySelector("#workDirValue"),
    providerValue: document.querySelector("#providerValue"),
    modelValue: document.querySelector("#modelValue"),
    mcpValue: document.querySelector("#mcpValue"),
    runStateValue: document.querySelector("#runStateValue"),
    contextDialog: document.querySelector("#contextDialog"),
    contextDialogValue: document.querySelector("#contextDialogValue"),
    contextDialogFill: document.querySelector("#contextDialogFill"),
    contextMeter: document.querySelector(".context-meter-large"),
    contextDescription: document.querySelector("#contextDescription"),
    compactNowButton: document.querySelector("#compactNowButton"),
    resetDialog: document.querySelector("#resetDialog"),
    confirmResetButton: document.querySelector("#confirmResetButton"),
    bypassDialog: document.querySelector("#bypassDialog"),
    cancelBypassButton: document.querySelector("#cancelBypassButton"),
    confirmBypassButton: document.querySelector("#confirmBypassButton"),
    toastRegion: document.querySelector("#toastRegion"),
    statusAnnouncements: document.querySelector("#statusAnnouncements"),
    userMessageTemplate: document.querySelector("#userMessageTemplate"),
    workflowTemplate: document.querySelector("#workflowTemplate"),
    turnTemplate: document.querySelector("#turnTemplate"),
    toolCardTemplate: document.querySelector("#toolCardTemplate"),
    permissionCardTemplate: document.querySelector("#permissionCardTemplate")
  };

  const app = {
    connection: "connecting",
    ready: false,
    fatal: false,
    runState: "IDLE",
    runId: null,
    lastEventId: null,
    eventSource: null,
    csrfToken: document.querySelector('meta[name="csrf-token"]')?.content || "",
    permissionMode: "DEFAULT",
    previousPermissionMode: "DEFAULT",
    bypassConfirmed: false,
    autoFollow: true,
    context: null,
    hasConversation: false,
    activeWorkflow: null,
    workflows: new Map(),
    toolCards: new Map(),
    permissions: new Map()
  };

  app.channel = "BroadcastChannel" in window ? new BroadcastChannel("mycoder-workspace") : null;

  function text(value, fallback = "") {
    if (value === null || value === undefined) return fallback;
    return String(value);
  }

  function number(value, fallback = 0) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : fallback;
  }

  function formatNumber(value) {
    return new Intl.NumberFormat("zh-CN", { notation: "compact", maximumFractionDigits: 1 })
      .format(number(value));
  }

  function formatTime(value = Date.now()) {
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) return "";
    return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit" }).format(date);
  }

  function pretty(value) {
    if (typeof value === "string") return value;
    try {
      return JSON.stringify(value ?? {}, null, 2);
    } catch {
      return String(value);
    }
  }

  function announce(message) {
    dom.statusAnnouncements.textContent = "";
    window.requestAnimationFrame(() => {
      dom.statusAnnouncements.textContent = message;
    });
  }

  function showToast(message, tone = "neutral", duration = 2600) {
    const node = document.createElement("div");
    node.className = "toast";
    node.dataset.tone = tone;
    node.textContent = message;
    dom.toastRegion.append(node);
    window.setTimeout(() => node.remove(), duration);
  }

  function isBusy() {
    return ACTIVE_RUN_STATES.has(app.runState);
  }

  function canUseApi() {
    return app.connection === "connected" && app.ready && !app.fatal;
  }

  function updateControls() {
    const usable = canUseApi();
    const busy = isBusy();
    const hasText = dom.composerInput.value.trim().length > 0;

    dom.composerInput.disabled = !usable;
    dom.sendButton.disabled = !usable || busy || !hasText;
    dom.newConversationButton.disabled = !usable || busy || !app.hasConversation;
    dom.permissionMode.disabled = !usable || busy;
    dom.contextButton.disabled = !usable || !app.context;
    dom.compactNowButton.disabled = !usable || busy || !app.context || !app.hasConversation;

    document.querySelectorAll(".suggestion-card").forEach(button => {
      button.disabled = !usable || busy;
    });

    if (!usable) {
      dom.composerHint.textContent = app.fatal ? "请先处理运行状态中的错误" : "等待本地服务连接";
    } else if (busy) {
      dom.composerHint.textContent = "可以继续编辑 · 当前任务完成后发送";
    } else {
      dom.composerHint.textContent = "Enter 发送 · Shift + Enter 换行";
    }
  }

  function updateRunState(nextState, label) {
    const normalized = text(nextState, "IDLE").toUpperCase();
    app.runState = normalized;
    dom.appShell.dataset.runState = normalized;
    dom.runStateValue.textContent = normalized;

    const bannerLabels = {
      RUNNING: "Agent 正在处理当前任务",
      WAITING_PERMISSION: "Agent 正在等待你的授权",
      COMPACTING: "正在压缩旧上下文"
    };
    const bannerText = label || bannerLabels[normalized];
    dom.runBanner.hidden = !ACTIVE_RUN_STATES.has(normalized);
    if (bannerText) dom.runBannerText.textContent = bannerText;

    if (app.connection === "connected") {
      dom.healthButton.dataset.state = ACTIVE_RUN_STATES.has(normalized) ? "busy" : "connected";
      dom.connectionLabel.textContent = ACTIVE_RUN_STATES.has(normalized) ? "本地服务 · 运行中" : "本地服务已连接";
    }
    updateControls();
  }

  function setConnection(connection, label) {
    app.connection = connection;
    const state = connection === "connected" && isBusy() ? "busy" : connection;
    dom.healthButton.dataset.state = state;
    const labels = {
      connecting: "正在连接本地服务",
      connected: "本地服务已连接",
      disconnected: "本地服务未连接",
      error: "本地服务异常"
    };
    dom.connectionLabel.textContent = label || labels[connection] || labels.disconnected;
    updateControls();
  }

  function resizeComposer() {
    dom.composerInput.style.height = "auto";
    dom.composerInput.style.height = `${Math.min(dom.composerInput.scrollHeight, 180)}px`;
    updateControls();
  }

  function isNearBottom() {
    const remaining = dom.conversationScroll.scrollHeight
      - dom.conversationScroll.scrollTop
      - dom.conversationScroll.clientHeight;
    return remaining < 120;
  }

  function scrollToLatest(force = false) {
    if (!force && !app.autoFollow) return;
    window.requestAnimationFrame(() => {
      dom.conversationScroll.scrollTop = dom.conversationScroll.scrollHeight;
    });
  }

  function setAutoFollow(enabled) {
    app.autoFollow = enabled;
    dom.jumpLatestButton.hidden = enabled;
  }

  function removeWelcome() {
    dom.welcome?.remove();
    dom.welcome = null;
  }

  function restoreWelcome() {
    window.location.reload();
  }

  function requestHeaders(hasBody = false) {
    const headers = new Headers();
    headers.set("Accept", "application/json");
    if (hasBody) headers.set("Content-Type", "application/json");
    if (app.csrfToken) headers.set("X-CSRF-Token", app.csrfToken);
    return headers;
  }

  async function apiFetch(url, options = {}) {
    const init = { credentials: "same-origin", ...options };
    const hasBody = init.body !== undefined && init.body !== null;
    const headers = requestHeaders(hasBody);
    if (init.headers) {
      new Headers(init.headers).forEach((value, key) => headers.set(key, value));
    }
    init.headers = headers;

    const response = await fetch(url, init);
    const contentType = response.headers.get("Content-Type") || "";
    let payload = null;
    if (contentType.includes("application/json")) {
      payload = await response.json().catch(() => null);
    } else if (response.status !== 204) {
      payload = await response.text().catch(() => "");
    }

    if (!response.ok) {
      const message = payload && typeof payload === "object"
        ? payload.error || payload.message
        : payload;
      const error = new Error(message || `请求失败 (${response.status})`);
      error.status = response.status;
      error.payload = payload;
      throw error;
    }
    return payload;
  }

  function addUserMessage(content, timestamp) {
    removeWelcome();
    const fragment = dom.userMessageTemplate.content.cloneNode(true);
    const article = fragment.querySelector(".user-message");
    article.dataset.timestamp = text(timestamp, "");
    fragment.querySelector(".user-bubble").textContent = content;
    dom.conversation.append(fragment);
    app.hasConversation = true;
    updateControls();
    scrollToLatest();
  }

  function createWorkflow(runId, timestamp = Date.now(), restored = false) {
    removeWelcome();
    const fragment = dom.workflowTemplate.content.cloneNode(true);
    const article = fragment.querySelector(".assistant-workflow");
    const id = runId || `restored-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    article.dataset.runId = id;
    article.dataset.state = restored ? "completed" : "running";
    article.querySelector("time").textContent = formatTime(timestamp);
    if (restored) article.querySelector(".workflow-status").textContent = "已恢复";
    dom.conversation.append(fragment);
    const inserted = dom.conversation.querySelector(`.assistant-workflow[data-run-id="${CSS.escape(id)}"]`);
    const view = {
      runId: id,
      root: inserted,
      turnList: inserted.querySelector(".turn-list"),
      currentTurn: null,
      currentTurnNumber: 0,
      execution: inserted.querySelector(".execution-details"),
      logList: inserted.querySelector(".log-list"),
      terminal: inserted.querySelector(".workflow-terminal"),
      logCount: 0
    };
    app.workflows.set(id, view);
    app.activeWorkflow = view;
    app.hasConversation = true;
    updateControls();
    scrollToLatest();
    return view;
  }

  function assignWorkflowRunId(view, runId) {
    if (!view || !runId) return view;
    app.workflows.delete(view.runId);
    view.runId = runId;
    view.root.dataset.runId = runId;
    app.workflows.set(runId, view);
    return view;
  }

  function getWorkflow(event = {}) {
    const runId = text(event.runId || event.run_id || app.runId, "");
    if (runId && app.workflows.has(runId)) return app.workflows.get(runId);
    if (app.activeWorkflow && (!runId || app.activeWorkflow.runId === runId)) return app.activeWorkflow;
    return createWorkflow(runId || null, event.timestamp || Date.now());
  }

  function ensureTurn(view, turnNumber) {
    const desired = Math.max(1, number(turnNumber, view.currentTurnNumber || 1));
    if (view.currentTurn && view.currentTurnNumber === desired) return view.currentTurn;

    view.currentTurn?.text.classList.remove("is-streaming");
    const fragment = dom.turnTemplate.content.cloneNode(true);
    fragment.querySelector(".turn-label span").textContent = desired;
    view.turnList.append(fragment);
    const root = view.turnList.lastElementChild;
    view.currentTurn = {
      number: desired,
      root,
      text: root.querySelector(".assistant-text"),
      events: root.querySelector(".event-stack"),
      rawText: ""
    };
    view.currentTurnNumber = desired;
    return view.currentTurn;
  }

  function toolKey(runId, toolId) {
    return `${runId || "current"}:${toolId || "unknown"}`;
  }

  function summarizeTool(name, args) {
    const values = args && typeof args === "object" ? args : {};
    const normalized = text(name);
    if (normalized === "Bash") return text(values.command, "执行 Shell 命令").split(/\r?\n/, 1)[0].slice(0, 100);
    if (["ReadFile", "WriteFile", "EditFile"].includes(normalized)) return text(values.file_path, "文件操作");
    if (["Glob", "Grep"].includes(normalized)) {
      const path = text(values.path, ".");
      return `${text(values.pattern, "模式")} · ${path}`;
    }
    if (normalized.startsWith("mcp__")) {
      const [, server, ...tool] = normalized.split("__");
      return `${server || "MCP"} / ${tool.join("__") || normalized}`;
    }
    const entries = Object.entries(values).slice(0, 2);
    return entries.length ? entries.map(([key, value]) => `${key}: ${text(value).slice(0, 48)}`).join(" · ") : "工具调用";
  }

  function createToolCard(view, event) {
    const turn = ensureTurn(view, event.turn);
    const fragment = dom.toolCardTemplate.content.cloneNode(true);
    const root = fragment.querySelector(".tool-card");
    const toolId = text(event.id || event.toolCallId || event.tool_call_id, "unknown");
    const name = text(event.name, "Unknown tool");
    root.dataset.toolId = toolId;
    root.querySelector(".tool-title strong").textContent = name;
    root.querySelector(".tool-title small").textContent = summarizeTool(name, event.args);
    root.querySelector(".tool-args").textContent = pretty(event.args);

    const header = root.querySelector(".tool-card-header");
    const body = root.querySelector(".tool-card-body");
    header.addEventListener("click", () => {
      const expanded = header.getAttribute("aria-expanded") === "true";
      header.setAttribute("aria-expanded", String(!expanded));
      body.hidden = expanded;
    });

    root.querySelectorAll("[data-copy]").forEach(button => {
      button.addEventListener("click", async eventObject => {
        eventObject.stopPropagation();
        const target = button.dataset.copy === "args" ? root.querySelector(".tool-args") : root.querySelector(".tool-result");
        await copyText(target.textContent, button);
      });
    });

    turn.events.append(fragment);
    const inserted = turn.events.lastElementChild;
    app.toolCards.set(toolKey(view.runId, toolId), inserted);
    scrollToLatest();
    return inserted;
  }

  async function copyText(value, button) {
    try {
      await navigator.clipboard.writeText(value || "");
      const previous = button.textContent;
      button.textContent = "已复制";
      window.setTimeout(() => { button.textContent = previous; }, 1200);
    } catch {
      showToast("复制失败，请手动选择文本", "error");
    }
  }

  function updateToolResult(view, event) {
    const id = text(event.id || event.toolCallId || event.tool_call_id, "unknown");
    let card = app.toolCards.get(toolKey(view.runId, id));
    if (!card) {
      card = createToolCard(view, { ...event, id, name: event.name || "Unknown tool", args: event.args || {} });
    }
    const failed = Boolean(event.error);
    card.dataset.state = failed ? "failed" : "completed";
    card.querySelector(".tool-state").textContent = failed ? "执行失败" : "已完成";
    const output = text(event.output ?? event.preview, "(无输出)");
    card.querySelector(".tool-result").textContent = output || "(无输出)";
    if (event.truncated) {
      card.querySelector(".tool-result").textContent += `\n\n…结果已截断，共 ${formatNumber(event.totalChars || event.total_chars)} 字符`;
    }
    const artifact = text(event.artifactPath || event.artifact_path, "");
    if (artifact) {
      const path = card.querySelector(".artifact-path");
      path.hidden = false;
      path.textContent = `完整结果：${artifact}`;
    }
    scrollToLatest();
  }

  function createPermissionCard(view, event) {
    const turn = ensureTurn(view, event.turn);
    const fragment = dom.permissionCardTemplate.content.cloneNode(true);
    const root = fragment.querySelector(".permission-card");
    const requestId = text(event.requestId || event.request_id);
    root.dataset.requestId = requestId;
    root.querySelector(".permission-heading small").textContent = text(event.toolName || event.tool_name, "未知工具");
    root.querySelector(".permission-description").textContent = text(event.description, "该工具需要授权后才能继续。");
    root.querySelectorAll("[data-decision]").forEach(button => {
      button.addEventListener("click", () => resolvePermission(requestId, button.dataset.decision, root));
    });
    turn.events.append(fragment);
    const inserted = turn.events.lastElementChild;
    app.permissions.set(requestId, inserted);
    updateRunState("WAITING_PERMISSION");
    announce(`工具 ${text(event.toolName)} 正在等待授权`);
    scrollToLatest();
  }

  async function resolvePermission(requestId, decision, card) {
    const buttons = [...card.querySelectorAll("[data-decision]")];
    buttons.forEach(button => { button.disabled = true; });
    try {
      await apiFetch(API.permission, {
        method: "POST",
        body: JSON.stringify({ requestId, decision })
      });
    } catch (error) {
      buttons.forEach(button => { button.disabled = false; });
      showToast(error.message || "授权提交失败", "error");
    }
  }

  function markPermissionResolved(event) {
    const requestId = text(event.requestId || event.request_id);
    const card = app.permissions.get(requestId);
    if (!card) return;
    const decision = text(event.decision, "UNKNOWN");
    const labels = { ALLOW: "已允许一次", ALLOW_ALWAYS: "已设为总是允许", DENY: "已拒绝" };
    card.dataset.state = "resolved";
    card.querySelectorAll("[data-decision]").forEach(button => { button.disabled = true; });
    const resolution = card.querySelector(".permission-resolution");
    resolution.hidden = false;
    resolution.textContent = labels[decision] || `已处理：${decision}`;
    app.permissions.delete(requestId);
    announce(resolution.textContent);
  }

  function addLog(view, message) {
    if (!message) return;
    const node = document.createElement("div");
    node.className = "log-line";
    node.textContent = message;
    view.logList.append(node);
    view.logCount += 1;
    view.execution.dataset.hasLogs = "true";
    view.execution.querySelector(".detail-count").textContent = view.logCount;
    scrollToLatest();
  }

  function addContextEvent(view, message, state = "safe", turnNumber) {
    const turn = ensureTurn(view, turnNumber);
    const node = document.createElement("div");
    node.className = "context-event";
    node.dataset.state = state;
    node.textContent = message;
    turn.events.append(node);
    scrollToLatest();
  }

  function updateContext(payload) {
    const estimated = number(payload.estimatedInputTokens ?? payload.estimated_input_tokens);
    const limit = number(payload.inputLimit ?? payload.input_limit);
    const remaining = number(payload.remainingInputTokens ?? payload.remaining_input_tokens, Math.max(0, limit - estimated));
    const ratio = limit > 0 ? Math.min(1, Math.max(0, estimated / limit)) : 0;
    const level = ratio >= 0.85 ? "critical" : ratio >= 0.7 ? "warning" : "safe";
    app.context = { estimated, limit, remaining, ratio, level, shouldCompact: Boolean(payload.shouldCompact ?? payload.should_compact) };

    const label = `${formatNumber(estimated)} / ${formatNumber(limit)}`;
    dom.contextLabel.textContent = label;
    dom.contextFill.style.width = `${ratio * 100}%`;
    dom.contextButton.dataset.level = level;
    dom.contextButton.setAttribute("aria-label", `上下文用量 ${label}`);
    dom.contextDialogValue.textContent = `${estimated.toLocaleString("zh-CN")} / ${limit.toLocaleString("zh-CN")} tokens`;
    dom.contextDialogFill.style.width = `${ratio * 100}%`;
    dom.contextMeter.dataset.level = level;
    dom.contextDescription.textContent = app.context.shouldCompact
      ? `已达到自动压缩阈值，剩余输入空间约 ${remaining.toLocaleString("zh-CN")} tokens。`
      : `距离输入上限还剩约 ${remaining.toLocaleString("zh-CN")} tokens。`;
    updateControls();
  }

  function finalizeMarkdown(element) {
    if (!element || !element.dataset.rawMarkdown) return;
    const raw = element.dataset.rawMarkdown;
    const marked = window.marked;
    const purifier = window.DOMPurify;
    if (!marked || !purifier) {
      element.classList.remove("is-streaming");
      return;
    }
    try {
      const parsed = typeof marked.parse === "function" ? marked.parse(raw) : marked(raw);
      element.innerHTML = purifier.sanitize(parsed, { USE_PROFILES: { html: true } });
      element.classList.add("is-markdown");
      addCodeBlockActions(element);
    } catch {
      element.textContent = raw;
    }
    element.classList.remove("is-streaming");
  }

  function addCodeBlockActions(container) {
    container.querySelectorAll("pre > code").forEach(code => {
      const pre = code.parentElement;
      if (pre.querySelector(":scope > .code-copy")) return;
      const button = document.createElement("button");
      button.type = "button";
      button.className = "code-copy";
      button.textContent = "复制";
      button.addEventListener("click", () => copyText(code.textContent, button));
      pre.append(button);
    });
  }

  function finishWorkflow(view, state, message) {
    if (!view) return;
    view.root.dataset.state = state === "FAILED" ? "failed" : "completed";
    view.root.querySelector(".workflow-status").textContent = state === "FAILED" ? "运行失败" : "任务完成";
    view.currentTurn?.text.classList.remove("is-streaming");
    view.turnList.querySelectorAll(".assistant-text").forEach(element => finalizeMarkdown(element));
    view.terminal.hidden = false;
    view.terminal.textContent = message || (state === "FAILED" ? "任务失败" : "任务完成");
    updateRunState(state);
    scrollToLatest(true);
  }

  function addError(view, message, retryable = true) {
    const turn = ensureTurn(view, view.currentTurnNumber || 1);
    const card = document.createElement("div");
    card.className = retryable ? "retry-card" : "error-card";
    const copy = document.createElement("div");
    copy.textContent = message || "运行发生错误";
    card.append(copy);
    if (retryable) {
      const button = document.createElement("button");
      button.className = "secondary-button";
      button.type = "button";
      button.textContent = "重试";
      button.addEventListener("click", () => retryLastRun(button));
      card.append(button);
    }
    turn.events.append(card);
    finishWorkflow(view, "FAILED", "任务失败");
    announce(`任务失败：${message}`);
  }

  function handleRunEvent(event) {
    const type = text(event.type).toLowerCase();
    if (!type || type === "heartbeat") return;
    if (event.runId || event.run_id) app.runId = text(event.runId || event.run_id);
    const view = ["conversation_reset", "context_usage"].includes(type) ? app.activeWorkflow : getWorkflow(event);

    switch (type) {
      case "run_started":
        updateRunState(event.operation === "compact" ? "COMPACTING" : "RUNNING");
        if (view) view.root.querySelector(".workflow-status").textContent = event.operation === "compact" ? "正在压缩" : "正在运行";
        break;
      case "turn_started": {
        const turn = ensureTurn(view, event.turn);
        turn.text.classList.add("is-streaming");
        break;
      }
      case "text": {
        const turn = ensureTurn(view, event.turn);
        const delta = text(event.delta);
        turn.rawText += delta;
        turn.text.dataset.rawMarkdown = turn.rawText;
        turn.text.textContent += delta;
        turn.text.classList.add("is-streaming");
        scrollToLatest();
        break;
      }
      case "tool_call":
        createToolCard(view, event);
        break;
      case "tool_result":
        updateToolResult(view, event);
        break;
      case "permission_request":
        createPermissionCard(view, event);
        break;
      case "permission_resolved":
        markPermissionResolved(event);
        if (app.permissions.size === 0) updateRunState("RUNNING");
        break;
      case "context_usage":
        updateContext(event);
        break;
      case "compaction_started":
        updateRunState("COMPACTING");
        addContextEvent(view, "正在压缩旧上下文…", "warning", event.turn);
        break;
      case "context_compacted": {
        const beforeMessages = number(event.beforeMessages ?? event.before_messages);
        const afterMessages = number(event.afterMessages ?? event.after_messages);
        const beforeTokens = number(event.beforeTokens ?? event.before_tokens);
        const afterTokens = number(event.afterTokens ?? event.after_tokens);
        addContextEvent(view, `上下文已压缩：${beforeMessages} → ${afterMessages} 条消息，约 ${formatNumber(beforeTokens)} → ${formatNumber(afterTokens)} tokens`, "safe", event.turn);
        updateRunState("RUNNING");
        break;
      }
      case "compaction_failed":
        addContextEvent(view, `上下文压缩失败：${text(event.message, "未知错误")}`, "warning", event.turn);
        updateRunState("RUNNING");
        break;
      case "compaction_circuit_opened":
        addContextEvent(view, "自动压缩已暂时停用，可在空闲时手动重试。", "warning", event.turn);
        break;
      case "tool_result_offloaded":
        addContextEvent(view, `大工具结果已保存到 ${text(event.artifactPath || event.artifact_path)}`, "safe", event.turn);
        break;
      case "log":
        addLog(view, text(event.message));
        break;
      case "turn_complete":
        if (view?.currentTurn) {
          finalizeMarkdown(view.currentTurn.text);
          view.currentTurn = null;
        }
        break;
      case "loop_complete":
        finishWorkflow(view, "COMPLETED", `任务完成 · 共 ${number(event.turns)} 轮`);
        app.runId = null;
        closeEventStream();
        break;
      case "error":
        addError(view, text(event.message, "Agent 运行失败"), event.retryable !== false);
        app.runId = null;
        closeEventStream();
        break;
      case "conversation_reset":
        clearConversationUi();
        showToast("已开始新对话");
        break;
      default:
        if (view) addLog(view, `[${type}] ${pretty(event)}`);
    }
  }

  function handleSseMessage(sseEvent, forcedType) {
    if (sseEvent.lastEventId) app.lastEventId = sseEvent.lastEventId;
    let payload;
    try {
      payload = JSON.parse(sseEvent.data || "{}");
    } catch {
      showToast("收到无法解析的运行事件", "error");
      return;
    }
    if (!payload.type && forcedType) payload.type = forcedType;
    handleRunEvent(payload);
  }

  function connectEventStream(runId, afterEventId) {
    closeEventStream();
    if (!runId) return;
    app.runId = runId;
    let url = `/api/runs/${encodeURIComponent(runId)}/events`;
    if (afterEventId) url += `?after=${encodeURIComponent(afterEventId)}`;
    const source = new EventSource(url, { withCredentials: true });
    app.eventSource = source;

    source.onopen = () => setConnection("connected");
    source.onmessage = event => handleSseMessage(event);
    SSE_EVENT_NAMES.forEach(typeName => {
      source.addEventListener(typeName, event => handleSseMessage(event, typeName));
    });
    source.onerror = event => {
      if (typeof event.data === "string" && event.data) {
        handleSseMessage(event, "error");
        return;
      }
      if (TERMINAL_RUN_STATES.has(app.runState)) {
        closeEventStream();
        return;
      }
      setConnection("disconnected", "事件流正在重连");
    };
  }

  function closeEventStream() {
    app.eventSource?.close();
    app.eventSource = null;
  }

  async function startRun(message) {
    const trimmed = message.trim();
    if (!trimmed || !canUseApi() || isBusy()) return;
    addUserMessage(trimmed, Date.now());
    dom.composerInput.value = "";
    resizeComposer();
    const pending = createWorkflow(null, Date.now());
    pending.root.querySelector(".workflow-status").textContent = "正在创建任务";
    updateRunState("RUNNING", "正在创建 Agent 任务");

    try {
      const response = await apiFetch(API.runs, {
        method: "POST",
        body: JSON.stringify({ message: trimmed })
      });
      const runId = text(response?.runId || response?.run_id);
      if (!runId) throw new Error("服务未返回 runId");
      assignWorkflowRunId(pending, runId);
      app.activeWorkflow = pending;
      app.runId = runId;
      broadcast({ type: "run_started", runId });
      connectEventStream(runId, response.lastEventId || response.last_event_id);
    } catch (error) {
      addError(pending, error.message || "无法创建任务", false);
      setConnection(error.status ? "connected" : "disconnected");
      showToast(error.message || "无法创建任务", "error");
    }
  }

  async function retryLastRun(button) {
    if (!canUseApi() || isBusy()) return;
    button.disabled = true;
    updateRunState("RUNNING", "正在重试当前任务");
    const view = createWorkflow(null, Date.now());
    try {
      const response = await apiFetch(API.retry, { method: "POST", body: "{}" });
      const runId = text(response?.runId || response?.run_id);
      if (!runId) throw new Error("服务未返回 runId");
      assignWorkflowRunId(view, runId);
      app.runId = runId;
      broadcast({ type: "run_started", runId });
      connectEventStream(runId, response.lastEventId || response.last_event_id);
    } catch (error) {
      addError(view, error.message || "重试失败", false);
    } finally {
      button.disabled = false;
    }
  }

  async function requestCompaction() {
    if (!canUseApi() || isBusy() || !app.hasConversation) return;
    dom.compactNowButton.disabled = true;
    updateRunState("COMPACTING");
    const view = createWorkflow(null, Date.now());
    view.root.querySelector(".workflow-status").textContent = "正在压缩";
    try {
      const response = await apiFetch(API.compact, { method: "POST", body: "{}" });
      const runId = text(response?.runId || response?.run_id);
      if (!runId) throw new Error("服务未返回 runId");
      assignWorkflowRunId(view, runId);
      app.runId = runId;
      broadcast({ type: "run_started", runId });
      dom.contextDialog.close();
      connectEventStream(runId, response.lastEventId || response.last_event_id);
    } catch (error) {
      addError(view, error.message || "无法开始压缩", false);
    }
  }

  function clearConversationUi() {
    closeEventStream();
    app.runId = null;
    app.lastEventId = null;
    app.context = null;
    app.activeWorkflow = null;
    app.workflows.clear();
    app.toolCards.clear();
    app.permissions.clear();
    app.hasConversation = false;
    dom.conversation.replaceChildren();
    updateRunState("IDLE");
    restoreWelcome();
  }

  async function resetConversation() {
    dom.confirmResetButton.disabled = true;
    try {
      await apiFetch(API.reset, { method: "POST", body: "{}" });
      dom.resetDialog.close();
      broadcast({ type: "conversation_reset" });
      clearConversationUi();
    } catch (error) {
      showToast(error.message || "无法新建对话", "error");
    } finally {
      dom.confirmResetButton.disabled = false;
    }
  }

  async function setPermissionMode(mode) {
    try {
      const response = await apiFetch(API.permissionMode, {
        method: "POST",
        body: JSON.stringify({ mode })
      });
      app.permissionMode = text(response?.permissionMode || response?.permission_mode, mode);
      app.previousPermissionMode = app.permissionMode;
      dom.permissionMode.value = app.permissionMode;
      broadcast({ type: "permission_mode", mode: app.permissionMode });
      showToast(`权限模式已切换为 ${dom.permissionMode.selectedOptions[0]?.textContent || app.permissionMode}`);
    } catch (error) {
      dom.permissionMode.value = app.previousPermissionMode;
      showToast(error.message || "权限模式切换失败", "error");
    }
  }

  function applyHealth(state) {
    app.ready = state.ready !== false && !state.fatal;
    app.fatal = Boolean(state.fatal);
    if (state.csrfToken || state.csrf_token) app.csrfToken = text(state.csrfToken || state.csrf_token);
    app.permissionMode = text(state.permissionMode || state.permission_mode, "DEFAULT");
    if (app.permissionMode === "PLAN") app.permissionMode = "DEFAULT";
    app.previousPermissionMode = app.permissionMode;
    dom.permissionMode.value = app.permissionMode;

    const provider = state.provider || {};
    const mcp = state.mcp || {};
    const errors = Array.isArray(state.errors) ? state.errors : [];
    dom.workDirValue.textContent = text(state.workDir || state.work_dir, "—");
    dom.providerValue.textContent = text(provider.name || state.providerName || state.provider_name, "—");
    dom.modelValue.textContent = text(provider.model || state.model, "—");
    const connectedMcp = number(mcp.connectedServers ?? mcp.connected_servers ?? state.connectedMcpServers);
    const registeredTools = number(mcp.registeredTools ?? mcp.registered_tools ?? state.registeredMcpTools);
    dom.mcpValue.textContent = `${connectedMcp} Server · ${registeredTools} 工具`;

    dom.healthErrorList.replaceChildren();
    errors.forEach(item => {
      const node = document.createElement("div");
      node.className = "health-error-item";
      node.textContent = typeof item === "string" ? item : text(item.message, pretty(item));
      dom.healthErrorList.append(node);
    });
    dom.healthErrors.hidden = errors.length === 0;

    const summaryTitle = dom.healthSummary.querySelector("strong");
    const summaryText = dom.healthSummary.querySelector("p");
    if (app.fatal) {
      summaryTitle.textContent = "服务尚未就绪";
      summaryText.textContent = "处理下方配置错误后重新启动 MyCoder。";
      setConnection("error", "本地服务配置异常");
    } else if (!app.ready) {
      summaryTitle.textContent = "正在初始化";
      summaryText.textContent = text(state.stage, "正在连接 Provider 与 MCP Server。");
      setConnection("connecting", "本地服务正在初始化");
    } else {
      summaryTitle.textContent = errors.length ? "服务已启动，存在警告" : "本地服务运行正常";
      summaryText.textContent = errors.length ? "部分可选能力不可用，查看下方详情。" : "Provider 与 Agent 工作台已经就绪。";
      setConnection("connected");
    }

    if (state.context) updateContext(state.context);
    const runState = text(state.runState || state.run_state, "IDLE");
    const runId = text(state.activeRunId || state.active_run_id, "");
    updateRunState(runState);
    if (runId && ACTIVE_RUN_STATES.has(runState.toUpperCase())) {
      app.runId = runId;
      connectEventStream(runId, state.lastEventId || state.last_event_id);
    }
  }

  function hydrateConversation(snapshot) {
    const items = Array.isArray(snapshot?.items)
      ? snapshot.items
      : Array.isArray(snapshot?.messages) ? snapshot.messages : [];
    if (!items.length) return;

    removeWelcome();
    let restoredWorkflow = null;
    items.forEach(item => {
      const type = text(item.type || item.role).toLowerCase();
      if (type === "user") {
        addUserMessage(text(item.content), item.timestamp);
        restoredWorkflow = null;
        return;
      }
      if (["assistant", "tool_call", "tool_result"].includes(type)) {
        if (!restoredWorkflow) restoredWorkflow = createWorkflow(item.runId || item.run_id, item.timestamp, true);
        const turn = ensureTurn(restoredWorkflow, item.turn || 1);
        if (type === "assistant") {
          turn.rawText += text(item.content);
          turn.text.dataset.rawMarkdown = turn.rawText;
          turn.text.textContent = turn.rawText;
          finalizeMarkdown(turn.text);
        } else if (type === "tool_call") {
          createToolCard(restoredWorkflow, item);
        } else {
          updateToolResult(restoredWorkflow, item);
        }
      }
    });
    app.hasConversation = true;
    updateControls();
    scrollToLatest(true);
  }

  async function initialize() {
    bindEvents();
    resizeComposer();
    if (window.location.protocol === "file:") {
      setConnection("disconnected", "静态预览 · 后端未连接");
      showToast("当前是静态预览，启动 MyCoder --web 后可执行任务");
      return;
    }

    try {
      const state = await apiFetch(API.state, { method: "GET" });
      applyHealth(state || {});
      const snapshot = await apiFetch(API.conversation, { method: "GET" }).catch(error => {
        if (error.status !== 404) throw error;
        return null;
      });
      if (snapshot) hydrateConversation(snapshot);
      if (canUseApi()) dom.composerInput.focus();
    } catch (error) {
      app.ready = false;
      setConnection("disconnected");
      dom.healthSummary.querySelector("strong").textContent = "无法连接本地服务";
      dom.healthSummary.querySelector("p").textContent = "请确认 MyCoder 已使用 --web 启动。";
      showToast(error.message || "无法连接本地服务", "error");
    }
  }

  function broadcast(message) {
    app.channel?.postMessage(message);
  }

  function handleBroadcast(message) {
    if (!message || typeof message !== "object") return;
    switch (message.type) {
      case "run_started":
        if (message.runId && app.runId !== message.runId) {
          app.runId = message.runId;
          updateRunState("RUNNING");
          connectEventStream(message.runId);
        }
        break;
      case "conversation_reset":
        clearConversationUi();
        break;
      case "permission_mode":
        if (message.mode && message.mode !== "PLAN") {
          app.permissionMode = message.mode;
          app.previousPermissionMode = message.mode;
          dom.permissionMode.value = message.mode;
        }
        break;
      default:
        break;
    }
  }

  function bindEvents() {
    if (app.channel) app.channel.onmessage = event => handleBroadcast(event.data);
    dom.composerInput.addEventListener("input", resizeComposer);
    dom.composerInput.addEventListener("keydown", event => {
      if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        dom.composerForm.requestSubmit();
      }
    });
    dom.composerForm.addEventListener("submit", event => {
      event.preventDefault();
      startRun(dom.composerInput.value);
    });

    document.querySelectorAll(".suggestion-card").forEach(button => {
      button.addEventListener("click", () => {
        const prompt = text(button.dataset.prompt);
        dom.composerInput.value = prompt;
        resizeComposer();
        startRun(prompt);
      });
    });

    dom.conversationScroll.addEventListener("scroll", () => {
      setAutoFollow(isNearBottom());
    }, { passive: true });
    dom.jumpLatestButton.addEventListener("click", () => {
      setAutoFollow(true);
      scrollToLatest(true);
    });

    dom.healthButton.addEventListener("click", () => dom.healthDialog.showModal());
    dom.contextButton.addEventListener("click", () => dom.contextDialog.showModal());
    dom.compactNowButton.addEventListener("click", requestCompaction);
    dom.newConversationButton.addEventListener("click", () => {
      if (!isBusy()) dom.resetDialog.showModal();
    });
    dom.confirmResetButton.addEventListener("click", resetConversation);

    dom.permissionMode.addEventListener("change", () => {
      const next = dom.permissionMode.value;
      if (next === "BYPASS") {
        app.bypassConfirmed = false;
        dom.bypassDialog.showModal();
      } else {
        setPermissionMode(next);
      }
    });
    dom.cancelBypassButton.addEventListener("click", () => {
      dom.permissionMode.value = app.previousPermissionMode;
      dom.bypassDialog.close();
    });
    dom.confirmBypassButton.addEventListener("click", async () => {
      app.bypassConfirmed = true;
      dom.confirmBypassButton.disabled = true;
      await setPermissionMode("BYPASS");
      dom.confirmBypassButton.disabled = false;
      dom.bypassDialog.close();
    });
    dom.bypassDialog.addEventListener("close", () => {
      if (!app.bypassConfirmed) dom.permissionMode.value = app.previousPermissionMode;
    });

    document.querySelectorAll("[data-close-dialog]").forEach(button => {
      button.addEventListener("click", () => button.closest("dialog")?.close());
    });
    document.querySelectorAll("dialog").forEach(dialog => {
      dialog.addEventListener("click", event => {
        if (event.target === dialog && !dialog.classList.contains("confirm-dialog")) dialog.close();
      });
    });

    window.addEventListener("beforeunload", () => {
      closeEventStream();
      app.channel?.close();
    });
  }

  initialize();
})();
