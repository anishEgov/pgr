/**
 * mdms application module (former {@code mdms-v2} microservice).
 *
 * <p>Repackaged from {@code org.egov.infra.mdms.*} to {@code org.egov.mdmsv2.*} (D2). The name
 * is {@code mdmsv2}, NOT {@code mdms}, because the {@code mdms-client} library already owns the
 * {@code org.egov.mdms} package — a collision we must avoid in a single classpath.
 *
 * <p>Its service layer is published via {@code @NamedInterface} so the {@code pgr} module can
 * call {@code MDMSService.search(...)} in-process. The REST controllers ({@code /v1/_search},
 * {@code /v2/...}, {@code schema/v1/...}) are kept so external callers (Postman) are unaffected.
 */
@org.springframework.modulith.ApplicationModule(displayName = "mdms-v2")
package org.egov.mdmsv2;