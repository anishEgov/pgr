package org.egov;

import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Enforces the module structure (D1, D5).
 *
 * <p>In the microservice version, boundaries were physical: a service could only reach another
 * over HTTP, so it could never touch another's internals. Here there is no network, so
 * {@link ApplicationModules#verify()} takes over and fails the build if a module reaches into
 * another module's internals instead of its published surface. This is the build-time
 * substitute for the network boundary.
 *
 * <p>The root package {@code org.egov} also contains library packages ({@code org.egov.common},
 * {@code org.egov.tracer}, the {@code org.egov.mdms} mdms-client). They are excluded so the
 * module model reflects only our own services.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(
            Application.class,
            JavaClass.Predicates.resideInAnyPackage(
                    "org.egov.common..",
                    "org.egov.tracer..",
                    "org.egov.mdms..",      // mdms-client library (NOT the mdmsv2 module)
                    "org.egov.t3p..",       // any other org.egov.* third-party helpers
                    "org.egov.infra.."      // legacy parent of not-yet-migrated services
            ));

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }

    /** Prints detected modules and writes C4/PlantUML docs under target/spring-modulith-docs. */
    @Test
    void writesDocumentation() {
        modules.forEach(System.out::println);
        new Documenter(modules).writeDocumentation();
    }
}
