package com.subjex.platform.app.entity;

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
 * GenericEntityEndpoint — 通用实体 REST：按 {@code entityKey} 对元数据驱动的表做 CRUD。
 * <p>
 * Paths under {@code /api/v1/entities/{entityKey}/records}. Does not replace the bespoke
 * {@code service-note/notes} list. When {@code tenantScoped: true}, requires {@code X-Tenant-Id}
 * (and operator–tenant grant) and isolates rows on physical {@code tenant_id}.
 * With non-blank {@code X-Tenant-Id}, resolves entity via {@link EffectiveDeclarationService#runtimeEntity}
 * (DB draft overlay when tableName+PK match classpath); otherwise classpath baseline.
 * 路径在 {@code /api/v1/entities/{entityKey}/records}。不替换专用的 {@code service-note/notes} 列表。
 * {@code tenantScoped: true} 时要求租户头与授权，并按物理列 {@code tenant_id} 隔离。
 * 带租户头时经 runtimeEntity 覆盖（表名+主键须一致）。
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
    private final GenericEntityStore store;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public GenericEntityEndpoint(
            EffectiveDeclarationService effective,
            GenericEntityStore store,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess) {
        this.effective = Objects.requireNonNull(effective, "effective");
        this.store = Objects.requireNonNull(store, "store");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
    }

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
        List<Map<String, Object>> records = store.list(
                entity, capped, blankToNull(sort), ascending, blankToNull(filterField), filterValue, storeTenant);
        return new RecordsDocument(records);
    }

    @GetMapping(RECORD_PATH)
    public Map<String, Object> get(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedEntity entity = requireEntity(entityKey, operator, tenantId);
        String storeTenant = entity.tenantScoped() ? tenantId : null;
        return store.findById(entity, id, storeTenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

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
        store.save(entity, withPk, storeTenant);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(RECORD_PATH)
    public ResponseEntity<Void> delete(
            @PathVariable("entityKey") String entityKey,
            @PathVariable("id") String id,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedEntity entity = requireEntity(entityKey, operator, tenantId);
        String storeTenant = entity.tenantScoped() ? tenantId : null;
        if (!store.deleteById(entity, id, storeTenant)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
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
