# Docker for the vendor startup tests / 厂商启动测试用的 Docker

`VendorStartupTest` (module `platform-app`) starts `platform-app` once on MySQL and once on PostgreSQL.
Each database is a throwaway Testcontainers container (`mysql:8.4`, `postgres:16-alpine`).
`VendorStartupTest`（模块 `platform-app`）把 `platform-app` 分别在 MySQL 和 PostgreSQL 上各启动一次。
每个库都是用完即弃的 Testcontainers 容器（`mysql:8.4`、`postgres:16-alpine`）。

## What you need / 需要什么

- A running Docker daemon that the user running `mvn test` can reach (`docker info` succeeds).
  Testcontainers finds it the usual way: `DOCKER_HOST`, or the default socket.
  一个正在运行、执行 `mvn test` 的用户能连上的 Docker 守护进程（`docker info` 成功）。
  Testcontainers 按常规方式找到它：`DOCKER_HOST` 或默认套接字。
- Network access to pull the two images once, or the images already present locally.
  能拉一次这两个镜像，或者本机已有这两个镜像。

Without Docker the two tests are skipped, not faked. A skip is not a pass against a live database.
没有 Docker 时这两个测试跳过，不用别的库冒充。跳过不等于已经对着真实数据库通过。

## What the test guarantees / 测试保证什么

- Container settings are passed as command-line arguments (`--key=value`).
  They outrank `application.yml` and environment variables, so the test never falls back to a database on `127.0.0.1:5432`.
  The test also checks that the open connection's URL is the container's URL.
  容器设置以命令行参数（`--key=value`）传入，优先于 `application.yml` 和环境变量，
  所以测试不会退回到 `127.0.0.1:5432` 上的数据库。测试还核对打开的连接 URL 就是容器的 URL。
- The `local` profile (which seeds a LOCAL ONLY operator) is active only against the container database.
  `local` profile（写入仅限本地的操作员）只对着容器库启用。
- `server.port=0`: the operating system picks the HTTP port. Self-registration writes that bound port into `service_endpoint`, and the test checks it.
  `server.port=0`：由操作系统挑 HTTP 端口。自登记把这个已绑定端口写进 `service_endpoint`，测试会核对。

Run only these tests / 只跑这些测试：

```
mvn -pl platform-app -am test -Dtest=VendorStartupTest -Dsurefire.failIfNoSpecifiedTests=false
```
