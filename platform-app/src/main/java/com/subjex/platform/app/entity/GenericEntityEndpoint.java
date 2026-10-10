package com.subjex.platform.app.entity;

import com.subjex.entity.declare.EntityStorageMode;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.declaration.TenantDeclarationContext;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GenericEntityEndpoint — 通用实体 REST：按 {@code entityKey} 与 {@code storageMode} 路由 CRUD。
 * <p>
 * Paths under {@code /api/v1/entities/{entityKey}/records}. Routes {@code storageMode=table} to
 * {@link GenericEntityStore} and {@code hybrid} to {@link HybridEntityStore}; record delete cascades {@link EntityBlobStore} (ES-3). Large payloads
 * stay on {@link EntityBlobStore}, not in attrs. When {@code tenantScoped: true}, requires
 * {@code X-Tenant-Id} (and operator–tenant grant).
 * 路径在 {@code /api/v1/entities/{entityKey}/records}。table 轨走 {@link GenericEntityStore}，hybrid 走
 * {@link HybridEntityStore}；删记录级联附件（ES-3）。大载荷用 EntityBlobStore。租户隔离须头与授权。
 */
@RestController
public class GenericEntityEndpoint {

    /** Generic records collection path — 通用记录集合路径。 */
    public static final String RECORDS_PATH = JsonApi.BASE + "/entities/{entityKey}/records";

    /** One record by id — 按 id 的单条记录。 */
    public static final String RECORD_PATH = RECORDS_PATH + "/{id}";

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final EffectiveDeclarationService effective;
    private final GenericEntityStore tableStore;
    private final HybridEntityStore hybridStore;
    private final EntityBlobStore blobStore;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public GenericEntityEndpoint(
            EffectiveDeclarationService effective,
            GenericEntityStore tableStore,
            HybridEntityStore hybridStore,
            EntityBlobStore blobStore,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess) {
        this.effective = Objects.requireNonNull(effective, "effective");
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.hybridStore = Objects.requireNonNull(hybridStore, "hybridStore");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
    }

    /** List records via runtimeEntity + DeclarationAccess — 经 runtimeEntity + DeclarationAccess 列记录。 */
    @GetMapping(RECORDS_PATH)
    public RecordsDocument list(
            @PathVariable("entityKey") String entityKey,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "order", required = false) String order,
            @RequestParam(value = "filterField", required = false) String filterField,
            @RequestParam(value = "filterValue", required = false) String filterValue,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedEntity entity = requireEntity(entityKey, operator, tenantId);
        int capped = capLimit(limit);
        boolean ascending = parseOrder(order);
        boolean hasFilterField = filterField != null && !filterField.isBlank();
        boolean hasFilterValue = filterValue != null;
        if (hasFilterField != hasFilterValue) {
            throw new IllegalArgumentException("filterField and filterValue must be provided together");
        }
        String storeTenant = entity.tenantScoped() ? tenantId : null;
        List<Map<String, Object>> records;
        if (entity.storageMode() == EntityStorageMode.HYBRID) {
            if (blankToNull(sort) != null && !"record_id".equals(blankToNull(sort))
                    && !entity.primaryKey().name().equals(blankToNull(sort))) {
                throw new IllegalArgumentException(
                        "hybrid storageMode sort supports primary key / record_id only");
            }
            records = hybridStore.list(
                    entity, capped, storeTenant, ascending, blankToNull(filterField), filterValue);
        } else {
            records = tableStore.list(
                    entity, capped, blankToNull(sort), ascending, blankToNull(filterField), filterValue, storeTenant);
        }
        return new RecordsDocument(records);
    }

    /** Get one record; missing -> 404 — 读单条；缺失 404。 */
    @GetMapping(RECORD_PATH)
    public Map<String, Object> get(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedEntity entity = requireEntity(entityKey, operator, tenantId);
        String storeTenant = entity.tenantScoped() ? tenantId : null;
        return findOne(entity, id, storeTenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Upsert record; tenantScoped stamps tenant_id — 写入记录；租户隔离时盖章 tenant_id。 */
    @PutMapping(RECORD_PATH)
    public ResponseEntity<Void> put(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedEntity entity = requireEntity(entityKey, operator, tenantId);
        Map<String, Object> values = body == null ? Map.of() : body;
        Object bodyPk = values.get(entity.primaryKey().name());
        if (bodyPk != null && !id.equals(String.valueOf(bodyPk))) {
            throw new IllegalArgumentException("path id must match primary key field");
        }
        Map<String, Object> withPk = new LinkedHashMap<>(values);
        withPk.put(entity.primaryKey().name(), id);
        String storeTenant = entity.tenantScoped() ? tenantId : null;
        if (entity.storageMode() == EntityStorageMode.HYBRID) {
            hybridStore.save(entity, withPk, storeTenant);
        } else {
            tableStore.save(entity, withPk, storeTenant);
        }
        return ResponseEntity.noContent().build();
    }

    /** Delete record; fail-closed AuthZ via declaration permissions — 删除记录；声明权限失败关闭。 */
    @DeleteMapping(RECORD_PATH)
    public ResponseEntity<Void> delete(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedEntity entity = requireEntity(entityKey, operator, tenantId);
        String storeTenant = entity.tenantScoped() ? tenantId : null;
        boolean removed =
                entity.storageMode() == EntityStorageMode.HYBRID
                        ? hybridStore.deleteById(entity, id, storeTenant)
                        : tableStore.deleteById(entity, id, storeTenant);
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String blobTenant = entity.tenantScoped()
                ? tenantId.trim()
                : JdbcHybridEntityStore.PLATFORM_TENANT;
        blobStore.deleteForRecord(blobTenant, entityKey, id);
        return ResponseEntity.noContent().build();
    }

    private java.util.Optional<Map<String, Object>> findOne(RenderedEntity entity, String id, String storeTenant) {
        if (entity.storageMode() == EntityStorageMode.HYBRID) {
            return hybridStore.findById(entity, id, storeTenant);
        }
        return tableStore.findById(entity, id, storeTenant);
    }

    private RenderedEntity requireEntity(String entityKey, OperatorPrincipal operator, String tenantId) {
        if (TenantDeclarationContext.overlayRequested(tenantId)) {
            tenantAccess.requireGranted(operator, tenantId.trim());
        }
        RenderedEntity entity = effective
                .runtimeEntity(tenantId, entityKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        DeclarationAccess.require(
                operator,
                entity.permission(),
                entity.tenantScoped(),
                tenantId,
                tenantGuard,
                tenantAccess,
                AccessResource.of("entity", entityKey),
                AccessAction.of("access"));
        return entity;
    }

    private static int capLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static boolean parseOrder(String order) {
        if (order == null || order.isBlank() || "asc".equalsIgnoreCase(order.trim())) {
            return true;
        }
        if ("desc".equalsIgnoreCase(order.trim())) {
            return false;
        }
        throw new IllegalArgumentException("order must be asc or desc");
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * RecordsDocument — 记录列表：{@code records} 键装 camelCase 字段对象。
     */
    public record RecordsDocument(List<Map<String, Object>> records) {}
}
