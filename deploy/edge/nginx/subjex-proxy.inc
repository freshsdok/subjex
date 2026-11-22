# Shared reverse-proxy headers for platform-app / 反代 platform-app 的共用头
# Upstream is platform_app (business port 8080 only; never 8089/8090/8091).
# 上游是 platform_app（仅业务口 8080；绝不用 8089/8090/8091）。
# Install next to the included path, or copy to /etc/nginx/conf.d/subjex-proxy.inc
# 安装到 include 路径，或复制到 /etc/nginx/conf.d/subjex-proxy.inc
proxy_pass http://platform_app;
proxy_http_version 1.1;
# Tenant custom hosts: keep the original public hostname in Host. This cut in
# platform-app does not read X-Forwarded-Host. This include does not provision DNS or TLS.
# 租户独立域名：请把原来的公开主机名放在 Host 里。平台本切口不采信 X-Forwarded-Host。
# 本文件不办理 DNS，也不签发证书。
proxy_set_header Host              $host;
proxy_set_header X-Real-IP         $remote_addr;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host  $host;
# Forward correlation / W3C trace (ADR 0004 / 0021) / 转发关联与 W3C 跟踪头
proxy_set_header X-Correlation-Id  $http_x_correlation_id;
proxy_set_header X-Request-Id      $http_x_request_id;
proxy_set_header traceparent       $http_traceparent;
proxy_set_header tracestate        $http_tracestate;
