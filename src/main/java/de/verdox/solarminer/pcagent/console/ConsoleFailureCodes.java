package de.verdox.solarminer.pcagent.console;

import java.util.Map;
import java.util.Set;

/** Closed error codes at the new boundary; arbitrary tool/miner output stays diagnostic detail. */
final class ConsoleFailureCodes {
    private ConsoleFailureCodes() { }
    private static final Set<String> CODES = Set.of("power_mode_busy", "power_mode_unavailable", "power_mode_invalid",
            "power_mode_apply_failed", "gpu_not_found", "power_mode_no_measured_point", "power_mode_profit_unavailable",
            "gpu_power_control_unavailable", "auto_tuning_driver_default_power_unavailable", "auto_tuning_start_target_unsafe",
            "power_mode_temperature_unavailable_or_high", "power_mode_no_safe_hashrate_point", "auto_tuning_budget_above_safe_limit",
            "auto_tuning_budget_below_profile", "srbminer_native_tuning_failed", "power_mode_requires_gpu", "power_mode_requires_gpu_coin");
    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("Assign a coin to this worker first", "console_setup_required"),
            Map.entry("Zuweisung fehlt", "console_invalid_command"),
            Map.entry("Coin passt nicht zur Hardware", "console_hardware_incompatible"),
            Map.entry("Miner-Software unterstützt diesen Coin nicht", "console_hardware_incompatible"),
            Map.entry("Miner-Software ist mit diesem GPU-Hersteller nicht kompatibel", "console_hardware_incompatible"),
            Map.entry("The GPU model is not supported by this miner", "console_hardware_incompatible"),
            Map.entry("Miner-Software ist nicht einsatzbereit", "software_not_ready"),
            Map.entry("Der laufende Worker konnte nicht angehalten werden", "console_stop_failed"));
    static String forMessage(String message) {
        if (message == null) return "console_action_failed";
        if (message.equals("Install and configure at least one miner before running this benchmark")) return "tool_measure_prerequisites";
        if (CODES.contains(message)) return message;
        if (MESSAGES.containsKey(message)) return MESSAGES.get(message);
        if (message.startsWith("SolarMiner default payout for ") || message.startsWith("SolarMiner default payout could not be configured: "))
            return "console_default_payout_unavailable";
        if (message.startsWith("SolarMiner proxy route for ")) return "console_proxy_unavailable";
        return "console_action_failed";
    }
}
