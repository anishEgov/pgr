package org.egov.mdmsv2.repository;

import org.egov.mdmsv2.model.SchemaDefCriteria;
import org.egov.mdmsv2.model.SchemaDefinition;
import org.egov.mdmsv2.model.SchemaDefinitionRequest;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SchemaDefinitionRepository {
    public void create(SchemaDefinitionRequest schemaDefinitionRequest);

    public void update(SchemaDefinitionRequest schemaDefinitionRequest);

    public List<SchemaDefinition> search(SchemaDefCriteria schemaDefCriteria);

}
