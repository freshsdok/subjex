package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.ObjectDescriptor;
import com.subjex.platform.app.storage.ObjectUploadRejectedException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * EndUserObjectEndpoint — WP7-2 multipart upload and list under {@code /api/v1/end-user/objects}.
 * 终端对象上传与列表。下载仍走既有 {@code /download-links}。本切片不做分块上传。
 */
@RestController
public class EndUserObjectEndpoint {

    /** JSON/multipart base for end-user objects — 终端对象根路径. */
    public static final String PATH = JsonApi.BASE + "/end-user/objects";

    private final EndUserObjectService objects;

    public EndUserObjectEndpoint(EndUserObjectService objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    @GetMapping(PATH)
    public ListDocument list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        int lim = limit == null ? EndUserObjectService.DEFAULT_LIST_LIMIT : limit;
        int off = offset == null ? 0 : offset;
        List<ObjectDocument> items = objects
                .list(principal, resolveTenant(tenantHeader, tenantIdParam), lim, off)
                .stream()
                .map(ObjectDocument::from)
                .toList();
        return new ListDocument(items, lim, off);
    }

    @PostMapping(path = PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ObjectDocument upload(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestPart("file") MultipartFile file)
            throws IOException {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("multipart part 'file' is required");
        }
        String tenant = resolveTenant(tenantHeader, tenantIdParam);
        objects.rejectIfOversize(principal, tenant, file.getSize());
        EndUserObjectService.UploadedObject uploaded = objects.upload(
                principal,
                tenant,
                file.getBytes(),
                file.getContentType());
        return ObjectDocument.from(uploaded);
    }

    private static String resolveTenant(String header, String param) {
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (param != null && !param.isBlank()) {
            return param.trim();
        }
        return null;
    }

    /** Paginated list — 分页列表. */
    public record ListDocument(List<ObjectDocument> objects, int limit, int offset) {}

    /** Object JSON — 对象文档. */
    public record ObjectDocument(
            String objectId,
            String objectKey,
            String ownerSubjectId,
            Instant createdAt,
            String mediaType,
            Integer sizeBytes) {
        static ObjectDocument from(EndUserObjectService.UploadedObject uploaded) {
            ObjectDescriptor d = uploaded.descriptor();
            return new ObjectDocument(
                    d.objectId(),
                    d.objectKey(),
                    d.ownerSubjectId(),
                    d.createdAt(),
                    uploaded.mediaType(),
                    uploaded.sizeBytes());
        }

        static ObjectDocument from(EndUserObjectService.ListedObject listed) {
            ObjectDescriptor d = listed.descriptor();
            return new ObjectDocument(
                    d.objectId(),
                    d.objectKey(),
                    d.ownerSubjectId(),
                    d.createdAt(),
                    listed.mediaType(),
                    null);
        }
    }
}
