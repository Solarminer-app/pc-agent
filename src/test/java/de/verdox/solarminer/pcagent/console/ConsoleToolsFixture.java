package de.verdox.solarminer.pcagent.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.*;
import de.verdox.solarminer.pcagent.mining.*;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService;
import de.verdox.solarminer.pcagent.mining.energy.EnergyJournalService;
import de.verdox.solarminer.pcagent.mining.measurement.*;
import de.verdox.solarminer.pcagent.mining.power.WorkerPowerModeService;
import de.verdox.solarminer.pcagent.mining.wallet.WalletBalanceService;
import de.verdox.solarminer.pcagent.update.AgentUpdateService;
import static org.mockito.Mockito.mock;

class ConsoleToolsFixture {
    final ConsolePayoutService payouts=mock(ConsolePayoutService.class);
    final BenchmarkSessionService benchmarks=mock(BenchmarkSessionService.class);
    final EfficiencySweepService sweeps=mock(EfficiencySweepService.class);
    final BenchmarkSharingService sharing=mock(BenchmarkSharingService.class);
    final MiningPerformanceProfileStore profiles=mock(MiningPerformanceProfileStore.class);
    final AutoTuningSessionService sessions=mock(AutoTuningSessionService.class);
    final AutoTuningProfileStore tuningProfiles=mock(AutoTuningProfileStore.class);
    final AutoTuningSelectionStore selections=new AutoTuningSelectionStore();
    final LocalGpuPowerService power=mock(LocalGpuPowerService.class);
    final GpuTuningService tuning=mock(GpuTuningService.class);
    final LocalRunLock lock=new LocalRunLock();
    final WorkerAssignmentService workers=mock(WorkerAssignmentService.class);
    final AgentControlSettingsService controls=mock(AgentControlSettingsService.class);
    final WorkerPowerModeService modes=mock(WorkerPowerModeService.class);
    final WorkerCoinRevenueModeService revenue=mock(WorkerCoinRevenueModeService.class);
    final AgentUpdateService updates=mock(AgentUpdateService.class);
    final EnergyJournalService energy=mock(EnergyJournalService.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final de.verdox.solarminer.pcagent.miner.MinerFactory miners=mock(de.verdox.solarminer.pcagent.miner.MinerFactory.class);
    final WalletBalanceService balances=mock(WalletBalanceService.class);
    ConsoleToolsService service() { return new ConsoleToolsService(payouts,benchmarks,sweeps,sharing,profiles,sessions,
            tuningProfiles,selections,power,tuning,lock,workers,controls,modes,revenue,updates,energy,json,balances,miners); }
}
