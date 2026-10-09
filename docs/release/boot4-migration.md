# Spring Boot 4 migration / Boot 4 迁移（clear P1 spring-webmvc CRITICAL）

Goal: clear Trivy CRITICAL on `spring-webmvc` 6.2.19 (CVE-2026-47884 / CVE-2026-47890) by moving to Spring Framework **7.0.9** via Spring Boot **4.0.x**. No `v0.1.0-alpha.1` tag until image-scan is clean.

目标：升到 Boot 4 / Framework 7.0.9，清掉 spring-webmvc CRITICAL。image-scan 全绿前不打 alpha tag。

## Inventory (2026-10-09) / 盘点

| Item | Before Boot4-1 |
|------|----------------|
| Parent | Spring Boot **3.5.16** (last OSS 3.5) |
| Managed Framework | 6.2.19 → need **7.0.9** |
| Jackson | 2.21.7 (`com.fasterxml.jackson.*`); **18** Java files |
| Testcontainers | 1.x artifacts `junit-jupiter` / `mysql` / `postgresql` → Boot 4 BOM is **2.0.5** with `testcontainers-*` names |
| Overrides on 3.5 | `tomcat.version=10.1.60`, `jackson-bom.version=2.21.7`, `postgresql.version=42.7.14` |
| Tip at start | `ed4732b` (deps bump; image-scan still red on spring-webmvc only) |

### Jackson files (package migrate `com.fasterxml.jackson` → `tools.jackson`, keep `com.fasterxml.jackson.annotation`)

- platform-app main: `PlatformWiring`, `JdbcFormSubmissionStore`, `JdbcTaskMessagePort`, `HttpOidcTokenClient`
- platform-app test: `OpenApiDocumentTest`, `AiSummarizePreviewConfirmE2ETest`, `CapabilityApiSecurityTest`, `ConfigETagHttpTest`, `OutboxRelayTest`, `JdbcFormSubmissionStoreTest`, `HttpBasicDisabledSecurityTest`, `OperatorMfaSecurityTest`, `OperatorOidcSecurityTest`, `OperatorTokenAuthSecurityTest`
- sample-consumer: `ConsumerWiring`, `OutboxSocketListener`, `CrossApplicationDeliveryTest`, `OutboxTlsDeliveryTest`

## Slices / 切片

| Slice | Scope | Exit |
|-------|--------|------|
| **Boot4-1** | Plan + Boot **4.0.8**; testcontainers 2.x; Jackson 3; Boot 4 package/API fixes | **DONE** local tests green (ex VendorStartup); push |
| **Boot4-2** | `spring-boot-starter-flyway` for MigrationGuard; `tomcat.version=11.0.26`; `jackson-bom.version=3.1.7`; `jackson-2-bom.version=2.21.7`; springdoc **3.1.1** | local tests + CI test/image-scan |
| **Boot4-3** | Confirm image-scan green enough for alpha; checklist | alpha tag (owner only) |

## Known probes (already failed) / 已知试探

- Framework 7 overlay on Boot 3.5: compiles, tests `NoSuchMethodError` in `SpringExtension`.
- Boot 4.0.8 without testcontainers rename: POM cannot resolve `junit-jupiter` / `mysql` / `postgresql` modules.

## Non-goals for Boot4-1

- Alpha tag
- Full OpenRewrite automation (manual small diffs preferred for review)
- Boot 4.1.x (stay on latest **4.0.8** unless 4.0 line is insufficient)

## Status / 状态（2026-10-09）

**Boot4-1 DONE locally:** parent **4.0.8**; testcontainers 2.x artifactIds; Jackson 3 (`tools.jackson`); Boot 4 package moves (web server, security, WebMvcTest, ServerProperties, …); `spring.jackson.datatype.datetime.write-dates-as-timestamps`; Kafka `MockProducer` + Partitioner; sample-consumer autoconfigure.exclude FQCNs.  
`mvn test -Dsurefire.excludes=**/VendorStartupTest.java` → **BUILD SUCCESS**. VendorStartup still needs Docker on CI.

**Boot4-2 DONE locally (2026-10-09):** CI on Boot4-1 tip was red — VendorStartup missing Flyway bean; Trivy Tomcat 11.0.24 CRITICAL + Jackson HIGH (spring-webmvc CRITICAL cleared). Fixes: `spring-boot-starter-flyway` (MigrationGuard); property overrides `tomcat.version=11.0.26`, `jackson-bom.version=3.1.7`, `jackson-2-bom.version=2.21.7` (flyway/swagger leftover Jackson 2); springdoc **3.1.1** (Boot 4). Next = squash-push → CI; alpha tag only when image-scan green (owner). **No tag this slice.**

