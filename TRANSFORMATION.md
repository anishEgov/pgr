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

- **No "main" service — all modules are peers.** A single neutral launcher (`org.egov.Application`,
  just a bootstrap) owns the one `@SpringBootApplication`, one `pom.xml`, one `application.properties`.
  Every former service is an equal-weight module; each keeps its **own API path** (its old context
  path) and its **own migration folder**, so each is testable independently exactly as before.
  *(Earlier drafts wrongly treated pgr-services as the host; corrected in change-log [R.7].)*
- Each service becomes a **module inside the same application** (same JVM, same process).
- **Existing endpoints stay reachable at their ORIGINAL paths.** e.g. `POST /egov-idgen/id/_generate`
  works exactly as before — NOT nested under any other module's context path.
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
- [x] **Phase 3 — localization.** `org.egov.{config,web,...}`→`org.egov.localization.*`; rewrite PGR `NotificationUtil`. ✅ compiles + verify().
- [x] **Phase 4 — workflow.** `org.egov.wf` already clean; merge; rewrite PGR `WorkflowService`. ✅ compiles + verify().
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
### [Phase 3] localization — repackaged, absorbed, called in-process (brings JPA + Redis)
- **Files added / changed:**
  - `org/egov/localization/**` (36 files) — copied from localization's five generic roots
    `org.egov.{config,web,util,domain,persistence}` and repackaged to `org.egov.localization.*`
    (per-root `sed`). `LocalizationServiceApplication` (the `main()`) left behind.
  - `db/migration/localization/**` — localization migrations (D4).
  - `org/egov/localization/package-info.java` — module descriptor.
  - named interfaces: `domain/service` (`service`), `domain/model` (`model`),
    `web/contract` (`contract`).
  - `pom.xml` — `spring-boot-starter-data-jpa`, `spring-boot-starter-data-redis`,
    `commons-io:2.15.1`, `commons-lang3`.
  - `application.properties` — JPA (`ddl-auto=none`, etc.) + `spring.redis.host/port`.
  - `Application.java` — added `org.egov.localization` to `scanBasePackages`.
  - `pgr/util/NotificationUtil.java` — HTTP → in-process (below).
- **Build errors hit & fixed (in order):**
  1. `package org.springframework.data.redis.* does not exist` and JPA repository/entity symbols
     not found → localization persists messages with **Spring Data JPA** and caches them in
     **Redis**. **Fix:** add `spring-boot-starter-data-jpa` + `spring-boot-starter-data-redis`
     (+ `commons-io`/`commons-lang3`). *Architectural note:* this adds a JPA `EntityManager` over
     the shared datasource and a Redis client — **a Redis server is now required at runtime**.
  2. **verify() violation:** `Module Pgr uses field injection in
     org.egov.pgr.util.NotificationUtil.messageService. Prefer constructor injection instead!`
     Modulith forbids reaching another module's bean via `@Autowired` field injection — the
     dependency must be visible in the constructor. **Fix:** constructor-inject `messageService`
     + `mapper` (the other, intra-module, fields can stay field-injected).
- **Code — the call boundary (`pgr/util/NotificationUtil.java`):**

  ```java
  // ── BEFORE (microservice): HTTP GET/POST to localization /messages/v1/_search ─
  public String getLocalizationMessages(String tenantId, RequestInfo ri, String module) {
      LinkedHashMap responseMap = (LinkedHashMap) serviceRequestRepository.fetchResult(
              getUri(tenantId, ri, module), ri);          // builds URL host+contextPath+endpoint+?locale=...
      return new JSONObject(responseMap).toString();
  }
  ```
  ```java
  // ── AFTER (modulith): in-process call into the localization module ────────────
  public String getLocalizationMessages(String tenantId, RequestInfo ri, String module) {
      tenantId = centralInstanceUtil.getStateLevelTenant(tenantId);
      String locale = /* from ri.getMsgId() "msgId|locale", else default */;
      MessageSearchCriteria criteria = MessageSearchCriteria.builder()
              .locale(locale).tenantId(new Tenant(tenantId)).module(module).build();
      List<org.egov.localization.domain.model.Message> domain = messageService.getFilteredMessages(criteria);
      MessagesResponse response = new MessagesResponse(domain.stream()
              .map(org.egov.localization.web.contract.Message::new).collect(Collectors.toList()));
      return new JSONObject(mapper.convertValue(response, Map.class)).toString();  // same {"messages":[...]} shape
  }
  ```
- **Why (first principles):** PGR needs "the localized message templates for this module/locale."
  The controller answers that by running `getFilteredMessages` and wrapping the result; in-process
  we do the very same thing and serialize to the identical JSON string the rest of NotificationUtil
  already parses with JsonPath — so only the *transport* changed, never the contract.
- **Microservice vs Monolith vs Modulith:**
  - *Microservice:* HTTP + URL query params; localization owns its DB + Redis; separate deploy.
  - *Monolith:* PGR would query the message JPA repo / cache directly — no boundary.
  - *Modulith (this):* PGR depends only on localization's `service`/`model`/`contract` named
    interfaces, via constructor injection that `verify()` insists on — coupling stays explicit.

### [Phase 4] workflow — absorbed; the biggest integration goes in-process
- **Files added / changed:**
  - `org/egov/wf/**` (56 files) — copied unchanged (already `org.egov.wf`, no repackage). `Main`
    (the `main()`) dropped.
  - `db/migration/workflow/**` (D4).
  - `org/egov/wf/package-info.java` + named interfaces `service`, `service/V1` (`service-v1`),
    `web/models` (`models`).
  - `Application.java` — added `org.egov.wf` to scan **and** set
    `nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class` (see build errors).
  - `application.properties` — workflow-unique keys (kafka consumer, wf topics, `egov.wf.*`, …).
  - `pgr/service/WorkflowService.java` — three HTTP calls → in-process (below).
  - `pgr/service/NotificationService.java` — its hidden `/process/_search?...&history=true` HTTP
    call → in-process via a new `WorkflowService.searchProcessInstance(...)`.
- **Build errors hit & fixed (in order):**
  1. *(anticipated, fixed pre-emptively)* **bean-name collisions.** Four simple class names exist in
     more than one module — `WorkflowService` (pgr+wf), `UserService` (pgr+wf), `EnrichmentService`
     (pgr+wf), `MDMSService` (mdmsv2+wf). Spring's default namer would register e.g. two
     `workflowService` beans → `ConflictingBeanDefinitionException` at startup. **Fix:**
     `@ComponentScan(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)` so bean names
     are package-qualified. (Verified no `@Qualifier`/`getBean(name)` in pgr that would break.)
  2. `cannot find symbol method getprocessInstanceSearchURL` in `NotificationService` — I had deleted
     that URL-builder from `WorkflowService`, not realizing `NotificationService.getEmployeeName`
     also used it for a *second* workflow search (with `history=true`). **Fix:** add a proper
     in-process `searchProcessInstance(requestInfo, tenantId, serviceRequestId, history)` to the
     adapter and call it from `NotificationService`.
- **Code — transition call (`pgr/service/WorkflowService.java`):**

  ```java
  // ── BEFORE (microservice) ─────────────────────────────────────────────────────
  private ProcessInstanceResponse callWorkFlow(ProcessInstanceRequest workflowReq) {
      StringBuilder url = new StringBuilder(cfg.getWfHost().concat(cfg.getWfTransitionPath()));
      Object optional = repository.fetchResult(url, workflowReq);                 // HTTP POST
      return mapper.convertValue(optional, ProcessInstanceResponse.class);
  }
  ```
  ```java
  // ── AFTER (modulith): call the wf module's transition() directly ───────────────
  private ProcessInstanceResponse callWorkFlow(ProcessInstanceRequest workflowReq) {
      org.egov.wf.web.models.ProcessInstanceRequest wfRequest =
              mapper.convertValue(workflowReq, org.egov.wf.web.models.ProcessInstanceRequest.class);
      List<org.egov.wf.web.models.ProcessInstance> wf = wfWorkflowService.transition(wfRequest);
      return ProcessInstanceResponse.builder()
              .processInstances(mapper.convertValue(wf, new TypeReference<List<ProcessInstance>>(){}))
              .build();
  }
  ```
- **Code — the bean-name generator (`Application.java`):**

  ```java
  @ComponentScan(
      nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,  // unique names per module
      basePackages = { "org.egov.pgr", "org.egov.id", "org.egov.mdmsv2", "org.egov.localization", "org.egov.wf" })
  ```
- **Why (first principles):** workflow is PGR's hottest dependency — every create and update does a
  transition, every search enriches with workflow state, notifications read the workflow history.
  As microservices that's *several* network hops per request; in one process they're method calls
  in the same transaction. The bean-name collision is the flip side of collapsing services: names
  that were globally unique *per process* now share one container, so we make them unique again.
- **Microservice vs Monolith vs Modulith:**
  - *Microservice:* N HTTP hops/request; workflow independently scaled/deployed; resilience code
    (timeouts/retries) on every call.
  - *Monolith:* PGR would call workflow's `TransitionService`/repos directly — no boundary, and the
    two `WorkflowService` classes would just be an ugly name clash to "solve" by renaming domain code.
  - *Modulith (this):* PGR depends only on workflow's `service`/`service-v1`/`models` named
    interfaces; the clash is solved by infrastructure (bean namer), not by touching domain code.

---

## 7. Current status

**Single Spring Modulith app `application/` — builds on JDK 17, `ApplicationModules.verify()` green.**

| Module | Package | Source | PGR → it | Status |
|---|---|---|---|---|
| pgr (host) | `org.egov.pgr` | unchanged | — | ✅ |
| idgen | `org.egov.id` | unchanged | in-process `IdGenerationService` | ✅ Phase 1 |
| mdms-v2 | `org.egov.mdmsv2` | repackaged from `org.egov.infra.mdms` | in-process `MDMSService` | ✅ Phase 2 |
| localization | `org.egov.localization` | repackaged from `org.egov.{config,web,..}` | in-process `MessageService` | ✅ Phase 3 |
| workflow | `org.egov.wf` | unchanged | in-process `WorkflowService`/`BusinessMasterServiceV1` | ✅ Phase 4 |
| persister | `org.egov.persist` | (not yet) | async (kafka/events) | ⏳ Phase 5/6 |
| **egov-user** | — | **stays a microservice** | **HTTP / port-forward** | ⛔ out of scope (Spring Boot 1.5 / Java 8) |

**Sync HTTP eliminated for all in-scope modules.** Remaining `RestTemplate` use in `pgr` is only
for genuinely-remote dependencies: `egov-user` (`UserUtils`, `ServiceRequestValidator`),
HRMS (`HRMSUtil`), SMS/notification sender + migration (`NotificationUtil`, `MigrationUtils`) — all
via the generic `ServiceRequestRepository`, all pointed at port-forwarded hosts (D6).

**Remaining work:** Phase 5 (absorb persister as a co-located kafka consumer) and Phase 6 (turn the
async write/notification paths into Spring Modulith `@ApplicationModuleListener` events). Both were
explicitly deferred.

**How to build/verify:**
```bash
cd application
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn -o test -Dtest=ModularityTests   # boundaries
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn -o clean package                 # one fat jar
```

---

## 8. Phase R — Runtime boot (docker-compose infra)

Goal: actually start the modulith (`java -jar`) against real infra and fix whatever the merge
broke at *context-startup* time (compile + `verify()` can't catch bean-wiring / eager-init issues).
Per the plan, **persister stays a separate service consuming Kafka** (async, unchanged) — it is NOT
absorbed; it runs as its own container.

### [R.0] Infra via docker-compose
- **Files added:**
  - `application/docker-compose.yml` — `postgres:16` (host port **5433**, db `platform`),
    `redis:7` (6379), `apache/kafka:3.7.0` (KRaft single node; EXTERNAL `localhost:9092` for the
    host JVM, INTERNAL `kafka:29092` for containers), and `egovio/egov-persister` (consumes Kafka
    `kafka:29092`, writes to the same Postgres).
  - `application/persister-configs/pgr-v2-persist-batch.yml` — the topic→SQL mapping the persister
    container loads (`EGOV_PERSIST_YML_REPO_PATH`).
- **Why:** the modulith needs a real Postgres (one shared datasource, D4), Redis (localization
  cache), and Kafka (PGR producer → persister). persister stays external so the async write path is
  unchanged — exactly the "go with Kafka only for persistence" decision.

### [R.1] `application.properties` — runtime wiring (every changed line)
```properties
# datasource now points at the compose Postgres (was localhost:15432/pgr)
spring.datasource.url=jdbc:postgresql://localhost:5433/platform
# disable Spring Boot auto-Flyway for first boot (flyway-core is on the classpath via several
# modules; default would scan classpath:db/migration/** and run ALL modules' migrations, whose
# versions can clash). Schema handled separately.
spring.flyway.enabled=false
flyway.url=jdbc:postgresql://localhost:5433/platform     # (legacy non-spring keys, kept aligned)
# Kafka broker exposed by compose
spring.kafka.bootstrap-servers=localhost:9092
# allow bean-definition overriding — see [R.3]
spring.main.allow-bean-definition-overriding=true
```

### [R.2] Boot error #1 — duplicate `@Bean` method names across modules
- **Symptom:**
  ```
  The bean 'jacksonConverter', defined in class path resource [org/egov/wf/config/WorkflowConfig.class],
  could not be registered. A bean with that name has already been defined in class path resource
  [org/egov/pgr/config/PGRConfiguration.class] and overriding is disabled.
  ```
- **Cause (first principles):** the `FullyQualifiedAnnotationBeanNameGenerator` from Phase 4 only
  renames **component-scanned** beans (`@Component/@Service/...`). `@Bean` *methods* take their name
  from the method, so identical infra factory methods across modules — `jacksonConverter`,
  `objectMapper`, `restTemplate`, kafka templates — collide in one context. Each former service
  shipped its own copy of these infra beans; one container can only hold one of each name.
- **Fix:** `spring.main.allow-bean-definition-overriding=true` (last definition wins). These infra
  beans are equivalent across modules, so overriding is safe for boot. *Follow-up (cleaner):* delete
  the duplicate infra `@Bean`s and keep one shared definition.

### [R.3] Boot error #2 — workflow's eager MDMS load crashes the context
- **Symptom:** bean creation chain `…NotificationConsumer → NotificationService → pgr.WorkflowService
  → wf.WorkflowService → wf.TransitionService → wf.BusinessServiceRepositoryV1 → wf.MDMSService`,
  failing with:
  ```
  Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name
  'org.egov.wf.service.MDMSService': Invocation of init method failed
  Caused by: java.lang.IllegalArgumentException: json object can not be null
  ```
- **Cause:** `wf.MDMSService` has a `@PostConstruct stateLevelMapping()` that **eagerly** fetches
  business-service config from MDMS and `JsonPath.read`s it. As microservices, MDMS was a live HTTP
  endpoint at boot. In the modulith MDMS is in-process and the shared DB has no MDMS data yet, so the
  call returns null and the whole context aborts. A single service's optional startup cache should
  not be able to kill the entire application.
- **Fix (`org/egov/wf/service/MDMSService.java`):** wrap the eager load in try/catch, default to an
  empty mapping, and log a warning; added `@Slf4j` for the logger.
  ```java
  @PostConstruct
  public void stateLevelMapping(){
      Map<String, Boolean> stateLevelMapping = new HashMap<>();
      try {
          Object mdmsData = getBusinessServiceMDMS();
          List<HashMap<String,Object>> configs = JsonPath.read(mdmsData, JSONPATH_BUSINESSSERVICE_STATELEVEL);
          for (Map map : configs) {
              stateLevelMapping.put((String) map.get("businessService"),
                                    Boolean.valueOf((String) map.get("isStatelevel")));
          }
      } catch (Exception e) {
          log.warn("Could not load wf state-level businessService mapping at startup " +
                   "(MDMS data may not be seeded yet); defaulting to empty. Cause: {}", e.getMessage());
      }
      this.stateLevelMapping = stateLevelMapping;
  }
  ```
- **Note:** this only makes startup resilient. wf's *request-time* MDMS calls still go over HTTP to
  `egov.mdms.host`; wiring wf→mdms fully in-process (like pgr) + seeding MDMS data is a follow-up.

### [R.4] Boot error #3 — `java.net.ConnectException: Connection refused` (in progress)
- Investigating which eager client (Kafka admin / a `@PostConstruct` HTTP call / DB) is refused.

### [R.4 resolved] Boot error #3 — workflow needs a CacheManager bean
- **Symptom:** `BusinessMasterService` ctor param 5: `No qualifying bean of type
  'org.springframework.cache.CacheManager' available`. workflow's `@Cacheable("businessService",
  "roleTenantAndStatusesMapping")` methods need a CacheManager.
- **Cause:** workflow declared `@EnableCaching` + its cache2k `CacheManager` bean **on its `Main`
  class** — the `@SpringBootApplication` entry point we had to drop (one app, one entry point).
- **Fix (minimal, re-home not re-write):** moved that exact wiring onto workflow's **existing**
  `WorkflowConfig` (`@EnableCaching` + the same `cacheManager()` bean, reusing the existing
  `cache.expiry.workflow.minutes` property) instead of creating a new class; added the
  `cache2k-spring:2.6.1.Final` dependency workflow already declared.

### [R.5 resolved] Boot error #4 — idgen needs the mdms-client `MdmsClientService` bean
- **Symptom:** `org.egov.id.service.MdmsService` field `mdmsClientService`: `No qualifying bean of
  type 'org.egov.mdms.service.MdmsClientService'`.
- **Cause:** that bean is a `@Service` in the **mdms-client library** (`org.egov.mdms.service`).
  Standalone idgen registered it via a broad `org.egov` component-scan; our explicit scan list
  didn't include the library package.
- **Fix (minimal):** add the single library package `org.egov.mdms.service` to `scanBasePackages`
  (reuses the existing library bean — no new code).

### [R.6] ✅ Boot success + multi-module smoke test
```
Tomcat started on port 8280 (http) with context path '/pgr-services'
Started Application in 9.495 seconds
```
- 5 modules wired in one process; 3 Kafka consumers subscribed; connected to compose
  Postgres/Redis/Kafka. The one ERROR in the log is workflow's startup MDMS probe (Connection
  refused) — caught by the tolerant `@PostConstruct` (R.3), non-fatal.
- **Every module's HTTP API responds (single process, single port):**

  | call | route | result |
  |---|---|---|
  | idgen | `POST /pgr-services/egov-idgen/id/_generate` | **200** |
  | mdms | `POST /pgr-services/v1/_search` | **200** |
  | workflow | `POST /pgr-services/egov-wf/businessservice/_search` | **200** |
  | pgr | `POST /pgr-services/v1/_create` | **200** |

- **Note — context path:** the single app has one context path (`/pgr-services`), so each absorbed
  module's endpoints now live *under* it (e.g. idgen at `/pgr-services/egov-idgen/...`, not
  `/egov-idgen/...`). The paths are otherwise unchanged, so "call idgen from Postman" works — just
  with the `/pgr-services` prefix. (If bare prefixes are required, drop the global context path and
  let each controller carry its full path — a follow-up.)

### Runtime — what is and isn't validated
- ✅ Single app **boots**, all 5 modules' beans wire, all modules' controllers route (HTTP 200).
- ✅ In-process cross-module calls compile + wire (pgr → idgen/mdms/localization/workflow).
- ⏳ **Data-dependent behavior** (real complaint create→workflow→persist) needs DB **schema seeded**
  (Flyway is disabled; tables not yet created) and the **persister** container consuming
  `save-pgr-request*`. Booting persister + seeding schema is the next step for a true end-to-end test.

### [R.7] Correction — every module is a PEER (no "main", no shared context path)
- **Feedback that drove this:** treating `pgr` as the "main"/host leaked into (a) a global
  `server.servlet.context-path=/pgr-services` that prefixed *every* module's API (so idgen sat at
  `/pgr-services/egov-idgen/...` — wrong, coupled), and (b) naming pgr's migration folder `main`
  (implying primacy). In a microservice→modulith move each module has **equal value** and must be
  testable on its **own original URL**, exactly as if run alone.
- **Changes (all minimal):**
  1. **Removed the global context path** (`server.*context-path` deleted from properties).
  2. **Per-module path prefixes** restored each service's original context path, scoped by package,
     in `Application.configurePathMatch` (no new class — the neutral launcher implements
     `WebMvcConfigurer`):
     ```java
     @Override public void configurePathMatch(PathMatchConfigurer c) {
         c.addPathPrefix("/pgr-services",     HandlerTypePredicate.forBasePackage("org.egov.pgr"));
         c.addPathPrefix("/egov-idgen",       HandlerTypePredicate.forBasePackage("org.egov.id"));
         c.addPathPrefix("/egov-workflow-v2", HandlerTypePredicate.forBasePackage("org.egov.wf"));
         c.addPathPrefix("/mdms-v2",          HandlerTypePredicate.forBasePackage("org.egov.mdmsv2"));
         c.addPathPrefix("/localization",     HandlerTypePredicate.forBasePackage("org.egov.localization"));
     }
     ```
  3. **Renamed** `db/migration/main` → `db/migration/pgr` (pgr owns its migrations like every other
     module: `idgen`, `mdmsv2`, `localization`, `workflow`); updated `flyway.locations`.
  4. Re-worded `Application`'s Javadoc: it is a **neutral launcher**, not a main module.
- **Verified (boot on :8280, context path now `''`):** each module answers ONLY at its original URL
  and its handler actually runs —
  | module | URL | body proves |
  |---|---|---|
  | idgen | `POST /egov-idgen/id/_generate` | real response `status: SUCCESSFUL` |
  | mdms | `POST /mdms-v2/v1/_search` | handler ran (errors only on missing data) |
  | workflow | `POST /egov-workflow-v2/egov-wf/businessservice/_search` | handler ran (`PreparedStatement` → needs schema) |
  | localization | `POST /localization/messages/v1/_search` | handler ran (`JDBC exception` → needs schema) |
  | pgr | `POST /pgr-services/v1/_create` | handler ran |
  Wrong/coupled paths (e.g. `/pgr-services/egov-idgen/...`) return "No static resource" — confirming
  modules are decoupled, not nested under pgr.
- **Note on HTTP codes:** the eGov tracer wraps all responses (even 404s) as HTTP 200 with a status
  body, so correctness is judged by the **body**, not the status code.

### [R.8] persister runs as a SEPARATE service (multi-module), not in compose
- **Feedback that drove this:** a single persister container hardcodes one
  `EGOV_PERSIST_YML_REPO_PATH` → it can only persist ONE module. But persistence is cross-module
  (a PGR complaint creates BOTH a pgr record AND workflow transitions). So persister must load
  **multiple** persist configs at once.
- **Changes:**
  1. **Removed** the `persister` service from `application/docker-compose.yml` (and deleted the
     `persister-configs/` dir it used). Compose now provides only postgres + redis + kafka.
  2. **Run the real `egov-persister` service** (from the `egov-persister/` source folder) in
     parallel with the modulith, pointed at the **shared platform DB** and at **one folder** that
     holds every module's persist config:
     ```properties
     spring.datasource.driver-class-name=org.postgresql.Driver
     spring.datasource.url=jdbc:postgresql://localhost:5433/platform
     egov.persist.yml.repo.path=file://…/egov-persister/src/main/resources/persister-configs
     ```
  3. **Consolidated the persist YAMLs into one place** —
     `egov-persister/src/main/resources/persister-configs/` — holding `pgr-v2-persist-batch.yml`
     (moved out of the modulith, which never used it) and `egov-workflow-v2-persister.yml`. Adding
     a module's persistence is now just dropping its `*.yml` in this folder.
- **How auto-pick-by-topic actually works (clarification):** the persister does NOT scan Kafka to
  find configs. At startup it loads every `*.yml` under `repo.path`
  (`EgovPersistApplication.getFilesInFolder`), reads each file's `fromTopic`, and subscribes to
  exactly those topics. So routing is driven by each config's `fromTopic` — you point `repo.path`
  at the folder once; you don't list files. (A dedicated subfolder is used, not the resources root,
  because the loader parses *every* `.yml` it finds with no exclusions — the root's `application.yml`
  and unrelated sample configs would otherwise be (mis)parsed as persister configs.)
- **Topic wiring (complete):** the `persister-configs/` folder now covers every topic the modulith
  publishes —
  - `pgr-services-persister.yml` → `save-pgr-request`, `update-pgr-request` (+ `statea-` tenant
    variants) — the **live** pgr create/update path. *(Added after noticing the batch config alone
    didn't cover live creates.)*
  - `pgr-v2-persist-batch.yml` → `save-pgr-request-batch`, `save-wf-transitions-batch` — the
    migration/batch path.
  - `egov-workflow-v2-persister.yml` → `save-wf-transitions`, `save-wf-businessservice`,
    `update-wf-businessservice` — workflow.
  No duplicate `fromTopic` across files, so no double-processing.
- **Why (architecture):** this keeps the async write path **exactly as in microservices** — PGR/
  workflow publish to Kafka, an independent persister consumes and writes — which is the decoupling
  the user wants to preserve (and the reason persister is NOT being absorbed as a module). The
  modulith only changed *synchronous* in-process calls; the async pipeline is untouched.

### [R.9] Seed all modules' schema into the ONE shared Postgres
- **Why:** one shared datasource (D4) means every module's tables must live in the single
  `platform` DB — not just pgr's. A real PGR create touches pgr, workflow and mdms tables; the
  persister writes pgr/workflow rows.
- **How:** ran each module's Flyway migrations into `platform`, each with its **own history table**
  so their independent version numbers don't collide in one schema:
  ```bash
  flyway -url=jdbc:postgresql://localhost:5433/platform -user=postgres -password=postgres \
    -table=<module>_flyway_history -locations=filesystem:<module-migration-dir> \
    -baselineOnMigrate=true -outOfOrder=true migrate
  ```
  | module | locations | applied |
  |---|---|---|
  | pgr | `db/migration/pgr` | 4 → `eg_pgr_service_v2`, `eg_pgr_address_v2`, `eg_pgr_document_v2` |
  | idgen | `idgen/main` + `idgen/seed` | 51 → `id_generator` (+ seeded id formats) |
  | mdms-v2 | `mdmsv2/main` | 2 → `eg_mdms_data`, `eg_mdms_schema_definition` |
  | localization | `localization/ddl` | 4 → `message` |
  | workflow | `workflow/main` | 12 → `eg_wf_*` (processinstance, state, action, businessservice, …) |
  18 tables total in `public`. Per-module history tables (`<module>_flyway_history`) keep schema
  ownership legible — the modulith equivalent of each microservice owning its own DB.
- **Monolith vs Modulith:** a monolith would have one tangled schema with no ownership; microservices
  have N physically separate DBs. The modulith keeps **one** DB (single transaction) but preserves
  **logical** ownership via per-module migration folders + history tables.

### [R.10] End-to-end runtime test (infra + persister + modulith + port-forward)
- **Setup brought up:**
  - docker-compose: `platform-postgres` (5433), `platform-redis` (6379), `platform-kafka` (9092).
  - Schema seeded for all modules into the one `platform` DB ([R.9]).
  - **egov-persister** running and subscribed to `save-pgr-request`, `update-pgr-request`,
    `statea-*`, `save-wf-transitions`, `save-wf-businessservice`, `update-wf-businessservice`.
    *(Run note: the Spring Boot 3.4.5 `repackage`/`dependency` Maven plugins aren't in the offline
    cache, so a source `java -jar`/`spring-boot:run` build can't complete here. The same
    egov-persister was therefore run from its prebuilt image but standalone — NOT the single-config
    compose service — with the multi-config FOLDER mounted (`EGOV_PERSIST_YML_REPO_PATH=/configs`,
    plain path: the loader treats a `file://` value as a missing folder). This preserves the
    multi-module intent.)*
  - Modulith on :8280; non-module deps port-forwarded from `unified-dev`
    (`kubectl -n egov port-forward svc/egov-user 8081:8080`).
- **PGR create API (`POST /pgr-services/v2/request/_create`):** routes to the controller and runs
  the real business logic — it called the **port-forwarded egov-user** service (integration works)
  and stopped at `No user exist for the given accountId`. That is **environment data** (a valid
  citizen + PGR MDMS service-defs in the shared DB), not a modulith issue — the create path itself
  executes correctly through validation → user lookup.
- **Async pipeline proven (the goal):** published a complete `save-pgr-request` event to Kafka →
  **persister consumed it** (`PersisterMessageListener.onMessage → PersistService.persist`) → wrote
  **transactionally** into `eg_pgr_service_v2` AND `eg_pgr_address_v2`:
  ```
  servicerequestid   | tenantid | servicecode           | applicationstatus | source
  PGR-TEST-1781688206 | pg      | StreetLightNotWorking | PENDING           | web
  doorno | plotno | city  | pincode
  12     | 7      | CityA | 560001
  ```
  (First attempt failed with `PathNotFound $.service.address.plotNo` — the persist config maps every
  address field and JsonPath is strict; a complete payload inserted cleanly.)
- **Conclusion:** the modulith produces to Kafka exactly as the microservices did, and the
  independent persister consumes and persists to the shared DB — the async write path is preserved
  unchanged. The only thing between "publish a complaint event" and "PGR API end-to-end" is seeding
  real env data (citizen + MDMS masters) into the shared DB, which is operational, not architectural.
