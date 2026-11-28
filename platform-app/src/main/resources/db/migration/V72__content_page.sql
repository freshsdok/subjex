-- Tenant-scoped public content pages (structured blocks, draft/published).
-- 按租户的公开内容页（结构化块、草稿/已发布）。
-- This is a reusable landing-page surface, not a marketing CMS, blog, or theme store.
-- 这是可复用的落地页表面，不是营销 CMS、博客或主题商店。
-- Reserved tenant platform is never a business home. Shared by MySQL 8.4 and PostgreSQL 16 style.
-- 保留租户 platform 不是业务归属。MySQL 8.4 与 PostgreSQL 16 共用风格。

CREATE TABLE content_page (
    page_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    slug VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    page_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    created_by_subject_id VARCHAR(64),
    updated_by_subject_id VARCHAR(64),
    PRIMARY KEY (page_id)
);

CREATE UNIQUE INDEX content_page_tenant_slug
    ON content_page (tenant_id, slug);

CREATE INDEX content_page_tenant_updated
    ON content_page (tenant_id, updated_at);

CREATE TABLE content_page_block (
    block_id VARCHAR(64) NOT NULL,
    page_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL,
    block_type VARCHAR(16) NOT NULL,
    text_body VARCHAR(16384),
    text_format VARCHAR(16),
    object_id VARCHAR(64),
    href VARCHAR(2048),
    alt_text VARCHAR(256),
    PRIMARY KEY (block_id)
);

CREATE INDEX content_page_block_page_order
    ON content_page_block (tenant_id, page_id, sort_order);

CREATE INDEX content_page_block_image_object
    ON content_page_block (tenant_id, object_id);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('content.read', 'Read tenant content pages including drafts / 查阅租户内容页（含草稿）'),
    ('content.write', 'Create, edit, publish, or unpublish tenant content pages / 创建、编辑、发布或取消发布租户内容页');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'content.read'),
    ('platform-operator', 'content.write'),
    ('platform-reader', 'content.read');
