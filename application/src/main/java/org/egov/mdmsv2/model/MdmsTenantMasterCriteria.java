package org.egov.mdmsv2.model;

import lombok.*;

@Setter
@Getter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MdmsTenantMasterCriteria {

    private String tenantId;

    private String schemaCode;

}
