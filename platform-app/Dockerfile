# platform-app image — Platform host process / 平台宿主进程
# Build from the repository root / 在仓库根目录构建:
#   docker build -f platform-app/Dockerfile -t subjex/platform-app:0.1.0-SNAPSHOT .
# The image name matches deploy/k8s/platform-app.yaml. Nothing in mvn test builds or runs this file.
# 镜像名与 deploy/k8s/platform-app.yaml 一致。mvn test 不构建也不运行这个文件。

# Stage 1, build: compile the reactor modules platform-app needs and package the runnable jar.
# 第一阶段 build：编译 platform-app 需要的模块，打出可运行 jar。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY platform-contract/pom.xml platform-contract/
COPY platform-app/pom.xml platform-app/
COPY sample-consumer/pom.xml sample-consumer/
COPY model-gateway/pom.xml model-gateway/
COPY form-render/pom.xml form-render/
COPY page-language/pom.xml page-language/
COPY page-skin/pom.xml page-skin/
COPY deploy/k8s deploy/k8s
COPY platform-contract/src platform-contract/src
COPY platform-app/src platform-app/src
COPY sample-consumer/src sample-consumer/src
COPY model-gateway/src model-gateway/src
COPY form-render/src form-render/src
COPY page-language/src page-language/src
COPY page-skin/src page-skin/src
RUN mvn -B -q -DskipTests -pl platform-app -am package \
    && cp platform-app/target/platform-app-*-exec.jar /workspace/platform-app.jar

# Stage 2, runtime: a JRE and the jar, run as an unprivileged user.
# 第二阶段 runtime：只有 JRE 和 jar，以无特权用户运行。
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 subjex \
    && useradd --system --uid 10001 --gid subjex --home-dir /app --shell /usr/sbin/nologin subjex \
    && mkdir -p /app \
    && chown subjex:subjex /app
WORKDIR /app
COPY --from=build --chown=subjex:subjex /workspace/platform-app.jar /app/platform-app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/platform-app.jar"]
