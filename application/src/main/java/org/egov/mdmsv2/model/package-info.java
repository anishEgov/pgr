/**
 * Published surface of the mdms module: its contract model.
 *
 * <p>Exposed as a named interface because {@code pgr} translates its mdms-client
 * {@code MdmsCriteriaReq} into this module's {@code MdmsCriteriaReq} and reads back an
 * {@code MdmsResponse} when calling {@link org.egov.mdmsv2.service.MDMSService} in-process.
 *
 * <p>Added in response to a real {@code verify()} violation: "Module 'pgr' depends on non-exposed
 * type org.egov.mdmsv2.model.MdmsResponse$MdmsResponseBuilder within module 'mdmsv2'". That is the
 * modulith boundary working as designed — a type only becomes cross-module API once published.
 */
@org.springframework.modulith.NamedInterface("model")
package org.egov.mdmsv2.model;
