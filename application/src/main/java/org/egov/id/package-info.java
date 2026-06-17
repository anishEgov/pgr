/**
 * idgen application module (former {@code egov-idgen} microservice).
 *
 * <p>Per the agreed minimal-change approach (D1a) the service is brought in with its existing
 * internal sub-structure ({@code service}, {@code model}, {@code api}, ...) intact — no forced
 * {@code api/}+{@code internal/} split. {@code ApplicationModules.verify()} still enforces the
 * important invariants (no cycles, no reaching into other modules' internals).
 *
 * <p>When the {@code pgr} module is wired to call idgen in-process (replacing its HTTP client),
 * the specific type(s) it needs (e.g. {@code IdGenerationService}) will be published with
 * {@code @org.springframework.modulith.NamedInterface} — the Spring Modulith 1.1.x way to expose
 * a sub-package type across a module boundary. That published surface is also the exact seam
 * along which idgen can be extracted back into a standalone microservice.
 */
@org.springframework.modulith.ApplicationModule(displayName = "idgen")
package org.egov.id;
