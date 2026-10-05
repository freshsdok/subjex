# Node 4 progress — 节点四进度

Slice log — 切片记录:

4a. [done] ARCHITECTURE §13: live DB proof (existing VendorStartupTest), entry-gateway process, coarse in-process RateLimitPort, compose path; explicitly defer dynamic routing / TLS / multi-upstream.
4b. [done] `entry-gateway` module: reverse proxy + GatewayRateLimit (RateLimitPort) + 6 tests (identity, limit window, forward/429/actuator).

4c. [done] Dockerfile, k8s stub, deploy/compose/docker-compose.yml; ContainerImageTest covers entry-gateway; README section.

4d. [done] VendorStartupTest 2/2 on live MySQL+PostgreSQL; gateway jar smoke: forward 200 then 429 at permits=3.
4e. [next] Push to GitHub.

