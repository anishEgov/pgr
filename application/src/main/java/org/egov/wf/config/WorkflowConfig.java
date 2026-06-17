package org.egov.wf.config;

import java.util.TimeZone;


import jakarta.annotation.PostConstruct;
import org.cache2k.extra.spring.SpringCache2kCacheManager;
import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.tracer.config.TracerConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;



@Import({TracerConfiguration.class, MultiStateInstanceUtil.class})
@EnableCaching
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Component
public class WorkflowConfig {



    @Value("${app.timezone}")
    private String timeZone;

    @Value("${cache.expiry.workflow.minutes}")
    private int workflowExpiry;

    @PostConstruct
    public void initialize() {
        TimeZone.setDefault(TimeZone.getTimeZone(timeZone));
    }

    // [Phase R] Re-homed verbatim from workflow's dropped Main entry point (which carried
    // @EnableCaching + this bean). Required by workflow's @Cacheable("businessService" /
    // "roleTenantAndStatusesMapping") methods; the merged context had no CacheManager otherwise.
    @Bean
    @Profile("!test")
    public CacheManager cacheManager() {
        return new SpringCache2kCacheManager()
                .addCaches(b -> b.name("businessService").expireAfterWrite(workflowExpiry, TimeUnit.MINUTES).entryCapacity(10))
                .addCaches(b -> b.name("roleTenantAndStatusesMapping").expireAfterWrite(workflowExpiry, TimeUnit.MINUTES).entryCapacity(10));
    }

    @Bean
    public MappingJackson2HttpMessageConverter jacksonConverter(ObjectMapper objectMapper) {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);
        return converter;
    }


    @Value("${egov.wf.default.limit}")
    private Integer defaultLimit;

    @Value("${egov.wf.default.offset}")
    private Integer defaultOffset;

    @Value("${egov.wf.max.limit}")
    private Integer maxSearchLimit;

    @Value("${persister.save.transition.wf.topic}")
    private String saveTransitionTopic;

    @Value("${persister.save.businessservice.wf.topic}")
    private String saveBusinessServiceTopic;

    @Value("${persister.update.businessservice.wf.topic}")
    private String updateBusinessServiceTopic;



    //MDMS
    @Value("${egov.mdms.host}")
    private String mdmsHost;

    @Value("${egov.mdms.search.endpoint}")
    private String mdmsEndPoint;


    //User
    @Value("${egov.user.host}")
    private String userHost;

    @Value("${egov.user.search.endpoint}")
    private String userSearchEndpoint;

    @Value("${egov.wf.inbox.assignedonly}")
    private Boolean assignedOnly;


    // Statelevel tenantId required for escalation
    @Value("${state.level.tenant.id}")
    private String stateLevelTenantId;

    @Value("${egov.wf.escalation.batch.size}")
    private Integer escalationBatchSize;

    // Central instance configs
    @Value("${state.level.tenantid.length}")
    private Integer stateLevelTenantIdLength;

    @Value("${is.environment.central.instance}")
    private Boolean isEnvironmentCentralInstance;




}
