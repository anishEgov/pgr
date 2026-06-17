# PGR → Spring Modulith Transformation Log

> **Purpose of this file:** track *every* structural change made while converting this
> set of DIGIT microservices into a single Spring Modulith application. For each change
> we record **what** changed, **why** (from first principles), and **how the same problem
> is solved differently** in a classic monolith vs. a modulith vs. the microservices we
> are coming from. Nothing is changed without an entry here.

---

## 1. Goal (as agreed)

Convert the current **microservice layout** (7 independently bootable Spring Boot apps in
sibling folders) into **one Spring Boot application** structured with **Spring Modulith**:

- **`pgr-services` is the main/host application.** It owns the single entry point
  (`@SpringBootApplication`), the single `pom.xml`, and the single `application.properties`.
- The other services become **modules inside the same application** (same JVM, same process).
- **Existing endpoints stay reachable.** e.g. calling `egov-idgen/id/_generate` from Postman
  must still work exactly as before — internalizing a service must not remove its HTTP surface.
- **Synchronous** integrations between PGR and `user / workflow / idgen / mdms / localization`
  stop being HTTP calls and become **in-process calls across module boundaries** (the point
  of a modulith). **Asynchronous** flows (persister, notifications) will later use
  **Spring Modulith application events** — deferred for now.
- **One shared datasource** (single Postgres) — see §4.
- Dependencies that are **not present in this repo** (HRMS, boundary-service, url-shortener)
  remain **HTTP calls via `kubectl port-forward`**.

---

## 2. First-principles framing: why a modulith at all?

A system has two independent axes that microservices forcibly couple together:

1. **Logical modularity** — code organized into bounded contexts with clear, enforced
   boundaries (who may call whom, what is public API vs. internal).
2. **Physical distribution** — each boundary deployed as a separately running process,
   talking over the network.

Microservices give you (1) *only by paying for* (2). But (2) has a real bill:
network latency, serialization, partial-failure handling, retries, distributed tracing,
eventual consistency, N deployments, N datastores, N config sets, version skew
(we literally have Spring Boot 1.5 → 3.4 across these services).

**Spring Modulith's thesis:** you can keep (1) — enforced bounded contexts — *without* (2).
Modules live in one deployable; boundaries are enforced at **build/test time** by the
`spring-modulith` verification API instead of by the network. You get the in-process speed
and single-transaction simplicity of a monolith, but with the discipline that normally only
distribution forces on you.

| Concern | Microservices (now) | Big-ball-of-mud monolith | **Spring Modulith (target)** |
|---|---|---|---|
| Calling another context | HTTP + JSON over network | direct call, no boundary | **direct call through the module's public API only** |
| Boundary enforcement | network (physical) | none | **`ApplicationModules.verify()` at test time** |
| Failure mode of a call | network error, timeout, retry | `NullPointerException` | normal in-process exception |
| Data consistency | distributed / eventual | single DB transaction | **single DB transaction** |
| Deployment | N pipelines, N pods | 1 | **1** |
| Config | N property sets, version skew | 1 | **1** |
| Latency between contexts | ms (serialized) | ns | **ns** |
| Refactoring across contexts | coordinate N repos/teams | trivial but unsafe | **trivial + compiler-checked** |

The transformation is essentially: **collapse axis (2), preserve axis (1) by making it
explicit with Spring Modulith.**

---

## 3. Current state (discovered)

| Folder | Base package | Entry point | Spring Boot | Java | Context path / port | Own DB |
|---|---|---|---|---|---|---|
| `pgr-services` (HOST) | `org.egov.pgr` | `PGRApp` | 3.2.2 | 17 | `/pgr-services` :8280 | `pgr` |
| `egov-user` | `org.egov.user` | `EgovUserApplication` | **1.5.22** | **8** | `/user` :8081 | user db |
| `egov-workflow-v2` | `org.egov.wf` | `Main` | 3.4.5 | 17 | `/egov-workflow-v2` :8084 | wf db |
| `idgen` | `org.egov` *(→ `.id`)* | `PtIdGenerationApplication` | 3.4.5 | 17 | `/egov-idgen` :8088 | `rainmaker_new` |
| `localization` | `org.egov` *(→ `.config/.web/...`)* | `LocalizationServiceApplication` | 3.2.2 | 17 | `/localization` :8087 | loc db |
| `mdms-v2` | `org.egov` *(→ `.infra`)* | `MDMSApplication` | 3.4.5 | 17 | `/mdms-v2` :8082 | mdms db |
| `egov-persister` | `org.egov` *(→ `.infra`)* | `EgovPersistApplication` | 3.4.5 | 17 | n/a (kafka) | persist |

**Obstacle A — package collisions.** `idgen`, `localization`, `mdms-v2`, `egov-persister`
all use base package `org.egov`, and `org.egov.infra` exists in *both* mdms and persister.
Spring Modulith requires every module to be a **distinct direct sub-package of the application
root package**. So each of these must be repackaged under its own namespace.

**Obstacle B — version skew.** `egov-user` is **Spring Boot 1.5 / Java 8** (`javax.*`,
old Spring Security/Data). Merging it into a Spring Boot 3.2 / Java 17 app is a full
`javax → jakarta` migration — far from "minimal changes." **Decision:** `egov-user` stays an
**HTTP/port-forward dependency** for now (treated like HRMS/boundary) and is migrated **last**,
in its own phase. The Spring Boot 3.x services are internalized first.

---

## 4. Key decisions

> **NOTE (revised after discussion).** Earlier drafts proposed root package `org.egov.platform`
> with a full `api/`+`internal/` split per module. That was rejected as too much movement. The
> decisions below are the **final, minimal-change** agreement. Superseded rows are marked.

| # | Decision | Rationale (first principles) |
|---|---|---|
| D0 | **Keep the original service folders untouched as a microservice reference. Build the modulith in a new sibling folder `application/`.** | Non-destructive, lets us A/B the two architectures. The single Spring Modulith app lives entirely under `application/`; the 6 source folders remain the reference. |
| D1 | **App root package = `org.egov`**, single `@SpringBootApplication @Modulithic` class `org.egov.Application`. Each former service is ONE module = ONE distinct sub-package. **`scanBasePackages` lists the module packages explicitly** so the `org.egov.common`/`org.egov.tracer`/`org.egov.mdms` *library* packages on the classpath are not component-scanned. | Minimal movement: `pgr`,`id`,`wf` already sit on distinct sub-packages of `org.egov`, so they don't move. Rooting at `org.egov` (not a new `org.egov.platform`) avoids repackaging the 3 clean services. The explicit scan list is the price for keeping the bare `org.egov` root clean. |
| D1a | *(SUPERSEDED — no forced `api/`+`internal/` split.)* Each module stays in its **existing internal sub-structure** (e.g. `pgr.service`, `pgr.web`). Cross-module access discipline is documented and (later) checked via Modulith named-interfaces, not a mass file move now. | "Minimal changes": splitting 250+ files into api/internal is not a *required* change to get a working modulith. Encapsulation can be tightened incrementally once the single app boots. |
| D2 | **Module package map** (only what *must* move moves): `pgr`→`org.egov.pgr` *(unchanged)*; `idgen`→`org.egov.id` *(unchanged)*; `workflow`→`org.egov.wf` *(unchanged)*; `mdms-v2` `org.egov.infra.mdms.*`→`org.egov.mdmsv2.*`; `persister` `org.egov.infra.persist.*`→`org.egov.persist.*`; `localization` `org.egov.{config,web,util,domain,persistence}`→`org.egov.localization.*`. | `mdms` can't be named `org.egov.mdms` (the `mdms-client` jar already owns that package). `infra.*` and localization's bare generic packages aren't valid standalone modules, so they get one clean namespace each. The 3 already-clean services are left alone. |
| D3 | **Single `pom.xml`** = union of all module deps, pinned to **Spring Boot 3.2.2 / Java 17**. 3.4.5 services aligned down to 3.2.2. **Build with JDK 17** (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`) — the default `mvn` here runs JDK 25, on which Boot-3.2.2's Lombok 1.18.30 annotation processor fails (no getters/`@Slf4j` generated → whole repo won't compile; pre-existing, reproduced on the untouched `pgr-services` too). No pom change needed once JDK 17 is used. | One deployable ⇒ one classpath ⇒ one Spring Boot line. The JDK mismatch is an environment issue, not a transformation change, so we fix it the minimal way: build on the JDK the project already targets. |
| D4 | **One shared datasource** (one Postgres). Each module keeps its **own Flyway migration folder** `db/migration/<module>`, all run by the one app. | Single process ⇒ single transaction boundary. Per-module migration folders keep schema ownership legible and each module independently extractable. |
| D5 | **Sync** cross-module calls (pgr → idgen/workflow/mdms/localization) are rewritten from `RestTemplate` to **injecting the target module's Spring service bean** (in-process). **Async** flows (persister, notifications) move to **Spring Modulith application events** later. Each module's **HTTP controllers stay** so Postman/external callers keep working unchanged. | The point of the modulith: delete the network hop for in-process calls while preserving the external API. Keeping controllers = "call idgen from Postman without interference" (your requirement). |
| D6 | **`egov-user` + not-in-repo deps (HRMS, boundary, url-shortener)** keep `RestTemplate` over **port-forwarded** hosts. `egov-user` is explicitly **out of scope** for internalization (Spring Boot 1.5 / Java 8). | user = unportable without a full 1.5→3.2 / javax→jakarta migration; others = no source. Genuinely remote dependencies. |
| D7 | **Single `application.properties`** at `application/src/main/resources`. Existing per-service keys are merged; where two services used the same key for different things, the module's keys are prefixed. Shared infra (datasource, kafka) declared once. | One process ⇒ one config source of truth. |
| D8 | **`git init` + commit after every edit**, linear history on the default branch. | The commit log itself is the change-tracking artifact (paired with this doc); each step is trivially `diff`-able against the microservice reference. |

---

## 5. Phased plan

- [ ] **Phase 0 — Foundation.** Create `application/` (seeded from `pgr-services`, the host).
      Single entry point `org.egov.Application` (`@SpringBootApplication @Modulithic`,
      explicit `scanBasePackages`); single pom with `spring-modulith` BOM + starters and the
      Lombok 1.18.38 fix; `ModularityTests` running `ApplicationModules.verify()`.
- [x] **Phase 1 — idgen.** Brought `org.egov.id` into `application` (no repackage); dropped its
      `main()`; merged pom deps + properties + `db/migration/idgen`; rewrote PGR
      `IdGenRepository` from HTTP to a direct `IdGenerationService` bean call. ✅ compiles + verify().
- [x] **Phase 2 — mdms-v2.** `org.egov.infra.mdms.*`→`org.egov.mdmsv2.*`; merge; rewrite PGR `MDMSUtils`. ✅ compiles + verify().
- [ ] **Phase 3 — localization.** `org.egov.{config,web,...}`→`org.egov.localization.*`; rewrite PGR `NotificationUtil`.
- [ ] **Phase 4 — workflow.** `org.egov.wf` already clean; merge; rewrite PGR `WorkflowService`.
- [ ] **Phase 5 — persister.** `org.egov.infra.persist.*`→`org.egov.persist.*`; wire kafka in the one app.
- [ ] **Phase 6 — events.** Convert async paths (persister, notifications) to Spring Modulith events.
- [ ] **egov-user — OUT OF SCOPE.** Stays an HTTP/port-forward dependency (Spring Boot 1.5 / Java 8).

Each phase = its own change-log section in §6 and stays independently buildable.

---

## 6. Change log

> Append one subsection per concrete change. Template:
> **[Phase N.x] <title>** — *What:* … *Why (first principles):* … *Monolith vs Modulith:* …

### [Phase -1] Plan revised to final minimal-change structure
- **What:** Dropped the earlier `pgr-modulith/` experiment and the `org.egov.platform` + full
  `api/internal` split. Agreed final target: a new `application/` project = single pom + single
  `application.properties` + single db-migration, with each service as one module sub-package of
  `org.egov`. The 6 source folders stay as the microservice reference; `egov-user` is out of scope.
- **Why (first principles):** "Minimal changes" means *only* the moves that are strictly required
  to make one app boot with separated modules. `pgr`/`id`/`wf` already have clean distinct
  packages, so they don't move; only the collision/generic packages (mdms, persister, localization)
  get one clean namespace each. Everything else is left byte-for-byte.
- **Microservice vs Modulith:** Microservices enforce module boundaries with the network (one
  process each). The modulith keeps the boundaries (distinct module packages, in-process calls
  through service beans) but deletes the network, the N deployments, and the N configs.

### [Phase 0] Foundation — `application/` host + single entry point + Modulith wiring
- **What:**
  - Seeded `application/` from `pgr-services` (the host). `pgr` code stays at `org.egov.pgr`.
  - Replaced `org.egov.pgr.PGRApp` with a single root entry point `org.egov.Application`
    (`@SpringBootApplication @Modulithic`, explicit `scanBasePackages` listing module packages,
    same `@Import`/`ObjectMapper` bean as before).
  - Added `spring-modulith-bom` (1.1.3) + `spring-modulith-starter-core`/`-test` to the one pom.
  - Added `ModularityTests` running `ApplicationModules.verify()`, excluding the `org.egov.*`
    library packages so only our modules are modelled.
- **Result:** compiles on JDK 17; `verify()` passes; Modulith reports one module `Pgr`
  (`org.egov.pgr`). The skeleton is ready to absorb modules one per phase.
- **Why (first principles):** Establish the single deployable + the boundary-checker *before*
  moving any service in, so every later phase is validated by `verify()` the moment it lands.
- **Monolith vs Modulith:** A plain monolith would just have one `main()` and no boundary check —
  nothing stops package tangling. `@Modulithic` + `verify()` is exactly the discipline that keeps
  this from decaying into a big ball of mud as services are absorbed.

### [Phase 1] idgen — first module absorbed and called in-process
- **What:**
  - Copied idgen sources `org.egov.id.*` into `application` unchanged (already a distinct package,
    so no repackage). Left `PtIdGenerationApplication` behind (single `main()` rule).
  - Copied idgen's `db/migration/*` to `db/migration/idgen` (D4 — per-module ownership).
  - Added `json-path` + `json-smart` to the one pom (idgen's `MdmsService` needs `net.minidev`).
  - Merged idgen's functional properties into the single `application.properties` (NOT its
    datasource / server.port / context-path — the modulith has one of each).
  - Declared `org.egov.id` a module (`package-info`), and published `org.egov.id.service` and
    `org.egov.id.model` as `@NamedInterface`s — the Modulith 1.1.x way to expose specific
    sub-packages across a boundary (no `type=OPEN` in 1.1.x).
  - **Rewrote `pgr.IdGenRepository`**: removed `RestTemplate`/host config; injected
    `IdGenerationService`; build idgen's request, call `generateIdResponse(...)` directly, and
    map idgen's response back to PGR's own DTO with `ObjectMapper.convertValue` so `getId(...)`'s
    signature — and `EnrichmentService` — are untouched.
  - Activated `org.egov.id` in `Application`'s `scanBasePackages`.
- **Result:** compiles on JDK 17; `verify()` passes with modules `Pgr` + `idgen`; the only
  cross-module edge is `pgr → idgen` through idgen's named interfaces.
- **Code — the call boundary (`pgr/repository/IdGenRepository.java`):**

  ```java
  // ── BEFORE (microservice): HTTP round-trip to the idgen service ───────────────
  private RestTemplate restTemplate;
  private PGRConfiguration config;                       // holds egov.idgen.host / .path

  public IdGenerationResponse getId(RequestInfo ri, String tenantId,
                                    String name, String format, int count) {
      IdGenerationRequest req = IdGenerationRequest.builder()...build();   // PGR's own DTO
      // serialize → TCP → idgen process → deserialize → ... → back
      return restTemplate.postForObject(
                 config.getIdGenHost() + config.getIdGenPath(), req,
                 IdGenerationResponse.class);            // PGR's own DTO
  }
  ```
  ```java
  // ── AFTER (modulith): direct in-process call through idgen's @NamedInterface ──
  private final IdGenerationService idGenerationService; // org.egov.id.service (published)
  private final ObjectMapper mapper;

  public IdGenerationResponse getId(RequestInfo ri, String tenantId,
                                    String name, String format, int count) {
      org.egov.id.model.IdGenerationRequest req = new org.egov.id.model.IdGenerationRequest();
      req.setRequestInfo(mapper.convertValue(ri, org.egov.id.model.RequestInfo.class)); // map at the seam
      req.setIdRequests(reqList);
      org.egov.id.model.IdGenerationResponse resp = idGenerationService.generateIdResponse(req);
      return mapper.convertValue(resp, IdGenerationResponse.class);   // back to PGR's DTO
  }
  ```
- **Code — exposing only what crosses the boundary (`id/service/package-info.java`):**

  ```java
  @org.springframework.modulith.NamedInterface("service")   // IdGenerationService becomes public API
  package org.egov.id.service;                               // everything else in idgen stays internal
  ```
- **Why (first principles):** A call between two bounded contexts is, fundamentally, "give me ids
  for these formats." Microservices answer that with an HTTP round-trip (serialize → TCP →
  deserialize → handle → reverse) purely because the contexts run in different processes. Once
  they share a process, the honest implementation of the same call is a method invocation. We
  delete the accidental complexity (network, JSON, timeouts, `ServiceCallException`) and keep the
  essential one (the contract), translating DTOs at exactly one boundary.
- **Microservice vs Monolith vs Modulith:**
  - *Microservice:* `RestTemplate.postForObject(host+path, …)` — network boundary, independent
    deploy, but latency + partial-failure handling on every id fetch.
  - *Monolith:* `EnrichmentService` would just `new` or autowire idgen's service and reach into
    any of its classes freely — fast, but no boundary; idgen could never be pulled back out.
  - *Modulith (this):* direct bean call **through a published interface only**; `verify()` fails
    the build if PGR touches idgen's internals. Fast like a monolith, bounded like a microservice,
    and the named interface is the exact seam to re-extract idgen later.

### [Phase 2] mdms-v2 — repackaged, absorbed, called in-process
- **Files added / changed:**
  - `org/egov/mdmsv2/**` (48 files) — copied from `mdms-v2`'s `org.egov.infra.mdms.*`, repackaged
    to `org.egov.mdmsv2.*` via `sed 's/org.egov.infra.mdms/org.egov.mdmsv2/g'`. `MDMSApplication`
    (the `main()`) left behind.
  - `db/migration/mdmsv2/**` — mdms migrations (per-module ownership, D4).
  - `org/egov/mdmsv2/package-info.java` — module descriptor (displayName `mdms-v2`).
  - `org/egov/mdmsv2/service/package-info.java` — `@NamedInterface("service")`.
  - `org/egov/mdmsv2/model/package-info.java` — `@NamedInterface("model")` (added after a verify()
    violation, see below).
  - `pom.xml` — added `org.everit.json:org.everit.json.schema:1.5.1`, `org.json:json:20231013`,
    `commons-beanutils:1.11.0`, `io.swagger.core.v3:swagger-annotations:2.2.8`,
    `org.egov.services:services-common:2.0.0-SNAPSHOT`.
  - `application.properties` — mdms kafka topics + `mdms.default.offset/limit`.
  - `Application.java` — added `org.egov.mdmsv2` to `scanBasePackages`.
  - `pgr/util/MDMSUtils.java` — HTTP → in-process (below).
- **Build errors hit & fixed (in order):**
  1. `package io.swagger.v3.oas.annotations.media does not exist` and `package org.everit.json.schema
     does not exist` → mdms models use Swagger v3 annotations and everit JSON-schema validation that
     PGR's classpath lacked. **Fix:** add the five deps above (all present in mdms-v2's own pom).
  2. `incompatible types: org.egov.mdms.model.MdmsCriteriaReq cannot be converted to
     org.egov.mdmsv2.model.MdmsCriteriaReq` → I had assumed PGR and mdms shared the mdms-client DTO;
     in fact **mdms-v2 ships its own copy** of the contract (`MdmsCriteriaReq`, `MdmsResponse`, …).
     **Fix:** `ObjectMapper.convertValue` PGR's mdms-client `MdmsCriteriaReq` into the module's type
     at the call boundary (same JSON shape).
  3. `duplicate import` of `MdmsResponse` → I had both `org.egov.mdms.model.MdmsResponse` and
     `org.egov.mdmsv2.model.MdmsResponse`. **Fix:** keep only the module's (`mdmsv2.model`).
  4. **verify() violation** (the boundary catching me): `Module 'pgr' depends on non-exposed type
     org.egov.mdmsv2.model.MdmsResponse$MdmsResponseBuilder within module 'mdmsv2'`. I had published
     only `mdmsv2.service`, but PGR also touches `mdmsv2.model`. **Fix:** add
     `@NamedInterface("model")` to `org.egov.mdmsv2.model`. This is exactly the value of a modulith —
     the build refuses an un-declared cross-module dependency.
- **Code — the call boundary (`pgr/util/MDMSUtils.java`):**

  ```java
  // ── BEFORE (microservice): generic HTTP POST to the mdms service ──────────────
  public Object mDMSCall(ServiceRequest request){
      MdmsCriteriaReq mdmsCriteriaReq = getMDMSRequest(ri, stateLevelTenant);   // mdms-client DTO
      return serviceRequestRepository.fetchResult(getMdmsSearchUrl(), mdmsCriteriaReq); // → host:8082
  }
  ```
  ```java
  // ── AFTER (modulith): in-process call into the mdmsv2 module ──────────────────
  public Object mDMSCall(ServiceRequest request){
      MdmsCriteriaReq mdmsCriteriaReq = getMDMSRequest(ri, stateLevelTenant);   // mdms-client DTO
      org.egov.mdmsv2.model.MdmsCriteriaReq moduleReq =                          // translate at the seam
              mapper.convertValue(mdmsCriteriaReq, org.egov.mdmsv2.model.MdmsCriteriaReq.class);
      Map<String, Map<String, JSONArray>> masters = mdmsService.search(moduleReq);
      MdmsResponse resp = MdmsResponse.builder().mdmsRes(masters).build();       // mirror the controller
      return mapper.convertValue(resp, Map.class);                               // same shape as old HTTP
  }
  ```
- **Why (first principles):** PGR's need is "give me these masters for this tenant." The old call
  answered it with a generic JSON POST whose response PGR re-parsed with JsonPath. In-process we
  call the very method the mdms controller calls and rebuild the identical `MdmsResponse` envelope,
  so every downstream `$.MdmsRes...` read is unchanged. The two DTO copies are a microservice
  artifact (each service vendored the contract); we bridge them once, at the boundary.
- **Microservice vs Monolith vs Modulith:**
  - *Microservice:* network POST; mdms owns its data + deploy; PGR couples only to JSON.
  - *Monolith:* PGR would call repositories/enrichers directly — no boundary, mdms un-extractable.
  - *Modulith (this):* PGR reaches mdms only through `service` + `model` named interfaces;
    `verify()` proved it (it rejected the un-exposed builder until we published `model`).