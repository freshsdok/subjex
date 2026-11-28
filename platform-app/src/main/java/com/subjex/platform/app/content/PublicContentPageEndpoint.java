package com.subjex.platform.app.content;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.content.ContentPageDocument;
import com.subjex.platform.contract.content.ContentPageSummary;
import com.subjex.platform.contract.storage.StoredObject;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PublicContentPageEndpoint — unauthenticated published reads under {@code /api/v1/public/content-pages}.
 * 无需登录阅读已发布内容页。草稿对外按不存在处理。
 * <p>
 * Tenant comes from {@code X-Tenant-Id} / {@code tenantId}, including the value filled from a bound
 * {@code Host} by {@code TenantHostResolutionFilter}.
 * 租户来自请求头或查询参数，也包括已绑定 Host 由过滤器填入的值。
 */
@RestController
public class PublicContentPageEndpoint {

    /** JSON base for public published content pages — 公开已发布内容页 JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/public/content-pages";

    private final PublicContentPageService pages;

    public PublicContentPageEndpoint(PublicContentPageService pages) {
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    @GetMapping(PATH)
    public List<ContentPageSummary> list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return pages.listPublished(resolveTenant(tenantHeader, tenantIdParam), limit);
    }

    @GetMapping(PATH + "/{slug}")
    public ContentPageDocument get(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("slug") String slug) {
        return pages.getPublished(resolveTenant(tenantHeader, tenantIdParam), slug);
    }

    @GetMapping(PATH + "/{slug}/media/{objectId}")
    public ResponseEntity<byte[]> media(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("slug") String slug,
            @PathVariable("objectId") String objectId) {
        StoredObject object = pages.publishedImage(resolveTenant(tenantHeader, tenantIdParam), slug, objectId);
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (object.mediaType() != null && !object.mediaType().isBlank()) {
            try {
                mediaType = MediaType.parseMediaType(object.mediaType());
            } catch (RuntimeException ignored) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(object.content());
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
}
