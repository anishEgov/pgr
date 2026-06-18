# Common Changes & Fixes: Microservice → Spring Modulith

This file lists the **cross-cutting** changes and fixes that apply to **every** service when folding
it into the single Spring Modulith app. It's the *repeatable recipe*. For service-specific detail
(idgen vs mdms vs workflow …) and the blow-by-blow narrative, see `TRANSFORMATION.md`.

- **Reference (microservices):** the original `*/` service folders — left untouched.
- **Target (modulith):** `application/` — one Spring Boot app, one `pom.xml`, one
  `application.properties`, one datasource. Root package `org.egov`; each former service is a module
  = a sub-package (`org.egov.pgr`, `org.egov.id`, `org.egov.mdmsv2`, `org.egov.localization`,
  `org.egov.wf`). `egov-user` stays external (Spring Boot 1.5 / Java 8) over HTTP/port-forward.

---

## 0. One-time / global setup (done once for the whole app)

| # | Change | Detail |
|---|---|---|
| G1 | **Single entry point** | One `@SpringBootApplication @Modulithic` class `org.egov.Application`. All other services' `main()` / `@SpringBootApplication` are dropped. |
| G2 | **Single `pom.xml`** | Union of all modules' deps, pinned to **Spring Boot 3.2.2 / Java 17** (the 3.4.x services align down). Add `spring-modulith-bom:1.1.3` + `spring-modulith-starter-core` + `-starter-test`. |
| G3 | **Single `application.properties`** | All modules' props merged into one file. |
| G4 | **Single datasource** | One Postgres; each module keeps its own `db/migration/<module>` folder. |
| G5 | **`ModularityTests`** | `ApplicationModules.of(Application.class).verify()` — the build-time boundary guard. |
| G6 | **Build on JDK 17** | `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`. NOT a code change — but mandatory: on JDK 25 the Boot-3.2 Lombok processor fails and nothing compiles. |

---

## 1. Common change recipe — applied to EVERY absorbed module

Do these for each service you fold in (idgen, mdms, localization, workflow, …):

1. **Drop the entry point.** Delete the service's `@SpringBootApplication`/`main()` class — one app
   has one entry point. (Any beans that lived ON that class must be re-homed — see Fix F4.)
2. **Repackage only if it collides / is generic.** Move it to a distinct module package *only* if
   its base package clashes (e.g. `org.egov.infra.*`, or generic `org.egov.{config,web,util}`).
   Services already on a clean distinct package (`org.egov.pgr`, `org.egov.id`, `org.egov.wf`) don't
   move. Rule of thumb: `sed 's/<old.base.pkg>/<org.egov.module>/g'` across the copied sources.
3. **Bring its DB migrations** into `db/migration/<module>` and run them into the shared DB with a
   **per-module Flyway history table** (`-table=<module>_flyway_history`) so versions don't collide.
4. **Add its unique dependencies** to the single `pom.xml` (see Fix F1 — compile-driven).
5. **Merge its properties** into the single `application.properties`, namespaced by module
   (see Fix F6 for key collisions).
6. **Publish its API**: add `@NamedInterface` to the package(s) other modules call
   (typically `…​.service` and `…​.model`), via `package-info.java`. Everything else stays internal.
7. **Register it for component scan**: add the module package to `@ComponentScan(basePackages = …)`
   on `org.egov.Application`.
8. **Rewire PGR's call** (sync): replace the `RestTemplate`/`fetchResult(...)` HTTP call with an
   injected call to the module's published bean; map DTOs at the boundary with
   `ObjectMapper.convertValue` (each service ships its own copy of the contract). Keep the module's
   REST controllers so external/Postman callers are unaffected.

---

## 2. Common BUILD-TIME fixes (recur across services)

| # | Symptom (compile) | Root cause | Fix |
|---|---|---|---|
| F1 | `package X does not exist` / `cannot find symbol` after adding a module | each service had extra deps not on the shared classpath | add that module's deps to the one `pom.xml`. Drive it by recompiling: each missing import names the jar (e.g. everit-json-schema, swagger v3 annotations, json-path/json-smart, cache2k, spring-data-jpa/redis). |
| F2 | `cannot find symbol` for getters/`log` everywhere (Lombok not generating) | building on **JDK 25**; Boot-3.2's Lombok 1.18.30 processor can't run on it | build on **JDK 17** (G6). Reproduces on the untouched reference too — environment, not transformation. |
| F3 | `incompatible types: org.egov.X cannot be converted to org.egov.module.X` | each service vendored its **own copy** of a shared contract (e.g. `MdmsCriteriaReq`) | translate at the call boundary with `ObjectMapper.convertValue(src, ModuleType.class)` — don't try to share the type. |
| F4 | `duplicate import` / two types same simple name in one file | same-named DTOs across modules | use the fully-qualified name for one of them at the call site. |

---

## 3. Common RUNTIME / BOOT fixes (recur across services)

| # | Symptom (startup) | Root cause | Fix |
|---|---|---|---|
| R1 | `ConflictingBeanDefinitionException` — two beans named e.g. `workflowService` | duplicate **simple class names** across modules (`WorkflowService`, `UserService`, `EnrichmentService`, `MDMSService`) | `@ComponentScan(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)` on `Application` → bean names become package-qualified. |
| R2 | `bean 'jacksonConverter'/'objectMapper'/'restTemplate' … already defined, overriding is disabled` | every service defined its own infra `@Bean` **methods** with identical names (`@Bean` names ignore the name generator) | `spring.main.allow-bean-definition-overriding=true`. (Clean-up later: dedupe to one shared config.) |
| R3 | Context aborts in a module's `@PostConstruct` (e.g. `json object can not be null`, `Connection refused`) | services eagerly call an external service (MDMS/user) at startup; in the modulith that dep may be in-process / DB empty / not seeded | make the eager load **tolerant** (try/catch → default + warn). A module's optional startup cache must not kill the whole app. |
| R4 | `No qualifying bean of type 'CacheManager'` (or any bean the service expected) | the bean was defined ON the service's dropped `main()`/`@SpringBootApplication` class | **re-home** that `@Bean`/`@Enable…` onto an existing `@Configuration` in the module (don't create a new class if one exists). |
| R5 | `No qualifying bean of type '<library>.SomeService'` | the service relied on a **library** `@Service`/`@Component` it picked up via a broad `org.egov` scan | add just that library package to `scanBasePackages` (e.g. `org.egov.mdms.service` for the mdms-client bean). |
| R6 | URL like `http://host:8081user/_search` (MalformedURL) at a module's outbound HTTP call | merged `host` key (no trailing slash) + a module's `endpoint` key (no leading slash); standalone they were paired differently | normalize the slash — give the endpoint a leading `/` (or the host a trailing `/`), consistently. |

---

## 4. Common MODULITH-BOUNDARY fixes (`verify()` failures)

| # | `verify()` says | Fix |
|---|---|---|
| V1 | `Module 'A' depends on non-exposed type …B.internalPkg.X within module 'B'` | publish what A legitimately needs from B via `@NamedInterface` (on B's `service`/`model` package); never reach into B's internal packages. See `failed-verify.md` (branch `demo/modulith-verify-fails`) for a live example. |
| V2 | `Module 'A' uses field injection in …X.y. Prefer constructor injection instead!` | constructor-inject the cross-module bean (intra-module field injection is fine). |
| V3 | extra/stray "modules" detected for library packages under `org.egov.*` | exclude them in `ModularityTests`: `ApplicationModules.of(App.class, JavaClass.Predicates.resideInAnyPackage("org.egov.common..","org.egov.tracer..","org.egov.mdms..","org.egov.infra.."))`. |

---

## 5. Common CONFIG-merge concerns (single `application.properties`)

| # | Concern | Handling |
|---|---|---|
| C1 | Two modules want **different values** for the **same logical setting** | **namespace per module** — `egov.idgen.*`, `egov.localization.*`, `egov.wf.*`, `mdms.*` — each module's `@Value`/`@ConfigurationProperties` binds its own prefix. Already the dominant pattern in the merged file. |
| C2 | Two modules read the **literal same key** (a framework key you can't rename, e.g. `spring.kafka.consumer.group-id`) | a flat key holds one value (last-wins). Set it **per component** in code instead — e.g. `@KafkaListener(..., groupId = "${pgr.kafka.consumer.group-id}")`. (Find collisions: `grep -vE '^\s*#|^\s*$' application.properties \| sed 's/=.*//' \| sort \| uniq -d`.) |
| C3 | Shared keys that are genuinely the same (`app.timezone`, `kafka bootstrap`, `egov.mdms.host`) | keep one value — intentional. |

---

## 6. Per-module API path (so each module keeps its ORIGINAL URL)

There is **no** global `server.servlet.context-path`. Each module's original context path is restored
by package, in `Application.configurePathMatch(...)` (implements `WebMvcConfigurer`):
```java
configurer.addPathPrefix("/pgr-services",     HandlerTypePredicate.forBasePackage("org.egov.pgr"));
configurer.addPathPrefix("/egov-idgen",       HandlerTypePredicate.forBasePackage("org.egov.id"));
configurer.addPathPrefix("/egov-workflow-v2", HandlerTypePredicate.forBasePackage("org.egov.wf"));
configurer.addPathPrefix("/mdms-v2",          HandlerTypePredicate.forBasePackage("org.egov.mdmsv2"));
configurer.addPathPrefix("/localization",     HandlerTypePredicate.forBasePackage("org.egov.localization"));
```
So idgen stays at `/egov-idgen/...`, workflow at `/egov-workflow-v2/...`, etc. — every module callable
independently, none nested under another.

---

## 7. "Absorb a new service" — quick checklist

```
[ ] copy sources into application/ (repackage only if base package collides)
[ ] delete its @SpringBootApplication / main()        (re-home any @Beans it carried — R4)
[ ] add its unique deps to the single pom             (compile-driven — F1)
[ ] copy db/migration/<module>; flyway with -table=<module>_flyway_history
[ ] merge properties (namespaced — C1); fix slash/url pairing if it calls out (R6)
[ ] @NamedInterface on the package(s) other modules call (service/model)   (V1)
[ ] add module package to Application @ComponentScan
[ ] addPathPrefix(...) to keep its original API path                       (§6)
[ ] rewrite the PGR HTTP client → in-process bean call + convertValue DTO map (F3)
[ ] build on JDK 17 (G6); mvn -o test -Dtest=ModularityTests must stay green (V1–V3)
[ ] watch first boot for R1/R2/R3/R5; apply the matching fix
```
