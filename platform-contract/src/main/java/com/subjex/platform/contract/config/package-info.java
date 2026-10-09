/**
 * Configuration source — 配置来源：按名字读取一个配置值，并说清这个值从哪一层来。
 * <p>
 * The default source is this process's application configuration. A stored override layer may override
 * one named key per namespace (database in platform-app, memory in tests). {@link ConfigCenterPort} is the
 * Item 5 lifecycle surface (list/get/put with revision). Namespaces default to {@link ConfigNamespaces#DEFAULT}.
 * ETag/poll is Config-5c. See {@code docs/config/independent-config-center-plan.md}. No separate config microservice in the MVP.
 * 默认来源是本进程应用配置。覆盖层按命名空间压过键；{@link ConfigCenterPort} 带修订号。ETag/轮询见 Config-5c。
 */
package com.subjex.platform.contract.config;
