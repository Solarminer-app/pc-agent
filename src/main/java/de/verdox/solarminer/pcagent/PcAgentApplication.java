package de.verdox.solarminer.pcagent;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PcAgentApplication {

    public static void main(String[] args) {
        ApplicationContext context = new SpringApplicationBuilder(PcAgentApplication.class)
                .headless(false)
                .run(args);
    }

}
