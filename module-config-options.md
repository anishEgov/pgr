# Module-specific config values in a single `application.properties`

The modulith has **one** `application.properties`. That's one **flat key namespace**, so a given key
string can hold only **one** value. "A module-specific value" therefore means one of two things:

1. give each module a **different key** (namespacing) — for keys *you* define (Kafka **topics**,
   limits, hosts, …), **or**
2. if the key must be the **literal same** (a framework key you can't rename, e.g. a Kafka consumer
   group), resolve it **per-bean in code**.

Below are all the options, ranked, with when to use each. (Nothing here is wired into the code — it's
the menu of approaches; see the Kafka section for the concrete recommendation.)

---

## Option 1 — Namespace the key per module  *(default, recommended)*
Each module reads its **own prefixed key**.
```properties
# application.properties — one file, distinct keys per module
egov.pgr.kafka.create.topic=save-pgr-request
egov.wf.persister.save.transition.topic=save-wf-transitions
egov.mdms.data.save.topic=save-mdms-data
```
```java
@Value("${egov.pgr.kafka.create.topic}") String pgrCreateTopic;     // pgr
@Value("${egov.wf.persister.save.transition.topic}") String wfTopic; // wf
```
Already the dominant pattern in the merged file (`egov.idgen.*`, `egov.wf.*`, `mdms.*`).
**Best for: Kafka topic names and any key you own.**

## Option 2 — `@ConfigurationProperties(prefix = "…")` per module
Same idea, typed and grouped (no scattered `@Value`, no key typos):
```java
@ConfigurationProperties(prefix = "egov.idgen")   // binds egov.idgen.*
class IdgenProps { private String createTopic; /* getters/setters */ }
```
**Best for: a module with several related settings.**

## Option 3 — Separate per-module files (organizational split, same runtime effect)
Keep keys namespaced but put each module's keys in its own file:
- **(a) central import** in `application.properties`:
  ```properties
  spring.config.import=classpath:config/idgen.properties,classpath:config/wf.properties
  ```
- **(b) module-owned**: `@PropertySource("classpath:config/idgen.properties")` on the module's
  `@Configuration` class.

All keys still land in one `Environment`; this only declutters the file / gives each module ownership
of its slice. **Best for: tidiness once the merged file gets large.**

## Option 4 — Profiles (`application-<module>.properties`)
Possible, but profiles are meant for **environments** (dev/prod), not "always-on per module." Use only
if a module's values must change **per environment**, not to separate modules.

## Option 5 — Per-bean / per-component config in code  *(the hard case)*
When two modules need different values for the **literal same framework key** that you can't rename —
e.g. `spring.kafka.consumer.group-id` (Spring applies it to **all** consumers). Namespacing can't
help; set it **per component**:
```java
@KafkaListener(topics = "${pgr.kafka.create.topic}",
               groupId = "${egov.pgr.kafka.consumer.group-id}")          // pgr's consumers
@KafkaListener(topics = "${persister.save.businessservice.wf.topic}",
               groupId = "${egov.wf.kafka.consumer.group-id}")           // wf's consumer
```
…or define a **dedicated bean per module** (its own `KafkaTemplate` / listener `ContainerFactory` with
its own serializers/group) and have each module inject its own.
**Best for: a framework key shared by all modules that two modules need to differ on.**

---

## Applied to Kafka, concretely

| Kafka setting | Option | Why |
|---|---|---|
| **Topic name** (your key) | **1 / 2** — namespace per module (`egov.<module>.*.topic`) | it's your key; just give each module its own. |
| **Consumer group-id** (framework key, one global default) | **5** — `groupId` per `@KafkaListener` | `spring.kafka.consumer.group-id` is a single global key. |
| **Different serializers / bootstrap per module** | **5** — separate `KafkaTemplate` / listener-factory beans | one global factory can't hold two configs. |

### The real collision in *this* repo (for the demo)
The merged `application.properties` sets the **same** key twice:
```
spring.kafka.consumer.group-id=egov-pgr-services    # line 10  (pgr wanted this)
spring.kafka.consumer.group-id=egov-wf-services      # line 116 (wf wanted this)
```
A flat key is **last-wins**, so *all* consumers end up in `egov-wf-services`. And none of the
`@KafkaListener`s override `groupId`, so they genuinely share one group.

**Recommended fix (Option 5)** — give each module its own group, in code, from its own namespaced key:
```properties
# application.properties
egov.pgr.kafka.consumer.group-id=egov-pgr-services
egov.wf.kafka.consumer.group-id=egov-wf-services
```
```java
// org.egov.pgr.consumer.* (pgr's listeners)
@KafkaListener(topics = {"${pgr.kafka.migration.topic}"},        groupId = "${egov.pgr.kafka.consumer.group-id}")
@KafkaListener(topicPattern = "${pgr.kafka.notification.topic.pattern}", groupId = "${egov.pgr.kafka.consumer.group-id}")

// org.egov.wf.producer.consumer (wf's listener)
@KafkaListener(topics = {"${persister.save.businessservice.wf.topic}"}, groupId = "${egov.wf.kafka.consumer.group-id}")
```
Result: pgr's consumers join group `egov-pgr-services`, wf's joins `egov-wf-services` — module-specific
values even though the underlying framework key is global. (Documented here as the approach; not wired
into the code.)

---

## Rule of thumb
- **Your key** → **namespace it** (Option 1; add 2/3 for typing/tidiness).
- **Framework key you can't rename, two modules differ** → **configure per-bean in code** (Option 5).
- A flat single key is correct only when every module genuinely wants the **same** value
  (`app.timezone`, kafka bootstrap, etc.).
