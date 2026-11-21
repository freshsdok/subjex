package com.subjex.platform.app.storage;

import java.time.Duration;

/**
 * ObjectDownloadUrlIssuer — backend-specific signed URL after the authorizer has already allowed.
 * 授权判定已允许之后，由后端签发签名 URL（S3 预签名或本地 HMAC）。
 */
@FunctionalInterface
public interface ObjectDownloadUrlIssuer {

    IssuedDownloadLink issue(String tenantId, String objectKey, Duration ttl);
}
