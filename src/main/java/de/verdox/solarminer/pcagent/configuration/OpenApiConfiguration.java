package de.verdox.solarminer.pcagent.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI pcAgentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SolarMiner PC Agent API")
                        .description("Local CPU/GPU mining-agent control API. The agent is work in progress and should only be reachable from trusted clients.")
                        .version(implementationVersion())
                        .license(new License().name("AGPL-3.0")))
                .tags(List.of(
                        new Tag().name("PC mining agent").description("Agent discovery, mining state, pool configuration and power control.")
                ));
    }

    private String implementationVersion() {
        return Optional.ofNullable(OpenApiConfiguration.class.getPackage().getImplementationVersion())
                .orElse("development");
    }
}
