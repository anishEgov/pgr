package org.egov.pgr.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.minidev.json.JSONArray;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.mdms.model.MasterDetail;
import org.egov.mdms.model.MdmsCriteria;
import org.egov.mdms.model.MdmsCriteriaReq;
import org.egov.mdms.model.ModuleDetail;
import org.egov.mdmsv2.model.MdmsResponse;
import org.egov.mdmsv2.service.MDMSService;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.web.models.ServiceRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

import static org.egov.pgr.util.PGRConstants.MDMS_MODULE_NAME;
import static org.egov.pgr.util.PGRConstants.MDMS_SERVICEDEF;
import static org.egov.pgr.util.PGRConstants.MDMS_COMMON_MASTERS_MODULE_NAME;
import static org.egov.pgr.util.PGRConstants.MDMS_DEPT_MASTER;

@Component
public class MDMSUtils {



    private PGRConfiguration config;

    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private MultiStateInstanceUtil multiStateInstanceUtil;

    // [Phase 2] in-process handle to the mdms module + mapper to keep mDMSCall's Object/Map shape
    private final MDMSService mdmsService;

    private final ObjectMapper mapper;

    @Autowired
    public MDMSUtils(PGRConfiguration config, ServiceRequestRepository serviceRequestRepository,
                     MDMSService mdmsService, ObjectMapper mapper) {
        this.config = config;
        this.serviceRequestRepository = serviceRequestRepository;
        this.mdmsService = mdmsService;
        this.mapper = mapper;
    }

    /**
     * Calls the in-process mdms module to fetch pgr master data.
     *
     * <p><b>Modulith change (Phase 2, D5):</b> was an HTTP POST to {@code egov.mdms.host} via
     * {@code serviceRequestRepository.fetchResult(...)}. Now we call the {@code mdmsv2} module's
     * {@link MDMSService#search(MdmsCriteriaReq)} directly. The request DTO ({@code MdmsCriteriaReq})
     * is the shared {@code mdms-client} contract, so no mapping is needed on the way in. We
     * replicate exactly what {@code MDMSController} did — wrap the master map in an
     * {@code MdmsResponse} — and convert to a {@code Map} so callers see the same shape the HTTP
     * response produced ({@code $.MdmsRes...}).
     *
     * @param request the PGR service request
     * @return MDMS master data as a Map, identical in shape to the former HTTP response
     */
    public Object mDMSCall(ServiceRequest request){
        RequestInfo requestInfo = request.getRequestInfo();
        String tenantId = request.getService().getTenantId();
        MdmsCriteriaReq mdmsCriteriaReq = getMDMSRequest(requestInfo,multiStateInstanceUtil.getStateLevelTenant(tenantId));
        // mdms-v2 carries its own copy of the contract model, so translate PGR's mdms-client
        // MdmsCriteriaReq into the module's MdmsCriteriaReq at this single boundary.
        org.egov.mdmsv2.model.MdmsCriteriaReq moduleReq =
                mapper.convertValue(mdmsCriteriaReq, org.egov.mdmsv2.model.MdmsCriteriaReq.class);
        Map<String, Map<String, JSONArray>> moduleMasterMap = mdmsService.search(moduleReq);
        MdmsResponse mdmsResponse = MdmsResponse.builder().mdmsRes(moduleMasterMap).build();
        return mapper.convertValue(mdmsResponse, Map.class);
    }


    /**
     * Returns mdms search criteria based on the tenantId
     * @param requestInfo
     * @param tenantId
     * @return
     */
    public MdmsCriteriaReq getMDMSRequest(RequestInfo requestInfo,String tenantId){
        List<ModuleDetail> pgrModuleRequest = getPGRModuleRequest();

        List<ModuleDetail> moduleDetails = new LinkedList<>();
        moduleDetails.addAll(pgrModuleRequest);

        List<MasterDetail> commonMasterDetails = new ArrayList<>();
        commonMasterDetails.add(MasterDetail.builder().name(MDMS_DEPT_MASTER).build());
        ModuleDetail commonModuleDtls = ModuleDetail.builder().masterDetails(commonMasterDetails)
                .moduleName(MDMS_COMMON_MASTERS_MODULE_NAME).build();
        moduleDetails.add(commonModuleDtls);

        MdmsCriteria mdmsCriteria = MdmsCriteria.builder().moduleDetails(moduleDetails).tenantId(tenantId)
                .build();

        MdmsCriteriaReq mdmsCriteriaReq = MdmsCriteriaReq.builder().mdmsCriteria(mdmsCriteria)
                .requestInfo(requestInfo).build();
        return mdmsCriteriaReq;
    }


    /**
     * Creates request to search serviceDef from MDMS
     * @return request to search UOM from MDMS
     */
    private List<ModuleDetail> getPGRModuleRequest() {

        // master details for TL module
        List<MasterDetail> pgrMasterDetails = new ArrayList<>();

        // filter to only get code field from master data
        final String filterCode = "$.[?(@.active==true)]";

        pgrMasterDetails.add(MasterDetail.builder().name(MDMS_SERVICEDEF).filter(filterCode).build());

        ModuleDetail pgrModuleDtls = ModuleDetail.builder().masterDetails(pgrMasterDetails)
                .moduleName(MDMS_MODULE_NAME).build();


        return Collections.singletonList(pgrModuleDtls);

    }


    /**
     * Returns the url for mdms search endpoint
     *
     * @return url for mdms search endpoint
     */
    public StringBuilder getMdmsSearchUrl() {
        return new StringBuilder().append(config.getMdmsHost()).append(config.getMdmsEndPoint());
    }

}
