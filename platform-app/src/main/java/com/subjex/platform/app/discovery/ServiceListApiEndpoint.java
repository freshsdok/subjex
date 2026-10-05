package com.subjex.platform.app.discovery;

import com.subjex.platform.app.api.JsonApi;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ServiceListApiEndpoint — 服务名单接口：{@link ServiceListPage} 的 JSON 孪生，名字、地址，以及 up 或 unknown。
 * <p>
 * The status word is folded exactly as the page folds it: {@code up} or {@code unknown}, nothing else.
 * 状态词按页面同样的方式归并：只有 {@code up} 或 {@code unknown}。
 */
@RestController
public class ServiceListApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/services";

    private final ServiceCatalog catalog;

    public ServiceListApiEndpoint(ServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping(PATH)
    public ServiceListDocument services() {
        List<ServiceDocument> services = catalog.list().stream()
                .map(service -> new ServiceDocument(
                        service.serviceName(),
                        service.host(),
                        service.port(),
                        AddressProbe.UP.equals(service.status()) ? AddressProbe.UP : AddressProbe.UNKNOWN))
                .toList();
        return new ServiceListDocument(services);
    }

    /**
     * ServiceListDocument — 服务名单文档：登记的服务。
     */
    public record ServiceListDocument(List<ServiceDocument> services) {}

    /**
     * ServiceDocument — 名单上的一项：服务名、主机、端口、状态词（up / unknown）。
     */
    public record ServiceDocument(String serviceName, String host, int port, String status) {}
}
