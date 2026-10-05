package com.subjex.platform.app.deploy;

import com.subjex.platform.app.api.JsonApi;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DeployApiEndpoint — 清单接口：{@link DeployPage} 的 JSON 孪生，工作负载名、镜像、两条探针路径和内存上限。
 * <p>
 * Read from the same {@link ManifestCatalog}. These manifests are not applied to a cluster; there is no
 * cluster status here either. The memory limit is the raw quantity as written, e.g. {@code 512Mi}.
 * 读的是同一个 {@link ManifestCatalog}。这些清单没有应用到任何集群，这里也没有集群状态。
 * 内存上限是按字面写的数量，例如 {@code 512Mi}。
 */
@RestController
public class DeployApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/deploy";

    private final ManifestCatalog catalog;

    public DeployApiEndpoint(ManifestCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping(PATH)
    public DeployDocument deploy() {
        List<WorkloadDocument> workloads = catalog.list().stream()
                .map(workload -> new WorkloadDocument(
                        workload.name(),
                        workload.image(),
                        workload.livenessPath(),
                        workload.readinessPath(),
                        workload.memoryLimit()))
                .toList();
        return new DeployDocument(false, workloads);
    }

    /**
     * DeployDocument — 清单文档：{@code applied} 恒为 false（没有应用到集群），以及清单里的工作负载。
     */
    public record DeployDocument(boolean applied, List<WorkloadDocument> workloads) {}

    /**
     * WorkloadDocument — 一个工作负载：名字、镜像字符串、存活路径、就绪路径、内存上限（原样）。
     */
    public record WorkloadDocument(
            String name, String image, String livenessPath, String readinessPath, String memoryLimit) {}
}
