package com.subjex.platform.contract.discovery;

import java.util.List;

/**
 * ServiceRoster — 服务名册：能列出全部已登记端点的登记簿。
 * <p>
 * The page that lists services needs every endpoint, not only one resolved name. The two-operation
 * {@link ServiceRegistry} port is unchanged; a roster adds the read of the whole list.
 * 列服务的页面需要全部端点，而不只是解析一个名字。两操作的 {@link ServiceRegistry} 端口不变；名册多一个读全表。
 */
public interface ServiceRoster extends ServiceRegistry {

    /**
     * @return every registered endpoint / 全部已登记的端点
     */
    List<ServiceEndpoint> endpoints();
}
