package org.egov.pgr.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.pgr.web.models.*;
import org.egov.pgr.web.models.workflow.*;
import org.egov.tracer.model.CustomException;
import org.egov.wf.service.V1.BusinessMasterServiceV1;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

import static org.egov.pgr.util.PGRConstants.*;

/**
 * Adapter from the {@code pgr} module to the {@code workflow} (org.egov.wf) module.
 *
 * <p><b>Modulith change (Phase 4, D5):</b> the three HTTP calls PGR used to make to the workflow
 * service — businessservice search, process {@code _transition}, process {@code _search} — are now
 * direct in-process calls to the workflow module's beans ({@link BusinessMasterServiceV1} and the
 * workflow {@code WorkflowService}). PGR keeps its own workflow DTOs; we translate to/from the
 * module's DTOs at each call with {@link ObjectMapper}. Both sides reuse the same
 * {@code org.egov.common.contract} {@code RequestInfo}, so only the workflow-specific models map.
 *
 * <p>The class name {@code WorkflowService} exists in both modules; they no longer collide because
 * the app uses a fully-qualified bean-name generator. We reference the workflow one by FQN.
 */
@org.springframework.stereotype.Service
public class WorkflowService {

    private final ObjectMapper mapper;

    // workflow module beans (FQN: the workflow module also has a class named WorkflowService)
    private final org.egov.wf.service.WorkflowService wfWorkflowService;

    private final BusinessMasterServiceV1 businessMasterServiceV1;

    @Autowired
    public WorkflowService(ObjectMapper mapper,
                           org.egov.wf.service.WorkflowService wfWorkflowService,
                           BusinessMasterServiceV1 businessMasterServiceV1) {
        this.mapper = mapper;
        this.wfWorkflowService = wfWorkflowService;
        this.businessMasterServiceV1 = businessMasterServiceV1;
    }

    /*
     *
     * Should return the applicable BusinessService for the given request
     *
     * */
    public BusinessService getBusinessService(ServiceRequest serviceRequest) {
        String tenantId = serviceRequest.getService().getTenantId();
        // was: HTTP GET wf/businessservice/_search?tenantId=..&businessServices=..
        org.egov.wf.web.models.BusinessServiceSearchCriteria criteria =
                new org.egov.wf.web.models.BusinessServiceSearchCriteria();
        criteria.setTenantId(tenantId);
        criteria.setBusinessServices(Collections.singletonList(PGR_BUSINESSSERVICE));

        List<org.egov.wf.web.models.BusinessService> wfBusinessServices = businessMasterServiceV1.search(criteria);
        List<BusinessService> businessServices =
                mapper.convertValue(wfBusinessServices, new TypeReference<List<BusinessService>>() {});

        if (CollectionUtils.isEmpty(businessServices))
            throw new CustomException("BUSINESSSERVICE_NOT_FOUND", "The businessService " + PGR_BUSINESSSERVICE + " is not found");

        return businessServices.get(0);
    }


    /*
     * Call the workflow service with the given action and update the status
     * return the updated status of the application
     *
     * */
    public String updateWorkflowStatus(ServiceRequest serviceRequest) {
        ProcessInstance processInstance = getProcessInstanceForPGR(serviceRequest);
        ProcessInstanceRequest workflowRequest = new ProcessInstanceRequest(serviceRequest.getRequestInfo(), Collections.singletonList(processInstance));
        ProcessInstanceResponse response = callWorkFlow(workflowRequest);
        serviceRequest.getService().setApplicationStatus(response.getProcessInstances().get(0).getState().getApplicationStatus());
        serviceRequest.getService().setProcessInstance(response.getProcessInstances().get(0));
        return response.getProcessInstances().get(0).getState().getApplicationStatus();
    }


    public void validateAssignee(ServiceRequest serviceRequest) {
        /*
         * Call HRMS service and validate of the assignee belongs to same department
         * as the employee assigning it
         *
         * */

    }


    public void enrichmentForSendBackToCititzen() {
        /*
         * If send bac to citizen action is taken assignes should be set to accountId
         *
         * */
    }


    public List<ServiceWrapper> enrichWorkflow(RequestInfo requestInfo, List<ServiceWrapper> serviceWrappers) {

        // FIX ME FOR BULK SEARCH
        Map<String, List<ServiceWrapper>> tenantIdToServiceWrapperMap = getTenantIdToServiceWrapperMap(serviceWrappers);

        List<ServiceWrapper> enrichedServiceWrappers = new ArrayList<>();

        for(String tenantId : tenantIdToServiceWrapperMap.keySet()) {

            List<String> serviceRequestIds = new ArrayList<>();

            List<ServiceWrapper> tenantSpecificWrappers = tenantIdToServiceWrapperMap.get(tenantId);

            tenantSpecificWrappers.forEach(pgrEntity -> {
                serviceRequestIds.add(pgrEntity.getService().getServiceRequestId());
            });

            // was: HTTP POST wf/process/_search?tenantId=..&businessIds=..  -> now in-process
            org.egov.wf.web.models.ProcessInstanceSearchCriteria criteria =
                    new org.egov.wf.web.models.ProcessInstanceSearchCriteria();
            criteria.setTenantId(tenantId);
            criteria.setBusinessIds(serviceRequestIds);

            List<org.egov.wf.web.models.ProcessInstance> wfProcessInstances =
                    wfWorkflowService.search(requestInfo, criteria);

            ProcessInstanceResponse processInstanceResponse;
            try {
                List<ProcessInstance> processInstances =
                        mapper.convertValue(wfProcessInstances, new TypeReference<List<ProcessInstance>>() {});
                processInstanceResponse = ProcessInstanceResponse.builder().processInstances(processInstances).build();
            } catch (IllegalArgumentException e) {
                throw new CustomException("PARSING ERROR", "Failed to parse response of workflow processInstance search");
            }

            if (CollectionUtils.isEmpty(processInstanceResponse.getProcessInstances()) || processInstanceResponse.getProcessInstances().size() != serviceRequestIds.size())
                throw new CustomException("WORKFLOW_NOT_FOUND", "The workflow object is not found");

            Map<String, Workflow> businessIdToWorkflow = getWorkflow(processInstanceResponse.getProcessInstances());

            tenantSpecificWrappers.forEach(pgrEntity -> {
                pgrEntity.setWorkflow(businessIdToWorkflow.get(pgrEntity.getService().getServiceRequestId()));
            });

            enrichedServiceWrappers.addAll(tenantSpecificWrappers);
        }

        return enrichedServiceWrappers;

    }

    private Map<String, List<ServiceWrapper>> getTenantIdToServiceWrapperMap(List<ServiceWrapper> serviceWrappers) {
        Map<String, List<ServiceWrapper>> resultMap = new HashMap<>();
        for(ServiceWrapper serviceWrapper : serviceWrappers){
            if(resultMap.containsKey(serviceWrapper.getService().getTenantId())){
                resultMap.get(serviceWrapper.getService().getTenantId()).add(serviceWrapper);
            }else{
                List<ServiceWrapper> serviceWrapperList = new ArrayList<>();
                serviceWrapperList.add(serviceWrapper);
                resultMap.put(serviceWrapper.getService().getTenantId(), serviceWrapperList);
            }
        }
        return resultMap;
    }

    /**
     * Enriches ProcessInstance Object for workflow
     *
     * @param request
     */
    private ProcessInstance getProcessInstanceForPGR(ServiceRequest request) {

        Service service = request.getService();
        Workflow workflow = request.getWorkflow();

        ProcessInstance processInstance = new ProcessInstance();
        processInstance.setBusinessId(service.getServiceRequestId());
        processInstance.setAction(request.getWorkflow().getAction());
        processInstance.setModuleName(PGR_MODULENAME);
        processInstance.setTenantId(service.getTenantId());
        processInstance.setBusinessService(getBusinessService(request).getBusinessService());
        processInstance.setDocuments(request.getWorkflow().getVerificationDocuments());
        processInstance.setComment(workflow.getComments());

        if(!CollectionUtils.isEmpty(workflow.getAssignes())){
            List<User> users = new ArrayList<>();

            workflow.getAssignes().forEach(uuid -> {
                User user = new User();
                user.setUuid(uuid);
                users.add(user);
            });

            processInstance.setAssignes(users);
        }

        return processInstance;
    }

    /**
     *
     * @param processInstances
     */
    public Map<String, Workflow> getWorkflow(List<ProcessInstance> processInstances) {

        Map<String, Workflow> businessIdToWorkflow = new HashMap<>();

        processInstances.forEach(processInstance -> {
            List<String> userIds = null;

            if(!CollectionUtils.isEmpty(processInstance.getAssignes())){
                userIds = processInstance.getAssignes().stream().map(User::getUuid).collect(Collectors.toList());
            }

            Workflow workflow = Workflow.builder()
                    .action(processInstance.getAction())
                    .assignes(userIds)
                    .comments(processInstance.getComment())
                    .verificationDocuments(processInstance.getDocuments())
                    .build();

            businessIdToWorkflow.put(processInstance.getBusinessId(), workflow);
        });

        return businessIdToWorkflow;
    }

    /**
     * In-process process-instance search (replaces the former HTTP {@code /process/_search}).
     * Used by NotificationService (with history=true) to read the workflow timeline of a complaint.
     */
    public ProcessInstanceResponse searchProcessInstance(RequestInfo requestInfo, String tenantId,
                                                         String serviceRequestId, boolean history) {
        org.egov.wf.web.models.ProcessInstanceSearchCriteria criteria =
                new org.egov.wf.web.models.ProcessInstanceSearchCriteria();
        criteria.setTenantId(tenantId);
        criteria.setBusinessIds(Collections.singletonList(serviceRequestId));
        criteria.setHistory(history);
        List<org.egov.wf.web.models.ProcessInstance> wfProcessInstances =
                wfWorkflowService.search(requestInfo, criteria);
        List<ProcessInstance> processInstances =
                mapper.convertValue(wfProcessInstances, new TypeReference<List<ProcessInstance>>() {});
        return ProcessInstanceResponse.builder().processInstances(processInstances).build();
    }

    /**
     * Method to integrate with workflow
     * <p>
     * takes the ProcessInstanceRequest, maps it to the workflow module's request, calls the
     * workflow module's transition() in-process and maps the result back to PGR's response.
     */
    private ProcessInstanceResponse callWorkFlow(ProcessInstanceRequest workflowReq) {
        org.egov.wf.web.models.ProcessInstanceRequest wfRequest =
                mapper.convertValue(workflowReq, org.egov.wf.web.models.ProcessInstanceRequest.class);
        List<org.egov.wf.web.models.ProcessInstance> wfProcessInstances = wfWorkflowService.transition(wfRequest);
        List<ProcessInstance> processInstances =
                mapper.convertValue(wfProcessInstances, new TypeReference<List<ProcessInstance>>() {});
        return ProcessInstanceResponse.builder().processInstances(processInstances).build();
    }


}
