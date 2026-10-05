# Node 6 progress — 节点六进度

Scope (user chose D): multi-form catalog, submission store + history, second form (config-override), FormRecordGenerator CLI. No designer.

Slice log — 切片记录:

6a. [done] ARCHITECTURE §15 + this progress file.
6b. [done] Flyway `form_submission` + catalog loads all `*.form.yaml`; submit persists; list API.
6c. [done] `config-override.form.yaml` + submit path + console form picker.
6d. [done] CLI: regenerate checked-in records from YAML.
6e. [done] Pushed as remote `275fbfd` (on top of `9e62f95`).

## 6b notes

- `V4__form_submission.sql`: shared submission history table.
- `FormCatalog` indexes every `classpath*:forms/*.form.yaml`; `publication()` = `require("endpoint-publication")`.
- `FormSubmissionStore` / `JdbcFormSubmissionStore` save + list by formKey.
- APIs: `GET /api/v1/forms` index `{forms:[...]}`; `GET /api/v1/forms/{formKey}` detail; POST submit returns `submissionId`; `GET .../submissions` history.
- POST submit is `.authenticated()`; write permission checked per form in the endpoint (`registry.write` for endpoint-publication).

## 6c notes

- Second form `config-override` (configKey + configValue); submit needs `config.write`, calls `ConfigCatalog.override`, audits `config.override`.
- Checked-in `ConfigOverride.java` record.
- Console forms page: form picker from index API, detail by `?form=`; write permission depends on formKey.
- `web/openapi.json` + `npm run gen:api` updated for index/detail/history/result shapes.

## 6d notes — FormRecordGenerator CLI

Main class: `com.subjex.form.render.FormRecordWriteMain`

```bash
mvn -o -pl form-render -q -DskipTests package
SNAKE=$(ls ~/.m2/repository/org/yaml/snakeyaml/*/snakeyaml-*.jar | tail -1)
java -cp "form-render/target/form-render-0.1.0-SNAPSHOT.jar:$SNAKE" \
  com.subjex.form.render.FormRecordWriteMain \
  form-render/src/main/resources/forms/endpoint-publication.form.yaml \
  form-render/src/main/java/com/subjex/form/generated/EndpointPublication.java
```

Optional third arg: package name (default `com.subjex.form.generated`). Caller checks in the file; build does not run an annotation processor.
