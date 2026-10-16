# web console image — optional local/eval (not production-hardened).
# 控制台镜像 — 可选本地/评估用（非生产加固）。
# Build from repository root / 在仓库根目录构建:
#   docker build -f web/Dockerfile -t subjex/web:0.1.0-SNAPSHOT .
# Runtime: set SUBJEX_API_BASE (compose default http://platform-app:8080).
# 运行时设置 SUBJEX_API_BASE（compose 默认 http://platform-app:8080）。

FROM node:20-bookworm-slim AS build
WORKDIR /app
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:20-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
# Default for local laptop against platform-app on the host; compose overrides.
# 本机默认指向宿主机 platform-app；compose 会覆盖。
ENV SUBJEX_API_BASE=http://127.0.0.1:8080
COPY --from=build /app/package.json /app/package-lock.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/next.config.ts ./
COPY --from=build /app/next-env.d.ts ./
USER node
EXPOSE 3000
CMD ["npm", "run", "start"]
