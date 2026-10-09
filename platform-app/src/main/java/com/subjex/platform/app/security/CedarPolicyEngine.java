package com.subjex.platform.app.security;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.loader.LibraryLoader;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.AuthorizationSuccessResponse;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.exception.AuthException;
import com.cedarpolicy.model.exception.InternalException;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.value.CedarList;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimBool;
import com.cedarpolicy.value.PrimString;
import com.cedarpolicy.value.Value;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * CedarPolicyEngine — {@link PolicyEngine} adapter over Cedar ({@code cedar-java} uber / FFI).
 * <p>
 * AuthZ-1d: selectable via {@code platform.authz.engine=cedar|sql} (default cedar).
 * Prefer {@link PolicyEngineFactory} for construction. Permission membership uses classpath policies
 * ({@code authz/baseline.cedar}); tenant grant + organization scope reuse
 * {@link AccessChecker} after Cedar allows. Native/FFI or policy load failure is fail-soft:
 * {@link AccessDecision#DENY_CEDAR_UNAVAILABLE} — the SQL default path stays untouched.
 * <p>
 * FFI: uber jar loads natives via JNE; container images must match linux/amd64 or arm64.
 * See {@code docs/authz/cedar-or-casbin-adr.md}.
 * Cedar 适配器；1b 不切换默认 Bean；原生库失败软拒绝。
 */
public final class CedarPolicyEngine implements PolicyEngine {

    public static final String DEFAULT_POLICY_RESOURCE = "authz/baseline.cedar";

    private static final String ACTION_INVOKE = "invoke";
    /** Cedar EntityUID when PolicyPrincipal subjectId is null/blank — 无主体时的哨兵 id。 */
    static final String UNAUTHENTICATED_SUBJECT_ID = "__unauthenticated__";
    private static final String TYPE_SUBJECT = "Subject";
    private static final String TYPE_ACTION = "Action";
    private static final String TYPE_RESOURCE = "Resource";

    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;
    private final PolicySet policySet;
    private final BasicAuthorizationEngine engine;
    private final EntityTypeName subjectType;
    private final EntityTypeName actionType;
    private final EntityTypeName resourceType;
    private final boolean nativeAvailable;
    private final String nativeFailureMessage;

    public CedarPolicyEngine(TenantGuard tenantGuard, OperatorTenantAccess tenantAccess) {
        this(tenantGuard, tenantAccess, DEFAULT_POLICY_RESOURCE);
    }

    public CedarPolicyEngine(
            TenantGuard tenantGuard, OperatorTenantAccess tenantAccess, String classpathPolicyResource) {
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        Objects.requireNonNull(classpathPolicyResource, "classpathPolicyResource");

        Init init = Init.tryCreate(classpathPolicyResource);
        this.nativeAvailable = init.ok;
        this.nativeFailureMessage = init.failureMessage;
        this.policySet = init.policySet;
        this.engine = init.engine;
        this.subjectType = init.subjectType;
        this.actionType = init.actionType;
        this.resourceType = init.resourceType;
    }

    /** Whether Cedar FFI + policies are ready — 原生库与策略是否可用。 */
    public boolean isNativeAvailable() {
        return nativeAvailable;
    }

    public Optional<String> nativeFailureMessage() {
        return Optional.ofNullable(nativeFailureMessage);
    }

    @Override
    public AccessDecision evaluate(
            PolicyPrincipal principal,
            String requiredPermission,
            AccessAction action,
            PolicyResource resource,
            PolicyContext context) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resource, "resource");
        PolicyContext ctx = context == null ? PolicyContext.unscoped() : context;
        String subjectId = principal == null ? null : principal.subjectId();

        if (requiredPermission == null || requiredPermission.isBlank()) {
            return AccessDecision.deny(
                    subjectId,
                    ctx.tenantId(),
                    ctx.orgScope(),
                    AccessResource.of(resource.kind(), resource.id()),
                    action,
                    null,
                    AccessDecision.DENY_PERMISSION_BLANK);
        }
        String permission = requiredPermission.trim();

        if (!nativeAvailable) {
            return AccessDecision.deny(
                    subjectId,
                    ctx.tenantId(),
                    ctx.orgScope(),
                    AccessResource.of(resource.kind(), resource.id()),
                    action,
                    permission,
                    AccessDecision.DENY_CEDAR_UNAVAILABLE);
        }

        String ctxTenant = ctx.tenantId();
        String resourceTenant = resource.attribute(PolicyResource.ATTR_TENANT_ID);
        if (ctxTenant != null
                && resourceTenant != null
                && !resourceTenant.isBlank()
                && !ctxTenant.equals(resourceTenant.trim())) {
            return AccessDecision.deny(
                    subjectId,
                    ctxTenant,
                    ctx.orgScope(),
                    AccessResource.of(resource.kind(), resource.id()),
                    action,
                    permission,
                    AccessDecision.DENY_TENANT_MISMATCH);
        }

        try {
            if (!cedarAllowsPermission(principal, permission, action, resource, ctx)) {
                return AccessDecision.deny(
                        subjectId,
                        ctx.tenantId(),
                        ctx.orgScope(),
                        AccessResource.of(resource.kind(), resource.id()),
                        action,
                        null,
                        AccessDecision.DENY_PERMISSION_MISSING);
            }
        } catch (AuthException | RuntimeException ex) {
            return AccessDecision.deny(
                    subjectId,
                    ctx.tenantId(),
                    ctx.orgScope(),
                    AccessResource.of(resource.kind(), resource.id()),
                    action,
                    permission,
                    AccessDecision.DENY_CEDAR_ERROR);
        }

        String tenantId = ctxTenant != null ? ctxTenant : resourceTenant;
        return AccessChecker.evaluate(
                SqlRbacPolicyEngine.bridge(principal),
                permission,
                ctx.tenantScoped(),
                tenantId,
                tenantGuard,
                tenantAccess,
                AccessResource.of(resource.kind(), resource.id()),
                action,
                ctx.orgScope(),
                resource.organizationId());
    }

    private boolean cedarAllowsPermission(
            PolicyPrincipal principal,
            String permission,
            AccessAction action,
            PolicyResource resource,
            PolicyContext ctx)
            throws AuthException {
        String sid = principal == null || principal.subjectId() == null || principal.subjectId().isBlank()
                ? UNAUTHENTICATED_SUBJECT_ID
                : principal.subjectId();
        EntityUID principalUid = subjectType.of(sid);
        EntityUID actionUid = actionType.of(ACTION_INVOKE);
        String resourceId =
                resource.kind().isEmpty() && resource.id().isEmpty()
                        ? "_"
                        : resource.kind() + ":" + resource.id();
        EntityUID resourceUid = resourceType.of(resourceId);

        CedarList permissions = new CedarList();
        if (principal != null) {
            for (String p : principal.permissionNames()) {
                if (p != null && !p.isBlank()) {
                    permissions.add(new PrimString(p.trim()));
                }
            }
        }

        Set<Entity> entities = new LinkedHashSet<>();
        entities.add(new Entity(principalUid, Map.of("permissions", permissions), Set.of()));
        entities.add(new Entity(actionUid));
        Map<String, Value> resourceAttrs = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : resource.attributes().entrySet()) {
            resourceAttrs.put(e.getKey(), new PrimString(e.getValue()));
        }
        entities.add(new Entity(resourceUid, resourceAttrs, Set.of()));

        Map<String, Value> cedarContext = new LinkedHashMap<>();
        cedarContext.put("requiredPermission", new PrimString(permission));
        cedarContext.put("accessAction", new PrimString(action.name()));
        cedarContext.put("tenantScoped", new PrimBool(ctx.tenantScoped()));
        if (ctx.tenantId() != null) {
            cedarContext.put("tenantId", new PrimString(ctx.tenantId()));
        }
        OrganizationScope org = ctx.orgScope();
        if (org != null) {
            cedarContext.put("orgMode", new PrimString(org.mode()));
            CedarList orgIds = new CedarList();
            for (String id : org.organizationIds()) {
                orgIds.add(new PrimString(id));
            }
            cedarContext.put("organizationIds", orgIds);
            CedarList roots = new CedarList();
            for (String id : org.rootOrganizationIds()) {
                roots.add(new PrimString(id));
            }
            cedarContext.put("rootOrganizationIds", roots);
        }

        AuthorizationRequest request =
                new AuthorizationRequest(principalUid, actionUid, resourceUid, cedarContext);
        AuthorizationResponse response = engine.isAuthorized(request, policySet, entities);
        if (response.success.isEmpty()) {
            return false;
        }
        AuthorizationSuccessResponse success = response.success.get();
        return success.isAllowed();
    }

    private static final class Init {
        final boolean ok;
        final String failureMessage;
        final PolicySet policySet;
        final BasicAuthorizationEngine engine;
        final EntityTypeName subjectType;
        final EntityTypeName actionType;
        final EntityTypeName resourceType;

        private Init(
                boolean ok,
                String failureMessage,
                PolicySet policySet,
                BasicAuthorizationEngine engine,
                EntityTypeName subjectType,
                EntityTypeName actionType,
                EntityTypeName resourceType) {
            this.ok = ok;
            this.failureMessage = failureMessage;
            this.policySet = policySet;
            this.engine = engine;
            this.subjectType = subjectType;
            this.actionType = actionType;
            this.resourceType = resourceType;
        }

        static Init tryCreate(String classpathPolicyResource) {
            try {
                LibraryLoader.loadLibrary();
            } catch (Throwable t) {
                return unavailable(formatThrowable(t));
            }
            try {
                PolicySet policies = PolicySet.parsePolicies(readClasspath(classpathPolicyResource));
                return new Init(
                        true,
                        null,
                        policies,
                        new BasicAuthorizationEngine(),
                        EntityTypeName.parse(TYPE_SUBJECT).orElseThrow(),
                        EntityTypeName.parse(TYPE_ACTION).orElseThrow(),
                        EntityTypeName.parse(TYPE_RESOURCE).orElseThrow());
            } catch (IOException | InternalException | RuntimeException ex) {
                return unavailable(formatThrowable(ex));
            }
        }

        private static Init unavailable(String message) {
            return new Init(false, message, null, null, null, null, null);
        }

        private static String formatThrowable(Throwable t) {
            String msg = t.getMessage();
            return t.getClass().getSimpleName() + ": " + (msg == null ? t.toString() : msg);
        }

        private static String readClasspath(String resource) throws IOException {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = CedarPolicyEngine.class.getClassLoader();
            }
            try (InputStream in = cl.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("missing classpath resource: " + resource);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
