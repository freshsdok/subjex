package com.subjex.platform.app.admin;

/**
 * AdminHealth — 管理台健康：存活与就绪两个探针的状态名。
 * <p>
 * This is the operator view. The anonymous probe URLs stay free of extra detail.
 * 这是操作员看到的视图。匿名探针地址本身不附加更多细节。
 */
public record AdminHealth(String liveness, String readiness) {
}
