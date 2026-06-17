package org.egov.pgr.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.localization.domain.model.MessageSearchCriteria;
import org.egov.localization.domain.model.Tenant;
import org.egov.localization.domain.service.MessageService;
import org.egov.localization.web.contract.MessagesResponse;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.producer.Producer;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.web.models.Notification.EventRequest;
import org.egov.pgr.web.models.Notification.SMSRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;
import static org.egov.pgr.util.PGRConstants.*;

@Component
@Slf4j
public class NotificationUtil {

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private PGRConfiguration config;

    @Autowired
    private Producer producer;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private MultiStateInstanceUtil centralInstanceUtil;

    // [Phase 3] in-process handle to the localization module + mapper to keep the JSON-string shape.
    // Constructor-injected: Spring Modulith's verify() requires cross-module dependencies to use
    // constructor injection (it rejected field injection here) so the module coupling is explicit.
    private final MessageService messageService;

    private final ObjectMapper mapper;

    @Autowired
    public NotificationUtil(MessageService messageService, ObjectMapper mapper) {
        this.messageService = messageService;
        this.mapper = mapper;
    }


    /**
     * Fetch localized messages from the in-process localization module.
     *
     * <p><b>Modulith change (Phase 3, D5):</b> was an HTTP GET/POST to {@code egov.localization.host}
     * {@code /messages/v1/_search} via {@code serviceRequestRepository.fetchResult(...)}. Now we
     * call the localization module's {@link MessageService#getFilteredMessages} directly and rebuild
     * the same {@code MessagesResponse} the controller returns, then serialize it to the JSON string
     * the rest of NotificationUtil already JsonPath-parses — so callers are unchanged.
     *
     * @param tenantId Tenant ID
     * @param requestInfo Request Info object (its msgId may carry the locale as {@code msgId|locale})
     * @param module Module name
     * @return localization messages as a JSON string (shape {@code {"messages":[...]}})
     */
    public String getLocalizationMessages(String tenantId, RequestInfo requestInfo,String module) {
        tenantId = centralInstanceUtil.getStateLevelTenant(tenantId);
        String locale = NOTIFICATION_LOCALE;
        if (!StringUtils.isEmpty(requestInfo.getMsgId()) && requestInfo.getMsgId().split("\\|").length >= 2)
            locale = requestInfo.getMsgId().split("\\|")[1];

        MessageSearchCriteria criteria = MessageSearchCriteria.builder()
                .locale(locale).tenantId(new Tenant(tenantId)).module(module).build();
        List<org.egov.localization.domain.model.Message> domainMessages =
                messageService.getFilteredMessages(criteria);
        MessagesResponse response = new MessagesResponse(domainMessages.stream()
                .map(org.egov.localization.web.contract.Message::new).collect(Collectors.toList()));
        return new JSONObject(mapper.convertValue(response, Map.class)).toString();
    }

    /**
     *
     * @param tenantId Tenant ID
     * @param requestInfo Request Info object
     * @param module Module name
     * @return Return uri
     */
    public StringBuilder getUri(String tenantId, RequestInfo requestInfo, String module) {

        /*if (config.getIsLocalizationStateLevel())
            tenantId= centralInstanceUtil.getStateLevelTenant(tenantId);*/
        tenantId= centralInstanceUtil.getStateLevelTenant(tenantId);
        log.info("tenantId after calling central instance method :"+ tenantId);
        String locale = NOTIFICATION_LOCALE;
        if (!StringUtils.isEmpty(requestInfo.getMsgId()) && requestInfo.getMsgId().split("|").length >= 2)
            locale = requestInfo.getMsgId().split("\\|")[1];
        StringBuilder uri = new StringBuilder();
        uri.append(config.getLocalizationHost()).append(config.getLocalizationContextPath())
                .append(config.getLocalizationSearchEndpoint()).append("?").append("locale=").append(locale)
                .append("&tenantId=").append(tenantId).append("&module=").append(module);

        return uri;
    }

    /**
     *
     * @param action Action
     * @param applicationStatus Application Status
     * @param roles CITIZEN or EMPLOYEE
     * @param localizationMessage Localisation Message
     * @return Return Customized Message based on localisation code
     */
    public String getCustomizedMsg(String action, String applicationStatus, String roles, String localizationMessage) {
        StringBuilder notificationCode = new StringBuilder();

        notificationCode.append("PGR_").append(roles.toUpperCase()).append("_").append(action.toUpperCase()).append("_").append(applicationStatus.toUpperCase()).append("_SMS_MESSAGE");

        String path = "$..messages[?(@.code==\"{}\")].message";
        path = path.replace("{}", notificationCode);
        String message = null;
        try {
            ArrayList<String> messageObj = JsonPath.parse(localizationMessage).read(path);
            if(messageObj != null && messageObj.size() > 0) {
                message = messageObj.get(0);
            }
        } catch (Exception e) {
            log.warn("Fetching from localization failed", e);
        }

        return message;
    }

    /**
     *
     * @param roles EMPLOYEE or CITIZEN
     * @param localizationMessage Localisation Message
     * @return Return localisation message based on default code
     */
    public String getDefaultMsg(String roles, String localizationMessage) {
        StringBuilder notificationCode = new StringBuilder();

        notificationCode.append("PGR_").append("DEFAULT_").append(roles.toUpperCase()).append("_SMS_MESSAGE");

        String path = "$..messages[?(@.code==\"{}\")].message";
        path = path.replace("{}", notificationCode);
        String message = null;
        try {
            ArrayList<String> messageObj = JsonPath.parse(localizationMessage).read(path);
            if(messageObj != null && messageObj.size() > 0) {
                message = messageObj.get(0);
            }
        } catch (Exception e) {
            log.warn("Fetching from localization failed", e);
        }

        return message;
    }

    /**
     * Send the SMSRequest on the SMSNotification kafka topic
     * @param smsRequestList The list of SMSRequest to be sent
     */
    public void sendSMS(String tenantId, List<SMSRequest> smsRequestList) {
        if (config.getIsSMSEnabled()) {
            if (CollectionUtils.isEmpty(smsRequestList)) {
                log.info("Messages from localization couldn't be fetched!");
                return;
            }
            for (SMSRequest smsRequest : smsRequestList) {
                producer.push(tenantId,config.getSmsNotifTopic(), smsRequest);
                log.info("Messages: " + smsRequest.getMessage());
            }
        }
    }

    /**
     * Pushes the event request to Kafka Queue.
     *
     * @param request EventRequest Object
     */
    public void sendEventNotification(String tenantId, EventRequest request) {
        producer.push(tenantId,config.getSaveUserEventsTopic(), request);
    }

    /**
     *
     * @param actualURL Actual URL
     * @return Shortened URL
     */
    public String getShortnerURL(String actualURL) {
        HashMap<String,String> body = new HashMap<>();
        body.put("url",actualURL);
        StringBuilder builder = new StringBuilder(config.getUrlShortnerHost());
        builder.append(config.getUrlShortnerEndpoint());
        String res = restTemplate.postForObject(builder.toString(), body, String.class);

        if(StringUtils.isEmpty(res)){
            log.error("URL_SHORTENING_ERROR","Unable to shorten url: "+actualURL); ;
            return actualURL;
        }
        else return res;
    }

    /**
     *
     * @param localizationMessage Localisation Code
     * @param notificationCode Notification Code
     * @return Return Customized Message
     */
    public String getCustomizedMsgForPlaceholder(String localizationMessage,String notificationCode) {
        String path = "$..messages[?(@.code==\"{}\")].message";
        path = path.replace("{}", notificationCode);
        String message = null;
        try {
            ArrayList<String> messageObj = (ArrayList<String>) JsonPath.parse(localizationMessage).read(path);
            if(messageObj != null && messageObj.size() > 0) {
                message = messageObj.get(0);
            }
        } catch (Exception e) {
            log.warn("Fetching from localization for placeholder failed", e);
        }
        return message;
    }

}
