package com.subjex.platform.app.discovery;

/**
 * ListedService — 名单上的一项：服务名、地址，以及同一个状态词。
 * <p>
 * The page and the list endpoint both use this word, so they cannot disagree.
 * 页面和名单接口用同一个词，因此不会各说各的。
 */
public record ListedService(String serviceName, String host, int port, String status) {}
