// The translation dictionary of the PC-Agent frontend. Keys, not source text.
//
//   FRONTEND dictionary — every string the frontend authors, addressed by a stable key:
//     SolarMinerI18n.t('some_key', {params}) resolves catalog.frontend[key][locale].
//     Keys are English slugs derived from the English text; each entry carries BOTH locales.
//     An unknown key logs a console warning and scripts/check-i18n.cjs fails the build.
//     Interpolated values use {placeholder} tokens filled AFTER lookup.
//   AGENT dictionaries — strings produced at runtime by the Java agent (and by Error(t(…))
//   messages that flow through SolarMinerI18n.s()); they cannot carry keys, so they stay
//   text-keyed: `agentEnglishToGerman` = English source -> German,
//   `agentGermanToEnglish` = German source -> English. s() also resolves frontend keys,
//   so Error(t('key')) messages stay localizable.
//   Narrow documented prefix rules for compound agent messages live in i18n.js.
//
// NEVER author a user-facing string without a frontend key in the same change.
// Static HTML references keys via data-i18n / data-i18n-title / data-i18n-placeholder /
// data-i18n-aria-label; the German text inside the markup is only the no-JS fallback.
window.SolarMinerI18nCatalog = {
    frontend: {
        "agent_software": {de: "AGENT-SOFTWARE", en: "AGENT SOFTWARE"},
        "agent_update": {de: "PC-Agent aktualisieren", en: "Update PC Agent"},
        "update_progress": {de: "Update-Fortschritt", en: "Update progress"},
        "docker_update_instructions": {de: "Führe diese Befehle auf dem Docker-Host im Ordner mit compose.yml aus:", en: "Run these commands on the Docker host in the folder containing compose.yml:"},
        "docker_update_custom_note": {de: "Bei eigenen Compose-Dateien passe die Dateinamen an. Falls Docker nur mit Administratorrechten erreichbar ist, verwende sudo.", en: "Adjust the file names for custom Compose setups. Use sudo if Docker requires administrator access."},
        "update_current_version": {de: "Installiert: {version} ({channel})", en: "Installed: {version} ({channel})"},
        "stable_channel": {de: "Stable", en: "Stable"},
        "beta_channel": {de: "Beta", en: "Beta"},
        "update_available": {de: "Update {version} verfügbar.", en: "Update {version} is available."},
        "update_current": {de: "Der PC-Agent ist aktuell.", en: "PC Agent is up to date."},
        "update_version_unknown": {de: "Die installierte Version ist unbekannt; prüfe den Startweg.", en: "The installed version is unknown; check how it was started."},
        "update_checking": {de: "Suche nach Updates …", en: "Checking for updates …"},
        "update_downloading": {de: "Lade Update herunter …", en: "Downloading update …"},
        "update_verifying": {de: "Prüfe Update …", en: "Verifying update …"},
        "update_restarting": {de: "PC-Agent startet neu …", en: "PC Agent is restarting …"},
        "update_reconnecting": {de: "Warte auf den neu gestarteten PC-Agent …", en: "Waiting for the restarted PC Agent …"},
        "update_complete": {de: "Update installiert und PC-Agent neu gestartet.", en: "Update installed and PC Agent restarted."},
        "update_check_failed": {de: "Update-Prüfung fehlgeschlagen. Bitte später erneut versuchen.", en: "Update check failed. Please try again later."},
        "update_download_failed": {de: "Update-Download oder Prüfung fehlgeschlagen. Die bisherige Version läuft weiter.", en: "Update download or verification failed. The previous version keeps running."},
        "update_start_failed": {de: "Die neue Version konnte nicht gestartet werden. Die vorherige Version wurde wiederhergestellt.", en: "The new version could not start. The previous version was restored."},
        "update_request_failed": {de: "Update konnte nicht gestartet werden.", en: "Could not start the update."},
        "show_docker_update_steps": {de: "Docker-Anleitung anzeigen", en: "Show Docker instructions"},
        "install_update_and_restart": {de: "Update installieren und neu starten", en: "Install update and restart"},
        "open_update_locally": {de: "Lokal aktualisieren", en: "Update locally"},
        "open_update_locally_detail": {de: "Öffne dieses Dashboard auf dem PC-Agent-Host unter http://127.0.0.1:8084/ und starte das Update dort. Ein automatisch gestarteter Launcher ist erforderlich.", en: "Open this dashboard on the PC Agent host at http://127.0.0.1:8084/ and start the update there. A managed launcher is required."},
        "open_worker_agent_dashboard": {de: "Dashboard von {agent} für {worker} öffnen", en: "Open {agent} dashboard for {worker}"},
        "power_steps_count": {de: "{count} Power-Schritte anzeigen", en: "Show {count} power steps"},
        "driver_default_power_limit": {de: "Treiber-Standard: {watts} W", en: "Driver default: {watts} W"},
        "driver_default_unavailable": {de: "Treiber-Standard unbekannt", en: "Driver default unavailable"},
        "power_consumption": {de: "Stromverbrauch", en: "Power consumption"},
        "period": {de: "Zeitraum", en: "Period"},
        "coin": {de: "Coin", en: "Coin"},
        "estimated_electricity_cost": {de: "Geschätzte Stromkosten", en: "Estimated electricity cost"},
        "power_cost_note": {de: "Kosten sind Schätzungen aus gemessener Komponentenenergie und dem aktuell eingestellten Tarif jedes Agenten. Historische Tarifänderungen und der übrige PC-Verbrauch sind nicht enthalten.", en: "Costs are estimates based on measured component energy and each agent's current rate. Past rate changes and the rest of the PC's consumption are excluded."},
        "local_device_history": {de: "LOKALE GERÄTE", en: "LOCAL DEVICES"},
        "gpu_efficiency_history": {de: "GPU-Effizienz im Zeitraum", en: "GPU efficiency for this period"},
        "local_device_history_note": {de: "Nur Geräte dieses PC-Agenten. Werte je Algorithmus sind vergleichbar; andere Agenten geben keine Gerätehistorie frei.", en: "Only devices on this PC agent. Compare values within the same algorithm; other agents do not share device history."},
        "average_measured_power": {de: "Ø gemessene Leistung", en: "Average measured power"},
        "hashes_per_watt": {de: "Hashes pro Watt", en: "Hashes per watt"},
        "no_local_gpu_history": {de: "Keine lokalen GPU-Messungen im Zeitraum.", en: "No local GPU measurements for this period."},
        "kwh_per_hour": {de: "kWh pro Stunde", en: "kWh per hour"},
        "kwh_per_day": {de: "kWh pro Tag", en: "kWh per day"},
        "hashrate_axis": {de: "Hashrate (H/s)", en: "Hashrate (H/s)"},
        "energy_per_day": {de: "Energie je Tag", en: "Energy per day"},
        "mixed_algorithms_no_hashrate": {de: "Mehrere Algorithmen im Zeitraum: Hashraten sind nicht addierbar. Der Verlauf zeigt nur gemessene Energie.", en: "Multiple algorithms in this period: their hashrates cannot be combined. The chart shows measured energy only."},
        "hashrate_algorithm_unavailable": {de: "Die Algorithmusangabe fehlt für einen Teil der Daten. Der Verlauf zeigt nur gemessene Energie.", en: "Algorithm information is missing for some data. The chart shows measured energy only."},
        "incomplete_measurement": {de: "unvollständig", en: "incomplete"},
        "power_history_intro": {de: "Mining-Leistung und gemessenen Stromverbrauch für diesen Agenten und freigegebene PC-Agenten im Netzwerk.", en: "Mining performance and measured power consumption for this agent and PC agents that share data on the network."},
        "date_from": {de: "Von", en: "From"}, "date_to": {de: "Bis", en: "To"},
        "show_period": {de: "Zeitraum anzeigen", en: "Show period"}, "network_history": {de: "NETZWERKVERLAUF", en: "NETWORK HISTORY"},
        "mining_and_power": {de: "Mining und Stromverbrauch", en: "Mining and power consumption"},
        "average_hashrate": {de: "Durchschnittliche Hashrate", en: "Average hashrate"}, "energy_per_hour": {de: "Energie je Stunde", en: "Energy per hour"},
        "daily_summary": {de: "TAGESÜBERSICHT", en: "DAILY SUMMARY"}, "daily_statistics": {de: "Tagesstatistiken", en: "Daily statistics"},
        "date": {de: "Datum", en: "Date"}, "mining_time": {de: "Mining-Zeit", en: "Mining time"}, "measured_energy": {de: "Gemessene Energie", en: "Measured energy"},
        "no_power_history": {de: "Für diesen Zeitraum liegen keine Messdaten vor.", en: "No measurements are available for this period."},
        "power_history_measurement_note": {de: "Es werden nur echte Sensorwerte eingerechnet. Nicht erreichbare Agenten und nicht freigegebene Netzwerke liefern keine Daten; fehlende Messwerte werden nicht geschätzt.", en: "Only real sensor readings are included. Unreachable agents and agents that have not enabled sharing provide no data; missing readings are not estimated."},
        "legacy_energy_allocation_note": {de: "Ältere Sitzungen enthalten nur Gesamtsummen; ihre Energie wird für den Zeitverlauf gleichmäßig über die Laufzeit verteilt. Neue Messungen werden stündlich aus den einzelnen Sensorintervallen summiert.", en: "Older sessions contain totals only, so their energy is spread evenly over the runtime for the timeline. New readings are summed into hourly buckets from individual sensor intervals."},
        "mining_power_chart": {de: "Mining- und Stromverbrauchsverlauf", en: "Mining and power consumption history"},
        "hashrate_scale": {de: "Hashrate", en: "Hashrate"}, "energy_scale": {de: "Energie", en: "Energy"},
        "loading_power_history": {de: "Verlauf wird geladen …", en: "Loading history …"},
        "invalid_date_range": {de: "Bitte wähle einen gültigen Zeitraum von höchstens einem Jahr.", en: "Choose a valid date range of no more than one year."},
        "power_history_unavailable": {de: "Der Verlauf ist derzeit nicht verfügbar.", en: "History is currently unavailable."},
        "worker_power_mode": {de: "Betriebsmodus", en: "Operating mode"},
        "worker_mode_manual": {de: "Manuell · gespeichertes Power-Limit", en: "Manual · saved power limit"},
        "worker_mode_efficiency": {de: "Effizienz · Hashes pro Watt", en: "Efficiency · hashes per watt"},
        "worker_mode_max_hashrate": {de: "Maximale Hashrate · temperaturbegrenzt", en: "Maximum hashrate · temperature limited"},
        "worker_mode_revenue": {de: "Ertrag pro kWh · fester Coin", en: "Revenue per kWh · fixed coin"},
        "worker_mode_coin_revenue": {de: "Coin & Ertrag pro kWh", en: "Coin & revenue per kWh"},
        "worker_mode_help": {de: "Automatische Modi benötigen lokale Messpunkte. Effizienz wählt Hashes pro Watt; maximale Hashrate den schnellsten stabilen GPU-Sweep-Punkt. Bei Hitze oder fehlendem Temperaturwert stoppt dieser Modus. Ertrag pro kWh wählt den besten Messpunkt für den festen Coin. Coin & Ertrag pro kWh darf auch einen anderen Coin wählen. Die Node steuert dann nur Ein/Aus.", en: "Automatic modes need local measurements. Efficiency chooses hashes per watt; maximum hashrate chooses the fastest stable GPU sweep point. This mode stops on overheating or missing temperature readings. Revenue per kWh chooses the best measured point for the fixed coin. Coin & revenue per kWh may choose a different coin. The Node then controls only on/off."},
        "power_mode_invalid": {de: "Unbekannter Betriebsmodus.", en: "Unknown operating mode."},
        "power_mode_requires_gpu": {de: "Automatische Betriebsmodi sind derzeit nur für GPUs verfügbar.", en: "Automatic operating modes are currently available only for GPUs."},
        "power_mode_requires_gpu_coin": {de: "Weise der GPU zuerst einen Coin zu.", en: "Assign a coin to the GPU first."},
        "power_mode_no_measured_point": {de: "Kein stabiler Messpunkt innerhalb der GPU-Grenzen gefunden. Führe einen Power-Sweep oder Benchmark aus.", en: "No stable measurement within the GPU limits. Run a power sweep or benchmark."},
        "power_mode_no_safe_hashrate_point": {de: "Kein stabiler GPU-Sweep-Punkt unter der Temperaturgrenze gefunden. Führe einen Power-Sweep aus.", en: "No stable GPU sweep point below the temperature limit. Run a power sweep."},
        "power_mode_temperature_unavailable_or_high": {de: "GPU-Temperatur ist nicht lesbar oder für maximale Hashrate noch zu hoch.", en: "GPU temperature is unavailable or still too high for maximum hashrate."},
        "power_mode_apply_failed": {de: "Das gewählte GPU-Power-Limit konnte nicht sicher angewendet werden.", en: "The selected GPU power limit could not be applied safely."},
        "power_mode_save_failed": {de: "Der Betriebsmodus konnte nicht gespeichert werden.", en: "The operating mode could not be saved."},
        "power_mode_unavailable": {de: "Der Betriebsmodus ist vorübergehend nicht verfügbar.", en: "The operating mode is temporarily unavailable."},
        "power_mode_busy": {de: "Während eines Messlaufs oder eines anderen Steuerbefehls kann der Betriebsmodus nicht geändert werden.", en: "The operating mode cannot change during a measurement or another control action."},
        "power_mode_locally_managed": {de: "Das Power-Limit wird lokal vom PC-Agenten gesteuert. Die Node kann nur starten oder pausieren.", en: "The PC Agent manages the power limit locally. The Node can only start or pause."},
        "benchmark_jump_comparison": {de: "Ertrag vergleichen", en: "Compare revenue"},
        "benchmark_comparison_intro": {de: "Gemessene GPU-, Coin- und Miner-Kombinationen mit aktuellen Netzwerkkursen vergleichen.", en: "Compare measured GPU, coin and miner combinations using current network quotes."},
        "benchmark_select_coin": {de: "Wähle mindestens einen installierten Coin für den Messlauf aus.", en: "Select at least one installed coin for the measurement run."},
        "benchmark_select_worker": {de: "Wähle mindestens einen Worker für den Messlauf aus.", en: "Select at least one worker for the measurement run."},
        "benchmark_select_workers": {de: "Worker für Messläufe auswählen", en: "Select workers for measurement runs"},
        "benchmark_select_gpu_for_sweep": {de: "Wähle mindestens eine GPU für den Power-Limit-Test aus.", en: "Select at least one GPU for the power-limit test."},
        "benchmark_worker_unavailable": {de: "Ein ausgewählter Worker ist nicht mehr verfügbar. Lade die Worker-Liste neu.", en: "A selected worker is no longer available. Reload the worker list."},
        "benchmark_worker_list_unavailable": {de: "Die Worker-Liste konnte nicht geladen werden. Lade die Seite neu.", en: "Could not load the worker list. Reload the page."},
        "benchmark_no_selected_targets": {de: "Für die ausgewählten GPUs und Coins ist kein Power-Limit-Test möglich. Prüfe Miner, Zuordnung und schreibbare Power-Limits.", en: "No power-limit test is available for the selected GPUs and coins. Check miners, assignments, and writable power limits."},
        "benchmark_worker_selection_intro": {de: "Wähle die Worker für den Messlauf aus. Nur diese Geräte werden gemessen; für den Power-Limit-Test muss mindestens eine GPU ausgewählt sein.", en: "Select the workers for the measurement run. Only these devices are measured; the power-limit test requires at least one GPU."},
        "benchmark_gpu_concurrency_label": {de: "Gleichzeitig messende GPUs", en: "GPUs measuring at the same time"},
        "benchmark_gpu_concurrency_help": {de: "Begrenzt gleichzeitig laufende GPU-Miner bei installierten Benchmarks und Power Sweeps. Niedrige Werte verringern die gemeinsame Abwärme; laufende Miner messen bleibt eine reine Beobachtung.", en: "Limits GPU miners running at once during installed benchmarks and power sweeps. Lower values reduce shared heat; measuring running miners only observes them."},
        "benchmark_invalid_gpu_concurrency": {de: "Wähle eine ganze Zahl von 1 bis 64 für gleichzeitig messende GPUs.", en: "Choose a whole number from 1 to 64 for GPUs measuring at the same time."},
        "benchmark_sweep_stop_selected_failed": {de: "Ausgewählte GPU-Miner konnten vor dem Sweep nicht angehalten werden.", en: "Selected GPU miners could not be stopped before the sweep."},
        "benchmark_choose_workers_above": {de: "Worker für diesen Test auswählen", en: "Select workers for this test"},
        "benchmark_comparison_gpu": {de: "GPU auswählen", en: "Choose GPU"},
        "benchmark_comparison_load_failed": {de: "Der Netzwerkvergleich konnte nicht geladen werden.", en: "Could not load the network comparison."},
        "benchmark_comparison_no_device": {de: "Noch keine geeignete GPU-Messung vorhanden. Starte einen Benchmark oder Power Sweep.", en: "No eligible GPU measurement yet. Run a benchmark or power sweep."},
        "benchmark_filter_label": {de: "Messungen filtern", en: "Filter measurements"},
        "benchmark_filter_placeholder": {de: "Worker, Coin oder Algorithmus", en: "Worker, coin or algorithm"},
        "measurement_overview": {de: "Messübersicht", en: "Measurement overview"},
        "coin_hopping_kicker": {de: "AUTOMATISCHE COIN-WAHL", en: "AUTOMATIC COIN SELECTION"},
        "coin_hopping_title": {de: "Coin Hopping", en: "Coin hopping"},
        "coin_hopping_intro": {de: "Vergleiche gemessene Betriebspunkte derselben GPU am gleichen Power Target mit aktuellen Netzwerkdaten.", en: "Compare measured operating points for the same GPU and power target with current network data."},
        "coin_hopping_teaser": {de: "Vergleiche gemessene Betriebspunkte und verfolge automatische Coin-Wechsel auf der eigenen Coin-Hopping-Seite.", en: "Compare measured operating points and follow automatic coin switches on the dedicated coin hopping page."},
        "open_coin_hopping": {de: "Coin Hopping öffnen", en: "Open coin hopping"},
        "coin_hopping_page_intro": {de: "Der PC-Agent wählt Coin und Power Target gemeinsam aus gemessenen Betriebspunkten und aktuellen Netzwerkkursen. Ziel ist maximaler prognostizierter Bruttoertrag pro kWh. Die Beträge sind keine Pool-Auszahlungen.", en: "The PC-Agent chooses the coin and power target together from measured operating points and current network quotes. The goal is maximum forecast gross revenue per kWh. Amounts are not pool payouts."},
        "coin_hopping_auto_target_explanation": {de: "Der PC-Agent wählt Coin und Power Target gemeinsam aus gemessenen Betriebspunkten. Er maximiert den prognostizierten Bruttoertrag pro kWh innerhalb der geprüften GPU-Grenzen.", en: "The PC-Agent chooses the coin and power target together from measured operating points. It maximizes forecast gross revenue per kWh within verified GPU limits."},
        "coin_hopping_recommended_point": {de: "Beste Kombination aktuell: {coin} · {miner} bei {watts} W", en: "Best combination now: {coin} · {miner} at {watts} W"},
        "coin_hopping_no_recommendation": {de: "Noch keine sichere Kombination mit aktuellen Netzwerkkursen verfügbar.", en: "No safe combination with current network quotes is available yet."},
        "coin_hopping_settings": {de: "EINSTELLUNGEN", en: "SETTINGS"},
        "coin_hopping_strategy": {de: "Deine Strategie", en: "Your strategy"},
        "coin_hopping_cooldown": {de: "Sperrfrist zwischen Wechseln (Minuten)", en: "Cooldown between switches (minutes)"},
        "coin_hopping_threshold": {de: "Mindestvorteil für einen Wechsel (%)", en: "Minimum gain for a switch (%)"},
        "coin_hopping_conditions": {de: "Wechsel nur bei aktivem Worker, passendem GPU-Limit, gültigen Messungen für beide Coins und bereiten Miner- und Proxy-Routen. Ein gestoppter Worker bleibt gestoppt.", en: "Switches require an active worker, matching GPU limit, valid measurements for both coins and ready miner and proxy routes. A stopped worker remains stopped."},
        "coin_hopping_market": {de: "NETZWERKVERGLEICH", en: "NETWORK COMPARISON"},
        "coin_hopping_comparison_heading": {de: "Ertrag pro kWh nach Coin und Miner", en: "Revenue per kWh by coin and miner"},
        "coin_hopping_modelled_gain": {de: "MODELLIERTER MEHRERTRAG", en: "MODELLED GAIN"},
        "coin_hopping_actual_vs_fixed": {de: "Hopping gegen den besten festen Coin", en: "Hopping versus the best fixed coin"},
        "coin_hopping_actual_usd": {de: "Mit Hopping", en: "With hopping"},
        "coin_hopping_fixed_usd": {de: "Bester fester Coin", en: "Best fixed coin"},
        "coin_hopping_gain_usd": {de: "Differenz", en: "Difference"},
        "coin_hopping_covered_time": {de: "Ausgewertete Mining-Zeit", en: "Accounted mining time"},
        "coin_hopping_estimated_coins": {de: "Geschätzte Coins nach Coin", en: "Estimated coins by coin"},
        "coin_hopping_accounting_note": {de: "Die Berechnung zählt nur Zeitabschnitte mit aktivem Worker, passendem Power Target und vergleichbaren aktuellen Marktdaten. Der feste Coin wird für dieselbe geschätzte Energiemenge verglichen; Grundlage sind Prognosen zum jeweiligen Prüfzeitpunkt. Pool-Auszahlungen, Gebühren, abgelehnte Shares und Stromkosten sind nicht enthalten. Eine negative Differenz ist möglich.", en: "The calculation includes only periods with an active worker, matching power target and comparable current market data. The fixed coin is compared over the same estimated energy use, using forecasts at each evaluation. Pool payouts, fees, rejected shares and electricity cost are excluded. The difference can be negative."},
        "coin_hopping_actions": {de: "AKTIONEN", en: "ACTIONS"},
        "coin_hopping_history": {de: "Wechselverlauf", en: "Switch history"},
        "coin_hopping_point_detail": {de: "{source} · {rate} bei {power} W · {time}", en: "{source} · {rate} at {power} W · {time}"},
        "coin_hopping_source_sweep": {de: "Sweep", en: "Sweep"},
        "coin_hopping_source_benchmark": {de: "Benchmark", en: "Benchmark"},
        "coin_hopping_source_live": {de: "Laufender Betrieb", en: "Live operation"},
        "coin_hopping_duration": {de: "{hours} h {minutes} min", en: "{hours} h {minutes} min"},
        "coin_hopping_no_comparison_yet": {de: "Noch kein durchgehend vergleichbarer fester Coin. Die Auswertung beginnt mit dem nächsten gültigen Zeitabschnitt.", en: "No fixed coin was comparable throughout the accounted period yet. Evaluation begins with the next valid interval."},
        "coin_hopping_no_coins_yet": {de: "Noch keine geschätzten Coins erfasst.", en: "No estimated coins recorded yet."},
        "coin_hopping_fixed_coins": {de: "Fester Vergleich: {amount} {coin}", en: "Fixed comparison: {amount} {coin}"},
        "coin_hopping_action_switch": {de: "{from} → {to} gewechselt", en: "Switched {from} → {to}"},
        "coin_hopping_action_power": {de: "{coin} auf {watts} W eingestellt", en: "Set {coin} to {watts} W"},
        "coin_hopping_action_failed": {de: "Wechsel {from} → {to} fehlgeschlagen", en: "Switch {from} → {to} failed"},
        "coin_hopping_action_gain": {de: "Prognose: +{gain} % bei {watts} W", en: "Forecast: +{gain}% at {watts} W"},
        "coin_hopping_no_actions_yet": {de: "Noch keine automatischen Wechsel. Entscheidungen erscheinen hier nach dem ersten Wechselversuch.", en: "No automatic switches yet. Decisions appear here after the first switch attempt."},
        "coin_hopping_gpu": {de: "GPU", en: "GPU"},
        "coin_hopping_target": {de: "Power Target", en: "Power target"},
        "coin_hopping_enable": {de: "Automatische Coin-Wahl aktivieren", en: "Enable automatic coin selection"},
        "coin_hopping_comparison": {de: "Vergleich des geschätzten Bruttoertrags pro Kilowattstunde", en: "Estimated gross revenue per kilowatt-hour comparison"},
        "coin_hopping_active": {de: "AKTIV", en: "ACTIVE"},
        "coin_hopping_off": {de: "AUS", en: "OFF"},
        "coin_hopping_no_candidates": {de: "Für diese GPU liegen noch keine nutzbaren Messpunkte mit aktuellen Marktdaten vor.", en: "No usable measurements with current market data are available for this GPU yet."},
        "benchmark_library_heading": {de: "Messdaten & Profile", en: "Measurements & profiles"},
        "benchmark_algorithm_entries": {de: "{count} Messungen", en: "{count} measurements"},
        "benchmark_algorithm_entry_one": {de: "1 Messung", en: "1 measurement"},
        "benchmark_unknown_algorithm": {de: "Unbekannter Algorithmus", en: "Unknown algorithm"},
        "benchmark_jump_measurements": {de: "Messdaten ansehen", en: "View measurements"},
        "benchmark_jump_run": {de: "Benchmark starten", en: "Start benchmark"},
        "benchmark_jump_sweep": {de: "Power Sweep", en: "Power sweep"},
        "benchmark_library_intro": {de: "Alle lokalen Messungen an einem Ort – auch ohne aktiven Benchmark.", en: "All local measurements in one place, even without an active benchmark."},
        "benchmark_profile_target": {de: "Power Target: {target}", en: "Power target: {target}"},
        "benchmark_target_not_recorded": {de: "nicht erfasst", en: "not recorded"},
        "benchmark_no_saved_points": {de: "Noch keine lokalen Messpunkte gespeichert. Ein Profil entsteht nach zwölf gültigen Messwerten je Worker; hochgeladene Einzelwerte sind davon unabhängig.", en: "No local measurements saved yet. A profile is created after twelve valid readings per worker; uploaded individual samples are separate."},
        "benchmark_current_run": {de: "Aktueller Messlauf", en: "Current run"},
        "benchmark_data_sharing_status": {de: "Datenfreigabe", en: "Data sharing"},
        "benchmark_sweep_waiting_points": {de: "Der Test läuft. Sobald der erste stabile Power-Schritt abgeschlossen ist, beginnt die Kurve zu wachsen.", en: "The test is running. The curve starts growing when the first stable power step completes."},
        "benchmark_sweep_no_live_curve": {de: "Starte einen Power-Limit-Test, um die Effizienzkurve hier live aufzubauen.", en: "Start a power limit test to build the efficiency curve here live."},
        "benchmark_sweep_curve_aria": {de: "Live-Effizienzkurve des Power-Limit-Tests", en: "Live power limit efficiency curve"},
        "benchmark_sweep_short_intro": {de: "Der Agent misst stabile Hashrate und Leistungsaufnahme bei mehreren GPU-Limits. Die Kurve wächst mit jedem abgeschlossenen Schritt.", en: "The agent measures stable hashrate and power at several GPU limits. The curve grows with each completed step."},
        "benchmark_sweep_how_it_works": {de: "So funktioniert der Test", en: "How the test works"},
        "benchmark_hashes_per_watt": {de: "Hashrate pro Watt", en: "Hashrate per watt"},
        "power_target_watts": {de: "Power Target (W)", en: "Power target (W)"},
        "coin_hopping_measured_detail": {de: "Gemessen: {rate} bei {power} W", en: "Measured: {rate} at {power} W"},
        "coin_hopping_gross_note": {de: "Brutto-Prognose vor Poolgebühren, Minergebühren und Stromkosten. Sweep- und Betriebsmessungen mit frischen Netzwerkkursen werden gleich behandelt.", en: "Gross forecast before pool fees, miner fees and electricity cost. Sweep and live operating measurements with fresh network quotes are treated equally."},
        "coin_hopping_saved": {de: "Coin-Hopping-Einstellung gespeichert.", en: "Coin hopping setting saved."},
        "coin_hopping_load_failed": {de: "Coin-Hopping-Daten konnten nicht geladen werden.", en: "Could not load coin hopping data."},
        "coin_hopping_invalid_settings": {de: "Ungültige Coin-Hopping-Einstellung.", en: "Invalid coin hopping setting."},
        "coin_hopping_gpu_missing": {de: "Die ausgewählte GPU ist nicht verfügbar.", en: "The selected GPU is unavailable."},
        "coin_hopping_target_unavailable": {de: "Dieses Power Target kann nicht sicher gesetzt werden.", en: "This power target cannot be applied safely."},
        "coin_hopping_target_unmeasured": {de: "Für dieses Power Target fehlt eine gültige Messung.", en: "This power target has no valid measurement."},
        "coin_hopping_save_failed": {de: "Coin-Hopping-Einstellung konnte nicht gespeichert werden.", en: "Could not save the coin hopping setting."},
        "coin_hopping_measurement_or_plan_active": {de: "Messlauf oder Node-Plan aktiv; Coin-Wechsel ausgesetzt.", en: "Measurement or Node plan active; coin switch deferred."},
        "economic_plan_invalid_worker_set": {de: "Der Node-Plan enthält eine ungültige Worker-Auswahl.", en: "The Node plan has an invalid worker selection."},
        "economic_plan_worker_not_allowed": {de: "Ein Worker ist nicht für die automatische Auswahl freigegeben.", en: "A worker is not enabled for automatic selection."},
        "economic_plan_worker_action_failed": {de: "Die Worker-Auswahl konnte nicht sicher angewendet werden.", en: "The worker selection could not be applied safely."},
        "economic_plan_unmeasured_worker": {de: "Für einen ausgewählten Worker fehlen aktuelle Leistungs- oder Marktdaten.", en: "A selected worker is missing current performance or market data."},
        "coin_hopping_insufficient_candidates": {de: "Weniger als zwei vergleichbare Coins verfügbar.", en: "Fewer than two comparable coins are available."},
        "coin_hopping_current_unmeasured": {de: "Für den aktuell gewählten Coin fehlt ein stabiler Messpunkt.", en: "The current coin has no stable measurement."},
        "coin_hopping_best_already_selected": {de: "Der aktuelle Coin ist optimal oder der Vorteil liegt unter dem eingestellten Mindestwert.", en: "The current coin is best or the gain is below the configured threshold."},
        "coin_hopping_cooldown_active": {de: "Die Sperrfrist zwischen Coin-Wechseln läuft noch.", en: "The cooldown between coin switches is still active."},
        "coin_hopping_target_mismatch": {de: "Das aktuelle GPU-Limit entspricht nicht dem gewählten Power Target.", en: "The current GPU limit does not match the selected power target."},
        "coin_hopping_ledger_save_failed": {de: "Der Coin-Hopping-Verlauf konnte nicht gespeichert werden; automatische Wechsel sind ausgesetzt.", en: "Could not save coin hopping history; automatic switches are deferred."},
        "coin_hopping_switch_not_recorded": {de: "Coin gewechselt, aber die Aktion konnte nicht im Verlauf gespeichert werden.", en: "Coin switched, but the action could not be saved to history."},
        "coin_hopping_worker_not_mining": {de: "Der Worker minet nicht; automatische Wechsel sind ausgesetzt.", en: "The worker is not mining; automatic switches are deferred."},
        "coin_hopping_target_failed": {de: "Power Target konnte nicht gesetzt werden.", en: "Could not apply the power target."},
        "coin_hopping_switch_failed": {de: "Coin-Wechsel fehlgeschlagen; Wiederherstellung der vorherigen Zuordnung wurde versucht.", en: "Coin switch failed; restoration of the previous assignment was attempted."},
        "coin_hopping_evaluation_failed": {de: "Coin-Hopping-Prüfung fehlgeschlagen; automatische Wechsel werden beim nächsten Intervall erneut geprüft.", en: "Coin hopping evaluation failed; automatic switching will be checked again at the next interval."},
        "benchmark_sample_progress": {de: "{phase} · {samples}/{required} Messpunkte", en: "{phase} · {samples}/{required} samples"},
        "coin_hopping_switched": {de: "Coin automatisch gewechselt.", en: "Coin switched automatically."},
        "coin_hopping_power_adjusted": {de: "Power Target für den aktuellen Coin automatisch angepasst.", en: "Power target adjusted automatically for the current coin."},
        "coin_hopping_switch_not_persisted": {de: "Coin gewechselt; die Sperrfrist konnte nicht gespeichert werden. Automatische Wechsel nach einem Neustart prüfen.", en: "Coin switched, but the cooldown could not be saved. Check automatic switching after a restart."},
        "energy_per_hash": {de: "Energie je Hash", en: "Energy per hash"},
        "cpu_gpu_power": {de: "CPU + GPU LEISTUNG", en: "CPU + GPU POWER"},
        "pc_components": {de: "PC-KOMPONENTEN", en: "PC COMPONENTS"},
        "other_power_sensors": {de: "Weitere Leistungswerte", en: "Other power readings"},
        "no_separate_system_power_sensor": {de: "Kein separater Leistungswert für weitere PC-Komponenten verfügbar", en: "No separate power reading for other PC components is available"},
        "0_available": {de: "0 verfügbar", en: "0 available"},
        "0_installed": {de: "0 installiert", en: "0 installed"},
        "1_pool_fee": {de: "· 1 % Poolgebühr", en: "· 1% pool fee"},
        "2_5_miner_fee": {de: "2.5% miner fee", en: "2.5% miner fee"},
        "7_days": {de: "7 Tage", en: "7 days"},
        "a_checkmark_indicates_a_setting_solarminer_e": {
            de: "Ein Haken zeigt eine Einstellung an, die SolarMiner für den Miner aktiviert hat. Ob der Miner sie vollständig nutzen kann, siehst du in seiner Ausgabe.",
            en: "A checkmark indicates a setting SolarMiner enabled for the miner. Check its output to see whether the miner can fully use it."
        },
        "a_miner_is_reporting_an_error_its_console_ca": {
            de: "Ein Miner meldet einen Fehler. Seine Konsole hilft bei der Diagnose.",
            en: "A miner is reporting an error. Its console can help diagnose the issue."
        },
        "a_miner_is_the_combination_of_a_coin_and_a_m": {
            de: "Ein Miner ist die Kombination aus Coin und Miner-Programm. Mehrere Programme pro Coin sind installierbar; gestartet wird das markierte.",
            en: "A miner is the combination of a coin and a miner program. Several programs per coin can be installed; the marked one is the one that starts."
        },
        "a_solarminer_node_steers_or_reads_this_agent": {
            de: "Ein SolarMiner Node steuert oder fragt diesen Agenten ab – volle Automatisierungsgebühr.",
            en: "A SolarMiner Node steers or reads this agent – full automation fee."
        },
        "a_wallet_is_saved_together_with_its_pool_des": {
            de: "Eine Wallet wird zusammen mit dem Pool-Ziel gespeichert. Für GPUs werden dabei alle erkannten GPUs vorbereitet; welche davon tatsächlich minen, bestimmst du später unter Worker.",
            en: "A wallet is saved together with its pool destination. All detected GPUs are prepared; choose which ones mine later in Workers."
        },
        "a_worker_needs_attention": {de: "Ein Worker braucht Aufmerksamkeit.", en: "A worker needs attention."},
        "above": {de: "über", en: "above"},
        "account": {de: "Konto", en: "Account"},
        "action": {de: "Aktion", en: "Action"},
        "action_failed_error": {de: "Aktion fehlgeschlagen: {error}", en: "Action failed: {error}"},
        "actions_are_available_afterwards": {
            de: "Aktionen sind danach verfügbar.",
            en: "Actions are available afterwards."
        },
        "activation_failed": {de: "Aktivierung fehlgeschlagen:", en: "Activation failed:"},
        "active_build": {de: "Aktiver Build", en: "Active build"},
        "active_build_for_this_coin": {de: "Aktiver Build für diesen Coin", en: "Active build for this coin"},
        "active_pools_and_connection": {de: "Aktive Pools und Verbindung", en: "Active pools and connection"},
        "active_reported": {de: "Aktiv / gemeldet", en: "Active / reported"},
        "active_target": {de: "aktives Ziel", en: "active target"},
        "active_workers": {de: "AKTIVE WORKER", en: "ACTIVE WORKERS"},
        "adapter_and_real_fee_share_verification_are": {
            de: "Adapter und reale Fee-/Share-Prüfung stehen aus",
            en: "Adapter and real fee/share verification are pending"
        },
        "adapter_not_integrated_yet_download_start_an": {
            de: "Noch nicht integrierter Adapter: kein Download, Start oder Fee-Pfad verfügbar.",
            en: "Adapter not integrated yet: download, start, and fee path are unavailable."
        },
        "add": {de: "Hinzufügen", en: "Add"},
        "add_a_miner": {de: "Miner hinzufügen", en: "Add a miner"},
        "add_miner": {de: "MINER HINZUFÜGEN", en: "ADD MINER"},
        "add_miner_dd6410": {de: "Miner hinzufügen →", en: "Add miner →"},
        "advanced": {de: "Erweitert", en: "Advanced"},
        "advanced_custom_pool_address": {de: "Erweitert · eigene Pool-Adresse", en: "Advanced · custom pool address"},
        "agent_connected": {de: "Agent verbunden", en: "Agent connected"},
        "agent_mode": {de: "Agent-Modus", en: "Agent mode"},
        "agent_status": {de: "Agent-Status", en: "Agent status"},
        "agent_unavailable": {de: "Agent nicht erreichbar", en: "Agent unavailable"},
        "algorithm": {de: "Algorithmus", en: "Algorithm"},
        "algorithm_for_hashrate_and_efficiency": {
            de: "Algorithmus für Hashrate und Effizienz",
            en: "Algorithm for hashrate and efficiency"
        },
        "algorithm_for_hashrate_history": {
            de: "Algorithmus für Hashrate-Verlauf",
            en: "Algorithm for hashrate history"
        },
        "algorithms": {de: "Algorithmen", en: "Algorithms"},
        "all": {de: "Alle", en: "All"},
        "all_algorithms_do_not_add_incomparable_rates": {
            de: "Alle Algorithmen · nicht vergleichbar summieren",
            en: "All algorithms · do not add incomparable rates"
        },
        "all_detected_devices": {de: "Alle erkannten Geräte", en: "All detected devices"},
        "all_devices_across_all_miners_a_worker_alway": {
            de: "Alle Geräte über alle Miner hinweg. Ein Worker gehört immer zu genau einem aktiven Miner seines Coins.",
            en: "All devices across all miners. A worker always belongs to exactly one active miner of its coin."
        },
        "all_gpus": {de: "Alle GPUs", en: "All GPUs"},
        "all_hardware": {de: "Alle Hardware", en: "All hardware"},
        "all_known_deductions_are_broken_down_live_so": {
            de: "Alle bekannten Abzüge werden live aufgeschlüsselt. SolarMiner nutzt eine verpflichtende Fee-Route; ist sie nicht erreichbar, startet der betreffende Miner nicht. Pool- und Miner-Gebühren sind zusätzlich ausgewiesen.",
            en: "All known deductions are broken down live. SolarMiner uses a mandatory fee route; if it is unavailable, the affected miner will not start. Pool and miner fees are listed separately."
        },
        "all_known_deductions_are_itemised_live_solar": {
            de: "Alle bekannten Abzüge werden live aufgeschlüsselt. SolarMiner nutzt eine verpflichtende Fee-Route; ist sie nicht erreichbar, startet der Miner nicht.",
            en: "All known deductions are itemised live. SolarMiner uses a mandatory fee route; if it is unavailable, the miner will not start."
        },
        "all_sensors": {de: "Alle Sensoren →", en: "All sensors →"},
        "all_telemetry_data": {de: "Alle Telemetriedaten", en: "All telemetry data"},
        "all_workers": {de: "Alle Worker", en: "All workers"},
        "all_workers_7f893c": {de: "Alle Worker →", en: "All workers →"},
        "allow_global_node_automation": {de: "Node-Automatik global erlauben", en: "Allow global Node automation"},
        "allow_node_control": {de: "Node-Steuerung erlauben", en: "Allow Node control"},
        "allow_remote_control_by_solarminer_node": {
            de: "Remote-Steuerung durch SolarMiner Node und freigegebene PC-Agents erlauben",
            en: "Allow remote control by SolarMiner Node and shared PC agents"
        },
        "and_its_workers_appear_here": {
            de: "— und seine Worker erscheinen hier.",
            en: "— and its workers appear here."
        },
        "another_pc_agent_shares_only_read_only_wo": {
            de: "Ein anderer PC-Agent teilt Worker, Ertrag und zusammengefasste Energiewerte. Wenn dort die externe Steuerung aktiv ist, kannst du seine Worker hier ohne weiteren Bestätigungsdialog starten und pausieren.",
            en: "Another PC agent shares workers, earnings and aggregate energy values. If external control is enabled there, you can start and pause its workers here without another confirmation dialog."
        },
        "active_workers_on_this_and_shared_agents": {
            de: "Worker auf diesem und freigegebenen Agents",
            en: "Workers on this and shared agents"
        },
        "average_earnings_per_kwh": {de: "Ø Ertrag pro kWh", en: "Avg. earnings per kWh"},
        "average_of_workers_with_data": {de: "Durchschnitt der Worker mit Daten", en: "Average of workers with data"},
        "local_and_shared_workers": {de: "lokale und freigegebene Worker", en: "local and shared workers"},
        "network_mining": {de: "MINING IM NETZWERK", en: "NETWORK MINING"},
        "pc_agent": {de: "PC-Agent", en: "PC agent"},
        "read_only": {de: "schreibgeschützt", en: "read-only"},
        "remote_workers_are_read_only": {
            de: "Freigegebene Remote-Worker können direkt gestartet oder pausiert werden; deaktivierte Steuerungen bleiben nur sichtbar.",
            en: "Shared remote workers can be started or paused directly; workers with control disabled remain view-only."
        },
        "some_pc_agents_are_not_sharing_live_data": {
            de: "{count} PC-Agent(s) liefern derzeit keine Live-Daten.",
            en: "{count} PC agent(s) are not currently providing live data."
        },
        "sum_of_running_workers": {de: "Summe der laufenden Worker", en: "Sum of running workers"},
        "this_pc": {de: "dieser PC", en: "this PC"},
        "this_pc_agent": {de: "Dieser PC-Agent", en: "This PC agent"},
        "could_not_change_lan_visibility_error": {
            de: "LAN-Sichtbarkeit konnte nicht geändert werden: {error}",
            en: "Could not change LAN visibility: {error}"
        },
        "lan_import": {de: "LAN-IMPORT", en: "LAN IMPORT"},
        "copy_wallet_targets_from_another_agent": {de: "Wallet-Ziele von anderem Agenten übernehmen", en: "Copy wallet targets from another agent"},
        "choose_a_discovered_agent_to_load_its_explicit": {
            de: "Wähle einen gefundenen PC-Agenten. Es werden nur Wallet-Ziele geladen, die dessen Besitzer ausdrücklich im LAN freigegeben hat. Übernommene Werte werden erst nach „Wallet speichern“ lokal gespeichert.",
            en: "Choose a discovered PC Agent. Only wallet targets its owner explicitly shared on the LAN are loaded. Imported values are saved locally only after you select Save wallet."
        },
        "no_other_pc_agents_found_on_the_lan": {de: "Keine weiteren PC-Agents im lokalen Netzwerk gefunden.", en: "No other PC Agents found on the local network."},
        "no_local_or_shared_workers_available": {de: "Keine lokalen oder freigegebenen Worker verfügbar.", en: "No local or shared workers are available."},
        "load_wallet_targets": {de: "Wallet-Ziele laden", en: "Load wallet targets"},
        "loading_wallet_targets": {de: "Wallet-Ziele werden geladen …", en: "Loading wallet targets …"},
        "use_imported_wallet_target": {de: "Übernommenes Ziel verwenden", en: "Use imported target"},
        "wallet_target_applied_save_to_confirm": {de: "{coin}: Übernommenes Wallet-Ziel eingesetzt. Speichere es zur Bestätigung.", en: "{coin}: Imported wallet target applied. Save it to confirm."},
        "wallet_targets_loaded_choose_a_coin_to_apply": {de: "Wallet-Ziele von {agent} geladen. Wähle bei einem Coin „Übernommenes Ziel verwenden“.", en: "Wallet targets from {agent} loaded. Choose Use imported target for a coin."},
        "the_selected_agent_has_no_saved_wallet_targets": {de: "Der ausgewählte Agent hat keine gespeicherten Wallet-Ziele.", en: "The selected agent has no saved wallet targets."},
        "wallet_targets_not_available_from_this_agent": {de: "Dieser Agent gibt seine Wallet-Ziele nicht frei.", en: "This agent does not share its wallet targets."},
        "peer_wallet_targets_unavailable": {de: "Die Wallet-Ziele dieses Agenten sind nicht erreichbar.", en: "This agent's wallet targets are unavailable."},
        "could_not_load_wallet_targets_error": {de: "Wallet-Ziele konnten nicht geladen werden: {error}", en: "Could not load wallet targets: {error}"},
        "share_wallet_targets_with_other_pc_agents": {de: "Wallet-Ziele mit anderen PC-Agents im selben Netzwerk teilen", en: "Share wallet targets with other PC Agents on the same network"},
        "wallet_targets_are_private_by_default_and_can_be": {de: "Wallet-Ziele sind standardmäßig privat. Bei Freigabe können andere gefundene PC-Agents die Ziele laden, aber nicht speichern oder ändern.", en: "Wallet targets are private by default. When shared, other discovered PC Agents can load them but cannot save or change them."},
        "wallet_target_sharing_enabled": {de: "Wallet-Ziele sind jetzt im lokalen Netzwerk freigegeben.", en: "Wallet targets are now shared on the local network."},
        "wallet_target_sharing_disabled": {de: "Wallet-Ziele sind nicht mehr im lokalen Netzwerk freigegeben.", en: "Wallet targets are no longer shared on the local network."},
        "could_not_change_wallet_target_sharing_error": {de: "Wallet-Freigabe im LAN konnte nicht geändert werden: {error}", en: "Could not change LAN wallet sharing: {error}"},
        "lan_visibility": {de: "LAN-Sichtbarkeit", en: "LAN visibility"},
        "lan_visibility_enabled_searching_the_local_n": {
            de: "LAN-Sichtbarkeit aktiviert. Die Suche im lokalen Netzwerk läuft.",
            en: "LAN visibility enabled. Searching the local network."
        },
        "lan_visibility_is_off_this_agent_shows_no": {
            de: "LAN-Sichtbarkeit ist deaktiviert. Andere PC-Agents sehen diesen PC, aber nicht seine Worker, seinen Ertrag oder seine Energiewerte.",
            en: "LAN visibility is off. Other PC agents see this PC but not its workers, earnings or energy values."
        },
        "lan_visibility_off_peers_stay_listed_without": {
            de: "LAN-Sichtbarkeit ist deaktiviert: Andere Agents bleiben hier sichtbar; eigene Worker, Erträge und Energiewerte bleiben verborgen.",
            en: "LAN visibility is off: other agents stay listed here; your own workers, earnings and energy values stay hidden."
        },
        "no_other_pc_agents_found_searching_the_local": {
            de: "Keine weiteren PC-Agents gefunden — die Suche im lokalen Netzwerk läuft.",
            en: "No other PC agents found — searching the local network."
        },
        "open_agent_dashboard": {de: "Agent-Dashboard öffnen →", en: "Open agent dashboard →"},
        "other_pc_agents_in_the_network": {de: "Andere PC-Agents im Netzwerk", en: "Other PC agents in the network"},
        "searching_the_local_network_for_pc_agents": {
            de: "Suche im lokalen Netzwerk nach PC-Agents …",
            en: "Searching the local network for PC agents …"
        },
        "shared_network": {de: "GEMEINSAMES NETZWERK", en: "SHARED NETWORK"},
        "show_this_agent_to_other_pc_agents_in_th": {
            de: "Diesen Agenten anderen PC-Agents im selben Netzwerk anzeigen (Worker, Ertrag und Energiewerte)",
            en: "Show this agent to other PC agents in the same network (workers, earnings and energy values)"
        },
        "lan_control": {de: "LAN-Steuerung", en: "LAN control"},
        "view_only": {de: "nur Ansicht", en: "view only"},
        "starting_remote_worker": {de: "{worker} wird gestartet …", en: "Starting {worker} …"},
        "miner_starting": {de: "Startet …", en: "Starting …"},
        "worker_start_in_progress": {de: "Dieser Worker wird bereits gestartet.", en: "This worker is already starting."},
        "pausing_remote_worker": {de: "{worker} wird pausiert …", en: "Pausing {worker} …"},
        "remote_worker_started": {de: "{worker} wurde gestartet.", en: "{worker} was started."},
        "remote_worker_paused": {de: "{worker} wurde pausiert.", en: "{worker} was paused."},
        "peer_worker_control_disabled": {de: "Die externe Steuerung ist auf diesem PC-Agent deaktiviert.", en: "External control is disabled on this PC agent."},
        "peer_worker_unavailable": {de: "Der Remote-Worker ist nicht erreichbar.", en: "The remote worker is unavailable."},
        "peer_worker_action_failed": {de: "Die Worker-Aktion wurde vom Remote-Agent nicht ausgeführt.", en: "The remote agent did not apply the worker action."},
        "remote_worker_action_failed_error": {de: "Remote-Worker-Aktion fehlgeschlagen: {error}", en: "Remote worker action failed: {error}"},
        "network_energy_uses_each_agents_tariff": {
            de: "Summe dieses und {count} freigegebener PC-Agent(s). Kosten verwenden den jeweiligen lokalen Tarif und werden in die Anzeigewährung umgerechnet. Netzteil- und übrige Systemverluste können fehlen.",
            en: "Total for this and {count} shared PC agent(s). Costs use each agent's local rate and are converted to the display currency. Power-supply and other system losses may be missing."
        },
        "visibility_in_the_network": {de: "SICHTBARKEIT IM NETZWERK", en: "VISIBILITY IN THE NETWORK"},
        "allow_the_node_to_control_this_device": {
            de: "Dieses Gerät für die Node freigeben",
            en: "Allow the Node to control this device"
        },
        "allowed": {de: "erlaubt", en: "allowed"},
        "already_paid_balance": {de: "Bereits ausgezahlter Bestand", en: "Already paid balance"},
        "total_paid_by_pool": {de: "Vom Pool ausgezahlt", en: "Paid by pool"},
        "confirmed_pool_payouts": {de: "Bestätigte Pool-Auszahlungen insgesamt", en: "Total confirmed pool payouts"},
        "amd_only": {de: "Nur AMD", en: "AMD only"},
        "an_installed_miner_needs_its_pool_payout_and": {
            de: "Pool, Auszahlung und Geräteauswahl fehlen bei einem installierten Miner.",
            en: "An installed miner needs its pool, payout and device selection configured."
        },
        "anonymous_one_time": {de: "ANONYM & EINMALIG", en: "ANONYMOUS & ONE-TIME"},
        "api_documentation": {de: "API-Dokumentation ↗", en: "API documentation ↗"},
        "apply": {de: "Übernehmen", en: "Apply"},
        "apply_custom_target": {de: "Eigenes Ziel übernehmen", en: "Apply custom target"},
        "apply_limits": {de: "Grenzen anwenden", en: "Apply limits"},
        "apply_power_target": {de: "Leistungsziel übernehmen", en: "Apply power target"},
        "approve_the_windows_uac_prompt_without_appro": {
            de: "Bestätige die Windows-UAC-Abfrage. Ohne Freigabe bleiben Agent und Telemetrie gesperrt.",
            en: "Approve the Windows UAC prompt. Without approval, the agent and telemetry remain blocked."
        },
        "approve_the_windows_uac_prompt_without_appro_3f5bb1": {
            de: "Bestätige die Windows-UAC-Abfrage. Ohne Freigabe fehlen die Windows-Sensoren. Einige Mining-Steuerungsaktionen benötigen diesen Sensorzugriff.",
            en: "Approve the Windows UAC prompt. Without approval, Windows sensors are unavailable. Some mining controls require sensor access."
        },
        "approximate_time_remaining_time": {de: "Ungefähre Restzeit: {time}", en: "Approximate time remaining: {time}"},
        "as_of": {de: "Stand", en: "As of"},
        "as_of_time": {de: "Stand {time}", en: "As of {time}"},
        "asia": {de: "Asien", en: "Asia"},
        "assign_a_coin_to_this_worker_first_its_miner": {
            de: "Weise diesem Worker zuerst einen Coin zu. Danach erscheint hier seine Minerausgabe.",
            en: "Assign a coin to this worker first. Its miner output will then appear here."
        },
        "assign_at_least_one_device_in_workers_so_the": {
            de: "Ordne im Worker-Bereich mindestens ein Gerät zu, damit der Node automatisch regeln darf.",
            en: "Assign at least one device in Workers so the Node can control it automatically."
        },
        "assign_devices": {de: "Geräte zuordnen →", en: "Assign devices →"},
        "assign_devices_and_default_coins": {
            de: "Geräte & Standard-Coins zuordnen",
            en: "Assign devices and default coins"
        },
        "assign_devices_and_default_coins_in_the_mini": {
            de: "Geräte und Standard-Coins im Mining-Profil festlegen →",
            en: "Assign devices and default coins in the mining profile →"
        },
        "assign_devices_b603f2": {de: "Geräte zuteilen →", en: "Assign devices →"},
        "assign_devices_used_by_the_node_in_the_minin": {
            de: "Welche Geräte der Node verwendet, legst du im Mining-Profil fest →",
            en: "Assign devices used by the Node in the mining profile →"
        },
        "assign_each_cpu_and_gpu_to_a_coin_and_instal": {
            de: "Hier weist du CPU und GPUs einem Coin und einer installierten Miner-Software zu.",
            en: "Assign each CPU and GPU to a coin and installed mining software here."
        },
        "assign_each_cpu_and_gpu_to_a_coin_installed": {
            de: "Hier weist du CPU und GPUs einem Coin, einer installierten Miner-Software und einem Steuerungsmodus zu.",
            en: "Assign each CPU and GPU to a coin, installed mining software and a control mode here."
        },
        "assign_workers": {de: "Worker zuweisen", en: "Assign workers"},
        "assignment": {de: "Zuweisung", en: "Assignment"},
        "assignment_failed_error": {de: "Zuweisung fehlgeschlagen: {error}", en: "Assignment failed: {error}"},
        "assignment_saved": {de: "Zuweisung gespeichert.", en: "Assignment saved."},
        "assignment_is_missing": {de: "Zuweisung fehlt", en: "Assignment is missing"},
        "assignment_saved_the_previous_running_worker": {
            de: "Zuweisung gespeichert. Ein laufender vorheriger Worker wurde sicher angehalten.",
            en: "Assignment saved. The previous running worker was stopped safely."
        },
        "assignments_control": {de: "Zuweisungen & Steuerung →", en: "Assignments & control →"},
        "at_least_one_worker_is_reporting_an_error": {
            de: "Mindestens ein Worker meldet einen Fehler.",
            en: "At least one worker is reporting an error."
        },
        "at_least_one_worker_reports_an_error": {
            de: "Mindestens ein Worker meldet einen Fehler",
            en: "At least one worker reports an error"
        },
        "automation": {de: "AUTOMATISIERUNG", en: "AUTOMATION"},
        "automation_791a1f": {de: "Automatisierung", en: "Automation"},
        "available": {de: "VERFÜGBAR", en: "AVAILABLE"},
        "available_4073fd": {de: "Verfügbar", en: "Available"},
        "available_of_total_readings_available": {
            de: "{available} von {total} Messwerten verfügbar",
            en: "{available} of {total} readings available"
        },
        "available_programs": {de: "Verfügbare Programme", en: "Available programs"},
        "available_readings_only": {de: "Nur verfügbare Messwerte", en: "Available readings only"},
        "available_sensor_readings_only": {de: "Nur verfügbare Sensorwerte", en: "Available sensor readings only"},
        "available_targets": {de: "Verfügbare Ziele", en: "Available targets"},
        "available_to_install": {de: "installierbar", en: "available to install"},
        "available_to_node": {de: "Remote freigegeben", en: "Available remotely"},
        "available_workers": {de: "Freigegebene Worker", en: "Available workers"},
        "balanced": {de: "AUSGEWOGEN", en: "BALANCED"},
        "balanced_059ab8": {de: "Ausgewogen", en: "Balanced"},
        "balances_payouts": {de: "Kontostände & Auszahlung", en: "Balances & payouts"},
        "below": {de: "unter", en: "below"},
        "benchmark_data_sent_successfully": {
            de: "Benchmarkdaten erfolgreich ans Backend übermittelt.",
            en: "Benchmark data sent successfully."
        },
        "benchmark_data_was_not_uploaded_sharing_is_d": {
            de: "Benchmarkdaten wurden nicht hochgeladen: Teilen ist deaktiviert.",
            en: "Benchmark data was not uploaded: sharing is disabled."
        },
        "benchmark_run": {de: "MESSLAUF", en: "BENCHMARK RUN"},
        "benchmark_running": {de: "Benchmark läuft", en: "Benchmark running"},
        "benchmarking": {de: "Benchmark läuft", en: "Benchmarking"},
        "power_sweep_running": {de: "Power Sweep läuft", en: "Power sweep running"},
        "benchmark_runs_until_enough_observations_are": {
            de: "Benchmark läuft bis genügend Messpunkte vorliegen",
            en: "Benchmark runs until enough observations are collected"
        },
        "benchmark_sharing_withdrawal_sent": {
            de: "Widerruf der Benchmark-Freigabe übermittelt.",
            en: "Benchmark sharing withdrawal sent."
        },
        "benchmark_started": {de: "Messlauf gestartet …", en: "Benchmark started …"},
        "benchmark_upload": {de: "Benchmark-Upload", en: "Benchmark upload"},
        "benchmark_updated_at": {de: "Aktualisiert: {time}", en: "Updated: {time}"},
        "benchmarks": {de: "Benchmarks", en: "Benchmarks"},
        "benchmark_page_title": {de: "Benchmark", en: "Benchmark"},
        "benchmark_measurement_controls": {de: "MESSUNGEN", en: "MEASUREMENTS"},
        "benchmark_page_intro": {de: "Starte Messläufe und Power Sweeps für die ausgewählten Worker.", en: "Start benchmark runs and power sweeps for the selected workers."},
        "benchmark_results_title": {de: "Ergebnisse", en: "Results"},
        "benchmark_results_intro": {de: "Wähle eine GPU, um ihre Messungen, Kurven und Erträge zu sehen.", en: "Select a GPU to see its measurements, curves, and revenue."},
        "solarminer_benchmark_results": {de: "SolarMiner · Ergebnisse", en: "SolarMiner · Results"},
        "solarminer_benchmark": {de: "SolarMiner · Benchmark", en: "SolarMiner · Benchmark"},
        "benchmark_current_sweep": {de: "Aktueller Power Sweep", en: "Current power sweep"},
        "benchmark_no_gpu_available": {de: "Keine GPU verfügbar", en: "No GPU available"},
        "benchmark_no_gpu_run_result": {de: "Für diese GPU liegen im aktuellen Messlauf keine Ergebnisse vor.", en: "The current run has no results for this GPU."},
        "benchmark_open_gpu_results": {de: "GPU-Ergebnisse und Kurven ansehen", en: "View GPU results and curves"},
        "benchmark_live_kicker": {de: "LIVE-MESSUNG", en: "LIVE MEASUREMENT"},
        "benchmark_live_heading": {de: "Worker im Benchmark", en: "Workers in the benchmark"},
        "benchmark_live_intro": {de: "Wähle einen Worker. Kurven und Zwischenwerte wachsen mit jedem gültigen Messpunkt.", en: "Select a worker. Charts and interim values grow with each valid sample."},
        "benchmark_worker_tabs": {de: "Benchmark-Worker", en: "Benchmark workers"},
        "benchmark_live_chart_aria": {de: "{metric}-Verlauf mit {count} Messpunkten", en: "{metric} chart with {count} samples"},
        "benchmark_live_sample_axis": {de: "Messpunkt {count}", en: "Sample {count}"},
        "benchmark_live_sample_number": {de: "Messpunkt {number}", en: "Sample {number}"},
        "benchmark_live_points": {de: "{count}/{required} Messpunkte", en: "{count}/{required} samples"},
        "benchmark_live_waiting": {de: "Dieser Worker wartet auf den ersten gültigen Messpunkt.", en: "This worker is waiting for its first valid sample."},
        "benchmark_live_no_samples": {de: "Für diesen Worker wurden keine gültigen Messpunkte erfasst.", en: "No valid samples were captured for this worker."},
        "benchmark_auto_start_label": {de: "Mining nach Benchmark starten", en: "Start mining after benchmark"},
        "benchmark_auto_start_help": {de: "Startet nach einem abgeschlossenen Benchmark die ausgewählten Worker mit ihrer gespeicherten Coin-, Miner- und Betriebsmodus-Konfiguration. Bei Abbruch erfolgt kein zusätzlicher Start.", en: "After a completed benchmark, starts the selected workers with their saved coin, miner, and operation mode settings. Cancelling does not trigger another start."},
        "benchmark_auto_start_pending": {de: "Nach dem Benchmark wird Mining für die ausgewählten Worker gestartet.", en: "Mining will start for the selected workers after the benchmark."},
        "benchmark_auto_start_result_heading": {de: "Mining-Start nach Benchmark", en: "Mining start after benchmark"},
        "benchmark_auto_start_accepted": {de: "Start ausgelöst", en: "Start requested"},
        "benchmark_auto_start_failed": {de: "Start fehlgeschlagen", en: "Start failed"},
        "benchmark_auto_start_skipped": {de: "Nicht gestartet", en: "Not started"},
        "benchmark_auto_start_no_result": {de: "Für diesen Benchmark wurde kein Mining-Start ausgeführt.", en: "No mining start was performed for this benchmark."},
        "benchmark_auto_start_cancelled": {de: "Der Benchmark wurde abgebrochen.", en: "The benchmark was cancelled."},
        "benchmark_auto_start_failed_run": {de: "Der Benchmark oder die Wiederherstellung ist fehlgeschlagen.", en: "The benchmark or restoration failed."},
        "benchmark_auto_start_stop_failed": {de: "Der bisherige Miner konnte nicht angehalten werden.", en: "The previous miner could not be stopped."},
        "benchmark_auto_start_start_failed": {de: "Der konfigurierte Miner konnte nicht gestartet werden.", en: "The configured miner could not be started."},
        "best_share_difficulty": {de: "Beste Share-Difficulty", en: "Best share difficulty"},
        "best_stable_value_watts_w_hashrate_power_w_e": {
            de: "Bester stabiler Messpunkt: {watts} W Limit · {hashrate} · {power} W gemessen · Energie je Hash: {efficiency}",
            en: "Best stable measurement: {watts} W limit · {hashrate} · {power} W measured · energy per hash: {efficiency}"
        },
        "blake3_decred_support": {de: "BLAKE3-Decred-Unterstützung", en: "BLAKE3 Decred support"},
        "blocked": {de: "gesperrt", en: "blocked"},
        "btc_price": {de: "BTC-Preis —", en: "BTC price —"},
        "calculating_eta": {de: "ETA wird berechnet …", en: "Calculating ETA …"},
        "cancel": {de: "Abbrechen", en: "Cancel"},
        "cancel_measurement": {de: "Messlauf abbrechen", en: "Cancel measurement"},
        "cancel_sweep": {de: "Sweep abbrechen", en: "Cancel sweep"},
        "cancellation_requested_power_limits_and_mine": {
            de: "Abbruch angefordert. Leistungsgrenzen und Miner-Zustand werden wiederhergestellt.",
            en: "Cancellation requested. Power limits and miner state are being restored."
        },
        "cancellation_requested_the_previous_mining_s": {
            de: "Abbruch angefordert. Der vorherige Mining-Zustand wird wiederhergestellt.",
            en: "Cancellation requested. The previous mining state will be restored."
        },
        "cancelled": {de: "Abgebrochen", en: "Cancelled"},
        "cancelled_power_limits_and_miner_state_resto": {
            de: "Abgebrochen; Leistungsgrenzen und Miner-Zustand wiederhergestellt",
            en: "Cancelled; power limits and miner state restored"
        },
        "change": {de: "Ändern", en: "Change"},
        "check_connection": {de: "Verbindung prüfen", en: "Check connection"},
        "check_connection_6c056f": {de: "Verbindung prüfen →", en: "Check connection →"},
        "check_gpu_drivers_and_device_access_you_can": {
            de: "Prüfe GPU-Treiber und Gerätezugriff. CPU-Mining kannst du unabhängig davon in Miner einrichten.",
            en: "Check GPU drivers and device access. You can set up CPU mining independently in Miners."
        },
        "check_worker": {de: "Worker prüfen", en: "Check worker"},
        "check_workers": {de: "Worker prüfen →", en: "Check workers →"},
        "checking_connection": {de: "Verbindung wird geprüft", en: "Checking connection"},
        "choose_a_compatible_build_in_the_miner_libra": {
            de: "Wähle in der Miner-Bibliothek einen passenden Build und installiere ihn.",
            en: "Choose a compatible build in the miner library and install it."
        },
        "choose_a_discovered_proxy_the_connection_cha": {
            de: "Wähle einen gefundenen Proxy. Die Verbindung wird erst beim Aktivieren geändert.",
            en: "Choose a discovered proxy. The connection changes only when you activate it."
        },
        "choose_a_measurement_mode_a_result_requires": {
            de: "Wähle die Art der Messung. Ein Ergebnis benötigt 12 gültige Hashrate-Messpunkte pro Worker; die Dauer hängt vom Verbindungsaufbau ab. Ergebnisse bleiben auch ohne Teilen lokal sichtbar.",
            en: "Choose a measurement mode. A result requires 12 valid hashrate samples per worker; duration depends on establishing the connection. Results remain visible locally without sharing."
        },
        "choose_at_least_one_worker_under_node_automa": {
            de: "Wähle unter Node-Automatisierung mindestens einen Worker aus, damit der Node automatisch regeln darf.",
            en: "Choose at least one worker under Node automation so the Node can control automatically."
        },
        "choose_coin": {de: "Coin wählen", en: "Choose coin"},
        "choose_the_coins_for_measurement_runs_this": {
            de: "Coins für Messläufe auswählen. Diese Auswahl gilt für den Benchmark und den Power-Limit-Test. Nicht installierte Coins sind gesperrt; Coins, die deine Karten nicht unterstützen, kannst du abwählen, damit fehlerhafte Phasen den Lauf nicht blockieren.",
            en: "Choose the coins for measurement runs. This selection applies to the benchmark and the power-limit test. Uninstalled coins are locked; deselect coins your cards cannot run so failing phases never block a run."
        },
        "coins_for_measurement_runs": {de: "Coins für Messläufe auswählen", en: "Select coins for measurement runs"},
        "choose_cpu_or_gpu_and_install_a_suitable_min": {
            de: "Wähle CPU oder GPU und installiere den passenden Miner über das Plus.",
            en: "Choose CPU or GPU and install a suitable miner using the plus button."
        },
        "choose_hardware": {de: "Hardware wählen", en: "Choose hardware"},
        "choose_how_much_power_your_gpus_may_use_unde": {
            de: "Lege den zulässigen Leistungsbereich deiner GPUs fest. Treibergrenzen geben den verfügbaren Bereich vor. Beim Anwenden wird das aktuelle Treiberlimit sofort an diesen Bereich angepasst; beim nächsten Miner-Start wird die Grenze erneut geprüft.",
            en: "Choose the allowed power range for your GPUs. Driver limits define the available range. Applying the range immediately adjusts the current driver limit; the limit is checked again at the next miner start."
        },
        "choose_kryptex_with_your_own_etc_wallet_or_c": {
            de: "Wähle Kryptex mit eigener ETC-Wallet oder bestätige das SolarMiner-Standardziel. Die Fee-Route muss vor dem Start freigeschaltet sein.",
            en: "Choose Kryptex with your own ETC wallet or confirm the SolarMiner default destination. The fee route must be enabled before mining starts."
        },
        "choose_kryptex_with_your_own_rvn_wallet_or_c": {
            de: "Wähle Kryptex mit eigener RVN-Wallet oder bestätige das SolarMiner-Standardziel. Die Fee-Route muss vor dem Start freigeschaltet sein.",
            en: "Choose Kryptex with your own RVN wallet or confirm the SolarMiner default destination. The fee route must be enabled before mining starts."
        },
        "choose_language": {de: "Sprache wählen", en: "Choose language"},
        "choose_miner_log": {de: "Miner-Log wählen", en: "Choose miner log"},
        "choose_mining_pool": {de: "Mining-Pool wählen", en: "Choose mining pool"},
        "choose_the_default_coin_for_each_device_loca": {
            de: "Lege pro Gerät den Standard-Coin fest. „Nur lokal / Benchmarks“ schließt das Gerät von automatischen Starts und der Leistungsplanung des Node aus. Du kannst es weiterhin manuell nutzen.",
            en: "Choose the default coin for each device. “Local / benchmarks only” excludes that device from automatic starts and the Node’s power planning. You can still use it manually."
        },
        "choose_your_hardware_and_install_the_matchin": {
            de: "Wähle deine Hardware und installiere den passenden Miner.",
            en: "Choose your hardware and install the matching miner."
        },
        "clear_selection": {de: "Auswahl aufheben", en: "Clear selection"},
        "click_a_worker_to_open_its_continuous_miner": {
            de: "Klicke einen Worker an, um seine fortlaufende Miner-Konsole zu öffnen. Leistungs- und Energiewerte werden nur angezeigt, wenn der Agent einen echten Sensorwert erhält. Fehlende Messwerte werden nicht als null Verbrauch gewertet.",
            en: "Click a worker to open its continuous miner console. Power and energy values are shown only when the agent receives a real sensor reading. Missing readings are not treated as zero consumption."
        },
        "close": {de: "Schließen", en: "Close"},
        "close_navigation": {de: "Navigation schließen", en: "Close navigation"},
        "coin_algorithm": {de: "Coin & Algorithmus", en: "Coin & algorithm"},
        "coin_algorithmunavailable": {de: "{coin} · {algorithm}{unavailable}", en: "{coin} · {algorithm}{unavailable}"},
        "coin_could_not_save_error": {
            de: "{coin}: Speichern fehlgeschlagen: {error}",
            en: "{coin}: Could not save: {error}"
        },
        "coin_in_pool": {de: "{coin} · im Pool", en: "{coin} · in pool"},
        "coin_is_incompatible_with_this_hardware": {
            de: "Coin passt nicht zur Hardware",
            en: "Coin is incompatible with this hardware"
        },
        "coin_policy_must_be_auto_or_fixed": {
            de: "Coin-Policy muss AUTO oder FIXED sein",
            en: "Coin policy must be AUTO or FIXED"
        },
        "coin_pool_payout_or_device_selection_is_miss": {
            de: "{coin}: Pool, Auszahlung oder Geräteauswahl fehlen.",
            en: "{coin}: Pool, payout, or device selection is missing."
        },
        "coin_routes_connection_details": {
            de: "Coin-Routen & Verbindungsdetails",
            en: "Coin routes & connection details"
        },
        "coin_tested_date": {de: "{coin} · getestet {date}", en: "{coin} · tested {date}"},
        "coins_miners": {de: "Coins & Miner", en: "Coins & miners"},
        "coins_with_a_pool": {de: "Coins mit Pool", en: "Coins with a pool"},
        "coins_with_a_pool_aff512": {de: "Coins mit Pool:", en: "Coins with a pool:"},
        "compare_configurations": {de: "VERGLEICH DER KONFIGURATIONEN", en: "COMPARE CONFIGURATIONS"},
        "comparison": {de: "Vergleich", en: "Comparison"},
        "compatible_gpu_and_driver": {de: "Kompatible GPU und Treiber", en: "Compatible GPU and driver"},
        "coin_compatible_gpu_vendor": {de: "{coin}: {vendor}-GPU", en: "{coin}: {vendor} GPU"},
        "complete": {de: "Fertig", en: "Complete"},
        "complete_miner_output": {de: "Vollständige Miner-Ausgabe", en: "Complete miner output"},
        "complete_power_limits_and_miner_state_restor": {
            de: "Fertig; Leistungsgrenzen und Miner-Zustand wiederhergestellt",
            en: "Complete; power limits and miner state restored"
        },
        "complete_setup": {de: "Einrichtung abschließen", en: "Complete setup"},
        "completed_runs_count": {de: "Abgeschlossene Läufe ({count})", en: "Completed runs ({count})"},
        "component": {de: "Komponente", en: "Component"},
        "component_consumption_from_available_cpu_pac": {
            de: "Komponentenverbrauch aus verfügbaren CPU-Package- und GPU-Board-Sensoren · Tarif {tariff} {currency}/kWh. Netzteil- und übrige Systemverluste können fehlen.",
            en: "Component consumption from available CPU package and GPU board sensors · Rate {tariff} {currency}/kWh. Power supply and other system losses may be missing."
        },
        "configuration": {de: "KONFIGURATION", en: "CONFIGURATION"},
        "configuration_faa93c": {de: "Konfiguration", en: "Configuration"},
        "configuration_is_missing": {de: "Konfiguration fehlt", en: "Configuration is missing"},
        "configure_workers": {de: "Worker einrichten →", en: "Configure workers →"},
        "connect_proxy": {de: "Proxy verbinden →", en: "Connect proxy →"},
        "connect_the_solarminer_proxy_first": {
            de: "Verbinde zuerst den SolarMiner-Proxy.",
            en: "Connect the SolarMiner proxy first."
        },
        "connected": {de: "Verbunden", en: "Connected"},
        "connected_e0e3b1": {de: "verbunden", en: "connected"},
        "connecting": {de: "Verbinde …", en: "Connecting …"},
        "connecting_to_pool": {de: "Verbinde mit Pool", en: "Connecting to pool"},
        "connection": {de: "VERBINDUNG", en: "CONNECTION"},
        "connection_5fbdf2": {de: "Verbindung", en: "Connection"},
        "connection_changed_all_miners_were_paused_re": {
            de: "Verbindung geändert. Alle Miner wurden pausiert. Starte sie auf der Miner-Seite erneut.",
            en: "Connection changed. All miners were paused. Restart them on the Miners page."
        },
        "connection_failed": {de: "Verbindung fehlgeschlagen", en: "Connection failed"},
        "connection_to_the_agent_interrupted_displaye": {
            de: "Verbindung zum Agent unterbrochen. Angezeigte Werte können veraltet sein.",
            en: "Connection to the agent interrupted. Displayed values may be stale."
        },
        "console": {de: "Konsole", en: "Console"},
        "console_diagnostics": {de: "Konsole & Diagnose", en: "Console & diagnostics"},
        "console_output_could_not_be_loaded_error": {
            de: "Konsolenausgabe konnte nicht geladen werden: {error}",
            en: "Console output could not be loaded: {error}"
        },
        "consumption_cost": {de: "Verbrauch & Kosten", en: "Consumption & cost"},
        "consumption_of_these_sessions": {de: "Verbrauch dieser Sessions", en: "Consumption of these sessions"},
        "continuous_miner_output": {de: "Fortlaufende Minerausgabe", en: "Continuous miner output"},
        "control": {de: "Steuerung", en: "Control"},
        "control_allowed": {de: "Steuerung erlaubt", en: "Control allowed"},
        "control_disabled": {de: "Steuerung ausgeschaltet", en: "Control disabled"},
        "control_is_allowed_but_no_device_is_assigned": {
            de: "Die Steuerung ist erlaubt, aber noch kein Gerät dem Node zugeordnet.",
            en: "Control is allowed, but no device is assigned to the Node yet."
        },
        "control_miners": {de: "Miner steuern →", en: "Control miners →"},
        "control_miners_locally_and_assign_devices_fo": {
            de: "Miner lokal steuern und Geräte für die Automatisierung zuordnen.",
            en: "Control miners locally and assign devices for automation."
        },
        "control_mode": {de: "Steuerungsmodus", en: "Control mode"},
        "control_workers": {de: "Worker steuern →", en: "Control workers →"},
        "could_not_cancel_the_measurement": {
            de: "Messlauf konnte nicht abgebrochen werden.",
            en: "Could not cancel the measurement."
        },
        "could_not_cancel_the_sweep": {
            de: "Der Sweep konnte nicht abgebrochen werden.",
            en: "Could not cancel the sweep."
        },
        "could_not_change_remote_control_error": {
            de: "Remote-Steuerung konnte nicht geändert werden: {error}",
            en: "Could not change remote control: {error}"
        },
        "could_not_change_worker_permission_error": {
            de: "Worker-Freigabe konnte nicht geändert werden: {error}",
            en: "Could not change worker permission: {error}"
        },
        "could_not_display_sensor_data_error": {
            de: "Sensordaten konnten nicht dargestellt werden: {error}",
            en: "Could not display sensor data: {error}"
        },
        "could_not_load_agent_data": {
            de: "Agent-Daten konnten nicht geladen werden:",
            en: "Could not load agent data:"
        },
        "could_not_load_automation_error": {
            de: "Automatisierung konnte nicht geladen werden: {error}",
            en: "Could not load automation: {error}"
        },
        "could_not_load_benchmark_status_existing_res": {
            de: "Benchmark-Status konnte nicht geladen werden. Vorhandene Ergebnisse bleiben sichtbar.",
            en: "Could not load benchmark status. Existing results remain visible."
        },
        "could_not_load_hardware_data": {
            de: "Hardwaredaten konnten nicht geladen werden:",
            en: "Could not load hardware data:"
        },
        "could_not_load_hardware_data_error": {
            de: "Hardwaredaten konnten nicht geladen werden: {error}",
            en: "Could not load hardware data: {error}"
        },
        "could_not_load_miner_catalog_error": {
            de: "Miner-Katalog konnte nicht geladen werden: {error}",
            en: "Could not load miner catalog: {error}"
        },
        "could_not_load_proxy_data": {
            de: "Proxy-Daten konnten nicht geladen werden:",
            en: "Could not load proxy data:"
        },
        "could_not_load_proxy_data_error": {
            de: "Proxy-Daten konnten nicht geladen werden: {error}",
            en: "Could not load proxy data: {error}"
        },
        "could_not_load_sensor_readings": {
            de: "Sensorwerte konnten nicht geladen werden",
            en: "Could not load sensor readings"
        },
        "could_not_load_status": {de: "Status konnte nicht geladen werden:", en: "Could not load status:"},
        "could_not_load_telemetry_error": {
            de: "Telemetrie konnte nicht geladen werden: {error}",
            en: "Could not load telemetry: {error}"
        },
        "could_not_load_the_node_profile": {
            de: "Node-Profil konnte nicht geladen werden.",
            en: "Could not load the Node profile."
        },
        "could_not_load_wallet_data": {
            de: "Wallet-Daten konnten nicht geladen werden:",
            en: "Could not load wallet data:"
        },
        "could_not_load_wallet_data_error": {
            de: "Wallet-Daten konnten nicht geladen werden: {error}",
            en: "Could not load wallet data: {error}"
        },
        "could_not_load_workers_error": {
            de: "Worker konnten nicht geladen werden: {error}",
            en: "Could not load workers: {error}"
        },
        "could_not_read_node_permissions": {
            de: "Node-Freigabe konnte nicht gelesen werden.",
            en: "Could not read Node permissions."
        },
        "could_not_read_sharing_permissions_refresh_t": {
            de: "Die Freigabe konnte nicht gelesen werden. Aktualisiere die Seite, bevor du sie änderst.",
            en: "Could not read sharing permissions. Refresh the page before changing them."
        },
        "could_not_render_sensor_data": {de: "Sensoranzeige fehlgeschlagen", en: "Could not render sensor data"},
        "could_not_request_restart": {
            de: "Neustart konnte nicht angefordert werden:",
            en: "Could not request restart:"
        },
        "could_not_request_restart_error": {
            de: "Neustart konnte nicht angefordert werden: {error}",
            en: "Could not request restart: {error}"
        },
        "could_not_retry_the_upload": {
            de: "Der Upload konnte nicht erneut gestartet werden.",
            en: "Could not retry the upload."
        },
        "could_not_safely_apply_power_target": {
            de: "Leistungsziel konnte nicht sicher angewendet werden",
            en: "Could not safely apply power target"
        },
        "could_not_save_default": {de: "Standard konnte nicht gespeichert werden", en: "Could not save default"},
        "could_not_save_local_control_settings": {
            de: "Lokale Steuereinstellungen konnten nicht gespeichert werden",
            en: "Could not save local control settings"
        },
        "could_not_save_miner_selection": {
            de: "Miner-Auswahl konnte nicht gespeichert werden",
            en: "Could not save miner selection"
        },
        "could_not_save_permission": {
            de: "Die Freigabe konnte nicht gespeichert werden.",
            en: "Could not save permission."
        },
        "could_not_save_the_electricity_rate": {
            de: "Stromtarif konnte nicht gespeichert werden.",
            en: "Could not save the electricity rate."
        },
        "could_not_save_the_local_setting": {
            de: "Die lokale Einstellung konnte nicht gespeichert werden.",
            en: "Could not save the local setting."
        },
        "could_not_save_worker_assignment": {
            de: "Worker-Zuweisung konnte nicht gespeichert werden",
            en: "Could not save worker assignment"
        },
        "could_not_save_worker_permission": {
            de: "Worker-Freigabe konnte nicht gespeichert werden",
            en: "Could not save worker permission"
        },
        "could_not_start_miner": {de: "Miner konnte nicht gestartet werden", en: "Could not start miner"},
        "could_not_start_the_benchmark": {
            de: "Benchmark konnte nicht gestartet werden.",
            en: "Could not start the benchmark."
        },
        "could_not_start_the_efficiency_sweep": {
            de: "Der Effizienz-Sweep konnte nicht gestartet werden.",
            en: "Could not start the efficiency sweep."
        },
        "could_not_stop_gpu_miners": {de: "GPU-Miner konnten nicht angehalten werden", en: "Could not stop GPU miners"},
        "could_not_stop_previous_worker": {
            de: "Vorheriger Worker konnte nicht angehalten werden",
            en: "Could not stop previous worker"
        },
        "could_not_stop_the_running_worker": {
            de: "Der laufende Worker konnte nicht angehalten werden",
            en: "Could not stop the running worker"
        },
        "count_available": {de: "{count} verfügbar", en: "{count} available"},
        "count_components_are_not_assigned_to_a_minin": {
            de: "{count} Komponenten sind noch keinem Mining-Profil zugewiesen.",
            en: "{count} components are not assigned to a mining profile yet."
        },
        "count_configured_workers": {de: "{count} konfigurierte Worker", en: "{count} configured workers"},
        "count_devices": {de: "{count} Geräte", en: "{count} devices"},
        "count_hidden_reset_filters": {
            de: "{count} ausgeblendet · Filter zurücksetzen",
            en: "{count} hidden · Reset filters"
        },
        "count_installed": {de: "{count} installiert", en: "{count} installed"},
        "count_samples": {de: "({count} Messwerte)", en: "({count} samples)"},
        "count_workers": {de: "{count} Worker", en: "{count} workers"},
        "count_workers_duration": {de: "{count} Worker · {duration}", en: "{count} workers · {duration}"},
        "count_workers_with_errors": {de: "{count} Worker mit Fehler", en: "{count} workers with errors"},
        "cpu_and_gpu_miners_can_run_in_parallel": {
            de: "CPU- und GPU-Miner können parallel laufen.",
            en: "CPU and GPU miners can run in parallel."
        },
        "cpu_and_gpu_recorded_separately": {de: "CPU und GPU getrennt erfasst", en: "CPU and GPU recorded separately"},
        "cpu_mining": {de: "CPU Mining", en: "CPU mining"},
        "current": {de: "Aktuell:", en: "Current:"},
        "current_median_hashrate": {de: "Aktuelle mediane Hashrate", en: "Current median hashrate"},
        "current_miner_value": {de: "Aktueller Miner-Wert", en: "Current miner value"},
        "current_pool_difficulty": {de: "Aktuelle Pool-Difficulty", en: "Current pool difficulty"},
        "current_pool_target": {de: "Aktuelles Pool-Ziel", en: "Current pool target"},
        "current_readings": {de: "Aktuelle Messwerte", en: "Current readings"},
        "current_values": {de: "Aktuelle Werte", en: "Current values"},
        "currently_unavailable": {de: "Momentan nicht erreichbar", en: "Currently unavailable"},
        "currently_unavailable_for_assignment": {
            de: "derzeit nicht zuweisbar",
            en: "currently unavailable for assignment"
        },
        "gpu_vendor_amd": {de: "AMD", en: "AMD"},
        "gpu_vendor_intel": {de: "Intel", en: "Intel"},
        "gpu_vendor_nvidia": {de: "NVIDIA", en: "NVIDIA"},
        "no_miner_compatible_with_this_gpu": {
            de: "Kein Miner ist mit dieser GPU kompatibel.",
            en: "No miner is compatible with this GPU."
        },
        "not_compatible_with_this_gpu": {de: "nicht mit dieser GPU kompatibel", en: "not compatible with this GPU"},
        "select_a_miner_compatible_with_this_gpu": {
            de: "Wähle einen Miner, der mit dieser GPU kompatibel ist.",
            en: "Choose a miner compatible with this GPU."
        },
        "custom_address": {de: "Eigene Adresse", en: "Custom address"},
        "custom_not_yet_catalogued_pool": {
            de: "Eigener/noch nicht katalogisierter Pool",
            en: "Custom / not yet catalogued pool"
        },
        "custom_pool": {de: "Eigener Pool", en: "Custom pool"},
        "custom_pool_address": {de: "Eigene Pool-Adresse", en: "Custom pool address"},
        "daily_earnings": {de: "Ertrag pro Tag", en: "Daily earnings"},
        "dashboard": {de: "Dashboard", en: "Dashboard"},
        "data_stale": {de: "Daten veraltet", en: "Data stale"},
        "default_destination": {de: "Standardziel", en: "Default destination"},
        "default_for_the_node": {de: "Standard für den Node", en: "Default for the Node"},
        "default_no_referrer": {de: "STANDARD · KEIN REFERRER", en: "DEFAULT · NO REFERRER"},
        "default_route": {de: "Standardroute", en: "Default route"},
        "define_node_profile": {de: "Node-Profil festlegen", en: "Define Node profile"},
        "derived_estimated": {de: "abgeleitet / geschätzt", en: "derived / estimated"},
        "dev_fee": {de: "Dev-Fee", en: "Dev fee"},
        "dev_fee_split": {de: "Aufteilung der Dev-Fee:", en: "Dev fee split:"},
        "dev_fee_split_currently_unavailable_check_th": {
            de: "Dev-Fee-Aufteilung gerade nicht abrufbar – Proxy-Verbindung prüfen.",
            en: "Dev fee split currently unavailable – check the proxy connection."
        },
        "developer_fee": {de: "Entwicklergebühr", en: "Developer fee"},
        "device": {de: "Gerät", en: "Device"},
        "device_identity": {de: "Gerätekennung", en: "Device identity"},
        "device_permission_is_disabled_in_hardware": {
            de: "Gerätefreigabe ist in Hardware deaktiviert.",
            en: "Device permission is disabled in Hardware."
        },
        "device_was_not_detected": {de: "Gerät wurde nicht erkannt", en: "Device was not detected"},
        "devices": {de: "GERÄTE", en: "DEVICES"},
        "devices_4425de": {de: "Geräte", en: "Devices"},
        "devices_assigned_to_the_node": {de: "Geräte für den Node freigegeben", en: "Devices assigned to the Node"},
        "diagnostics": {de: "Diagnose", en: "Diagnostics"},
        "direct_measurement": {de: "direkte Messung", en: "direct measurement"},
        "disabled": {de: "deaktiviert", en: "disabled"},
        "disabled_139955": {de: "AUSGESCHALTET", en: "DISABLED"},
        "discard": {de: "Verwerfen", en: "Discard"},
        "disconnected": {de: "getrennt", en: "disconnected"},
        "discovery_failed_error": {de: "Suche fehlgeschlagen: {error}", en: "Discovery failed: {error}"},
        "display_currency": {de: "Anzeigewährung", en: "Display currency"},
        "documented_local_api": {de: "Dokumentierte lokale API", en: "Documented local API"},
        "don_t_share": {de: "Nicht teilen", en: "Don't share"},
        "done_total_devices_complete_phase": {
            de: "{done}/{total} Geräte abgeschlossen · {phase}",
            en: "{done}/{total} devices complete · {phase}"
        },
        "download_and_install_a_suitable_miner": {
            de: "Passenden Miner herunterladen und installieren.",
            en: "Download and install a suitable miner."
        },
        "download_full_log": {de: "Gesamtes Log herunterladen", en: "Download full log"},
        "download_full_log_d17e12": {de: "Vollständiges Log herunterladen", en: "Download full log"},
        "download_install": {de: "Herunterladen & installieren", en: "Download & install"},
        "download_log": {de: "Log herunterladen", en: "Download log"},
        "download_srbminer_again": {de: "SRBMiner erneut herunterladen", en: "Download SRBMiner again"},
        "draw_watts": {de: "Aufnahme {watts}", en: "Draw {watts}"},
        "driver_and_gpu_compatibility_required": {
            de: "Treiber- und GPU-Kompatibilität erforderlich",
            en: "Driver and GPU compatibility required"
        },
        "driver_did_not_confirm_the_power_cap": {
            de: "Treiber bestätigte den Power-Cap nicht",
            en: "Driver did not confirm the power cap"
        },
        "driver_did_not_confirm_the_power_limit": {
            de: "Treiber hat die Leistungsgrenze nicht bestätigt",
            en: "Driver did not confirm the power limit"
        },
        "driver_did_not_confirm_the_power_limit_step": {
            de: "Leistungsgrenze wurde nicht vom Treiber bestätigt; Schritt übersprungen",
            en: "Driver did not confirm the power limit; step skipped"
        },
        "driver_min_max_w": {de: "Treiber {min}–{max} W", en: "Driver {min}–{max} W"},
        "driver_or_user_limits_could_not_be_verified": {
            de: "Treibergrenzen oder Nutzergrenzen nicht sicher verifizierbar",
            en: "Driver or user limits could not be verified safely"
        },
        "driver_readback_does_not_match": {
            de: "Treiber-Readback stimmt nicht überein",
            en: "Driver readback does not match"
        },
        "driver_values_are_checked_before_every_chang": {
            de: "Treiberwerte werden vor jeder Änderung geprüft.",
            en: "Driver values are checked before every change."
        },
        "duration_percent_measured": {de: "{duration} · {percent} % gemessen", en: "{duration} · {percent}% measured"},
        "dynamic_power_control": {de: "Dynamische Leistungsregelung", en: "Dynamic power control"},
        "dynamic_power_control_is_disabled_enable_it": {
            de: "Dynamische Leistungsregelung ist ausgeschaltet. Aktiviere sie oben, um Grenzen zu bearbeiten.",
            en: "Dynamic power control is disabled. Enable it above to edit limits."
        },
        "each_software_package_appears_exactly_once_e": {
            de: "Jedes Softwarepaket erscheint genau einmal – auch wenn es mehrere Coins unterstützt.",
            en: "Each software package appears exactly once, even when it supports multiple coins."
        },
        "earnings": {de: "Ertrag", en: "Earnings"},
        "earnings_forecast": {de: "ERTRAGSPROGNOSE", en: "EARNINGS FORECAST"},
        "earnings_forecasts_appear_once_a_miner_is_co": {
            de: "Ertragsprognosen erscheinen, sobald ein Miner eingerichtet ist und Hashrate liefert.",
            en: "Earnings forecasts appear once a miner is configured and reports hashrate."
        },
        "earnings_per_kwh": {de: "Ertrag pro kWh", en: "Earnings per kWh"},
        "edit_miner": {de: "Miner anpassen", en: "Edit miner"},
        "edit_miner_bc7f5d": {de: "Miner anpassen →", en: "Edit miner →"},
        "edit_wallet_pool": {de: "Wallet & Pool bearbeiten →", en: "Edit wallet & pool →"},
        "efficiency": {de: "Effizienz", en: "Efficiency"},
        "efficiency_curves_were_not_uploaded_sharing": {
            de: "Effizienzkurven wurden nicht hochgeladen: Teilen ist deaktiviert.",
            en: "Efficiency curves were not uploaded: sharing is disabled."
        },
        "efficiency_sweep_in_progress": {de: "Effizienz-Sweep läuft", en: "Efficiency sweep in progress"},
        "efficiency_sweep_restarted_power_limits_are": {
            de: "Effizienz-Sweep neu gestartet. Leistungsgrenzen werden schrittweise gesenkt und nach dem Lauf wiederhergestellt.",
            en: "Efficiency sweep restarted. Power limits are lowered step by step and restored after the run."
        },
        "efficiency_sweep_resumed_with_completed_resu": {
            de: "Effizienz-Sweep wird mit den bereits abgeschlossenen Ergebnissen fortgesetzt.",
            en: "Efficiency sweep resumed with completed results."
        },
        "electricity_cost": {de: "Stromkosten", en: "Electricity cost"},
        "electricity_cost_today": {de: "Stromkosten heute", en: "Electricity cost today"},
        "electricity_rate_in_currency_per_kwh": {
            de: "Strompreis in {currency} pro kWh",
            en: "Electricity rate in {currency} per kWh"
        },
        "electricity_tariff": {de: "Stromtarif", en: "Electricity tariff"},
        "enable_1_gb_pages_for_randomx": {de: "1-GB-Pages für RandomX aktivieren", en: "Enable 1 GB pages for RandomX"},
        "enable_external_proxy": {de: "Externen Proxy aktivieren", en: "Enable external proxy"},
        "enable_global_remote_control_first_to_make_w": {
            de: "Aktiviere zuerst die globale Remote-Steuerung, um Worker freizugeben.",
            en: "Enable global remote control first to make workers available."
        },
        "enable_huge_pages_for_randomx": {de: "Huge Pages für RandomX aktivieren", en: "Enable Huge Pages for RandomX"},
        "enabled": {de: "aktiv", en: "enabled"},
        "enabled_cfd095": {de: "EINGESCHALTET", en: "ENABLED"},
        "end_to_end_route_remains_experimental": {
            de: "End-to-End-Route noch experimentell",
            en: "End-to-end route remains experimental"
        },
        "energy": {de: "ENERGIE", en: "ENERGY"},
        "energy_api": {de: "Energie-API:", en: "Energy API:"},
        "energy_data_could_not_be_loaded": {
            de: "Energiedaten konnten nicht geladen werden.",
            en: "Energy data could not be loaded."
        },
        "energy_journal": {de: "ENERGIE-JOURNAL", en: "ENERGY JOURNAL"},
        "energy_today": {de: "Energie heute", en: "Energy today"},
        "enter_a_valid_positive_electricity_rate": {
            de: "Bitte gib einen gültigen positiven Strompreis ein.",
            en: "Enter a valid positive electricity rate."
        },
        "enter_your_own_wallet_or_explicitly_confirm": {
            de: "Eigene Wallet eintragen oder das SolarMiner-Standardziel bewusst bestätigen.",
            en: "Enter your own wallet or explicitly confirm the SolarMiner standard destination."
        },
        "error": {de: "Fehler", en: "Error"},
        "estimated_daily_earnings": {de: "Voraussichtliche Earnings pro Tag", en: "Estimated daily earnings"},
        "estimated_for_you": {de: "Voraussichtlich für dich", en: "Estimated for you"},
        "etc_dev_fee_is_lower_on_polaris": {
            de: "ETC-Dev-Fee ist auf Polaris niedriger",
            en: "ETC dev fee is lower on Polaris"
        },
        "etchash_capable_gpu_and_driver": {de: "ETCHash-fähige GPU und Treiber", en: "ETCHash-capable GPU and driver"},
        "etchash_support": {de: "ETCHash-Unterstützung", en: "ETCHash support"},
        "ethereum_classic_saved_payout_to_the_solarmi": {
            de: "Ethereum Classic gespeichert: Auszahlung an das SolarMiner-Standardziel.",
            en: "Ethereum Classic saved: payout to the SolarMiner standard destination."
        },
        "europe": {de: "Europa", en: "Europe"},
        "exclude_installation_folder_in_defender": {
            de: "Installationsordner in Defender ausnehmen",
            en: "Exclude installation folder in Defender"
        },
        "exclude_this_installation_folder": {
            de: "Diesen Installationsordner ausnehmen",
            en: "Exclude this installation folder"
        },
        "existing_gpu_installer": {de: "Bereits verwendeter GPU-Installer", en: "Existing GPU installer"},
        "external_control_was_disabled_locally": {
            de: "Externe Steuerung wurde lokal deaktiviert",
            en: "External control was disabled locally"
        },
        "external_proxy": {de: "Externer Proxy", en: "External proxy"},
        "external_proxy_host": {de: "Externer Proxy-Host", en: "External proxy host"},
        "external_proxy_selected": {de: "Externer Proxy ausgewählt", en: "External proxy selected"},
        "external_solarminer_proxy": {de: "Externer SolarMiner-Proxy", en: "External SolarMiner proxy"},
        "failed": {de: "Fehlgeschlagen", en: "Failed"},
        "feature_disabled": {de: "Funktion wurde deaktiviert.", en: "Feature disabled."},
        "feature_enabled": {de: "Funktion wurde aktiviert.", en: "Feature enabled."},
        "fee_distribution": {de: "Fee-Verteilung", en: "Fee distribution"},
        "fee_distribution_changed_the_local_proxy_res": {
            de: "Fee-Verteilung geändert. Der lokale Proxy wurde neu gestartet und alle Miner wurden pausiert. Starte sie auf der Miner-Seite erneut.",
            en: "Fee distribution changed. The local proxy restarted and all miners were paused. Restart them on the miner page."
        },
        "fee_target_loaded": {de: "FEE-ZIEL GELADEN", en: "FEE TARGET LOADED"},
        "fee_target_missing": {de: "FEE-ZIEL FEHLT", en: "FEE TARGET MISSING"},
        "fee_targets_loaded": {de: "Fee-Ziele geladen", en: "Fee targets loaded"},
        "fees": {de: "GEBÜHREN", en: "FEES"},
        "filter_workers": {de: "Worker filtern", en: "Filter workers"},
        "find_proxy_on_network": {de: "Proxy im Netzwerk suchen", en: "Find proxy on network"},
        "first_confirm_the_payout_to_the_solarminer_s": {
            de: "Bestätige zuerst die Auszahlung an das SolarMiner-Standardziel.",
            en: "First confirm the payout to the SolarMiner standard destination."
        },
        "first_review_the_detection_name_and_file_und": {
            de: "Prüfe zuerst Erkennungsname und Datei unter Windows-Sicherheit → Viren- & Bedrohungsschutz → Schutzverlauf. Gib nur eine verifizierte Datei frei.",
            en: "First review the detection name and file under Windows Security → Virus & threat protection → Protection history. Allow only a verified file."
        },
        "forecast_day": {de: "PROGNOSE / TAG", en: "FORECAST / DAY"},
        "format_stratum_tcp_host_port_or_stratum_ssl": {
            de: "Format: stratum+tcp://host:port oder stratum+ssl://host:port",
            en: "Format: stratum+tcp://host:port or stratum+ssl://host:port"
        },
        "free_capacity": {de: "Freie Kapazität", en: "Free capacity"},
        "full_reference_curve": {de: "Vollständige Referenzkurve", en: "Full reference curve"},
        "full_device_curve": {de: "Vollständige GPU-Kurve", en: "Full GPU curve"},
        "global_node_automation_blocked": {
            de: "Node-Automatik global gesperrt.",
            en: "Global Node automation blocked."
        },
        "global_node_automation_can_also_block_all_de": {
            de: "Die globale Node-Automatik kann zusätzlich alle Freigaben sperren.",
            en: "Global Node automation can also block all device permissions."
        },
        "global_node_automation_enabled": {de: "Node-Automatik global erlaubt.", en: "Global Node automation enabled."},
        "global_permission": {de: "GLOBALE FREIGABE", en: "GLOBAL PERMISSION"},
        "go_to_mining_page": {de: "Zur Mining-Seite", en: "Go to mining page"},
        "gpu_and_pool_account_required": {de: "GPU und Poolkonto erforderlich", en: "GPU and pool account required"},
        "gpu_coin_configuration_is_missing": {
            de: "GPU-Coin-Konfiguration fehlt",
            en: "GPU coin configuration is missing"
        },
        "gpu_command_timed_out": {de: "GPU-Befehl hat das Zeitlimit überschritten", en: "GPU command timed out"},
        "gpu_driver_and_srbminer_compatibility_depend": {
            de: "Die GPU-, Treiber- und SRBMiner-Kompatibilität hängt von Modell, Version und Betriebssystem ab. Für diese Prüfung gibt es derzeit keine automatische Aktivierungsaktion.",
            en: "GPU, driver and SRBMiner compatibility depends on model, version and operating system. This check has no automatic activation action."
        },
        "gpu_miner": {de: "GPU-Miner", en: "GPU miner"},
        "gpu_miner_paused": {de: "GPU-Miner pausiert", en: "GPU miner paused"},
        "gpu_mining": {de: "GPU Mining", en: "GPU mining"},
        "gpu_power_limits_are_missing": {de: "GPU-Leistungsgrenzen fehlen", en: "GPU power limits are missing"},
        "gpu_power_limits_could_not_be_saved_or_driver_v": {
            de: "GPU-Leistungsgrenzen konnten nicht gespeichert oder vom Treiber verifiziert werden",
            en: "GPU power limits could not be saved or verified by the driver"
        },
        "gpu_power_limits_are_outside_the_current_dri": {
            de: "GPU-Leistungsgrenzen liegen außerhalb der aktuellen Treibergrenzen",
            en: "GPU power limits are outside the current driver range"
        },
        "gpu_power_limits_cannot_be_changed_during_a": {
            de: "GPU-Leistungsgrenzen können während eines Messlaufs nicht geändert werden",
            en: "GPU power limits cannot be changed during a benchmark run"
        },
        "gpu_power_limits_saved": {de: "{gpu}: Leistungsgrenzen gespeichert.", en: "{gpu}: Power limits saved."},
        "gpu_power_target": {de: "GPU-Leistungsziel", en: "GPU power target"},
        "gpu_power_target_applied": {de: "GPU-Leistungsziel übernommen.", en: "GPU power target applied."},
        "gpu_reports_no_hashrate_despite_pool_jobs_ch": {
            de: "GPU liefert trotz Pool-Jobs keine Hashrate; SRBMiner- und GPU-Treiber prüfen",
            en: "GPU reports no hashrate despite pool jobs; check SRBMiner and GPU drivers"
        },
        "gpu_temperature_unavailable_cancelled_for_sa": {
            de: "GPU-Temperatur nicht lesbar; Sicherheitshalber abgebrochen",
            en: "GPU temperature unavailable; cancelled for safety"
        },
        "gpu_tool_output_exceeds_32_kib": {
            de: "GPU-Tool-Ausgabe überschreitet 32 KiB",
            en: "GPU tool output exceeds 32 KiB"
        },
        "graphics_cards": {de: "GRAFIKKARTEN", en: "GRAPHICS CARDS"},
        "gross": {de: "brutto", en: "gross"},
        "gross_at_measured_worker_power": {
            de: "brutto · bei gemessener Workerleistung",
            en: "gross · at measured worker power"
        },
        "gross_before_pool_miner_and_solarminer_fees": {
            de: "Brutto · vor Pool-, Miner- und SolarMiner-Gebühren",
            en: "Gross · before pool, miner and SolarMiner fees"
        },
        "gross_estimate": {de: "Brutto-Schätzung", en: "Gross estimate"},
        "gross_forecast_from_current_hashrate_and_net": {
            de: "Brutto-Prognose aus aktueller Hashrate und Netzwerkdaten. Gebühren und Stromkosten sind noch nicht abgezogen; dies ist keine Pool-Gutschrift.",
            en: "Gross forecast from current hashrate and network data. Fees and electricity costs have not been deducted; this is not a pool credit."
        },
        "gross_projection_from_current_hashrate_and_n": {
            de: "Brutto-Prognose aus aktueller Hashrate und Netzwerkdaten. Gebühren und Stromkosten sind nicht abgezogen; dies ist keine Pool-Gutschrift.",
            en: "Gross projection from current hashrate and network data. Fees and electricity costs are not deducted; this is not a pool credit."
        },
        "hardware": {de: "Hardware", en: "Hardware"},
        "hardware_comparison": {de: "HARDWARE-VERGLEICH", en: "HARDWARE COMPARISON"},
        "hardware_count_samples": {de: "{hardware} · {count} Messpunkte", en: "{hardware} · {count} samples"},
        "hardware_monitor_failed": {de: "Hardware-Monitor fehlgeschlagen", en: "Hardware monitor failed"},
        "hardware_monitor_not_started": {
            de: "Hardware-Monitor noch nicht gestartet",
            en: "Hardware monitor not started"
        },
        "hardware_monitor_stopped": {de: "Hardware-Monitor angehalten", en: "Hardware monitor stopped"},
        "hardware_sensors": {de: "HARDWARE & SENSOREN", en: "HARDWARE & SENSORS"},
        "hardware_type": {de: "Hardwareart", en: "Hardware type"},
        "hashrate": {de: "Hashrate", en: "Hashrate"},
        "hashrate_by_algorithm": {de: "Hashrate je Algorithmus", en: "Hashrate by algorithm"},
        "hashrate_or_measured_watts_missing": {
            de: "Hashrate oder gemessene Watt fehlen",
            en: "Hashrate or measured watts missing"
        },
        "hashrate_or_power_missing": {de: "Hashrate oder Leistung fehlt", en: "Hashrate or power missing"},
        "hashrate_per_measured_worker_watt": {
            de: "Hashrate pro Watt gemessener Workerleistung",
            en: "Hashrate per measured worker watt"
        },
        "hashrate_per_watt": {de: "Hashrate pro Watt", en: "Hashrate per watt"},
        "highest_temperature": {de: "Höchste Temperatur", en: "Highest temperature"},
        "hong_kong": {de: "Hongkong", en: "Hong Kong"},
        "host_sensor": {de: "Host-Sensor", en: "Host sensor"},
        "hostname_or_ip_address_enabling_this_pauses": {
            de: "Hostname oder IP-Adresse. Aktivieren pausiert alle Miner.",
            en: "Hostname or IP address. Enabling this pauses all miners."
        },
        "hottest_device": {de: "Heißestes Gerät", en: "Hottest device"},
        "http_404_restart_or_update_the_pc_agent": {
            de: "HTTP 404 · PC-Agent neu starten oder aktualisieren.",
            en: "HTTP 404 · Restart or update the PC agent."
        },
        "huge_pages_are_disabled_in_xmrig_windows_app": {
            de: "Huge Pages sind in XMRig deaktiviert. Windows übernimmt Änderungen an Benutzerrechten bei einer neuen Anmeldung; vorhandene Sitzungen können das alte Recht bis dahin behalten.",
            en: "Huge Pages are disabled in XMRig. Windows applies user-right changes at the next sign-in; existing sessions may retain the old right until then."
        },
        "huge_pages_reserve_ram_while_mining_other_pr": {
            de: "Huge Pages halten RAM während des Minings fest. Dieser Speicher steht anderen Programmen und Windows dann nicht zur Verfügung; bei wenig freiem RAM kann das System langsamer reagieren. Beim Ausschalten entfernt SolarMiner nur ein Windows-Recht, das es selbst hinzugefügt hat.",
            en: "Huge Pages reserve RAM while mining. Other programs and Windows cannot use this memory; low available RAM may make the system respond more slowly. When disabled, SolarMiner removes only a Windows right that it added itself."
        },
        "i_confirm_without_my_own_wallet_my_entire_mi": {
            de: "Ich bestätige: Ohne eigene Wallet geht meine gesamte Mining-Auszahlung an das angezeigte SolarMiner-Standardziel. Die vollständigen Gebühren stehen unten.",
            en: "I confirm: without my own wallet, my entire mining payout goes to the displayed SolarMiner standard destination. The complete fees are listed below."
        },
        "i_confirm_without_my_own_wallet_my_entire_mi_606c12": {
            de: "Ich bestätige: Ohne eigene Wallet geht meine gesamte Mining-Auszahlung an das angezeigte SolarMiner-Standardziel. Die vollständigen Gebühren stehen unter Erweitert.",
            en: "I confirm: without my own wallet, my entire mining payout goes to the displayed SolarMiner standard destination. The full fees are listed under Advanced."
        },
        "identical_gpus_are_grouped_per_coin_and_algo": {
            de: "Jede GPU misst für jeden ausgewählten, geeigneten Coin ihre eigene vollständige Power-Kurve. Verschiedene Karten können gleichzeitig messen; dieselbe GPU wird nie doppelt belegt. Nach jedem Power-Wechsel erhält der Miner bis zu vier Minuten Zeit, eine gültige Hashrate zu melden, bevor ein Schritt als instabil gilt. Ein Ergebnis wird erst gerätespezifisch gespeichert, wenn Miner, Pool, Temperatur und Shares stabil bleiben. Nach dem Lauf werden Leistungsgrenzen und Miner-Zustand verifiziert wiederhergestellt. Während der Sweep läuft, bleiben abgeschlossene oder fehlgeschlagene Kurven mit ihren Messwerten aufgeklappt. Wartende Läufe sind in einer Gruppe zusammengefasst.",
            en: "Each GPU measures its own complete power curve for every selected eligible coin. Different cards can measure at the same time; the same GPU is never used twice at once. After every power change the miner gets up to four minutes to report a valid hashrate before a step counts as unstable. A result is stored for a device only after its miner, pool, temperature and shares remain stable. Power limits and miner state are verified and restored after the run. Finished or failed curves stay expanded with their measurements while the sweep is running. Waiting runs are grouped together."
        },
        "included_in_the_node_profile": {de: "Im Node-Profil enthalten.", en: "Included in the Node profile."},
        "included_with_the_pc_agent_a_single_pc_does": {
            de: "Im PC-Agent enthalten. Für einen einzelnen PC brauchst du keinen zusätzlichen Server.",
            en: "Included with the PC agent. A single PC does not need an additional server."
        },
        "install": {de: "Installieren", en: "Install"},
        "install_a_suitable_miner_configure_the_pool": {
            de: "Installiere einen passenden Miner, richte Pool und Auszahlung ein und starte anschließend bewusst.",
            en: "Install a suitable miner, configure the pool and payout, then start it when you are ready."
        },
        "install_compatible_miner_software": {
            de: "Kompatible Miner-Software installieren →",
            en: "Install compatible miner software →"
        },
        "install_compatible_software_in_the_miner_sof": {
            de: "Installiere zuerst eine kompatible Software im Bereich Miner-Software.",
            en: "Install compatible software in the Miner software section first."
        },
        "install_configure_and_control_miners": {
            de: "Miner installieren, konfigurieren und steuern",
            en: "Install, configure and control miners"
        },
        "install_faeb39": {de: "installieren", en: "install"},
        "install_miner": {de: "Miner installieren", en: "Install miner"},
        "install_miner_0f7094": {de: "Miner installieren →", en: "Install miner →"},
        "install_programs": {de: "Programme installieren", en: "Install programs"},
        "install_selection": {de: "Auswahl installieren", en: "Install selection"},
        "install_the_miner_using_first": {
            de: "Miner zuerst über + installieren.",
            en: "Install the miner using + first."
        },
        "install_this_miner": {de: "Diesen Miner installieren", en: "Install this miner"},
        "install_update_repair_or_remove_mining_progr": {
            de: "Mining-Programme installieren, aktualisieren, reparieren oder entfernen. Coins, Geräte und Starts ordnest du anschließend im Bereich Worker zu.",
            en: "Install, update, repair or remove mining programs. Assign coins, devices and start controls in the Worker section."
        },
        "installable": {de: "Installierbar", en: "Installable"},
        "installation": {de: "Installation", en: "Installation"},
        "installation_failed": {de: "Installation fehlgeschlagen", en: "Installation failed"},
        "installation_in_progress": {de: "Installation läuft …", en: "Installation in progress …"},
        "installation_started": {de: "Installation wurde gestartet.", en: "Installation started."},
        "installation_started_the_progress_appears_in": {
            de: "Installation gestartet. Der Fortschritt erscheint in der Miner-Liste.",
            en: "Installation started. The progress appears in the miner list."
        },
        "installation_status": {de: "Installationsstatus", en: "Installation status"},
        "installation_uses_the_verified_official_rele": {
            de: "Installation erfolgt aus der verifizierten offiziellen Release-Quelle.",
            en: "Installation uses the verified official release source."
        },
        "installed": {de: "INSTALLIERT", en: "INSTALLED"},
        "installed_lowercase": {de: "installiert", en: "installed"},
        "installed_and_ready": {de: "Installiert und einsatzbereit.", en: "Installed and ready."},
        "installed_d3615a": {de: "installiert", en: "installed"},
        "installed_ff9a99": {de: "Installiert", en: "Installed"},
        "installed_miners": {de: "Installierte Miner", en: "Installed miners"},
        "installing": {de: "Installiert …", en: "Installing …"},
        "installing_d0dbee": {de: "Wird installiert", en: "Installing"},
        "installing_f2e4e4": {de: "Wird installiert …", en: "Installing …"},
        "interrupted": {de: "Unterbrochen", en: "Interrupted"},
        "invalid_pool_address_format_stratum_tcp_host": {
            de: "Pool-Adresse ungültig. Format: stratum+tcp://host:port oder stratum+ssl://host:port",
            en: "Invalid pool address. Format: stratum+tcp://host:port or stratum+ssl://host:port"
        },
        "json": {de: "JSON ↗", en: "JSON ↗"},
        "json_endpoint": {de: "JSON-Schnittstelle ↗", en: "JSON endpoint ↗"},
        "kawpow_capable_gpu_and_driver": {de: "KAWPOW-fähige GPU und Treiber", en: "KAWPOW-capable GPU and driver"},
        "kawpow_support": {de: "KAWPOW-Unterstützung", en: "KAWPOW support"},
        "known_deductions": {de: "bekannte Abzüge", en: "known deductions"},
        "known_value": {de: "Bekannter Wert ≈", en: "Known value ≈"},
        "known_value_value": {de: "Bekannter Wert ≈ {value}", en: "Known value ≈ {value}"},
        "kryptex_balance_before_payout": {de: "Kryptex-Guthaben vor Auszahlung", en: "Kryptex balance before payout"},
        "kryptex_europe": {de: "Kryptex · Europa", en: "Kryptex · Europe"},
        "kryptex_north_america": {de: "Kryptex · Nordamerika", en: "Kryptex · North America"},
        "kryptex_qtc_pool_support": {de: "Kryptex QTC pool support", en: "Kryptex QTC pool support"},
        "last_known_state_checking_current_data": {
            de: "Letzter bekannter Stand · aktuelle Daten werden geprüft.",
            en: "Last known state · checking current data."
        },
        "last_known_state_checking_current_data_actio": {
            de: "Letzter bekannter Stand · Aktuelle Daten werden geprüft. Aktionen sind danach verfügbar.",
            en: "Last known state · Checking current data. Actions will be available afterwards."
        },
        "last_update": {de: "Letzter Stand", en: "Last update"},
        "latency": {de: "Latenz", en: "Latency"},
        "latency_8741f8": {de: "Ø Latenz", en: "Ø Latency"},
        "librehardwaremonitor_is_not_running_windows": {
            de: "LibreHardwareMonitor läuft nicht. Windows benötigt eine Administratorfreigabe, um den Hardware-Monitor neu zu starten.",
            en: "LibreHardwareMonitor is not running. Windows requires administrator approval to restart the hardware monitor."
        },
        "librehardwaremonitor_is_starting_with_window": {
            de: "LibreHardwareMonitor wird mit Windows-UAC-Rechten gestartet.",
            en: "LibreHardwareMonitor is starting with Windows UAC permissions."
        },
        "librehardwaremonitor_will_restart_after_wind": {
            de: "LibreHardwareMonitor wird nach Windows-UAC-Freigabe neu gestartet.",
            en: "LibreHardwareMonitor will restart after Windows UAC approval."
        },
        "limit_watts_w": {de: "Limit {watts} W", en: "Limit {watts} W"},
        "live_estimate": {de: "LIVE-SCHÄTZUNG", en: "LIVE ESTIMATE"},
        "live_full_history": {de: "Live · vollständiger Verlauf", en: "Live · full history"},
        "live_operation": {de: "LIVE-BETRIEB", en: "LIVE OPERATION"},
        "live_output": {de: "LIVE-AUSGABE", en: "LIVE OUTPUT"},
        "loaded_from_the_solarminer_fee_backend": {
            de: "Vom SolarMiner Fee-Backend geladen",
            en: "Loaded from the SolarMiner fee backend"
        },
        "loading": {de: "Lade …", en: "Loading …"},
        "loading_connection": {de: "Verbindung wird geladen …", en: "Loading connection …"},
        "loading_devices": {de: "Geräte werden geladen …", en: "Loading devices …"},
        "loading_energy_journal": {de: "Journal wird geladen", en: "Loading energy journal"},
        "loading_fee_model": {de: "Gebührenmodell wird geladen …", en: "Loading fee model …"},
        "loading_full_output": {de: "Lade vollständige Ausgabe …", en: "Loading full output …"},
        "loading_market_data": {de: "Marktdaten werden geladen", en: "Loading market data"},
        "loading_miners": {de: "Miner werden geladen …", en: "Loading miners …"},
        "loading_network_and_price_data": {
            de: "Netzwerk- und Preisdaten werden geladen",
            en: "Loading network and price data"
        },
        "loading_network_data": {de: "Netzwerkdaten werden geladen", en: "Loading network data"},
        "loading_node_profile": {de: "Lade Node-Profil …", en: "Loading Node profile …"},
        "loading_operational_data": {de: "Betriebsdaten werden geladen …", en: "Loading operational data …"},
        "loading_sensors": {de: "Sensoren werden geladen", en: "Loading sensors"},
        "loading_status": {de: "Status wird geladen …", en: "Loading status …"},
        "local_benchmarks_only": {de: "Nur lokal / Benchmarks", en: "Local / benchmarks only"},
        "local_control": {de: "Lokale Steuerung", en: "Local control"},
        "local_library": {de: "LOKALE BIBLIOTHEK", en: "LOCAL LIBRARY"},
        "local_mining_hub": {de: "LOKALE MINING-ZENTRALE", en: "LOCAL MINING HUB"},
        "local_mining_service": {de: "lokaler Mining-Dienst", en: "local mining service"},
        "local_mining_service_ready": {de: "Lokaler Mining-Dienst bereit", en: "Local mining service ready"},
        "local_only": {de: "Nur lokal", en: "Local only"},
        "local_proxy": {de: "Lokaler Proxy", en: "Local proxy"},
        "local_proxy_5d93d2": {de: "lokaler Proxy", en: "local proxy"},
        "local_proxy_is_not_running": {de: "Lokaler Proxy läuft nicht", en: "Local proxy is not running"},
        "local_proxy_selected": {de: "Lokaler Proxy ausgewählt", en: "Local proxy selected"},
        "local_sensor_api_disabled": {de: "Lokale Sensor-API deaktiviert", en: "Local sensor API disabled"},
        "local_sensor_api_unavailable": {de: "Lokale Sensor-API nicht erreichbar", en: "Local sensor API unavailable"},
        "local_service": {de: "Lokaler Dienst", en: "Local service"},
        "lower_the_power_limit_step_by_step_and_check": {
            de: "Power-Limit schrittweise senken und Stabilität prüfen",
            en: "Lower the power limit step by step and check stability"
        },
        "main_navigation": {de: "Hauptnavigation", en: "Main navigation"},
        "make_this_worker_available_to_the_node": {
            de: "Diesen Worker für Remote-Steuerung freigeben",
            en: "Make this worker available for remote control"
        },
        "manage_gpu_safety_limits_and_power_values": {
            de: "GPU-Schutzgrenzen und Leistungswerte verwalten",
            en: "Manage GPU safety limits and power values"
        },
        "manage_installation": {de: "Installation verwalten", en: "Manage installation"},
        "manage_miner_software": {de: "Miner-Software verwalten →", en: "Manage miner software →"},
        "manage_node_automation": {de: "Node-Automatisierung verwalten →", en: "Manage Node automation →"},
        "manage_pools": {de: "Pools verwalten →", en: "Manage pools →"},
        "manual_control": {de: "MANUELLE STEUERUNG", en: "MANUAL CONTROL"},
        "max_watts_w": {de: "Max. {watts} W", en: "Max. {watts} W"},
        "maximum_power": {de: "Maximale Leistung", en: "Maximum power"},
        "maximum_solarminer_power": {de: "maximale SolarMiner-Leistung", en: "maximum SolarMiner power"},
        "measurable_hashrate_missing": {de: "messbare Hashrate fehlt", en: "measurable hashrate missing"},
        "measure_current_miners": {de: "Aktuelle Miner messen", en: "Measure current miners"},
        "measure_running_miners": {de: "Laufende Miner messen", en: "Measure running miners"},
        "measure_your_miners_and_compare_them_with_pu": {
            de: "Miss die Leistung deiner Miner und vergleiche sie mit veröffentlichten Ergebnissen.",
            en: "Measure your miners and compare them with published results."
        },
        "measured_by_the_miner_api": {de: "Von der Miner-API gemessen", en: "Measured by the miner API"},
        "measured_by_the_miner_api_of_the_running_min": {
            de: "Gemessen von der Miner-API des laufenden Miners",
            en: "Measured by the miner API of the running miner"
        },
        "measured_power": {de: "Gemessene Leistung", en: "Measured power"},
        "measured_worker_power": {de: "Gemessene Worker-Leistung", en: "Measured worker power"},
        "measured_worker_power_7e2a91": {de: "gemessener Workerleistung", en: "measured worker power"},
        "measurement_coverage": {de: "Messabdeckung", en: "Measurement coverage"},
        "measurement_history_since_opening_this_page": {
            de: "Messverlauf seit Öffnen dieser Seite",
            en: "Measurement history since opening this page"
        },
        "measurement_started_you_can_cancel_it_at_any": {
            de: "Messlauf gestartet. Du kannst ihn jederzeit abbrechen.",
            en: "Measurement started. You can cancel it at any time."
        },
        "median": {de: "Median", en: "Median"},
        "microsoft_manage_exclusions": {de: "Microsoft: Ausnahmen verwalten ↗", en: "Microsoft: Manage exclusions ↗"},
        "microsoft_protection_history": {de: "Microsoft: Schutzverlauf ↗", en: "Microsoft: Protection history ↗"},
        "middle_east": {de: "Naher Osten", en: "Middle East"},
        "min_watts_w": {de: "Min. {watts} W", en: "Min. {watts} W"},
        "miner_api_unavailable": {de: "Miner-API nicht erreichbar", en: "Miner API unavailable"},
        "miner_console": {de: "Miner-Konsole", en: "Miner console"},
        "miner_console_worker": {de: "Miner-Konsole · {worker}", en: "Miner console · {worker}"},
        "miner_executable_missing": {de: "Miner-Binary fehlt", en: "Miner executable missing"},
        "miner_installed": {de: "Miner installiert", en: "Miner installed"},
        "miner_installed_set_up_a_pool_and_your_own_w": {
            de: "Miner installiert – Pool und eigene Wallet einrichten.",
            en: "Miner installed – set up a pool and your own wallet."
        },
        "miner_is_not_reporting_hashrate_yet": {
            de: "Miner liefert noch keine Hashrate",
            en: "Miner is not reporting hashrate yet"
        },
        "miner_is_open": {de: "Miner ist geöffnet", en: "Miner is open"},
        "miner_library": {de: "MINER-BIBLIOTHEK", en: "MINER LIBRARY"},
        "miner_library_45e996": {de: "Miner-Bibliothek", en: "Miner library"},
        "miner_opened": {de: "Miner geöffnet", en: "Miner opened"},
        "miner_or_host_sensor": {de: "Miner- oder Host-Sensor", en: "Miner or host sensor"},
        "miner_output": {de: "Miner-Ausgabe", en: "Miner output"},
        "miner_overview": {de: "MINER-ÜBERSICHT", en: "MINER OVERVIEW"},
        "miner_paused": {de: "Miner pausiert", en: "Miner paused"},
        "miner_paused_9a07ba": {de: "Miner pausiert.", en: "Miner paused."},
        "miner_process_exited": {de: "Miner-Prozess ausgefallen", en: "Miner process exited"},
        "miner_reports_no_hashrate_despite_pool_jobs": {
            de: "Miner liefert trotz Pool-Jobs keine Hashrate",
            en: "Miner reports no hashrate despite pool jobs"
        },
        "miner_worker_not_reported_within_startup_grace": {
            de: "Miner-Worker wird noch nicht gemeldet; keine Hashrate innerhalb der Startwartezeit",
            en: "Miner worker has not appeared; no hashrate was reported within the startup grace period"
        },
        "miner_running": {de: "Miner läuft", en: "Miner running"},
        "miner_sections": {de: "Miner-Bereiche", en: "Miner sections"},
        "miner_software": {de: "Miner-Software", en: "Miner software"},
        "miner_software_does_not_support_this_coin": {
            de: "Miner-Software unterstützt diesen Coin nicht",
            en: "Miner software does not support this coin"
        },
        "miner_software_is_not_ready": {
            de: "Miner-Software ist nicht einsatzbereit",
            en: "Miner software is not ready"
        },
        "miner_started": {de: "Miner gestartet.", en: "Miner started."},
        "miners": {de: "MINER", en: "MINERS"},
        "miners_448600": {de: "Miner", en: "Miners"},
        "minimum_power": {de: "Minimale Leistung", en: "Minimum power"},
        "minimum_solarminer_power": {de: "minimale SolarMiner-Leistung", en: "minimum SolarMiner power"},
        "mining": {de: "Mining", en: "Mining"},
        "mining_active": {de: "Mining aktiv", en: "Mining active"},
        "mining_connection": {de: "Mining-Verbindung", en: "Mining connection"},
        "mining_connection_a617f9": {de: "Mining-Verbindung:", en: "Mining connection:"},
        "mining_connection_disconnected": {de: "Mining-Verbindung getrennt", en: "Mining connection disconnected"},
        "mining_pool": {de: "Mining-Pool", en: "Mining pool"},
        "mining_pool_missing": {de: "Mining-Pool fehlt", en: "Mining pool missing"},
        "mining_pools": {de: "MINING-POOLS", en: "MINING POOLS"},
        "mining_proxy": {de: "MINING-PROXY", en: "MINING PROXY"},
        "mining_proxy_not_ready": {de: "Mining-Proxy nicht bereit", en: "Mining proxy not ready"},
        "mining_requires_a_reachable_proxy_the_listen": {
            de: "Ein erreichbarer Proxy ist Voraussetzung für Mining. Listener und Fee-Ziel werden für jeden Coin separat geprüft. Vorbereitete Coins erhalten dadurch keine Produktionsfreigabe.",
            en: "Mining requires a reachable proxy. The listener and fee target are checked separately for each coin. Prepared coins are not released by this action."
        },
        "mining_software": {de: "Miner-Programm", en: "Mining software"},
        "mining_software_b8eea6": {de: "Mining-Software", en: "Mining software"},
        "mining_status": {de: "Mining-Status", en: "Mining status"},
        "missing_readings_are_not_treated_as_zero_con": {
            de: "Fehlende Messwerte werden nicht als null Verbrauch gewertet.",
            en: "Missing readings are not treated as zero consumption."
        },
        "missing_sensor_intervals_are_not_estimated": {
            de: "Fehlende Sensorintervalle werden nicht geschätzt",
            en: "Missing sensor intervals are not estimated"
        },
        "monero_and_pearl_available": {de: "Monero und Pearl verfügbar", en: "Monero and Pearl available"},
        "monero_at_pool": {de: "Monero · im Pool", en: "Monero · at pool"},
        "monero_configuration_saved": {de: "Monero-Konfiguration gespeichert.", en: "Monero configuration saved."},
        "monero_dev_fee_target": {de: "Monero Dev-Fee-Ziel", en: "Monero dev-fee target"},
        "monero_route": {de: "Monero-Route", en: "Monero route"},
        "monero_runs_on_the_cpu_temperatures_come_fro": {
            de: "Monero läuft auf der CPU. Temperaturen stammen aus der lokalen Sensor-API, der Miner selbst meldet keine.",
            en: "Monero runs on the CPU. Temperatures come from the local sensor API; the miner itself reports none."
        },
        "monero_saved_payout_to_the_solarminer_defaul": {
            de: "Monero gespeichert: Auszahlung an das SolarMiner-Standardziel.",
            en: "Monero saved: payout to the SolarMiner default destination."
        },
        "monero_uses_the_cpu_one_device_is_always_act": {
            de: "Monero nutzt die CPU; ein Gerät ist immer aktiv.",
            en: "Monero uses the CPU; one device is always active."
        },
        "name_for_this_pc_in_the_pool_up_to_32_letter": {
            de: "Bezeichnung für diesen PC im Pool, maximal 32 Zeichen aus Buchstaben, Zahlen, _ und -.",
            en: "Name for this PC in the pool, up to 32 letters, digits, underscores or hyphens."
        },
        "name_of_this_agent": {de: "Name dieses Agents", en: "Name of this agent"},
        "name_removed_the_node_shows_solarminer_pc_ag": {
            de: "Name entfernt. Der Node zeigt wieder SolarMiner PC Agent.",
            en: "Name removed. The Node shows SolarMiner PC Agent again."
        },
        "network_proxy": {de: "Proxy im Netzwerk", en: "Network proxy"},
        "no_action_needed_the_readings_below_show_cur": {
            de: "Keine offene Aktion. Die Messwerte unten zeigen den laufenden Betrieb.",
            en: "No action needed. The readings below show current operation."
        },
        "catalog_unavailable": {de: "Miner-Katalog ist nicht erreichbar", en: "Miner catalog is unavailable"},
        "everything_is_within_expected_limits": {
            de: "Alles im erwarteten Bereich",
            en: "Everything is within expected limits"
        },
        "invalid_consent": {de: "Ungültige Freigabeantwort", en: "Invalid consent response"},
        "malformed_event": {de: "Ungültiges Live-Ereignis", en: "Malformed live event"},
        "sharing_unavailable": {de: "Freigabe ist nicht erreichbar", en: "Sharing is unavailable"},
        "no_coin_selected_open_the_miner_library": {
            de: "Kein Coin ausgewählt. Öffne die Miner-Bibliothek.",
            en: "No coin selected. Open the miner library."
        },
        "no_compatible_proxy_found_check_that_both_de": {
            de: "Kein kompatibler Proxy gefunden. Prüfe, ob beide Geräte im selben LAN sind und UDP 8091 sowie API-Port 8090 erreichbar sind.",
            en: "No compatible proxy found. Check that both devices are on the same LAN and UDP 8091 and API port 8090 are reachable."
        },
        "no_configured_gpu_detected": {de: "Keine konfigurierte GPU erkannt", en: "No configured GPU detected"},
        "no_controllable_gpu_detected": {de: "Keine regelbare GPU erkannt", en: "No controllable GPU detected"},
        "no_cpu_sensor_readings_available": {
            de: "Keine CPU-Sensorwerte verfügbar",
            en: "No CPU sensor readings available"
        },
        "no_difficulty_reported": {de: "Keine Difficulty gemeldet", en: "No difficulty reported"},
        "no_efficiency_profile_yet_start_a_test_to_fi": {
            de: "Noch kein Effizienz-Profil. Starte einen Test, um stabile Power-Limits zu ermitteln.",
            en: "No efficiency profile yet. Start a test to find stable power limits."
        },
        "no_efficiency_samples_available_to_upload": {
            de: "Keine Effizienz-Messpunkte zum Hochladen vorhanden.",
            en: "No efficiency samples available to upload."
        },
        "no_entries": {de: "Keine Einträge.", en: "No entries."},
        "no_external_host_saved": {de: "Kein externer Host gespeichert.", en: "No external host saved."},
        "no_gpu_detected": {de: "Keine GPU erkannt.", en: "No GPU detected."},
        "no_gpu_detected_check_the_drivers_and_the_po": {
            de: "Keine GPU erkannt. Prüfe Treiber und die Seite Leistungsgrenzen.",
            en: "No GPU detected. Check the drivers and the Power limits page."
        },
        "no_gpu_selected": {de: "Keine GPU ausgewählt", en: "No GPU selected"},
        "no_gpu_was_detected_by_the_driver": {
            de: "Keine GPU vom Treiber erkannt.",
            en: "No GPU was detected by the driver."
        },
        "no_gpus_were_detected": {de: "Es wurden keine GPUs erkannt.", en: "No GPUs were detected."},
        "no_graphics_card_detected": {de: "Keine Grafikkarte erkannt", en: "No graphics card detected"},
        "no_hashrate": {de: "Keine Hashrate", en: "No hashrate"},
        "no_hashrate_reported": {de: "Keine Hashrate gemeldet", en: "No hashrate reported"},
        "no_measurement_yet_choose_a_mode_above": {
            de: "Noch keine Messung. Wähle oben einen Messlauf.",
            en: "No measurement yet. Choose a mode above."
        },
        "no_miner": {de: "kein Miner", en: "no miner"},
        "no_miner_installed_yet": {de: "Noch kein Miner installiert", en: "No miner installed yet"},
        "no_miner_is_available_for_this_hardware_filt": {
            de: "Für diesen Hardware-Filter ist kein Miner verfügbar.",
            en: "No miner is available for this hardware filter."
        },
        "no_miner_is_running_the_miners_section_shows": {
            de: "Kein Miner läuft gerade. Angehaltene Miner zeigt der Bereich Miner.",
            en: "No miner is running. The Miners section shows paused miners."
        },
        "no_miner_matches_the_search": {de: "Kein Miner passt zur Suche.", en: "No miner matches the search."},
        "no_miner_output_yet": {de: "Noch keine Miner-Ausgabe", en: "No miner output yet"},
        "no_miner_running": {de: "kein Miner läuft", en: "no miner running"},
        "no_miner_software_matches_this_filter": {
            de: "Keine Miner-Software passt zu diesem Filter.",
            en: "No miner software matches this filter."
        },
        "no_mining_software_is_available_for_this_har": {
            de: "Für diesen Hardware-Filter ist keine Mining-Software verfügbar.",
            en: "No mining software is available for this hardware filter."
        },
        "no_mining_software_is_in_the_catalog_for_thi": {
            de: "Für diesen Coin ist keine Mining-Software im Katalog.",
            en: "No mining software is in the catalog for this coin."
        },
        "no_node_connected_local_mining_is_still_avai": {
            de: "Kein Node verbunden. Lokales Mining bleibt möglich.",
            en: "No Node connected. Local mining is still available."
        },
        "no_personal_payout": {de: "keine eigene Auszahlung", en: "no personal payout"},
        "no_pool_reported": {de: "Kein Pool gemeldet", en: "No pool reported"},
        "no_power_sensor_available": {de: "Kein Leistungssensor verfügbar", en: "No power sensor available"},
        "no_power_target_yet": {de: "Noch kein Leistungsziel", en: "No power target yet"},
        "no_proxy_found_you_can_enter_a_known_host_ma": {
            de: "Kein Proxy gefunden. Du kannst einen bekannten Host manuell eintragen.",
            en: "No proxy found. You can enter a known host manually."
        },
        "no_published_comparison_for_this_hardware_an": {
            de: "Noch kein veröffentlichter Vergleich für diese Hardware und diesen Algorithmus.",
            en: "No published comparison for this hardware and algorithm yet."
        },
        "no_route_available": {de: "Keine Route verfügbar", en: "No route available"},
        "no_saved_benchmark_observations_to_upload_ag": {
            de: "Keine gespeicherten Benchmark-Messwerte zum erneuten Hochladen vorhanden.",
            en: "No saved benchmark observations to upload again."
        },
        "no_sensors_match_this_filter": {
            de: "Keine Sensoren passen zu diesem Filter.",
            en: "No sensors match this filter."
        },
        "no_software_required": {de: "Keine Software erforderlich.", en: "No software required."},
        "no_solarminer_default_destination_is_availab": {
            de: "Kein SolarMiner-Standardziel erreichbar. Eigene Wallet angeben oder Proxy auf der Seite Verbindung prüfen.",
            en: "No SolarMiner default destination is available. Enter your own wallet or check the proxy on the Connection page."
        },
        "no_solarminer_standard_destination_is_availa": {
            de: "Kein SolarMiner-Standardziel erreichbar. Eigene Wallet angeben oder Proxy prüfen.",
            en: "No SolarMiner standard destination is available. Enter your own wallet or check the proxy."
        },
        "no_stable_power_limit_found": {
            de: "Kein stabiler Power-Limit-Wert gefunden.",
            en: "No stable power limit found."
        },
        "no_stable_value_found": {de: "Kein stabiler Wert gefunden", en: "No stable value found"},
        "no_suitable_benchmark_observations_to_upload": {
            de: "Keine geeigneten Benchmark-Messwerte zum Hochladen vorhanden.",
            en: "No suitable benchmark observations to upload."
        },
        "no_supported_gpu_detected": {de: "Keine unterstützte GPU erkannt.", en: "No supported GPU detected."},
        "no_valid_mining_readings_recorded_check_that": {
            de: "Keine gültigen Mining-Messwerte erfasst. Prüfe, ob Miner laufen und Hashrate liefern.",
            en: "No valid mining readings recorded. Check that miners are running and reporting hashrate."
        },
        "no_valid_samples": {de: "Keine gültigen Messpunkte", en: "No valid samples"},
        "no_wallet_configured_open_wallets": {
            de: "Noch keine Wallet hinterlegt · Wallets öffnen",
            en: "No wallet configured · Open wallets"
        },
        "no_worker_is_running_configure_hardware_in_w": {
            de: "Kein Worker läuft gerade. Richte Hardware im Bereich Worker ein oder starte eine vorhandene Zuweisung.",
            en: "No worker is running. Configure hardware in Workers or start an existing assignment."
        },
        "no_worker_is_running_find_assignments_and_pa": {
            de: "Kein Worker läuft gerade. Zuweisungen und angehaltene Geräte findest du im Bereich Worker.",
            en: "No worker is running. Find assignments and paused devices in the Workers section."
        },
        "no_worker_is_running_start_a_miner_manually": {
            de: "Aktuell läuft kein Worker. Starte einen Miner manuell oder lass den Node dein gespeichertes Profil steuern.",
            en: "No worker is running. Start a miner manually or let the Node control your saved profile."
        },
        "no_worker_matches_the_filters": {
            de: "Kein Worker passt zu den Filtern.",
            en: "No worker matches the filters."
        },
        "no_workers_configured_yet": {de: "Noch keine Worker eingerichtet.", en: "No workers configured yet."},
        "no_workers_match_the_filters": {
            de: "Keine Worker passen zu den Filtern.",
            en: "No workers match the filters."
        },
        "no_workers_yet": {de: "Noch keine Worker", en: "No workers yet"},
        "node_allowed": {de: "Node erlaubt", en: "Node allowed"},
        "node_assessment_available_decision_details_r": {
            de: "Node-Bewertung verfügbar. Details zur Entscheidung bleiben beim Node.",
            en: "Node assessment available. Decision details remain on the Node."
        },
        "node_automation": {de: "Node-Automatisierung", en: "Node automation"},
        "node_automation_c51f25": {de: "Node-Automatik", en: "Node automation"},
        "node_connected": {de: "Node verbunden", en: "Node connected"},
        "node_control": {de: "NODE-STEUERUNG", en: "NODE CONTROL"},
        "node_control_allowed_only_devices_in_this_pr": {
            de: "Node-Steuerung erlaubt. Nur Geräte aus diesem Profil werden automatisch verwendet.",
            en: "Node control allowed. Only devices in this profile are used automatically."
        },
        "node_control_disabled_you_can_still_pause_ru": {
            de: "Node-Steuerung ausgeschaltet. Laufende Miner kannst du weiterhin lokal pausieren.",
            en: "Node control disabled. You can still pause running miners locally."
        },
        "node_control_enabled_but_no_device_is_assign": {
            de: "Node-Steuerung erlaubt, aber kein Gerät freigegeben",
            en: "Node control enabled, but no device is assigned"
        },
        "node_control_is_temporarily_locked_during_a": {
            de: "Node-Steuerung ist während eines Benchmarks vorübergehend gesperrt",
            en: "Node control is temporarily locked during a benchmark"
        },
        "node_mining_is_currently_approved_as_economi": {
            de: "Node: Mining ist aktuell wirtschaftlich freigegeben.",
            en: "Node: mining is currently approved as economical."
        },
        "node_mining_is_currently_not_approved_as_eco": {
            de: "Node: Mining ist aktuell nicht wirtschaftlich freigegeben.",
            en: "Node: mining is currently not approved as economical."
        },
        "node_no_economic_assessment_yet": {
            de: "Node: Noch keine Wirtschaftlichkeitsbewertung.",
            en: "Node: no economic assessment yet."
        },
        "node_profile": {de: "NODE-PROFIL", en: "NODE PROFILE"},
        "node_profile_0a1814": {de: "Node-Profil", en: "Node profile"},
        "node_profile_unavailable": {de: "Node-Profil ist nicht verfügbar.", en: "Node profile unavailable."},
        "node_status_unavailable": {de: "Node-Status nicht verfügbar.", en: "Node status unavailable."},
        "node_tier": {de: "Node-Tarif", en: "Node tier"},
        "north_america": {de: "Nordamerika", en: "North America"},
        "not_applied_yet": {de: "Noch nicht angewendet.", en: "Not applied yet."},
        "not_available_yet": {de: "NOCH NICHT VERFÜGBAR", en: "NOT AVAILABLE YET"},
        "not_configured_yet": {de: "Noch nicht eingerichtet", en: "Not configured yet"},
        "not_connected": {de: "nicht verbunden", en: "not connected"},
        "not_detected": {de: "Nicht erkannt", en: "Not detected"},
        "not_enabled": {de: "nicht freigegeben", en: "not enabled"},
        "not_installed": {de: "nicht installiert", en: "not installed"},
        "not_installed_2b337f": {de: "Nicht installiert", en: "Not installed"},
        "not_installed_e95589": {de: "Noch nicht installiert", en: "Not installed"},
        "not_integrated": {de: "Nicht integriert", en: "Not integrated"},
        "not_integrated_yet": {de: "Noch nicht integriert", en: "Not integrated yet"},
        "not_available_on_this_system": {de: "Auf diesem System nicht verfügbar", en: "Not available on this system"},
        "not_loaded": {de: "NICHT GELADEN", en: "NOT LOADED"},
        "not_reported_by_the_miner_api": {de: "Nicht von der Miner-API gemeldet", en: "Not reported by the miner API"},
        "not_selected": {de: "Nicht ausgewählt", en: "Not selected"},
        "not_set_up": {de: "Nicht eingerichtet", en: "Not set up"},
        "not_started": {de: "Noch nicht gestartet", en: "Not started"},
        "not_technically_integrated_yet": {de: "Noch nicht technisch integriert", en: "Not technically integrated yet"},
        "note": {de: "Hinweis", en: "Note"},
        "notifications": {de: "Meldungen", en: "Notifications"},
        "nvidia_and_amd_rdna_gpu_path": {de: "NVIDIA and AMD RDNA GPU path", en: "NVIDIA and AMD RDNA GPU path"},
        "observes_miners_that_are_already_running_doe": {
            de: "Beobachtet Miner, die bereits laufen. Startet oder pausiert keine Worker. Ideal, um deine aktuelle Konfiguration zu prüfen.",
            en: "Observes miners that are already running. Does not start or pause workers. Use it to check your current configuration."
        },
        "off_by_default_turning_it_off_later_sends_a": {
            de: "Standardmäßig deaktiviert. Eine spätere Deaktivierung sendet eine Widerrufsmeldung.",
            en: "Off by default. Turning it off later sends a withdrawal notification."
        },
        "offline": {de: "Offline", en: "Offline"},
        "on_this_pc": {de: "AUF DIESEM PC", en: "ON THIS PC"},
        "on_this_pc_local_network": {de: "Auf diesem PC · lokal im Netzwerk", en: "On this PC · local network"},
        "on_your_network": {de: "IM NETZWERK", en: "ON YOUR NETWORK"},
        "one_package_can_support_multiple_coins_selec": {
            de: "Ein Paket kann mehrere Coins bereitstellen. Wähle alle gewünschten Miner und starte die Installation gemeinsam.",
            en: "One package can support multiple coins. Select every miner you want and start the installations together."
        },
        "one_program_can_serve_several_coins_multiple": {
            de: "Ein Programm kann mehrere Coins bedienen. Mehrere Installationen pro Coin sind möglich; gestartet wird je Coin das als aktiv markierte Programm.",
            en: "One program can serve several coins. Multiple installations per coin are possible; per coin, the marked program is the one that starts."
        },
        "online": {de: "Online", en: "Online"},
        "only_cpu_path_in_the_pc_agent": {de: "Nur CPU-Pfad im PC-Agent", en: "Only CPU path in the PC agent"},
        "only_devices_that_are_mining_right_now_a_gpu": {
            de: "Nur Geräte, die gerade mining. Ein GPU gehört immer zu genau einem laufenden Miner; angehaltene Miner zeigen hier keine Geräte, damit keine zusätzliche Kapazität vorgetäuscht wird.",
            en: "Only devices that are mining right now. A GPU always belongs to exactly one running miner; paused miners list no devices here, so no extra capacity is implied."
        },
        "only_if_you_reviewed_the_detection_and_the_s": {
            de: "Nur wenn du den Fund geprüft hast und derselbe Miner weiter blockiert wird: Du kannst Windows bitten, genau den Installationsordner auszunehmen. Alle Dateien in diesem Ordner werden dann nicht von Defender geprüft.",
            en: "Only if you reviewed the detection and the same miner is still blocked: you can ask Windows to exclude that exact installation folder. Defender will then not scan files in that folder."
        },
        "only_measurable_while_running": {de: "Nur im laufenden Betrieb messbar", en: "Only measurable while running"},
        "only_this_algorithm_history_since_opening_th": {
            de: "Nur dieser Algorithmus · Verlauf seit Seitenaufruf",
            en: "Only this algorithm · history since opening this page"
        },
        "only_while_miner_is_running": {de: "Nur bei laufendem Miner", en: "Only while miner is running"},
        "open": {de: "Öffnen", en: "Open"},
        "open_an_installed_miner_or_add_one_for_your": {
            de: "Öffne einen installierten Miner oder ergänze einen für deine CPU oder GPU.",
            en: "Open an installed miner or add one for your CPU or GPU."
        },
        "open_connection": {de: "Verbindung öffnen →", en: "Open connection →"},
        "open_documentation": {de: "Dokumentation öffnen ↗", en: "Open documentation ↗"},
        "open_hardware_telemetry": {de: "Hardware & Telemetrie öffnen →", en: "Open hardware & telemetry →"},
        "open_miner": {de: "Miner öffnen", en: "Open miner"},
        "open_miner_251fb2": {de: "Miner öffnen →", en: "Open miner →"},
        "open_mining": {de: "Mining öffnen →", en: "Open mining →"},
        "open_navigation": {de: "Navigation öffnen", en: "Open navigation"},
        "open_node_automation": {de: "Node-Automatisierung öffnen →", en: "Open Node automation →"},
        "open_node_profile": {de: "Node-Profil öffnen", en: "Open Node profile"},
        "open_node_profile_1b4679": {de: "Node-Profil öffnen →", en: "Open Node profile →"},
        "open_proxy_dashboard": {de: "Proxy-Dashboard öffnen ↗", en: "Open proxy dashboard ↗"},
        "open_the_related_miner": {de: "Den zugehörigen Miner öffnen", en: "Open the related miner"},
        "open_this_page_on_the_affected_pc_at_http_12": {
            de: "Öffne diese Seite auf dem betroffenen PC unter http://127.0.0.1:8084/mining.html, um die Defender-Freigabe lokal anzufordern.",
            en: "Open this page on the affected PC at http://127.0.0.1:8084/mining.html to request the Defender exclusion locally."
        },
        "open_windows_security_virus_threat_protectio": {
            de: "Öffne Windows-Sicherheit → Viren- & Bedrohungsschutz → Schutzverlauf. Prüfe den Erkennungsnamen und die Datei. Gib nur eine verifizierte Datei frei. Danach kannst du den Download erneut starten.",
            en: "Open Windows Security → Virus & threat protection → Protection history. Check the detection name and file. Allow only a verified file. Then retry the download."
        },
        "open_worker": {de: "Worker öffnen →", en: "Open worker →"},
        "operating_status": {de: "Betriebsstatus", en: "Operating status"},
        "operation": {de: "BETRIEB", en: "OPERATION"},
        "optimization": {de: "OPTIMIERUNG", en: "OPTIMIZATION"},
        "optional_connect_a_solarminer_node_for_rules": {
            de: "Optional: SolarMiner Node verbinden für Regeln, PV-Überschuss und Automatisierung.",
            en: "Optional: connect a SolarMiner Node for rules, PV surplus and automation."
        },
        "optional_linux": {de: "Optional · Linux", en: "Optional · Linux"},
        "defaults_to_the_node_name": {
            de: "Standardmäßig der Node-Name; ohne Namen Solarminer-PC-Agent",
            en: "Defaults to the Node name; without a name, Solarminer-PC-Agent"
        },
        "optional_sharing": {de: "FREIWILLIGES TEILEN", en: "OPTIONAL SHARING"},
        "overview": {de: "Übersicht", en: "Overview"},
        "overview_cb2732": {de: "Übersicht", en: "Overview"},
        "own_payout_pool_and_wallet_belong_together": {
            de: "Eigene Auszahlung: Pool und Wallet gehören zusammen.",
            en: "Own payout: pool and wallet belong together."
        },
        "own_payout_pool_and_wallet_must_match_each_o": {
            de: "Eigene Auszahlung: Pool und Wallet müssen zueinander passen.",
            en: "Own payout: pool and wallet must match each other."
        },
        "own_wallet": {de: "Eigene Wallet", en: "Own wallet"},
        "parallel_device_validation": {de: "Parallele Gerätevalidierung", en: "Parallel device validation"},
        "pause": {de: "Pausieren", en: "Pause"},
        "pause_gpu": {de: "GPU pausieren", en: "Pause GPU"},
        "pause_miner": {de: "Miner pausieren", en: "Pause miner"},
        "paused": {de: "Pausiert", en: "Paused"},
        "paused_39d7c0": {de: "pausiert", en: "paused"},
        "paused_manually": {de: "Manuell pausiert", en: "Paused manually"},
        "payout": {de: "AUSZAHLUNG", en: "PAYOUT"},
        "payout_a85663": {de: "Auszahlung", en: "Payout"},
        "payout_to_the_solarminer_standard_destinatio": {
            de: "Auszahlung an das SolarMiner-Standardziel",
            en: "Payout to the SolarMiner standard destination"
        },
        "payout_worker": {de: "Auszahlung & Worker", en: "Payout & worker"},
        "pearl_at_address": {de: "Pearl · auf Adresse", en: "Pearl · at address"},
        "pearl_at_pool": {de: "Pearl · im Pool", en: "Pearl · at pool"},
        "pearl_configuration_and_gpu_selection_saved": {
            de: "Pearl-Konfiguration und GPU-Auswahl gespeichert.",
            en: "Pearl configuration and GPU selection saved."
        },
        "pearl_configuration_is_missing": {de: "Pearl-Konfiguration fehlt", en: "Pearl configuration is missing"},
        "pearl_dev_fee_target": {de: "Pearl Dev-Fee-Ziel", en: "Pearl dev-fee target"},
        "pearl_route": {de: "Pearl-Route", en: "Pearl route"},
        "pearl_saved_payout_to_the_solarminer_default": {
            de: "Pearl gespeichert: Auszahlung an das SolarMiner-Standardziel.",
            en: "Pearl saved: payout to the SolarMiner default destination."
        },
        "per_gpu_control": {de: "Pro-GPU-Steuerung", en: "Per-GPU control"},
        "percent_measurement_coverage": {de: "{percent} % Messabdeckung", en: "{percent}% measurement coverage"},
        "performance_and_state": {de: "LEISTUNG UND ZUSTAND", en: "PERFORMANCE AND STATE"},
        "periodic_benchmark_upload_failed_the_agent_w": {
            de: "Regelmäßiger Benchmark-Upload fehlgeschlagen; der Agent versucht es beim nächsten Intervall erneut.",
            en: "Periodic benchmark upload failed; the agent will retry at the next interval."
        },
        "periodic_benchmark_upload_sent_successfully": {
            de: "Regelmäßiger Benchmark-Upload erfolgreich übermittelt.",
            en: "Periodic benchmark upload sent successfully."
        },
        "periodic_benchmarks": {de: "Regelmäßige Benchmarks", en: "Periodic benchmarks"},
        "periodic_upload": {de: "Regelmäßiger Upload", en: "Periodic upload"},
        "permission_saved_active_miners_will_be_repor": {
            de: "Freigabe gespeichert. Aktive Miner werden regelmäßig übertragen.",
            en: "Permission saved. Active miners will be reported periodically."
        },
        "permission_unavailable": {de: "Freigabe nicht verfügbar", en: "Permission unavailable"},
        "permissions_apply_only_while_global_remote_c": {
            de: "Die Freigaben gelten nur, wenn die globale Remote-Steuerung aktiviert ist. Lokale Start- und Pause-Aktionen bleiben davon unabhängig.",
            en: "Permissions apply only while global remote control is enabled. Local start and pause actions remain independent."
        },
        "phase_step_step_total": {de: "{phase} · Schritt {step}/{total}", en: "{phase} · step {step}/{total}"},
        "phase_waiting_for_the_first_valid_hashrate_s": {
            de: "{phase} · Warte auf die ersten gültigen Hashrate-Messpunkte …",
            en: "{phase} · Waiting for the first valid hashrate samples …"
        },
        "planned": {de: "Geplant", en: "Planned"},
        "platform": {de: "Plattform", en: "Platform"},
        "platform_tips": {de: "PLATTFORMTIPPS", en: "PLATFORM TIPS"},
        "please_approve_the_windows_security_prompt_t": {
            de: "Bitte bestätige die Windows-Sicherheitsabfrage. Der PC-Agent wartet auf die Administratorfreigabe.",
            en: "Please approve the Windows security prompt. The PC agent is waiting for administrator approval."
        },
        "pool": {de: "Pool", en: "Pool"},
        "pool_22feef": {de: "Pool:", en: "Pool:"},
        "pool_active_payout_to_solarminer_default": {
            de: "Pool aktiv · Auszahlung an SolarMiner-Standard",
            en: "Pool active · payout to SolarMiner default"
        },
        "pool_address": {de: "Pool-Adresse", en: "Pool address"},
        "pool_and_wallet_are_missing": {de: "Pool und Wallet fehlen", en: "Pool and wallet are missing"},
        "pool_and_wallet_configured": {de: "Pool und Wallet konfiguriert", en: "Pool and wallet configured"},
        "pool_balance_before_payout": {de: "Pool-Guthaben vor Auszahlung", en: "Pool balance before payout"},
        "pool_configuration_missing": {de: "Pool-Konfiguration fehlt", en: "Pool configuration missing"},
        "pool_configured": {de: "Pool eingerichtet", en: "Pool configured"},
        "pool_connected": {de: "Pool verbunden", en: "Pool connected"},
        "pool_connected_waiting_for_gpu_hashrate": {
            de: "Pool verbunden, warte auf GPU-Hashrate",
            en: "Pool connected; waiting for GPU hashrate"
        },
        "pool_contact": {de: "Pool-Kontakt", en: "Pool contact"},
        "pool_destination": {de: "Pool-Ziel", en: "Pool destination"},
        "pool_difficulty_is_not_reported": {
            de: "Pool-Difficulty wird nicht gemeldet",
            en: "Pool difficulty is not reported"
        },
        "pool_fee": {de: "Poolgebühr", en: "Pool fee"},
        "pool_fee_unknown_please_check_with_the_pool": {
            de: "Poolgebühr unbekannt – bitte beim Pool prüfen",
            en: "Pool fee unknown – please check with the pool"
        },
        "pool_for": {de: "Pool für", en: "Pool for"},
        "pool_job_or_fee_route_unavailable": {
            de: "Pool-Job oder Fee-Route nicht verfügbar",
            en: "Pool job or fee route unavailable"
        },
        "pool_latency": {de: "Pool-Latenz", en: "Pool latency"},
        "pool_missing": {de: "Pool fehlt", en: "Pool missing"},
        "pool_must_use_stratum_tcp_ssl_host_port": {
            de: "Pool muss stratum+tcp/ssl://host:port sein",
            en: "Pool must use stratum+tcp/ssl://host:port"
        },
        "pool_payout": {de: "Pool & Auszahlung", en: "Pool & payout"},
        "pools_and_connection": {de: "Pools und Verbindung", en: "Pools and connection"},
        "power": {de: "LEISTUNG", en: "POWER"},
        "power_4606f4": {de: "Leistung", en: "Power"},
        "power_and_energy_values_are_shown_only_when": {
            de: "Leistungs- und Energiewerte werden nur angezeigt, wenn der Agent einen echten Sensorwert erhält. Fehlende Messwerte werden nicht als null Verbrauch gewertet.",
            en: "Power and energy values are shown only when the agent receives a real sensor reading. Missing readings are not counted as zero consumption."
        },
        "power_cap_unavailable": {de: "Power-Cap nicht verfügbar", en: "Power cap unavailable"},
        "power_ce610e": {de: "Leistung —", en: "Power —"},
        "power_ceiling_not_reported": {de: "Leistungsobergrenze nicht gemeldet", en: "Power ceiling not reported"},
        "power_consumption": {de: "Stromverbrauch", en: "Power consumption"},
        "power_control_updated": {de: "Leistungsregelung aktualisiert.", en: "Power control updated."},
        "power_limit_efficiency_test": {de: "Power-Limit-Effizienztest", en: "Power limit efficiency test"},
        "power_limit_sweep": {de: "POWER-LIMIT-SWEEP", en: "POWER LIMIT SWEEP"},
        "power_limits": {de: "Leistungsgrenzen", en: "Power limits"},
        "power_limits_per_gpu": {de: "Leistungsgrenzen je GPU →", en: "Power limits per GPU →"},
        "power_limits_saved": {de: "Leistungsgrenzen gespeichert.", en: "Power limits saved."},
        "power_measurement_unavailable": {de: "Leistungsmessung nicht verfügbar", en: "Power measurement unavailable"},
        "power_target": {de: "LEISTUNGSZIEL", en: "POWER TARGET"},
        "prepared": {de: "VORBEREITET", en: "PREPARED"},
        "prepared_the_mining_route_is_not_released_ye": {
            de: "Vorbereitet. Mining-Route noch nicht freigegeben.",
            en: "Prepared. The mining route is not released yet."
        },
        "preparing_the_mining_proxy": {de: "Mining-Proxy wird vorbereitet", en: "Preparing the mining proxy"},
        "processor": {de: "PROZESSOR", en: "PROCESSOR"},
        "project": {de: "Projekt", en: "Project"},
        "project_page": {de: "Projektseite ↗", en: "Project page ↗"},
        "proxy": {de: "Proxy", en: "Proxy"},
        "proxy_log": {de: "Proxy-Log", en: "Proxy log"},
        "proxy_must_use_stratum_tcp_host_port": {
            de: "Proxy muss stratum+tcp://host:port sein",
            en: "Proxy must use stratum+tcp://host:port"
        },
        "proxy_reachable": {de: "Proxy erreichbar", en: "Proxy reachable"},
        "proxy_reachable_the_fee_route_is_checked_for": {
            de: "Proxy erreichbar. Die Fee-Route wird je Coin beim Start geprüft.",
            en: "Proxy reachable. The fee route is checked for each coin at startup."
        },
        "proxy_saved": {de: "Proxy gespeichert.", en: "Proxy saved."},
        "proxy_target_of_the_miner": {de: "Proxy-Ziel des Miners", en: "Proxy target of the miner"},
        "proxy_targets": {de: "Proxy-Ziele", en: "Proxy targets"},
        "proxy_tier_1": {de: "Proxy-Tarif · 1 %", en: "Proxy tier · 1%"},
        "proxy_unreachable": {de: "Proxy nicht erreichbar", en: "Proxy unreachable"},
        "proxy_unreachable_check_the_connection_befor": {
            de: "Proxy nicht erreichbar. Prüfe die Verbindung vor dem Start.",
            en: "Proxy unreachable. Check the connection before starting."
        },
        "quick_access": {de: "SCHNELLZUGRIFF", en: "QUICK ACCESS"},
        "quick_start": {de: "SCHNELLSTART", en: "QUICK START"},
        "random": {de: "ZUFÄLLIG", en: "RANDOM"},
        "random_3eb821": {de: "Zufällig", en: "Random"},
        "randomx_optimizations_and_huge_pages": {
            de: "RandomX-Optimierungen und Huge Pages",
            en: "RandomX optimizations and Huge Pages"
        },
        "rate": {de: "Tarif", en: "Rate"},
        "rate_price_currency_kwh": {de: "Tarif {price} {currency}/kWh", en: "Rate {price} {currency}/kWh"},
        "ravencoin_saved_payout_to_the_solarminer_sta": {
            de: "Ravencoin gespeichert: Auszahlung an das SolarMiner-Standardziel.",
            en: "Ravencoin saved: payout to the SolarMiner standard destination."
        },
        "raw_value": {de: "Rohwert:", en: "Raw value:"},
        "raw_value_value": {de: "Rohwert: {value}", en: "Raw value: {value}"},
        "readings": {de: "MESSWERTE", en: "READINGS"},
        "readings_since_the_page_loaded": {de: "Messwerte seit Seitenaufruf", en: "Readings since the page loaded"},
        "readings_update_continuously_comparison_appe": {
            de: "Messwerte werden laufend aktualisiert; der Vergleich erscheint nach Abschluss.",
            en: "Readings update continuously; comparison appears after completion."
        },
        "ready": {de: "Bereit", en: "Ready"},
        "ready_to_start": {de: "Bereit zum Starten", en: "Ready to start"},
        "ready_when_you_are": {de: "Bereit, wenn du es bist.", en: "Ready when you are."},
        "recommended": {de: "Empfohlen", en: "Recommended"},
        "reference_complete_waiting_for_a_free_gpu": {
            de: "Referenz fertig; wartet auf freie GPU",
            en: "Reference complete; waiting for a free GPU"
        },
        "reference_curve": {de: "Referenzkurve", en: "Reference curve"},
        "reference_gpu_did_not_produce_a_stable_start": {
            de: "Referenzkarte lieferte keinen stabilen Ausgangswert",
            en: "Reference GPU did not produce a stable starting point"
        },
        "referral_key": {de: "Referral-Key", en: "Referral key"},
        "referral_key_saved_locally_the_node_may_over": {
            de: "Referral-Key lokal gespeichert. Der Node kann ihn wieder überschreiben.",
            en: "Referral key saved locally. The Node may overwrite it."
        },
        "referrer_code_pct": {de: "Referrer {code}: {pct}", en: "Referrer {code}: {pct}"},
        "refresh": {de: "Aktualisieren", en: "Refresh"},
        "update_required": {de: "Update erforderlich", en: "Update required"},
        "update_srbminer": {de: "SRBMiner aktualisieren", en: "Update SRBMiner"},
        "srbminer_update_started": {de: "SRBMiner-Update gestartet.", en: "SRBMiner update started."},
        "update_srbminer_to_the_reviewed_version_your_mining": {de: "Aktualisiere SRBMiner auf die geprüfte Version. Deine Mining-Konfiguration bleibt erhalten.", en: "Update SRBMiner to the reviewed version. Your mining configuration is kept."},
        "refreshing": {de: "Aktualisiere …", en: "Refreshing …"},
        "rejected_shares_of_all_reported_shares": {
            de: "Abgelehnte Shares an allen gemeldeten Shares",
            en: "Rejected shares of all reported shares"
        },
        "rejection_rate": {de: "Ablehnungsquote", en: "Rejection rate"},
        "remote_control": {de: "Remote-Steuerung", en: "Remote control"},
        "remote_control_disabled_the_dev_fee_uses_the": {
            de: "Remote-Steuerung deaktiviert. Die Dev-Fee nutzt wieder den Proxy-Tarif von 1 %.",
            en: "Remote control disabled. The dev fee uses the 1% proxy tier again."
        },
        "remote_control_enabled_now_choose_the_availa": {
            de: "Remote-Steuerung aktiviert. Wähle nun die verfügbaren Worker aus.",
            en: "Remote control enabled. Now choose the available workers."
        },
        "remove_miner": {de: "Miner entfernen", en: "Remove miner"},
        "remove_miner_installation": {de: "Miner-Installation entfernen", en: "Remove miner installation"},
        "removes_the_shared_srbminer_installation_all": {
            de: "Entfernt die gemeinsame SRBMiner-Installation. Alle GPU-Miner (Pearl, Ravencoin, Ethereum Classic) werden vorher gestoppt; gespeicherte Pool- und Wallet-Daten bleiben erhalten.",
            en: "Removes the shared SRBMiner installation. All GPU miners (Pearl, Ravencoin, Ethereum Classic) are stopped first; saved pool and wallet data is kept."
        },
        "removes_xmrig_the_miner_is_paused_first_save": {
            de: "Entfernt XMRig. Der Miner wird vorher pausiert; gespeicherte Pool- und Wallet-Daten bleiben erhalten.",
            en: "Removes XMRig. The miner is paused first; saved pool and wallet data is kept."
        },
        "removing_the_miner_stops_it_your_pool_and_wa": {
            de: "Beim Entfernen wird der Miner angehalten. Deine Pool- und Wallet-Konfiguration bleibt gespeichert.",
            en: "Removing the miner stops it. Your pool and wallet configuration is kept."
        },
        "request_timed_out": {de: "Zeitüberschreitung", en: "Request timed out"},
        "requesting_windows_approval": {de: "Windows-Freigabe wird angefordert …", en: "Requesting Windows approval …"},
        "requirement": {de: "Voraussetzung", en: "Requirement"},
        "requirements_optimizations": {de: "Voraussetzungen & Optimierungen", en: "Requirements & optimizations"},
        "restart_from_scratch": {de: "Komplett neu starten", en: "Restart from scratch"},
        "restart_hardware_monitor": {de: "Hardware-Monitor neu starten", en: "Restart hardware monitor"},
        "restart_librehardwaremonitor": {de: "LibreHardwareMonitor neu starten", en: "Restart LibreHardwareMonitor"},
        "restart_required_the_setting_and_windows_per": {
            de: "Neustart erforderlich: Die Einstellung und Windows-Berechtigung sind eingerichtet. Starte Windows neu, damit Huge Pages für den PC-Agent wirksam werden.",
            en: "Restart required: the setting and Windows permission are configured. Restart Windows for Huge Pages to take effect for the PC agent."
        },
        "restart_required_the_setting_and_windows_per_ccef7b": {
            de: "Neustart erforderlich: Die Einstellung und Windows-Berechtigung sind eingerichtet. Starte Windows neu, damit Huge Pages wirksam werden.",
            en: "Restart required: the setting and Windows permission are configured. Restart Windows to make huge pages effective."
        },
        "restart_required_windows_configured_the_perm": {
            de: "Neustart erforderlich: Windows hat die Berechtigung eingerichtet. Starte den PC neu; danach wird Huge Pages hier als aktiv angezeigt.",
            en: "Restart required: Windows configured the permission. Restart your PC; Huge Pages will then appear as active here."
        },
        "restoring_live_connection": {
            de: "Live-Verbindung wird wiederhergestellt …",
            en: "Restoring live connection …"
        },
        "results": {de: "ERGEBNISSE", en: "RESULTS"},
        "resume_interrupted_sweep": {de: "Unterbrochenen Sweep fortsetzen", en: "Resume interrupted sweep"},
        "retry_after_review": {de: "Nach Prüfung erneut versuchen", en: "Retry after review"},
        "retry_failed": {de: "Erneuter Versuch fehlgeschlagen:", en: "Retry failed:"},
        "retry_failed_error": {de: "Erneuter Versuch fehlgeschlagen: {error}", en: "Retry failed: {error}"},
        "retry_upload": {de: "Upload erneut versuchen", en: "Retry upload"},
        "retrying": {de: "Erneuter Versuch läuft …", en: "Retrying …"},
        "reused_from_interrupted_sweep": {
            de: "Aus unterbrochenem Sweep übernommen",
            en: "Reused from interrupted sweep"
        },
        "review_your_hardware_and_choose_a_cpu_or_gpu": {
            de: "Hardware ansehen und einen CPU- oder GPU-Miner auswählen.",
            en: "Review your hardware and choose a CPU or GPU miner."
        },
        "risk": {de: "Risiko:", en: "Risk:"},
        "running": {de: "Läuft", en: "Running"},
        "running_84a579": {de: "· läuft", en: "· running"},
        "running_a9e2ac": {de: ", läuft", en: ", running"},
        "running_bf6628": {de: "läuft", en: "running"},
        "running_detected_components": {de: "Laufend / erkannte Komponenten", en: "Running / detected components"},
        "running_devices": {de: "Laufende Geräte", en: "Running devices"},
        "running_miners_node_permissions_and_connecti": {
            de: "Laufende Miner, Node-Freigabe und Verbindungsstatus auf einen Blick.",
            en: "Running miners, Node permissions and connection status at a glance."
        },
        "running_on_gpu_s": {de: "Läuft auf GPU(s)", en: "Running on GPU(s)"},
        "running_sessions": {de: "Laufende Sessions", en: "Running sessions"},
        "running_workers": {de: "Laufende Worker", en: "Running workers"},
        "running_workers_in_detail": {de: "Laufende Worker im Detail →", en: "Running workers in detail →"},
        "russia": {de: "Russland", en: "Russia"},
        "save": {de: "Speichern", en: "Save"},
        "save_assignment": {de: "Zuweisung speichern", en: "Save assignment"},
        "save_configuration": {de: "Konfiguration speichern", en: "Save configuration"},
        "save_locally": {de: "Lokal speichern", en: "Save locally"},
        "save_wallet": {de: "Wallet speichern", en: "Save wallet"},
        "saved_efficiency_profiles": {de: "Gespeicherte Effizienzprofile", en: "Saved efficiency profiles"},
        "saved_local_benchmark_profiles": {de: "Gespeicherte lokale Benchmark-Profile", en: "Saved local benchmark profiles"},
        "no_saved_local_benchmark_profiles_yet": {de: "Noch keine lokalen Benchmark-Profile gespeichert.", en: "No local benchmark profiles have been saved yet."},
        "could_not_load_saved_benchmark_profiles": {de: "Benchmark-Profile konnten nicht geladen werden: {error}", en: "Could not load benchmark profiles: {error}"},
        "could_not_load_efficiency_profiles": {de: "Effizienzprofile konnten nicht geladen werden: {error}", en: "Could not load efficiency profiles: {error}"},
        "profile_storage_diagnostic": {de: "Speicherdatei: {file} · Profile: {count} · Status: {state}", en: "Storage file: {file} · Profiles: {count} · Status: {state}"},
        "file_readable": {de: "Datei lesbar", en: "File readable"},
        "file_not_created_yet": {de: "Datei wurde noch nicht angelegt", en: "File has not been created yet"},
        "efficiency_curve_for_hardware": {de: "Effizienzkurve für {hardware}, {algorithm}", en: "Efficiency curve for {hardware}, {algorithm}"},
        "power_consumption_watts": {de: "Stromverbrauch (Watt)", en: "Power consumption (watts)"},
        "sweet_spot": {de: "Sweetspot", en: "Sweet spot"},
        "stable": {de: "stabil", en: "stable"},
        "unstable": {de: "instabil", en: "unstable"},
        "saved_limits_moving_the_sliders_does_not_app": {
            de: "Gespeicherte Grenzen. Verschieben ändert noch nichts.",
            en: "Saved limits. Moving the sliders does not apply changes."
        },
        "saved_node_control_is_disabled": {
            de: "Gespeichert. Node-Steuerung ist ausgeschaltet.",
            en: "Saved. Node control is disabled."
        },
        "saved_power_cap_could_not_yet_be_restored_af": {
            de: "Gespeicherter Power-Cap konnte nach dem letzten Abbruch noch nicht wiederhergestellt werden",
            en: "Saved power cap could not yet be restored after the last interruption"
        },
        "saved_the_solarminer_node_shows_this_name": {
            de: "Gespeichert. Der SolarMiner Node zeigt diesen Namen.",
            en: "Saved. The SolarMiner Node shows this name."
        },
        "saving": {de: "Speichern …", en: "Saving …"},
        "saving_failed": {de: "Speichern fehlgeschlagen:", en: "Saving failed:"},
        "saving_wallet": {de: "Wallet wird gespeichert …", en: "Saving wallet …"},
        "search_coin_pool_or_address": {de: "Coin, Pool oder Adresse suchen", en: "Search coin, pool or address"},
        "search_failed": {de: "Suche fehlgeschlagen:", en: "Search failed:"},
        "search_miner_or_coin": {de: "Miner oder Coin suchen", en: "Search miner or coin"},
        "search_network": {de: "Im Netzwerk suchen", en: "Search network"},
        "search_pools": {de: "Pools durchsuchen", en: "Search pools"},
        "search_program_coin_or_algorithm": {
            de: "Programm, Coin oder Algorithmus suchen",
            en: "Search program, coin or algorithm"
        },
        "search_sensor_unit_or_source": {de: "Sensor, Einheit oder Quelle suchen", en: "Search sensor, unit or source"},
        "search_sensors": {de: "Sensoren durchsuchen", en: "Search sensors"},
        "search_worker_device_coin_or_miner": {
            de: "Worker, Gerät, Coin oder Miner suchen",
            en: "Search worker, device, coin or miner"
        },
        "search_worker_device_or_coin": {de: "Worker, Gerät oder Coin suchen", en: "Search worker, device or coin"},
        "search_worker_miner_or_pool": {de: "Worker, Miner oder Pool suchen", en: "Search worker, miner or pool"},
        "searching_the_local_network": {de: "Suche im lokalen Netzwerk …", en: "Searching the local network …"},
        "see_your_running_workers_below_manual_starts": {
            de: "Die laufenden Worker findest du unten. Manuelle Starts und das Node-Profil lassen sich unabhängig steuern.",
            en: "See your running workers below. Manual starts and the Node profile are controlled independently."
        },
        "select": {de: "auswählen", en: "Select"},
        "select_at_least_one_detected_gpu": {
            de: "Wähle mindestens eine erkannte GPU.",
            en: "Select at least one detected GPU."
        },
        "select_at_least_one_gpu": {de: "Wähle mindestens eine GPU.", en: "Select at least one GPU."},
        "select_at_least_one_gpu_in_nvidia_0_or_amd_0": {
            de: "Mindestens eine GPU im Format NVIDIA:0 oder AMD:0 wählen",
            en: "Select at least one GPU in NVIDIA:0 or AMD:0 format"
        },
        "select_available": {de: "Verfügbare auswählen", en: "Select available"},
        "select_for_installation": {de: "für Installation auswählen", en: "select for installation"},
        "select_gpu_console": {de: "GPU-Konsole auswählen", en: "Select GPU console"},
        "select_gpus": {de: "GPUs auswählen", en: "Select GPUs"},
        "selected": {de: "AUSGEWÄHLT", en: "SELECTED"},
        "selected_devices_of_this_coin_with_pool_cont": {
            de: "Ausgewählte Geräte dieses Coins mit Pool-Kontakt",
            en: "Selected devices of this coin with pool contact"
        },
        "selected_worker": {de: "Ausgewählter Worker", en: "Selected worker"},
        "sensor_access_ready": {de: "Sensorzugriff bereit", en: "Sensor access ready"},
        "sensor_access_status": {de: "Sensorzugriff: {status}", en: "Sensor access: {status}"},
        "sensor_api_unavailable": {de: "Sensor-API nicht erreichbar", en: "Sensor API unavailable"},
        "sensor_details": {de: "Sensor-Details", en: "Sensor details"},
        "sensor_request_timed_out": {de: "Zeitüberschreitung beim Sensorabruf", en: "Sensor request timed out"},
        "sensors": {de: "SENSOREN", en: "SENSORS"},
        "sensors_7d5114": {de: "Sensoren", en: "Sensors"},
        "sensors_readings": {de: "Sensoren & Messwerte", en: "Sensors & readings"},
        "session": {de: "Session", en: "Session"},
        "session_energy": {de: "Session-Energie", en: "Session energy"},
        "session_history": {de: "SITZUNGSVERLAUF", en: "SESSION HISTORY"},
        "set_as_active_build": {de: "Als aktiven Build wählen", en: "Set as active build"},
        "set_centrally_whether_a_solarminer_node_may": {
            de: "Lege zentral fest, ob ein SolarMiner Node oder ein freigegebener PC-Agent diesen PC steuern darf und welche Worker dafür verfügbar sind.",
            en: "Set centrally whether a SolarMiner Node or a shared PC agent may control this PC and which workers are available."
        },
        "set_power_limits": {de: "Leistungsgrenzen einstellen →", en: "Set power limits →"},
        "set_up": {de: "Einrichten", en: "Set up"},
        "set_up_miner": {de: "Miner einrichten →", en: "Set up miner →"},
        "set_up_mining": {de: "Mining einrichten", en: "Set up mining"},
        "set_up_wallets": {de: "Wallets einrichten →", en: "Set up wallets →"},
        "set_up_worker": {de: "WORKER EINRICHTEN", en: "SET UP WORKER"},
        "set_your_wallet_pool_destination_and_worker": {
            de: "Hinterlege pro Coin deine Wallet, dein Pool-Ziel und einen Worker-Namen.",
            en: "Set your wallet, pool destination and worker name for each coin."
        },
        "setup": {de: "Einrichtung", en: "Setup"},
        "setup_incomplete": {de: "Einrichtung unvollständig", en: "Setup incomplete"},
        "setup_required": {de: "Einrichtung erforderlich", en: "Setup required"},
        "setup_required_choose_the_pool_wallet_and_th": {
            de: "Einrichtung fehlt: Pool, Wallet und dieses Gerät auswählen.",
            en: "Setup required: choose the pool, wallet and this device."
        },
        "share_anonymous_benchmarks": {de: "Anonyme Benchmarks teilen", en: "Share anonymous benchmarks"},
        "share_anonymous_performance_data": {
            de: "Anonyme Leistungswerte teilen",
            en: "Share anonymous performance data"
        },
        "share_results_with_solarminer": {
            de: "Ergebnisse mit SolarMiner teilen?",
            en: "Share results with SolarMiner?"
        },
        "shared_gpu_installer": {de: "Gemeinsamer GPU-Installer", en: "Shared GPU installer"},
        "shares_accepted": {de: "Shares akzeptiert", en: "Shares accepted"},
        "shares_this_session": {de: "Shares dieser Session", en: "Shares this session"},
        "sharing_disabled_withdrawal_will_be_sent_at": {
            de: "Freigabe deaktiviert. Der Widerruf wird beim nächsten Versandintervall übermittelt.",
            en: "Sharing disabled. Withdrawal will be sent at the next reporting interval."
        },
        "singapore": {de: "Singapur", en: "Singapore"},
        "skip_to_content": {de: "Zum Inhalt springen", en: "Skip to content"},
        "skipped": {de: "Übersprungen", en: "Skipped"},
        "software": {de: "SOFTWARE", en: "SOFTWARE"},
        "software_removed": {de: "Software wurde entfernt.", en: "Software removed."},
        "solarminer_and_referrer_shares_are_loaded_li": {
            de: "SolarMiner- und Referrer-Anteile kommen live vom Fee-Backend. Pool- und Miner-Gebühren werden je Coin offen ausgewiesen.",
            en: "SolarMiner and referrer shares are loaded live from the fee backend. Pool and miner fees are shown openly for each coin."
        },
        "solarminer_benchmarks": {de: "SolarMiner · Benchmarks", en: "SolarMiner · Benchmarks"},
        "solarminer_connection": {de: "SolarMiner · Verbindung", en: "SolarMiner · Connection"},
        "solarminer_dashboard": {de: "SolarMiner · Dashboard", en: "SolarMiner · Dashboard"},
        "solarminer_default_destination_no_personal_p": {
            de: "SolarMiner-Standardziel · ohne eigene Auszahlung",
            en: "SolarMiner default destination · no personal payout"
        },
        "solarminer_default_no_personal_payout": {
            de: "SolarMiner-Standard · ohne eigene Auszahlung",
            en: "SolarMiner default · no personal payout"
        },
        "solarminer_default_payout_will_be_checked_at": {
            de: "SolarMiner-Standardziel wird beim Start geprüft",
            en: "SolarMiner default payout will be checked at startup"
        },
        "solarminer_default_route": {de: "SolarMiner-Standardroute", en: "SolarMiner default route"},
        "solarminer_enables_the_xmrig_option_on_windo": {
            de: "SolarMiner schaltet die XMRig-Option ein. Unter Windows fragt der Agent per UAC nach der Berechtigung für dein Benutzerkonto; nach einer neuen Anmeldung wird das Recht wirksam.",
            en: "SolarMiner enables the XMRig option. On Windows, the agent requests permission for your account through UAC; the right takes effect after you sign in again."
        },
        "solarminer_enables_the_xmrig_option_xmrig_ma": {
            de: "SolarMiner schaltet die XMRig-Option ein. XMRig benötigt dafür bis zu 3 GB Speicher pro NUMA-Knoten; die Nutzung hängt zusätzlich von Kernel und verfügbarer Speicherkonfiguration ab.",
            en: "SolarMiner enables the XMRig option. XMRig may need up to 3 GB per NUMA node; actual use also depends on the kernel and available memory configuration."
        },
        "solarminer_fee_target_not_provisioned_yet": {
            de: "SolarMiner-Fee-Ziel noch nicht provisioniert",
            en: "SolarMiner fee target not provisioned yet"
        },
        "solarminer_miners": {de: "SolarMiner · Miner", en: "SolarMiner · Miners"},
        "solarminer_mining": {de: "SolarMiner · Mining", en: "SolarMiner · Mining"},
        "solarminer_node_automation": {de: "SolarMiner · Node-Automatisierung", en: "SolarMiner · Node automation"},
        "solarminer_node_connected": {de: "SolarMiner Node verbunden", en: "SolarMiner Node connected"},
        "solarminer_node_is_connected": {de: "SolarMiner Node ist verbunden.", en: "SolarMiner Node is connected."},
        "solarminer_node_not_connected": {de: "SolarMiner Node nicht verbunden", en: "SolarMiner Node not connected"},
        "solarminer_pc_agent": {de: "SolarMiner PC-Agent ·", en: "SolarMiner PC-Agent ·"},
        "solarminer_pct": {de: "SolarMiner: {pct}", en: "SolarMiner: {pct}"},
        "solarminer_power_limits": {de: "SolarMiner · Hardware", en: "SolarMiner · Power limits"},
        "solarminer_proxy_for_monero_is_missing_conne": {
            de: "SolarMiner-Proxy für Monero fehlt. Verbinde zuerst den Proxy.",
            en: "SolarMiner proxy for Monero is missing. Connect the proxy first."
        },
        "solarminer_proxy_for_pearl_is_missing_connec": {
            de: "SolarMiner-Proxy für Pearl fehlt. Verbinde zuerst den Proxy.",
            en: "SolarMiner proxy for Pearl is missing. Connect the proxy first."
        },
        "solarminer_proxy_reachable": {de: "SolarMiner-Proxy erreichbar", en: "SolarMiner proxy reachable"},
        "solarminer_proxy_route_does_not_match": {
            de: "SolarMiner-Proxy-Route stimmt nicht",
            en: "SolarMiner proxy route does not match"
        },
        "solarminer_proxy_unavailable": {de: "SolarMiner-Proxy nicht erreichbar", en: "SolarMiner proxy unavailable"},
        "solarminer_qtc_fee_route_and_accepted_share": {
            de: "SolarMiner QTC fee route and accepted-share credits are not yet verified",
            en: "SolarMiner QTC fee route and accepted-share credits are not yet verified"
        },
        "solarminer_sensors": {de: "SolarMiner · Telemetrie", en: "SolarMiner · Sensors"},
        "solarminer_wallets": {de: "SolarMiner · Wallets", en: "SolarMiner · Wallets"},
        "solarminer_workers": {de: "SolarMiner · Worker", en: "SolarMiner · Workers"},
        "south_america": {de: "Südamerika", en: "South America"},
        "specialized_for_amd_gpus": {de: "Auf AMD-GPUs spezialisiert", en: "Specialized for AMD GPUs"},
        "srbminer_download_started": {de: "SRBMiner-Download gestartet.", en: "SRBMiner download started."},
        "srbminer_has_an_additional_2_miner_fee": {
            de: "SRBMiner hat eine zusätzliche Miner-Gebühr von 2 %.",
            en: "SRBMiner has an additional 2% miner fee."
        },
        "srbminer_multi_developer_fee": {de: "SRBMiner-MULTI Entwicklergebühr", en: "SRBMiner-MULTI developer fee"},
        "srbminer_multi_etchash_developer_fee": {
            de: "SRBMiner-MULTI ETCHash Entwicklergebühr",
            en: "SRBMiner-MULTI ETCHash developer fee"
        },
        "srbminer_multi_kawpow_developer_fee": {
            de: "SRBMiner-MULTI KAWPOW Entwicklergebühr",
            en: "SRBMiner-MULTI KAWPOW developer fee"
        },
        "srbminer_must_detect_the_selected_gpu_for_et": {
            de: "SRBMiner muss die gewählte GPU für ETCHash erkennen. Prüfe Geräte und Initialisierungsfehler in der Miner-Konsole; der Agent ändert keine Treiber oder Taktraten.",
            en: "SRBMiner must detect the selected GPU for ETCHash. Check devices and initialization errors in its console; the agent does not change drivers or clock rates."
        },
        "srbminer_must_detect_the_selected_gpu_for_ka": {
            de: "SRBMiner muss die gewählte GPU für KAWPOW erkennen. Prüfe Geräte und Initialisierungsfehler in der Miner-Konsole; der Agent ändert keine Treiber oder Taktraten.",
            en: "SRBMiner must detect the selected GPU for KAWPOW. Check devices and initialization errors in its console; the agent does not change drivers or clock rates."
        },
        "srbminer_removed_the_pearl_configuration_rem": {
            de: "SRBMiner entfernt; die Pearl-Konfiguration bleibt gespeichert.",
            en: "SRBMiner removed; the Pearl configuration remains saved."
        },
        "srbminer_reports_detected_devices_and_initia": {
            de: "SRBMiner meldet erkannte Geräte und Initialisierungsfehler in seiner Konsole. Der PC-Agent zeigt die Miner-Ausgabe in dieser Ansicht.",
            en: "SRBMiner reports detected devices and initialization errors in its console. This view shows the miner output."
        },
        "stale_data": {de: "DATEN VERALTET", en: "STALE DATA"},
        "start": {de: "Starten", en: "Start"},
        "start_a_benchmark_run_to_see_results": {
            de: "Starte einen Messlauf, um Ergebnisse anzuzeigen.",
            en: "Start a benchmark run to see results."
        },
        "start_benchmark": {de: "Benchmark starten", en: "Start benchmark"},
        "start_cpu": {de: "CPU starten", en: "Start CPU"},
        "start_gpu": {de: "GPU starten", en: "Start GPU"},
        "start_group_number": {de: "Startgruppe {number}", en: "Start group {number}"},
        "start_librehardwaremonitor": {de: "LibreHardwareMonitor starten", en: "Start LibreHardwareMonitor"},
        "start_locally_add_a_node_later": {
            de: "Lokal starten – Node später hinzufügen",
            en: "Start locally – add a Node later"
        },
        "start_miner": {de: "Miner starten", en: "Start miner"},
        "start_power_limit_test": {de: "Power-Limit-Test starten", en: "Start power-limit test"},
        "status": {de: "STATUS", en: "STATUS"},
        "status_bae7d5": {de: "Status", en: "Status"},
        "status_devices": {de: "Status & Geräte", en: "Status & devices"},
        "stays_local_for_the_node_0_w": {de: "Bleibt lokal. Für den Node: 0 W.", en: "Stays local. For the Node: 0 W."},
        "stopped": {de: "Gestoppt", en: "Stopped"},
        "sum_of_measured_worker_power": {de: "Summe gemessener Workerleistung", en: "Sum of measured worker power"},
        "sweep_failed": {de: "Sweep fehlgeschlagen", en: "Sweep failed"},
        "switch": {de: "wechseln?", en: "switch?"},
        "switch_pool": {de: "POOL ZWECHSELN", en: "SWITCH POOL"},
        "switching_pauses_all_miners_restart_them_whe": {
            de: "Ein Wechsel pausiert alle Miner. Starte sie anschließend bewusst neu.",
            en: "Switching pauses all miners. Restart them when you are ready."
        },
        "switching_pool": {de: "Poolwechsel läuft …", en: "Switching pool …"},
        "switching_restarts_the_local_proxy_and_pause": {
            de: "Ein Wechsel startet den lokalen Proxy neu und pausiert alle Miner. Gilt nur für den lokalen Proxy; ein externer Proxy behält seine eigene Einstellung.",
            en: "Switching restarts the local proxy and pauses all miners. It applies to the local proxy only; an external proxy keeps its own setting."
        },
        "system": {de: "SYSTEM", en: "SYSTEM"},
        "target_12_observations_per_worker": {
            de: "Ziel: 12 Messpunkte je Worker",
            en: "Target: 12 observations per worker"
        },
        "target_not_selected_yet": {de: "Ziel noch nicht gewählt", en: "Target not selected yet"},
        "telemetry": {de: "Telemetrie", en: "Telemetry"},
        "temperature": {de: "Temperatur", en: "Temperature"},
        "temperature_2500e7": {de: "Temperatur —", en: "Temperature —"},
        "temperatures_labelled_host_sensor_come_from": {
            de: "Temperaturen mit der Angabe „Host-Sensor“ stammen von der lokalen Sensor-API, nicht vom Miner selbst.",
            en: "Temperatures labelled “Host-Sensor” come from the local sensor API, not from the miner itself."
        },
        "temperatures_labelled_host_sensor_come_from_281229": {
            de: "Temperaturen mit der Angabe „Host-Sensor“ stammen von der lokalen Sensor-API, nicht vom Miner selbst. Difficulty, Latenz und veraltete Shares meldet nur die Miner-API des laufenden Miners.",
            en: "Temperatures labelled “Host-Sensor” come from the local sensor API, not from the miner itself. Difficulty, latency and stale shares are reported only by the miner API of the running miner."
        },
        "test_installed_miners": {de: "Installierte Miner testen", en: "Test installed miners"},
        "tests_configured_miners_in_sequence_running": {
            de: "Jede ausgewählte GPU testet jeden für sie geeigneten Coin selbst; mehrere Karten messen zugleich. Laufende verwaltete Worker werden dafür vorübergehend pausiert und anschließend wiederhergestellt. Die Node-Steuerung ist währenddessen gesperrt.",
            en: "Each selected GPU tests every coin it supports; multiple cards measure at the same time. Running managed workers are temporarily paused and then restored. Node control is blocked during the test."
        },
        "the_agent_keeps_no_measurement_history_the_c": {
            de: "Der Agent hält keine Messhistorik vor; der Verlauf beginnt mit diesem Aufruf.",
            en: "The agent keeps no measurement history; the chart starts with this page load."
        },
        "the_agent_rejected_the_change": {
            de: "Der Agent hat die Änderung abgelehnt.",
            en: "The agent rejected the change."
        },
        "the_agent_rejected_the_connection_change_che": {
            de: "Der Agent hat die Verbindungsänderung abgelehnt. Prüfe Sensorfreigabe, Proxy und Agent-Logs.",
            en: "The agent rejected the connection change. Check sensor permissions, the proxy and agent logs."
        },
        "the_anonymous_upload_could_not_be_started": {
            de: "Der anonyme Upload konnte nicht gestartet werden.",
            en: "The anonymous upload could not be started."
        },
        "the_anonymous_upload_failed_you_can_retry_it": {
            de: "Der anonyme Upload ist fehlgeschlagen. Du kannst ihn über „Upload erneut versuchen“ wiederholen.",
            en: "The anonymous upload failed. You can retry it via “Try upload again”."
        },
        "the_dev_fee_immediately_returns_to_the_1_pro": {
            de: "Die Dev-Fee wechselt sofort zurück auf den Proxy-Tarif von 1 %.",
            en: "The dev fee immediately returns to the 1% proxy tier."
        },
        "the_drivers_rejected_these_limits_your_draft": {
            de: "Die Treiber haben diese Grenzen abgelehnt. Der Entwurf bleibt zum Prüfen erhalten.",
            en: "The drivers rejected these limits. Your draft is retained for review."
        },
        "gpu_not_found": {de: "GPU wurde nicht gefunden.", en: "GPU was not found."},
        "gpu_power_limit": {de: "GPU-Leistungsgrenze", en: "GPU power limit"},
        "gpu_power_limit_value_must_be_integer": {
            de: "Das Power-Limit muss als ganze Wattzahl angegeben werden.",
            en: "The power limit must be a whole number of watts."
        },
        "driver_power_range": {
            de: "Treiberbereich {min}–{max} W",
            en: "Driver range {min}–{max} W"
        },
        "power_limit_watts": {de: "Power-Limit {watts} W", en: "Power limit {watts} W"},
        "power_limit_saved": {de: "Power-Limit gespeichert.", en: "Power limit saved."},
        "gpu_power_limit_saved": {
            de: "{gpu}: Power-Limit auf {watts} W gesetzt.",
            en: "{gpu}: power limit set to {watts} W."
        },
        "gpu_power_control_unavailable": {
            de: "Power-Limit-Steuerung ist für diese GPU nicht verfügbar.",
            en: "Power-limit control is unavailable for this GPU."
        },
        "gpu_power_limits_outside_driver_range": {
            de: "Die Grenzen liegen außerhalb des Treiberbereichs:",
            en: "The limits are outside the driver range:"
        },
        "gpu_power_limits_save_failed": {
            de: "Die Leistungsgrenzen konnten nicht gespeichert werden.",
            en: "The power limits could not be saved."
        },
        "gpu_power_limit_readback_unavailable": {
            de: "Das aktuelle Power-Limit konnte nach dem Speichern nicht gelesen werden.",
            en: "The current power limit could not be read after saving."
        },
        "gpu_power_limit_driver_rejected": {
            de: "Der Treiber hat die Power-Limit-Änderung abgelehnt:",
            en: "The driver rejected the power-limit change:"
        },
        "the_included_proxy_is_running_on_this_pc": {
            de: "Der enthaltene Proxy läuft auf diesem PC.",
            en: "The included proxy is running on this PC."
        },
        "the_local_mining_proxy_is_starting": {
            de: "Der lokale Mining-Proxy wird gestartet.",
            en: "The local mining proxy is starting."
        },
        "the_local_proxy_is_not_ready_yet": {
            de: "Der lokale Proxy ist noch nicht bereit.",
            en: "The local proxy is not ready yet."
        },
        "the_local_proxy_process_has_not_started_yet": {
            de: "Der lokale Proxy-Prozess wurde noch nicht gestartet.",
            en: "The local proxy process has not started yet."
        },
        "the_median": {de: "dem Median", en: "the median"},
        "the_miner_always_connects_to_the_solarminer": {
            de: "Der Miner spricht immer den SolarMiner-Proxy an. Hier wählst du, welches Pool-Ziel der Proxy für einen Coin verwendet.",
            en: "The miner always connects to the SolarMiner proxy. Choose the pool destination the proxy uses for a coin here."
        },
        "the_miner_always_talks_to_the_proxy": {
            de: "Der Miner spricht immer den Proxy an",
            en: "The miner always talks to the proxy"
        },
        "the_miner_connects_through_the_solarminer_pr": {
            de: "Der Miner verbindet sich über den SolarMiner-Proxy; die Pool-Adresse wird dort als Ziel verwendet.",
            en: "The miner connects through the SolarMiner proxy, which uses the pool address as its destination."
        },
        "the_miner_is_not_running_starting_uses_the_s": {
            de: "Der Miner läuft nicht. Starten übernimmt die gespeicherte Konfiguration.",
            en: "The miner is not running. Starting uses the saved configuration."
        },
        "the_miner_may_be_unable_to_reserve_1_gb_page": {
            de: "Der Miner kann 1-GB-Pages möglicherweise nicht reservieren und auf normale Huge Pages zurückfallen. Der zusätzlich benötigte Speicher steht dem System und anderen Programmen währenddessen nicht zur Verfügung.",
            en: "The miner may be unable to reserve 1 GB pages and fall back to regular Huge Pages. The additional memory is unavailable to the system and other programs while mining."
        },
        "the_mining_connection_is_unreachable_check_t": {
            de: "Die Mining-Verbindung ist nicht erreichbar. Prüfe lokalen oder externen Proxy.",
            en: "The mining connection is unreachable. Check the local or external proxy."
        },
        "the_name_may_be_at_most_40_characters_and_mu": {
            de: "Der Name darf höchstens 40 Zeichen haben und keine Steuerzeichen enthalten",
            en: "The name may be at most 40 characters and must not contain control characters"
        },
        "the_node_can_start_pause_and_include_only_av": {
            de: "Node und freigegebene PC-Agents können nur freigegebene Worker starten oder pausieren; die Node darf sie zusätzlich in ihre Planung aufnehmen.",
            en: "The Node and shared PC agents can start or pause only enabled workers; the Node may additionally include them in its planning."
        },
        "the_node_may_allocate_power_within_your_limi": {
            de: "Der Node darf Wattziele innerhalb deiner Grenzen verteilen. Ohne Regelung bleiben Start und Pause möglich.",
            en: "The Node may allocate power within your limits. Start and pause remain available without dynamic control."
        },
        "the_node_may_control_the_device_profile": {
            de: "Der Node darf das Geräteprofil steuern.",
            en: "The Node may control the device profile."
        },
        "the_pc_agent_is_looking_for_the_newest_proxy": {
            de: "Der PC-Agent sucht die neueste Proxy-Version auf GitHub.",
            en: "The PC-Agent is looking for the newest proxy version on GitHub."
        },
        "the_pc_agent_is_unreachable": {de: "Der PC-Agent ist nicht erreichbar.", en: "The PC-Agent is unreachable."},
        "the_pc_agent_periodically_sends_active_hashr": {
            de: "Der PC-Agent sendet regelmäßig aktive Hashrate sowie verfügbare Leistungsaufnahme und Leistungsziel. Hardwaremodell und Algorithmus werden für den Vergleich benötigt. Keine Wallets, Pool-Zugangsdaten, IP-Adresse oder sonstige Sensordaten.",
            en: "The PC agent periodically sends active hashrate, available power usage and power target. Hardware model and algorithm are needed for comparison. Wallets, pool credentials, IP addresses and other sensor data are excluded."
        },
        "the_pc_agent_works_on_its_own_a_solarminer_n": {
            de: "Der PC-Agent funktioniert eigenständig. Ein SolarMiner Node ergänzt später Automatisierung, PV-Überschuss und weitere Homelab-Geräte.",
            en: "The PC agent works on its own. A SolarMiner Node can later add automation, PV surplus and other homelab devices."
        },
        "the_pool_address_and_wallet_are_used_to_star": {
            de: "Pool-Adresse und Wallet werden für den Mining-Start verwendet.",
            en: "The pool address and wallet are used to start mining."
        },
        "the_proxy_connects_miners_to_pools_and_provi": {
            de: "Der Proxy verbindet deine Miner mit den Pools und stellt die SolarMiner-Fee-Route bereit. Verwende den enthaltenen Proxy oder wähle bewusst einen Server in deinem Netzwerk.",
            en: "The proxy connects miners to pools and provides the SolarMiner fee route. Use the included proxy or choose a server on your network."
        },
        "the_proxy_is_unavailable_miners_cannot_start": {
            de: "Der Proxy ist nicht erreichbar; ohne ihn starten die Miner nicht.",
            en: "The proxy is unavailable; miners cannot start without it."
        },
        "the_proxy_is_unavailable_switching_pools_wor": {
            de: "Der Proxy ist nicht erreichbar; Poolwechsel ist erst nach der Verbindung möglich.",
            en: "The proxy is unavailable; switching pools works only after connecting."
        },
        "the_proxy_rolls_per_job_whether_your_miners": {
            de: "Der Proxy würfelt pro Job, ob deine Miner für dich oder für ein Fee-Ziel arbeiten. Zufällig würfelt jeden Job unabhängig; über kurze Zeiträume kann die Verteilung dadurch abweichen. Ausgewogen merkt sich die bisherigen Züge und hält die Verteilung nah an den Zielprozenten.",
            en: "The proxy rolls per job whether your miners work for you or for a fee target. Random rolls every job independently, so short-term distribution can drift. Balanced remembers past draws and keeps the distribution close to the target percentages."
        },
        "the_results_were_uploaded_once_anonymously_t": {
            de: "Die Ergebnisse wurden einmalig anonym hochgeladen. Die Einstellung für regelmäßige Benchmarks bleibt unverändert.",
            en: "The results were uploaded once anonymously. The periodic benchmark setting stays unchanged."
        },
        "the_setting_was_saved_but_is_not_active_on_t": {
            de: "Einstellung wurde gespeichert, ist auf diesem System aber noch nicht aktiv.",
            en: "The setting was saved, but is not active on this system yet."
        },
        "the_software_is_ready_and_can_be_assigned_to": {
            de: "Die Software ist bereit und kann einem Worker zugewiesen werden.",
            en: "The software is ready and can be assigned to a worker."
        },
        "the_solarminer_node_automatically_sets_its_r": {
            de: "Der SolarMiner Node setzt seinen Referral-Key automatisch erneut, sobald er den Agenten steuert.",
            en: "The SolarMiner Node automatically sets its referral key again when it controls this agent."
        },
        "the_solarminer_stratum_proxy_could_not_be_do": {
            de: "Der SolarMiner-Stratum-Proxy konnte nicht geladen oder gestartet werden.",
            en: "The SolarMiner Stratum proxy could not be downloaded or started."
        },
        "the_solarminer_stratum_proxy_is_downloading": {
            de: "Der SolarMiner-Stratum-Proxy wird heruntergeladen.",
            en: "The SolarMiner Stratum proxy is downloading."
        },
        "the_worker_action_was_not_applied_check_the": {
            de: "Die Worker-Aktion wurde nicht angewendet. Prüfe die Miner-Konsole.",
            en: "The worker action was not applied. Check the miner console."
        },
        "these_readings_describe_your_pc_power_use_an": {
            de: "Diese Werte beschreiben deinen PC. Leistungsaufnahme und Temperaturen sind von Mining-Hashrate und Node-Leistungsziel getrennt. „—“ bedeutet: kein verfügbarer Messwert.",
            en: "These readings describe your PC. Power use and temperatures are separate from mining hashrate and the Node power target. “—” means no reading is available."
        },
        "this_agent_runs_without_a_solarminer_node_re": {
            de: "Dieser Agent läuft ohne SolarMiner Node – reduzierte Proxy-Gebühr.",
            en: "This agent runs without a SolarMiner Node – reduced proxy fee."
        },
        "this_coin_has_no_miner_in_the_catalog_yet": {
            de: "Dieser Coin hat noch keinen Miner im Katalog.",
            en: "This coin has no miner in the catalog yet."
        },
        "this_component_remains_unassigned_and_cannot": {
            de: "Die Komponente bleibt frei und kann nicht gestartet werden.",
            en: "This component remains unassigned and cannot be started."
        },
        "this_device_supports_start_stop_only_power_l": {
            de: "Für dieses Gerät ist nur Start/Stopp verfügbar. Leistungsgrenzen können nicht zuverlässig angewendet werden.",
            en: "This device supports start/stop only. Power limits cannot be applied reliably."
        },
        "this_miner_is_not_installed_yet_after_instal": {
            de: "Dieser Miner ist noch nicht installiert. Nach der Installation kannst du Pool, Wallet und Geräte konfigurieren.",
            en: "This miner is not installed yet. After installation you can configure pool, wallet and devices."
        },
        "this_miner_software_is_currently_not_releas": {
            de: "Diese Miner-Software ist für den Coin derzeit nicht freigegeben.",
            en: "This miner software is currently not released for this coin."
        },
        "this_operating_system": {de: "dieses Betriebssystem", en: "this operating system"},
        "this_pc_cannot_mine_without_a_proxy": {
            de: "Ohne Proxy kann dieser PC nicht minen.",
            en: "This PC cannot mine without a proxy."
        },
        "through_solarminer": {de: "über SolarMiner aktivieren", en: "through SolarMiner"},
        "to_bottom": {de: "Zum Ende", en: "To bottom"},
        "today": {de: "HEUTE", en: "TODAY"},
        "today_1ab330": {de: "Heute", en: "Today"},
        "total_pct": {de: "Gesamt: {pct}", en: "Total: {pct}"},
        "total_power": {de: "GESAMTLEISTUNG", en: "TOTAL POWER"},
        "total_power_0c6b0d": {de: "Gesamtleistung", en: "Total power"},
        "transparent_fees": {de: "TRANSPARENTE GEBÜHREN", en: "TRANSPARENT FEES"},
        "try_again": {de: "Erneut versuchen", en: "Try again"},
        "unassigned": {de: "Frei", en: "Unassigned"},
        "unassigned_4cfcb8": {de: "Nicht zugewiesen", en: "Unassigned"},
        "unavailable": {de: "Nicht erreichbar", en: "Unavailable"},
        "unavailable_2a5d2c": {de: "Nicht verfügbar", en: "Unavailable"},
        "unavailable_6d094b": {de: "nicht verfügbar", en: "unavailable"},
        "unavailable_9da3a4": {de: "nicht erreichbar", en: "unavailable"},
        "unavailable_for_this_pool": {de: "Für diesen Pool nicht verfügbar", en: "Unavailable for this pool"},
        "uninstall": {de: "Deinstallieren", en: "Uninstall"},
        "unknown": {de: "Unbekannt", en: "Unknown"},
        "unknown_a4967c": {de: "unbekannt", en: "unknown"},
        "unknown_architecture": {de: "Architektur unbekannt", en: "Unknown architecture"},
        "unknown_gpu_coin": {de: "Unbekannter GPU-Coin", en: "Unknown GPU coin"},
        "unknown_processor": {de: "Prozessor unbekannt", en: "Unknown processor"},
        "unknown_source": {de: "Quelle unbekannt", en: "Unknown source"},
        "updated_time": {de: "Aktualisiert {time}", en: "Updated {time}"},
        "upload_anonymously_now": {de: "Jetzt anonym hochladen", en: "Upload anonymously now"},
        "upload_failed_you_can_retry_the_upload": {
            de: "Upload ans Backend fehlgeschlagen. Du kannst den Upload erneut versuchen.",
            en: "Upload failed. You can retry the upload."
        },
        "uploading_benchmark_data": {de: "Benchmarkdaten werden hochgeladen …", en: "Uploading benchmark data …"},
        "use_a_custom_pool_address": {de: "Eigene Pool-Adresse verwenden", en: "Use a custom pool address"},
        "use_a_shared_solarminer_proxy_on_another_dev": {
            de: "Für einen gemeinsamen SolarMiner-Proxy auf einem anderen Gerät. Die Suche zeigt Kandidaten; du bestimmst den Server.",
            en: "Use a shared SolarMiner proxy on another device. Discovery lists candidates; you choose the server."
        },
        "use_local_proxy": {de: "Lokalen Proxy verwenden", en: "Use local proxy"},
        "used_by_the_proxy": {de: "Vom Proxy verwendet", en: "Used by the proxy"},
        "value_day": {de: "≈ {value}/Tag", en: "≈ {value}/day"},
        "value_description": {de: "≈ {value} · {description}", en: "≈ {value} · {description}"},
        "values_come_live_from_the_solarminer_fee_bac": {
            de: "Die Werte kommen live vom SolarMiner Fee-Backend und ändern sich mit Modus und Referral-Code.",
            en: "Values come live from the SolarMiner fee backend and change with the mode and referral code."
        },
        "values_without_a_sensor_source_are_unavailab": {
            de: "Werte ohne Sensorquelle erscheinen als nicht verfügbar. Gesamtleistung summiert CPU-Package und erkannte GPU-Boardwerte.",
            en: "Values without a sensor source are unavailable. Total power adds CPU package and detected GPU board readings."
        },
        "version_version": {de: "Version {version}", en: "Version {version}"},
        "view_pools": {de: "Pools ansehen", en: "View pools"},
        "view_sensors_and_readings": {de: "Sensoren und Messwerte ansehen", en: "View sensors and readings"},
        "view_workers": {de: "Worker ansehen →", en: "View workers →"},
        "wait_for_current_agent_data_before_making_ch": {
            de: "Warte auf aktuelle Agent-Daten, bevor du eine Änderung ausführst.",
            en: "Wait for current agent data before making changes."
        },
        "waiting_for_a_pool_job": {de: "Warte auf Pool-Job", en: "Waiting for a pool job"},
        "waiting_for_data": {de: "Warte auf Daten", en: "Waiting for data"},
        "waiting_for_readings": {de: "Warte auf Messwerte", en: "Waiting for readings"},
        "waiting_for_the_reference_gpu_result": {
            de: "Wartet auf Ergebnis der Referenzkarte",
            en: "Waiting for the reference GPU result"
        },
        "waiting_for_windows_approval": {de: "Warte auf Windows-Freigabe …", en: "Waiting for Windows approval …"},
        "waiting_runs_count": {de: "Wartende Läufe ({count})", en: "Waiting runs ({count})"},
        "wallet_and_pool_destination_saved": {
            de: "Wallet und Pool-Ziel gespeichert.",
            en: "Wallet and pool destination saved."
        },
        "wallet_and_worker_stay_unchanged_make_sure_t": {
            de: "Wallet und Worker bleiben unverändert. Prüfe, dass die Wallet zum gewählten Pool passt.",
            en: "Wallet and worker stay unchanged. Make sure the wallet matches the selected pool."
        },
        "wallet_balances": {de: "Wallet-Kontostände", en: "Wallet balances"},
        "wallet_configured": {de: "Wallet hinterlegt", en: "Wallet configured"},
        "wallet_from_the_miner_configuration": {
            de: "Wallet aus der Miner-Konfiguration",
            en: "Wallet from the miner configuration"
        },
        "wallet_missing": {de: "Wallet fehlt", en: "Wallet missing"},
        "wallet_saved": {de: "Wallet gespeichert", en: "Wallet saved"},
        "wallet_worker": {de: "Wallet & Worker", en: "Wallet & worker"},
        "wallets": {de: "Wallets", en: "Wallets"},
        "watts_w_samples_required_samples": {
            de: "{watts} W · {samples}/{required} Messpunkte",
            en: "{watts} W · {samples}/{required} samples"
        },
        "power_limit_sample_progress": {
            de: "Fortschritt der aktuellen Power-Limit-Messung",
            en: "Current power-limit measurement progress"
        },
        "collecting_power_limit_samples": {
            de: "Messung läuft: {samples} von {required} Messpunkten gesammelt (5 Sekunden Abstand).",
            en: "Measurement active: collected {samples} of {required} samples (5 seconds apart)."
        },
        "waiting_for_first_power_limit_sample": {
            de: "Warte auf den ersten gültigen Messpunkt. Der Miner kann bis zu 4 Minuten zum Starten brauchen.",
            en: "Waiting for the first valid sample. The miner may take up to 4 minutes to start."
        },
        "what_is_running_now": {de: "Was gerade arbeitet", en: "What is running now"},
        "what_may_the_node_mine": {de: "Was darf der Node minen?", en: "What may the Node mine?"},
        "when_you_turn_off_remote_control_no_solarmin": {
            de: "Wenn du die Remote-Steuerung ausschaltest, können weder SolarMiner Node noch andere PC-Agents diesen Agenten kontrollieren. Die Dev-Fee wechselt sofort zurück auf den Proxy-Tarif von 1 %.",
            en: "When you turn off remote control, neither SolarMiner Node nor other PC agents can control this agent. The dev fee immediately returns to the 1% proxy tier."
        },
        "where_your_earnings_go": {de: "Wo dein Ertrag hingeht", en: "Where your earnings go"},
        "where_your_mining_yield_goes": {de: "Wo dein Mining-Ertrag hingeht", en: "Where your mining yield goes"},
        "while_mining": {de: "IM LAUFENDEN BETRIEB", en: "WHILE MINING"},
        "widely_used": {de: "Sehr verbreitet", en: "Widely used"},
        "windows_needs_administrator_approval_to_let": {
            de: "Windows benötigt eine Administratorfreigabe, damit der Hardware-Monitor seine Sensoren starten kann.",
            en: "Windows needs administrator approval to let the hardware monitor start its sensors."
        },
        "windows_security_blocked_the_download_review": {
            de: "Windows-Sicherheit hat den Download blockiert – sicher prüfen",
            en: "Windows Security blocked the download – review safely"
        },
        "windows_security_blocked_the_miner": {
            de: "Windows-Sicherheit hat den Miner blockiert.",
            en: "Windows Security blocked the miner."
        },
        "windows_sensor_access": {de: "WINDOWS SENSORZUGRIFF", en: "WINDOWS SENSOR ACCESS"},
        "without_a_fee_target_the_miner_will_not_star": {
            de: "Ohne Fee-Ziel startet der betreffende Miner nicht",
            en: "Without a fee target the miner will not start"
        },
        "without_a_node": {de: "Ohne Node", en: "Without a Node"},
        "without_permission_windows_sensors_are_unava": {
            de: "Ohne Freigabe fehlen die Windows-Sensoren. Einige Mining-Steuerungsaktionen benötigen diesen Sensorzugriff.",
            en: "Without permission, Windows sensors are unavailable. Some mining control actions require access to these sensors."
        },
        "without_your_own_wallet_the_solarminer_defau": {
            de: "Ohne eigene Wallet wird beim Start das SolarMiner-Standardziel geprüft. Die gesamte Auszahlung geht dann dorthin.",
            en: "Without your own wallet, the SolarMiner default payout is checked at startup. All mining payouts then go there."
        },
        "worker": {de: "Worker", en: "Worker"},
        "worker_action_failed": {de: "Worker-Aktion fehlgeschlagen:", en: "Worker action failed:"},
        "worker_active": {de: "Worker aktiv", en: "Worker active"},
        "currently_running_algorithm": {de: "Läuft aktuell · {algorithm}", en: "Currently running · {algorithm}"},
        "configured_assignment_coin": {de: "Zuweisung: {coin}", en: "Configured assignment: {coin}"},
        "worker_is_active": {de: "Worker ist aktiv", en: "Worker is active"},
        "worker_made_available_to_the_node": {
            de: "Worker für Remote-Steuerung freigegeben.",
            en: "Worker made available for remote control."
        },
        "worker_must_contain_1_32_letters_digits_or": {
            de: "Worker muss 1–32 Zeichen aus Buchstaben, Zahlen, _ oder - enthalten",
            en: "Worker must contain 1–32 letters, digits, _ or -"
        },
        "worker_name": {de: "Worker-Name", en: "Worker name"},
        "worker_paused": {de: "{worker} wurde pausiert.", en: "{worker} paused."},
        "worker_permissions": {de: "WORKER-FREIGABEN", en: "WORKER PERMISSIONS"},
        "worker_restricted_to_local_control": {
            de: "Worker auf lokale Steuerung beschränkt.",
            en: "Worker restricted to local control."
        },
        "worker_started": {de: "{worker} wurde gestartet.", en: "{worker} started."},
        "worker_was_not_detected": {de: "Worker wurde nicht erkannt", en: "Worker was not detected"},
        "workers_available_to_the_node": {de: "Remote verfügbare Worker", en: "Workers available remotely"},
        "working_devices": {de: "ARBEITENDE GERÄTE", en: "WORKING DEVICES"},
        "xmrig_developer_donation": {de: "XMRig Entwickler-Spende", en: "XMRig developer donation"},
        "xmrig_download_started": {de: "XMRig-Download gestartet.", en: "XMRig download started."},
        "xmrig_removed_the_pool_configuration_remains": {
            de: "XMRig entfernt; die Pool-Konfiguration bleibt gespeichert.",
            en: "XMRig removed; the pool configuration remains saved."
        },
        "you_control_mining_locally_the_node_may_not": {
            de: "Du entscheidest lokal. Der Node darf keine Miner starten.",
            en: "You control mining locally. The Node may not start miners."
        },
        "you_have_disabled_periodic_benchmark_sharing": {
            de: "Du hast das regelmäßige Benchmark-Teilen deaktiviert. Möchtest du die gerade gemessenen Ergebnisse einmalig anonym an SolarMiner übermitteln? Die Einstellung für regelmäßige Benchmarks bleibt unverändert.",
            en: "You have disabled periodic benchmark sharing. Would you like to upload the results you just measured once, anonymously, to SolarMiner? The periodic benchmark setting stays unchanged."
        },
        "your_default_configuration": {de: "DEINE STANDARDKONFIGURATION", en: "YOUR DEFAULT CONFIGURATION"},
        "your_first_miner": {de: "Dein erster Miner", en: "Your first miner"},
        "your_local_mining_agent": {de: "Dein lokaler Mining-Agent", en: "Your local mining agent"},
        "your_measurements": {de: "Deine Messungen", en: "Your measurements"},
        "your_miners": {de: "Deine Miner", en: "Your miners"},
        "your_mining_operations": {de: "DEIN MINING-BETRIEB", en: "YOUR MINING OPERATIONS"},
        "your_own_payout_is_configured": {
            de: "Eigene Auszahlung ist konfiguriert.",
            en: "Your own payout is configured."
        },
        "your_payout_address": {de: "Eigene Auszahlungsadresse", en: "Your payout address"},
        "your_pc_at_a_glance": {de: "DEIN PC IM ÜBERBLICK", en: "YOUR PC AT A GLANCE"},
        "your_pc_is_working": {de: "Dein PC arbeitet.", en: "Your PC is working."},
        "your_workspace": {de: "Dein Arbeitsbereich", en: "Your workspace"},
    },
    agentEnglishToGerman: {
        "Could not save local benchmark profiles": "Lokale Benchmark-Profile konnten nicht gespeichert werden",
        "Could not prepare benchmark sharing": "Benchmark-Freigabe konnte nicht vorbereitet werden",
        "CPU package + GPU board readings; excludes other PC components": "CPU-Paket und GPU-Platinenwerte; weitere PC-Komponenten sind nicht enthalten",
        "Diagnosis: this GPU cannot run the selected algorithm with the installed miner build; the miner has no working kernel for this GPU architecture. Check the miner's supported-GPU matrix, update the miner, or exclude this coin for this card in the benchmark selection.": "Diagnose: Diese GPU kann den gewählten Algorithmus mit dem installierten Miner-Build nicht ausführen; der Miner hat keinen funktionierenden Kernel für diese GPU-Architektur. Prüfe die unterstützten GPUs des Miners, aktualisiere den Miner oder wähle diesen Coin in der Benchmark-Auswahl für diese Karte ab.",
        "Diagnosis: the GPU driver rejected the miner's CUDA/OpenCL context. Reinstall or update the GPU driver, then retry.": "Diagnose: Der GPU-Treiber hat den CUDA-/OpenCL-Kontext des Miners abgelehnt. Installiere oder aktualisiere den GPU-Treiber und versuche es erneut.",
        "Diagnosis: the miner could not reach the pool. Check the SolarMiner proxy, the pool address and the network.": "Diagnose: Der Miner hat den Pool nicht erreicht. Prüfe SolarMiner-Proxy, Pool-Adresse und Netzwerk.",
        "Diagnosis: XMRig could not lock memory. Grant huge-page permissions to the agent or disable huge pages in the miner configuration.": "Diagnose: XMRig konnte keinen Speicher sperren. Erteile dem Agenten Huge-Page-Rechte oder deaktiviere Huge Pages in der Miner-Konfiguration.",
        "Diagnosis: the miner binary could not be executed. Reinstall the miner from the miner library.": "Diagnose: Die Miner-Binary konnte nicht ausgeführt werden. Installiere den Miner über die Miner-Bibliothek neu.",
        "Diagnosis: XMRig could not reach the pool. Check the SolarMiner proxy, the pool address and the network.": "Diagnose: XMRig hat den Pool nicht erreicht. Prüfe SolarMiner-Proxy, Pool-Adresse und Netzwerk.",
        "available": "verfügbar",
        "starting": "startet",
        "stopped": "angehalten",
        "failed": "fehlgeschlagen",
        "api-disabled": "API deaktiviert",
        "api-unavailable": "API nicht erreichbar",
        "not-started": "nicht gestartet",
        "Unsupported XMRig operating system or architecture": "Nicht unterstütztes Betriebssystem oder Architektur für XMRig",
        "XMRig release metadata missing": "XMRig-Release-Metadaten fehlen",
        "XMRig release has no usable size or SHA-256 digest": "XMRig-Release enthält keine gültige Größe oder SHA-256-Prüfsumme",
        "XMRig download URL must point to the official GitHub release": "Die XMRig-Downloadadresse muss auf das offizielle GitHub-Release zeigen",
        "XMRig archive exceeds expected size": "XMRig-Archiv überschreitet die erwartete Größe",
        "XMRig archive size or SHA-256 mismatch": "Größe oder SHA-256-Prüfsumme des XMRig-Archivs stimmt nicht",
        "Cannot make XMRig executable": "XMRig konnte nicht ausführbar gemacht werden",
        "XMRig executable missing from archive": "XMRig-Programmdatei fehlt im Archiv",
        "XMRig executable exceeds size limit": "XMRig-Programmdatei überschreitet die Größenbegrenzung",
        "XMRig executable is empty": "XMRig-Programmdatei ist leer",
        "XMRig archive expands beyond limit": "Entpacktes XMRig-Archiv überschreitet die Größenbegrenzung",
        "Unsafe XMRig archive entry": "Unsicherer Eintrag im XMRig-Archiv",
        "SRBMiner-MULTI supports only x64 on this agent": "SRBMiner-MULTI unterstützt in diesem Agenten nur x64",
        "No official SRBMiner package for this operating system": "Für dieses Betriebssystem gibt es kein offizielles SRBMiner-Paket",
        "Release metadata too large": "Release-Metadaten sind zu groß",
        "SRBMiner release is not stable": "Das SRBMiner-Release ist nicht stabil",
        "Unexpected SRBMiner release tag": "Unerwartetes SRBMiner-Release-Kennzeichen",
        "SRBMiner release asset metadata is invalid": "Die SRBMiner-Release-Dateimetadaten sind ungültig",
        "SRBMiner executable missing from archive": "SRBMiner-Programmdatei fehlt im Archiv",
        "Invalid package path": "Ungültiger Paketpfad",
        "Could not make SRBMiner executable": "SRBMiner konnte nicht ausführbar gemacht werden",
        "SRBMiner archive exceeds expected size": "SRBMiner-Archiv überschreitet die erwartete Größe",
        "SRBMiner archive SHA-256 or size mismatch": "SHA-256-Prüfsumme oder Größe des SRBMiner-Archivs stimmt nicht",
        "SRBMiner archive contains a link": "SRBMiner-Archiv enthält einen Verweis",
        "Unsafe SRBMiner archive entry": "Unsicherer Eintrag im SRBMiner-Archiv",
        "SRBMiner archive expands beyond limit": "Entpacktes SRBMiner-Archiv überschreitet die Größenbegrenzung",
        "SRBMiner executable is empty": "SRBMiner-Programmdatei ist leer",
        "Could not write the SRBMiner version marker": "Das SRBMiner-Versionsmerkmal konnte nicht geschrieben werden",
        "Could not write the SRBMiner file manifest": "Das SRBMiner-Dateiverzeichnis konnte nicht geschrieben werden",
        "Pearl miner configuration could not be written": "Die Pearl-Miner-Konfiguration konnte nicht geschrieben werden",
        "GPU coin configuration could not be written": "Die GPU-Coin-Konfiguration konnte nicht geschrieben werden",
        "Tracked package path escapes the SRBMiner directory": "Erfasster Paketpfad liegt außerhalb des SRBMiner-Verzeichnisses",
        "No configured Pearl GPU detected": "Keine konfigurierte Pearl-GPU erkannt",
        "SRBMiner is running externally; no second process started": "SRBMiner läuft extern; kein zweiter Prozess wurde gestartet",
        "Pearl requires a reachable SolarMiner proxy with a loaded fee target": "Pearl benötigt einen erreichbaren SolarMiner-Proxy mit geladenem Fee-Ziel",
        "SRBMiner is starting": "SRBMiner wird gestartet",
        "Waiting for pool connection and mining job": "Warte auf Pool-Verbindung und Mining-Job",
        "Miner API is not reachable yet": "Miner-API ist noch nicht erreichbar",
        "LAN visibility is disabled on this agent": "LAN-Sichtbarkeit ist auf diesem Agent deaktiviert.",
        "Peer is not reachable": "Peer ist nicht erreichbar.",
        "Linux kernel sensor interfaces are used.": "Linux-Kernel-Sensorschnittstellen werden verwendet.",
        "active; reads hwmon, thermal and powercap on demand": "aktiv; liest hwmon, thermal und powercap bei Bedarf",
        "Assign a coin to this worker first": "Weise diesem Worker zuerst einen Coin zu",
        "GPU miner could not start. Check its console, installation and proxy fee route.": "GPU-Miner konnte nicht starten. Prüfe Konsole, Installation und Proxy-Fee-Route.",
        "XMRig could not start. Check the miner console, installation and proxy fee route.": "XMRig konnte nicht starten. Prüfe Miner-Konsole, Installation und Proxy-Fee-Route.",
        "A benchmark is already running": "Ein Benchmark läuft bereits",
        "Benchmark failed": "Benchmark fehlgeschlagen",
        "Benchmark interrupted": "Benchmark unterbrochen",
        "Miner did not start": "Miner konnte nicht starten",
        "Cancelled; previous miner state restored": "Abgebrochen; vorheriger Mining-Zustand wiederhergestellt",
        "Complete; previous miner state restored": "Abgeschlossen; vorheriger Mining-Zustand wiederhergestellt",
        "Could not fully restore miner state": "Mining-Zustand konnte nicht vollständig wiederhergestellt werden",
        "Install and configure at least one miner before running this benchmark": "Installiere und konfiguriere zuerst mindestens einen Miner.",
        "Reviewed SRBMiner-MULTI version is missing": "Die geprüfte SRBMiner-MULTI-Version fehlt",
        "LibreHardwareMonitor JSON API is responding on 127.0.0.1:8085.": "LibreHardwareMonitor ist unter 127.0.0.1:8085 erreichbar.",
        "LibreHardwareMonitor JSON API is responding on loopback.": "LibreHardwareMonitor ist lokal erreichbar.",
        "LibreHardwareMonitor is already running without a reachable local JSON API. Close it and restart the PC-Agent.": "LibreHardwareMonitor läuft ohne erreichbare lokale JSON-Schnittstelle. Schließe ihn und starte den PC-Agent neu.",
        "LibreHardwareMonitor is no longer responding. Restart it to continue.": "LibreHardwareMonitor antwortet nicht mehr. Starte ihn erneut, um fortzufahren.",
        "LibreHardwareMonitor was launched after Windows elevation approval.": "LibreHardwareMonitor wurde nach der Windows-Administratorfreigabe gestartet.",
        "LibreHardwareMonitor was launched, but its local JSON API did not become reachable. Check its window and the Windows elevation prompt.": "LibreHardwareMonitor wurde gestartet, ist aber lokal nicht erreichbar. Prüfe sein Fenster und die Windows-Administratorabfrage.",
        "Measuring active miners": "Laufende Miner messen",
        "Node controls are temporarily locked while a benchmark is running": "Node-Steuerung ist während eines Benchmarks vorübergehend gesperrt",
        "Pausing current miners": "Laufende Miner werden pausiert",
        "Preparing": "Vorbereitung",
        "Sequential benchmark skipped: externally started miners cannot be safely paused and restored": "Messlauf übersprungen: Extern gestartete Miner können nicht sicher pausiert und wiederhergestellt werden",
        "Summarizing": "Messwerte zusammenfassen",
        "Unknown benchmark mode": "Unbekannter Benchmark-Modus",
        "Waiting for Windows elevation approval timed out": "Zeitüberschreitung beim Warten auf die Windows-Administratorfreigabe",
        "QuanPool Miner developer fee": "QuanPool-Miner-Entwicklergebühr",
        "Optimized native CUDA engine": "Optimierte native CUDA-Engine",
        "Stable NVIDIA GPU selection by driver UUID": "Stabile NVIDIA-GPU-Auswahl über Treiber-UUID",
        "HiveOS-compatible JSON metrics": "HiveOS-kompatible JSON-Metriken",
        "Closed binary with a 2% developer fee": "Geschlossene Binärdatei mit 2 % Entwicklergebühr",
        "Real SolarMiner fee-share accounting is still pending": "Reale SolarMiner-Fee-Share-Abrechnung steht noch aus",
        "QuanPool Miner 6.4.0 is available only for Linux x86-64": "QuanPool Miner 6.4.0 ist nur für Linux x86-64 verfügbar",
        "QuanPool Miner removed; the Quantus pool configuration remains saved.": "QuanPool Miner entfernt; die Quantus-Pool-Konfiguration bleibt gespeichert.",
        "QuanPool Miner requires one or more NVIDIA GPUs": "QuanPool Miner benötigt mindestens eine NVIDIA-GPU",
        "QuanPool Miner GPU selection contains duplicates": "Die GPU-Auswahl für QuanPool Miner enthält Duplikate",
        "QuanPool Miner requires an NVIDIA GPU": "QuanPool Miner benötigt eine NVIDIA-GPU",
        "No configured QuanPool NVIDIA GPU was detected": "Keine konfigurierte QuanPool-NVIDIA-GPU wurde erkannt",
        "QuanPool Miner requires a detected NVIDIA GPU UUID": "QuanPool Miner benötigt eine erkannte NVIDIA-GPU-UUID",
        "SolarMiner proxy, fee target or QuanPool Miner for Quantus is not ready": "SolarMiner-Proxy, Fee-Ziel oder QuanPool Miner für Quantus ist nicht bereit",
        "Waiting for a pool job": "Warte auf einen Pool-Job",
        "QuanPool Miner is hashing through the SolarMiner proxy": "QuanPool Miner hasht über den SolarMiner-Proxy",
        "Pool connected; waiting for GPU hashrate": "Pool verbunden; warte auf GPU-Hashrate",
        "QuanPool miner metrics are unavailable": "QuanPool-Miner-Metriken sind nicht erreichbar",
        "QuanPool Miner lost its metrics or SolarMiner fee route": "QuanPool Miner hat seine Metriken oder die SolarMiner-Fee-Route verloren",
        "Miner fee differs by worker": "Die Miner-Gebühr unterscheidet sich je Worker",
        "Worker use different mining software; a coin-wide net estimate would be misleading": "Worker verwenden unterschiedliche Mining-Software; eine coinweite Netto-Schätzung wäre irreführend",
        "Miner paused": "Miner pausiert",
        "Not started yet": "Noch nicht gestartet",
        "Diagnosis: QuanPool Miner could not initialize CUDA. Check the NVIDIA driver and the miner's supported-GPU matrix.": "Diagnose: QuanPool Miner konnte CUDA nicht initialisieren. Prüfe den NVIDIA-Treiber und die unterstützten GPUs des Miners.",
        "Diagnosis: QuanPool Miner could not reach the pool. Check the SolarMiner proxy and the network.": "Diagnose: QuanPool Miner konnte den Pool nicht erreichen. Prüfe den SolarMiner-Proxy und das Netzwerk.",
    },
    agentGermanToEnglish: {
        "Der Effizienz-Sweep konnte nicht gestartet werden.": "Could not start the efficiency sweep.",
        "Der Sweep konnte nicht abgebrochen werden.": "Could not cancel the sweep.",
        "Software wurde entfernt.": "Software removed.",
        "Pool-Guthaben vor Auszahlung": "Pool balance before payout",
        "Installation wurde gestartet.": "Installation started.",
        "Effizienz-Sweep wird mit den bereits abgeschlossenen Ergebnissen fortgesetzt.": "Efficiency sweep resumed with completed results.",
        "Effizienz-Sweep neu gestartet. Leistungsgrenzen werden schrittweise gesenkt und nach dem Lauf wiederhergestellt.": "Efficiency sweep restarted. Power limits are lowered step by step and restored after the run.",
        "Abbruch angefordert. Leistungsgrenzen und Miner-Zustand werden wiederhergestellt.": "Cancellation requested. Power limits and miner state are being restored.",
        "7 Tage": "7 days",
        "Keine Miner-Software passt zu diesem Filter.": "No miner software matches this filter.",
        "Entwicklergebühr": "Developer fee",
        "Plattform": "Platform",
        "Projekt": "Project",
        "Installation erfolgt aus der verifizierten offiziellen Release-Quelle.": "Installation uses the verified official release source.",
        "Projektseite ↗": "Project page ↗",
        "Deinstallieren": "Uninstall",
        "Keine Hashrate": "No hashrate",
        "Miner- oder Host-Sensor": "Miner or host sensor",
        "Journal wird geladen": "Loading energy journal",
        "Einrichten": "Set up",
        "Wallet & Pool bearbeiten →": "Edit wallet & pool →",
        "Es wurden keine GPUs erkannt.": "No GPUs were detected.",
        "Konfiguration fehlt": "Configuration is missing",
        "Pool muss stratum+tcp/ssl://host:port sein": "Pool must use stratum+tcp/ssl://host:port",
        "Proxy muss stratum+tcp://host:port sein": "Proxy must use stratum+tcp://host:port",
        "Worker muss 1–32 Zeichen aus Buchstaben, Zahlen, _ oder - enthalten": "Worker must contain 1–32 letters, digits, _ or -",
        "Mindestens eine GPU im Format NVIDIA:0 oder AMD:0 wählen": "Select at least one GPU in NVIDIA:0 or AMD:0 format",
        "SolarMiner-Proxy-Route stimmt nicht": "SolarMiner proxy route does not match",
        "GPU-Miner konnten nicht angehalten werden": "Could not stop GPU miners",
        "Keine konfigurierte GPU erkannt": "No configured GPU detected",
        "Unbekannter GPU-Coin": "Unknown GPU coin",
        "GPU-Coin-Konfiguration fehlt": "GPU coin configuration is missing",
        "Pearl-Konfiguration fehlt": "Pearl configuration is missing",
        "Der lokale Proxy-Prozess wurde noch nicht gestartet.": "The local proxy process has not started yet.",
        "Gespeicherter Power-Cap konnte nach dem letzten Abbruch noch nicht wiederhergestellt werden": "Saved power cap could not yet be restored after the last interruption",
        "Treibergrenzen oder Nutzergrenzen nicht sicher verifizierbar": "Driver or user limits could not be verified safely",
        "Treiber-Readback stimmt nicht überein": "Driver readback does not match",
        "Treiber bestätigte den Power-Cap nicht": "Driver did not confirm the power cap",
        "GPU-Befehl hat das Zeitlimit überschritten": "GPU command timed out",
        "GPU-Tool-Ausgabe überschreitet 32 KiB": "GPU tool output exceeds 32 KiB",
        "Zuweisung fehlt": "Assignment is missing",
        "Coin passt nicht zur Hardware": "Coin is incompatible with this hardware",
        "Der laufende Worker konnte nicht angehalten werden": "Could not stop the running worker",
        "Miner-Software unterstützt diesen Coin nicht": "Miner software does not support this coin",
        "Miner-Software ist nicht einsatzbereit": "Miner software is not ready",
        "Miner-Auswahl konnte nicht gespeichert werden": "Could not save miner selection",
        "Worker-Zuweisung konnte nicht gespeichert werden": "Could not save worker assignment",
        "Gerät wurde nicht erkannt": "Device was not detected",
        "Leistungsziel konnte nicht sicher angewendet werden": "Could not safely apply power target",
        "Miner konnte nicht gestartet werden": "Could not start miner",
        "Lokale Steuereinstellungen konnten nicht gespeichert werden": "Could not save local control settings",
        "Worker wurde nicht erkannt": "Worker was not detected",
        "Worker-Freigabe konnte nicht gespeichert werden": "Could not save worker permission",
        "Vorheriger Worker konnte nicht angehalten werden": "Could not stop previous worker",
        "Standard konnte nicht gespeichert werden": "Could not save default",
        "Coin-Policy muss AUTO oder FIXED sein": "Coin policy must be AUTO or FIXED",
        "Node-Steuerung ist während eines Benchmarks vorübergehend gesperrt": "Node control is temporarily locked during a benchmark",
        "Externe Steuerung wurde lokal deaktiviert": "External control was disabled locally",
        "GPU-Leistungsgrenzen fehlen": "GPU power limits are missing",
        "GPU-Leistungsgrenzen konnten nicht gespeichert oder vom Treiber verifiziert werden": "GPU power limits could not be saved or verified by the driver",
        "GPU-Leistungsgrenzen können während eines Messlaufs nicht geändert werden": "GPU power limits cannot be changed during a benchmark run",
        "GPU-Leistungsgrenzen liegen außerhalb der aktuellen Treibergrenzen": "GPU power limits are outside the current driver range",
        "Effizienzkurven wurden nicht hochgeladen: Teilen ist deaktiviert.": "Efficiency curves were not uploaded: sharing is disabled.",
        "Keine Effizienz-Messpunkte zum Hochladen vorhanden.": "No efficiency samples available to upload.",
        "Vollständige Referenzkurve": "Full reference curve",
        "Vollständige GPU-Kurve": "Full GPU curve",
        "Wartet auf Ergebnis der Referenzkarte": "Waiting for the reference GPU result",
        "Aus unterbrochenem Sweep übernommen": "Reused from interrupted sweep",
        "Referenz fertig; wartet auf freie GPU": "Reference complete; waiting for a free GPU",
        "Abgebrochen; Leistungsgrenzen und Miner-Zustand wiederhergestellt": "Cancelled; power limits and miner state restored",
        "Fertig; Leistungsgrenzen und Miner-Zustand wiederhergestellt": "Complete; power limits and miner state restored",
        "Referenzkarte lieferte keinen stabilen Ausgangswert": "Reference GPU did not produce a stable starting point",
        "Leistungsgrenze wurde nicht vom Treiber bestätigt; Schritt übersprungen": "Driver did not confirm the power limit; step skipped",
        "Treiber hat die Leistungsgrenze nicht bestätigt": "Driver did not confirm the power limit",
        "Unterbrochen": "Interrupted",
        "Miner-Prozess ausgefallen": "Miner process exited",
        "GPU-Temperatur nicht lesbar; Sicherheitshalber abgebrochen": "GPU temperature unavailable; cancelled for safety",
        "Miner liefert trotz Pool-Jobs keine Hashrate": "Miner reports no hashrate despite pool jobs",
        "Miner-Worker wird noch nicht gemeldet; keine Hashrate innerhalb der Startwartezeit": "Miner worker has not appeared; no hashrate was reported within the startup grace period",
        "Keine gültigen Messpunkte": "No valid samples",
        "Kein stabiler Wert gefunden": "No stable value found",
        "Power-Cap nicht verfügbar": "Power cap unavailable",
        "Sweep fehlgeschlagen": "Sweep failed",
        "Sehr verbreitet": "Widely used",
        "RandomX-Optimierungen und Huge Pages": "RandomX optimizations and Huge Pages",
        "Nur CPU-Pfad im PC-Agent": "Only CPU path in the PC agent",
        "Gemeinsamer GPU-Installer": "Shared GPU installer",
        "Pro-GPU-Steuerung": "Per-GPU control",
        "Treiber- und GPU-Kompatibilität erforderlich": "Driver and GPU compatibility required",
        "KAWPOW-Unterstützung": "KAWPOW support",
        "ETCHash-Unterstützung": "ETCHash support",
        "End-to-End-Route noch experimentell": "End-to-end route remains experimental",
        "BLAKE3-Decred-Unterstützung": "BLAKE3 Decred support",
        "Bereits verwendeter GPU-Installer": "Existing GPU installer",
        "GPU und Poolkonto erforderlich": "GPU and pool account required",
        "SolarMiner-Fee-Ziel noch nicht provisioniert": "SolarMiner fee target not provisioned yet",
        "Auf AMD-GPUs spezialisiert": "Specialized for AMD GPUs",
        "Dokumentierte lokale API": "Documented local API",
        "Nur AMD": "AMD only",
        "ETC-Dev-Fee ist auf Polaris niedriger": "ETC dev fee is lower on Polaris",
        "Adapter und reale Fee-/Share-Prüfung stehen aus": "Adapter and real fee/share verification are pending",
        "Noch nicht technisch integriert": "Not technically integrated yet",
        "Noch nicht integrierter Adapter: kein Download, Start oder Fee-Pfad verfügbar.": "Adapter not integrated yet: download, start, and fee path are unavailable.",
        "Kryptex QTC pool support": "Kryptex QTC pool support",
        "NVIDIA and AMD RDNA GPU path": "NVIDIA and AMD RDNA GPU path",
        "2.5% miner fee": "2.5% miner fee",
        "SolarMiner QTC fee route and accepted-share credits are not yet verified": "SolarMiner QTC fee route and accepted-share credits are not yet verified",
        "SRBMiner entfernt; die Pearl-Konfiguration bleibt gespeichert.": "SRBMiner removed; the Pearl configuration remains saved.",
        "XMRig entfernt; die Pool-Konfiguration bleibt gespeichert.": "XMRig removed; the pool configuration remains saved.",
        "Warte auf Pool-Job": "Waiting for a pool job",
        "Pool verbunden, warte auf GPU-Hashrate": "Pool connected; waiting for GPU hashrate",
        "Miner-API nicht erreichbar": "Miner API unavailable",
        "Pool-Job oder Fee-Route nicht verfügbar": "Pool job or fee route unavailable",
        "GPU liefert trotz Pool-Jobs keine Hashrate; SRBMiner- und GPU-Treiber prüfen": "GPU reports no hashrate despite pool jobs; check SRBMiner and GPU drivers",
        "Miner pausiert": "Miner paused",
        "GPU-Miner pausiert": "GPU miner paused",
        "Noch kein Effizienz-Profil. Starte einen Test, um stabile Power-Limits zu ermitteln.": "No efficiency profile yet. Start a test to find stable power limits.",
        "Kein stabiler Power-Limit-Wert gefunden.": "No stable power limit found.",
        "{coin}: Speichern fehlgeschlagen: {error}": "{coin}: Could not save: {error}",
        "Wallet-Daten konnten nicht geladen werden: {error}": "Could not load wallet data: {error}",
        "kein Miner": "no miner",
        "Zuweisung fehlgeschlagen: {error}": "Assignment failed: {error}",
        "Dashboard": "Dashboard",
        "Benchmarks": "Benchmarks",
        "Proxy": "Proxy",
        "SolarMiner · Dashboard": "SolarMiner · Dashboard",
        "SolarMiner · Benchmarks": "SolarMiner · Benchmarks",
        "SolarMiner · Miner": "SolarMiner · Miners",
        "SolarMiner · Wallets": "SolarMiner · Wallets",
        "SolarMiner · Worker": "SolarMiner · Workers",
        "SolarMiner PC-Agent ·": "SolarMiner PC-Agent ·",
        "Anonyme Leistungswerte teilen": "Share anonymous performance data",
        "Benchmark starten": "Start benchmark",
        "ERGEBNISSE": "RESULTS",
        "Effizienz-Sweep läuft": "Efficiency sweep in progress",
        "FREIWILLIGES TEILEN": "OPTIONAL SHARING",
        "HARDWARE-VERGLEICH": "HARDWARE COMPARISON",
        "MESSLAUF": "BENCHMARK RUN",
        "POWER-LIMIT-SWEEP": "POWER LIMIT SWEEP",
        "Power-Limit schrittweise senken und Stabilität prüfen": "Lower the power limit step by step and check stability",
        "Power-Limit-Effizienztest": "Power limit efficiency test",
        "Starte einen Messlauf, um Ergebnisse anzuzeigen.": "Start a benchmark run to see results.",
        "Sweep abbrechen": "Cancel sweep",
        "Upload erneut versuchen": "Retry upload",
        "Zur Mining-Seite": "Go to mining page",
        "Wallets einrichten →": "Set up wallets →",
        "0 installiert": "0 installed",
        "0 verfügbar": "0 available",
        "JSON ↗": "JSON ↗",
        "LEISTUNG": "POWER",
        "Sensor-Details": "Sensor details",
        "Gerät": "Device",
        "WORKER EINRICHTEN": "SET UP WORKER",
        "Proxy-Log": "Proxy log",
        "Neustart konnte nicht angefordert werden: {error}": "Could not request restart: {error}",
        "Wallet hinterlegt": "Wallet configured",
        "≈ {value} · {description}": "≈ {value} · {description}",
        "{coin} · im Pool": "{coin} · in pool",
        "Noch keine Wallet hinterlegt · Wallets öffnen": "No wallet configured · Open wallets",
        "Bekannter Wert ≈ {value}": "Known value ≈ {value}",
        "BTC-Preis —": "BTC price —",
        "Frei": "Unassigned",
        "Aktualisiert {time}": "Updated {time}",
        "{phase} · Warte auf die ersten gültigen Hashrate-Messpunkte …": "{phase} · Waiting for the first valid hashrate samples …",
        "{hardware} · {count} Messpunkte": "{hardware} · {count} samples",
        "Vergleich": "Comparison",
        "Median": "Median",
        "dem Median": "the median",
        "{phase} · Schritt {step}/{total}": "{phase} · step {step}/{total}",
        "({count} Messwerte)": "({count} samples)",
        "Messlauf gestartet …": "Benchmark started …",
        "{coin} · getestet {date}": "{coin} · tested {date}",
        "Bester stabiler Wert: {watts} W · {hashrate} · {power} W · {efficiency}": "Best stable value: {watts} W · {hashrate} · {power} W · {efficiency}",
        "Min. {watts} W": "Min. {watts} W",
        "Max. {watts} W": "Max. {watts} W",
        "{gpu}: Leistungsgrenzen gespeichert.": "{gpu}: Power limits saved.",
        "Treiber {min}–{max} W": "Driver {min}–{max} W",
        "Limit {watts} W": "Limit {watts} W",
        "Aufnahme {watts}": "Draw {watts}",
        "Hardwaredaten konnten nicht geladen werden: {error}": "Could not load hardware data: {error}",
        "{count} Worker mit Fehler": "{count} workers with errors",
        "{coin}: Pool, Auszahlung oder Geräteauswahl fehlen.": "{coin}: Pool, payout, or device selection is missing.",
        "≈ {value}/Tag": "≈ {value}/day",
        "Kein Pool gemeldet": "No pool reported",
        "Session": "Session",
        "{count} Worker · {duration}": "{count} workers · {duration}",
        "Stromkosten": "Electricity cost",
        "Komponentenverbrauch aus verfügbaren CPU-Package- und GPU-Board-Sensoren · Tarif {tariff} {currency}/kWh. Netzteil- und übrige Systemverluste können fehlen.": "Component consumption from available CPU package and GPU board sensors · Rate {tariff} {currency}/kWh. Power supply and other system losses may be missing.",
        "Strompreis in {currency} pro kWh": "Electricity rate in {currency} per kWh",
        "Proxy-Daten konnten nicht geladen werden: {error}": "Could not load proxy data: {error}",
        "Suche fehlgeschlagen: {error}": "Discovery failed: {error}",
        "{count} installiert": "{count} installed",
        "{count} verfügbar": "{count} available",
        "Aktion fehlgeschlagen: {error}": "Action failed: {error}",
        "{count} Geräte": "{count} devices",
        "{available} von {total} Messwerten verfügbar": "{available} of {total} readings available",
        "Stand {time}": "As of {time}",
        "Sensorzugriff: {status}": "Sensor access: {status}",
        "Sensordaten konnten nicht dargestellt werden: {error}": "Could not display sensor data: {error}",
        "Telemetrie konnte nicht geladen werden: {error}": "Could not load telemetry: {error}",
        "Rohwert: {value}": "Raw value: {value}",
        "{percent} % Messabdeckung": "{percent}% measurement coverage",
        "Tarif {price} {currency}/kWh": "Rate {price} {currency}/kWh",
        "{duration} · {percent} % gemessen": "{duration} · {percent}% measured",
        "{worker} wurde gestartet.": "{worker} started.",
        "{worker} wurde pausiert.": "{worker} paused.",
        "Worker konnten nicht geladen werden: {error}": "Could not load workers: {error}",
        "Miner-Katalog konnte nicht geladen werden: {error}": "Could not load miner catalog: {error}",
        "Aktiviere zuerst die globale Remote-Steuerung, um Worker freizugeben.": "Enable global remote control first to make workers available.",
        "aktiv": "enabled",
        "Automatisierung konnte nicht geladen werden: {error}": "Could not load automation: {error}",
        "Diesen Worker für die Node freigeben": "Make this worker available to the Node",
        "Die Dev-Fee wechselt sofort zurück auf den Proxy-Tarif von 1 %.": "The dev fee immediately returns to the 1% proxy tier.",
        "Wenn du die Remote-Steuerung ausschaltest, kann kein SolarMiner Node diesen Agenten kontrollieren. Die Dev-Fee wechselt sofort zurück auf den Proxy-Tarif von 1 %.": "When you turn off remote control, no SolarMiner Node can control this agent. The dev fee immediately returns to the 1% proxy tier.",
        "Die Freigaben gelten nur, wenn die globale Remote-Steuerung aktiviert ist. Lokale Start- und Pause-Aktionen bleiben davon unabhängig.": "Permissions apply only while global remote control is enabled. Local start and pause actions remain independent.",
        "Die Node kann nur freigegebene Worker starten, pausieren und in ihre Planung aufnehmen.": "The Node can start, pause, and include only available workers in its planning.",
        "deaktiviert": "disabled",
        "Für die Node verfügbare Worker": "Workers available to the Node",
        "Freigegebene Worker": "Available workers",
        "GLOBALE FREIGABE": "GLOBAL PERMISSION",
        "Lege zentral fest, ob ein SolarMiner Node diesen PC steuern darf und welche Worker dafür verfügbar sind.": "Set centrally whether a SolarMiner Node may control this PC and which workers are available to it.",
        "Noch keine Worker eingerichtet.": "No workers configured yet.",
        "Node-Automatisierung": "Node automation",
        "Node-Automatisierung öffnen →": "Open Node automation →",
        "Node-Automatisierung verwalten →": "Manage Node automation →",
        "Node-Tarif": "Node tier",
        "SolarMiner · Node-Automatisierung": "SolarMiner · Node automation",
        "NODE-STEUERUNG": "NODE CONTROL",
        "Nur lokal": "Local only",
        "Proxy-Tarif · 1 %": "Proxy tier · 1%",
        "Remote-Steuerung": "Remote control",
        "Remote-Steuerung aktiviert. Wähle nun die verfügbaren Worker aus.": "Remote control enabled. Now choose the available workers.",
        "Remote-Steuerung durch SolarMiner Node erlauben": "Allow remote control by SolarMiner Node",
        "Remote-Steuerung deaktiviert. Die Dev-Fee nutzt wieder den Proxy-Tarif von 1 %.": "Remote control disabled. The dev fee uses the 1% proxy tier again.",
        "Remote-Steuerung konnte nicht geändert werden: {error}": "Could not change remote control: {error}",
        "Wähle unter Node-Automatisierung mindestens einen Worker aus, damit der Node automatisch regeln darf.": "Choose at least one worker under Node automation so the Node can control automatically.",
        "WORKER-FREIGABEN": "WORKER PERMISSIONS",
        "Worker für die Node freigegeben.": "Worker made available to the Node.",
        "Worker auf lokale Steuerung beschränkt.": "Worker restricted to local control.",
        "Worker-Freigabe konnte nicht geändert werden: {error}": "Could not change worker permission: {error}",
        "{count} konfigurierte Worker": "{count} configured workers",
        "Abgebrochen": "Cancelled",
        "Abgeschlossene Läufe ({count})": "Completed runs ({count})",
        "Baugleiche GPUs werden pro Coin und Algorithmus gruppiert. Während eine Referenzkarte ihre Kurve ermittelt, messen die noch wartenden Schwesterkarten bereits andere Coins. Sobald die Referenz fertig ist, werden ihre Validierungen sofort freigeschaltet und auf den nächsten passenden freien Karten ausgeführt. Dieselbe GPU wird nie doppelt belegt. Nach jedem Power-Wechsel erhält der Miner bis zu vier Minuten Zeit, eine gültige Hashrate zu melden, bevor ein Schritt als instabil gilt. Ein Ergebnis wird erst gerätespezifisch gespeichert, wenn Miner, Pool, Temperatur und Shares stabil bleiben. Nach dem Lauf werden Leistungsgrenzen und Miner-Zustand verifiziert wiederhergestellt. Wartende und abgeschlossene Läufe sind zu kompakten Gruppen eingeklappt; nur der laufende Messpunkt bleibt ausgeklappt.": "Identical GPUs are grouped per coin and algorithm. While one reference card measures its curve, the still-waiting sibling cards already measure other coins. As soon as the reference finishes, its validations unlock immediately and run on the next matching free card. The same GPU is never double-booked. After every power change the miner gets up to four minutes to report a valid hashrate before a step counts as unstable. A result is stored for a device only after its miner, pool, temperature and shares remain stable. Power limits and miner state are verified and restored after the run. Waiting and completed runs are collapsed into compact groups; only the live measurement stays expanded.",
        "Fehlgeschlagen": "Failed",
        "Fertig": "Complete",
        "Geplant": "Planned",
        "Gespeicherte Effizienzprofile": "Saved efficiency profiles",
        "Läuft": "Running",
        "Parallele Gerätevalidierung": "Parallel device validation",
        "Referenzkurve": "Reference curve",
        "Startgruppe {number}": "Start group {number}",
        "Unterbrochenen Sweep fortsetzen": "Resume interrupted sweep",
        "Komplett neu starten": "Restart from scratch",
        "Power-Limit-Test starten": "Start power-limit test",
        "Ungefähre Restzeit: {time}": "Approximate time remaining: {time}",
        "ETA wird berechnet …": "Calculating ETA …",
        "{done}/{total} Geräte abgeschlossen · {phase}": "{done}/{total} devices complete · {phase}",
        "{watts} W · {samples}/{required} Messpunkte": "{watts} W · {samples}/{required} samples",
        "Übersprungen": "Skipped",
        "{coin} · {algorithm}{unavailable}": "{coin} · {algorithm}{unavailable}",
        "{count} Worker": "{count} workers",
        "{count} ausgeblendet · Filter zurücksetzen": "{count} hidden · Reset filters",
        "{count} Komponenten sind noch keinem Mining-Profil zugewiesen.": "{count} components are not assigned to a mining profile yet.",
        "derzeit nicht zuweisbar": "currently unavailable for assignment",
        "nicht installiert": "not installed",
        "nicht freigegeben": "not enabled",
        "Pool": "Pool",
        "SolarMiner-Standardroute": "SolarMiner default route",
        ", läuft": ", running",
        "1-GB-Pages für RandomX aktivieren": "Enable 1 GB pages for RandomX",
        "AKTIVE WORKER": "ACTIVE WORKERS",
        "ANONYM & EINMALIG": "ANONYMOUS & ONE-TIME",
        "API-Dokumentation ↗": "API documentation ↗",
        "ARBEITENDE GERÄTE": "WORKING DEVICES",
        "AUF DIESEM PC": "ON THIS PC",
        "AUSGESCHALTET": "DISABLED",
        "AUSGEWÄHLT": "SELECTED",
        "AUSGEWOGEN": "BALANCED",
        "AUSZAHLUNG": "PAYOUT",
        "AUTOMATISIERUNG": "AUTOMATION",
        "Abbrechen": "Cancel",
        "Abbruch angefordert. Der vorherige Mining-Zustand wird wiederhergestellt.": "Cancellation requested. The previous mining state will be restored.",
        "Abgelehnte Shares an allen gemeldeten Shares": "Rejected shares of all reported shares",
        "Ablehnungsquote": "Rejection rate",
        "Agent nicht erreichbar": "Agent unavailable",
        "Agent verbunden": "Agent connected",
        "Agent-Daten konnten nicht geladen werden:": "Could not load agent data:",
        "Agent-Modus": "Agent mode",
        "Agent-Status": "Agent status",
        "Aktion": "Action",
        "Aktionen sind danach verfügbar.": "Actions are available afterwards.",
        "Aktiv / gemeldet": "Active / reported",
        "Aktive Pools und Verbindung": "Active pools and connection",
        "Aktiver Build": "Active build",
        "Aktiver Build für diesen Coin": "Active build for this coin",
        "Aktivierung fehlgeschlagen:": "Activation failed:",
        "Aktualisiere …": "Refreshing …",
        "Aktualisieren": "Refresh",
        "Aktuell läuft kein Worker. Starte einen Miner manuell oder lass den Node dein gespeichertes Profil steuern.": "No worker is running. Start a miner manually or let the Node control your saved profile.",
        "Aktuell:": "Current:",
        "Aktuelle Messwerte": "Current readings",
        "Aktuelle Miner messen": "Measure current miners",
        "Aktuelle Pool-Difficulty": "Current pool difficulty",
        "Aktuelle Werte": "Current values",
        "Aktueller Miner-Wert": "Current miner value",
        "Aktuelles Pool-Ziel": "Current pool target",
        "Algorithmen": "Algorithms",
        "Algorithmus": "Algorithm",
        "Algorithmus für Hashrate und Effizienz": "Algorithm for hashrate and efficiency",
        "Algorithmus für Hashrate-Verlauf": "Algorithm for hashrate history",
        "Alle": "All",
        "Alle Algorithmen · nicht vergleichbar summieren": "All algorithms · do not add incomparable rates",
        "Alle GPUs": "All GPUs",
        "Alle Geräte über alle Miner hinweg. Ein Worker gehört immer zu genau einem aktiven Miner seines Coins.": "All devices across all miners. A worker always belongs to exactly one active miner of its coin.",
        "Alle Hardware": "All hardware",
        "Alle Sensoren →": "All sensors →",
        "Alle Telemetriedaten": "All telemetry data",
        "Alle Worker": "All workers",
        "Alle Worker →": "All workers →",
        "Alle bekannten Abzüge werden live aufgeschlüsselt. SolarMiner nutzt eine verpflichtende Fee-Route; ist sie nicht erreichbar, startet der Miner nicht.": "All known deductions are itemised live. SolarMiner uses a mandatory fee route; if it is unavailable, the miner will not start.",
        "Alle bekannten Abzüge werden live aufgeschlüsselt. SolarMiner nutzt eine verpflichtende Fee-Route; ist sie nicht erreichbar, startet der betreffende Miner nicht. Pool- und Miner-Gebühren sind zusätzlich ausgewiesen.": "All known deductions are broken down live. SolarMiner uses a mandatory fee route; if it is unavailable, the affected miner will not start. Pool and miner fees are listed separately.",
        "Alle erkannten Geräte": "All detected devices",
        "Als aktiven Build wählen": "Set as active build",
        "Anonyme Benchmarks teilen": "Share anonymous benchmarks",
        "Anzeigewährung": "Display currency",
        "Architektur unbekannt": "Unknown architecture",
        "Asien": "Asia",
        "Auf diesem PC · lokal im Netzwerk": "On this PC · local network",
        "Aufteilung der Dev-Fee:": "Dev fee split:",
        "Ausgewogen": "Balanced",
        "Ausgewählte Geräte dieses Coins mit Pool-Kontakt": "Selected devices of this coin with pool contact",
        "Ausgewählter Worker": "Selected worker",
        "Auswahl aufheben": "Clear selection",
        "Auswahl installieren": "Install selection",
        "Auszahlung": "Payout",
        "Auszahlung & Worker": "Payout & worker",
        "Auszahlung an das SolarMiner-Standardziel": "Payout to the SolarMiner standard destination",
        "Automatisierung": "Automation",
        "BETRIEB": "OPERATION",
        "Beim Entfernen wird der Miner angehalten. Deine Pool- und Wallet-Konfiguration bleibt gespeichert.": "Removing the miner stops it. Your pool and wallet configuration is kept.",
        "Bekannter Wert ≈": "Known value ≈",
        "Benchmark konnte nicht gestartet werden.": "Could not start the benchmark.",
        "Benchmark läuft": "Benchmark running",
        "Benchmark läuft bis genügend Messpunkte vorliegen": "Benchmark runs until enough observations are collected",
        "Benchmark-Status konnte nicht geladen werden. Vorhandene Ergebnisse bleiben sichtbar.": "Could not load benchmark status. Existing results remain visible.",
        "Benchmark-Upload": "Benchmark upload",
        "Benchmarkdaten erfolgreich ans Backend übermittelt.": "Benchmark data sent successfully.",
        "Benchmarkdaten werden hochgeladen …": "Uploading benchmark data …",
        "Benchmarkdaten wurden nicht hochgeladen: Teilen ist deaktiviert.": "Benchmark data was not uploaded: sharing is disabled.",
        "Beobachtet Miner, die bereits laufen. Startet oder pausiert keine Worker. Ideal, um deine aktuelle Konfiguration zu prüfen.": "Observes miners that are already running. Does not start or pause workers. Use it to check your current configuration.",
        "Bereit": "Ready",
        "Bereit zum Starten": "Ready to start",
        "Bereit, wenn du es bist.": "Ready when you are.",
        "Bereits ausgezahlter Bestand": "Already paid balance",
        "Beste Share-Difficulty": "Best share difficulty",
        "Bestätige die Windows-UAC-Abfrage. Ohne Freigabe bleiben Agent und Telemetrie gesperrt.": "Approve the Windows UAC prompt. Without approval, the agent and telemetry remain blocked.",
        "Bestätige die Windows-UAC-Abfrage. Ohne Freigabe fehlen die Windows-Sensoren. Einige Mining-Steuerungsaktionen benötigen diesen Sensorzugriff.": "Approve the Windows UAC prompt. Without approval, Windows sensors are unavailable. Some mining controls require sensor access.",
        "Bestätige zuerst die Auszahlung an das SolarMiner-Standardziel.": "First confirm the payout to the SolarMiner standard destination.",
        "Betriebsdaten werden geladen …": "Loading operational data …",
        "Betriebsstatus": "Operating status",
        "Bezeichnung für diesen PC im Pool, maximal 32 Zeichen aus Buchstaben, Zahlen, _ und -.": "Name for this PC in the pool, up to 32 letters, digits, underscores or hyphens.",
        "Bitte bestätige die Windows-Sicherheitsabfrage. Der PC-Agent wartet auf die Administratorfreigabe.": "Please approve the Windows security prompt. The PC agent is waiting for administrator approval.",
        "Bitte gib einen gültigen positiven Strompreis ein.": "Enter a valid positive electricity rate.",
        "Bleibt lokal. Für den Node: 0 W.": "Stays local. For the Node: 0 W.",
        "Brutto · vor Pool-, Miner- und SolarMiner-Gebühren": "Gross · before pool, miner and SolarMiner fees",
        "Brutto-Prognose aus aktueller Hashrate und Netzwerkdaten. Gebühren und Stromkosten sind nicht abgezogen; dies ist keine Pool-Gutschrift.": "Gross projection from current hashrate and network data. Fees and electricity costs are not deducted; this is not a pool credit.",
        "Brutto-Prognose aus aktueller Hashrate und Netzwerkdaten. Gebühren und Stromkosten sind noch nicht abgezogen; dies ist keine Pool-Gutschrift.": "Gross forecast from current hashrate and network data. Fees and electricity costs have not been deducted; this is not a pool credit.",
        "Brutto-Schätzung": "Gross estimate",
        "CPU Mining": "CPU mining",
        "CPU starten": "Start CPU",
        "CPU und GPU getrennt erfasst": "CPU and GPU recorded separately",
        "CPU- und GPU-Miner können parallel laufen.": "CPU and GPU miners can run in parallel.",
        "Coin & Algorithmus": "Coin & algorithm",
        "Coin wählen": "Choose coin",
        "Coin, Pool oder Adresse suchen": "Search coin, pool or address",
        "Coin-Routen & Verbindungsdetails": "Coin routes & connection details",
        "Coins & Miner": "Coins & miners",
        "Coins mit Pool": "Coins with a pool",
        "Coins mit Pool:": "Coins with a pool:",
        "DATEN VERALTET": "STALE DATA",
        "DEIN MINING-BETRIEB": "YOUR MINING OPERATIONS",
        "DEIN PC IM ÜBERBLICK": "YOUR PC AT A GLANCE",
        "DEINE STANDARDKONFIGURATION": "YOUR DEFAULT CONFIGURATION",
        "Daten veraltet": "Data stale",
        "Dein Arbeitsbereich": "Your workspace",
        "Dein PC arbeitet.": "Your PC is working.",
        "Dein erster Miner": "Your first miner",
        "Dein lokaler Mining-Agent": "Your local mining agent",
        "Deine Messungen": "Your measurements",
        "Deine Miner": "Your miners",
        "Den zugehörigen Miner öffnen": "Open the related miner",
        "Der Agent hat die Verbindungsänderung abgelehnt. Prüfe Sensorfreigabe, Proxy und Agent-Logs.": "The agent rejected the connection change. Check sensor permissions, the proxy and agent logs.",
        "Der Agent hat die Änderung abgelehnt.": "The agent rejected the change.",
        "Der Agent hält keine Messhistorik vor; der Verlauf beginnt mit diesem Aufruf.": "The agent keeps no measurement history; the chart starts with this page load.",
        "Der Miner kann 1-GB-Pages möglicherweise nicht reservieren und auf normale Huge Pages zurückfallen. Der zusätzlich benötigte Speicher steht dem System und anderen Programmen währenddessen nicht zur Verfügung.": "The miner may be unable to reserve 1 GB pages and fall back to regular Huge Pages. The additional memory is unavailable to the system and other programs while mining.",
        "Der Miner läuft nicht. Starten übernimmt die gespeicherte Konfiguration.": "The miner is not running. Starting uses the saved configuration.",
        "Der Miner spricht immer den Proxy an": "The miner always talks to the proxy",
        "Der Miner spricht immer den SolarMiner-Proxy an. Hier wählst du, welches Pool-Ziel der Proxy für einen Coin verwendet.": "The miner always connects to the SolarMiner proxy. Choose the pool destination the proxy uses for a coin here.",
        "Der Miner verbindet sich über den SolarMiner-Proxy; die Pool-Adresse wird dort als Ziel verwendet.": "The miner connects through the SolarMiner proxy, which uses the pool address as its destination.",
        "Der Name darf höchstens 40 Zeichen haben und keine Steuerzeichen enthalten": "The name may be at most 40 characters and must not contain control characters",
        "Der Node darf Wattziele innerhalb deiner Grenzen verteilen. Ohne Regelung bleiben Start und Pause möglich.": "The Node may allocate power within your limits. Start and pause remain available without dynamic control.",
        "Der Node darf das Geräteprofil steuern.": "The Node may control the device profile.",
        "Der PC-Agent funktioniert eigenständig. Ein SolarMiner Node ergänzt später Automatisierung, PV-Überschuss und weitere Homelab-Geräte.": "The PC agent works on its own. A SolarMiner Node can later add automation, PV surplus and other homelab devices.",
        "Der PC-Agent ist nicht erreichbar.": "The PC-Agent is unreachable.",
        "Der PC-Agent sendet regelmäßig aktive Hashrate sowie verfügbare Leistungsaufnahme und Leistungsziel. Hardwaremodell und Algorithmus werden für den Vergleich benötigt. Keine Wallets, Pool-Zugangsdaten, IP-Adresse oder sonstige Sensordaten.": "The PC agent periodically sends active hashrate, available power usage and power target. Hardware model and algorithm are needed for comparison. Wallets, pool credentials, IP addresses and other sensor data are excluded.",
        "Der PC-Agent sucht die neueste Proxy-Version auf GitHub.": "The PC-Agent is looking for the newest proxy version on GitHub.",
        "Der Proxy ist nicht erreichbar; Poolwechsel ist erst nach der Verbindung möglich.": "The proxy is unavailable; switching pools works only after connecting.",
        "Der Proxy ist nicht erreichbar; ohne ihn starten die Miner nicht.": "The proxy is unavailable; miners cannot start without it.",
        "Der Proxy verbindet deine Miner mit den Pools und stellt die SolarMiner-Fee-Route bereit. Verwende den enthaltenen Proxy oder wähle bewusst einen Server in deinem Netzwerk.": "The proxy connects miners to pools and provides the SolarMiner fee route. Use the included proxy or choose a server on your network.",
        "Der Proxy würfelt pro Job, ob deine Miner für dich oder für ein Fee-Ziel arbeiten. Zufällig würfelt jeden Job unabhängig; über kurze Zeiträume kann die Verteilung dadurch abweichen. Ausgewogen merkt sich die bisherigen Züge und hält die Verteilung nah an den Zielprozenten.": "The proxy rolls per job whether your miners work for you or for a fee target. Random rolls every job independently, so short-term distribution can drift. Balanced remembers past draws and keeps the distribution close to the target percentages.",
        "Der SolarMiner Node setzt seinen Referral-Key automatisch erneut, sobald er den Agenten steuert.": "The SolarMiner Node automatically sets its referral key again when it controls this agent.",
        "Der SolarMiner-Stratum-Proxy konnte nicht geladen oder gestartet werden.": "The SolarMiner Stratum proxy could not be downloaded or started.",
        "Der SolarMiner-Stratum-Proxy wird heruntergeladen.": "The SolarMiner Stratum proxy is downloading.",
        "Der Upload konnte nicht erneut gestartet werden.": "Could not retry the upload.",
        "Der anonyme Upload ist fehlgeschlagen. Du kannst ihn über „Upload erneut versuchen“ wiederholen.": "The anonymous upload failed. You can retry it via “Try upload again”.",
        "Der anonyme Upload konnte nicht gestartet werden.": "The anonymous upload could not be started.",
        "Der enthaltene Proxy läuft auf diesem PC.": "The included proxy is running on this PC.",
        "Der lokale Mining-Proxy wird gestartet.": "The local mining proxy is starting.",
        "Der lokale Proxy ist noch nicht bereit.": "The local proxy is not ready yet.",
        "Diagnose": "Diagnostics",
        "Die Ergebnisse wurden einmalig anonym hochgeladen. Die Einstellung für regelmäßige Benchmarks bleibt unverändert.": "The results were uploaded once anonymously. The periodic benchmark setting stays unchanged.",
        "Die Freigabe konnte nicht gelesen werden. Aktualisiere die Seite, bevor du sie änderst.": "Could not read sharing permissions. Refresh the page before changing them.",
        "Die Freigabe konnte nicht gespeichert werden.": "Could not save permission.",
        "Die GPU-, Treiber- und SRBMiner-Kompatibilität hängt von Modell, Version und Betriebssystem ab. Für diese Prüfung gibt es derzeit keine automatische Aktivierungsaktion.": "GPU, driver and SRBMiner compatibility depends on model, version and operating system. This check has no automatic activation action.",
        "Die Komponente bleibt frei und kann nicht gestartet werden.": "This component remains unassigned and cannot be started.",
        "Die Mining-Verbindung ist nicht erreichbar. Prüfe lokalen oder externen Proxy.": "The mining connection is unreachable. Check the local or external proxy.",
        "Die Software ist bereit und kann einem Worker zugewiesen werden.": "The software is ready and can be assigned to a worker.",
        "Die Steuerung ist erlaubt, aber noch kein Gerät dem Node zugeordnet.": "Control is allowed, but no device is assigned to the Node yet.",
        "Die Treiber haben diese Grenzen abgelehnt. Der Entwurf bleibt zum Prüfen erhalten.": "The drivers rejected these limits. Your draft is retained for review.",
        "Die Werte kommen live vom SolarMiner Fee-Backend und ändern sich mit Modus und Referral-Code.": "Values come live from the SolarMiner fee backend and change with the mode and referral code.",
        "Dev-Fee": "Dev fee",
        "Dev-Fee-Aufteilung gerade nicht abrufbar – Proxy-Verbindung prüfen.": "Dev fee split currently unavailable – check the proxy connection.",
        "Die globale Node-Automatik kann zusätzlich alle Freigaben sperren.": "Global Node automation can also block all device permissions.",
        "Die laufenden Worker findest du unten. Manuelle Starts und das Node-Profil lassen sich unabhängig steuern.": "See your running workers below. Manual starts and the Node profile are controlled independently.",
        "Die lokale Einstellung konnte nicht gespeichert werden.": "Could not save the local setting.",
        "Diese Werte beschreiben deinen PC. Leistungsaufnahme und Temperaturen sind von Mining-Hashrate und Node-Leistungsziel getrennt. „—“ bedeutet: kein verfügbarer Messwert.": "These readings describe your PC. Power use and temperatures are separate from mining hashrate and the Node power target. “—” means no reading is available.",
        "Diesen Installationsordner ausnehmen": "Exclude this installation folder",
        "Diesen Miner installieren": "Install this miner",
        "Dieser Coin hat noch keinen Miner im Katalog.": "This coin has no miner in the catalog yet.",
        "Dieser Agent läuft ohne SolarMiner Node – reduzierte Proxy-Gebühr.": "This agent runs without a SolarMiner Node – reduced proxy fee.",
        "Dieser Miner ist noch nicht installiert. Nach der Installation kannst du Pool, Wallet und Geräte konfigurieren.": "This miner is not installed yet. After installation you can configure pool, wallet and devices.",
        "Dieses Gerät für die Node freigeben": "Allow the Node to control this device",
        "Dokumentation öffnen ↗": "Open documentation ↗",
        "Du entscheidest lokal. Der Node darf keine Miner starten.": "You control mining locally. The Node may not start miners.",
        "Du hast das regelmäßige Benchmark-Teilen deaktiviert. Möchtest du die gerade gemessenen Ergebnisse einmalig anonym an SolarMiner übermitteln? Die Einstellung für regelmäßige Benchmarks bleibt unverändert.": "You have disabled periodic benchmark sharing. Would you like to upload the results you just measured once, anonymously, to SolarMiner? The periodic benchmark setting stays unchanged.",
        "Dynamische Leistungsregelung": "Dynamic power control",
        "Dynamische Leistungsregelung ist ausgeschaltet. Aktiviere sie oben, um Grenzen zu bearbeiten.": "Dynamic power control is disabled. Enable it above to edit limits.",
        "EINGESCHALTET": "ENABLED",
        "ENERGIE": "ENERGY",
        "ENERGIE-JOURNAL": "ENERGY JOURNAL",
        "ERTRAGSPROGNOSE": "EARNINGS FORECAST",
        "ETCHash-fähige GPU und Treiber": "ETCHash-capable GPU and driver",
        "Effizienz": "Efficiency",
        "Eigene Adresse": "Custom address",
        "Eigene Auszahlung ist konfiguriert.": "Your own payout is configured.",
        "Eigene Auszahlung: Pool und Wallet gehören zusammen.": "Own payout: pool and wallet belong together.",
        "Eigene Auszahlung: Pool und Wallet müssen zueinander passen.": "Own payout: pool and wallet must match each other.",
        "Eigene Auszahlungsadresse": "Your payout address",
        "Eigene Pool-Adresse": "Custom pool address",
        "Eigene Pool-Adresse verwenden": "Use a custom pool address",
        "Eigene Wallet": "Own wallet",
        "Eigene Wallet eintragen oder das SolarMiner-Standardziel bewusst bestätigen.": "Enter your own wallet or explicitly confirm the SolarMiner standard destination.",
        "Eigener Pool": "Custom pool",
        "Eigener/noch nicht katalogisierter Pool": "Custom / not yet catalogued pool",
        "Eigenes Ziel übernehmen": "Apply custom target",
        "Ein Haken zeigt eine Einstellung an, die SolarMiner für den Miner aktiviert hat. Ob der Miner sie vollständig nutzen kann, siehst du in seiner Ausgabe.": "A checkmark indicates a setting SolarMiner enabled for the miner. Check its output to see whether the miner can fully use it.",
        "Ein Miner ist die Kombination aus Coin und Miner-Programm. Mehrere Programme pro Coin sind installierbar; gestartet wird das markierte.": "A miner is the combination of a coin and a miner program. Several programs per coin can be installed; the marked one is the one that starts.",
        "Ein Miner meldet einen Fehler. Seine Konsole hilft bei der Diagnose.": "A miner is reporting an error. Its console can help diagnose the issue.",
        "Ein Paket kann mehrere Coins bereitstellen. Wähle alle gewünschten Miner und starte die Installation gemeinsam.": "One package can support multiple coins. Select every miner you want and start the installations together.",
        "Ein Programm kann mehrere Coins bedienen. Mehrere Installationen pro Coin sind möglich; gestartet wird je Coin das als aktiv markierte Programm.": "One program can serve several coins. Multiple installations per coin are possible; per coin, the marked program is the one that starts.",
        "Ein SolarMiner Node steuert oder fragt diesen Agenten ab – volle Automatisierungsgebühr.": "A SolarMiner Node steers or reads this agent – full automation fee.",
        "Ein Wechsel pausiert alle Miner. Starte sie anschließend bewusst neu.": "Switching pauses all miners. Restart them when you are ready.",
        "Ein Wechsel startet den lokalen Proxy neu und pausiert alle Miner. Gilt nur für den lokalen Proxy; ein externer Proxy behält seine eigene Einstellung.": "Switching restarts the local proxy and pauses all miners. It applies to the local proxy only; an external proxy keeps its own setting.",
        "Ein Worker braucht Aufmerksamkeit.": "A worker needs attention.",
        "Ein erreichbarer Proxy ist Voraussetzung für Mining. Listener und Fee-Ziel werden für jeden Coin separat geprüft. Vorbereitete Coins erhalten dadurch keine Produktionsfreigabe.": "Mining requires a reachable proxy. The listener and fee target are checked separately for each coin. Prepared coins are not released by this action.",
        "Eine Wallet wird zusammen mit dem Pool-Ziel gespeichert. Für GPUs werden dabei alle erkannten GPUs vorbereitet; welche davon tatsächlich minen, bestimmst du später unter Worker.": "A wallet is saved together with its pool destination. All detected GPUs are prepared; choose which ones mine later in Workers.",
        "Einrichtung": "Setup",
        "Einrichtung abschließen": "Complete setup",
        "Einrichtung erforderlich": "Setup required",
        "Einrichtung fehlt: Pool, Wallet und dieses Gerät auswählen.": "Setup required: choose the pool, wallet and this device.",
        "Einrichtung unvollständig": "Setup incomplete",
        "Einstellung wurde gespeichert, ist auf diesem System aber noch nicht aktiv.": "The setting was saved, but is not active on this system yet.",
        "Empfohlen": "Recommended",
        "Energie heute": "Energy today",
        "Energie-API:": "Energy API:",
        "Energiedaten konnten nicht geladen werden.": "Energy data could not be loaded.",
        "Entfernt XMRig. Der Miner wird vorher pausiert; gespeicherte Pool- und Wallet-Daten bleiben erhalten.": "Removes XMRig. The miner is paused first; saved pool and wallet data is kept.",
        "Entfernt die gemeinsame SRBMiner-Installation. Alle GPU-Miner (Pearl, Ravencoin, Ethereum Classic) werden vorher gestoppt; gespeicherte Pool- und Wallet-Daten bleiben erhalten.": "Removes the shared SRBMiner installation. All GPU miners (Pearl, Ravencoin, Ethereum Classic) are stopped first; saved pool and wallet data is kept.",
        "Ergebnisse mit SolarMiner teilen?": "Share results with SolarMiner?",
        "Erneut versuchen": "Try again",
        "Erneuter Versuch fehlgeschlagen:": "Retry failed:",
        "Erneuter Versuch fehlgeschlagen: {error}": "Retry failed: {error}",
        "Erneuter Versuch läuft …": "Retrying …",
        "Ertrag": "Earnings",
        "Ertrag pro Tag": "Daily earnings",
        "Ertrag pro kWh": "Earnings per kWh",
        "Ertragsprognosen erscheinen, sobald ein Miner eingerichtet ist und Hashrate liefert.": "Earnings forecasts appear once a miner is configured and reports hashrate.",
        "Erweitert": "Advanced",
        "Erweitert · eigene Pool-Adresse": "Advanced · custom pool address",
        "Ethereum Classic gespeichert: Auszahlung an das SolarMiner-Standardziel.": "Ethereum Classic saved: payout to the SolarMiner standard destination.",
        "Europa": "Europe",
        "Externen Proxy aktivieren": "Enable external proxy",
        "Externer Proxy": "External proxy",
        "Externer Proxy ausgewählt": "External proxy selected",
        "Externer Proxy-Host": "External proxy host",
        "Externer SolarMiner-Proxy": "External SolarMiner proxy",
        "FEE-ZIEL FEHLT": "FEE TARGET MISSING",
        "FEE-ZIEL GELADEN": "FEE TARGET LOADED",
        "Fee-Verteilung": "Fee distribution",
        "Fee-Verteilung geändert. Der lokale Proxy wurde neu gestartet und alle Miner wurden pausiert. Starte sie auf der Miner-Seite erneut.": "Fee distribution changed. The local proxy restarted and all miners were paused. Restart them on the miner page.",
        "Fee-Ziele geladen": "Fee targets loaded",
        "Fehlende Messwerte werden nicht als null Verbrauch gewertet.": "Missing readings are not treated as zero consumption.",
        "Fehlende Sensorintervalle werden nicht geschätzt": "Missing sensor intervals are not estimated",
        "Fehler": "Error",
        "Format: stratum+tcp://host:port oder stratum+ssl://host:port": "Format: stratum+tcp://host:port or stratum+ssl://host:port",
        "Freie Kapazität": "Free capacity",
        "Freigabe deaktiviert. Der Widerruf wird beim nächsten Versandintervall übermittelt.": "Sharing disabled. Withdrawal will be sent at the next reporting interval.",
        "Freigabe gespeichert. Aktive Miner werden regelmäßig übertragen.": "Permission saved. Active miners will be reported periodically.",
        "Freigabe nicht verfügbar": "Permission unavailable",
        "Funktion wurde aktiviert.": "Feature enabled.",
        "Funktion wurde deaktiviert.": "Feature disabled.",
        "Für Node freigegeben": "Available to Node",
        "Für diesen Coin ist keine Mining-Software im Katalog.": "No mining software is in the catalog for this coin.",
        "Für diesen Hardware-Filter ist kein Miner verfügbar.": "No miner is available for this hardware filter.",
        "Für diesen Hardware-Filter ist keine Mining-Software verfügbar.": "No mining software is available for this hardware filter.",
        "Für diesen Pool nicht verfügbar": "Unavailable for this pool",
        "Für dieses Gerät ist nur Start/Stopp verfügbar. Leistungsgrenzen können nicht zuverlässig angewendet werden.": "This device supports start/stop only. Power limits cannot be applied reliably.",
        "Für einen gemeinsamen SolarMiner-Proxy auf einem anderen Gerät. Die Suche zeigt Kandidaten; du bestimmst den Server.": "Use a shared SolarMiner proxy on another device. Discovery lists candidates; you choose the server.",
        "GEBÜHREN": "FEES",
        "GERÄTE": "DEVICES",
        "GESAMTLEISTUNG": "TOTAL POWER",
        "GPU Mining": "GPU mining",
        "GPU pausieren": "Pause GPU",
        "GPU starten": "Start GPU",
        "GPU-Konsole auswählen": "Select GPU console",
        "GPU-Leistungsziel": "GPU power target",
        "GPU-Leistungsziel übernommen.": "GPU power target applied.",
        "GPU-Miner": "GPU miner",
        "GPU-Schutzgrenzen und Leistungswerte verwalten": "Manage GPU safety limits and power values",
        "GPUs auswählen": "Select GPUs",
        "GRAFIKKARTEN": "GRAPHICS CARDS",
        "Gebührenmodell wird geladen …": "Loading fee model …",
        "Gemessen von der Miner-API des laufenden Miners": "Measured by the miner API of the running miner",
        "Gemessene Leistung": "Measured power",
        "Gemessene Worker-Leistung": "Measured worker power",
        "Geräte": "Devices",
        "Geräte & Standard-Coins zuordnen": "Assign devices and default coins",
        "Geräte für den Node freigegeben": "Devices assigned to the Node",
        "Geräte und Standard-Coins im Mining-Profil festlegen →": "Assign devices and default coins in the mining profile →",
        "Geräte werden geladen …": "Loading devices …",
        "Geräte zuordnen →": "Assign devices →",
        "Geräte zuteilen →": "Assign devices →",
        "Gerätefreigabe ist in Hardware deaktiviert.": "Device permission is disabled in Hardware.",
        "Gerätekennung": "Device identity",
        "Gesamtes Log herunterladen": "Download full log",
        "Gesamt: {pct}": "Total: {pct}",
        "Gesamtleistung": "Total power",
        "Gespeichert. Der SolarMiner Node zeigt diesen Namen.": "Saved. The SolarMiner Node shows this name.",
        "Gespeichert. Node-Steuerung ist ausgeschaltet.": "Saved. Node control is disabled.",
        "Gespeicherte Grenzen. Verschieben ändert noch nichts.": "Saved limits. Moving the sliders does not apply changes.",
        "Gestoppt": "Stopped",
        "Grenzen anwenden": "Apply limits",
        "HARDWARE & SENSOREN": "HARDWARE & SENSORS",
        "HEUTE": "TODAY",
        "HTTP 404 · PC-Agent neu starten oder aktualisieren.": "HTTP 404 · Restart or update the PC agent.",
        "Hardware": "Hardware",
        "Hardware & Telemetrie öffnen →": "Open hardware & telemetry →",
        "Hardware ansehen und einen CPU- oder GPU-Miner auswählen.": "Review your hardware and choose a CPU or GPU miner.",
        "Hardware wählen": "Choose hardware",
        "Hardware-Monitor angehalten": "Hardware monitor stopped",
        "Hardware-Monitor fehlgeschlagen": "Hardware monitor failed",
        "Hardware-Monitor neu starten": "Restart hardware monitor",
        "Hardware-Monitor noch nicht gestartet": "Hardware monitor not started",
        "Hardwareart": "Hardware type",
        "Hardwaredaten konnten nicht geladen werden:": "Could not load hardware data:",
        "Hashrate": "Hashrate",
        "Hashrate je Algorithmus": "Hashrate by algorithm",
        "Hashrate oder Leistung fehlt": "Hashrate or power missing",
        "Hashrate oder gemessene Watt fehlen": "Hashrate or measured watts missing",
        "Hashrate pro Watt": "Hashrate per watt",
        "Hashrate pro Watt gemessener Workerleistung": "Hashrate per measured worker watt",
        "Hauptnavigation": "Main navigation",
        "Heißestes Gerät": "Hottest device",
        "Herunterladen & installieren": "Download & install",
        "Heute": "Today",
        "Hier weist du CPU und GPUs einem Coin, einer installierten Miner-Software und einem Steuerungsmodus zu.": "Assign each CPU and GPU to a coin, installed mining software and a control mode here.",
        "Hier weist du CPU und GPUs einem Coin und einer installierten Miner-Software zu.": "Assign each CPU and GPU to a coin and installed mining software here.",
        "Hinterlege pro Coin deine Wallet, dein Pool-Ziel und einen Worker-Namen.": "Set your wallet, pool destination and worker name for each coin.",
        "Hinweis": "Note",
        "Hinzufügen": "Add",
        "Hongkong": "Hong Kong",
        "Host-Sensor": "Host sensor",
        "Hostname oder IP-Adresse. Aktivieren pausiert alle Miner.": "Hostname or IP address. Enabling this pauses all miners.",
        "Huge Pages für RandomX aktivieren": "Enable Huge Pages for RandomX",
        "Huge Pages halten RAM während des Minings fest. Dieser Speicher steht anderen Programmen und Windows dann nicht zur Verfügung; bei wenig freiem RAM kann das System langsamer reagieren. Beim Ausschalten entfernt SolarMiner nur ein Windows-Recht, das es selbst hinzugefügt hat.": "Huge Pages reserve RAM while mining. Other programs and Windows cannot use this memory; low available RAM may make the system respond more slowly. When disabled, SolarMiner removes only a Windows right that it added itself.",
        "Huge Pages sind in XMRig deaktiviert. Windows übernimmt Änderungen an Benutzerrechten bei einer neuen Anmeldung; vorhandene Sitzungen können das alte Recht bis dahin behalten.": "Huge Pages are disabled in XMRig. Windows applies user-right changes at the next sign-in; existing sessions may retain the old right until then.",
        "Höchste Temperatur": "Highest temperature",
        "IM LAUFENDEN BETRIEB": "WHILE MINING",
        "IM NETZWERK": "ON YOUR NETWORK",
        "INSTALLIERT": "INSTALLED",
        "Ich bestätige: Ohne eigene Wallet geht meine gesamte Mining-Auszahlung an das angezeigte SolarMiner-Standardziel. Die vollständigen Gebühren stehen unten.": "I confirm: without my own wallet, my entire mining payout goes to the displayed SolarMiner standard destination. The complete fees are listed below.",
        "Ich bestätige: Ohne eigene Wallet geht meine gesamte Mining-Auszahlung an das angezeigte SolarMiner-Standardziel. Die vollständigen Gebühren stehen unter Erweitert.": "I confirm: without my own wallet, my entire mining payout goes to the displayed SolarMiner standard destination. The full fees are listed under Advanced.",
        "Im Netzwerk suchen": "Search network",
        "Im Node-Profil enthalten.": "Included in the Node profile.",
        "Im PC-Agent enthalten. Für einen einzelnen PC brauchst du keinen zusätzlichen Server.": "Included with the PC agent. A single PC does not need an additional server.",
        "Installation": "Installation",
        "Installation fehlgeschlagen": "Installation failed",
        "Installation gestartet. Der Fortschritt erscheint in der Miner-Liste.": "Installation started. The progress appears in the miner list.",
        "Installation läuft …": "Installation in progress …",
        "Installation verwalten": "Manage installation",
        "Installationsordner in Defender ausnehmen": "Exclude installation folder in Defender",
        "Installationsstatus": "Installation status",
        "Installierbar": "Installable",
        "Installiere einen passenden Miner, richte Pool und Auszahlung ein und starte anschließend bewusst.": "Install a suitable miner, configure the pool and payout, then start it when you are ready.",
        "Installiere zuerst eine kompatible Software im Bereich Miner-Software.": "Install compatible software in the Miner software section first.",
        "Installieren": "Install",
        "Installiert": "Installed",
        "Installiert und einsatzbereit.": "Installed and ready.",
        "Installiert …": "Installing …",
        "Installierte Miner": "Installed miners",
        "Installierte Miner testen": "Test installed miners",
        "JSON-Schnittstelle ↗": "JSON endpoint ↗",
        "Jedes Softwarepaket erscheint genau einmal – auch wenn es mehrere Coins unterstützt.": "Each software package appears exactly once, even when it supports multiple coins.",
        "Jetzt anonym hochladen": "Upload anonymously now",
        "KAWPOW-fähige GPU und Treiber": "KAWPOW-capable GPU and driver",
        "KONFIGURATION": "CONFIGURATION",
        "Kein Coin ausgewählt. Öffne die Miner-Bibliothek.": "No coin selected. Open the miner library.",
        "Kein Leistungssensor verfügbar": "No power sensor available",
        "Kein Miner läuft gerade. Angehaltene Miner zeigt der Bereich Miner.": "No miner is running. The Miners section shows paused miners.",
        "Kein Miner passt zur Suche.": "No miner matches the search.",
        "Kein Node verbunden. Lokales Mining bleibt möglich.": "No Node connected. Local mining is still available.",
        "Kein Proxy gefunden. Du kannst einen bekannten Host manuell eintragen.": "No proxy found. You can enter a known host manually.",
        "Kein SolarMiner-Standardziel erreichbar. Eigene Wallet angeben oder Proxy auf der Seite Verbindung prüfen.": "No SolarMiner default destination is available. Enter your own wallet or check the proxy on the Connection page.",
        "Kein SolarMiner-Standardziel erreichbar. Eigene Wallet angeben oder Proxy prüfen.": "No SolarMiner standard destination is available. Enter your own wallet or check the proxy.",
        "Kein Worker läuft gerade. Richte Hardware im Bereich Worker ein oder starte eine vorhandene Zuweisung.": "No worker is running. Configure hardware in Workers or start an existing assignment.",
        "Kein Worker läuft gerade. Zuweisungen und angehaltene Geräte findest du im Bereich Worker.": "No worker is running. Find assignments and paused devices in the Workers section.",
        "Kein Worker passt zu den Filtern.": "No worker matches the filters.",
        "Kein externer Host gespeichert.": "No external host saved.",
        "Kein kompatibler Proxy gefunden. Prüfe, ob beide Geräte im selben LAN sind und UDP 8091 sowie API-Port 8090 erreichbar sind.": "No compatible proxy found. Check that both devices are on the same LAN and UDP 8091 and API port 8090 are reachable.",
        "Keine CPU-Sensorwerte verfügbar": "No CPU sensor readings available",
        "Keine Difficulty gemeldet": "No difficulty reported",
        "Keine Einträge.": "No entries.",
        "Keine GPU ausgewählt": "No GPU selected",
        "Keine GPU erkannt.": "No GPU detected.",
        "Keine GPU erkannt. Prüfe Treiber und die Seite Leistungsgrenzen.": "No GPU detected. Check the drivers and the Power limits page.",
        "Keine GPU vom Treiber erkannt.": "No GPU was detected by the driver.",
        "Keine Grafikkarte erkannt": "No graphics card detected",
        "Keine Hashrate gemeldet": "No hashrate reported",
        "Keine Route verfügbar": "No route available",
        "Keine Sensoren passen zu diesem Filter.": "No sensors match this filter.",
        "Keine Software erforderlich.": "No software required.",
        "Keine Worker passen zu den Filtern.": "No workers match the filters.",
        "Keine geeigneten Benchmark-Messwerte zum Hochladen vorhanden.": "No suitable benchmark observations to upload.",
        "Keine gespeicherten Benchmark-Messwerte zum erneuten Hochladen vorhanden.": "No saved benchmark observations to upload again.",
        "Keine gültigen Mining-Messwerte erfasst. Prüfe, ob Miner laufen und Hashrate liefern.": "No valid mining readings recorded. Check that miners are running and reporting hashrate.",
        "Keine offene Aktion. Die Messwerte unten zeigen den laufenden Betrieb.": "No action needed. The readings below show current operation.",
        "Keine regelbare GPU erkannt": "No controllable GPU detected",
        "Keine unterstützte GPU erkannt.": "No supported GPU detected.",
        "Klicke einen Worker an, um seine fortlaufende Miner-Konsole zu öffnen. Leistungs- und Energiewerte werden nur angezeigt, wenn der Agent einen echten Sensorwert erhält. Fehlende Messwerte werden nicht als null Verbrauch gewertet.": "Click a worker to open its continuous miner console. Power and energy values are shown only when the agent receives a real sensor reading. Missing readings are not treated as zero consumption.",
        "Kompatible GPU und Treiber": "Compatible GPU and driver",
        "Kompatible Miner-Software installieren →": "Install compatible miner software →",
        "Komponente": "Component",
        "Konfiguration": "Configuration",
        "Konfiguration speichern": "Save configuration",
        "Konsole": "Console",
        "Konsole & Diagnose": "Console & diagnostics",
        "Konsolenausgabe konnte nicht geladen werden: {error}": "Console output could not be loaded: {error}",
        "Konto": "Account",
        "Kontostände & Auszahlung": "Balances & payouts",
        "Kryptex · Europa": "Kryptex · Europe",
        "Kryptex · Nordamerika": "Kryptex · North America",
        "Kryptex-Guthaben vor Auszahlung": "Kryptex balance before payout",
        "LEISTUNG UND ZUSTAND": "PERFORMANCE AND STATE",
        "LEISTUNGSZIEL": "POWER TARGET",
        "LIVE-AUSGABE": "LIVE OUTPUT",
        "LIVE-BETRIEB": "LIVE OPERATION",
        "LIVE-SCHÄTZUNG": "LIVE ESTIMATE",
        "LOKALE BIBLIOTHEK": "LOCAL LIBRARY",
        "LOKALE MINING-ZENTRALE": "LOCAL MINING HUB",
        "Lade Node-Profil …": "Loading Node profile …",
        "Lade vollständige Ausgabe …": "Loading full output …",
        "Lade …": "Loading …",
        "Latenz": "Latency",
        "Laufend / erkannte Komponenten": "Running / detected components",
        "Laufende Geräte": "Running devices",
        "Laufende Miner messen": "Measure running miners",
        "Laufende Miner, Node-Freigabe und Verbindungsstatus auf einen Blick.": "Running miners, Node permissions and connection status at a glance.",
        "Laufende Sessions": "Running sessions",
        "Laufende Worker": "Running workers",
        "Laufende Worker im Detail →": "Running workers in detail →",
        "Lege fest, wie viel Leistung deine GPUs im geregelten Betrieb nutzen dürfen. Treibergrenzen geben den verfügbaren Bereich vor. Änderungen werden erst mit „Grenzen anwenden“ gespeichert.": "Choose how much power your GPUs may use under dynamic control. Driver limits define the available range. Changes are saved only when you apply them.",
        "Lege pro Gerät den Standard-Coin fest. „Nur lokal / Benchmarks“ schließt das Gerät von automatischen Starts und der Leistungsplanung des Node aus. Du kannst es weiterhin manuell nutzen.": "Choose the default coin for each device. “Local / benchmarks only” excludes that device from automatic starts and the Node’s power planning. You can still use it manually.",
        "Leistung": "Power",
        "Leistung —": "Power —",
        "Leistungs- und Energiewerte werden nur angezeigt, wenn der Agent einen echten Sensorwert erhält. Fehlende Messwerte werden nicht als null Verbrauch gewertet.": "Power and energy values are shown only when the agent receives a real sensor reading. Missing readings are not counted as zero consumption.",
        "Leistungsgrenzen": "Power limits",
        "Leistungsgrenzen einstellen →": "Set power limits →",
        "Leistungsgrenzen gespeichert.": "Power limits saved.",
        "Leistungsgrenzen je GPU →": "Power limits per GPU →",
        "Leistungsmessung nicht verfügbar": "Power measurement unavailable",
        "Leistungsobergrenze nicht gemeldet": "Power ceiling not reported",
        "Leistungsregelung aktualisiert.": "Power control updated.",
        "Leistungsziel übernehmen": "Apply power target",
        "Letzter Stand": "Last update",
        "Letzter bekannter Stand · Aktuelle Daten werden geprüft. Aktionen sind danach verfügbar.": "Last known state · Checking current data. Actions will be available afterwards.",
        "Letzter bekannter Stand · aktuelle Daten werden geprüft.": "Last known state · checking current data.",
        "LibreHardwareMonitor läuft nicht. Windows benötigt eine Administratorfreigabe, um den Hardware-Monitor neu zu starten.": "LibreHardwareMonitor is not running. Windows requires administrator approval to restart the hardware monitor.",
        "LibreHardwareMonitor neu starten": "Restart LibreHardwareMonitor",
        "LibreHardwareMonitor starten": "Start LibreHardwareMonitor",
        "LibreHardwareMonitor wird mit Windows-UAC-Rechten gestartet.": "LibreHardwareMonitor is starting with Windows UAC permissions.",
        "LibreHardwareMonitor wird nach Windows-UAC-Freigabe neu gestartet.": "LibreHardwareMonitor will restart after Windows UAC approval.",
        "Live · vollständiger Verlauf": "Live · full history",
        "Live-Verbindung wird wiederhergestellt …": "Restoring live connection …",
        "Lokal speichern": "Save locally",
        "Lokal starten – Node später hinzufügen": "Start locally – add a Node later",
        "Lokale Sensor-API deaktiviert": "Local sensor API disabled",
        "Lokale Sensor-API nicht erreichbar": "Local sensor API unavailable",
        "Lokale Steuerung": "Local control",
        "Lokalen Proxy verwenden": "Use local proxy",
        "Lokaler Dienst": "Local service",
        "Lokaler Mining-Dienst bereit": "Local mining service ready",
        "Lokaler Proxy": "Local proxy",
        "Lokaler Proxy ausgewählt": "Local proxy selected",
        "Lokaler Proxy läuft nicht": "Local proxy is not running",
        "Läuft auf GPU(s)": "Running on GPU(s)",
        "MANUELLE STEUERUNG": "MANUAL CONTROL",
        "MESSWERTE": "READINGS",
        "MINER": "MINERS",
        "MINER HINZUFÜGEN": "ADD MINER",
        "MINER-BIBLIOTHEK": "MINER LIBRARY",
        "MINER-ÜBERSICHT": "MINER OVERVIEW",
        "MINING-POOLS": "MINING POOLS",
        "MINING-PROXY": "MINING PROXY",
        "Manuell pausiert": "Paused manually",
        "Marktdaten werden geladen": "Loading market data",
        "Maximale Leistung": "Maximum power",
        "Meldungen": "Notifications",
        "Messabdeckung": "Measurement coverage",
        "Messlauf abbrechen": "Cancel measurement",
        "Messlauf gestartet. Du kannst ihn jederzeit abbrechen.": "Measurement started. You can cancel it at any time.",
        "Messlauf konnte nicht abgebrochen werden.": "Could not cancel the measurement.",
        "Messverlauf seit Öffnen dieser Seite": "Measurement history since opening this page",
        "Messwerte seit Seitenaufruf": "Readings since the page loaded",
        "Messwerte werden laufend aktualisiert; der Vergleich erscheint nach Abschluss.": "Readings update continuously; comparison appears after completion.",
        "Microsoft: Ausnahmen verwalten ↗": "Microsoft: Manage exclusions ↗",
        "Microsoft: Schutzverlauf ↗": "Microsoft: Protection history ↗",
        "Mindestens ein Worker meldet einen Fehler": "At least one worker reports an error",
        "Mindestens ein Worker meldet einen Fehler.": "At least one worker is reporting an error.",
        "Miner": "Miners",
        "Miner anpassen": "Edit miner",
        "Miner anpassen →": "Edit miner →",
        "Miner einrichten →": "Set up miner →",
        "Miner entfernen": "Remove miner",
        "Miner gestartet.": "Miner started.",
        "Miner geöffnet": "Miner opened",
        "Miner hinzufügen": "Add a miner",
        "Miner hinzufügen →": "Add miner →",
        "Miner installieren": "Install miner",
        "Miner installieren →": "Install miner →",
        "Miner installieren, konfigurieren und steuern": "Install, configure and control miners",
        "Miner installiert": "Miner installed",
        "Miner installiert – Pool und eigene Wallet einrichten.": "Miner installed – set up a pool and your own wallet.",
        "Miner ist geöffnet": "Miner is open",
        "Miner liefert noch keine Hashrate": "Miner is not reporting hashrate yet",
        "Miner lokal steuern und Geräte für die Automatisierung zuordnen.": "Control miners locally and assign devices for automation.",
        "Miner läuft": "Miner running",
        "Miner oder Coin suchen": "Search miner or coin",
        "Miner pausieren": "Pause miner",
        "Miner pausiert.": "Miner paused.",
        "Miner starten": "Start miner",
        "Miner steuern →": "Control miners →",
        "Miner werden geladen …": "Loading miners …",
        "Miner zuerst über + installieren.": "Install the miner using + first.",
        "Miner öffnen": "Open miner",
        "Miner öffnen →": "Open miner →",
        "Miner-Ausgabe": "Miner output",
        "Miner-Bereiche": "Miner sections",
        "Miner-Bibliothek": "Miner library",
        "Miner-Binary fehlt": "Miner executable missing",
        "Miner-Installation entfernen": "Remove miner installation",
        "Miner-Konsole": "Miner console",
        "Miner-Konsole · {worker}": "Miner console · {worker}",
        "Miner-Log wählen": "Choose miner log",
        "Miner-Programm": "Mining software",
        "Miner-Software": "Miner software",
        "Miner-Software verwalten →": "Manage miner software →",
        "Minimale Leistung": "Minimum power",
        "Mining": "Mining",
        "Mining aktiv": "Mining active",
        "Mining einrichten": "Set up mining",
        "Mining öffnen →": "Open mining →",
        "Mining-Pool": "Mining pool",
        "Mining-Pool fehlt": "Mining pool missing",
        "Mining-Pool wählen": "Choose mining pool",
        "Mining-Programme installieren, aktualisieren, reparieren oder entfernen. Coins, Geräte und Starts ordnest du anschließend im Bereich Worker zu.": "Install, update, repair or remove mining programs. Assign coins, devices and start controls in the Worker section.",
        "Mining-Proxy nicht bereit": "Mining proxy not ready",
        "Mining-Proxy wird vorbereitet": "Preparing the mining proxy",
        "Mining-Software": "Mining software",
        "Mining-Status": "Mining status",
        "Mining-Verbindung": "Mining connection",
        "Mining-Verbindung getrennt": "Mining connection disconnected",
        "Mining-Verbindung:": "Mining connection:",
        "Miss die Leistung deiner Miner und vergleiche sie mit veröffentlichten Ergebnissen.": "Measure your miners and compare them with published results.",
        "Momentan nicht erreichbar": "Currently unavailable",
        "Monero Dev-Fee-Ziel": "Monero dev-fee target",
        "Monero gespeichert: Auszahlung an das SolarMiner-Standardziel.": "Monero saved: payout to the SolarMiner default destination.",
        "Monero läuft auf der CPU. Temperaturen stammen aus der lokalen Sensor-API, der Miner selbst meldet keine.": "Monero runs on the CPU. Temperatures come from the local sensor API; the miner itself reports none.",
        "Monero nutzt die CPU; ein Gerät ist immer aktiv.": "Monero uses the CPU; one device is always active.",
        "Monero und Pearl verfügbar": "Monero and Pearl available",
        "Monero · im Pool": "Monero · at pool",
        "Monero-Konfiguration gespeichert.": "Monero configuration saved.",
        "Monero-Route": "Monero route",
        "NICHT GELADEN": "NOT LOADED",
        "NOCH NICHT VERFÜGBAR": "NOT AVAILABLE YET",
        "NODE-PROFIL": "NODE PROFILE",
        "Nach Prüfung erneut versuchen": "Retry after review",
        "Naher Osten": "Middle East",
        "Name dieses Agents": "Name of this agent",
        "Name entfernt. Der Node zeigt wieder SolarMiner PC Agent.": "Name removed. The Node shows SolarMiner PC Agent again.",
        "Navigation schließen": "Close navigation",
        "Navigation öffnen": "Open navigation",
        "Netzwerk- und Preisdaten werden geladen": "Loading network and price data",
        "Netzwerkdaten werden geladen": "Loading network data",
        "Neustart erforderlich: Die Einstellung und Windows-Berechtigung sind eingerichtet. Starte Windows neu, damit Huge Pages für den PC-Agent wirksam werden.": "Restart required: the setting and Windows permission are configured. Restart Windows for Huge Pages to take effect for the PC agent.",
        "Neustart erforderlich: Die Einstellung und Windows-Berechtigung sind eingerichtet. Starte Windows neu, damit Huge Pages wirksam werden.": "Restart required: the setting and Windows permission are configured. Restart Windows to make huge pages effective.",
        "Neustart erforderlich: Windows hat die Berechtigung eingerichtet. Starte den PC neu; danach wird Huge Pages hier als aktiv angezeigt.": "Restart required: Windows configured the permission. Restart your PC; Huge Pages will then appear as active here.",
        "Neustart konnte nicht angefordert werden:": "Could not request restart:",
        "Nicht ausgewählt": "Not selected",
        "Nicht eingerichtet": "Not set up",
        "Nicht erkannt": "Not detected",
        "Nicht erreichbar": "Unavailable",
        "Nicht installiert": "Not installed",
        "Nicht integriert": "Not integrated",
        "Nicht teilen": "Don't share",
        "Nicht verfügbar": "Unavailable",
        "Nicht von der Miner-API gemeldet": "Not reported by the miner API",
        "Nicht zugewiesen": "Unassigned",
        "Noch kein Leistungsziel": "No power target yet",
        "Noch kein Miner installiert": "No miner installed yet",
        "Noch kein veröffentlichter Vergleich für diese Hardware und diesen Algorithmus.": "No published comparison for this hardware and algorithm yet.",
        "Noch keine Messung. Wähle oben einen Messlauf.": "No measurement yet. Choose a mode above.",
        "Noch keine Miner-Ausgabe": "No miner output yet",
        "Noch keine Worker": "No workers yet",
        "Noch nicht angewendet.": "Not applied yet.",
        "Noch nicht eingerichtet": "Not configured yet",
        "Noch nicht gestartet": "Not started",
        "Noch nicht installiert": "Not installed",
        "Noch nicht integriert": "Not integrated yet",
        "Node erlaubt": "Node allowed",
        "Node verbunden": "Node connected",
        "Node-Automatik": "Node automation",
        "Node-Automatik global erlauben": "Allow global Node automation",
        "Node-Automatik global erlaubt.": "Global Node automation enabled.",
        "Node-Automatik global gesperrt.": "Global Node automation blocked.",
        "Node-Bewertung verfügbar. Details zur Entscheidung bleiben beim Node.": "Node assessment available. Decision details remain on the Node.",
        "Node-Freigabe konnte nicht gelesen werden.": "Could not read Node permissions.",
        "Node-Profil": "Node profile",
        "Node-Profil festlegen": "Define Node profile",
        "Node-Profil ist nicht verfügbar.": "Node profile unavailable.",
        "Node-Profil konnte nicht geladen werden.": "Could not load the Node profile.",
        "Node-Profil öffnen": "Open Node profile",
        "Node-Profil öffnen →": "Open Node profile →",
        "Node-Status nicht verfügbar.": "Node status unavailable.",
        "Node-Steuerung ausgeschaltet. Laufende Miner kannst du weiterhin lokal pausieren.": "Node control disabled. You can still pause running miners locally.",
        "Node-Steuerung erlauben": "Allow Node control",
        "Node-Steuerung erlaubt, aber kein Gerät freigegeben": "Node control enabled, but no device is assigned",
        "Node-Steuerung erlaubt. Nur Geräte aus diesem Profil werden automatisch verwendet.": "Node control allowed. Only devices in this profile are used automatically.",
        "Node: Mining ist aktuell nicht wirtschaftlich freigegeben.": "Node: mining is currently not approved as economical.",
        "Node: Mining ist aktuell wirtschaftlich freigegeben.": "Node: mining is currently approved as economical.",
        "Node: Noch keine Wirtschaftlichkeitsbewertung.": "Node: no economic assessment yet.",
        "Nordamerika": "North America",
        "Nur Geräte, die gerade mining. Ein GPU gehört immer zu genau einem laufenden Miner; angehaltene Miner zeigen hier keine Geräte, damit keine zusätzliche Kapazität vorgetäuscht wird.": "Only devices that are mining right now. A GPU always belongs to exactly one running miner; paused miners list no devices here, so no extra capacity is implied.",
        "Nur bei laufendem Miner": "Only while miner is running",
        "Nur dieser Algorithmus · Verlauf seit Seitenaufruf": "Only this algorithm · history since opening this page",
        "Nur im laufenden Betrieb messbar": "Only measurable while running",
        "Nur lokal / Benchmarks": "Local / benchmarks only",
        "Nur verfügbare Messwerte": "Available readings only",
        "Nur verfügbare Sensorwerte": "Available sensor readings only",
        "Nur wenn du den Fund geprüft hast und derselbe Miner weiter blockiert wird: Du kannst Windows bitten, genau den Installationsordner auszunehmen. Alle Dateien in diesem Ordner werden dann nicht von Defender geprüft.": "Only if you reviewed the detection and the same miner is still blocked: you can ask Windows to exclude that exact installation folder. Defender will then not scan files in that folder.",
        "OPTIMIERUNG": "OPTIMIZATION",
        "Offline": "Offline",
        "Ohne Fee-Ziel startet der betreffende Miner nicht": "Without a fee target the miner will not start",
        "Ohne Freigabe fehlen die Windows-Sensoren. Einige Mining-Steuerungsaktionen benötigen diesen Sensorzugriff.": "Without permission, Windows sensors are unavailable. Some mining control actions require access to these sensors.",
        "Ohne Node": "Without a Node",
        "Ohne Proxy kann dieser PC nicht minen.": "This PC cannot mine without a proxy.",
        "Online": "Online",
        "Optional · Linux": "Optional · Linux",
        "Optional: SolarMiner Node verbinden für Regeln, PV-Überschuss und Automatisierung.": "Optional: connect a SolarMiner Node for rules, PV surplus and automation.",
        "Standardmäßig der Node-Name; ohne Namen Solarminer-PC-Agent": "Defaults to the Node name; without a name, Solarminer-PC-Agent",
        "Ordne im Worker-Bereich mindestens ein Gerät zu, damit der Node automatisch regeln darf.": "Assign at least one device in Workers so the Node can control it automatically.",
        "Overview": "Overview",
        "PLATTFORMTIPPS": "PLATFORM TIPS",
        "POOL ZWECHSELN": "SWITCH POOL",
        "PROGNOSE / TAG": "FORECAST / DAY",
        "PROZESSOR": "PROCESSOR",
        "Passenden Miner herunterladen und installieren.": "Download and install a suitable miner.",
        "Pausieren": "Pause",
        "Pausiert": "Paused",
        "Pearl Dev-Fee-Ziel": "Pearl dev-fee target",
        "Pearl gespeichert: Auszahlung an das SolarMiner-Standardziel.": "Pearl saved: payout to the SolarMiner default destination.",
        "Pearl · auf Adresse": "Pearl · at address",
        "Pearl · im Pool": "Pearl · at pool",
        "Pearl-Konfiguration und GPU-Auswahl gespeichert.": "Pearl configuration and GPU selection saved.",
        "Pearl-Route": "Pearl route",
        "Pool & Auszahlung": "Pool & payout",
        "Pool aktiv · Auszahlung an SolarMiner-Standard": "Pool active · payout to SolarMiner default",
        "Pool eingerichtet": "Pool configured",
        "Pool fehlt": "Pool missing",
        "Pool für": "Pool for",
        "Pool und Wallet fehlen": "Pool and wallet are missing",
        "Pool und Wallet konfiguriert": "Pool and wallet configured",
        "Pool verbunden": "Pool connected",
        "Pool, Auszahlung und Geräteauswahl fehlen bei einem installierten Miner.": "An installed miner needs its pool, payout and device selection configured.",
        "Pool-Adresse": "Pool address",
        "Pool-Adresse und Wallet werden für den Mining-Start verwendet.": "The pool address and wallet are used to start mining.",
        "Pool-Adresse ungültig. Format: stratum+tcp://host:port oder stratum+ssl://host:port": "Invalid pool address. Format: stratum+tcp://host:port or stratum+ssl://host:port",
        "Pool-Difficulty wird nicht gemeldet": "Pool difficulty is not reported",
        "Pool-Konfiguration fehlt": "Pool configuration missing",
        "Pool-Kontakt": "Pool contact",
        "Pool-Latenz": "Pool latency",
        "Pool-Ziel": "Pool destination",
        "Pool:": "Pool:",
        "Poolgebühr": "Pool fee",
        "Poolgebühr unbekannt – bitte beim Pool prüfen": "Pool fee unknown – please check with the pool",
        "Pools ansehen": "View pools",
        "Pools durchsuchen": "Search pools",
        "Pools und Verbindung": "Pools and connection",
        "Pools verwalten →": "Manage pools →",
        "Poolwechsel läuft …": "Switching pool …",
        "Programm, Coin oder Algorithmus suchen": "Search program, coin or algorithm",
        "Programme installieren": "Install programs",
        "Proxy erreichbar": "Proxy reachable",
        "Proxy erreichbar. Die Fee-Route wird je Coin beim Start geprüft.": "Proxy reachable. The fee route is checked for each coin at startup.",
        "Proxy gespeichert.": "Proxy saved.",
        "Proxy im Netzwerk": "Network proxy",
        "Proxy im Netzwerk suchen": "Find proxy on network",
        "Proxy nicht erreichbar": "Proxy unreachable",
        "Proxy nicht erreichbar. Prüfe die Verbindung vor dem Start.": "Proxy unreachable. Check the connection before starting.",
        "Proxy verbinden →": "Connect proxy →",
        "Proxy-Dashboard öffnen ↗": "Open proxy dashboard ↗",
        "Proxy-Daten konnten nicht geladen werden:": "Could not load proxy data:",
        "Proxy-Ziel des Miners": "Proxy target of the miner",
        "Proxy-Ziele": "Proxy targets",
        "Prozessor unbekannt": "Unknown processor",
        "Prüfe GPU-Treiber und Gerätezugriff. CPU-Mining kannst du unabhängig davon in Miner einrichten.": "Check GPU drivers and device access. You can set up CPU mining independently in Miners.",
        "Prüfe zuerst Erkennungsname und Datei unter Windows-Sicherheit → Viren- & Bedrohungsschutz → Schutzverlauf. Gib nur eine verifizierte Datei frei.": "First review the detection name and file under Windows Security → Virus & threat protection → Protection history. Allow only a verified file.",
        "Quelle unbekannt": "Unknown source",
        "Ravencoin gespeichert: Auszahlung an das SolarMiner-Standardziel.": "Ravencoin saved: payout to the SolarMiner standard destination.",
        "Referral-Key": "Referral key",
        "Referral-Key lokal gespeichert. Der Node kann ihn wieder überschreiben.": "Referral key saved locally. The Node may overwrite it.",
        "Referrer {code}: {pct}": "Referrer {code}: {pct}",
        "Regelmäßige Benchmarks": "Periodic benchmarks",
        "Regelmäßiger Benchmark-Upload erfolgreich übermittelt.": "Periodic benchmark upload sent successfully.",
        "Regelmäßiger Benchmark-Upload fehlgeschlagen; der Agent versucht es beim nächsten Intervall erneut.": "Periodic benchmark upload failed; the agent will retry at the next interval.",
        "Regelmäßiger Upload": "Periodic upload",
        "Risiko:": "Risk:",
        "Rohwert:": "Raw value:",
        "Russland": "Russia",
        "SCHNELLSTART": "QUICK START",
        "SCHNELLZUGRIFF": "QUICK ACCESS",
        "SENSOREN": "SENSORS",
        "SITZUNGSVERLAUF": "SESSION HISTORY",
        "SOFTWARE": "SOFTWARE",
        "SRBMiner erneut herunterladen": "Download SRBMiner again",
        "SRBMiner hat eine zusätzliche Miner-Gebühr von 2 %.": "SRBMiner has an additional 2% miner fee.",
        "SRBMiner meldet erkannte Geräte und Initialisierungsfehler in seiner Konsole. Der PC-Agent zeigt die Miner-Ausgabe in dieser Ansicht.": "SRBMiner reports detected devices and initialization errors in its console. This view shows the miner output.",
        "SRBMiner muss die gewählte GPU für ETCHash erkennen. Prüfe Geräte und Initialisierungsfehler in der Miner-Konsole; der Agent ändert keine Treiber oder Taktraten.": "SRBMiner must detect the selected GPU for ETCHash. Check devices and initialization errors in its console; the agent does not change drivers or clock rates.",
        "SRBMiner muss die gewählte GPU für KAWPOW erkennen. Prüfe Geräte und Initialisierungsfehler in der Miner-Konsole; der Agent ändert keine Treiber oder Taktraten.": "SRBMiner must detect the selected GPU for KAWPOW. Check devices and initialization errors in its console; the agent does not change drivers or clock rates.",
        "SRBMiner-Download gestartet.": "SRBMiner download started.",
        "SRBMiner-MULTI ETCHash Entwicklergebühr": "SRBMiner-MULTI ETCHash developer fee",
        "SRBMiner-MULTI BLAKE3-Decred Entwicklergebühr": "SRBMiner-MULTI BLAKE3 (Decred) developer fee",
        "SRBMiner-MULTI QPoW Entwicklergebühr": "SRBMiner-MULTI QPoW developer fee",
        "TeamRedMiner Entwicklergebühr": "TeamRedMiner developer fee",
        "Gebühr für die gewählte SRBMiner-Version muss vor Start verifiziert werden": "The fee of the shipped SRBMiner version must be verified before starting",
        "Miner-Katalogeintrag fehlt": "Miner catalog entry missing",
        "Kein Miner im Katalog ausgewählt; Entwicklergebühr kann nicht bestimmt werden": "No miner selected in the catalog; the developer fee cannot be determined",
        "SRBMiner-MULTI Entwicklergebühr": "SRBMiner-MULTI developer fee",
        "SRBMiner-MULTI KAWPOW Entwicklergebühr": "SRBMiner-MULTI KAWPOW developer fee",
        "STANDARD · KEIN REFERRER": "DEFAULT · NO REFERRER",
        "STATUS": "STATUS",
        "SYSTEM": "SYSTEM",
        "Schließen": "Close",
        "Sensor, Einheit oder Quelle suchen": "Search sensor, unit or source",
        "Sensor-API nicht erreichbar": "Sensor API unavailable",
        "Sensoranzeige fehlgeschlagen": "Could not render sensor data",
        "Sensoren": "Sensors",
        "Sensoren & Messwerte": "Sensors & readings",
        "Sensoren durchsuchen": "Search sensors",
        "Sensoren und Messwerte ansehen": "View sensors and readings",
        "Sensoren werden geladen": "Loading sensors",
        "Sensorwerte konnten nicht geladen werden": "Could not load sensor readings",
        "Sensorzugriff bereit": "Sensor access ready",
        "Session-Energie": "Session energy",
        "Shares akzeptiert": "Shares accepted",
        "Shares dieser Session": "Shares this session",
        "Singapur": "Singapore",
        "SolarMiner Node ist verbunden.": "SolarMiner Node is connected.",
        "SolarMiner Node nicht verbunden": "SolarMiner Node not connected",
        "SolarMiner Node verbunden": "SolarMiner Node connected",
        "SolarMiner: {pct}": "SolarMiner: {pct}",
        "SolarMiner schaltet die XMRig-Option ein. Unter Windows fragt der Agent per UAC nach der Berechtigung für dein Benutzerkonto; nach einer neuen Anmeldung wird das Recht wirksam.": "SolarMiner enables the XMRig option. On Windows, the agent requests permission for your account through UAC; the right takes effect after you sign in again.",
        "SolarMiner schaltet die XMRig-Option ein. XMRig benötigt dafür bis zu 3 GB Speicher pro NUMA-Knoten; die Nutzung hängt zusätzlich von Kernel und verfügbarer Speicherkonfiguration ab.": "SolarMiner enables the XMRig option. XMRig may need up to 3 GB per NUMA node; actual use also depends on the kernel and available memory configuration.",
        "SolarMiner · Hardware": "SolarMiner · Power limits",
        "SolarMiner · Mining": "SolarMiner · Mining",
        "SolarMiner · Telemetrie": "SolarMiner · Sensors",
        "SolarMiner · Verbindung": "SolarMiner · Connection",
        "SolarMiner- und Referrer-Anteile kommen live vom Fee-Backend. Pool- und Miner-Gebühren werden je Coin offen ausgewiesen.": "SolarMiner and referrer shares are loaded live from the fee backend. Pool and miner fees are shown openly for each coin.",
        "SolarMiner-Proxy erreichbar": "SolarMiner proxy reachable",
        "SolarMiner-Proxy für Monero fehlt. Verbinde zuerst den Proxy.": "SolarMiner proxy for Monero is missing. Connect the proxy first.",
        "SolarMiner-Proxy für Pearl fehlt. Verbinde zuerst den Proxy.": "SolarMiner proxy for Pearl is missing. Connect the proxy first.",
        "SolarMiner-Proxy nicht erreichbar": "SolarMiner proxy unavailable",
        "SolarMiner-Standard · ohne eigene Auszahlung": "SolarMiner default · no personal payout",
        "SolarMiner-Standardziel · ohne eigene Auszahlung": "SolarMiner default destination · no personal payout",
        "Speichern": "Save",
        "Speichern fehlgeschlagen:": "Saving failed:",
        "Speichern …": "Saving …",
        "Sprache wählen": "Choose language",
        "Stand": "As of",
        "Standard für den Node": "Default for the Node",
        "Standardmäßig deaktiviert. Eine spätere Deaktivierung sendet eine Widerrufsmeldung.": "Off by default. Turning it off later sends a withdrawal notification.",
        "Standardroute": "Default route",
        "Standardziel": "Default destination",
        "Starten": "Start",
        "Status": "Status",
        "Status & Geräte": "Status & devices",
        "Status konnte nicht geladen werden:": "Could not load status:",
        "Status wird geladen …": "Loading status …",
        "Steuerung": "Control",
        "Steuerung ausgeschaltet": "Control disabled",
        "Steuerung erlaubt": "Control allowed",
        "Steuerungsmodus": "Control mode",
        "Stromkosten heute": "Electricity cost today",
        "Stromtarif": "Electricity tariff",
        "Stromtarif konnte nicht gespeichert werden.": "Could not save the electricity rate.",
        "Stromverbrauch": "Power consumption",
        "Suche fehlgeschlagen:": "Search failed:",
        "Suche im lokalen Netzwerk …": "Searching the local network …",
        "Summe gemessener Workerleistung": "Sum of measured worker power",
        "Südamerika": "South America",
        "TRANSPARENTE GEBÜHREN": "TRANSPARENT FEES",
        "Tarif": "Rate",
        "Telemetrie": "Telemetry",
        "Temperatur": "Temperature",
        "Temperatur —": "Temperature —",
        "Temperaturen mit der Angabe „Host-Sensor“ stammen von der lokalen Sensor-API, nicht vom Miner selbst.": "Temperatures labelled “Host-Sensor” come from the local sensor API, not from the miner itself.",
        "Temperaturen mit der Angabe „Host-Sensor“ stammen von der lokalen Sensor-API, nicht vom Miner selbst. Difficulty, Latenz und veraltete Shares meldet nur die Miner-API des laufenden Miners.": "Temperatures labelled “Host-Sensor” come from the local sensor API, not from the miner itself. Difficulty, latency and stale shares are reported only by the miner API of the running miner.",
        "Testet konfigurierte Miner nacheinander. Laufende verwaltete Worker werden dafür vorübergehend pausiert und anschließend wiederhergestellt. Die Node-Steuerung ist währenddessen gesperrt.": "Tests configured miners in sequence. Running managed workers are temporarily paused and then restored. Node control is blocked during the test.",
        "Treiberwerte werden vor jeder Änderung geprüft.": "Driver values are checked before every change.",
        "Unbekannt": "Unknown",
        "Upload ans Backend fehlgeschlagen. Du kannst den Upload erneut versuchen.": "Upload failed. You can retry the upload.",
        "VERBINDUNG": "CONNECTION",
        "VERFÜGBAR": "AVAILABLE",
        "VERGLEICH DER KONFIGURATIONEN": "COMPARE CONFIGURATIONS",
        "VORBEREITET": "PREPARED",
        "Verbinde mit Pool": "Connecting to pool",
        "Verbinde zuerst den SolarMiner-Proxy.": "Connect the SolarMiner proxy first.",
        "Verbinde …": "Connecting …",
        "Verbindung": "Connection",
        "Verbindung fehlgeschlagen": "Connection failed",
        "Verbindung geändert. Alle Miner wurden pausiert. Starte sie auf der Miner-Seite erneut.": "Connection changed. All miners were paused. Restart them on the Miners page.",
        "Verbindung prüfen": "Check connection",
        "Verbindung prüfen →": "Check connection →",
        "Verbindung wird geladen …": "Loading connection …",
        "Verbindung wird geprüft": "Checking connection",
        "Version {version}": "Version {version}",
        "Verbindung zum Agent unterbrochen. Angezeigte Werte können veraltet sein.": "Connection to the agent interrupted. Displayed values may be stale.",
        "Verbindung öffnen →": "Open connection →",
        "Verbrauch & Kosten": "Consumption & cost",
        "Verbrauch dieser Sessions": "Consumption of these sessions",
        "Verbunden": "Connected",
        "Verfügbar": "Available",
        "Verfügbare Programme": "Available programs",
        "Verfügbare Ziele": "Available targets",
        "Verfügbare auswählen": "Select available",
        "Verwerfen": "Discard",
        "Vollständige Miner-Ausgabe": "Complete miner output",
        "Vollständiges Log herunterladen": "Download full log",
        "Vom Proxy verwendet": "Used by the proxy",
        "Vom SolarMiner Fee-Backend geladen": "Loaded from the SolarMiner fee backend",
        "Von der Miner-API gemessen": "Measured by the miner API",
        "Voraussetzung": "Requirement",
        "Voraussetzungen & Optimierungen": "Requirements & optimizations",
        "Voraussichtlich für dich": "Estimated for you",
        "Voraussichtliche Earnings pro Tag": "Estimated daily earnings",
        "Vorbereitet. Mining-Route noch nicht freigegeben.": "Prepared. The mining route is not released yet.",
        "WINDOWS SENSORZUGRIFF": "WINDOWS SENSOR ACCESS",
        "Wallet & Worker": "Wallet & worker",
        "Wallet aus der Miner-Konfiguration": "Wallet from the miner configuration",
        "Wallet fehlt": "Wallet missing",
        "Wallet gespeichert": "Wallet saved",
        "Wallet speichern": "Save wallet",
        "Wallet und Pool-Ziel gespeichert.": "Wallet and pool destination saved.",
        "Wallet und Worker bleiben unverändert. Prüfe, dass die Wallet zum gewählten Pool passt.": "Wallet and worker stay unchanged. Make sure the wallet matches the selected pool.",
        "Wallet wird gespeichert …": "Saving wallet …",
        "Wallet-Daten konnten nicht geladen werden:": "Could not load wallet data:",
        "Wallet-Kontostände": "Wallet balances",
        "Wallets": "Wallets",
        "Warte auf Daten": "Waiting for data",
        "Warte auf Messwerte": "Waiting for readings",
        "Warte auf Windows-Freigabe …": "Waiting for Windows approval …",
        "Warte auf aktuelle Agent-Daten, bevor du eine Änderung ausführst.": "Wait for current agent data before making changes.",
        "Wartende Läufe ({count})": "Waiting runs ({count})",
        "Was darf der Node minen?": "What may the Node mine?",
        "Was gerade arbeitet": "What is running now",
        "Welche Geräte der Node verwendet, legst du im Mining-Profil fest →": "Assign devices used by the Node in the mining profile →",
        "Werte ohne Sensorquelle erscheinen als nicht verfügbar. Gesamtleistung summiert CPU-Package und erkannte GPU-Boardwerte.": "Values without a sensor source are unavailable. Total power adds CPU package and detected GPU board readings.",
        "Widerruf der Benchmark-Freigabe übermittelt.": "Benchmark sharing withdrawal sent.",
        "Windows benötigt eine Administratorfreigabe, damit der Hardware-Monitor seine Sensoren starten kann.": "Windows needs administrator approval to let the hardware monitor start its sensors.",
        "Windows-Freigabe wird angefordert …": "Requesting Windows approval …",
        "Windows-Sicherheit hat den Download blockiert – sicher prüfen": "Windows Security blocked the download – review safely",
        "Windows-Sicherheit hat den Miner blockiert.": "Windows Security blocked the miner.",
        "Wird installiert": "Installing",
        "Wird installiert …": "Installing …",
        "Wo dein Ertrag hingeht": "Where your earnings go",
        "Wo dein Mining-Ertrag hingeht": "Where your mining yield goes",
        "Worker": "Worker",
        "Worker aktiv": "Worker active",
        "Worker ansehen →": "View workers →",
        "Worker einrichten →": "Configure workers →",
        "Worker filtern": "Filter workers",
        "Worker ist aktiv": "Worker is active",
        "Worker prüfen": "Check worker",
        "Worker prüfen →": "Check workers →",
        "Worker steuern →": "Control workers →",
        "Worker zuweisen": "Assign workers",
        "Worker öffnen →": "Open worker →",
        "Worker, Gerät oder Coin suchen": "Search worker, device or coin",
        "Worker, Gerät, Coin oder Miner suchen": "Search worker, device, coin or miner",
        "Worker, Miner oder Pool suchen": "Search worker, miner or pool",
        "Worker-Name": "Worker name",
        "Wähle CPU oder GPU und installiere den passenden Miner über das Plus.": "Choose CPU or GPU and install a suitable miner using the plus button.",
        "Wähle Kryptex mit eigener ETC-Wallet oder bestätige das SolarMiner-Standardziel. Die Fee-Route muss vor dem Start freigeschaltet sein.": "Choose Kryptex with your own ETC wallet or confirm the SolarMiner default destination. The fee route must be enabled before mining starts.",
        "Wähle Kryptex mit eigener RVN-Wallet oder bestätige das SolarMiner-Standardziel. Die Fee-Route muss vor dem Start freigeschaltet sein.": "Choose Kryptex with your own RVN wallet or confirm the SolarMiner default destination. The fee route must be enabled before mining starts.",
        "Wähle deine Hardware und installiere den passenden Miner.": "Choose your hardware and install the matching miner.",
        "Wähle die Art der Messung. Ein Ergebnis benötigt 12 gültige Hashrate-Messpunkte pro Worker; die Dauer hängt vom Verbindungsaufbau ab. Ergebnisse bleiben auch ohne Teilen lokal sichtbar.": "Choose a measurement mode. A result requires 12 valid hashrate samples per worker; duration depends on establishing the connection. Results remain visible locally without sharing.",
        "Wähle einen gefundenen Proxy. Die Verbindung wird erst beim Aktivieren geändert.": "Choose a discovered proxy. The connection changes only when you activate it.",
        "Wähle in der Miner-Bibliothek einen passenden Build und installiere ihn.": "Choose a compatible build in the miner library and install it.",
        "Wähle mindestens eine GPU.": "Select at least one GPU.",
        "Wähle mindestens eine erkannte GPU.": "Select at least one detected GPU.",
        "XMRig Entwickler-Spende": "XMRig developer donation",
        "XMRig-Download gestartet.": "XMRig download started.",
        "Zeitüberschreitung": "Request timed out",
        "Zeitüberschreitung beim Sensorabruf": "Sensor request timed out",
        "Ziel noch nicht gewählt": "Target not selected yet",
        "Ziel: 12 Messpunkte je Worker": "Target: 12 observations per worker",
        "Zum Ende": "To bottom",
        "Zum Inhalt springen": "Skip to content",
        "ZUFÄLLIG": "RANDOM",
        "Zufällig": "Random",
        "Zuweisung": "Assignment",
        "Zuweisung gespeichert. Ein laufender vorheriger Worker wurde sicher angehalten.": "Assignment saved. The previous running worker was stopped safely.",
        "Zuweisung speichern": "Save assignment",
        "Zuweisungen & Steuerung →": "Assignments & control →",
        "abgeleitet / geschätzt": "derived / estimated",
        "aktives Ziel": "active target",
        "auswählen": "Select",
        "bekannte Abzüge": "known deductions",
        "brutto": "gross",
        "brutto · bei gemessener Workerleistung": "gross · at measured worker power",
        "dieses Betriebssystem": "this operating system",
        "direkte Messung": "direct measurement",
        "erlaubt": "allowed",
        "für Installation auswählen": "select for installation",
        "gemessener Workerleistung": "measured worker power",
        "gesperrt": "blocked",
        "getrennt": "disconnected",
        "installierbar": "available to install",
        "installieren": "install",
        "installiert": "installed",
        "kein Miner läuft": "no miner running",
        "keine eigene Auszahlung": "no personal payout",
        "lokaler Mining-Dienst": "local mining service",
        "lokaler Proxy": "local proxy",
        "läuft": "running",
        "maximale SolarMiner-Leistung": "maximum SolarMiner power",
        "messbare Hashrate fehlt": "measurable hashrate missing",
        "minimale SolarMiner-Leistung": "minimum SolarMiner power",
        "nicht erreichbar": "unavailable",
        "nicht verbunden": "not connected",
        "nicht verfügbar": "unavailable",
        "pausiert": "paused",
        "unbekannt": "unknown",
        "unter": "below",
        "verbunden": "connected",
        "wechseln?": "switch?",
        "· 1 % Poolgebühr": "· 1% pool fee",
        "· läuft": "· running",
        "Ändern": "Change",
        "Öffne Windows-Sicherheit → Viren- & Bedrohungsschutz → Schutzverlauf. Prüfe den Erkennungsnamen und die Datei. Gib nur eine verifizierte Datei frei. Danach kannst du den Download erneut starten.": "Open Windows Security → Virus & threat protection → Protection history. Check the detection name and file. Allow only a verified file. Then retry the download.",
        "Öffne diese Seite auf dem betroffenen PC unter http://127.0.0.1:8084/mining.html, um die Defender-Freigabe lokal anzufordern.": "Open this page on the affected PC at http://127.0.0.1:8084/mining.html to request the Defender exclusion locally.",
        "Öffne einen installierten Miner oder ergänze einen für deine CPU oder GPU.": "Open an installed miner or add one for your CPU or GPU.",
        "Öffnen": "Open",
        "Ø Latenz": "Ø Latency",
        "Übernehmen": "Apply",
        "Die Worker-Aktion wurde nicht angewendet. Prüfe die Miner-Konsole.": "The worker action was not applied. Check the miner console.",
        "Worker-Aktion fehlgeschlagen:": "Worker action failed:",
        "Weise diesem Worker zuerst einen Coin zu. Danach erscheint hier seine Minerausgabe.": "Assign a coin to this worker first. Its miner output will then appear here.",
        "Fortlaufende Minerausgabe": "Continuous miner output",
        "Log herunterladen": "Download log",
        "SolarMiner-Standardziel wird beim Start geprüft": "SolarMiner default payout will be checked at startup",
        "Ohne eigene Wallet wird beim Start das SolarMiner-Standardziel geprüft. Die gesamte Auszahlung geht dann dorthin.": "Without your own wallet, the SolarMiner default payout is checked at startup. All mining payouts then go there.",
        "Übersicht": "Overview",
        "über": "above",
        "über SolarMiner aktivieren": "through SolarMiner",
    }
};
