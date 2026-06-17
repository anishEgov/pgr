/**
 * localization application module (former egov-localization microservice).
 *
 * <p>Repackaged from the generic roots {@code org.egov.{config,web,util,domain,persistence}} into
 * {@code org.egov.localization.*} (D2) so it forms ONE clean module instead of five stray
 * top-level packages. Brings Spring Data JPA + Redis into the app (see pom note). REST
 * controllers ({@code /messages/...}, {@code /defaultdata/...}) are kept for external callers.
 *
 * <p>Publishes {@code domain.service}, {@code domain.model} and {@code web.contract} as named
 * interfaces so {@code pgr} can fetch localized messages in-process.
 */
@org.springframework.modulith.ApplicationModule(displayName = "localization")
package org.egov.localization;
