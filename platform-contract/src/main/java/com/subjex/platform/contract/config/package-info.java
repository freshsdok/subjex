/**
 * Configuration source — 配置来源：按名字读取一个配置值，并说清这个值从哪一层来。
 * <p>
 * The default source is this process's application configuration. An in-memory source may override one named key.
 * platform-app stores that override and serves it over HTTP. There is no separate configuration server.
 * 默认来源是本进程的应用配置。内存来源可以压过一个具名键。
 * platform-app 保存这份覆盖并用 HTTP 提供。没有单独的配置服务器。
 */
package com.subjex.platform.contract.config;
