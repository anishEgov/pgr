package org.egov.id.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 *
 * @author Yosadhara
 *
 * <p>idgen module config (Option 2 — typed, prefix-bound). idgen's functional keys are re-homed
 * under the single {@code egov.idgen.*} prefix and bound by field name via Spring's relaxed binding
 * (e.g. {@code timeZone} ← {@code egov.idgen.time-zone}). Replaces the previous per-getter
 * {@code Environment.getProperty(...)} lookups; getter names are unchanged so call sites
 * ({@code IdGenerationService}, {@code GlobalExceptionHandler}) are untouched.
 *
 * <p>The former {@code getDbUrl/getDbUserName/getDbPassword/getServerContextpath} accessors were
 * dropped: they read shared infra keys ({@code spring.datasource.*}, {@code server.context-path})
 * that idgen does not own, and had no callers in the modulith.
 */
@Configuration
@ConfigurationProperties(prefix = "egov.idgen")
@ToString
@Getter
@Setter
@NoArgsConstructor
public class PropertiesManager {

	private String invalidInput;        // egov.idgen.invalid-input

	private String idGenerationTable;   // egov.idgen.id-generation-table

	private String idSequenceOverflow;  // egov.idgen.id-sequence-overflow

	private String idSequenceNotFound;  // egov.idgen.id-sequence-notfound

	private String invalidIdFormat;     // egov.idgen.invalid-id-format

	private String success;             // egov.idgen.success

	private String failed;              // egov.idgen.failed

	private String cityCodeNotFound;    // egov.idgen.city-code-notfound

	private String timeZone;            // egov.idgen.time-zone

}
