(function () {
  const state = {
    apiBase: "",
    tasks: [],
    selectedId: "",
    metadata: [],
    stream: null,
    pendingAction: "",
    noticeTimer: null,
  };

  const els = {
    apiBase: document.getElementById("api-base"),
    reloadBtn: document.getElementById("reload-btn"),
    newBtn: document.getElementById("new-btn"),
    notice: document.getElementById("notice"),
    taskCount: document.getElementById("task-count"),
    taskList: document.getElementById("task-list"),
    editorTitle: document.getElementById("editor-title"),
    form: document.getElementById("task-form"),
    taskId: document.getElementById("task-id"),
    name: document.getElementById("name"),
    productId: document.getElementById("productId"),
    messageType: document.getElementById("messageType"),
    deviceCount: document.getElementById("deviceCount"),
    messagesPerMinute: document.getElementById("messagesPerMinute"),
    brokerUrl: document.getElementById("brokerUrl"),
    username: document.getElementById("username"),
    password: document.getElementById("password"),
    clientId: document.getElementById("clientId"),
    clientIdPrefix: document.getElementById("clientIdPrefix"),
    topicTemplate: document.getElementById("topicTemplate"),
    metadataText: document.getElementById("metadata-text"),
    metadataFile: document.getElementById("metadata-file"),
    applyMetadata: document.getElementById("apply-metadata"),
    clearMetadata: document.getElementById("clear-metadata"),
    metadataPreview: document.getElementById("metadata-preview"),
    payloadPreview: document.getElementById("payload-preview"),
    runtimeLog: document.getElementById("runtime-log"),
    runtimeStatus: document.getElementById("runtime-status"),
    streamChip: document.getElementById("stream-chip"),
    startBtn: document.getElementById("start-btn"),
    stopBtn: document.getElementById("stop-btn"),
    deleteBtn: document.getElementById("delete-btn"),
    resetBtn: document.getElementById("reset-btn"),
    statStatus: document.getElementById("stat-status"),
    statConnected: document.getElementById("stat-connected"),
    statSent: document.getElementById("stat-sent"),
    statTargetRate: document.getElementById("stat-target-rate"),
    statRate: document.getElementById("stat-rate"),
    statErrors: document.getElementById("stat-errors"),
    statUptime: document.getElementById("stat-uptime"),
  };

  function api(path) {
    return `${state.apiBase}${path}`;
  }

  function log(message, detail) {
    const entry = document.createElement("div");
    entry.className = "log-entry";
    const ts = new Date().toLocaleTimeString();
    entry.innerHTML = `<small>${ts}</small><div>${message}${detail ? `: ${detail}` : ""}</div>`;
    els.runtimeLog.prepend(entry);
    while (els.runtimeLog.children.length > 20) {
      els.runtimeLog.removeChild(els.runtimeLog.lastChild);
    }
  }

  function showNotice(message, tone = "info", sticky = false) {
    clearTimeout(state.noticeTimer);
    els.notice.className = `notice ${tone}`;
    els.notice.textContent = message;
    if (!sticky) {
      state.noticeTimer = setTimeout(() => {
        els.notice.className = "notice hidden";
        els.notice.textContent = "";
      }, 2600);
    }
  }

  function setPendingAction(action) {
    state.pendingAction = action || "";
    const map = [
      [els.reloadBtn, "reload"],
      [els.applyMetadata, "import"],
      [els.startBtn, "start"],
      [els.stopBtn, "stop"],
      [els.deleteBtn, "delete"],
      [document.getElementById("save-btn"), "save"],
    ];
    map.forEach(([button, key]) => {
      if (!button) return;
      const active = state.pendingAction === key;
      button.disabled = active;
      button.classList.toggle("loading", active);
    });
  }

  function statusClass(status) {
    return `status-${(status || "idle").toLowerCase()}`;
  }

  function defaultTask() {
    return {
      id: "",
      name: "",
      productId: "",
      messageType: "change",
      metadata: [],
      deviceCount: 10,
      messagesPerMinute: 60,
      brokerUrl: "tcp://127.0.0.1:1883",
      username: "",
      password: "",
      clientId: "",
      clientIdPrefix: "mqtt-simulator",
      topicTemplate: "IOT/{productId}/Data",
    };
  }

  function readForm() {
    return {
      id: els.taskId.value,
      name: els.name.value.trim(),
      productId: els.productId.value.trim(),
      messageType: els.messageType.value.trim(),
      metadata: state.metadata,
      deviceCount: Number(els.deviceCount.value || 0),
      messagesPerMinute: Number(els.messagesPerMinute.value || 0),
      brokerUrl: els.brokerUrl.value.trim(),
      username: els.username.value.trim(),
      password: els.password.value,
      clientId: els.clientId.value.trim(),
      clientIdPrefix: els.clientIdPrefix.value.trim(),
      topicTemplate: els.topicTemplate.value.trim(),
    };
  }

  function fillForm(task) {
    const config = task?.config || task || defaultTask();
    state.selectedId = config.id || task?.id || "";
    els.taskId.value = state.selectedId;
    els.name.value = config.name || "";
    els.productId.value = config.productId || "";
    els.messageType.value = config.messageType || "change";
    els.deviceCount.value = config.deviceCount || 10;
    els.messagesPerMinute.value = config.messagesPerMinute || 60;
    els.brokerUrl.value = config.brokerUrl || "tcp://127.0.0.1:1883";
    els.username.value = config.username || "";
    els.password.value = config.password || "";
    els.clientId.value = config.clientId || "";
    els.clientIdPrefix.value = config.clientIdPrefix || "mqtt-simulator";
    els.topicTemplate.value = config.topicTemplate || "IOT/{productId}/Data";
    state.metadata = Array.isArray(config.metadata) ? config.metadata : [];
    els.metadataText.value = state.metadata.length ? JSON.stringify({ properties: state.metadata }, null, 2) : "";
    renderMetadataPreview();
    renderRuntime(task?.runtime);
    updatePreview();
    els.editorTitle.textContent = state.selectedId ? `Edit simulation: ${config.name || config.productId}` : "Create simulation";
  }

  function renderMetadataPreview() {
    if (!state.metadata.length) {
      els.metadataPreview.textContent = "No metadata imported.";
      return;
    }
    els.metadataPreview.textContent = JSON.stringify(
      state.metadata.slice(0, 12).map((item) => ({
        id: item.id,
        name: item.name,
        type: item.valueType?.type || item.valueType?.id || "unknown",
      })),
      null,
      2
    );
  }

  function renderTasks() {
    els.taskCount.textContent = `${state.tasks.length} simulations`;
    els.taskList.innerHTML = "";
    if (!state.tasks.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "No tasks yet. Create a task to begin sending MQTT traffic.";
      els.taskList.appendChild(empty);
      return;
    }

    state.tasks.forEach((task) => {
      const item = document.createElement("button");
      item.type = "button";
      item.className = `task-item${task.id === state.selectedId ? " active" : ""}`;
      const runtime = task.runtime || {};
      item.innerHTML = `
        <div class="task-item-head">
          <div>
            <h3>${escapeHtml(task.config.name || task.config.productId)}</h3>
            <p>${escapeHtml(task.config.productId)}</p>
          </div>
          <span class="chip ${statusClass(runtime.status)}">${escapeHtml(runtime.status || "idle")}</span>
        </div>
        <div class="task-meta">
          <span class="chip">${task.config.deviceCount} devices</span>
          <span class="chip">${task.config.messagesPerMinute}/min</span>
        </div>
      `;
      item.addEventListener("click", () => fillForm(task));
      els.taskList.appendChild(item);
    });
  }

  async function request(path, options = {}) {
    const response = await fetch(api(path), {
      headers: {
        "Content-Type": "application/json",
        ...(options.headers || {}),
      },
      ...options,
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(payload.error || `Request failed: ${response.status}`);
    }
    if (response.status === 204) {
      return null;
    }
    return response.json();
  }

  async function loadTasks() {
    try {
      setPendingAction("reload");
      const data = await request("/api/tasks");
      state.tasks = data.items || [];
      renderTasks();
      if (!state.selectedId && state.tasks.length) {
        fillForm(state.tasks[0]);
      } else if (state.selectedId) {
        const selected = state.tasks.find((item) => item.id === state.selectedId);
        if (selected) {
          fillForm(selected);
        }
      }
      log("Tasks reloaded");
      showNotice("Tasks reloaded.", "info");
    } catch (error) {
      log("Load failed", error.message);
      showNotice(`Load failed: ${error.message}`, "error", true);
    } finally {
      setPendingAction("");
    }
  }

  async function importMetadata() {
    try {
      setPendingAction("import");
      showNotice("Importing metadata...", "info", true);
      const raw = els.metadataText.value.trim();
      const data = await request("/api/products/import-model", {
        method: "POST",
        body: JSON.stringify({ raw }),
      });
      state.metadata = data.properties || [];
      renderMetadataPreview();
      updatePreview();
      log("Metadata imported", `${state.metadata.length} properties`);
      showNotice(`Imported ${state.metadata.length} properties.`, "success");
    } catch (error) {
      log("Metadata import failed", error.message);
      showNotice(`Metadata import failed: ${error.message}`, "error", true);
      alert(error.message);
    } finally {
      setPendingAction("");
    }
  }

  async function updatePreview() {
    const config = readForm();
    if (!config.productId || !config.brokerUrl || !config.deviceCount || !config.messagesPerMinute || !state.metadata.length || !config.name) {
      els.payloadPreview.textContent = "Fill the form and import metadata to preview payload.";
      return;
    }
    try {
      const data = await request("/api/preview/payload", {
        method: "POST",
        body: JSON.stringify({ config }),
      });
      els.payloadPreview.textContent = `Topic: ${data.topic}\n\n${data.payload}`;
    } catch (error) {
      els.payloadPreview.textContent = error.message;
    }
  }

  async function saveTask(event) {
    event.preventDefault();
    const config = readForm();
    if (!state.metadata.length) {
      alert("Please import metadata before saving.");
      return;
    }

    const path = config.id ? `/api/tasks/${config.id}` : "/api/tasks";
    const method = config.id ? "PUT" : "POST";

    try {
      setPendingAction("save");
      showNotice(config.id ? "Updating task..." : "Creating task...", "info", true);
      const result = await request(path, {
        method,
        body: JSON.stringify(config),
      });
      log(config.id ? "Task updated" : "Task created", config.name);
      await loadTasks();
      if (config.id && result && result.restarted) {
        showNotice("Task updated and restarted with the new settings.", "success");
      } else {
        showNotice(config.id ? "Task updated." : "Task created.", "success");
      }
    } catch (error) {
      log("Save failed", error.message);
      showNotice(`Save failed: ${error.message}`, "error", true);
      alert(error.message);
    } finally {
      setPendingAction("");
    }
  }

  async function mutateTask(action) {
    const id = els.taskId.value;
    if (!id) {
      alert("Select or save a task first.");
      return;
    }
    try {
      setPendingAction(action);
      showNotice(`${action === "delete" ? "Deleting" : action === "start" ? "Starting" : "Stopping"} task...`, "info", true);
      if (action === "delete") {
        await request(`/api/tasks/${id}`, { method: "DELETE" });
        log("Task deleted", id);
        fillForm(defaultTask());
        await loadTasks();
        showNotice("Task deleted.", "success");
        return;
      }
      await request(`/api/tasks/${id}/${action}`, { method: "POST" });
      log(`Task ${action}ed`, id);
      await loadTasks();
      showNotice(`Task ${action}ed successfully.`, "success");
    } catch (error) {
      log(`Task ${action} failed`, error.message);
      showNotice(`Task ${action} failed: ${error.message}`, "error", true);
      alert(error.message);
    } finally {
      setPendingAction("");
    }
  }

  function renderRuntime(runtime = {}) {
    const config = state.tasks.find((item) => item.id === state.selectedId)?.config || readForm();
    if (runtime.lastError) {
      els.runtimeStatus.textContent = runtime.lastError;
    } else if ((runtime.status || "idle") === "running") {
      els.runtimeStatus.textContent = "Task is running and publishing.";
    } else if ((runtime.status || "idle") === "starting") {
      els.runtimeStatus.textContent = "Task is connecting to broker...";
    } else if ((runtime.status || "idle") === "stopped") {
      els.runtimeStatus.textContent = "Task stopped.";
    } else {
      els.runtimeStatus.textContent = "Idle";
    }
    els.statStatus.textContent = runtime.status || "idle";
    els.statConnected.textContent = runtime.connected ? "yes" : "no";
    els.statSent.textContent = `${runtime.sentTotal || 0}`;
    els.statTargetRate.textContent = `${config.messagesPerMinute || 0}/min`;
    els.statRate.textContent = `${runtime.sentLastSecond || 0}/sec`;
    els.statErrors.textContent = `${runtime.failedTotal || 0}`;
    els.statUptime.textContent = formatUptime(runtime.startedAt);
    els.statStatus.classList.toggle("is-live", (runtime.status || "idle") === "running");
    els.statSent.classList.toggle("is-live", (runtime.sentTotal || 0) > 0);
    els.statRate.classList.toggle("is-live", (runtime.sentLastSecond || 0) > 0);
  }

  function formatUptime(startedAt) {
    if (!startedAt) return "00:00:00";
    const diff = Math.max(0, Date.now() - new Date(startedAt).getTime());
    const total = Math.floor(diff / 1000);
    const h = String(Math.floor(total / 3600)).padStart(2, "0");
    const m = String(Math.floor((total % 3600) / 60)).padStart(2, "0");
    const s = String(total % 60).padStart(2, "0");
    return `${h}:${m}:${s}`;
  }

  function connectStream() {
    if (state.stream) {
      state.stream.close();
    }
    try {
      state.stream = new EventSource(api("/api/stream/tasks"));
      els.streamChip.textContent = "Streaming";
      state.stream.addEventListener("task", (event) => {
        const data = JSON.parse(event.data);
        const task = state.tasks.find((item) => item.id === data.taskId);
        if (task) {
          task.runtime = data.runtime;
          renderTasks();
          if (task.id === state.selectedId) {
            renderRuntime(data.runtime);
            if (data.runtime.status === "running") {
              showNotice("Task is now running.", "success");
            } else if (data.runtime.status === "stopped") {
              showNotice("Task has stopped.", "info");
            } else if (data.runtime.status === "error") {
              showNotice(`Task error: ${data.runtime.lastError || "unknown error"}`, "error", true);
            }
          }
        }
      });
      state.stream.onerror = () => {
        els.streamChip.textContent = "Stream offline";
        showNotice("Realtime stream disconnected.", "error", true);
      };
    } catch (error) {
      els.streamChip.textContent = "No stream";
    }
  }

  function resetEditor() {
    state.selectedId = "";
    state.metadata = [];
    els.form.reset();
    fillForm(defaultTask());
  }

  function escapeHtml(value) {
    return String(value || "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;");
  }

  els.apiBase.addEventListener("change", () => {
    state.apiBase = els.apiBase.value.trim().replace(/\/$/, "");
    connectStream();
    loadTasks();
  });
  els.reloadBtn.addEventListener("click", loadTasks);
  els.newBtn.addEventListener("click", resetEditor);
  els.applyMetadata.addEventListener("click", importMetadata);
  els.clearMetadata.addEventListener("click", () => {
    state.metadata = [];
    els.metadataText.value = "";
    renderMetadataPreview();
    updatePreview();
  });
  els.metadataFile.addEventListener("change", async (event) => {
    const file = event.target.files && event.target.files[0];
    if (!file) return;
    els.metadataText.value = await file.text();
  });
  els.form.addEventListener("submit", saveTask);
  els.startBtn.addEventListener("click", () => mutateTask("start"));
  els.stopBtn.addEventListener("click", () => mutateTask("stop"));
  els.deleteBtn.addEventListener("click", () => mutateTask("delete"));
  els.resetBtn.addEventListener("click", resetEditor);
  ["input", "change"].forEach((eventName) => {
    els.form.addEventListener(eventName, updatePreview);
  });

  fillForm(defaultTask());
  connectStream();
  loadTasks();
})();
