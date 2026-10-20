/* Independent specialist catalog, extending only the new console's closed catalog. */
Object.assign(window.OperationsI18n.catalog, {
  tool_pool_balance: ["Pool-Guthaben", "Pool balance"],
  tool_paid: ["Ausgezahlt", "Paid total"],
  tool_chain_balance: ["On-Chain-Guthaben", "On-chain balance"],
  tool_current_clocks: [
    "Gemeldeter Kern-/Speichertakt",
    "Reported core/memory clocks",
  ],
  tool_upload_IDLE: ["Noch kein Upload", "No upload yet"],
  tool_upload_SENT: ["Erfolgreich gesendet", "Sent successfully"],
  tool_upload_UPLOADING: ["Wird hochgeladen", "Uploading"],
  tool_upload_FAILED: ["Upload fehlgeschlagen", "Upload failed"],
  tool_upload_NOT_SHARED: ["Freigabe deaktiviert", "Sharing disabled"],
  tool_upload_NO_DATA: [
    "Keine gültigen Daten für einen Upload",
    "No valid data for upload",
  ],
  gpu_power_limits_outside_driver_range: [
    "Leistungsziel liegt außerhalb der Treibergrenzen.",
    "Power target is outside driver limits.",
  ],
  gpu_power_limits_save_failed: [
    "Leistungsgrenze konnte nicht gespeichert werden.",
    "Could not save the power limit.",
  ],
  gpu_power_limit_readback_unavailable: [
    "Treiber meldet keine bestätigte Leistungsgrenze zurück.",
    "Driver does not report a verified power-limit readback.",
  ],
  gpu_power_limit_driver_rejected: [
    "Der Treiber hat die Leistungsänderung abgelehnt.",
    "The driver rejected the power change.",
  ],
  gpu_clock_controls_unavailable: [
    "Treiber bietet keine bestätigte Taktsteuerung.",
    "Driver provides no verified clock control.",
  ],
  gpu_clock_permission_denied: [
    "Berechtigung zur Taktsteuerung fehlt.",
    "Clock control permission is missing.",
  ],
  gpu_clock_probe_pending: [
    "Taktfähigkeiten werden noch geprüft.",
    "Clock capabilities are still being checked.",
  ],
  gpu_clock_target_out_of_range: [
    "Taktraten liegen außerhalb des bestätigten Treiberbereichs.",
    "Clocks are outside the verified driver range.",
  ],
  gpu_clock_write_failed: [
    "Taktraten konnten nicht geschrieben und bestätigt werden.",
    "Could not write and verify clock settings.",
  ],
  gpu_memory_clock_control_unavailable: [
    "Speichertaktsteuerung ist auf dieser GPU nicht verfügbar.",
    "Memory clock control is unavailable on this GPU.",
  ],
  gpu_memory_clock_lock_requires_write_test: [
    "Speichertaktsperre benötigt zuerst einen erfolgreichen Treiber-Schreibtest.",
    "Memory clock lock requires a successful driver write test first.",
  ],
  gpu_memory_offset_unavailable: [
    "Der Treiber bietet keinen verwendbaren Speicher-Offset.",
    "Driver provides no usable memory offset.",
  ],
  gpu_tuning_recovery_save_failed: [
    "Wiederherstellungsdaten konnten nicht gespeichert werden; Änderung abgebrochen.",
    "Could not save recovery data; change aborted.",
  ],
  gpu_tuning_request_invalid: [
    "Ungültige Takteinstellungen.",
    "Invalid clock settings.",
  ],
  auto_tuning_requires_active_worker: [
    "Worker zuerst starten. Kalibrierung benötigt laufendes Mining.",
    "Start the worker first. Calibration requires running mining.",
  ],
  auto_tuning_requires_gpu_coin: [
    "Auto Tuning benötigt eine GPU mit zugewiesenem GPU-Coin.",
    "Auto Tuning requires a GPU with an assigned GPU coin.",
  ],
  auto_tuning_requires_matching_algorithm: [
    "Laufender Algorithmus passt nicht zur Zuweisung. Worker neu starten.",
    "Running algorithm does not match the assignment. Restart the worker.",
  ],
  auto_tuning_selected_control_unavailable: [
    "Eine gewählte Kalibrierungsdimension wird vom Treiber nicht unterstützt.",
    "Driver does not support a selected calibration dimension.",
  ],
  auto_tuning_capacity_reached: [
    "Die konfigurierte Kalibrierungsparallelität ist bereits belegt.",
    "Configured calibration concurrency is already in use.",
  ],
  auto_tuning_gpu_requires_attention: [
    "GPU benötigt eine Prüfung, bevor die Kalibrierung fortgesetzt wird.",
    "GPU needs attention before calibration can continue.",
  ],
  auto_tuning_gpu_unresponsive: [
    "GPU antwortet nicht. Treiber und Hardware prüfen.",
    "GPU is unresponsive. Check driver and hardware.",
  ],
  auto_tuning_no_safe_clock_candidates: [
    "Keine sicheren Taktkandidaten verfügbar.",
    "No safe clock candidates available.",
  ],
  auto_tuning_current_target_unsafe: [
    "Aktuelles Leistungsziel liegt außerhalb der sicheren Grenze.",
    "Current power target is outside the safe limit.",
  ],
  auto_tuning_phase_prepare: [
    "Kalibrierung wird vorbereitet",
    "Preparing calibration",
  ],
  auto_tuning_complete: ["Kalibrierung abgeschlossen", "Calibration complete"],
  auto_tuning_failed: ["Kalibrierung fehlgeschlagen", "Calibration failed"],
  auto_tuning_cancelled: ["Kalibrierung abgebrochen", "Calibration cancelled"],
  tool_update_start: ["Update starten", "Start update"],
  auto_tuning_gpu_reset_failed: [
    "GPU-Reset fehlgeschlagen. Treiber und andere GPU-Prozesse prüfen.",
    "GPU reset failed. Check the driver and other GPU processes.",
  ],
  auto_tuning_clock_not_reached: [
    "Der angeforderte Takt wurde nicht erreicht.",
    "Requested clock was not reached.",
  ],
  auto_tuning_clock_readback_unavailable: [
    "Der Treiber liefert keine bestätigten Taktraten.",
    "Driver provides no verified clock readback.",
  ],
  auto_tuning_insufficient_samples: [
    "Nicht genügend gültige Messwerte vorhanden.",
    "Not enough valid samples.",
  ],
  auto_tuning_memory_lock_ineffective: [
    "Die Speichertaktsperre wurde unter Last nicht wirksam.",
    "Memory clock lock was ineffective under load.",
  ],
  auto_tuning_miner_unavailable: [
    "Der Miner ist für die Kalibrierung nicht verfügbar.",
    "Miner is unavailable for calibration.",
  ],
  auto_tuning_no_confirmed_tier: [
    "Kein unabhängig bestätigtes Profil für alle erforderlichen Leistungsstufen vorhanden.",
    "No independently confirmed profile covers all required power tiers.",
  ],
  auto_tuning_observation_save_failed: [
    "Suchmessung konnte nicht gespeichert werden.",
    "Could not save search measurement.",
  ],
  auto_tuning_power_range_too_narrow: [
    "Verfügbarer Leistungsbereich ist für die Kalibrierung zu klein.",
    "Available power range is too narrow for calibration.",
  ],
  auto_tuning_profile_save_failed: [
    "Kalibrierungsprofil konnte nicht gespeichert werden.",
    "Could not save calibration profile.",
  ],
  auto_tuning_sample_unavailable: [
    "Messwert ist derzeit nicht verfügbar.",
    "Sample is currently unavailable.",
  ],
  auto_tuning_stop_recovery_timeout: [
    "Miner-Stopp oder Wiederherstellung hat das Zeitlimit überschritten.",
    "Miner stop or recovery timed out.",
  ],
  auto_tuning_temperature_limit: [
    "Temperaturgrenze erreicht. Kalibrierung angehalten.",
    "Temperature limit reached. Calibration stopped.",
  ],
  auto_tuning_temperature_unavailable: [
    "Temperaturmessung fehlt. Keine sichere Kalibrierung möglich.",
    "Temperature reading is missing. Safe calibration is unavailable.",
  ],
  auto_tuning_time_limit: [
    "Kalibrierung hat das Zeitlimit erreicht.",
    "Calibration reached its time limit.",
  ],
  auto_tuning_unstable_core_clock: [
    "Der Kerntakt ist während der Messung instabil.",
    "Core clock is unstable during measurement.",
  ],
  auto_tuning_unstable_hashrate: [
    "Die Hashrate ist während der Messung instabil.",
    "Hashrate is unstable during measurement.",
  ],
  auto_tuning_worker_stopped: [
    "Worker wurde während der Kalibrierung gestoppt.",
    "Worker stopped during calibration.",
  ],
  benchmark_select_worker: [
    "Mindestens einen Worker auswählen.",
    "Select at least one worker.",
  ],
  benchmark_worker_unavailable: [
    "Ein ausgewählter Worker ist nicht mehr verfügbar.",
    "A selected worker is no longer available.",
  ],
  tool_measure_prerequisites: [
    "Zuerst Mining-Software installieren und eine verwendbare Mining-Route einrichten.",
    "Install mining software and configure a usable mining route first.",
  ],
  update_download_failed: [
    "Update-Download oder Prüfsummenprüfung fehlgeschlagen. Die laufende Version bleibt erhalten.",
    "Update download or checksum verification failed. The running version is retained.",
  ],
  tool_cpu_target: ["CPU-Leistungsziel setzen", "Set CPU power target"],
  tool_cpu_watts: [
    "CPU-Leistungsziel für diese Sitzung (geschätzte W)",
    "CPU power target for this session (estimated W)",
  ],
  tool_cpu_hint: [
    "CPU zuerst pausieren und den manuellen Modus wählen. Das Ziel steuert das Thread-Budget beim nächsten Start; es ist keine gemessene Leistungsbegrenzung am Stromanschluss und wird nicht über Agent-Neustarts gespeichert.",
    "Pause the CPU and choose manual mode first. The target controls the thread budget on the next start; it is not a measured wall-power cap and is not persisted across agent restarts.",
  ],
  tool_cpu_required: [
    "Für diese Einstellung einen CPU-Worker auswählen.",
    "Select a CPU worker for this setting.",
  ],
  tool_cpu_target_outside_range: [
    "CPU-Ziel liegt außerhalb des geschätzten steuerbaren Bereichs.",
    "CPU target is outside the estimated controllable range.",
  ],
  tool_manual_mode_required: [
    "Zuerst den manuellen Betriebsmodus wählen.",
    "Choose manual operating mode first.",
  ],
  tool_tariff_invalid: [
    "Stromtarif muss zwischen 0 und 100 liegen, mit EUR, USD oder CHF.",
    "Electricity tariff must be between 0 and 100, in EUR, USD or CHF.",
  ],
  tool_upload_no_data: [
    "Keine gespeicherten Messwerte für einen Upload vorhanden.",
    "No saved measurements available for upload.",
  ],
  tool_upload_not_shared: [
    "Für diesen Upload liegt keine Freigabe vor.",
    "Sharing permission is missing for this upload.",
  ],
  tool_upload_failed: [
    "Upload konnte nicht übermittelt werden. Den Upload-Status prüfen.",
    "Could not deliver upload. Check upload status.",
  ],
  tool_run_RUNNING: ["Messung läuft", "Measuring"],
  tool_run_COMPLETE: ["Abgeschlossen", "Complete"],
  tool_run_QUEUED: ["Wartet", "Queued"],
  tool_run_CANCELLED: ["Abgebrochen", "Cancelled"],
  tool_run_FAILED: ["Fehlgeschlagen", "Failed"],
  tool_select_page: ["Diese Seite auswählen", "Select this page"],
  tool_title: ["Werkzeuge", "Tools"],
  tool_intro: [
    "Einrichten, messen und optimieren – direkt auf diesem Agent.",
    "Configure, measure and optimize directly on this agent.",
  ],
  tool_wallets: ["Wallets & Pools", "Wallets & pools"],
  tool_measurements: ["Benchmarks", "Benchmarks"],
  tool_hardware: ["Leistung & Betriebsmodi", "Power & operating modes"],
  tool_tuning: ["Auto Tuning & Taktraten", "Auto Tuning & clocks"],
  tool_updates: ["Agent-Updates", "Agent updates"],
  tool_logs: ["Miner-Konsole", "Miner console"],
  tool_choose_workers: ["Worker auswählen", "Choose workers"],
  tool_choose_device: ["Gerät auswählen", "Choose device"],
  tool_device_selected: [
    "Ausgewähltes Gerät: {device}",
    "Selected device: {device}",
  ],
  tool_worker_selection: [
    "{count} Worker für den Messlauf ausgewählt",
    "{count} workers selected for measurement",
  ],
  tool_tasks_hint: [
    "Aufträge werden nicht automatisch wiederholt. Der Verlauf bleibt nach einem Seitenwechsel verfügbar.",
    "Tasks are not retried automatically. History remains available after navigating away.",
  ],
  tool_load_failed: [
    "Daten sind derzeit nicht verfügbar. Der letzte Quellstand bleibt sichtbar; Schreibaktionen sind gesperrt.",
    "Data is currently unavailable. The last source snapshot remains visible; writes are disabled.",
  ],
  tool_wait_for_data: [
    "Auf den ersten Datenstand warten …",
    "Waiting for the first snapshot …",
  ],
  tool_select_workers_coins: [
    "Mindestens einen Worker und einen Coin auswählen.",
    "Select at least one worker and one coin.",
  ],
  tool_invalid_request: [
    "Eingaben sind ungültig. Werte und Auswahl prüfen.",
    "Invalid input. Check values and selection.",
  ],
  tool_request_id_conflict: [
    "Diese Auftrags-ID wurde bereits für andere Eingaben verwendet.",
    "This task ID has already been used for different input.",
  ],
  tool_action_failed: [
    "Aktion fehlgeschlagen. Voraussetzungen und Diagnose prüfen.",
    "Action failed. Check prerequisites and diagnostics.",
  ],
  tool_pool_invalid: [
    "Pool-Adresse muss stratum+tcp://host:port oder stratum+ssl://host:port sein, ohne Zugangsdaten oder Pfad.",
    "Pool address must be stratum+tcp://host:port or stratum+ssl://host:port, without credentials or a path.",
  ],
  tool_wallet_invalid: [
    "Die Wallet-Adresse passt nicht zum ausgewählten Coin.",
    "The wallet address does not match the selected coin.",
  ],
  tool_worker_invalid: [
    "Worker-Name: 1–32 Buchstaben, Ziffern, _ oder -.",
    "Worker name: 1–32 letters, digits, _ or -.",
  ],
  tool_adapter_missing: [
    "Für diesen Coin ist kein verwendbarer Miner-Adapter vorhanden.",
    "No usable miner adapter exists for this coin.",
  ],
  tool_route_partial: [
    "Die Konfiguration konnte nicht für alle Miner gespeichert werden. Betroffene Worker bleiben gestoppt. Gespeicherte Routen vor einem Neustart prüfen.",
    "Could not save configuration for every miner. Affected workers remain stopped. Check saved routes before restarting.",
  ],
  tool_settings_partial: [
    "Ein Teil der Einstellungen wurde gespeichert. Aktuellen Stand prüfen.",
    "Some settings were saved. Check the current state.",
  ],
  tool_save_failed: [
    "Einstellungen konnten nicht gespeichert werden.",
    "Could not save settings.",
  ],
  tool_remove_failed: [
    "Mining-Software konnte nicht vollständig entfernt werden.",
    "Could not fully remove mining software.",
  ],
  tool_assignment_required: [
    "Zuerst einen Coin zuweisen.",
    "Assign a coin first.",
  ],
  tool_pause_before_mode: [
    "Worker zuerst pausieren, dann den Betriebsmodus speichern. Anschließend ausdrücklich starten.",
    "Pause the worker before saving its operating mode, then start it explicitly.",
  ],
  tool_coin: ["Coin", "Coin"],
  tool_pool: ["Pool-Adresse", "Pool address"],
  tool_wallet: ["Eigene Wallet-Adresse", "Custom wallet address"],
  tool_worker_name: ["Pool-Worker-Name", "Pool worker name"],
  tool_default_hint: [
    "Leere Wallet verwendet das verfügbare SolarMiner-Standardziel als vollständige Pool-/Wallet-Route. Der Proxy wird automatisch übernommen.",
    "An empty wallet uses the available SolarMiner default target as a complete pool/wallet route. The proxy is supplied automatically.",
  ],
  tool_route_stop: [
    "Speichern stoppt die betroffenen Miner dieses Coins. Andere Coins werden nicht ausgewählt oder gestartet.",
    "Saving stops affected miners for this coin. Other coins are not selected or started.",
  ],
  tool_route_saved: ["Gespeicherte Route", "Saved route"],
  tool_default_active: [
    "SolarMiner-Standardziel aktiv",
    "SolarMiner default target active",
  ],
  tool_own_active: ["Eigene Wallet aktiv", "Custom wallet active"],
  tool_default_missing: [
    "Standardziel derzeit nicht verfügbar",
    "Default target currently unavailable",
  ],
  tool_default_target: [
    "Verfügbares Standardziel: {pool} · {wallet}",
    "Available default target: {pool} · {wallet}",
  ],
  tool_mode: ["Messmodus", "Measurement mode"],
  tool_live: ["Laufende Miner beobachten", "Observe running miners"],
  tool_installed: [
    "Installierte Miner nacheinander messen",
    "Measure installed miners sequentially",
  ],
  tool_coins: ["Coins für den Messlauf", "Coins for measurement"],
  tool_concurrency: ["Gleichzeitige GPUs (1–64)", "Concurrent GPUs (1–64)"],
  tool_after: [
    "Nach dem Benchmark die gewählten Worker starten",
    "Start selected workers after benchmarking",
  ],
  tool_measure_hint: [
    "Installierte Benchmarks und Effizienz-Sweeps pausieren und restaurieren Miner. LIVE beobachtet laufende Miner. Ein Steuerbefehl allein belegt noch keine Hashrate.",
    "Installed benchmarks and efficiency sweeps pause and restore miners. LIVE observes running miners. A control command alone does not prove hashrate.",
  ],
  tool_benchmark_start: ["Benchmark starten", "Start benchmark"],
  tool_benchmark_cancel: ["Benchmark abbrechen", "Cancel benchmark"],
  tool_sweep_start: ["Effizienz-Sweep neu starten", "Restart efficiency sweep"],
  tool_sweep_resume: [
    "Unterbrochenen Sweep fortsetzen",
    "Resume interrupted sweep",
  ],
  tool_sweep_cancel: ["Sweep abbrechen", "Cancel sweep"],
  tool_results: [
    "Ergebnisse & gespeicherte Profile",
    "Results & saved profiles",
  ],
  tool_progress: ["Fortschritt", "Progress"],
  tool_active: ["Läuft", "Running"],
  tool_idle: ["Inaktiv / abgeschlossen", "Idle / complete"],
  tool_samples: ["Messwerte", "Samples"],
  tool_efficiency: ["Hashes pro Watt", "Hashes per watt"],
  tool_profile_source: ["Quelle", "Source"],
  tool_export: [
    "Vollständige Daten als JSON exportieren",
    "Export complete data as JSON",
  ],
  tool_rows_limit: [
    "{count} Datensätze vorhanden; höchstens die letzten 200 werden geladen und 50 angezeigt. Export enthält alle Daten.",
    "{count} records available; at most the latest 200 are loaded and 50 displayed. Export includes all data.",
  ],
  tool_consent: [
    "Benchmark-Daten dauerhaft für die Community freigeben",
    "Continuously share benchmark data with the community",
  ],
  tool_consent_hint: [
    "Die Freigabe ist freiwillig. Einmaliges Hochladen und dauerhafte Freigabe sind getrennte Aktionen.",
    "Sharing is optional. One-time upload and continuous sharing are separate actions.",
  ],
  tool_upload_once: [
    "Letzten Benchmark einmal hochladen",
    "Upload latest benchmark once",
  ],
  tool_upload_retry: [
    "Fehlgeschlagenen Upload wiederholen",
    "Retry failed upload",
  ],
  tool_upload_status: ["Upload-Status", "Upload status"],
  tool_never_zero: [
    "Fehlende Messwerte bleiben unbekannt. Miner-Shares und Prognosen sind keine Pool-Abrechnung.",
    "Missing readings remain unknown. Miner shares and forecasts are not pool accounting.",
  ],
  tool_watts: ["Manuelle Leistungsgrenze (W)", "Manual power limit (W)"],
  tool_power_hint: [
    "Treibergrenzen gelten weiterhin. Automatikmodi können beim Start einen anderen gemessenen Zielwert verwenden.",
    "Driver limits still apply. Automatic modes may use another measured target when starting.",
  ],
  tool_driver_bounds: ["Treiberbereich", "Driver range"],
  tool_user_bounds: ["Nutzerbereich", "User range"],
  tool_current_cap: ["Aktuelle Grenze", "Current limit"],
  tool_driver_default: ["Treiberstandard", "Driver default"],
  tool_remote_worker: [
    "Fernsteuerung für diesen Worker erlauben",
    "Allow remote control for this worker",
  ],
  tool_boot: [
    "Diesen Worker beim Agent-Start starten",
    "Start this worker when the agent starts",
  ],
  tool_policy: [
    "Worker-Freigaben & Startverhalten",
    "Worker permissions & startup",
  ],
  tool_tariff: ["Stromtarif pro kWh", "Electricity tariff per kWh"],
  tool_currency: ["Währung", "Currency"],
  tool_energy: ["Energie & Stromtarif", "Energy & electricity tariff"],
  tool_energy_today: ["Heute", "Today"],
  tool_gpu_required: [
    "Für diese Funktion eine lokale GPU auswählen.",
    "Select a local GPU for this function.",
  ],
  tool_worker_required: [
    "Einen lokalen Worker auswählen.",
    "Select a local worker.",
  ],
  tool_selection: ["Kalibrierungseinstellungen", "Calibration settings"],
  tool_core: ["Kerntakt", "Core clock"],
  tool_memory: ["Speichertakt", "Memory clock"],
  tool_power_dimension: ["Leistungsgrenze", "Power limit"],
  tool_goal: ["Betriebsziel", "Operating goal"],
  tool_goal_efficiency: [
    "Höchste gemessene Effizienz",
    "Highest measured efficiency",
  ],
  tool_goal_profit: ["Geschätzter Tagesgewinn", "Estimated daily profit"],
  tool_profit_hint: [
    "Tagesgewinn benötigt aktuelle Marktdaten und einen gültigen Stromtarif. Ohne Prognose verwendet Auto Tuning das Effizienzprofil. Gebühren und tatsächliche Auszahlungen sind nicht enthalten.",
    "Daily profit requires current market data and a valid electricity tariff. Without a forecast, Auto Tuning uses the efficiency profile. Fees and actual payouts are excluded.",
  ],
  tool_tuning_start: ["Gerät jetzt kalibrieren", "Calibrate device now"],
  tool_tuning_cancel: [
    "Kalibrierung dieses Geräts abbrechen",
    "Cancel calibration for this device",
  ],
  tool_tuning_clear: [
    "Kalibrierdaten dieses Geräts löschen",
    "Delete calibration data for this device",
  ],
  tool_clear_confirm: [
    "Gespeicherte Kalibrierungen und Messwerte dieses Geräts löschen? Diese Aktion kann nicht rückgängig gemacht werden.",
    "Delete saved calibration profiles and readings for this device? This action cannot be undone.",
  ],
  tool_clock_manual: ["Manuelle Taktraten", "Manual clocks"],
  tool_core_min: ["Kern Minimum (MHz)", "Core minimum (MHz)"],
  tool_core_max: ["Kern Maximum (MHz)", "Core maximum (MHz)"],
  tool_memory_min: ["Speicher Minimum (MHz)", "Memory minimum (MHz)"],
  tool_memory_max: ["Speicher Maximum (MHz)", "Memory maximum (MHz)"],
  tool_offset: ["Speicher-Offset (MHz)", "Memory offset (MHz)"],
  tool_pstate: [
    "Offset-P-State (-1 für geräteweit)",
    "Offset P-state (-1 for device-wide)",
  ],
  tool_clock_apply: ["Taktraten anwenden", "Apply clocks"],
  tool_clock_restore: [
    "Treiberstandards wiederherstellen",
    "Restore driver defaults",
  ],
  tool_clock_hint: [
    "Leere Taktfelder bleiben ungesetzt. Nur vom Treiber bestätigte Bereiche sind verwendbar. Gespeicherte Taktraten können die nächste Mining-Sitzung beeinflussen.",
    "Empty clock fields remain unset. Only driver-verified ranges are usable. Saved clocks can affect the next mining session.",
  ],
  tool_history: [
    "Kalibrierungsprofil & Messverlauf",
    "Calibration profile & measurement history",
  ],
  tool_provisional: ["Suchmessung (vorläufig)", "Search reading (provisional)"],
  tool_confirmed: ["Bestätigtes Ergebnis", "Confirmed result"],
  tool_stable: ["Stabil", "Stable"],
  tool_unstable: ["Instabil", "Unstable"],
  tool_no_results: ["Noch keine Messwerte vorhanden.", "No measurements yet."],
  tool_versions: ["Version & Kanal", "Version & channel"],
  tool_update_current: [
    "Installiert: {current} · verfügbar: {available} · Kanal: {channel}",
    "Installed: {current} · available: {available} · channel: {channel}",
  ],
  tool_update_check: ["Nach Updates suchen", "Check for updates"],
  tool_update_install: [
    "Update installieren und Agent neu starten",
    "Install update and restart agent",
  ],
  tool_update_confirm: [
    "Das geprüfte Update installieren und diesen Agent neu starten?",
    "Install the verified update and restart this agent?",
  ],
  tool_update_docker: [
    "Docker-Update auf dem Host ausführen. Dateinamen und GPU-Overlay bei eigenen Installationen anpassen.",
    "Run the Docker update on the host. Adapt filenames and GPU overlay for custom installations.",
  ],
  tool_update_native: [
    "Automatische Installation benötigt den überwachten nativen Launcher. Andernfalls den Agent über den ursprünglichen Installer aktualisieren.",
    "Automatic installation requires the supervised native launcher. Otherwise update through the original installer.",
  ],
  tool_update_reconnect: [
    "Agent startet neu. Verbindung und tatsächlich laufende Version werden erneut geprüft …",
    "Agent is restarting. Rechecking connection and the version actually running …",
  ],
  tool_update_complete: [
    "Der Agent läuft mit der erwarteten neuen Version.",
    "The agent is running the expected new version.",
  ],
  tool_channel_beta: ["Beta", "Beta"],
  tool_channel_stable: ["Stabil", "Stable"],
  tool_update_checking: ["Release wird geprüft …", "Checking release …"],
  tool_update_downloading: [
    "Update wird heruntergeladen …",
    "Downloading update …",
  ],
  tool_update_verifying: [
    "Prüfsumme wird verifiziert …",
    "Verifying checksum …",
  ],
  tool_update_restarting: ["Agent wird neu gestartet …", "Restarting agent …"],
  tool_update_ready: [
    "Versionsprüfung abgeschlossen.",
    "Version check complete.",
  ],
  tool_update_failed: ["Update fehlgeschlagen.", "Update failed."],
  update_unavailable: [
    "Kein ausführbares Update verfügbar. Kanal, Launcher und laufende Prüfung kontrollieren.",
    "No executable update available. Check channel, launcher and running check.",
  ],
  update_check_failed: [
    "Release-Prüfung fehlgeschlagen. Netzwerkverbindung prüfen.",
    "Release check failed. Check the network connection.",
  ],
  update_start_failed: [
    "Update konnte nicht gestartet werden.",
    "Could not start update.",
  ],
  update_request_failed: [
    "Update-Anfrage fehlgeschlagen.",
    "Update request failed.",
  ],
  tool_log_source: ["Miner-Ausgabe auswählen", "Choose miner output"],
  tool_log_follow: [
    "Neue Ausgabe automatisch nachführen",
    "Follow new output automatically",
  ],
  tool_log_download: [
    "Vollständiges Log herunterladen",
    "Download complete log",
  ],
  tool_log_clear: ["Anzeige leeren", "Clear display"],
  tool_log_hint: [
    "Originalausgabe des Miners bleibt unverändert und kann Zugangsdaten enthalten. Die Anzeige ist auf die letzten 200.000 Zeichen begrenzt.",
    "Original miner output is retained verbatim and may contain credentials. Display is limited to the last 200,000 characters.",
  ],
  tool_job_missing: [
    "Auftrag nicht mehr verfügbar. Agent könnte neu gestartet sein. Aktuellen Zustand vor einer erneuten Aktion prüfen.",
    "Task is no longer available. The agent may have restarted. Check current state before another action.",
  ],
  tool_acceptance_unknown: [
    "Antwort unklar. Auftragsstatus wird über dieselbe ID geprüft; der Schreibbefehl wird nicht erneut gesendet.",
    "Response unclear. Checking task status using the same ID; the write command is not sent again.",
  ],
  tool_discard_changes: [
    "Nicht gespeicherte Änderungen für diese Ansicht verwerfen?",
    "Discard unsaved changes for this view?",
  ],
  tool_route_save: ["Wallet-/Pool-Route speichern", "Save wallet/pool route"],
  tool_power_limit: ["Leistungsgrenze speichern", "Save power limit"],
  tool_power_mode: ["Betriebsmodus speichern", "Save operating mode"],
  tool_worker_policy: ["Worker-Freigaben speichern", "Save worker permissions"],
  tool_tuning_selection: [
    "Kalibrierungseinstellungen speichern",
    "Save calibration settings",
  ],
  tool_tuning_concurrency: ["Parallelität speichern", "Save concurrency"],
  tool_energy_settings: ["Stromtarif speichern", "Save electricity tariff"],
  tool_benchmark_consent: [
    "Community-Freigabe speichern",
    "Save community sharing",
  ],
  tool_benchmark_upload: ["Einmaligen Upload senden", "Send one-time upload"],
  tool_benchmark_retry: ["Upload wiederholen", "Retry upload"],
  tool_software_remove: ["Mining-Software entfernen", "Remove mining software"],
  auto_tuning_concurrency_invalid: [
    "Parallelität muss zwischen 1 und 64 liegen.",
    "Concurrency must be between 1 and 64.",
  ],
  auto_tuning_selection_required: [
    "Mindestens eine Kalibrierungsdimension auswählen.",
    "Select at least one calibration dimension.",
  ],
  auto_tuning_selection_save_failed: [
    "Kalibrierungseinstellungen konnten nicht gespeichert werden.",
    "Could not save calibration settings.",
  ],
  auto_tuning_clear_failed: [
    "Kalibrierdaten konnten nicht vollständig gelöscht werden.",
    "Could not fully delete calibration data.",
  ],
  auto_tuning_busy: [
    "Eine Kalibrierung oder Messung läuft. Zuerst beenden.",
    "A calibration or measurement is running. Finish it first.",
  ],
  gpu_clock_restore_failed: [
    "Treiber-Taktraten konnten nicht wiederhergestellt werden.",
    "Could not restore driver clocks.",
  ],
  auto_tuning_phase_sampling: ["Suchmessung", "Search sampling"],
  auto_tuning_phase_confirming: [
    "Unabhängige Bestätigung",
    "Independent confirmation",
  ],
  auto_tuning_phase_complete: [
    "Kalibrierung abgeschlossen",
    "Calibration complete",
  ],
  auto_tuning_phase_failed: [
    "Kalibrierung fehlgeschlagen",
    "Calibration failed",
  ],
  auto_tuning_phase_cancelled: [
    "Kalibrierung abgebrochen",
    "Calibration cancelled",
  ],
});
