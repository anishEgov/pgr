/**
 * workflow application module (former egov-workflow-v2 microservice).
 *
 * <p>Already cleanly namespaced at {@code org.egov.wf}, so no repackage was needed (D2).
 * REST controllers ({@code /egov-wf/process/...}, {@code /egov-wf/businessservice/...}) are kept
 * for external callers. Publishes {@code service}, {@code service.V1} and {@code web.models} as
 * named interfaces so {@code pgr} can run transitions/searches in-process.
 */
@org.springframework.modulith.ApplicationModule(displayName = "workflow")
package org.egov.wf;
