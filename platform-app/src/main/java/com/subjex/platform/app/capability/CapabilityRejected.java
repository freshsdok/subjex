package com.subjex.platform.app.capability;

/**
 * CapabilityRejected — 能力目录/执行拒绝：未知键、坏 YAML、缺输入等失败关闭。
 */
public final class CapabilityRejected extends RuntimeException {

    public CapabilityRejected(String message) {
        super(message);
    }
}
