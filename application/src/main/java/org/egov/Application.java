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
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.Modulithic;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.HandlerTypePredicate;

import java.util.TimeZone;

/**
 * Neutral launcher for the Spring Modulith application (D1).
 *
 * <p>This class is ONLY a bootstrap — it is NOT a "main module". Every former microservice is an
 * equal-weight application module in its own sub-package: {@code org.egov.pgr}, {@code org.egov.id}
 * (idgen), {@code org.egov.wf} (workflow), {@code org.egov.mdmsv2}, {@code org.egov.localization}
 * (and later {@code org.egov.persist}). The root lives at {@code org.egov} simply because it is the
 * common ancestor; no module is privileged.
 *
 * <p><b>Per-module API paths (no global context path).</b> In the microservice world each service
 * answered on its own context path (idgen {@code /egov-idgen}, workflow {@code /egov-workflow-v2},
 * mdms {@code /mdms-v2}, localization {@code /localization}, pgr {@code /pgr-services}). We restore
 * exactly that here with {@link #configurePathMatch} — each module package gets its original prefix —
 * so every module is callable independently at its original URL (e.g. {@code POST /egov-idgen/id/_generate}),
 * just like running that service alone. There is intentionally NO {@code server.servlet.context-path}.
 *
 * <p><b>Why an explicit {@code scanBasePackages}?</b> The classpath carries library jars under
 * {@code org.egov.*} ({@code org.egov.common}, {@code org.egov.tracer}, mdms-client). A bare scan
 * would pick those up; listing the module packages keeps the scan to our own code.
 */
@SpringBootApplication
@Modulithic(systemName = "eGov Platform Modulith")
@ComponentScan(
        // Several modules ship beans with the SAME simple class name (e.g. WorkflowService,
        // UserService, EnrichmentService, MDMSService exist in pgr/wf/mdmsv2). The default bean
        // namer would register them all as e.g. "workflowService" and the context would fail with
        // a ConflictingBeanDefinitionException. Fully-qualified names (package + class) make each
        // bean unique — the standard fix when one app hosts modules that were separate services.
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
        basePackages = {
        "org.egov.pgr",
        "org.egov.id",              // Phase 1 — idgen (wired: pgr calls IdGenerationService in-process)
        "org.egov.mdmsv2",          // Phase 2 — mdms (wired: pgr calls MDMSService in-process)
        "org.egov.localization",    // Phase 3 — localization (wired: pgr calls MessageService in-process)
        "org.egov.wf",              // Phase 4 — workflow (wired: pgr calls wf WorkflowService in-process)
        "org.egov.mdms.service"     // mdms-client library bean (MdmsClientService @Service) that
                                    // idgen's MdmsService autowires; the standalone idgen registered
                                    // it via a broad org.egov scan — we add just this one lib package.
        // , "org.egov.persist"     // Phase 5 — persister
})
@Import({TracerConfiguration.class, MultiStateInstanceUtil.class})
public class Application implements WebMvcConfigurer {

    @Value("${app.timezone}")
    private String timeZone;

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setTimeZone(TimeZone.getTimeZone(timeZone));
    }

    /**
     * Give each module the API path prefix its standalone service used (its old context path),
     * scoped by the module's base package. No module is "main"; each is reachable at its original
     * URL exactly as if it were running alone.
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/pgr-services",     HandlerTypePredicate.forBasePackage("org.egov.pgr"));
        configurer.addPathPrefix("/egov-idgen",       HandlerTypePredicate.forBasePackage("org.egov.id"));
        configurer.addPathPrefix("/egov-workflow-v2", HandlerTypePredicate.forBasePackage("org.egov.wf"));
        configurer.addPathPrefix("/mdms-v2",          HandlerTypePredicate.forBasePackage("org.egov.mdmsv2"));
        configurer.addPathPrefix("/localization",     HandlerTypePredicate.forBasePackage("org.egov.localization"));
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
