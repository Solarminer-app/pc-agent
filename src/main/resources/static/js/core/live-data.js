// Shared REST/SSE data loader. REST is the default for snapshots and configuration; SSE is
// reserved for data that genuinely changes while the operator watches it. Every channel carries
// its own age so a page can never keep showing dead numbers.
// Loading plan P7: every accepted value is mirrored into sessionStorage, so switching pages
// rehydrates instantly from the last snapshot instead of waiting for a cold REST/SSE round-trip.
// The snapshot is delivered through the normal age rule: an old value renders immediately but
// arrives flagged meta.stale, and the watchdog/stream replace it as soon as the agent answers.
// See docs/agent-wiki/frontend-i18n.md for the text rule and docs/agent-wiki/pc-agent.md for the contract.
(() => {
  const channels = new Map();
  let stream = null, backoffMs = 1000, watchdog = null, pendingPaint = null;

  const listeners = channel => [...channel.subscribers];
  const now = () => Date.now();

  // P7 snapshot store: one sessionStorage entry per channel, self-pruning by age. Volatile
  // run-state channels (benchmarks, efficiency) opt out via {persist:false} — a stale
  // "running" card is worse than a blank half-second.
  const SNAPSHOT_PREFIX = 'solarminer.channel.v1.';
  const SNAPSHOT_MAX_AGE_MS = 30 * 60 * 1000, SNAPSHOT_MAX_BYTES = 128 * 1024;
  function readSnapshot(name) {
    const key = SNAPSHOT_PREFIX + name;
    try {
      const saved = JSON.parse(sessionStorage.getItem(key) || 'null');
      const age = now() - (saved?.receivedAt || 0);
      if (!saved || age < 0 || age > SNAPSHOT_MAX_AGE_MS) { sessionStorage.removeItem(key); return null; }
      return saved;
    } catch (_) { try { sessionStorage.removeItem(key); } catch (__) {} return null; }
  }
  function writeSnapshot(name, value, receivedAt) {
    try {
      const raw = JSON.stringify({receivedAt, value});
      if (raw.length > SNAPSHOT_MAX_BYTES) return;
      sessionStorage.setItem(SNAPSHOT_PREFIX + name, raw);
    } catch (_) { /* quota or privacy mode: caching is an optimization, never a requirement */ }
  }

  class Channel {
    constructor(name, {endpoint, maxAgeMs = 6000, watchMs = 2000, transform = value => value, persist = true,
                       transport = 'sse', pollMs = 0, initial = true}) {
      this.name = name;
      this.endpoint = endpoint;
      this.maxAgeMs = maxAgeMs;
      this.watchMs = watchMs;
      this.transform = transform;
      this.persist = persist;
      this.transport = transport;
      this.pollMs = pollMs;
      this.initial = initial;
      this.subscribers = new Set();
      this.value = null;
      this.receivedAt = 0;
      this.source = null;
      this.loading = null;
      this.failed = null;
      this.initialRequested = false;
      // P7: rehydrate from the last snapshot of a previous page in this tab BEFORE any
      // subscribe() runs, so the first handler call already paints real data. The value
      // enters through the normal age rule: older than maxAgeMs means meta.stale:true and
      // the watchdog replaces it within its next tick.
      if (persist) {
        const saved = readSnapshot(name);
        if (saved) { this.value = saved.value; this.receivedAt = saved.receivedAt; this.source = 'snapshot'; }
      }
    }

    /** Age of the newest accepted value; Infinity until the first one arrives. */
    age() { return this.receivedAt ? now() - this.receivedAt : Infinity; }
    fresh() { return this.receivedAt > 0 && this.age() <= this.maxAgeMs; }

    subscribe(handler) {
      this.subscribers.add(handler);
      if (this.receivedAt) handler(this.value, this.meta());
      if (this.transport === 'sse') {
        open();
        scheduleFirstPaint();
      } else if (this.initial) {
        scheduleRestFirstPaint();
      }
      return () => {
        this.subscribers.delete(handler);
        if (!this.subscribers.size) close();
      };
    }

    meta() { return {at: this.receivedAt, stale: !this.fresh(), source: this.source, error: this.failed}; }

    emit(value, source) {
      this.value = this.transform(value);
      this.receivedAt = now();
      this.source = source;
      this.failed = null;
      if (this.persist) writeSnapshot(this.name, this.value, this.receivedAt);
      for (const handler of listeners(this)) handler(this.value, this.meta());
    }

    /** A transport failure never clears the last value by itself; the age rule does that. */
    fail(error) {
      this.failed = error;
      for (const handler of listeners(this)) handler(this.value, this.meta());
    }

    async refresh() {
      if (this.loading) return this.loading;
      this.loading = (async () => {
        try {
          const response = await fetch(this.endpoint, {cache: 'no-store', signal: AbortSignal.timeout(8000)});
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          this.emit(await response.json(), 'rest');
        } catch (error) {
          this.fail(error);
        } finally {
          this.loading = null;
        }
      })();
      return this.loading;
    }
  }

  function channel(name, options) {
    const existing = channels.get(name);
    if (existing) return existing;
    const created = new Channel(name, options);
    channels.set(name, created);
    // A channel registered after the stream opened must join it. Re-opening per
    // registration used to abort and re-establish the EventSource once per channel,
    // which showed up server-side as a storm of discarded async requests. Coalesce all
    // registrations of the same tick into one re-open instead.
    if (stream && created.transport === 'sse') rescheduleOpen();
    return created;
  }

  let pendingOpen = null;
  function rescheduleOpen() {
    if (pendingOpen) return;
    // A macrotask (not a microtask): deferred page scripts register their channels in
    // separate tasks, and each task would otherwise flush its own reconnect.
    pendingOpen = setTimeout(() => {
      pendingOpen = null;
      close();
      open();
    }, 0);
  }

  function names() {
    return [...channels.values()]
      .filter(target => target.transport === 'sse' && target.subscribers.size)
      .map(target => target.name);
  }

  let pendingRestPaint = null;
  function scheduleRestFirstPaint() {
    if (pendingRestPaint) return;
    pendingRestPaint = setTimeout(() => {
      pendingRestPaint = null;
      for (const target of channels.values()) {
        // A session snapshot is useful for an instant paint, but it is not proof that the agent
        // is reachable now. Revalidate every REST channel once on page entry instead of waiting
        // for the two-second watchdog/poll interval (which kept the badge on "Connecting").
        if (target.transport !== 'rest' || !target.initial || !target.subscribers.size
            || target.initialRequested) continue;
        target.initialRequested = true;
        target.refresh();
      }
    }, 0);
  }

  // Loading plan P5: the first paint of a page reads the REST aggregate GET /local/status
  // ONCE and distributes its sections to every channel that has not received a value yet;
  // channels the aggregate does not carry (workers, power-control, benchmarks, ...) keep
  // their individual fallback. The aggregate is only fetched when at least one pending
  // channel is section-backed — pages like hardware (power-control only) never pay for it.
  // After the first aggregate read, later reconnects fall back to the per-channel gap
  // healing (the stream re-delivers every channel on connect).
  const AGGREGATE_SECTIONS = new Set(['overview', 'settings', 'telemetry', 'proxy-gate',
    'fee-tier', 'energy', 'node-assessment', 'wallets', 'coin-catalog', 'ui']);
  let aggregatePainted = false, firstPaintInFlight = false;
  function scheduleFirstPaint() {
    if (pendingPaint) return;
    // Page scripts register several channels synchronously. Defer one turn so all of them
    // share the aggregate request instead of the first channel triggering a partial paint.
    pendingPaint = setTimeout(() => {
      pendingPaint = null;
      firstPaint();
    }, 0);
  }

  async function firstPaint() {
    const pending = [...channels.values()].filter(target =>
      target.transport === 'sse' && !target.receivedAt && target.subscribers.size);
    if (!pending.length) return;
    if (aggregatePainted || !pending.some(target => AGGREGATE_SECTIONS.has(target.name))) {
      for (const target of pending) target.refresh();
      return;
    }
    aggregatePainted = true;
    firstPaintInFlight = true;
    try {
      // Tell the backend precisely which cached views this page needs. The dashboard no longer
      // waits for unrelated cold work (for example wallets or market prices) before rendering.
      const requested = pending.filter(target => AGGREGATE_SECTIONS.has(target.name))
        .map(target => target.name);
      const response = await fetch(`/api/agent/local/status?sections=${encodeURIComponent(requested.join(','))}`, {cache: 'no-store', signal: AbortSignal.timeout(8000)});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const aggregate = await response.json();
      const sections = new Map((aggregate.sections || []).map(entry => [entry.name, entry.section]));
      for (const target of pending) {
        // The stream's connect snapshot may have arrived while the aggregate was in flight:
        // a channel that already has a value needs neither the section nor a fallback.
        if (target.receivedAt) continue;
        const section = sections.get(target.name);
        if (section && !section.stale) target.emit(section.payload, 'rest');
        else target.refresh();
      }
    } catch (error) {
      for (const target of pending) if (!target.receivedAt) target.refresh();
    } finally {
      firstPaintInFlight = false;
    }
  }

  function open() {
    const requested = names();
    if (stream || document.hidden || !requested.length) return;
    const source = new EventSource(`/api/agent/local/events?channels=${encodeURIComponent(requested.join(','))}`);
    stream = source;
    backoffMs = 1000;
    for (const [name, target] of channels) source.addEventListener(name, event => {
      if (source !== stream) return;
      try { target.emit(JSON.parse(event.data), 'push'); }
      catch (_) { target.fail(new Error('malformed_event')); }
    });
    source.onopen = () => {
      // Headers from the local SSE endpoint are enough to confirm that the agent is reachable.
      // Data may still be loading, but the shell must not keep claiming "Connecting" until a
      // slow sensor, wallet, or proxy-dependent section finishes.
      window.SolarMinerUI?.setConnection('online');
      // One deferred aggregate request covers all channels registered by the page. Do not issue
      // the old per-channel burst here: firstPaint() falls back only for aggregate gaps.
      scheduleFirstPaint();
    };
    source.onerror = () => {
      if (source !== stream) return;
      close();
      setTimeout(open, backoffMs);
      backoffMs = Math.min(backoffMs * 2, 15000);
    };
  }

  function close() {
    if (!stream) return;
    stream.close();
    stream = null;
  }

  /** Watchdog: only fetches a channel when push went silent, so a healthy stream costs no requests. */
  function startWatchdog() {
    if (watchdog) return;
    watchdog = setInterval(() => {
      if (document.hidden) return;
      for (const target of channels.values()) {
        if (!target.subscribers.size) continue;
        if (target.transport === 'rest') {
          if (target.pollMs > 0 && target.age() >= target.pollMs) target.refresh();
          continue;
        }
        // proxy-gate starts the watchdog immediately. Without this guard, its first 2 s tick
        // launches a second /overview request while the bundled aggregate REST read is still
        // running. That duplicate cold read contends for the same proxy/GPU work and can turn
        // a normal cache reconciliation into an 8 s timeout and a false stale notice.
        if (firstPaintInFlight && !target.receivedAt && AGGREGATE_SECTIONS.has(target.name)) continue;
        if (stream && target.age() <= target.maxAgeMs) continue;
        target.refresh();
      }
    }, 2000);
  }

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) close();
    else {
      for (const target of channels.values()) if (target.subscribers.size) target.refresh();
      open();
    }
  });

  window.SolarMinerLive = {
    channel,
    /** P7: last persisted snapshot of a channel WITHOUT registering it (pages like wallets
     *  that keep a manual load path still seed instantly from the tab's live data). */
    snapshot(name) { return readSnapshot(name)?.value ?? null; },
    /** Force every subscribed channel to re-read immediately, e.g. right after a POST. */
    refreshAll() {
      for (const target of channels.values()) if (target.subscribers.size) target.refresh();
    },
    refresh(name) { return channels.get(name)?.refresh(); },
    get(name) { return channels.get(name)?.value ?? null; },
    age(name) { return channels.get(name)?.age() ?? Infinity; },
    fresh(name) { return Boolean(channels.get(name)?.fresh()); },
    start: startWatchdog,
    open, close
  };
})();
