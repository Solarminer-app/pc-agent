package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The tier decision is local to the agent and must fail toward the higher
 * (node) fee: consent hook on, or recent Node presence, both force "node".
 */
class FeeTierServiceTest {

    private FeeTierService service(AgentControlSettingsService controls) {
        return new FeeTierService(controls, mock(ProxyConfigurationService.class),
                mock(ManagedProxyService.class), new ObjectMapper());
    }

    @Test
    void consentHookOnForcesNodeTier() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, true));
        assertEquals(FeeTierService.TIER_NODE, service(controls).effectiveTier());
    }

    @Test
    void noConsentAndNoPresenceRunsTheProxyTier() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, false));
        assertEquals(FeeTierService.TIER_PROXY, service(controls).effectiveTier());
    }

    @Test
    void recentNodePresenceForcesNodeTierEvenWithoutConsentHook() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, false));
        FeeTierService service = service(controls);
        service.recordNodeActivity();
        assertEquals(FeeTierService.TIER_NODE, service.effectiveTier());
    }

    @Test
    void statusReflectsConsentAndPresence() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, true));
        FeeTierService service = service(controls);
        FeeTierService.Status status = service.status();
        assertEquals(FeeTierService.TIER_NODE, status.tier());
        assertTrue(status.externalControlEnabled());
    }
}
