# entry-gateway image — reproducible multi-stage build (non-root uid 10001).
# 可复现多阶段构建（非 root uid 10001）。
# Build from repository root / 在仓库根目录构建:
#   docker build -f entry-gateway/Dockerfile -t subjex/entry-gateway:0.1.0-SNAPSHOT .
# CI image-scan builds these three Dockerfiles; mvn test does not.
# CI 镜像扫描会构建这三个 Dockerfile；mvn test 不构建。

# Stage 1, build: reactor modules needed by entry-gateway.
# 第一阶段 build：编译 entry-gateway 需要的 reactor 模块。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY platform-contract/pom.xml platform-contract/
COPY platform-app/pom.xml platform-app/
COPY sample-consumer/pom.xml sample-consumer/
COPY model-gateway/pom.xml model-gateway/
COPY form-render/pom.xml form-render/
COPY entity-declare/pom.xml entity-declare/
COPY page-declare/pom.xml page-declare/
COPY page-language/pom.xml page-language/
COPY page-skin/pom.xml page-skin/
COPY entry-gateway/pom.xml entry-gateway/
COPY subjex-outbox-kafka/pom.xml subjex-outbox-kafka/
COPY tools/subjex-init/pom.xml tools/subjex-init/
COPY deploy/k8s deploy/k8s
COPY platform-contract/src platform-contract/src
COPY platform-app/src platform-app/src
COPY sample-consumer/src sample-consumer/src
COPY model-gateway/src model-gateway/src
COPY form-render/src form-render/src
COPY entity-declare/src entity-declare/src
COPY page-declare/src page-declare/src
COPY page-language/src page-language/src
COPY page-skin/src page-skin/src
COPY entry-gateway/src entry-gateway/src
RUN mvn -B -q -DskipTests -pl entry-gateway -am package \
    && cp entry-gateway/target/entry-gateway-*-exec.jar /workspace/entry-gateway.jar

# Stage 2, runtime: JRE + jar as unprivileged user.
# 第二阶段 runtime：JRE + jar，无特权用户。
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 subjex \
    && useradd --system --uid 10001 --gid subjex --home-dir /app --shell /usr/sbin/nologin subjex \
    && mkdir -p /app \
    && chown subjex:subjex /app
WORKDIR /app
COPY --from=build --chown=subjex:subjex /workspace/entry-gateway.jar /app/entry-gateway.jar
USER 10001:10001
EXPOSE 8088
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/entry-gateway.jar"]
