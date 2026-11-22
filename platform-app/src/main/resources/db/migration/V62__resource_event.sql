-- Generic tenant-scoped share / open events (optional campaign or UTM if the client sends them).
-- 通用的租户内分享/打开事件（仅当客户端带上活动或 UTM 字段时才记录这些归因）。
-- Not a marketing suite, heatmap product, or industry-specific entity.
-- 不是营销套件、热力产品，也不是行业实体。
-- Time is when the platform received the event (occurred_at). Denied attempts are not stored here.
-- 时间为平台收到事件的时刻。此表不记录拒绝次数。

CREATE TABLE resource_event (
    event_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_subject_id VARCHAR(64),
    referrer VARCHAR(512),
    campaign VARCHAR(128),
    utm_source VARCHAR(128),
    utm_medium VARCHAR(128),
    utm_campaign VARCHAR(128),
    utm_content VARCHAR(128),
    utm_term VARCHAR(128),
    occurred_at TIMESTAMP NOT NULL,
    PRIMARY KEY (event_id)
);

CREATE INDEX resource_event_tenant_time ON resource_event (tenant_id, occurred_at);
CREATE INDEX resource_event_tenant_type ON resource_event (tenant_id, event_type, occurred_at);
CREATE INDEX resource_event_tenant_resource ON resource_event (tenant_id, resource_type, resource_id);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('resource-event.read', 'Read recorded share and open events in a granted tenant / 在已授权租户内查阅已记录的分享与打开事件');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'resource-event.read'),
    ('platform-reader', 'resource-event.read');
