package de.verdox.solarminer.pcagent.mining.economics;

import de.verdox.solarminer.pcagent.mining.energy.EnergyJournalService;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Prices saved Auto Tuning measurements with the same fresh market/tariff inputs as daily-profit modes. */
@Service
public class AutoTuningProfileEconomicsService {
    private static final Duration MAX_MARKET_AGE = Duration.ofHours(2);
    private final AutoTuningProfileStore profiles;
    private final MarketDataService market;
    private final EnergyJournalService energy;

    public AutoTuningProfileEconomicsService(AutoTuningProfileStore profiles, MarketDataService market,
                                             EnergyJournalService energy) {
        this.profiles = profiles;
        this.market = market;
        this.energy = energy;
    }

    public Comparison compare(String deviceId, String coinId, String softwareId, String softwareVersion,
                              String model, String vendor, int budgetWatts, String algorithm) {
        List<AutoTuningProfileStore.Profile> efficientCandidates = profiles.matrix(deviceId, coinId, softwareId,
                softwareVersion, model, vendor, algorithm);
        // Three complete power tiers remain the admission gate, but profit considers every
        // stable clock/memory point in those tiers rather than only their efficiency winner.
        List<AutoTuningProfileStore.Profile> candidates = efficientCandidates.isEmpty() ? List.of()
                : profiles.aggregatedCandidates(deviceId, coinId, softwareId, softwareVersion, model, vendor, algorithm).stream()
                .filter(profile -> profile.powerLimitWatts() <= budgetWatts).toList();
        Projection efficient = efficientCandidates.stream().filter(profile -> profile.powerLimitWatts() <= budgetWatts)
                .max(Comparator.comparingDouble(AutoTuningProfileStore.Profile::efficiencyHashesPerWatt)
                        .thenComparingInt(profile -> -profile.powerLimitWatts()))
                .map(this::project).orElse(null);
        Projection profitable = candidates.stream().map(this::project)
                .filter(projection -> projection.profitUsdPerDay() != null)
                .max(Comparator.comparingDouble(Projection::profitUsdPerDay)
                        .thenComparing(projection -> projection.profile().calibratedAt()))
                .orElse(null);
        return new Comparison(efficient, profitable);
    }

    private Projection project(AutoTuningProfileStore.Profile profile) {
        MarketDataService.Forecast forecast = market.forecastForProfile(profile.coinId(), profile.hashrateHs());
        if (forecast == null || !forecast.available() || forecast.stale() || forecast.updatedAt() == null
                || forecast.updatedAt().isBefore(Instant.now().minus(MAX_MARKET_AGE))
                || !(forecast.usdPerDay() > 0)) return new Projection(profile, null, null, null);
        EnergyJournalService.Settings tariff = energy.overview().settings();
        Double tariffUsd = tariff == null || tariff.pricePerKwh() == null ? null
                : market.toUsd(tariff.pricePerKwh().doubleValue(), tariff.currency());
        Double electricityUsd = tariffUsd == null || !Double.isFinite(tariffUsd) || tariffUsd < 0 ? null
                : profile.measuredWatts() * 24.0 / 1000.0 * tariffUsd;
        return new Projection(profile, forecast.usdPerDay(), electricityUsd,
                electricityUsd == null ? null : forecast.usdPerDay() - electricityUsd);
    }

    public record Comparison(Projection efficiency, Projection profit) { }
    public record Projection(AutoTuningProfileStore.Profile profile, Double grossUsdPerDay,
                             Double electricityUsdPerDay, Double profitUsdPerDay) { }
}
