package org.egov.mdmsv2.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.egov.common.utils.MultiStateInstanceUtil;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * mdms module config (Option 2 — typed, prefix-bound).
 *
 * <p>All keys live under the single {@code egov.mdms.*} prefix and bind by field name via Spring's
 * relaxed binding (e.g. field {@code saveMdmsDataTopicName} ← {@code egov.mdms.save-mdms-data-topic-name}).
 * Replaces the previous scattered {@code @Value} lookups; getter names are unchanged so call sites
 * (repositories / query builders / services) are untouched.
 */
@Configuration
@ConfigurationProperties(prefix = "egov.mdms")
@ToString
@Setter
@Getter
@Import({MultiStateInstanceUtil.class})
public class ApplicationConfig {

    private String saveSchemaDefinitionTopicName;   // egov.mdms.save-schema-definition-topic-name

    private String saveMdmsDataTopicName;            // egov.mdms.save-mdms-data-topic-name

    private String updateMdmsDataTopicName;          // egov.mdms.update-mdms-data-topic-name

    private Integer defaultOffset;                   // egov.mdms.default-offset

    private Integer defaultLimit;                    // egov.mdms.default-limit

}