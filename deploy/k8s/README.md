# Kubernetes manifests / Kubernetes 清单

These files describe how `platform-app` and `sample-consumer` would run as two Deployments.
They are not applied. The build does not call `kubectl`, and it does not build container images.
There is no cluster in the test path, and no HorizontalPodAutoscaler.

这些文件描述 `platform-app` 和 `sample-consumer` 作为两个 Deployment 时的样子。
它们不会被应用。构建不调用 `kubectl`，也不构建容器镜像。
测试路径上没有集群，也没有 HorizontalPodAutoscaler。

Image names (`subjex/platform-app`, `subjex/sample-consumer`) are placeholders.

镜像名（`subjex/platform-app`、`subjex/sample-consumer`）是占位符。
