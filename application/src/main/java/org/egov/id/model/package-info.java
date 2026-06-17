/**
 * Published surface of the idgen module: its request/response contract model.
 *
 * <p>Exposed as a named interface because {@code pgr}'s {@code IdGenRepository} builds an
 * {@code IdGenerationRequest} and reads an {@code IdGenerationResponse} when calling
 * {@link org.egov.id.service.IdGenerationService} in-process. idgen owns these types; PGR maps
 * to/from its own contract at the call boundary.
 */
@org.springframework.modulith.NamedInterface("model")
package org.egov.id.model;
