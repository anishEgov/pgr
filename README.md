# PGR Spring Modulith — Run & API Guide

This repo contains the DIGIT PGR stack converted from **microservices** into a single
**Spring Modulith** application (`application/`). The original service folders
(`pgr-services`, `egov-workflow-v2`, `idgen`, `localization`, `mdms-v2`, `egov-user`,
`egov-persister`) are kept as the **microservice reference**. See `TRANSFORMATION.md` for the
full transformation log (decisions, code changes, build errors, runtime/e2e tests).

- **One app, peer modules** (no "main"): `org.egov.{pgr,id,wf,mdmsv2,localization}` each keep
  their **original API path** (idgen at `/egov-idgen`, workflow at `/egov-workflow-v2`, …).
- **Sync** cross-module calls (pgr→idgen/mdms/workflow) are **in-process** bean calls.
- **Async** persistence is unchanged: modules **produce to Kafka**, the **separate `egov-persister`**
  consumes and writes the shared DB.
- `egov-user` stays an external HTTP dependency (Spring Boot 1.5 / Java 8) reached via port-forward.

---

## 1. Run it

```bash
# JDK 17 is required (the repo targets Java 17; JDK 25 breaks Lombok's processor)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# 1) Infra: Postgres (5433) + Redis (6379) + Kafka (9092)
cd application && docker compose up -d postgres redis kafka

# 2) Seed schema for every module into the ONE platform DB (per-module flyway history table)
B=application/src/main/resources/db/migration
flyway -url=jdbc:postgresql://localhost:5433/platform -user=postgres -password=postgres -table=pgr_flyway_history          -locations=filesystem:$B/pgr           -baselineOnMigrate=true migrate
flyway -url=jdbc:postgresql://localhost:5433/platform -user=postgres -password=postgres -table=idgen_flyway_history        -locations=filesystem:$B/idgen/main,filesystem:$B/idgen/seed -baselineOnMigrate=true -outOfOrder=true migrate
flyway -url=jdbc:postgresql://localhost:5433/platform -user=postgres -password=postgres -table=mdmsv2_flyway_history       -locations=filesystem:$B/mdmsv2/main   -baselineOnMigrate=true migrate
flyway -url=jdbc:postgresql://localhost:5433/platform -user=postgres -password=postgres -table=localization_flyway_history -locations=filesystem:$B/localization/ddl -baselineOnMigrate=true migrate
flyway -url=jdbc:postgresql://localhost:5433/platform -user=postgres -password=postgres -table=workflow_flyway_history     -locations=filesystem:$B/workflow/main -baselineOnMigrate=true migrate

# 3) egov-persister (separate, multi-module configs). Offline here we run its prebuilt image
#    standalone with the configs FOLDER mounted (plain path, not file://):
docker run -d --name platform-persister --network application_default -p 8090:8080 \
  -v $PWD/../egov-persister/src/main/resources/persister-configs:/configs:ro \
  -e SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/platform \
  -e SPRING_DATASOURCE_USERNAME=postgres -e SPRING_DATASOURCE_PASSWORD=postgres \
  -e KAFKA_CONFIG_BOOTSTRAP_SERVER_CONFIG=kafka:29092 -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092 \
  -e SPRING_KAFKA_CONSUMER_GROUP_ID=platform-persister -e SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=earliest \
  -e EGOV_PERSIST_YML_REPO_PATH=/configs  egovio/egov-persister:master-3b238aa

# 4) The modulith (serves all modules on :8280, no global context path)
cd application && mvn -o clean package -DskipTests && java -jar target/application-3.0.0.jar

# 5) Port-forward the external dep (egov-user) the modulith calls over HTTP
kubectl -n egov port-forward svc/egov-user 8081:8080
```

> **Note on responses:** the eGov tracer wraps responses (even errors) as **HTTP 200** with a status
> body, so judge success by the body, not the status code.
>
> The PGR `_create` examples use a real EMPLOYEE auth token/user for tenant `dev` — replace
> `<AUTH_TOKEN>` and `userInfo` with your own. `<AUTH_TOKEN>` is also accepted on the other calls.

---

## 2. Working API calls (request + response)

### 2.0 (setup) create a test user — `POST /user/users/_createnovalidate`
PGR's create flow needs a citizen/user. Create a dedicated **test** user via the port-forwarded
`egov-user` (`:8081`). Give it a role allowed by the workflow `CREATE` action (e.g.
`SYSTEM_ADMINISTRATOR`). Replace `<AUTH_TOKEN>` / `<REQUESTER_UUID>` with a valid admin's token/uuid.
```bash
curl -s -X POST http://localhost:8081/user/users/_createnovalidate \
  -H "Content-Type: application/json" -d '{
  "RequestInfo":{"apiId":"Rainmaker","authToken":"<AUTH_TOKEN>","msgId":"1761036073664|en_IN",
    "userInfo":{"uuid":"<REQUESTER_UUID>","type":"EMPLOYEE","tenantId":"dev",
      "roles":[{"code":"SYSTEM_ADMINISTRATOR","tenantId":"dev"}]},"plainAccessRequest":{}},
  "user":{"userName":"SUPERUSER_1","name":"Admin User 2","mobileNumber":"9898989899","type":"EMPLOYEE",
    "active":true,"password":"eGov@1234","emailId":"xyz@gmail.com","tenantId":"dev",
    "roles":[{"name":"Super User","code":"SUPERUSER","tenantId":"dev"},
             {"name":"System Administrator","code":"SYSTEM_ADMINISTRATOR","tenantId":"dev"},
             {"name":"HELPDESK USER","code":"HELPDESK_USER","tenantId":"dev"},
             {"name":"Distributor","code":"DISTRIBUTOR","tenantId":"dev"}]}}'
```
```json
{"responseInfo":{"status":"200"},
 "user":[{"id":39179,"uuid":"7b588c39-44ee-4c1b-93ef-69830f74368d","userName":"SUPERUSER_1",
   "name":"Admin User 2","mobileNumber":"9898989899","type":"EMPLOYEE","tenantId":"dev","active":true,
   "roles":[{"code":"SUPERUSER"},{"code":"SYSTEM_ADMINISTRATOR"},{"code":"HELPDESK_USER"},{"code":"DISTRIBUTOR"}]}]}
```
Use the returned `uuid` (`7b588c39-…`) as `userInfo.uuid` in the PGR create below.

### 2.1 idgen — `POST /egov-idgen/id/_generate`
```bash
# (one-time) the format references a DB sequence; idgen auto-creates it, or pre-create:
#   docker exec platform-postgres psql -U postgres -d platform -c "CREATE SEQUENCE IF NOT EXISTS SEQ_DEMO_TEST;"
curl -s -X POST http://localhost:8280/egov-idgen/id/_generate \
  -H "Content-Type: application/json" -d '{
    "RequestInfo":{"apiId":"x","ver":"1","ts":1},
    "idRequests":[{"idName":"demo.id","tenantId":"dev","format":"DEMO-[SEQ_DEMO_TEST]"}]
  }'
```
```json
{"responseInfo":{"apiId":"x","ver":"1","ts":1,"resMsgId":"uief87324","status":"SUCCESSFUL"},
 "idResponses":[{"id":"DEMO-000001"}]}
```

### 2.2 mdms — `POST /mdms-v2/v1/_search`
```bash
curl -s -X POST http://localhost:8280/mdms-v2/v1/_search \
  -H "Content-Type: application/json" -d '{
    "RequestInfo":{"apiId":"x","ver":"1","ts":1},
    "MdmsCriteria":{"tenantId":"dev","moduleDetails":[{"moduleName":"RAINMAKER-PGR","masterDetails":[{"name":"ServiceDefs"}]}]}
  }'
```
```json
{"ResponseInfo":null,"MdmsRes":{}}
```
*(empty `MdmsRes` because no MDMS master data is seeded locally; the endpoint/handler works.)*

### 2.3 localization — `POST /localization/messages/v1/_search`
```bash
# seed a message (direct, for demo):
#   docker exec platform-postgres psql -U postgres -d platform -c \
#     "insert into message(id,locale,code,message,tenantid,module,createdby,createddate) \
#      values('msg-1','en_IN','PGR_DEMO_MSG','Demo complaint message','dev','rainmaker-common',1,now());"
# localization caches in Redis — flush after seeding: docker exec platform-redis redis-cli FLUSHALL
curl -s -X POST "http://localhost:8280/localization/messages/v1/_search?tenantId=dev&locale=en_IN&module=rainmaker-common" \
  -H "Content-Type: application/json" -d '{"RequestInfo":{"apiId":"x","ver":"1","ts":1}}'
```
```json
{"messages":[{"code":"PGR_DEMO_MSG","message":"Demo complaint message","module":"rainmaker-common","locale":"en_IN"}]}
```

### 2.4 workflow — businessservice `POST /egov-workflow-v2/egov-wf/businessservice/_search`
```bash
curl -s -X POST "http://localhost:8280/egov-workflow-v2/egov-wf/businessservice/_search?tenantId=dev&businessServices=PGR" \
  -H "Content-Type: application/json" -d '{"RequestInfo":{"apiId":"Rainmaker","ver":".01","ts":1234,"authToken":"<AUTH_TOKEN>"}}'
```
```json
{"ResponseInfo":{"status":"successful"},
 "BusinessServices":[{"tenantId":"dev","businessService":"PGR","business":"pgr-services",
   "states":[{"state":null,"actions":[{"action":"CREATE","nextState":"...PENDING_ASSIGNMENT uuid..."}]},
             {"state":"PENDING_ASSIGNMENT","applicationStatus":"PENDING_ASSIGNMENT","actions":[...]}, ...]}]}
```

### 2.5 workflow — process `POST /egov-workflow-v2/egov-wf/process/_search`
```bash
curl -s -X POST "http://localhost:8280/egov-workflow-v2/egov-wf/process/_search?tenantId=dev&businessIds=PB-PGR-2026-06-17-000007" \
  -H "Content-Type: application/json" -d '{"RequestInfo":{"apiId":"Rainmaker","ver":".01","ts":1234,"authToken":"<AUTH_TOKEN>"}}'
```
```json
{"ResponseInfo":null,
 "ProcessInstances":[{"id":"a0c09c89-160e-40c6-a123-c81a3bd0444f","tenantId":"dev","businessService":"PGR",
   "businessId":"PB-PGR-2026-06-17-000007","action":"CREATE","moduleName":"pgr-services",
   "state":{"state":"PENDING_ASSIGNMENT","applicationStatus":"PENDING_ASSIGNMENT"}}]}
```

### 2.6 PGR create — `POST /pgr-services/v2/request/_create`  ← produces `save-pgr-request` → persister
```bash
curl -s -X POST http://localhost:8280/pgr-services/v2/request/_create \
  -H "Content-Type: application/json" -d '{
  "RequestInfo":{"apiId":"Rainmaker","ver":".01","ts":1234,"action":"_create","msgId":"20170310130900|en_IN",
    "authToken":"<AUTH_TOKEN>",
    "userInfo":{"id":39179,"uuid":"7b588c39-44ee-4c1b-93ef-69830f74368d","userName":"SUPERUSER_1","name":"Admin User 2",
      "mobileNumber":"9898989899","type":"EMPLOYEE","tenantId":"dev",
      "roles":[{"code":"SYSTEM_ADMINISTRATOR","tenantId":"dev"}]}},
  "service":{"tenantId":"dev","serviceCode":"StreetLightNotWorking","description":"modulith e2e","source":"web",
    "citizen":{"name":"Admin User 2","mobileNumber":"9898989899","type":"CITIZEN","tenantId":"dev"},
    "address":{"tenantId":"dev","doorNo":"12","plotNo":"7","buildingName":"Block A","street":"MG Road",
      "landmark":"Near Park","city":"CityA","pincode":"560001","locality":{"code":"SUN01"},"district":"DistA",
      "region":"RegionA","state":"StateA","country":"India","geoLocation":{"latitude":12.97,"longitude":77.59},
      "additionDetails":{},"additionalDetail":{}}},
  "workflow":{"action":"CREATE"}}'
```
```json
{"ResponseInfo":{"status":"successful"},
 "ServiceWrappers":[{"service":{"serviceRequestId":"PB-PGR-2026-06-17-000007","tenantId":"dev",
   "serviceCode":"StreetLightNotWorking","applicationStatus":"PENDING_ASSIGNMENT",
   "id":"56632d29-331f-4498-89bb-2bafa2b3a5b3","accountId":"...","citizen":{...}},
   "workflow":{"action":"CREATE"}}]}
```
**Async result** — the modulith pushed `save-pgr-request`; the persister consumed it and wrote the DB:
```sql
select servicerequestid, tenantid, servicecode, applicationstatus from eg_pgr_service_v2;
--  PB-PGR-2026-06-17-000007 | dev | StreetLightNotWorking | PENDING_ASSIGNMENT
```

### 2.7 PGR search — `POST /pgr-services/v2/request/_search`
```bash
curl -s -X POST "http://localhost:8280/pgr-services/v2/request/_search?tenantId=dev" \
  -H "Content-Type: application/json" -d '{"RequestInfo":{"apiId":"Rainmaker","ver":".01","ts":1234,"authToken":"<AUTH_TOKEN>",
    "userInfo":{"uuid":"7b588c39-44ee-4c1b-93ef-69830f74368d","type":"EMPLOYEE","tenantId":"dev",
      "roles":[{"code":"SYSTEM_ADMINISTRATOR","tenantId":"dev"}]}}}'
```
```json
{"responseInfo":{"status":"successful"},
 "ServiceWrappers":[{"service":{"active":true,"serviceRequestId":"PB-PGR-2026-06-17-000007","tenantId":"dev",
   "applicationStatus":"PENDING_ASSIGNMENT","citizen":{"id":39179,"userName":"SUPERUSER_1","name":"Admin User 2"}},
   "workflow":{...}}]}
```

---

## 3. Prerequisites / data needed for a real PGR create
- **idgen**: `application.properties` has `idformat.from.mdms=false` + `autocreate.request.seq=true`
  (use PGR's own format, auto-create the sequence).
- **user**: a citizen reachable via the port-forwarded `egov-user`, and a requester role allowed by
  the workflow `CREATE` action (e.g. `SYSTEM_ADMINISTRATOR`).
- **workflow**: the `PGR` businessservice must exist locally — seed it via
  `POST /egov-workflow-v2/egov-wf/businessservice/_create` (this itself flows modulith → Kafka
  `save-wf-businessservice` → persister → `eg_wf_*`). When seeding, send `nextState` as state **names**.
- **mdms**: validation is disabled in PGR code (`validateMDMS` commented out), so no PGR master data
  is required for create.