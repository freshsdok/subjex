-- WP6-2: store a callback payload fingerprint on the payment aggregate (ADR 0018 D2).
-- WP6-2：在支付聚合上保存回调载荷指纹（ADR 0018 D2）。
-- Same callback_notification_id + same fingerprint must replay; a mismatch is rejected.
-- 同一回调通知标识且指纹一致则回放；不一致则拒绝。
-- No vendor-only types, no FKs, no second outbox or idempotency_claim reuse.
-- 无厂商专有类型，不加外键，不另起出箱，也不占用 idempotency_claim。

ALTER TABLE payment
    ADD callback_notification_fingerprint VARCHAR(128);
