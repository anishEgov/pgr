package org.egov;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.tracer.config.TracerConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.Modulithic;

import java.util.TimeZone;

/**
 * Single entry point of the Spring Modulith application (D1).
 *
 * <p>Root package is {@code org.egov}. Each former microservice is one application module living
 * in a distinct sub-package: {@code org.egov.pgr} (host), and — added phase by phase —
 * {@code org.egov.id} (idgen), {@code org.egov.wf} (workflow), {@code org.egov.mdmsv2},
 * {@code org.egov.persist}, {@code org.egov.localization}.
 *
 * <p><b>Why an explicit {@code scanBasePackages} instead of the default?</b> The classpath
 * carries library jars that also live under {@code org.egov.*} ({@code org.egov.common},
 * {@code org.egov.tracer}, the {@code org.egov.mdms} mdms-client). A bare {@code org.egov} scan
 * would component-scan those too. Listing the module packages keeps the scan to our own code.
 * Each new module is activated by adding its package to this list — that is the only wiring
 * change required when a service is brought in.
 *
 * <p>{@code TracerConfiguration} and {@code MultiStateInstanceUtil} live outside the module
 * packages, so they are imported explicitly exactly as the original {@code PGRApp} did.
 */
@SpringBootApplication
@Modulithic(systemName = "eGov Platform Modulith")
@ComponentScan(basePackages = {
        "org.egov.pgr",
        "org.egov.id",              // Phase 1 — idgen (wired: pgr calls IdGenerationService in-process)
        "org.egov.mdmsv2",          // Phase 2 — mdms (wired: pgr calls MDMSService in-process)
        "org.egov.localization"     // Phase 3 — localization (wired: pgr calls MessageService in-process)
        // , "org.egov.wf"          // Phase 4 — workflow
        // , "org.egov.persist"     // Phase 5 — persister
})
@Import({TracerConfiguration.class, MultiStateInstanceUtil.class})
public class Application {

    @Value("${app.timezone}")
    private String timeZone;

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setTimeZone(TimeZone.getTimeZone(timeZone));
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
