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

| # | Decision | Rationale (first principles) |
|---|---|---|
| D0 | **Keep the 7 original service folders untouched as a reference. Build the modulith in a new sibling folder `pgr-modulith/`** (seeded by copying `pgr-services`). | We want to *compare* the two architectures side-by-side, not lose the microservice version. Non-destructive: the reference keeps compiling/running independently; every modulith change is isolated to `pgr-modulith/`. The "host app = pgr-services" decision is unchanged — `pgr-modulith` simply *is* that host, grown to absorb the other modules. |
| D1 | **App root package = `org.egov.platform`**, single `@SpringBootApplication @Modulithic` main class (`PlatformApplication`) lives there. Each former service becomes ONE module package: `org.egov.platform.{pgr,idgen,mdms,localization,workflow,persister,user}`. | Modulith modules are the direct sub-packages of the root. Rooting at `org.egov.platform` (not `org.egov`) keeps the module scan off the library jars that live under `org.egov.*` (`org.egov.common`, `org.egov.tracer`, `mdms-client`). `pgr` is "main" *functionally*, but the package root is the neutral `platform`. |
| D1a | **Inside each module: `api/` (public — interfaces, DTOs, published events) and `internal/` (impl, repos, entities — package-private).** Cross-module access goes only through `api/`. | This is what makes the boundary *real*: Modulith treats non-`api`/non-named-interface types as internal and fails the build if another module imports them. Encapsulation is enforced by the compiler+verify(), not convention. |
| D2 | **Repackage** every service under its module package, preserving logic/entities (`org.egov.id`→`org.egov.platform.idgen.*`, mdms `org.egov.infra`→`org.egov.platform.mdms.*`, persister `org.egov.infra`→`org.egov.platform.persister.*`, localization `org.egov.{config,web,...}`→`org.egov.platform.localization.*`, `org.egov.pgr`→`org.egov.platform.pgr.*`, `org.egov.wf`→`org.egov.platform.workflow.*`). | Removes the `org.egov.infra` clash between mdms & persister and gives each module a single clean boundary Modulith can verify. |
| D3 | **Single `pom.xml`** = union of all modules' dependencies, pinned to **Spring Boot 3.2.2 / Java 17** (PGR's version). 3.4.5 services are aligned to 3.2.2. | One deployable ⇒ one classpath. Version skew is impossible inside one JVM; we must converge on one Spring Boot line. 3.4.5→3.2.2 is a minor step (low risk) vs. 1.5→3.2 for user (high risk, hence D6). |
| D4 | **One shared datasource**, one Postgres. Each module keeps its **own Flyway migration folder** (`db/migration/<module>`). | A single process should have a single transaction boundary; multiple `DataSource` beans re-import the distributed-data problem we are trying to delete. Per-module migration folders preserve schema ownership/clarity. |
| D5 | **Sync** cross-module calls (PGR → idgen/workflow/mdms/localization) are rewritten from `RestTemplate` to **injection of the target module's `api/` interface bean** (in-process). **Async** flows (persister write path, notifications) use **Spring Modulith application events** (`@ApplicationModuleListener`). The modules' existing **HTTP controllers stay** so external callers (Postman) keep working. | The whole point of the modulith: replace network hops with in-process calls/events while keeping the external API intact. Sync→interface keeps the call-site obvious and transactional; async→event removes temporal coupling without a broker per-hop. |
| D6 | **`egov-user` and the not-in-repo deps (HRMS, boundary, url-shortener)** keep using `RestTemplate` against **port-forwarded** hosts. | user = Spring Boot 1.5/Java 8 version-skew risk (B), migrated last; others = no source available. Both are genuine "remote" dependencies for now. |
| D7 | **Single `application.yml`** at `src/main/resources`; per-module settings namespaced `platform.<module>.*`; shared infra (datasource, kafka, event-publication-registry) declared once. Profiles via `application-<profile>.yml`. | One process ⇒ one config source of truth. Prefix-namespacing keeps module settings from colliding while staying in one file. |
| D8 | **`git init` + commit after every edit**, linear history on the default branch. | The user wants the commit log itself to be the change-tracking artifact (paired with this doc). A linear history on one branch makes each transformation step trivially `diff`-able against the microservice reference. |

---

## 5. Phased plan

- [ ] **Phase 0 — Foundation.** Single entry point at `org.egov`; add `spring-modulith`
      dependencies + BOM to PGR's pom; package-info module descriptors; a
      `ModularityTests` running `ApplicationModules.verify()`.
- [ ] **Phase 1 — idgen (pilot).** Repackage `org.egov.id`→`org.egov.idgen`; merge pom deps;
      merge properties; point shared datasource; rewrite PGR `IdGenRepository` to a direct call.
- [ ] **Phase 2 — mdms-v2.** `org.egov.infra`→`org.egov.mdms`; rewrite PGR `MDMSUtils`.
- [ ] **Phase 3 — localization.** `org.egov.*`→`org.egov.localization.*`; rewrite PGR `NotificationUtil`.
- [ ] **Phase 4 — workflow.** `org.egov.wf` already clean; rewrite PGR `WorkflowService`.
- [ ] **Phase 5 — persister.** `org.egov.infra`→`org.egov.persister`; wire kafka in same app.
- [ ] **Phase 6 — events.** Convert async paths to Spring Modulith events (D7).
- [ ] **Phase 7 — egov-user.** Spring Boot 1.5→3.2 / javax→jakarta migration, then internalize.

Each phase = its own change-log section in §6 and stays independently buildable.

---

## 6. Change log

> Append one subsection per concrete change. Template:
> **[Phase N.x] <title>** — *What:* … *Why (first principles):* … *Monolith vs Modulith:* …

### [Phase 0.0] Create `pgr-modulith/` reference-preserving workspace
- **What:** Copied `pgr-services/` → `pgr-modulith/` (minus `target/`). The 7 original folders
  are now the *microservice reference*; `pgr-modulith/` is the *single-app modulith* we grow.
- **Why (first principles):** A refactor of this size needs a reversible baseline. Keeping the
  microservice version bootable next to the modulith lets us A/B the two structures and verify
  behavior parity (same endpoints, same responses) rather than trusting the rewrite blindly.
- **Microservice vs Modulith:** In microservices the "comparison" would be across repos; here
  both live in one tree so a single `diff` shows exactly what collapsing axis-2 (distribution)
  costs and saves.