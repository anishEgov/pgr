package org.egov.pgr.repository;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.contract.request.RequestInfo;
import org.egov.id.service.IdGenerationService;
import org.egov.pgr.web.models.Idgen.IdGenerationResponse;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter from the {@code pgr} module to the {@code idgen} module.
 *
 * <p><b>Modulith change (Phase 1, D5):</b> previously this used a {@link
 * org.springframework.web.client.RestTemplate} HTTP POST to {@code egov.idgen.host}. Now idgen
 * lives in the same process as the {@code org.egov.id} module, so we inject its published
 * {@link IdGenerationService} bean and call it directly — no network, no serialization, same
 * transaction. The HTTP latency, timeout handling and {@code ServiceCallException} path are gone.
 *
 * <p>idgen carries its own contract model ({@code org.egov.id.model.*}), distinct from PGR's
 * ({@code org.egov.pgr.web.models.Idgen.*}). Rather than couple PGR's domain to idgen's types,
 * we translate at this single boundary with {@link ObjectMapper#convertValue} (both sides share
 * the same JSON contract), so {@code getId(...)}'s signature and every caller stay unchanged.
 *
 * <p><b>Microservice extraction seam:</b> to put idgen back on the network, replace the injected
 * {@link IdGenerationService} with an HTTP-backed implementation of the same type — nothing else
 * in PGR changes.
 */
@Repository
public class IdGenRepository {

    private final IdGenerationService idGenerationService;

    private final ObjectMapper mapper;

    @Autowired
    public IdGenRepository(IdGenerationService idGenerationService, ObjectMapper mapper,
                           org.egov.id.config.PropertiesManager idgenInternalConfig) {
        this.idGenerationService = idGenerationService;
        this.mapper = mapper;
        // ⚠️ DEMO ONLY (branch demo/modulith-verify-fails): this class is in the PGR module
        // (org.egov.pgr.repository) and here calls a function on IDGEN's INTERNAL config
        // (org.egov.id.config — NOT exposed; only id.service + id.model are published via
        // @NamedInterface). Compiles fine (PropertiesManager is public), but cross-module access
        // into a non-exposed package makes ApplicationModules.verify() FAIL the build:
        //   "Module 'pgr' depends on non-exposed type org.egov.id.config.PropertiesManager ..."
        idgenInternalConfig.getIdGenerationTable();
    }


    /**
     * Generate ids via the in-process idgen module.
     * @param requestInfo The requestInfo of the request
     * @param tenantId The tenantId of the service request
     * @param name Name of the format
     * @param format Format of the ids
     * @param count Total Number of idGen ids required
     * @return ids wrapped in PGR's own IdGenerationResponse contract
     */
    public IdGenerationResponse getId(RequestInfo requestInfo, String tenantId, String name, String format, int count) {

        List<org.egov.id.model.IdRequest> reqList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            org.egov.id.model.IdRequest idRequest = new org.egov.id.model.IdRequest();
            idRequest.setIdName(name);
            idRequest.setFormat(format);
            idRequest.setTenantId(tenantId);
            reqList.add(idRequest);
        }

        org.egov.id.model.IdGenerationRequest req = new org.egov.id.model.IdGenerationRequest();
        req.setRequestInfo(mapper.convertValue(requestInfo, org.egov.id.model.RequestInfo.class));
        req.setIdRequests(reqList);

        try {
            org.egov.id.model.IdGenerationResponse response = idGenerationService.generateIdResponse(req);
            return mapper.convertValue(response, IdGenerationResponse.class);
        } catch (Exception e) {
            Map<String, String> map = new HashMap<>();
            map.put("IDGEN_ERROR", e.getMessage());
            throw new CustomException(map);
        }
    }


}