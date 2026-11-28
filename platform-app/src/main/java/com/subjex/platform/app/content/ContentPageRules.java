package com.subjex.platform.app.content;

import com.subjex.platform.contract.content.ContentBlock;
import com.subjex.platform.contract.content.ContentBlockType;
import com.subjex.platform.contract.content.ContentTextFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * ContentPageRules — slug, title, and block validation for the small generic block set.
 * 别名、标题与小型通用内容块的校验。
 */
final class ContentPageRules {

    static final int SLUG_MAX = 64;
    static final int TITLE_MAX = 200;
    static final int TEXT_MAX = 16384;
    static final int HREF_MAX = 2048;
    static final int ALT_MAX = 256;
    static final int MAX_BLOCKS = 40;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern HTTP = Pattern.compile("^https?://[^\\s]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern APP_PATH = Pattern.compile("^/pages/[A-Za-z0-9._/-]+$");

    private ContentPageRules() {}

    static String requireSlug(String raw) {
        String slug = requireText(raw, "slug", SLUG_MAX).toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug must be lowercase letters, digits, and single hyphens");
        }
        return slug;
    }

    static String requireTitle(String raw) {
        return requireText(raw, "title", TITLE_MAX);
    }

    static String requireActor(String raw) {
        return requireText(raw, "actor subject id", 64);
    }

    static String requirePageId(String raw) {
        return requireText(raw, "page id", 64);
    }

    static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    static List<ContentBlock> normalizeBlocks(List<ContentBlock> raw, Predicate<String> objectInTenant) {
        List<ContentBlock> source = raw == null ? List.of() : raw;
        if (source.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("too many content blocks");
        }
        List<ContentBlock> out = new ArrayList<>(source.size());
        int index = 0;
        for (ContentBlock block : source) {
            if (block == null || block.type() == null) {
                throw new IllegalArgumentException("block type is required");
            }
            out.add(normalizeBlock(block, objectInTenant, index++));
        }
        return List.copyOf(out);
    }

    private static ContentBlock normalizeBlock(
            ContentBlock block, Predicate<String> objectInTenant, int index) {
        return switch (block.type()) {
            case TITLE -> new ContentBlock(
                    block.blockId(),
                    ContentBlockType.TITLE,
                    requireText(block.text(), "title text", TITLE_MAX),
                    null,
                    null,
                    null,
                    null,
                    null);
            case TEXT -> {
                ContentTextFormat format = block.format() == null ? ContentTextFormat.PLAIN : block.format();
                yield new ContentBlock(
                        block.blockId(),
                        ContentBlockType.TEXT,
                        requireText(block.text(), "text", TEXT_MAX),
                        format,
                        null,
                        null,
                        null,
                        null);
            }
            case IMAGE -> {
                String objectId = requireText(block.objectId(), "object id", 64);
                if (objectInTenant != null && !objectInTenant.test(objectId)) {
                    throw new IllegalArgumentException("image object is not in this tenant");
                }
                yield new ContentBlock(
                        block.blockId(),
                        ContentBlockType.IMAGE,
                        null,
                        null,
                        objectId,
                        null,
                        optionalText(block.alt(), "alt", ALT_MAX),
                        null);
            }
            case LINK -> new ContentBlock(
                    block.blockId(),
                    ContentBlockType.LINK,
                    requireText(block.text(), "link label", TITLE_MAX),
                    null,
                    null,
                    requireHref(block.href()),
                    null,
                    null);
        };
    }

    static String publicMediaUrl(String slug, String objectId, String tenantId) {
        return PublicContentPageEndpoint.PATH
                + "/"
                + slug
                + "/media/"
                + objectId
                + "?tenantId="
                + tenantId;
    }

    static ContentBlock withPublicMedia(ContentBlock block, String slug, String tenantId) {
        if (block.type() != ContentBlockType.IMAGE || block.objectId() == null) {
            return block;
        }
        return new ContentBlock(
                block.blockId(),
                block.type(),
                block.text(),
                block.format(),
                block.objectId(),
                block.href(),
                block.alt(),
                publicMediaUrl(slug, block.objectId(), tenantId));
    }

    private static String requireHref(String raw) {
        String href = requireText(raw, "href", HREF_MAX);
        if (HTTP.matcher(href).matches() || APP_PATH.matcher(href).matches()) {
            return href;
        }
        throw new IllegalArgumentException("href must be an http(s) URL or an in-app /pages/ path");
    }

    private static String requireText(String raw, String label, int max) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String value = raw.trim();
        if (value.length() > max) {
            throw new IllegalArgumentException(label + " too long");
        }
        return value;
    }

    private static String optionalText(String raw, String label, int max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > max) {
            throw new IllegalArgumentException(label + " too long");
        }
        return value;
    }
}
