# Demo: Spring Modulith `verify()` failing on a module-boundary violation

This branch (`demo/modulith-verify-fails`) intentionally breaks a module boundary so you can SEE
`ApplicationModules.verify()` fail the build. `main` stays green; this branch is red on purpose.

> One-line takeaway: the class we touch is `public`, so the **Java compiler is happy** — only
> **Spring Modulith** fails it, because one module reached into another module's *internal* package.

---

## 1. The change we made

In **pgr**'s `IdGenRepository` (package `org.egov.pgr.repository` → module **`pgr`**), the constructor
now calls a method on **idgen**'s **internal** config class
`org.egov.id.config.PropertiesManager` (package `org.egov.id.config` → module **`id`**, NOT exposed —
only `org.egov.id.service` and `org.egov.id.model` are published as `@NamedInterface`s).

`application/src/main/java/org/egov/pgr/repository/IdGenRepository.java`
```java
//  BEFORE (on main — allowed): pgr only uses idgen's PUBLISHED service
@Autowired
public IdGenRepository(IdGenerationService idGenerationService, ObjectMapper mapper) {
    this.idGenerationService = idGenerationService;   // org.egov.id.service  (exposed)
    this.mapper = mapper;
}
```
```java
//  AFTER (this branch — VIOLATION): pgr reaches into idgen's INTERNAL config
@Autowired
public IdGenRepository(IdGenerationService idGenerationService, ObjectMapper mapper,
                       org.egov.id.config.PropertiesManager idgenInternalConfig) {  // ❌ internal type
    this.idGenerationService = idGenerationService;
    this.mapper = mapper;
    idgenInternalConfig.getIdGenerationTable();        // ❌ call into idgen's non-exposed package
}
```

Why this is a violation (and the same call from *inside* idgen would NOT be):
- Modulith boundaries are between **modules** = top-level packages under the root `org.egov`.
  A class belongs to the module of its package. `IdGenRepository` is in `org.egov.pgr.*` → module
  **pgr** (it's pgr's *client* to idgen, despite the name).
- `PropertiesManager` is in `org.egov.id.config` → module **id**, and `config` is **not** an exposed
  named interface. So this is **pgr → idgen-internal**, a cross-module access into a non-exposed
  package → forbidden.
- If an idgen class (`org.egov.id.*`) called the same `PropertiesManager`, that's **intra-module**
  (idgen → idgen) → perfectly fine, no failure. idgen owns its own config.

---

## 2. How to run it

```bash
git checkout demo/modulith-verify-fails

# JDK 17 (repo targets Java 17; JDK 25 breaks Lombok's annotation processor)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# run ONLY the modularity test
mvn -o test -Dtest=ModularityTests
```

Show that it's green on main for contrast:
```bash
git checkout main
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn -o test -Dtest=ModularityTests   # ✅ passes
```

---

## 3. The error we got

```
[ERROR] ModularityTests.verifiesModuleBoundaries:35 » Violations
org.springframework.modulith.core.Violations:
- Module 'pgr' depends on non-exposed type org.egov.id.config.PropertiesManager within module 'id'!

[ERROR] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
```

- `ApplicationModules.verify()` (run by `org.egov.ModularityTests`) walks the module model with
  ArchUnit and finds the illegal edge `pgr → org.egov.id.config.PropertiesManager`.
- Note: it's an **Error** in `verifiesModuleBoundaries`, i.e. the build FAILS. Compilation succeeded
  — only the boundary check failed.

---

## 4. The fix (what `main` does)

Depend only on idgen's **published** surface — its named interfaces `id.service` / `id.model`:
```java
// allowed: org.egov.id.service.IdGenerationService is exposed via @NamedInterface("service")
public IdGenRepository(IdGenerationService idGenerationService, ObjectMapper mapper) { ... }
```
Remove the `PropertiesManager` parameter/call → `verify()` passes again.

If pgr genuinely needed something from idgen's config, idgen would **expose** it (move it to the
module base package, or add `@NamedInterface`, or surface it through a published `service` method) —
i.e. make it part of idgen's intended API, not reach around it.

---

## 5. Module API surface (for reference) — what IS exposed per module

| module | base pkg | exposed (callable cross-module) | internal (NOT callable) |
|---|---|---|---|
| idgen | `org.egov.id` | `id.service`, `id.model` | `id.config` ⟵ *this demo*, `id.api`, `id.exception` |
| mdms-v2 | `org.egov.mdmsv2` | `mdmsv2.service`, `mdmsv2.model` | `repository`, `controller`, `producer`, … |
| localization | `org.egov.localization` | `domain.service`, `domain.model`, `web.contract` | `persistence`, `config`, `web.controller` |
| workflow | `org.egov.wf` | `wf.service`, `wf.service.V1`, `wf.web.models` | `repository`, `web.controllers`, `producer` |
| pgr | `org.egov.pgr` | (consumer; exposes nothing) | everything |
