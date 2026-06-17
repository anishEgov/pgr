/**
 * Published surface of the mdms module: its service layer.
 *
 * <p>Exposed as a named interface so the {@code pgr} module may depend on {@code MDMSService}
 * (and the v2/schema services) in-process. Repositories, querybuilders, enrichers and validators
 * stay internal — {@code verify()} blocks any other module from reaching them, which is the seam
 * along which mdms could be re-extracted to a standalone service.
 */
@org.springframework.modulith.NamedInterface("service")
package org.egov.mdmsv2.service;