package de.verdox.solarminer.pcagent.mining.measurement;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ThermalStabilityTrackerTest {
    static long at(int seconds) { return TimeUnit.SECONDS.toNanos(seconds); }
    @Test void coldMinuteNeverQualifiesAndTheReportedTitanRiseNeverSettles() {
        var tracker = new ThermalStabilityTracker();
        for (int second = 0; second <= 180; second += 5) {
            double temperature = second <= 60 ? 59 + second / 6d : second <= 120 ? 69 + (second - 60) / 6d : 79 + (second - 120) / 15d;
            tracker.add(at(second), temperature);
            assertNull(tracker.evidence(84), "The 69 -> 79 -> 83 °C rise is not steady-state evidence");
        }
    }
    @Test void stableLoadedWindowQualifiesOnlyAfterThreeMinutesWithHeadroom() {
        var tracker = new ThermalStabilityTracker();
        for (int second = 0; second <= 175; second += 5) {
            tracker.add(at(second), 78);
            assertNull(tracker.evidence(84));
        }
        tracker.add(at(180), 78);
        var proof = tracker.evidence(84);
        assertNotNull(proof);
        assertEquals(180, proof.loadedSeconds()); assertTrue(proof.windowSeconds() >= 90);
        assertTrue(proof.safeFor(81)); assertFalse(proof.safeFor(80), "A changed policy is checked again");
    }
    @Test void gapsMissingReadingsAndSettingChangesCannotReuseTheWarmup() {
        var tracker = new ThermalStabilityTracker();
        for (int second = 0; second <= 180; second += 5) tracker.add(at(second), 70);
        assertNotNull(tracker.evidence(84));
        tracker.add(at(200), 70); assertNull(tracker.evidence(84));
        for (int second = 205; second <= 380; second += 5) tracker.add(at(second), 70);
        assertNotNull(tracker.evidence(84));
        tracker.add(at(385), Double.NaN); assertNull(tracker.evidence(84));
        tracker.reset(); tracker.add(at(390), 70); assertNull(tracker.evidence(84));
    }
    @Test void slowRiseOscillationCoolingAndNearLimitPlateausDoNotQualify() {
        for (int scenario = 0; scenario < 4; scenario++) {
            var tracker = new ThermalStabilityTracker();
            for (int second = 0; second <= 300; second += 5) {
                double value = switch (scenario) {
                    case 0 -> 70 + second / 60d;
                    case 1 -> second % 10 == 0 ? 74 : 78;
                    case 2 -> 80 - second / 60d;
                    default -> 82;
                };
                tracker.add(at(second), value); assertNull(tracker.evidence(84));
            }
        }
    }
    @Test void persistedProofIsValidatedStructurallyRatherThanTrustedAsABoolean() {
        assertFalse(new ThermalStabilityTracker.Evidence(0,180,90,70,70,0).safeFor(84));
        assertFalse(new ThermalStabilityTracker.Evidence(1,60,90,70,70,0).safeFor(84));
        assertFalse(new ThermalStabilityTracker.Evidence(1,180,30,70,70,0).safeFor(84));
        assertFalse(new ThermalStabilityTracker.Evidence(1,180,90,70,70,Double.NaN).safeFor(84));
        assertFalse(new ThermalStabilityTracker.Evidence(1,180,90,70,75,0).safeFor(84));
        assertFalse(new ThermalStabilityTracker.Evidence(1,180,90,70,70,0,85).safeFor(84));
        assertFalse(new ThermalStabilityTracker.Evidence(1,180,900,70,70,0).safeFor(84));
    }
    @Test void aLaterSteadyWindowDoesNotEraseAnEarlierPeakWhenThePolicyChanges() {
        var tracker = new ThermalStabilityTracker();
        for (int second = 0; second <= 180; second += 5) tracker.add(at(second), second == 0 ? 84 : 78);
        var proof = tracker.evidence(85);
        assertNotNull(proof); assertEquals(84d, proof.peakTemperatureC());
        assertFalse(proof.safeFor(84));
    }
}
