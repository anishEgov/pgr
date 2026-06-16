# Local Setup

To setup the pgr-services service in your local system, clone the [Citizen-Complaint-Resolution-System repository](https://github.com/egovernments/Citizen-Complaint-Resolution-System).

## Dependencies

### Infra Dependency

- [x] Postgres DB
- [ ] Redis
- [ ] Elasticsearch
- [x] Kafka
  - [x] Consumer
  - [x] Producer

## Running Locally

To run the pgr services in local system, you need to port forward below services.

```bash
 kubectl port-forward -n egov {egov-idgen} 8085:8080
 kubectl port-forward -n egov {egov-mdms} 8083:8080
 kubectl port-forward -n egov {egov-user} 8081:8080
 kubectl port-forward -n egov {egov-workflow-v2} 8282:8080
 kubectl port-forward -n egov {egov-localization} 8087:8080
 kubectl port-forward -n egov {egov-url-shortner} 8092:8080
 kubectl port-forward -n egov {egov-hrms} 8093:8080

```

Update below listed properties in `application.properties` before running the project:

```ini

-spring.datasource.url=jdbc:postgresql://localhost:5432/{local postgres db name}

-spring.flyway.url=jdbc:postgresql://localhost:5432/{local postgres db name}

-egov.mdms.host={mdms hostname}

-egov.user.host = {user service hostname}

-egov.idgen.host = {Id Gen service hostname}

-egov.localization.host = {Localization service hostname}

-egov.hrms.host = {HRMS service hostname}

-egov.url.shortner.host = {URL Shortening service hostname}

-egov.workflow.host = {Workflow service hostname}


```