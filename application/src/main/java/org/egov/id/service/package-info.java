/**
 * Published surface of the idgen module: its service layer.
 *
 * <p>{@code @NamedInterface} promotes this sub-package to part of idgen's public API so other
 * modules (here {@code pgr}) may depend on {@code IdGenerationService}. Everything in idgen NOT
 * in a named interface stays internal and {@code verify()} forbids outside access — that is the
 * enforced boundary that lets idgen be extracted back to a microservice cleanly.
 */
@org.springframework.modulith.NamedInterface("service")
package org.egov.id.service;
