# Spring Modulith for DIGIT PGR — Understanding & Findings

> A short deck of what Spring Modulith is, and what I learned folding the PGR
> microservices into one modular-monolith app.

---

## 1. The problem today

7 separate Spring Boot services talking over the network:

```text
Sync  : HTTP   (pgr → idgen / mdms / workflow / localization / user)
Async : Kafka  (pgr/workflow → egov-persister → DB)
```

Cost: network latency on every hop, 7 deployments, 7 config sets, and Spring Boot
versions ranging **1.5 → 3.4** in one product.

**Question:** can the related services live in one app *without* losing their boundaries?

---

## 2. The architecture spectrum

```text
Traditional Monolith   →   Spring Modulith   →   Microservices
```

### Traditional Monolith
- **Pros:** simple deployment, fast in-process calls, one transaction.
- **Cons:** weak/no boundaries, tight coupling, decays into a "big ball of mud".

### Microservices (where PGR is today)
- **Pros:** independent deploy, independent scaling, team autonomy.
- **Cons:** network latency, distributed transactions, retries/timeouts/circuit-breakers,
  Kafka overhead, service discovery, version skew, N deployments.

### Spring Modulith (the middle ground)
- **Pros:** single deployment + single JVM (monolith speed), **strong module boundaries**,
  **architecture verified at build time**.
- **Cons:** no independent scaling/deploy, bigger blast radius, forced version alignment.

**In one line:** microservice-style boundaries **without** the microservice network cost.

---

## 3. What is a "module"?

Not a Maven module, not an annotation, not a `@SpringBootApplication` per service —
just **package structure**. Every direct sub-package of the root is one module:

```text
org.egov                ← one app, one launcher (org.egov.Application)
├── pgr                 → /pgr-services
├── id                  → /egov-idgen
├── mdmsv2              → /mdms-v2
├── localization        → /localization
└── wf                  → /egov-workflow-v2
```

Each module keeps its **own API path** and **own DB migrations**.

> Packages alone are only a *convention* — Spring and `javac` happily let one module
> `@Autowired` another's repository. Spring Modulith adds the enforcement (slide 6).

---

## 4. Module = public API + hidden internals

```text
┌──────────────────────────────┐
│  Module (e.g. org.egov.id)
│   • public API   ← others may call this
│   • internals    ← service / repo / entity, hidden
└──────────────────────────────┘
```

Two ways to expose what's public (rest is internal by default):

**(a) Public types at the module root, internals in sub-packages** — Modulith's default rule:
types in the module's base package are public; anything in a sub-package is hidden.

```text
org.egov.id            ← public API types live here (e.g. IdGenerationApi)
└── service / repo     ← sub-packages = internal, not reachable from other modules
```

**(b) `@NamedInterface`** — when the API must stay in a sub-package, mark that package public:

```java
@NamedInterface("service")          // IdGenerationService becomes public API
package org.egov.id.service;        // everything else in idgen stays internal
```

I used **(b)** here because each service kept its existing layout (`id.service`, `pgr.web`, …)
— no need to move files just to expose an API.

---

## 5. Restricting who a module may depend on

A module can also whitelist which other modules it's allowed to use, on its `package-info`:

```java
@ApplicationModule(allowedDependencies = { "id", "mdmsv2", "localization", "wf" })
package org.egov.pgr;     // PGR may depend on exactly these — reaching any other module fails the build
```

This matches the only edges here: `pgr → {id, mdmsv2, localization, wf}`.

---

## 6. The boundary is checked at build time

One test guards the whole architecture:

```java
ApplicationModules.of(Application.class).verify();
```

**What `verify()` checks:**
- **Illegal access** — a module touching another module's internal (non-exposed) type.
- **Cyclic dependencies** — module A → B → A is rejected.
- **Undeclared dependencies** — using a module not in `allowedDependencies` (slide 5).
- **Exposed vs internal** — depending on a type that isn't part of any exposed API.

**What it does NOT do:**
- No bean creation / Spring wiring (that's runtime).
- No security, and it does **not** stop compilation — code still compiles fine.
- No runtime-behaviour checks — only the static module structure, only when the test runs.

Example it actually caught here:

```text
Module 'pgr' depends on non-exposed type
   org.egov.mdmsv2.model.MdmsResponse$MdmsResponseBuilder within module 'mdmsv2'
→ fix: publish org.egov.mdmsv2.model too.
```

That's the discipline a microservice gets from the network — here, for free, at compile time.

---

## 7. Maven module ≠ Spring Modulith module

Two different concepts; they don't have to align:

```text
Maven module           → build organization (one pom.xml = one build unit)
Spring Modulith module → architectural boundary (one package = one module)
```

Here it's **one Maven module** (single `pom.xml`) holding **five Modulith modules**.

---

## 8. Sync calls: HTTP → in-process

The core change. PGR's calls to idgen/mdms/workflow/localization stop being HTTP and become
direct bean calls. Example — fetching an id:

```java
// BEFORE: HTTP round-trip to the idgen service
return restTemplate.postForObject(host + path, req, IdGenerationResponse.class);

// AFTER: direct in-process call through idgen's published service
IdGenerationResponse resp = idGenerationService.generateIdResponse(req);
```

The HTTP controllers stay, so external callers (Postman, other systems) still hit
`/egov-idgen/...` unchanged. Only PGR's internal hop became a method call → no serialization,
no network, one transaction.

---

## 9. Async stays on Kafka (events deferred)

A "pure" modulith would replace Kafka with in-process events. **I did not.** The write path is
kept exactly as before:

```text
pgr / workflow ──► Kafka ──► egov-persister (separate service) ──► DB
```

Why: that producer→persister decoupling is worth keeping, so `egov-persister` is **not**
absorbed.

```text
sync   → in-process bean calls     ✅ done
async  → Kafka + separate persister ✅ kept on purpose
events → @ApplicationModuleListener ⏳ later
```

**What in-process events would add later (the deferred option):**
- Replace `kafkaTemplate.send(...)` with `publisher.publishEvent(...)` + an
  `@ApplicationModuleListener` — loose coupling with no broker.
- Use **after-commit** delivery so a failed side-effect (e.g. SMS) can't roll back the saved
  complaint.
- The **Event Publication Registry** persists each event (`Published / Completed / Failed`) so a
  JVM crash after commit can still replay it — the durability Kafka gives us today.

---

## 10. What I actually built

| Module | Was | PGR calls it via |
|---|---|---|
| `pgr` | host code, unchanged | — |
| `id` | unchanged package | in-process `IdGenerationService` |
| `mdmsv2` | `org.egov.infra.mdms` | in-process `MDMSService` |
| `localization` | `org.egov.{config,web,…}` | in-process `MessageService` |
| `wf` | unchanged package | in-process `WorkflowService` |
| `egov-user` | **kept a microservice** (Boot 1.5 / Java 8) | HTTP |
| `egov-persister` | **kept separate** (Kafka consumer) | async |

Proven end-to-end: a real PGR create → Kafka → persister → rows in the shared DB.

---

## 11. Findings (the parts theory skips)

- **Build & `verify()` catch boundaries; only *boot* catches wiring.** Most real bugs showed
  up at startup, not at compile time.
- **Bean-name clashes** — `WorkflowService`/`UserService` exist in two modules → fixed with a
  fully-qualified bean-name generator.
- **Duplicate infra `@Bean`s** (`objectMapper`, `restTemplate`) → allow bean overriding for now.
- **One eager `@PostConstruct` can kill the whole app** (felt the bigger blast radius) → made
  it tolerant.
- **One DB, but per-module migration folders + history tables** keep schema ownership clear.

---

## 12. When NOT to fold a service in

Keep a service separate when it needs any of:
- independent **scaling** or **deployment**,
- a different **tech stack** (exactly why `egov-user`, Boot 1.5 / Java 8, stayed out),
- strong **organizational / regulatory** isolation,
- or when its **decoupling is the feature** (why `egov-persister` stayed a Kafka consumer).

---

## 13. Recommendation — a deliberate hybrid

- **Fold in** the tightly-coupled, same-stack business modules (`pgr`, `id`, `wf`, `mdmsv2`,
  `localization`) — sync hops become in-process, boundaries enforced by `verify()`.
- **Keep separate** what's worth keeping separate: `egov-user` (different stack) and the async
  **persister** pipeline.

Not an all-or-nothing switch — you choose, per concern, what stays distributed.

---

## Key takeaway

> Spring Modulith isn't "a better monolith" or "microservices in packages."
> It enforces **microservice-style boundaries inside one deployment** —
> collapsing the network hop for sync calls, while you decide what stays distributed.