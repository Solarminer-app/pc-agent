package de.verdox.solarminer.pcagent.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * REST controller architecture audit (2026-10-11). HTTP adapters must stay thin: they read
 * in-memory state and dispatch commands. Raw I/O primitives (HTTP client, process launching,
 * future joining) belong to the owning services' background collectors and command paths.
 * The allowlist below is the audit's exception register — every entry is justified in
 * docs/architecture/pc-agent-rest-controller-audit.md (sections C7/C11/X1/X3/S2).
 */
class RestControllerArchitectureTest {

    private static final JavaClasses AGENT = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("de.verdox.solarminer.pcagent");

    /**
     * Documented exceptions (audit report):
     * - MinerConsoleController: log tail + file download of locally written console logs (X1).
     * - ProxyDashboardController: deliberate loopback reverse proxy to the proxy child (X3).
     * - MiningController: loopback address check guarding the Defender command (C11).
     * - AgentEventController: the SSE publisher is itself the sanctioned background publisher (S2).
     */
    private static final Set<String> ALLOWLIST = Set.of(
            "de.verdox.solarminer.pcagent.controller.MinerConsoleController",
            "de.verdox.solarminer.pcagent.controller.ProxyDashboardController",
            "de.verdox.solarminer.pcagent.controller.MiningController",
            "de.verdox.solarminer.pcagent.controller.AgentEventController");

    private static final DescribedPredicate<JavaClass> NOT_ALLOWLISTED =
            DescribedPredicate.not(new DescribedPredicate<JavaClass>("are audit allowlist entries") {
                @Override public boolean test(JavaClass javaClass) {
                    return ALLOWLIST.contains(javaClass.getFullName());
                }
            });

    @Test
    void controllersNeverOpenHttpConnectionsThemselves() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..pcagent.controller..").and(NOT_ALLOWLISTED)
                .should().dependOnClassesThat().resideInAPackage("java.net.http..")
                .because("REST adapters must read snapshots; HTTP belongs to background collectors "
                        + "(ProxyHealthService, FeeTransparencyService, MarketDataService, ...)");
        rule.check(AGENT);
    }

    @Test
    void controllersNeverLaunchProcessesThemselves() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..pcagent.controller..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .because("process ownership lives in the miner/proxy services, not in HTTP adapters");
        rule.check(AGENT);
    }

    @Test
    void controllersNeverJoinFuturesOnTheRequestPath() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..pcagent.controller..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.CompletableFuture")
                .because("request threads must not fan out and join; the audit removed every "
                        + "supplyAsync(...).join() from the HTTP call chain");
        rule.check(AGENT);
    }

    @Test
    void controllersNeverResolveNetworkAddressesOrSockets() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..pcagent.controller..").and(NOT_ALLOWLISTED)
                .should().dependOnClassesThat().haveFullyQualifiedName("java.net.InetAddress")
                .because("network probing belongs to discovery services (ProxyDiscoveryService) "
                        + "and background collectors, not to HTTP adapters");
        rule.check(AGENT);
    }
}
