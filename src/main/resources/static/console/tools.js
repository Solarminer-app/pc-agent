/* Specialist workspace. Independent REST contracts; no legacy assets, controllers or write retries. */
(() => {
  const { t, has, locale } = window.OperationsI18n;
  const $ = (id) => document.getElementById(id);
  const api = "/api/agent/local/operations";
  const names = [
    "wallets",
    "measurements",
    "hardware",
    "tuning",
    "updates",
    "logs",
  ];
  const views = new Map(),
    tasks = new Map(),
    taskNodes = new Map(),
    selectedWorkers = new Set();
  const deviceParameter =
    new URLSearchParams(location.search).get("device") || "";
  let device = deviceParameter,
    section = "",
    source = null,
    ownId = "",
    readVersion = 0,
    controller = null;
  let timer = null,
    taskTimer = null,
    pollBusy = false,
    pickerPage = 0,
    pickerVersion = 0,
    pickerQuery = "";
  let currentNotice = "";
  let submitting = false,
    online = false,
    catalog = [],
    logTimer = null,
    logVersion = 0,
    logOffset = 0,
    logRun = "",
    logDecoder = new TextDecoder();
  const node = (tag, cls, value) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (value != null) n.textContent = value;
    return n;
  };
  const text = (n, value) => {
    if (n && n.textContent !== String(value)) n.textContent = value;
  };
  const label = (key) => (has(key) ? t(key) : t("unknown"));
  const number = (value, digits = 2) =>
    value == null || !Number.isFinite(Number(value))
      ? "—"
      : new Intl.NumberFormat(locale, { maximumFractionDigits: digits }).format(
          Number(value),
        );
  const metric = (value, unit) =>
    value == null ? "—" : `${number(value)} ${unit}`;
  const message = (key) => {
    currentNotice = key;
    text($("tool-notice"), key ? label(key) : "");
    $("tool-notice").hidden = !key;
  };
  const current = () => views.get(section);
  const payload = () => source?.payload;
  const fresh = () =>
    online &&
    source?.payload &&
    !source.error &&
    Date.now() - new Date(source.collectedAt).getTime() < 15000;
  const buttons = new Map();
  let fieldSequence = 0;
  function field(key, type = "text", options = {}) {
    const wrapper = node("div", "tool-field"),
      title = node("label", "", t(key));
    const input = node(type === "select" ? "select" : "input");
    input.id = `tool-field-${++fieldSequence}`;
    title.htmlFor = input.id;
    if (type !== "select") input.type = type;
    for (const [key, value] of Object.entries(options)) input[key] = value;
    wrapper.append(title, input);
    return { wrapper, input };
  }
  function check(key, value = false) {
    const wrapper = node("label", "tool-check"),
      input = node("input");
    input.type = "checkbox";
    input.checked = value;
    wrapper.append(input, node("span", "", t(key)));
    return { wrapper, input };
  }
  function box(key) {
    const root = node("div", "tool-box");
    root.append(node("h3", "", t(key)));
    return root;
  }
  function action(key, handler, available = () => true, primary = false) {
    const button = node("button", primary ? "primary" : "", t(key));
    button.type = "button";
    button.addEventListener("click", () => {
      if (!button.disabled) handler();
    });
    buttons.set(button, available);
    return button;
  }
  function actions(...values) {
    const root = node("div", "tool-actions");
    root.append(...values);
    return root;
  }
  function selectOptions(select, values, signatureKey) {
    const signature = JSON.stringify(values);
    if (select.dataset.signature === signature) return;
    const before = select.value;
    select.dataset.signature = signature;
    select.replaceChildren(
      ...values.map(([value, text]) => new Option(text, value)),
    );
    if (values.some(([value]) => value === before)) select.value = before;
    if (signatureKey && !select.value) select.value = signatureKey;
  }
  function updateButtons() {
    const busy =
      submitting ||
      [...tasks.values()].some((task) =>
        ["queued", "running"].includes(task.state),
      );
    for (const [button, available] of buttons)
      if (button.isConnected)
        button.disabled = !fresh() || busy || !available();
  }
  function table(root, headings, values) {
    const table = node("table"),
      head = node("thead"),
      tr = node("tr"),
      body = node("tbody");
    headings.forEach((key) => tr.append(node("th", "", t(key))));
    head.append(tr);
    for (const valuesRow of values.slice(-50)) {
      const row = node("tr");
      valuesRow.forEach((value) => row.append(node("td", "", value ?? "—")));
      body.append(row);
    }
    table.append(head, body);
    root.replaceChildren(table);
    if (!values.length)
      root.append(node("p", "tool-empty", t("tool_no_results")));
  }
  function exportLink(section) {
    const link = node("a", "comparison", t("tool_export"));
    link.href = `${api}/tools/export/${section}`;
    return link;
  }
  const selectedDevice = () =>
    payload()?.gpus?.find((row) => row.deviceId === device);
  const selectedWorker = () =>
    payload()?.workers?.find((row) => row.deviceId === device);
  const argumentNumber = (input) =>
    input.value === "" ? null : Number(input.value);
  async function request(action, argumentsValue = {}) {
    if (!fresh() || submitting) return;
    const id =
      crypto.randomUUID?.() ||
      Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) =>
        byte.toString(16).padStart(2, "0"),
      ).join("");
    const job = {
      id,
      action,
      section,
      device: argumentsValue.device || null,
      coin: argumentsValue.coin || null,
      workerCount: argumentsValue.workers?.length || 0,
      state: "queued",
      error: null,
    };
    tasks.set(id, job);
    saveTasks();
    renderTasks();
    submitting = true;
    updateButtons();
    try {
      const response = await fetch(`${api}/tools/tasks`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id, action, arguments: argumentsValue }),
        signal: AbortSignal.timeout(10000),
      });
      const result = await response.json();
      if (!response.ok) {
        tasks.set(id, {
          ...job,
          state: "failed",
          error: result.error || "tool_action_failed",
        });
      } else {
        tasks.set(id, { ...job, ...result });
        message("");
      }
    } catch (_) {
      message("tool_acceptance_unknown");
    } finally {
      submitting = false;
      saveTasks();
      renderTasks();
      scheduleTasks();
    }
  }
  function saveTasks() {
    try {
      sessionStorage.setItem(
        "solarminer.operations.tool-tasks",
        JSON.stringify(
          [...tasks.values()]
            .slice(-16)
            .map(({ id, action, section, device, coin, workerCount }) => ({
              id,
              action,
              section,
              device,
              coin,
              workerCount,
            })),
        ),
      );
    } catch (_) {}
  }
  function renderTasks() {
    const finished = [...tasks.values()].filter((task) =>
      ["succeeded", "failed", "missing"].includes(task.state),
    );
    for (const task of finished.slice(0, Math.max(0, finished.length - 8))) {
      tasks.delete(task.id);
      taskNodes.get(task.id)?.root.remove();
      taskNodes.delete(task.id);
    }
    for (const task of [...tasks.values()]) {
      let view = taskNodes.get(task.id);
      if (!view) {
        const root = node("article", "tool-task"),
          name = node("strong"),
          state = node("span", "state"),
          reason = node("p", "reason");
        state.setAttribute("role", "status");
        root.append(name, state, reason);
        $("tool-tasks").prepend(root);
        view = { root, name, state, reason };
        taskNodes.set(task.id, view);
      }
      text(
        view.name,
        `${label(`tool_${task.action}`)}${task.device || task.coin ? ` · ${task.device || task.coin}` : task.workerCount ? ` · ${t("workers_count", { count: task.workerCount })}` : ""}`,
      );
      text(
        view.state,
        task.state === "missing" ? t("unknown") : label(task.state),
      );
      view.state.className = `state ${task.state}`;
      text(
        view.reason,
        task.state === "missing"
          ? t("tool_job_missing")
          : task.error
            ? has(task.error)
              ? t(task.error)
              : t("tool_action_failed")
            : "",
      );
      if ((task.detail || (task.error && !has(task.error))) && !view.detail) {
        const details = node("details", "tool-diagnostic"),
          detail = node("code");
        details.append(node("summary", "", t("diagnostic")), detail);
        view.root.append(details);
        view.detail = detail;
      }
      if (view.detail) text(view.detail, task.detail || task.error || "");
    }
    updateButtons();
  }
  function scheduleTasks() {
    clearTimeout(taskTimer);
    if (
      !document.hidden &&
      [...tasks.values()].some((task) =>
        ["queued", "running"].includes(task.state),
      )
    )
      taskTimer = setTimeout(pollTasks, 1000);
  }
  async function pollTasks() {
    if (pollBusy || document.hidden) return;
    pollBusy = true;
    try {
      await Promise.all(
        [...tasks.values()]
          .filter((task) => ["queued", "running"].includes(task.state))
          .map(async (task) => {
            try {
              const response = await fetch(
                `${api}/tools/tasks/${encodeURIComponent(task.id)}`,
                { cache: "no-store", signal: AbortSignal.timeout(8000) },
              );
              if (response.status === 404)
                tasks.set(task.id, { ...task, state: "missing" });
              else if (response.ok)
                tasks.set(task.id, {
                  ...task,
                  ...(await response.json()),
                  section: task.section,
                });
            } catch (_) {
              /* Read again later; never repeat a write. */
            }
          }),
      );
      saveTasks();
      renderTasks();
      if (
        currentNotice === "tool_acceptance_unknown" &&
        [...tasks.values()].every(
          (task) => !["queued", "running"].includes(task.state),
        )
      )
        message("");
      for (const task of tasks.values())
        if (task.action === "update_start" && task.state === "failed") {
          try {
            sessionStorage.removeItem("solarminer.operations.update-target");
          } catch (_) {}
        }
    } finally {
      pollBusy = false;
      scheduleTasks();
    }
  }
  function mountWallets(root) {
    const form = node("div", "tool-form"),
      coin = field("tool_coin", "select"),
      pool = field("tool_pool", "url"),
      wallet = field("tool_wallet"),
      worker = field("tool_worker_name");
    pool.input.type = "text";
    worker.input.maxLength = 32;
    wallet.input.maxLength = 200;
    pool.input.maxLength = 512;
    const status = node("p", "tool-status"),
      rows = node("div", "tool-results"),
      balances = node("div", "tool-results");
    form.append(
      coin.wrapper,
      pool.wrapper,
      wallet.wrapper,
      worker.wrapper,
      node("p", "tool-muted", t("tool_default_hint")),
      node("p", "tool-muted", t("tool_route_stop")),
      action(
        "tool_route_save",
        () =>
          request("route_save", {
            coin: coin.input.value,
            pool: pool.input.value,
            wallet: wallet.input.value.trim(),
            worker: worker.input.value.trim(),
          }),
        () => Boolean(coin.input.value),
        true,
      ),
    );
    const grid = node("div", "tool-grid");
    grid.append(form, status);
    root.append(grid, rows, balances);
    let hydrated = "";
    const hydrate = () => {
      const value = payload()?.routes?.find(
        (row) => row.coin === coin.input.value,
      );
      if (!value) return;
      pool.input.value = value.route?.poolUrl || "";
      wallet.input.value = value.usesDefault ? "" : value.route?.wallet || "";
      worker.input.value = value.route?.worker || "solarminer";
      hydrated = value.coin;
    };
    coin.input.addEventListener("change", hydrate);
    return () => {
      const routes = payload()?.routes || [];
      selectOptions(
        coin.input,
        routes.map((row) => [row.coin, `${row.name} (${row.ticker})`]),
      );
      if (!hydrated) hydrate();
      const value = routes.find((row) => row.coin === coin.input.value);
      text(
        status,
        value
          ? `${t(value.usesDefault ? "tool_default_active" : "tool_own_active")}\n${
              value.defaultTarget?.available
                ? t("tool_default_target", {
                    pool: value.defaultTarget.poolUrl,
                    wallet: value.defaultTarget.maskedWallet,
                  })
                : t("tool_default_missing")
            }`
          : t("tool_wait_for_data"),
      );
      table(
        rows,
        ["tool_coin", "tool_pool", "tool_route_saved"],
        routes.map((row) => [
          row.name,
          row.route?.poolUrl,
          row.usesDefault ? t("tool_default_active") : row.route?.wallet || "—",
        ]),
      );
      table(
        balances,
        ["tool_coin", "tool_pool_balance", "tool_paid", "tool_chain_balance"],
        (payload()?.balances || []).map((row) => [
          row.coin,
          number(row.poolBalance, 8),
          number(row.paidTotal, 8),
          number(row.onChainBalance, 8),
        ]),
      );
    };
  }
  function mountMeasurements(root) {
    const mode = field("tool_mode", "select"),
      concurrency = field("tool_concurrency", "number", {
        min: "1",
        max: "64",
        step: "1",
        value: "1",
      }),
      after = check("tool_after");
    mode.input.append(
      new Option(t("tool_live"), "LIVE"),
      new Option(t("tool_installed"), "INSTALLED"),
    );
    const coins = node("div", "tool-selection"),
      coinChecks = new Map(),
      status = node("p", "tool-status"),
      progress = node("progress", "tool-progress");
    progress.max = 1;
    const selection = node("p", "muted"),
      form = node("div", "tool-form");
    const args = () => ({
      mode: mode.input.value,
      concurrency: Number(concurrency.input.value),
      startAfter: after.input.checked,
      workers: [...selectedWorkers],
      coins: [...coinChecks]
        .filter(([, input]) => input.checked)
        .map(([coin]) => coin),
    });
    const available = () =>
      selectedWorkers.size > 0 &&
      [...coinChecks.values()].some((input) => input.checked) &&
      !payload()?.benchmark?.running &&
      !payload()?.sweep?.running;
    form.append(
      mode.wrapper,
      concurrency.wrapper,
      after.wrapper,
      node("h3", "tool-subheading", t("tool_coins")),
      coins,
      selection,
      node("p", "tool-muted", t("tool_measure_hint")),
      actions(
        action(
          "tool_benchmark_start",
          () => request("benchmark_start", args()),
          available,
          true,
        ),
        action(
          "tool_benchmark_cancel",
          () => request("benchmark_cancel"),
          () => Boolean(payload()?.benchmark?.running),
        ),
      ),
      actions(
        action(
          "tool_sweep_start",
          () => request("sweep_start", args()),
          available,
        ),
        action(
          "tool_sweep_resume",
          () => request("sweep_resume", args()),
          available,
        ),
        action(
          "tool_sweep_cancel",
          () => request("sweep_cancel"),
          () => Boolean(payload()?.sweep?.running),
        ),
      ),
    );
    const sharing = box("tool_consent"),
      consent = check("tool_consent"),
      upload = node("p", "tool-status");
    sharing.append(
      consent.wrapper,
      node("p", "tool-muted", t("tool_consent_hint")),
      actions(
        action("tool_benchmark_consent", () =>
          request("benchmark_consent", { enabled: consent.input.checked }),
        ),
        action("tool_benchmark_upload", () => request("benchmark_upload")),
        action("tool_benchmark_retry", () => request("benchmark_retry")),
      ),
      upload,
    );
    const grid = node("div", "tool-grid"),
      summary = box("tool_progress");
    summary.append(status, progress);
    grid.append(form, summary);
    const liveRows = node("div", "tool-results");
    const results = node("div", "tool-results"),
      count = node("p", "tool-muted");
    root.append(
      grid,
      liveRows,
      sharing,
      node("h3", "tool-subheading", t("tool_results")),
      count,
      results,
      exportLink("measurements"),
    );
    let hydrated = false;
    return () => {
      for (const option of catalog)
        if (!coinChecks.has(option.coin)) {
          const value = check("tool_coin");
          value.wrapper.lastChild.textContent = option.coinName || option.coin;
          value.input.checked = false;
          value.input.addEventListener("change", updateButtons);
          coins.append(value.wrapper);
          coinChecks.set(option.coin, value.input);
        }
      const data = payload();
      if (!data) return;
      if (!hydrated) {
        if (data.benchmark?.running && !selectedWorkers.size) {
          for (const id of data.benchmark.selectedWorkers || [])
            selectedWorkers.add(id);
          for (const input of document.querySelectorAll(".picker-row input"))
            input.checked = selectedWorkers.has(input.value);
        }
        consent.input.checked = data.consent;
        hydrated = true;
      }
      text(
        selection,
        t("tool_worker_selection", { count: selectedWorkers.size }),
      );
      const benchmark = data.benchmark,
        sweep = data.sweep;
      text(
        status,
        `${t("tool_measurements")}: ${t(benchmark?.running ? "tool_active" : "tool_idle")} · ${number(benchmark?.phaseIndex, 0)}/${number(benchmark?.phaseCount, 0)}\n${t("tool_sweep_start")}: ${t(sweep?.running ? "tool_active" : "tool_idle")} · ${number(sweep?.phaseIndex, 0)}/${number(sweep?.phaseCount, 0)}`,
      );
      const running = benchmark?.running ? benchmark : sweep;
      progress.value = running?.phaseCount
        ? running.phaseIndex / running.phaseCount
        : 0;
      progress.hidden = !running?.running;
      const traces = (benchmark?.liveSeries || []).map((trace) => {
        const latest = trace.points?.at(-1);
        return [
          trace.deviceId,
          trace.algorithm,
          trace.sampleCount ?? trace.points?.length,
          metric(latest?.hashrateHs, "H/s"),
          metric(latest?.powerWatts, "W"),
        ];
      });
      const sweepTraces = (sweep?.runs || []).map((run) => [
        run.deviceId,
        run.algorithm,
        `${run.samples}/${run.samplesRequired}`,
        metric(run.limitWatts, "W"),
        has(`tool_run_${run.status}`)
          ? t(`tool_run_${run.status}`)
          : t("unknown"),
      ]);
      table(
        liveRows,
        ["device", "algorithm", "tool_samples", "hashrate", "power"],
        traces,
      );
      if (sweepTraces.length) {
        const sweepTable = node("div", "tool-results");
        table(
          sweepTable,
          ["device", "algorithm", "tool_samples", "power", "status"],
          sweepTraces,
        );
        liveRows.append(sweepTable);
      }
      const benchmarkRows = (data.benchmarks || []).map((row) => [
        row.workerId,
        row.algorithm,
        metric(row.hashrateHps, "H/s"),
        metric(row.powerWatts, "W"),
        row.observations,
        t("tool_benchmark_start"),
      ]);
      const sweepRows = (data.efficiency || []).map((row) => [
        row.deviceId,
        row.algorithm,
        metric(row.bestHashrateHs, "H/s"),
        metric(row.bestPowerWatts, "W"),
        row.steps?.length,
        t("tool_sweep_start"),
      ]);
      table(
        results,
        [
          "device",
          "algorithm",
          "hashrate",
          "power",
          "tool_samples",
          "tool_profile_source",
        ],
        [...benchmarkRows, ...sweepRows],
      );
      text(
        count,
        t("tool_rows_limit", {
          count: (data.benchmarksTotal || 0) + (data.efficiencyTotal || 0),
        }),
      );
      const states = Object.values(data.uploads || {})
        .map((value) =>
          value && typeof value === "object"
            ? has(`tool_upload_${value.status}`)
              ? t(`tool_upload_${value.status}`)
              : t("unknown")
            : "",
        )
        .filter(Boolean);
      text(
        upload,
        `${t("tool_upload_status")}: ${states.join(" · ") || t("tool_idle")}`,
      );
    };
  }
  function mountHardware(root) {
    const grid = node("div", "tool-grid"),
      capBox = box("tool_watts"),
      modeBox = box("mode"),
      status = node("dl", "tool-hardware-spec");
    const watts = field("tool_watts", "number", { step: "1" }),
      mode = field("mode", "select");
    for (const key of [
      "MANUAL",
      "EFFICIENCY",
      "MAX_HASHRATE",
      "AUTO_TUNING",
      "REVENUE_PER_KWH",
      "COIN_REVENUE_PER_KWH",
      "PROFIT_PER_DAY",
      "COIN_PROFIT_PER_DAY",
    ])
      mode.input.append(new Option(label(key), key));
    capBox.append(
      status,
      watts.wrapper,
      node("p", "tool-muted", t("tool_power_hint")),
      action(
        "tool_power_limit",
        () =>
          request("power_limit", { device, watts: Number(watts.input.value) }),
        () => Boolean(selectedDevice()?.supportsDynamicPowerScaling),
        true,
      ),
    );
    modeBox.append(
      mode.wrapper,
      node("p", "tool-muted", t("tool_pause_before_mode")),
      action(
        "tool_power_mode",
        () => request("power_mode", { device, mode: mode.input.value }),
        () => Boolean(selectedWorker()),
      ),
    );
    const policy = box("tool_policy"),
      remote = check("tool_remote_worker"),
      boot = check("tool_boot");
    policy.append(
      remote.wrapper,
      boot.wrapper,
      action(
        "tool_worker_policy",
        () =>
          request("worker_policy", {
            device,
            remote: remote.input.checked,
            startOnBoot: boot.input.checked,
          }),
        () => Boolean(selectedWorker()),
      ),
    );
    const energy = box("tool_energy"),
      price = field("tool_tariff", "number", {
        min: "0",
        max: "100",
        step: "0.0001",
      }),
      currency = field("tool_currency", "select"),
      energyStatus = node("p", "tool-status");
    currency.input.append(
      ...["EUR", "USD", "CHF"].map((code) => new Option(code, code)),
    );
    energy.append(
      price.wrapper,
      currency.wrapper,
      energyStatus,
      action("tool_energy_settings", () =>
        request("energy_settings", {
          pricePerKwh: argumentNumber(price.input),
          currency: currency.input.value,
        }),
      ),
    );
    const cpuBox = box("tool_cpu_watts"),
      cpuWatts = field("tool_cpu_watts", "number", {
        min: "1",
        max: "10000",
        step: "1",
      });
    cpuBox.append(
      cpuWatts.wrapper,
      node("p", "tool-muted", t("tool_cpu_hint")),
      action(
        "tool_cpu_target",
        () =>
          request("cpu_target", {
            device,
            watts: Number(cpuWatts.input.value),
          }),
        () =>
          selectedWorker()?.hardwareType === "CPU" &&
          selectedWorker()?.powerMode === "MANUAL" &&
          !["MINING", "STARTING"].includes(selectedWorker()?.status),
        true,
      ),
    );
    grid.append(capBox, cpuBox, modeBox, policy, energy);
    root.append(grid);
    let hydrated = "",
      tariff = false;
    return () => {
      const data = payload();
      if (!data) return;
      const gpu = selectedDevice(),
        worker = selectedWorker();
      cpuBox.hidden = worker?.hardwareType !== "CPU";
      capBox.hidden = worker?.hardwareType === "CPU";
      cpuWatts.input.min = data.cpu?.minWatts || 1;
      cpuWatts.input.max = data.cpu?.maxWatts || 10000;
      if (worker && hydrated !== device) {
        mode.input.value = worker.powerMode;
        remote.input.checked = worker.externalControlEnabled;
        boot.input.checked = worker.startOnBoot;
        watts.input.value = gpu?.currentPowerLimitWatts ?? gpu?.maxWatts ?? "";
        cpuWatts.input.value =
          worker.telemetry?.powerTargetWatts || data.cpu?.minWatts || "";
        hydrated = device;
      }
      if (!tariff && data.energy?.settings) {
        price.input.value = data.energy.settings.pricePerKwh;
        currency.input.value = data.energy.settings.currency;
        tariff = true;
      }
      watts.input.min = gpu?.driverMinWatts || 1;
      watts.input.max = gpu?.driverMaxWatts || 10000;
      const values = gpu
        ? [
            [
              "tool_driver_bounds",
              `${gpu.driverMinWatts}–${gpu.driverMaxWatts} W`,
            ],
            ["tool_user_bounds", `${gpu.minWatts}–${gpu.maxWatts} W`],
            ["tool_current_cap", metric(gpu.currentPowerLimitWatts, "W")],
            ["tool_driver_default", metric(gpu.driverDefaultWatts, "W")],
          ]
        : [["tool_choose_device", t("tool_gpu_required")]];
      status.replaceChildren(
        ...values.flatMap(([key, value]) => [
          node("dt", "", t(key)),
          node("dd", "", value),
        ]),
      );
      if (gpu?.regulationError)
        status.append(
          node("dt", "", t("diagnostic")),
          node(
            "dd",
            "",
            has(gpu.regulationError)
              ? t(gpu.regulationError)
              : gpu.regulationError,
          ),
        );
      text(
        energyStatus,
        `${t("tool_energy_today")}: ${metric(data.energy?.today?.kilowattHours, "kWh")} · ${data.energy?.settings?.currency || ""} ${number(data.energy?.today?.cost)}`,
      );
    };
  }
  function mountTuning(root) {
    const grid = node("div", "tool-grid"),
      selectionBox = box("tool_selection"),
      core = check("tool_core", true),
      memory = check("tool_memory", true),
      power = check("tool_power_dimension", true),
      goal = field("tool_goal", "select");
    goal.input.append(
      new Option(t("tool_goal_efficiency"), "EFFICIENCY"),
      new Option(t("tool_goal_profit"), "PROFIT_PER_DAY"),
    );
    const concurrency = field("tool_concurrency", "number", {
      min: "1",
      max: "64",
      step: "1",
      value: "1",
    });
    selectionBox.append(
      core.wrapper,
      memory.wrapper,
      power.wrapper,
      goal.wrapper,
      node("p", "tool-muted", t("tool_profit_hint")),
      action(
        "tool_tuning_selection",
        () =>
          request("tuning_selection", {
            device,
            selection: {
              core: core.input.checked,
              memory: memory.input.checked,
              power: power.input.checked,
              goal: goal.input.value,
            },
          }),
        () => Boolean(selectedDevice()),
      ),
      concurrency.wrapper,
      action("tool_tuning_concurrency", () =>
        request("tuning_concurrency", {
          concurrency: Number(concurrency.input.value),
        }),
      ),
    );
    const live = box("tool_progress"),
      status = node("p", "tool-status"),
      progress = node("progress", "tool-progress");
    progress.max = 1;
    const curve = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    curve.setAttribute("viewBox", "0 0 500 160");
    curve.setAttribute("role", "img");
    curve.setAttribute("aria-label", t("tool_efficiency"));
    curve.classList.add("tool-chart");
    live.append(
      status,
      progress,
      curve,
      actions(
        action(
          "tool_tuning_start",
          () => request("tuning_start", { device }),
          () =>
            Boolean(selectedDevice()) &&
            !payload()?.sessions?.some(
              (session) => session.deviceId === device && session.running,
            ),
        ),
        action(
          "tool_tuning_cancel",
          () => request("tuning_cancel", { device }),
          () =>
            Boolean(
              payload()?.sessions?.some(
                (session) => session.deviceId === device && session.running,
              ),
            ),
        ),
      ),
      action(
        "tool_tuning_clear",
        () => {
          if (confirm(t("tool_clear_confirm")))
            request("tuning_clear", { device });
        },
        () => Boolean(selectedDevice()),
      ),
    );
    grid.append(selectionBox, live);
    root.append(grid);
    const manual = box("tool_clock_manual"),
      fields = [
        "tool_core_min",
        "tool_core_max",
        "tool_memory_min",
        "tool_memory_max",
        "tool_offset",
        "tool_pstate",
      ].map((key) => field(key, "number", { step: "1" }));
    const spec = node("p", "tool-status"),
      manualGrid = node("div", "tool-grid");
    fields.forEach((value) => manualGrid.append(value.wrapper));
    const clockArgs = () => ({
      device,
      coreMin: argumentNumber(fields[0].input),
      coreMax: argumentNumber(fields[1].input),
      memoryMin: argumentNumber(fields[2].input),
      memoryMax: argumentNumber(fields[3].input),
      memoryOffset: argumentNumber(fields[4].input),
      pstate: argumentNumber(fields[5].input),
    });
    manual.append(
      spec,
      manualGrid,
      node("p", "tool-muted", t("tool_clock_hint")),
      actions(
        action(
          "tool_clock_apply",
          () => request("clock_apply", clockArgs()),
          () =>
            Boolean(
              payload()?.capabilities?.some(
                (row) =>
                  row.deviceId === device &&
                  (row.coreClockControl || row.memoryClockControl),
              ),
            ),
        ),
        action(
          "tool_clock_restore",
          () => request("clock_restore", { device }),
          () => Boolean(selectedDevice()),
        ),
      ),
    );
    const savedProfiles = node("div", "tool-results"),
      rows = node("div", "tool-results"),
      count = node("p", "tool-muted");
    root.append(
      savedProfiles,
      manual,
      node("h3", "tool-subheading", t("tool_history")),
      count,
      rows,
      exportLink("tuning"),
    );
    let hydrated = null;
    return () => {
      const data = payload();
      if (!data) return;
      if (hydrated !== device && data.selection) {
        fields.forEach((field) => {
          field.input.value = "";
        });
        core.input.checked = data.selection.core;
        memory.input.checked = data.selection.memory;
        power.input.checked = data.selection.power;
        goal.input.value = data.selection.goal;
        concurrency.input.value = data.concurrency;
        hydrated = device;
      }
      const session = data.sessions?.find((row) => row.deviceId === device),
        capability = data.capabilities?.find((row) => row.deviceId === device);
      text(
        status,
        session
          ? `${has(session.phase) ? t(session.phase) : t(session.running ? "tool_active" : "tool_idle")}\n${number(session.candidateIndex, 0)}/${number(session.candidateCount, 0)} · ${t("tool_samples")}: ${session.sampleIndex}/${session.sampleCount}\n${metric(session.currentCoreMhz, "MHz")} · ${metric(session.currentMemoryMhz, "MHz")} · ${metric(session.currentPowerWatts, "W")}`
          : t("tool_no_results"),
      );
      progress.hidden = !session?.running;
      progress.value = session?.candidateCount
        ? session.candidateIndex / session.candidateCount
        : 0;
      if (data.offsetRange) {
        fields[4].input.min = data.offsetRange.minMhz;
        fields[4].input.max = data.offsetRange.maxMhz;
        if (fields[5].input.value === "")
          fields[5].input.value = data.offsetRange.pstate;
      }
      text(
        spec,
        capability
          ? `${t("tool_core")}: ${metric(capability.coreMinMhz, "MHz")} – ${metric(capability.coreMaxMhz, "MHz")}\n${t("tool_memory")}: ${metric(capability.memoryMinMhz, "MHz")} – ${metric(capability.memoryMaxMhz, "MHz")}${data.clocks ? `\n${t("tool_current_clocks")}: ${data.clocks.coreMhz} / ${data.clocks.memoryMhz} MHz` : ""}${capability.reason ? `\n${has(capability.reason) ? t(capability.reason) : capability.reason}` : ""}`
          : t("tool_gpu_required"),
      );
      const observations = session?.running
        ? (session.observations || []).filter(
            (row) => Number.isFinite(row.score) && row.score > 0,
          )
        : [];
      curve.replaceChildren();
      if (observations.length) {
        const max = Math.max(...observations.map((row) => row.score));
        const polyline = document.createElementNS(
          curve.namespaceURI,
          "polyline",
        );
        polyline.setAttribute(
          "points",
          observations
            .map(
              (row, index) =>
                `${15 + (index * 470) / Math.max(1, observations.length - 1)},${145 - (row.score / max) * 125}`,
            )
            .join(" "),
        );
        polyline.setAttribute("fill", "none");
        polyline.setAttribute("stroke", "#176650");
        polyline.setAttribute("stroke-width", "2");
        curve.append(polyline);
      }
      curve.toggleAttribute("hidden", !observations.length);
      table(
        savedProfiles,
        [
          "algorithm",
          "tool_core",
          "tool_memory",
          "power",
          "hashrate",
          "tool_efficiency",
        ],
        (data.profiles || []).map((row) => [
          row.algorithm,
          metric(row.coreMhz, "MHz"),
          metric(row.memoryMhz, "MHz"),
          metric(row.measuredWatts, "W"),
          metric(row.hashrateHs, "H/s"),
          number(row.efficiencyHashesPerWatt),
        ]),
      );
      const measurements = data.measurements || [];
      table(
        rows,
        [
          "tool_profile_source",
          "algorithm",
          "tool_core",
          "tool_memory",
          "power",
          "hashrate",
          "status",
        ],
        measurements
          .filter((row) => row.type !== "SAMPLE")
          .map((row) => [
            t(
              row.type === "RESULT" && row.stable === true
                ? "tool_confirmed"
                : "tool_provisional",
            ),
            row.algorithm,
            metric(row.coreMhz, "MHz"),
            metric(row.memoryMhz, "MHz"),
            metric(row.measuredWatts, "W"),
            metric(row.hashrateHs, "H/s"),
            row.stable == null
              ? "—"
              : t(row.stable ? "tool_stable" : "tool_unstable"),
          ]),
      );
      text(count, t("tool_rows_limit", { count: data.measurementsTotal || 0 }));
    };
  }
  function mountUpdates(root) {
    let completed = false;
    const versions = node("p", "tool-status"),
      status = node("p", "tool-status"),
      progress = node("progress", "tool-progress"),
      instructions = node("pre", "tool-code"),
      hint = node("p", "tool-muted");
    progress.max = 100;
    root.append(
      versions,
      status,
      progress,
      actions(
        action("tool_update_check", () => request("update_check")),
        action(
          "tool_update_install",
          () => {
            if (!confirm(t("tool_update_confirm"))) return;
            try {
              sessionStorage.setItem(
                "solarminer.operations.update-target",
                payload().availableTag,
              );
            } catch (_) {}
            request("update_start");
          },
          () =>
            Boolean(
              payload()?.automatic &&
              payload()?.updateAvailable &&
              !["checking", "downloading", "verifying", "restarting"].includes(
                payload()?.phase,
              ),
            ),
          true,
        ),
      ),
      hint,
      instructions,
    );
    return () => {
      const data = payload();
      if (!data) return;
      text(
        versions,
        t("tool_update_current", {
          current: data.currentTag,
          available: data.availableTag || "—",
          channel: t(
            data.channel === "beta"
              ? "tool_channel_beta"
              : "tool_channel_stable",
          ),
        }),
      );
      let target = "";
      try {
        target = sessionStorage.getItem("solarminer.operations.update-target");
      } catch (_) {}
      const done = target && data.currentTag === target;
      if (done) completed = true;
      text(
        status,
        completed
          ? t("tool_update_complete")
          : data.error
            ? label(data.error)
            : has(`tool_update_${data.phase}`)
              ? t(`tool_update_${data.phase}`)
              : t("tool_update_ready"),
      );
      if (done || data.phase === "failed")
        try {
          sessionStorage.removeItem("solarminer.operations.update-target");
        } catch (_) {}
      progress.hidden = !["downloading", "verifying", "restarting"].includes(
        data.phase,
      );
      progress.value = data.percent || 0;
      instructions.hidden = data.deployment !== "docker";
      text(
        hint,
        t(
          data.deployment === "docker"
            ? "tool_update_docker"
            : "tool_update_native",
        ),
      );
      const overlay = ["nvidia", "amd", "intel"].includes(data.dockerGpu)
        ? ` -f ${data.dockerGpu}.yml`
        : "";
      const image = `verdox/solar-miner-pc-agent:${data.channel === "beta" ? "latest-beta" : "latest"}`;
      text(
        instructions,
        `PC_AGENT_IMAGE=${image} docker compose -f compose.yml${overlay} pull pc-agent\nPC_AGENT_IMAGE=${image} docker compose -f compose.yml${overlay} up -d pc-agent`,
      );
    };
  }
  function mountLogs(root) {
    const source = field("tool_log_source", "select"),
      follow = check("tool_log_follow", true),
      output = node("pre", "tool-log"),
      download = node("a", "comparison", t("tool_log_download"));
    output.tabIndex = 0;
    output.setAttribute("aria-label", t("tool_logs"));
    root.append(
      source.wrapper,
      follow.wrapper,
      actions(
        action("tool_log_clear", () => {
          output.textContent = "";
        }),
      ),
      download,
      node("p", "tool-muted", t("tool_log_hint")),
      output,
    );
    const restart = () => {
      logVersion++;
      clearTimeout(logTimer);
      logOffset = 0;
      logRun = "";
      logDecoder = new TextDecoder();
      output.textContent = "";
      pollLog();
    };
    source.input.addEventListener("change", restart);
    async function pollLog() {
      clearTimeout(logTimer);
      if (section !== "logs" || document.hidden || !source.input.value) return;
      const version = logVersion;
      try {
        const response = await fetch(
          `${api}/tools/logs?${new URLSearchParams({ console: source.input.value, offset: logOffset })}`,
          { cache: "no-store", signal: AbortSignal.timeout(8000) },
        );
        if (!response.ok) throw new Error("tool_load_failed");
        const chunk = await response.json();
        if (version !== logVersion || section !== "logs") return;
        if (
          chunk.nextOffset < logOffset ||
          (logRun && chunk.runId !== logRun)
        ) {
          logOffset = 0;
          logRun = chunk.runId;
          logDecoder = new TextDecoder();
          output.textContent = "";
          logTimer = setTimeout(pollLog, 0);
          return;
        }
        const bytes = Uint8Array.from(atob(chunk.data || ""), (char) =>
          char.charCodeAt(0),
        );
        const content = logDecoder.decode(bytes, { stream: true });
        if (content)
          output.textContent = (output.textContent + content).slice(-200000);
        logOffset = chunk.nextOffset;
        logRun = chunk.runId;
        if (follow.input.checked) output.scrollTop = output.scrollHeight;
        download.href = `${api}/tools/logs/download?${new URLSearchParams({ console: source.input.value })}`;
        logTimer = setTimeout(pollLog, chunk.hasMore ? 100 : 2000);
      } catch (_) {
        if (version === logVersion && section === "logs")
          logTimer = setTimeout(pollLog, 5000);
      }
    }
    return () => {
      const before = source.input.value;
      selectOptions(
        source.input,
        (payload()?.sources || []).map((row) => [row.id, row.name]),
      );
      if (before !== source.input.value) restart();
      else if (!logTimer) pollLog();
    };
  }
  const mounts = {
    wallets: mountWallets,
    measurements: mountMeasurements,
    hardware: mountHardware,
    tuning: mountTuning,
    updates: mountUpdates,
    logs: mountLogs,
  };
  async function load() {
    clearTimeout(timer);
    controller?.abort();
    if (document.hidden || !section) return;
    const version = ++readVersion,
      control = new AbortController();
    controller = control;
    const timeout = setTimeout(() => control.abort(), 8000);
    try {
      const response = await fetch(
        `${api}/tools/state/${section}?${new URLSearchParams({ device: ["hardware", "tuning"].includes(section) ? device : "" })}`,
        { cache: "no-store", signal: control.signal },
      );
      if (!response.ok) throw new Error("tool_load_failed");
      const next = await response.json();
      if (version !== readVersion) return;
      source = next;
      online = true;
      if (["tool_load_failed", "tool_update_reconnect"].includes(currentNotice))
        message("");
      const age = source.collectedAt
        ? Math.max(
            0,
            Math.floor((Date.now() - new Date(source.collectedAt)) / 1000),
          )
        : null;
      text(
        $("tool-connection"),
        source.payload
          ? t("source_age", { seconds: age })
          : t("tool_wait_for_data"),
      );
      $("tool-connection").className = `connection ${fresh() ? "live" : ""}`;
      if (source.error) message(source.error);
      else if (source.payload && !fresh()) message("console_stale");
      else if (currentNotice === "console_stale") message("");
      if (source.payload) current().render();
      $("tool-panel").setAttribute("aria-busy", String(!source.payload));
      updateButtons();
    } catch (_) {
      if (version !== readVersion || document.hidden) return;
      online = false;
      text($("tool-connection"), t("offline"));
      $("tool-connection").className = "connection offline";
      let updating = false;
      try {
        updating =
          section === "updates" &&
          Boolean(
            sessionStorage.getItem("solarminer.operations.update-target"),
          );
      } catch (_) {}
      message(updating ? "tool_update_reconnect" : "tool_load_failed");
      updateButtons();
    } finally {
      clearTimeout(timeout);
      if (version === readVersion && !document.hidden)
        timer = setTimeout(
          load,
          !source?.payload ? 1000 : online ? 2500 : 8000,
        );
    }
  }
  async function pickWorkers() {
    const version = ++pickerVersion;
    try {
      if (!ownId) {
        const response = await fetch(`${api}/workspace?size=1`, {
          cache: "no-store",
          signal: AbortSignal.timeout(8000),
        });
        const value = await response.json();
        ownId = value.agents.find((agent) => agent.own)?.agentId || "";
      }
      if (!ownId) return;
      const response = await fetch(
        `${api}/workspace?${new URLSearchParams({ agent: ownId, page: pickerPage, size: 50, q: pickerQuery })}`,
        { cache: "no-store", signal: AbortSignal.timeout(8000) },
      );
      if (!response.ok) return;
      const value = await response.json();
      if (version !== pickerVersion) return;
      pickerPage = value.page;
      if (value.agents?.some((agent) => agent.own && !agent.collectedAt))
        setTimeout(() => {
          if (!document.hidden && !$("tool-worker-picker").hidden)
            pickWorkers();
        }, 1500);
      $("picker-rows").replaceChildren(
        ...value.workers.map((worker) => {
          const root = node("label", "picker-row"),
            input = node("input"),
            name = node("span", "", `${worker.model} · ${worker.deviceId}`);
          input.type = section === "measurements" ? "checkbox" : "radio";
          input.name = "tool-device";
          input.value = worker.deviceId;
          input.checked =
            input.type === "checkbox"
              ? selectedWorkers.has(worker.deviceId)
              : device === worker.deviceId;
          input.addEventListener("change", () => {
            if (section === "measurements") {
              input.checked
                ? selectedWorkers.add(worker.deviceId)
                : selectedWorkers.delete(worker.deviceId);
              current()?.render();
              updateButtons();
            } else {
              device = worker.deviceId;
              history.replaceState(
                null,
                "",
                `${location.pathname}?device=${encodeURIComponent(device)}#${section}`,
              );
              source = null;
              load();
            }
            text(
              $("picker-count"),
              section === "measurements"
                ? t("tool_worker_selection", { count: selectedWorkers.size })
                : t("tool_device_selected", { device }),
            );
          });
          root.append(input, name);
          return root;
        }),
      );
      text(
        $("picker-page"),
        t("page", {
          page: value.page + 1,
          pages: value.pages,
          count: value.total,
        }),
      );
      $("picker-prev").disabled = value.page === 0;
      $("picker-next").disabled = value.page + 1 >= value.pages;
      text(
        $("picker-count"),
        section === "measurements"
          ? t("tool_worker_selection", { count: selectedWorkers.size })
          : t("tool_device_selected", { device }),
      );
    } catch (_) {
      /* The main view reports transport state; the picker can be retried explicitly. */
    }
  }
  function navigate() {
    const target = location.hash.slice(1);
    section = names.includes(target) ? target : "wallets";
    source = null;
    online = false;
    $("tool-panel").dataset.section = section;
    $("tool-panel").setAttribute("aria-busy", "true");
    text($("tool-connection"), t("tool_wait_for_data"));
    $("tool-connection").className = "connection";
    logVersion++;
    clearTimeout(logTimer);
    logTimer = null;
    text($("tool-heading"), t(`tool_${section}`));
    for (const link of document.querySelectorAll(".sidebar nav a"))
      link.classList.toggle("current", link.hash === `#${section}`);
    for (const view of views.values()) view.root.hidden = true;
    if (!views.has(section)) {
      const root = node("div", "tools-body");
      $("tool-content").append(root);
      views.set(section, { root, render: mounts[section](root) });
    }
    current().root.hidden = false;
    $("picker-batch").hidden = section !== "measurements";
    $("tool-worker-picker").hidden = ![
      "measurements",
      "hardware",
      "tuning",
    ].includes(section);
    if (!$("tool-worker-picker").hidden) pickWorkers();
    updateButtons();
    load();
  }
  $("picker-select-page").addEventListener("click", () => {
    if (section !== "measurements") return;
    for (const input of document.querySelectorAll(".picker-row input")) {
      input.checked = true;
      selectedWorkers.add(input.value);
    }
    text(
      $("picker-count"),
      t("tool_worker_selection", { count: selectedWorkers.size }),
    );
    current()?.render();
    updateButtons();
  });
  $("picker-clear").addEventListener("click", () => {
    selectedWorkers.clear();
    for (const input of document.querySelectorAll(".picker-row input"))
      input.checked = false;
    text($("picker-count"), t("tool_worker_selection", { count: 0 }));
    if (section === "measurements") current()?.render();
    updateButtons();
  });
  $("picker-search-form").addEventListener("submit", (event) => {
    event.preventDefault();
    pickerQuery = $("picker-search").value;
    pickerPage = 0;
    pickWorkers();
  });
  $("picker-prev").addEventListener("click", () => {
    pickerPage--;
    pickWorkers();
  });
  $("picker-next").addEventListener("click", () => {
    pickerPage++;
    pickWorkers();
  });
  $("tool-refresh").addEventListener("click", () => {
    load();
    if (!$("tool-worker-picker").hidden) pickWorkers();
  });
  window.addEventListener("hashchange", navigate);
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      logVersion++;
      logTimer = null;
      readVersion++;
      controller?.abort();
      clearTimeout(timer);
      clearTimeout(taskTimer);
      clearTimeout(logTimer);
    } else {
      load();
      scheduleTasks();
      if (section === "logs") {
        logTimer = null;
        current().render();
      }
    }
  });
  setInterval(updateButtons, 1000);
  try {
    const stored = JSON.parse(
      sessionStorage.getItem("solarminer.operations.tool-tasks") || "[]",
    );
    if (Array.isArray(stored))
      for (const task of stored.slice(-16))
        if (/^[a-zA-Z0-9-]{16,80}$/.test(task.id))
          tasks.set(task.id, { ...task, state: "queued" });
  } catch (_) {}
  fetch(`${api}/configuration/software`, {
    cache: "no-store",
    signal: AbortSignal.timeout(8000),
  })
    .then((response) => (response.ok ? response.json() : []))
    .then((value) => {
      catalog = value;
      if (section === "measurements" && source?.payload) current().render();
    })
    .catch(() => {});
  navigate();
  renderTasks();
  scheduleTasks();
})();
