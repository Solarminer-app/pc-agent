package de.verdox.solarminer.pcagent.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.*;
import de.verdox.solarminer.pcagent.miner.*;
import de.verdox.solarminer.pcagent.mining.*;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService;
import de.verdox.solarminer.pcagent.mining.energy.EnergyJournalService;
import de.verdox.solarminer.pcagent.mining.measurement.*;
import de.verdox.solarminer.pcagent.mining.power.WorkerPowerModeService;
import de.verdox.solarminer.pcagent.update.AgentUpdateService;
import de.verdox.solarminer.pcagent.mining.wallet.WalletBalanceService;
import org.springframework.stereotype.Service;

import java.util.*;

/** Independent specialist application adapter. Business rules remain in the owning domain services. */
@Service
public class ConsoleToolsService {
    public static final Set<String> ACTIONS = Set.of("route_save", "benchmark_start", "benchmark_cancel", "sweep_start", "sweep_resume", "sweep_cancel",
            "tuning_start", "tuning_cancel", "tuning_selection", "tuning_concurrency", "tuning_clear", "clock_apply", "clock_restore",
            "power_limit", "cpu_target", "power_mode", "worker_policy", "update_check", "update_start", "energy_settings", "benchmark_consent",
            "benchmark_upload", "benchmark_retry");
    private final ConsolePayoutService payouts;
    private final BenchmarkSessionService benchmarks;
    private final EfficiencySweepService sweeps;
    private final BenchmarkSharingService sharing;
    private final MiningPerformanceProfileStore profiles;
    private final AutoTuningSessionService sessions;
    private final AutoTuningProfileStore tuningProfiles;
    private final AutoTuningSelectionStore selections;
    private final LocalGpuPowerService power;
    private final GpuTuningService tuning;
    private final LocalRunLock lock;
    private final WorkerAssignmentService workers;
    private final AgentControlSettingsService controls;
    private final WorkerPowerModeService modes;
    private final WorkerCoinRevenueModeService revenue;
    private final AgentUpdateService updates;
    private final EnergyJournalService energy;
    private final ObjectMapper json;
    private final WalletBalanceService balances;
    private final MinerFactory miners;
    public ConsoleToolsService(ConsolePayoutService payouts, BenchmarkSessionService benchmarks, EfficiencySweepService sweeps,
            BenchmarkSharingService sharing, MiningPerformanceProfileStore profiles, AutoTuningSessionService sessions,
            AutoTuningProfileStore tuningProfiles, AutoTuningSelectionStore selections, LocalGpuPowerService power, GpuTuningService tuning,
            LocalRunLock lock, WorkerAssignmentService workers, AgentControlSettingsService controls, WorkerPowerModeService modes,
            WorkerCoinRevenueModeService revenue, AgentUpdateService updates, EnergyJournalService energy, ObjectMapper json, WalletBalanceService balances, MinerFactory miners) {
        this.payouts=payouts; this.benchmarks=benchmarks; this.sweeps=sweeps; this.sharing=sharing; this.profiles=profiles;
        this.sessions=sessions; this.tuningProfiles=tuningProfiles; this.selections=selections; this.power=power; this.tuning=tuning;
        this.lock=lock; this.workers=workers; this.controls=controls; this.modes=modes; this.revenue=revenue;
        this.updates=updates; this.energy=energy; this.json=json; this.balances=balances; this.miners=miners;
    }
    public void execute(String action, JsonNode arguments) throws Exception {
        String id = arguments.path("device").asText("");
        switch (action) {
            case "route_save" -> payouts.save(json.treeToValue(arguments, ConsolePayoutService.Route.class));
            case "benchmark_start" -> {
                String mode = arguments.path("mode").asText();
                if (!Set.of("LIVE", "INSTALLED").contains(mode)) throw new IllegalArgumentException("tool_invalid_request");
                benchmarks.start(mode, strings(arguments, "coins"), strings(arguments, "workers"),
                        arguments.path("startAfter").asBoolean(false), integer(arguments, "concurrency", 1, 64));
            }
            case "benchmark_cancel" -> benchmarks.cancel();
            case "sweep_start", "sweep_resume" -> {
                List<String> selected = strings(arguments, "workers");
                if (selected.stream().anyMatch(worker -> !"GPU".equals(worker(worker).hardwareType()))) throw new IllegalArgumentException("power_mode_requires_gpu");
                if (action.equals("sweep_resume")) sweeps.resume(strings(arguments,"coins"), selected, integer(arguments,"concurrency",1,64));
                else sweeps.start(strings(arguments,"coins"), selected, integer(arguments,"concurrency",1,64));
            }
            case "sweep_cancel" -> sweeps.cancel();
            case "tuning_start" -> { var worker=worker(id); sessions.start(id, worker.coin()); }
            case "tuning_cancel" -> { worker(id); sessions.cancelForDevice(id); }
            case "tuning_concurrency" -> { if (!sessions.setMaxConcurrent(integer(arguments,"concurrency",1,64))) fail("auto_tuning_concurrency_invalid"); }
            case "tuning_selection" -> {
                gpu(id);
                var selection = json.treeToValue(arguments.path("selection"), AutoTuningSelectionStore.Selection.class);
                if (selection == null || !selection.core() && !selection.memory() && !selection.power()) fail("auto_tuning_selection_required");
                if (!selections.set(id, selection)) fail("auto_tuning_selection_save_failed");
            }
            case "tuning_clear" -> {
                gpu(id);
                locked(() -> {
                    if (!sessions.clearCompletedSession(id)) fail("auto_tuning_busy");
                    if (!tuningProfiles.clearDevice(id)) fail("auto_tuning_clear_failed");
                });
                if (worker(id).status()==MinerStats.MinerStatus.MINING) workers.maybeStartAutoTuning(id);
            }
            case "clock_apply" -> {
                gpu(id);
                Integer coreMin=optionalInteger(arguments,"coreMin"), coreMax=optionalInteger(arguments,"coreMax");
                Integer memMin=optionalInteger(arguments,"memoryMin"), memMax=optionalInteger(arguments,"memoryMax");
                Integer offset=optionalInteger(arguments,"memoryOffset"), pstate=optionalInteger(arguments,"pstate");
                if (coreMin==null && coreMax==null && memMin==null && memMax==null && offset==null) fail("tool_invalid_request");
                locked(() -> { var result=tuning.apply(id,coreMin,coreMax,memMin,memMax,offset,pstate); if (!result.applied()) fail(result.reason()); });
            }
            case "clock_restore" -> { gpu(id); locked(() -> { var result=tuning.restoreDriverDefaults(id); if (!result.applied()) fail(result.reason()); }); }
            case "power_limit" -> {
                int watts=integer(arguments,"watts",1,10000); gpu(id);
                locked(() -> { var result=power.updateUserPowerLimit(id,watts); if (!result.success()) fail(result.message()); });
            }
            case "cpu_target" -> {
                var worker=worker(id);
                if (!"CPU".equals(worker.hardwareType())) fail("tool_cpu_required");
                if (worker.status()==MinerStats.MinerStatus.MINING || worker.status()==MinerStats.MinerStatus.STARTING) fail("tool_pause_before_mode");
                if (!"MANUAL".equals(worker.powerMode())) fail("tool_manual_mode_required");
                var cpu=miners.cpuMiner(worker.coin()).orElseThrow(()->new IllegalArgumentException("tool_cpu_required"));
                int watts=integer(arguments,"watts",1,10000);
                if (watts<cpu.getMinimumControllablePowerWatts() || watts>cpu.getEstimatedMaxCpuWattage()) fail("tool_cpu_target_outside_range");
                locked(()->{ if(!cpu.setPowerCap(watts)) fail("power_mode_apply_failed"); });
            }
            case "power_mode" -> saveMode(id, arguments.path("mode").asText());
            case "worker_policy" -> {
                var worker=worker(id);
                if (!arguments.path("remote").isBoolean() || !arguments.path("startOnBoot").isBoolean()) fail("tool_invalid_request");
                if (arguments.path("startOnBoot").asBoolean() && "none".equals(worker.coin())) fail("tool_assignment_required");
                locked(() -> {
                    if (!controls.setWorkerEnabled(id, arguments.path("remote").asBoolean())) fail("tool_save_failed");
                    if (!controls.setWorkerStartOnBoot(id, arguments.path("startOnBoot").asBoolean())) fail("tool_settings_partial");
                });
            }
            case "update_check" -> updates.check();
            case "update_start" -> { if (!updates.start()) fail("update_unavailable"); }
            case "energy_settings" -> {
                var value=arguments.get("pricePerKwh");
                if(value==null || !value.isNumber() || value.decimalValue().signum()<0 || value.decimalValue().compareTo(new java.math.BigDecimal("100"))>0
                        || !Set.of("EUR","USD","CHF").contains(arguments.path("currency").asText())) fail("tool_tariff_invalid");
                energy.updateSettings(json.treeToValue(arguments,EnergyJournalService.Settings.class));
            }
            case "benchmark_consent" -> { if (!arguments.path("enabled").isBoolean()) fail("tool_invalid_request"); if (!sharing.setSharingEnabled(arguments.path("enabled").asBoolean())) fail("tool_save_failed"); }
            case "benchmark_upload" -> checkUpload(sharing.uploadManualResultsOnce());
            case "benchmark_retry" -> checkUpload(sharing.sharingEnabled()?sharing.retryManualResults():sharing.uploadManualResultsOnce());
            default -> throw new IllegalArgumentException("tool_invalid_request");
        }
    }
    private static void checkUpload(BenchmarkSharingService.ReportStatus result) {
        if (result==null) fail("tool_action_failed");
        switch(result.status()) {
            case "NO_DATA" -> fail("tool_upload_no_data");
            case "NOT_SHARED" -> fail("tool_upload_not_shared");
            case "FAILED" -> fail("tool_upload_failed");
            case "SENT", "UPLOADING" -> { }
            default -> fail("tool_action_failed");
        }
    }
    private void saveMode(String id, String mode) {
        var worker=worker(id);
        if (!Set.of("MANUAL","EFFICIENCY","MAX_HASHRATE","AUTO_TUNING","REVENUE_PER_KWH","COIN_REVENUE_PER_KWH","PROFIT_PER_DAY","COIN_PROFIT_PER_DAY").contains(mode)) fail("power_mode_invalid");
        if (worker.status()==MinerStats.MinerStatus.MINING || worker.status()==MinerStats.MinerStatus.STARTING) fail("tool_pause_before_mode");
        if ("CPU".equals(worker.hardwareType()) && Set.of("MAX_HASHRATE","AUTO_TUNING","COIN_REVENUE_PER_KWH","PROFIT_PER_DAY","COIN_PROFIT_PER_DAY").contains(mode)) fail("power_mode_requires_gpu");
        locked(() -> {
            if (!"MANUAL".equals(mode)) {
                String error=Set.of("REVENUE_PER_KWH","COIN_REVENUE_PER_KWH","PROFIT_PER_DAY","COIN_PROFIT_PER_DAY").contains(mode)
                        && "GPU".equals(worker.hardwareType()) ? revenue.selectBestStartPoint(id,worker.coin(),mode).error()
                        : modes.selectForMode(id,worker.coin(),mode).error();
                if (error!=null) fail(error);
            }
            if ("AUTO_TUNING".equals(worker.powerMode()) && !"AUTO_TUNING".equals(mode) && !modes.restoreAutoTuningClocks(id)) fail("gpu_clock_restore_failed");
            if (!controls.setWorkerPowerMode(id,mode)) fail("tool_save_failed");
        });
    }
    private void locked(Runnable action) {
        if (!lock.tryBegin("operations-tool")) fail("power_mode_busy");
        try { action.run(); } finally { lock.end(); }
    }
    private static void fail(String key) { throw new IllegalStateException(key==null?"tool_action_failed":key); }
    private WorkerAssignmentService.WorkerView worker(String id) {
        return workers.workers().stream().filter(worker->Objects.equals(id,worker.deviceId())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("console_worker_missing"));
    }
    private LocalGpuPowerService.Gpu gpu(String id) {
        return power.snapshot().stream().filter(gpu->Objects.equals(id,gpu.deviceId())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("gpu_not_found"));
    }
    private static int integer(JsonNode node,String field,int min,int max) {
        var value=node.get(field);
        if (value==null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<min || value.intValue()>max)
            throw new IllegalArgumentException("tool_invalid_request");
        return value.intValue();
    }
    private static Integer optionalInteger(JsonNode node,String field) {
        if (!node.hasNonNull(field)) return null;
        return integer(node,field,-100000,100000);
    }
    private static List<String> strings(JsonNode node,String field) {
        var values=node.get(field);
        if (values==null || !values.isArray() || values.isEmpty() || values.size()>4096) throw new IllegalArgumentException("tool_select_workers_coins");
        List<String> result=new ArrayList<>();
        for (var value:values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length()>200) throw new IllegalArgumentException("tool_invalid_request");
            result.add(value.asText());
        }
        return List.copyOf(new LinkedHashSet<>(result));
    }
    /** Called only by the background snapshot service, never from an HTTP request. */
    public Object export(String section) {
        return switch(section) {
            case "tuning" -> Map.of("profiles",tuningProfiles.all(),"measurements",tuningProfiles.measurements());
            case "measurements" -> Map.of("benchmarks",profiles.all(),"efficiency",sweeps.profiles());
            default -> throw new IllegalArgumentException("tool_invalid_request");
        };
    }
    public Object collect(String section) { return collect(section, null); }
    public Object collect(String section, String device) {
        return switch(section) {
            case "wallets" -> {
                var routes=payouts.routes();
                yield Map.of("routes",routes,"balances",balances.balances(routes.stream().filter(route->route.route()!=null)
                        .map(route->new WalletBalanceService.Target(route.coin(),route.route().poolUrl(),route.route().wallet())).toList()));
            }
            case "measurements" -> Map.of("benchmark",benchmarks.status(),"sweep",sweeps.status(),"benchmarks",profiles.all(),
                    "efficiency",sweeps.profiles(),"consent",sharing.sharingEnabled(),"uploads",sharing.uploadStatuses(),
                    "benchmarkStorage",profiles.storageStatus(),"sweepStorage",sweeps.profileStorageStatus());
            case "hardware" -> Map.of("gpus",power.snapshot(),"workers",workers.workers(),"energy",energy.overview(),
                    "cpu",miners.cpuMiner(Coin.MONERO.id()).map(cpu->Map.of("minWatts",cpu.getMinimumControllablePowerWatts(),"maxWatts",cpu.getEstimatedMaxCpuWattage())).orElse(Map.of()));
            case "tuning" -> {
                var history=tuningProfiles.measurements().stream().filter(row->device!=null && device.equals(row.deviceId())).toList();
                var cards=power.snapshot().stream().filter(gpu->device!=null && device.equals(gpu.deviceId())).toList();
                var saved=tuningProfiles.all().stream().filter(row->device!=null && device.equals(row.deviceId())).toList();
                var snapshot=new LinkedHashMap<String,Object>();
                snapshot.putAll(Map.of("gpus",cards,"capabilities",cards.stream().map(gpu->tuning.capabilities(gpu.deviceId())).toList(),
                        "sessions",sessions.sessions().stream().filter(row->device!=null && device.equals(row.deviceId())).toList(),
                        "profiles",saved,"measurements",history.subList(Math.max(0,history.size()-200),history.size()),
                        "measurementsTotal",history.size(),"selection",selections.get(device==null?"":device),"concurrency",sessions.maxConcurrent()));
                snapshot.put("clocks",cards.isEmpty()?null:tuning.readEffectiveClocks(device));
                snapshot.put("offsetRange",cards.isEmpty()?null:tuning.memoryOffsetRange(device));
                yield snapshot;
            }
            case "logs" -> {
                List<Map<String,String>> sources=new ArrayList<>();
                Coin.miningCoins().forEach(coin->sources.add(Map.of("id",coin.id(),"name",coin.displayName())));
                for (var worker:workers.workers()) if (worker.index()!=null && !"none".equals(worker.coin())) {
                    String console=worker.coin()+"-"+worker.vendor()+"-"+worker.index();
                    if (MinerConsoleService.isKnownConsole(console)) sources.add(Map.of("id",console,"name",worker.hardwareModel()+" · "+console));
                }
                yield Map.of("sources",sources);
            }
            case "updates" -> updates.status();
            default -> throw new IllegalArgumentException("tool_invalid_request");
        };
    }
}
