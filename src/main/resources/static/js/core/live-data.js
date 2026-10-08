// One Server-Sent-Events connection per page. Push is the primary path; a REST fetch happens
// only for the first paint, after a local mutation, and as a watchdog when a channel stops
// reporting. Every channel carries its own age so a page can never keep showing dead numbers.
// See pc-agent/FRONTEND-I18N.md for the text rule and docs/agent-wiki/pc-agent.md for the contract.
(() => {
  const channels = new Map();
  let stream = null, backoffMs = 1000, watchdog = null;

  const listeners = channel => [...channel.subscribers];
  const now = () => Date.now();

  class Channel {
    constructor(name, {endpoint, maxAgeMs = 6000, watchMs = 2000, transform = value => value}) {
      this.name = name;
      this.endpoint = endpoint;
      this.maxAgeMs = maxAgeMs;
      this.watchMs = watchMs;
      this.transform = transform;
      this.subscribers = new Set();
      this.value = null;
      this.receivedAt = 0;
      this.source = null;
      this.loading = null;
      this.failed = null;
    }

    /** Age of the newest accepted value; Infinity until the first one arrives. */
    age() { return this.receivedAt ? now() - this.receivedAt : Infinity; }
    fresh() { return this.receivedAt > 0 && this.age() <= this.maxAgeMs; }

    subscribe(handler) {
      this.subscribers.add(handler);
      if (this.receivedAt) handler(this.value, this.meta());
      open();
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
    // A channel added after the stream opened must join it: reconnecting re-delivers
    // every subscribed channel, so the new one is covered without a separate request.
    if (stream) { close(); open(); }
    return created;
  }

  function names() { return [...channels.keys()]; }

  function open() {
    if (stream || document.hidden || !channels.size) return;
    const source = new EventSource(`/api/agent/local/events?channels=${encodeURIComponent(names().join(','))}`);
    stream = source;
    backoffMs = 1000;
    for (const [name, target] of channels) source.addEventListener(name, event => {
      if (source !== stream) return;
      try { target.emit(JSON.parse(event.data), 'push'); }
      catch (_) { target.fail(new Error('malformed event')); }
    });
    source.onopen = () => {
      // The stream re-delivers every channel on connect, so a gap during reconnect heals itself.
      for (const target of channels.values()) if (!target.receivedAt) target.refresh();
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
        if (stream && target.age() <= target.maxAgeMs) continue;
        target.refresh();
      }
    }, 2000);
  }

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) close();
    else { for (const target of channels.values()) if (target.subscribers.size) target.refresh(); open(); }
  });

  window.SolarMinerLive = {
    channel,
    /** Force every subscribed channel to re-read immediately, e.g. right after a POST. */
    refreshAll() {
      for (const target of channels.values()) if (target.subscribers.size) target.refresh();
    },
    refresh(name) { channels.get(name)?.refresh(); },
    get(name) { return channels.get(name)?.value ?? null; },
    age(name) { return channels.get(name)?.age() ?? Infinity; },
    fresh(name) { return Boolean(channels.get(name)?.fresh()); },
    start: startWatchdog,
    open, close
  };
})();
